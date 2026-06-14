package com.aivca.model.session;

import com.aivca.model.message.ConnectionInitPayload;
import com.aivca.model.message.StatusUpdatePayload;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 单个客户端会话 —— 管理对话上下文、设备状态、视觉缓存和对话历史。
 *
 * 参考 EchoMind 的三层记忆架构。history/summary/idioms 双写：内存 + Redis（TTL 30min）。
 */
@Slf4j
@Data
public class ConversationSession {

    private static final Duration REDIS_TTL = Duration.ofMinutes(30);

    private final String sessionId;
    private final Instant createdAt;
    private Instant lastActiveAt;

    private ConnectionInitPayload.DeviceInfo deviceInfo;
    private boolean cameraEnabled;
    private boolean microphoneEnabled;
    private String cameraDeviceId;
    private String microphoneDeviceId;
    private String cachedVisionDescription;
    private String cachedImageChecksum;
    private Episode currentEpisode;
    private int visionGeneration;

    private final java.util.concurrent.BlockingQueue<TriggerEvent> eventQueue =
            new java.util.concurrent.LinkedBlockingQueue<>(100);
    private transient Thread consumerThread;
    private final List<String> bufferedFrames = java.util.Collections.synchronizedList(new ArrayList<>());
    private int conversationRound;
    private final List<ConversationTurn> history = new ArrayList<>();
    private String conversationSummary;
    private final List<String> usedIdioms = Collections.synchronizedList(new ArrayList<>());

    /** 是否已发送过欢迎消息 */
    private boolean welcomed;

    /** 上次视觉自动回复时间（冷却 15 秒） */
    private Instant lastVisionResponseAt;

    /** 上次视觉分析时间（限流：每 3 秒最多分析 1 次） */
    private Instant lastVisionAnalysisAt;

    // ==================== 压缩策略常量 ====================
    static final int COMPRESS_AT = 8;
    static final int KEEP_RAW = 3;
    private static final int MAX_HISTORY = 20;

    private StatusUpdatePayload.State currentState = StatusUpdatePayload.State.idle;

    /** Redis 双写（由 SessionManager 注入） */
    private transient RedisTemplate<String, Object> redis;

    public ConversationSession() {
        this.sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.createdAt = Instant.now();
        this.lastActiveAt = Instant.now();
    }

    public void touch() {
        this.lastActiveAt = Instant.now();
    }

    // ==================== 帧缓冲 ====================

    /** WS 线程直接写：说话期间累积帧 */
    public void addBufferedFrames(java.util.List<String> frames) {
        bufferedFrames.addAll(frames);
    }

    /** Consumer 线程取走并清空累积帧 */
    public java.util.List<String> getAndClearBufferedFrames() {
        java.util.List<String> copy = new ArrayList<>(bufferedFrames);
        bufferedFrames.clear();
        return copy;
    }

    // ==================== 对话历史 ====================

    /**
     * 追加一轮对话记录。
     *
     * @param userText      用户消息文本
     * @param assistantText AI 回复文本（可为空，后续通过 {@link #updateLastAssistantText} 回填）
     * @param agentType     处理本轮的 Agent 名称（如 "greeting", "technical"）
     */
    public void addTurn(String userText, String assistantText, String agentType) {
        history.add(new ConversationTurn(conversationRound++, userText, assistantText, agentType, Instant.now()));
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        syncHistoryToRedis();
    }

    /**
     * 回填最近一轮的 AI 回复文本（STT 完成时 assistant 为空，Agent 回复后补写）。
     */
    public void updateLastAssistantText(String assistantText) {
        if (history.isEmpty()) return;
        ConversationTurn last = history.get(history.size() - 1);
        last.setAssistantText(assistantText);
        syncHistoryToRedis();
    }

    public void updateLastAgentType(String agentType) {
        if (history.isEmpty()) return;
        ConversationTurn last = history.get(history.size() - 1);
        last.setAgentType(agentType);
        syncHistoryToRedis();
    }

    // ==================== 成语接龙追踪 ====================

    /** 追加一个已用成语 */
    public void addUsedIdiom(String idiom) {
        if (idiom != null && !idiom.isEmpty()) {
            usedIdioms.add(idiom);
            syncIdiomToRedis(idiom);
        }
    }

    /** 获取已用成语列表（只读） */
    public List<String> getUsedIdioms() {
        return List.copyOf(usedIdioms);
    }

    // ==================== 压缩策略 ====================

    /** 是否需要触发压缩 */
    public boolean needsCompression() {
        return history.size() >= COMPRESS_AT;
    }

    /**
     * 取出待压缩的旧轮次（从头部取 history.size() - KEEP_RAW 条），
     * 同时从 history 中移除这些条目。
     *
     * @return 待压缩的轮次列表；无需压缩时返回空列表
     */
    public List<ConversationTurn> popCompressibleTurns() {
        if (!needsCompression()) return List.of();
        int removeCount = history.size() - KEEP_RAW;
        List<ConversationTurn> toCompress = new ArrayList<>(history.subList(0, removeCount));
        history.subList(0, removeCount).clear();
        log.info("[SESSION] 压缩触发 | sessionId={} | 取出{}轮 → 保留{}轮",
                sessionId, toCompress.size(), history.size());
        syncHistoryToRedis();
        return toCompress;
    }

    /**
     * 追加压缩摘要（累积模式，参考 EchoMind 的 old_summary + new_summary）。
     */
    public void appendSummary(String summary) {
        if (summary == null || summary.isEmpty()) return;
        if (conversationSummary == null || conversationSummary.isEmpty()) {
            conversationSummary = summary;
        } else {
            conversationSummary = conversationSummary + "\n" + summary;
        }
        log.info("[SESSION] 摘要已追加 | sessionId={} | totalLen={}", sessionId, conversationSummary.length());
        syncSummaryToRedis();
    }

    // ==================== Redis 双写 ====================

    public void setRedis(RedisTemplate<String, Object> redis) { this.redis = redis; }

    private String histKey() { return "avca:hist:" + sessionId; }
    private String sumKey()  { return "avca:sum:" + sessionId; }
    private String idiomKey(){ return "avca:idiom:" + sessionId; }

    void syncHistoryToRedis() {
        if (redis == null) return;
        try {
            redis.delete(histKey());
            for (ConversationTurn t : history) {
                redis.opsForList().rightPush(histKey(), t);
            }
            redis.expire(histKey(), REDIS_TTL);
        } catch (Exception e) { log.warn("[REDIS] 同步历史失败", e); }
    }

    private void syncSummaryToRedis() {
        if (redis == null) return;
        try {
            redis.opsForValue().set(sumKey(), conversationSummary, REDIS_TTL);
        } catch (Exception e) { log.warn("[REDIS] 同步摘要失败", e); }
    }

    private void syncIdiomToRedis(String idiom) {
        if (redis == null) return;
        try {
            redis.opsForList().rightPush(idiomKey(), idiom);
            redis.expire(idiomKey(), REDIS_TTL);
        } catch (Exception e) { log.warn("[REDIS] 同步成语失败", e); }
    }

    // ==================== 单轮对话记录 ====================

    @Data
    public static class ConversationTurn {
        /** 轮次序号 */
        private final int round;
        /** 用户消息文本 */
        private final String userText;
        /** AI 回复文本（STT 完成时暂空，Agent 回复后回填） */
        private String assistantText;
        /** 处理本轮的 Agent 名称 */
        private String agentType;
        /** 该轮对话的时间戳 */
        private final Instant timestamp;

        public ConversationTurn(int round, String userText, String assistantText,
                                 String agentType, Instant timestamp) {
            this.round = round;
            this.userText = userText;
            this.assistantText = assistantText;
            this.agentType = agentType;
            this.timestamp = timestamp;
        }
    }
}

package com.aivca.model.session;

import com.aivca.model.message.ConnectionInitPayload;
import com.aivca.model.message.StatusUpdatePayload;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 单个客户端会话 —— 管理对话上下文、设备状态、视觉缓存和对话历史。
 *
 * 负责：
 * - 维护会话生命周期（创建 → 活跃 → 空闲 → 回收）
 * - 缓存视觉分析结果（用于帧差去重）
 * - 管理对话历史（最近 12 条，即 6 轮）
 * - 跟踪设备开关状态
 *
 * 不负责：
 * - 持久化存储（后续迭代实现）
 */
@Slf4j
@Data
public class ConversationSession {

    /** 服务端生成的唯一会话 ID（12 位） */
    private final String sessionId;

    /** 会话创建时间 */
    private final Instant createdAt;

    /** 最后活跃时间，用于空闲回收判断 */
    private Instant lastActiveAt;

    /** 客户端设备信息（UA、屏幕尺寸等） */
    private ConnectionInitPayload.DeviceInfo deviceInfo;

    /** 摄像头是否开启 */
    private boolean cameraEnabled;

    /** 麦克风是否开启 */
    private boolean microphoneEnabled;

    /** 当前使用的摄像头设备 ID */
    private String cameraDeviceId;

    /** 当前使用的麦克风设备 ID */
    private String microphoneDeviceId;

    /** 最近一次视觉描述的缓存，画面无变化时复用 */
    private String cachedVisionDescription;

    /** 最近一次视觉描述对应的帧校验和，用于缓存命中判断 */
    private String cachedImageChecksum;

    /** 当前 episode（一次完整交互窗口） */
    private Episode currentEpisode;

    /** Vision 调用版本号（防止旧结果覆盖新结果） */
    private int visionGeneration;

    /** 事件队列（串行消费） */
    private final java.util.concurrent.BlockingQueue<TriggerEvent> eventQueue =
            new java.util.concurrent.LinkedBlockingQueue<>(100);

    /** 消费线程 */
    private transient Thread consumerThread;

    /** 说话期间累积的原始帧（WS 线程直接写，不走队列） */
    private final List<String> bufferedFrames = java.util.Collections.synchronizedList(new ArrayList<>());

    /** 当前对话轮次计数（从 0 开始，每轮 user+assistant 递增） */
    private int conversationRound;

    /** 对话历史记录，最多保留 12 条（6 轮） */
    private final List<ConversationTurn> history = new ArrayList<>();

    /** 历史条数上限：12 条 = 6 轮对话 */
    private static final int MAX_HISTORY = 12;

    /** 当前服务端处理状态，推送给客户端显示状态指示器 */
    private StatusUpdatePayload.State currentState = StatusUpdatePayload.State.idle;

    /**
     * 创建新会话，自动生成 sessionId 和创建时间。
     */
    public ConversationSession() {
        this.sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.createdAt = Instant.now();
        this.lastActiveAt = Instant.now();
    }

    /**
     * 更新最后活跃时间（每次交互时调用，防止被空闲回收）。
     */
    public void touch() {
        this.lastActiveAt = Instant.now();
    }

    /**
     * 追加一轮对话记录，超出上限时自动裁剪最早记录。
     *
     * @param userText      用户消息文本
     * @param assistantText AI 回复文本
     */

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

    public void addTurn(String userText, String assistantText) {
        history.add(new ConversationTurn(conversationRound++, userText, assistantText, Instant.now()));
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    /**
     * 单轮对话记录。
     */
    @Data
    public static class ConversationTurn {
        /** 轮次序号 */
        private final int round;
        /** 用户消息文本 */
        private final String userText;
        /** AI 回复文本 */
        private final String assistantText;
        /** 该轮对话的时间戳 */
        private final Instant timestamp;
    }
}

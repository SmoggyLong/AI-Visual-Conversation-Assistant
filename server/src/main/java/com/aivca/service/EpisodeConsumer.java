package com.aivca.service;

import com.aivca.api.llm.model.IntentResult;
import com.aivca.agent.AgentRouter;
import com.aivca.agent.Agent;
import com.aivca.agent.model.AgentContext;
import com.aivca.agent.model.ChatResponse;
import com.aivca.api.tts.TtsService;
import com.aivca.constant.IntentType;
import com.aivca.api.vision.VisionService;
import com.aivca.api.vision.ZhipuVisionService;
import com.aivca.handler.Orchestrator;
import com.aivca.model.session.ConversationSession;
import com.aivca.model.session.Episode;
import com.aivca.model.session.TriggerEvent;
import com.aivca.util.CircuitBreaker;
import com.aivca.util.SpeechSanitizer;
import com.aivca.util.VisionStructurer;
import com.aivca.util.HistoryFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Episode 消费者 —— 每 session 一个线程，严格串行。
 *
 * isSpeaking 帧由 WS 线程直写 session，不经过此队列。
 * 此队列只接收：静默 VISION 帧、SPEECH_BATCH（语音+累积帧打包）。
 *
 * 新增：
 * - ResponseCallback：Agent 回复生成后推送前端
 * - 历史压缩触发：history 达到阈值 → LLM 摘要 → 累积追加
 * - 异常时 STATUS_UPDATE 通知前端
 */
@Slf4j
public class EpisodeConsumer implements Runnable {

    private final ConversationSession session;
    private final Orchestrator orchestrator;
    private final VisionService visionService;
    private final AgentRouter agentRouter;
    private final ResponseCallback callback;
    private final TtsService ttsService;
    private final CircuitBreaker visionBreaker;
    private final CircuitBreaker agentBreaker;
    private volatile boolean running = true;

    /** 超过 3 秒的静默帧视为过期丢弃 */
    private static final long MAX_FRAME_AGE_MS = 3000;

    /**
     * 回复回调接口 —— EpisodeConsumer 不持有 WebSocket 引用，
     * 通过此接口将 Agent 回复和状态通知转发给 ConversationWSHandler。
     */
    @FunctionalInterface
    public interface ResponseCallback {
        void onResponse(ChatResponse resp, Agent agent, int conversationRound);
        default void onStatus(String detail) {}
        default void onAudio(String base64Mp3) {}
        default void onVisionResult(String description) {}
    }

    public EpisodeConsumer(ConversationSession session, Orchestrator orchestrator,
                           String zhipuApiKey, ObjectMapper objectMapper,
                           AgentRouter agentRouter, ResponseCallback callback,
                           TtsService ttsService) {
        this.session = session;
        this.orchestrator = orchestrator;
        this.visionService = new ZhipuVisionService(zhipuApiKey, objectMapper);
        this.agentRouter = agentRouter;
        this.callback = callback;
        this.ttsService = ttsService;
        this.visionBreaker = new CircuitBreaker("vision", 3, Duration.ofSeconds(60));
        this.agentBreaker = new CircuitBreaker("agent", 5, Duration.ofSeconds(30));
        this.session.setConsumerThread(new Thread(this, "de-" + session.getSessionId()));
        this.session.getConsumerThread().start();
    }

    @Override
    public void run() {
        log.info("[EP] Consumer 启动 | sessionId={}", session.getSessionId());
        try {
            while (running) {
                TriggerEvent event = session.getEventQueue().take();
                processEvent(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.getEventQueue().clear();
            log.info("[EP] Consumer 中断，队列已清空 | sessionId={}", session.getSessionId());
        }
        log.info("[EP] Consumer 停止 | sessionId={}", session.getSessionId());
    }

    public void stop() {
        running = false;
        Thread t = session.getConsumerThread();
        if (t != null) t.interrupt();
    }

    // ==================== event processing ====================

    private void processEvent(TriggerEvent event) {
        if (event.getType() == TriggerEvent.Type.VISION) {
            long age = System.currentTimeMillis() - event.getTimestamp();
            if (age > MAX_FRAME_AGE_MS) {
                log.debug("[EP] 丢弃过期帧 | ageMs={}", age);
                return;
            }
        }

        switch (event.getType()) {
            case VISION -> handleVision(event);
            case SPEECH_BATCH -> handleSpeechBatch(event);
        }
    }

    /** 静默画面 → 同步调 Vision → 更新 episode + session 缓存。不触发回复（只有语音说话才回复）。 */
    private void handleVision(TriggerEvent event) {
        if (!visionBreaker.allowRequest()) {
            log.debug("[EP] Vision 熔断中，跳过");
            return;
        }
        String desc = visionService.describeBatch(event.getFrames());
        if (desc == null || desc.isEmpty()) {
            visionBreaker.recordFailure();
            return;
        }
        visionBreaker.recordSuccess();

        VisionStructurer vs = VisionStructurer.parse(desc);
        String action = vs.getAction() != null ? vs.getAction() : "";

        Episode ep = session.getCurrentEpisode();
        if (ep == null || ep.isClosed()) {
            ep = new Episode();
            session.setCurrentEpisode(ep);
            log.info("[EP-{}] ═══ EPISODE 开始 ═══ | trigger=vision", ep.getId());
        }

        ep.updateVision(vs.getDescription(), action);
        log.info("[EP-{}] VISION | desc={} | action={}", ep.getId(),
                truncate(vs.getDescription(), 60), truncate(action, 40));

        session.setCachedVisionDescription(desc);
        // VISION 只积累画面，不自动回复。回复仅在 SPEECH_BATCH 时触发。
    }

    /** 语音+累积帧 → Vision(异步) + Intent(并行) → Agent → 回复 */
    private void handleSpeechBatch(TriggerEvent event) {
        Episode ep = session.getCurrentEpisode();
        if (ep == null || ep.isClosed()) {
            ep = new Episode();
            session.setCurrentEpisode(ep);
        }

        ep.setSpeech(event.getSpeech());
        log.info("[EP-{}] ═══ EPISODE 开始 ═══ | trigger=speech", ep.getId());
        log.info("[EP-{}] SPEECH_BATCH | text={} | frames={}", ep.getId(),
                truncate(event.getSpeech(), 40),
                event.getFrames() != null ? event.getFrames().size() : 0);

        // Vision 异步执行 — 和 Intent 识别并行
        CompletableFuture<Void> visionFuture = CompletableFuture.completedFuture(null);
        if (event.getFrames() != null && !event.getFrames().isEmpty()) {
            var frames = new ArrayList<>(event.getFrames());
            final Episode epForVision = ep;
            final ConversationSession sessForVision = session;
            final ResponseCallback cb = callback;
            visionFuture = CompletableFuture.runAsync(() -> {
                String desc = visionService.describeBatch(frames);
                if (desc != null && !desc.isEmpty()) {
                    VisionStructurer vs = VisionStructurer.parse(desc);
                    epForVision.updateVision(vs.getDescription(), vs.getAction());
                    sessForVision.setCachedVisionDescription(desc);
                    try {
                        cb.onVisionResult(vs.getDescription());
                    } catch (Exception e) {
                        log.debug("[EP-{}] 推送Vision结果失败", epForVision.getId());
                    }
                    log.info("[EP-{}] VISION 完成 | desc={}", epForVision.getId(), truncate(vs.getDescription(), 60));
                }
            });
        }

        ep.forceClose("speech");
        ep.setClosed(true);
        ep.setCloseTime(java.time.Instant.now());
        log.info("[EP-{}] ═══ EPISODE 关闭 ═══ | reason=speech", ep.getId());

        // 即刻推送思考状态，开始处理
        try { callback.onStatus("思考中..."); } catch (Exception ignored) {}

        respond(ep, visionFuture);
    }

    private void respond(Episode ep, CompletableFuture<Void> visionFuture) {
        try {
            // Intent 识别 — 和 Vision 并行执行（不依赖 Vision 结果）
            IntentResult result = orchestrator.recognizeIntent(session);
            log.info("[EP-{}] INTENT | intent={} | urgency={} | confidence={} | reasoning={}",
                    ep.getId(),
                    result.getIntent().toValue(),
                    result.getUrgency().toValue(),
                    result.getConfidence(),
                    result.getReasoning());

            // 推送意图结果给前端
            try { callback.onStatus("已识别: " + result.getIntent().toValue()); } catch (Exception ignored) {}

            // 等待 Vision 完成（如果还没完）
            if (!visionFuture.isDone()) {
                log.debug("[EP-{}] 等待 Vision 完成...", ep.getId());
                try { visionFuture.get(8, TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException te) { log.warn("[EP-{}] Vision 超时", ep.getId()); }
                catch (Exception e) { log.debug("[EP-{}] Vision 异步异常: {}", ep.getId(), e.getMessage()); }
            }

            Agent agent = agentRouter.route(result);

            // 组装完整上下文（含对话历史 + 累积摘要）
            AgentContext agentCtx = new AgentContext(
                    ep.getSpeech(),
                    ep.getVisionDesc(),
                    ep.getAction(),
                    result.getUrgency().toValue(),
                    result.getSecondaryIntents().stream().map(IntentType::toValue).toList()
            );
            // 注入全量历史 + 累积摘要 + 已用成语（参考 EchoMind 的 MemoryContext）
            agentCtx.setConversationHistory(new ArrayList<>(session.getHistory()));
            agentCtx.setConversationSummary(session.getConversationSummary());
            agentCtx.setUsedIdioms(session.getUsedIdioms());

            // Agent LLM 调用（带熔断保护）
            ChatResponse chatResp;
            if (!agentBreaker.allowRequest()) {
                log.info("[EP-{}] Agent 熔断中，使用 fallback", ep.getId());
                chatResp = new ChatResponse(agent.fallbackText(), "idle", "neutral");
            } else {
                chatResp = agent.handle(agentCtx);
                if (chatResp.getText() != null && chatResp.getText().contains(agent.fallbackText())) {
                    agentBreaker.recordFailure();
                } else {
                    agentBreaker.recordSuccess();
                }
            }
            log.info("[EP-{}] AGENT | agent={} | text={} | action={} | expression={} | idiom={}",
                    ep.getId(), agent.name(),
                    chatResp.getText().length() > 50
                            ? chatResp.getText().substring(0, 50) : chatResp.getText(),
                    chatResp.getAction(), chatResp.getExpression(),
                    chatResp.getIdiom());

            // 回填最近一轮的 assistant 文本和 agent 类型
            session.updateLastAssistantText(chatResp.getText());
            session.updateLastAgentType(agent.name());

            // 成语接龙：追踪 Agent 使用的成语
            if (chatResp.getIdiom() != null && !chatResp.getIdiom().isEmpty()) {
                session.addUsedIdiom(chatResp.getIdiom());
            }

            // 推送到前端
            try {
                callback.onResponse(chatResp, agent, session.getConversationRound());
            } catch (Exception e) {
                log.warn("[EP-{}] 推送回复失败", ep.getId(), e);
            }

            // TTS 语音合成（异步，不阻塞主流程）
            try {
                String audio = ttsService.synthesize(chatResp.getText());
                if (audio != null) {
                    callback.onAudio(audio);
                }
            } catch (Exception e) {
                log.debug("[EP-{}] TTS 合成失败（非阻塞）: {}", ep.getId(), e.getMessage());
            }

            // 检查是否需要压缩历史
            if (session.needsCompression()) {
                List<ConversationSession.ConversationTurn> toCompress = session.popCompressibleTurns();
                String summary = HistoryFormatter.summarize(toCompress,
                        agent.getSummarizeModel());
                session.appendSummary(summary);
            }

        } catch (Exception e) {
            log.error("[EP-{}] respond 异常", ep.getId(), e);
            try { callback.onStatus("处理异常: " + e.getMessage()); } catch (Exception ignored) {}
        }

        long totalMs = java.time.Duration.between(ep.getStartTime(), java.time.Instant.now()).toMillis();
        log.info("[EP-{}] ═══ EPISODE 完成 ═══ | totalMs={} | queueSize={}",
                ep.getId(), totalMs, session.getEventQueue().size());
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}

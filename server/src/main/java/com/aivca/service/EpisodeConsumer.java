package com.aivca.service;

import com.aivca.api.llm.model.IntentResult;
import com.aivca.api.llm.model.AgentContext;
import com.aivca.api.llm.model.ChatResponse;
import com.aivca.api.llm.agent.Agent;
import com.aivca.api.llm.router.AgentRouter;
import com.aivca.constant.IntentType;
import com.aivca.api.vision.VisionService;
import com.aivca.api.vision.ZhipuVisionService;
import com.aivca.handler.Orchestrator;
import com.aivca.model.session.ConversationSession;
import com.aivca.model.session.Episode;
import com.aivca.model.session.TriggerEvent;
import com.aivca.util.SpeechSanitizer;
import com.aivca.util.VisionStructurer;
import com.aivca.util.ContextBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Episode 消费者 —— 每 session 一个线程，严格串行。
 *
 * isSpeaking 帧由 WS 线程直写 session，不经过此队列。
 * 此队列只接收：静默 VISION 帧、SPEECH_BATCH（语音+累积帧打包）。
 */
@Slf4j
public class EpisodeConsumer implements Runnable {

    private final ConversationSession session;
    private final Orchestrator orchestrator;
    private final VisionService visionService;
    private final AgentRouter agentRouter;
    private volatile boolean running = true;

    /** 超过 3 秒的静默帧视为过期丢弃 */
    private static final long MAX_FRAME_AGE_MS = 3000;

    public EpisodeConsumer(ConversationSession session, Orchestrator orchestrator,
                           String zhipuApiKey, ObjectMapper objectMapper, AgentRouter agentRouter) {
        this.session = session;
        this.orchestrator = orchestrator;
        this.visionService = new ZhipuVisionService(zhipuApiKey, objectMapper);
        this.agentRouter = agentRouter;
        this.session.setConsumerThread(new Thread(this, "de-" + session.getSessionId()));
        this.session.getConsumerThread().start();
    }

    @Override
    public void run() {
        log.info("[EP] Consumer 启动 | sessionId={}", session.getSessionId());
        try {
            while (running) {
                TriggerEvent event = session.getEventQueue().take();  // 阻塞，被 interrupt 时退出
                processEvent(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        // 过期帧丢弃
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

    /** 静默画面 → 同步调 Vision → updateEpisode → shouldClose */
    private void handleVision(TriggerEvent event) {
        String desc = visionService.describeBatch(event.getFrames());
        if (desc == null || desc.isEmpty()) return;

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
        checkAndClose(ep);
    }

    /** 语音+累积帧 → Vision → setSpeech → forceClose → respond */
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

        // 处理累积帧
        if (event.getFrames() != null && !event.getFrames().isEmpty()) {
            String desc = visionService.describeBatch(new ArrayList<>(event.getFrames()));
            if (desc != null && !desc.isEmpty()) {
                VisionStructurer vs = VisionStructurer.parse(desc);
                ep.updateVision(vs.getDescription(), vs.getAction());
                session.setCachedVisionDescription(desc);
                log.info("[EP-{}] VISION 完成 | desc={}", ep.getId(), truncate(vs.getDescription(), 60));
            }
        }

        ep.forceClose("speech");
        ep.setClosed(true);
        ep.setCloseTime(java.time.Instant.now());
        log.info("[EP-{}] ═══ EPISODE 关闭 ═══ | reason=speech", ep.getId());
        respond(ep);
    }

    private void checkAndClose(Episode ep) {
        if (ep.shouldClose()) {
            ep.setClosed(true);
            ep.setCloseTime(java.time.Instant.now());
            log.info("[EP-{}] ═══ EPISODE 关闭 ═══ | reason={}", ep.getId(), ep.getCloseReason());
            respond(ep);
        }
    }

    private void respond(Episode ep) {
        String cleanSpeech = SpeechSanitizer.sanitize(ep.getSpeech());
        VisionStructurer vision = VisionStructurer.parse(ep.getVisionDesc());

        String context = ContextBuilder.build(cleanSpeech, vision);
        if (context != null) {
            log.info("[EP-{}] 组合上下文:\n{}", ep.getId(), context);
        }

        IntentResult result = orchestrator.recognizeIntent(session);
        log.info("[EP-{}] INTENT | intent={} | urgency={} | confidence={} | reasoning={}",
                ep.getId(),
                result.getIntent().toValue(),
                result.getUrgency().toValue(),
                result.getConfidence(),
                result.getReasoning());

        // Agent 路由 → LLM 回复
        Agent agent = agentRouter.route(result);
        AgentContext agentCtx = new AgentContext(
                ep.getSpeech(),
                ep.getVisionDesc(),
                ep.getAction(),
                result.getSecondaryIntents().stream().map(IntentType::toValue).toList()
        );
        ChatResponse chatResp = agent.handle(agentCtx);
        log.info("[EP-{}] AGENT | agent={} | text={} | action={} | expression={}",
                ep.getId(), agent.name(),
                chatResp.getText().length() > 50 ? chatResp.getText().substring(0, 50) : chatResp.getText(),
                chatResp.getAction(), chatResp.getExpression());

        long totalMs = java.time.Duration.between(ep.getStartTime(), java.time.Instant.now()).toMillis();
        log.info("[EP-{}] ═══ EPISODE 完成 ═══ | totalMs={} | queueSize={}",
                ep.getId(), totalMs, session.getEventQueue().size());
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}

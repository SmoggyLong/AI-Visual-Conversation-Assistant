package com.aivca.service;

import com.aivca.api.llm.model.IntentResult;
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
import java.util.concurrent.TimeUnit;

/**
 * Episode 消费者 —— 每 session 一个线程，严格串行。
 *
 * 核心逻辑：
 * - isSpeaking=true  → 帧不分帧调用 Vision，累积到 episode.bufferedFrames
 * - isSpeaking=false → 同步调 Vision → episode.updateVision → shouldClose?
 * - SPEECH           → setSpeech → Vision(累积的帧) → forceClose → respond()
 */
@Slf4j
public class EpisodeConsumer implements Runnable {

    private final ConversationSession session;
    private final Orchestrator orchestrator;
    private final VisionService visionService;
    private volatile boolean running = true;

    /** 说话期间累积的原始帧 */
    private final List<String> bufferedFrames = new ArrayList<>();

    public EpisodeConsumer(ConversationSession session, Orchestrator orchestrator,
                           String zhipuApiKey, ObjectMapper objectMapper) {
        this.session = session;
        this.orchestrator = orchestrator;
        this.visionService = new ZhipuVisionService(zhipuApiKey, objectMapper);
        this.session.setConsumerThread(new Thread(this, "de-" + session.getSessionId()));
        this.session.getConsumerThread().start();
    }

    @Override
    public void run() {
        log.info("[EP] 消费者启动 | sessionId={}", session.getSessionId());
        try {
            while (running) {
                TriggerEvent event = session.getEventQueue().poll(10, TimeUnit.SECONDS);
                if (event == null) continue;
                processEvent(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[EP] 消费者停止 | sessionId={}", session.getSessionId());
    }

    public void stop() {
        running = false;
        Thread t = session.getConsumerThread();
        if (t != null) t.interrupt();
    }

    // ==================== event processing ====================

    private void processEvent(TriggerEvent event) {
        Episode ep = session.getCurrentEpisode();

        switch (event.getType()) {
            case VISION -> handleVision(event, ep);
            case SPEECH -> handleSpeech(event, ep);
        }
    }

    /** 静默时的画面 → 同步调 Vision → updateEpisode → shouldClose */
    private void handleVision(TriggerEvent event, Episode ep) {
        if (event.isSpeaking()) {
            // 说话期间 → 只累积帧，不调 Vision
            bufferedFrames.addAll(event.getFrames());
            log.debug("[EP] 说话期间累积帧 | totalBuffered={}", bufferedFrames.size());
            return;
        }

        // 静默时 → 同步调 Vision
        String desc = visionService.describeBatch(event.getFrames());
        if (desc == null || desc.isEmpty()) return;

        VisionStructurer vs = VisionStructurer.parse(desc);
        String action = vs.getAction() != null ? vs.getAction() : "";

        if (ep == null || ep.isClosed()) {
            if (!Episode.canStartNewEpisode()) {
                log.debug("[EP] 冷却中，跳过 vision");
                return;
            }
            ep = new Episode();
            session.setCurrentEpisode(ep);
            log.info("[EP-{}] ═══ EPISODE 开始 ═══ | trigger=vision_change", ep.getId());
        }

        ep.updateVision(vs.getDescription(), action);
        log.info("[EP-{}] VISION | desc={} | action={}",
                ep.getId(), truncate(vs.getDescription(), 60), truncate(action, 40));

        session.setCachedVisionDescription(desc);
        checkAndClose(ep);
    }

    /** 语音到达 → 处理累积帧 + 关闭 episode + 回复 */
    private void handleSpeech(TriggerEvent event, Episode ep) {
        if (ep == null || ep.isClosed()) {
            if (!Episode.canStartNewEpisode()) {
                log.debug("[EP] 冷却中，跳过 speech");
                return;
            }
            ep = new Episode();
            session.setCurrentEpisode(ep);
            log.info("[EP-{}] ═══ EPISODE 开始 ═══ | trigger=speech", ep.getId());
        }

        ep.setSpeech(event.getSpeech());
        log.info("[EP-{}] SPEECH | text={}", ep.getId(), truncate(event.getSpeech(), 40));

        // 处理说话期间累积的帧
        if (!bufferedFrames.isEmpty()) {
            log.info("[EP-{}] 处理累积帧 | count={}", ep.getId(), bufferedFrames.size());
            String desc = visionService.describeBatch(new ArrayList<>(bufferedFrames));
            bufferedFrames.clear();
            if (desc != null && !desc.isEmpty()) {
                VisionStructurer vs = VisionStructurer.parse(desc);
                ep.updateVision(vs.getDescription(), vs.getAction());
                session.setCachedVisionDescription(desc);
            }
        }

        ep.forceClose("speech");
        ep.setClosed(true);
        ep.setCloseTime(java.time.Instant.now());
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

        Episode.markResponseSent();
        log.info("[EP-{}] ═══ EPISODE 完成 ═══ | 冷却 {}s", ep.getId(), 5);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}

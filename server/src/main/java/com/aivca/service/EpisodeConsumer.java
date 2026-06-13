package com.aivca.service;

import com.aivca.api.llm.model.IntentResult;
import com.aivca.handler.Orchestrator;
import com.aivca.model.session.ConversationSession;
import com.aivca.model.session.Episode;
import com.aivca.model.session.TriggerEvent;
import com.aivca.util.SpeechSanitizer;
import com.aivca.util.VisionStructurer;
import com.aivca.util.ContextBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Episode 消费者 —— 每 session 一个线程，严格串行处理事件。
 *
 * 生命周期:
 *   IDLE → 收到 VISION 或 SPEECH → 开 episode → 累积数据
 *   → shouldClose → 组合上下文 → IntentRecognizer → 回复
 *   → 冷却 5s → IDLE
 */
@Slf4j
public class EpisodeConsumer implements Runnable {

    private final ConversationSession session;
    private final BlockingQueue<TriggerEvent> queue;
    private final Orchestrator orchestrator;
    private volatile boolean running = true;

    public EpisodeConsumer(ConversationSession session, Orchestrator orchestrator) {
        this.session = session;
        this.queue = session.getEventQueue();
        this.orchestrator = orchestrator;
        this.session.setConsumerThread(new Thread(this, "episode-" + session.getSessionId()));
        this.session.getConsumerThread().start();
    }

    @Override
    public void run() {
        log.info("[EP-{}] 消费者启动", session.getSessionId());
        try {
            while (running) {
                TriggerEvent event = queue.poll(10, TimeUnit.SECONDS);
                if (event == null) continue;

                processEvent(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[EP-{}] 消费者停止", session.getSessionId());
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

    private void handleVision(TriggerEvent event, Episode ep) {
        if (ep == null || ep.isClosed()) {
            // 冷却检查
            if (!Episode.canStartNewEpisode()) {
                log.debug("[EP-NEW] 冷却中，跳过 vision");
                return;
            }
            ep = new Episode();
            session.setCurrentEpisode(ep);
            log.info("[EP-{}] ═══ EPISODE 开始 ═══ | trigger=vision_change", ep.getId());
        }

        ep.updateVision(event.getVisionDesc(), event.getAction());
        log.info("[EP-{}] VISION | desc={} | action={}",
                ep.getId(),
                truncate(event.getVisionDesc(), 60),
                event.getAction() != null ? event.getAction() : "-");

        checkAndClose(ep);
    }

    private void handleSpeech(TriggerEvent event, Episode ep) {
        if (ep == null || ep.isClosed()) {
            if (!Episode.canStartNewEpisode()) {
                log.debug("[EP-NEW] 冷却中，跳过 speech");
                return;
            }
            ep = new Episode();
            session.setCurrentEpisode(ep);
            log.info("[EP-{}] ═══ EPISODE 开始 ═══ | trigger=speech", ep.getId());
        }

        ep.setSpeech(event.getSpeech());
        log.info("[EP-{}] SPEECH | text={}", ep.getId(), truncate(event.getSpeech(), 40));

        // 用户说话总是应立即关闭 episode
        ep.forceClose("speech");
        ep.setClosed(true);
        ep.setCloseTime(java.time.Instant.now());
        respond(ep);
    }

    /** 检查 episode 是否应该关闭 */
    private void checkAndClose(Episode ep) {
        if (ep.shouldClose()) {
            ep.setClosed(true);
            ep.setCloseTime(java.time.Instant.now());
            log.info("[EP-{}] ═══ EPISODE 关闭 ═══ | reason={}", ep.getId(), ep.getCloseReason());
            respond(ep);
        }
    }

    /** 组合上下文 → 意图识别 → 日志 */
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

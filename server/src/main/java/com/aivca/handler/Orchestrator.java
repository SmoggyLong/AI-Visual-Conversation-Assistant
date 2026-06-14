package com.aivca.handler;

import com.aivca.api.llm.model.IntentResult;
import com.aivca.api.llm.router.IntentRecognizer;
import com.aivca.agent.AgentRouter;
import com.aivca.model.session.ConversationSession;
import com.aivca.util.ContextBuilder;
import com.aivca.util.SpeechSanitizer;
import com.aivca.util.VisionStructurer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 编排器 — 串联数据清洗、意图识别。
 */
@Slf4j
@Component
public class Orchestrator {

    private final IntentRecognizer intentRecognizer;
    private final AgentRouter agentRouter;

    public Orchestrator(IntentRecognizer intentRecognizer, AgentRouter agentRouter) {
        this.intentRecognizer = intentRecognizer;
        this.agentRouter = agentRouter;
    }

    public AgentRouter getAgentRouter() { return agentRouter; }

    public IntentResult recognizeIntent(ConversationSession session) {
        String rawVision = session.getCachedVisionDescription();
        String cleanSpeech = SpeechSanitizer.sanitize(lastUserText(session));
        VisionStructurer vision = VisionStructurer.parse(rawVision);
        String context = ContextBuilder.build(cleanSpeech, vision);

        IntentResult result = intentRecognizer.recognize(context);
        log.info("[ORCH] 意图识别完成 | intent={} | urgency={} | confidence={}",
                result.getIntent().toValue(),
                result.getUrgency().toValue(),
                result.getConfidence());
        return result;
    }

    private static String lastUserText(ConversationSession session) {
        var history = session.getHistory();
        if (history.isEmpty()) return null;
        return history.get(history.size() - 1).getUserText();
    }
}

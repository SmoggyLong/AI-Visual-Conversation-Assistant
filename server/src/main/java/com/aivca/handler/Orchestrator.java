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

        // 注入对话状态：上一轮的 Agent 类型帮助意图识别 (如游戏接龙中)
        // 注意: history.last 是当前轮次 (agentType 还未回填), 需查倒数第二个
        var history = session.getHistory();
        if (history.size() >= 2) {
            var prevTurn = history.get(history.size() - 2);  // 上一轮
            if ("game".equals(prevTurn.getAgentType())) {
                context = (context != null ? context : "")
                        + "\n[对话状态]\n当前正在进行成语接龙游戏，用户的输入可能是成语接龙内容。\n";
            }
        }

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

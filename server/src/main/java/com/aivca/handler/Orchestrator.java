package com.aivca.handler;

import com.aivca.api.llm.model.IntentResult;
import com.aivca.api.llm.router.IntentRecognizer;
import com.aivca.agent.AgentRouter;
import com.aivca.model.session.ConversationSession;
import com.aivca.util.ContextBuilder;
import com.aivca.util.SpeechSanitizer;
import com.aivca.util.VisionStructurer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 编排器 — 串联数据清洗、意图识别、Agent 路由。
 *
 * 在 SPEECH_END 时被 ConversationWebSocketHandler 调用。
 *
 * 当前阶段: 实现意图识别（后续接入 Agent 路由 + LLM 回复）
 */
@Slf4j
public class Orchestrator {

    private final IntentRecognizer intentRecognizer;
    private final AgentRouter agentRouter;

    public Orchestrator(String zhipuApiKey, String deepseekApiKey,
                        ObjectMapper objectMapper) {
        this.intentRecognizer = new IntentRecognizer(deepseekApiKey, objectMapper);
        this.agentRouter = new AgentRouter(deepseekApiKey, zhipuApiKey, objectMapper);
    }

    /** 暴露 AgentRouter 供 EpisodeConsumer 使用 */
    public AgentRouter getAgentRouter() { return agentRouter; }

    /**
     * 执行意图识别管线。
     *
     * @param session 当前业务会话（包含 STT 文字和 Vision 缓存描述）
     * @return 识别结果；失败时降级为 (GENERAL, NORMAL)
     */
    public IntentResult recognizeIntent(ConversationSession session) {
        // 1. 取原始数据
        String rawVision = session.getCachedVisionDescription();

        // 2. 清洗 + 结构化
        String cleanSpeech = SpeechSanitizer.sanitize(lastUserText(session));
        VisionStructurer vision = VisionStructurer.parse(rawVision);

        // 3. 组装上下文
        String context = ContextBuilder.build(cleanSpeech, vision);

        // 4. 意图识别
        IntentResult result = intentRecognizer.recognize(context);
        log.info("[ORCH] 意图识别完成 | intent={} | urgency={} | confidence={}",
                result.getIntent().toValue(),
                result.getUrgency().toValue(),
                result.getConfidence());
        return result;
    }

    /** 从 session 中取最新一条用户消息 */
    private static String lastUserText(ConversationSession session) {
        var history = session.getHistory();
        if (history.isEmpty()) return null;
        var lastTurn = history.get(history.size() - 1);
        return lastTurn.getUserText();
    }
}

package com.aivca.agent;

import com.aivca.agent.model.AgentContext;
import com.aivca.agent.model.ChatResponse;
import com.aivca.constant.UrgencyLevel;
import com.aivca.model.session.ConversationSession;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.List;

/**
 * Agent 接口 — 每个意图对应一个 Agent 实现。
 */
public interface Agent {

    String name();
    String intent();
    String systemPrompt();
    double temperature();
    int maxTokens();

    /** Agent 默认使用的模型 */
    ChatLanguageModel getChatModel();

    /** 压缩摘要使用的模型 */
    ChatLanguageModel getSummarizeModel();

    /**
     * 根据紧急程度选择模型。
     */
    default ChatLanguageModel selectModel(UrgencyLevel urgency) {
        return getChatModel();
    }

    /** 是否在 prompt 中注入累积压缩摘要 */
    default boolean useSummary() { return true; }

    /** prompt 中保留的 raw 对话轮数。0 = 不注入 */
    default int maxRawTurns() { return 3; }

    /** 自定义历史格式化（返回 null 则用默认格式器） */
    default String formatHistory(List<ConversationSession.ConversationTurn> history) {
        return null;
    }

    /** LLM 调用失败时的兜底回复文案 */
    default String fallbackText() { return "我暂时无法回复，请稍后再试。"; }

    /** 处理用户输入，返回回复 */
    ChatResponse handle(AgentContext context);
}

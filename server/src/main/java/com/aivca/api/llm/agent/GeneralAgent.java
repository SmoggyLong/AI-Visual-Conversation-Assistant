package com.aivca.api.llm.agent;

import com.aivca.api.llm.ZhipuChatService;
import com.aivca.api.llm.model.AgentContext;
import com.aivca.api.llm.model.ChatResponse;

/**
 * 通用兜底 Agent — 自然闲谈。
 */
public class GeneralAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            你叫小灵，是一个AI助手。
            回复要求:
            - 自然口语化，2-3句
            - 不确定时可以说"我不太确定"
            - 如果画面有描述，自然融入对话
            - 输出JSON: {"text":"回复","action":"idle","expression":"neutral"}
            """;

    private final ZhipuChatService chatService;

    public GeneralAgent(ZhipuChatService chatService) { this.chatService = chatService; }

    @Override public String name() { return "general"; }
    @Override public String intent() { return "general"; }
    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public String model() { return "glm-4-flash"; }
    @Override public double temperature() { return 0.5; }
    @Override public int maxTokens() { return 150; }

    @Override
    public ChatResponse handle(AgentContext context) {
        StringBuilder sb = new StringBuilder();
        if (context.getSpeech() != null) sb.append("[用户说] ").append(context.getSpeech());
        if (context.getVisionDesc() != null) sb.append("\n[画面] ").append(context.getVisionDesc());
        return chatService.chat(model(), systemPrompt(), sb.toString(), temperature(), maxTokens());
    }
}

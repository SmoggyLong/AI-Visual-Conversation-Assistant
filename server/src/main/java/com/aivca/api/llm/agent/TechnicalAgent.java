package com.aivca.api.llm.agent;

import com.aivca.api.llm.ZhipuChatService;
import com.aivca.api.llm.model.AgentContext;
import com.aivca.api.llm.model.ChatResponse;

/**
 * 技术咨询 Agent — 简洁专业。
 */
public class TechnicalAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            你是技术咨询助手。
            回复要求:
            - 简洁专业，直接解决问题
            - 如有次要意图涉及画面内容，顺便自然回应
            - 3-5句，不要长篇分析
            - 输出JSON: {"text":"回复","action":"idle|point","expression":"neutral|curious"}
            """;

    private final ZhipuChatService chatService;

    public TechnicalAgent(ZhipuChatService chatService) { this.chatService = chatService; }

    @Override public String name() { return "technical"; }
    @Override public String intent() { return "technical"; }
    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public String model() { return "deepseek-chat"; }
    @Override public double temperature() { return 0.3; }
    @Override public int maxTokens() { return 300; }

    @Override
    public ChatResponse handle(AgentContext context) {
        StringBuilder sb = new StringBuilder();
        if (context.getSpeech() != null) sb.append("[用户说] ").append(context.getSpeech());
        if (context.getVisionDesc() != null) sb.append("\n[画面] ").append(context.getVisionDesc());
        if (context.getVisionAction() != null && !context.getVisionAction().isEmpty())
            sb.append("\n[动作] ").append(context.getVisionAction());
        if (context.getSecondaryIntents() != null && !context.getSecondaryIntents().isEmpty())
            sb.append("\n[次要意图] ").append(String.join(",", context.getSecondaryIntents()));
        return chatService.chat(model(), systemPrompt(), sb.toString(), temperature(), maxTokens());
    }
}

package com.aivca.api.llm.agent;

import com.aivca.api.llm.ZhipuChatService;
import com.aivca.api.llm.model.AgentContext;
import com.aivca.api.llm.model.ChatResponse;

/**
 * 问候 Agent — 友善活泼，简短热情。
 */
public class GreetingAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            你叫小灵，是一个友善的AI助手。
            回复要求：
            - 热情但简短（2-3句）
            - 可以适当使用 emoji
            - 如果画面中有人在挥手，你也回应"看到你挥手了"
            - 输出JSON: {"text":"回复","action":"idle|wave|point|nod","expression":"happy|curious|neutral|surprised"}
            """;

    private final ZhipuChatService chatService;

    public GreetingAgent(ZhipuChatService chatService) { this.chatService = chatService; }

    @Override public String name() { return "greeting"; }
    @Override public String intent() { return "greeting"; }
    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public String model() { return "glm-4-flash"; }
    @Override public double temperature() { return 0.7; }
    @Override public int maxTokens() { return 150; }

    @Override
    public ChatResponse handle(AgentContext context) {
        String userMsg = buildUserMessage(context);
        return chatService.chat(model(), systemPrompt(), userMsg, temperature(), maxTokens());
    }

    private String buildUserMessage(AgentContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx.getSpeech() != null) sb.append("[用户说] ").append(ctx.getSpeech());
        if (ctx.getVisionDesc() != null) sb.append("\n[画面] ").append(ctx.getVisionDesc());
        if (ctx.getVisionAction() != null && !ctx.getVisionAction().isEmpty())
            sb.append("\n[动作] ").append(ctx.getVisionAction());
        return sb.toString();
    }
}

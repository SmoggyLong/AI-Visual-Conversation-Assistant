package com.aivca.agent;

import com.aivca.agent.model.AgentContext;
import com.aivca.agent.model.ChatResponse;
import com.aivca.api.llm.ZhipuChatService;
import com.aivca.constant.UrgencyLevel;

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
    @Override public double temperature() { return 0.3; }
    @Override public int maxTokens() { return 300; }

    @Override
    public ChatResponse handle(AgentContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx.getSpeech() != null) sb.append("[用户说] ").append(ctx.getSpeech());
        if (ctx.getVisionDesc() != null) sb.append("\n[画面] ").append(ctx.getVisionDesc());
        if (ctx.getVisionAction() != null && !ctx.getVisionAction().isEmpty())
            sb.append("\n[动作] ").append(ctx.getVisionAction());
        if (ctx.getSecondaryIntents() != null && !ctx.getSecondaryIntents().isEmpty())
            sb.append("\n[次要意图] ").append(String.join(",", ctx.getSecondaryIntents()));
        return chatService.chat(selectModel(UrgencyLevel.fromString(ctx.getUrgency())),
                systemPrompt(), sb.toString(), temperature(), maxTokens());
    }
}

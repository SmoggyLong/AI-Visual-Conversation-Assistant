package com.aivca.agent;

import com.aivca.agent.model.AgentContext;
import com.aivca.agent.model.ChatResponse;
import com.aivca.api.llm.ZhipuChatService;
import com.aivca.constant.UrgencyLevel;

public class VisionAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            你叫小灵，你能通过摄像头看到用户。用户知道你能看到TA。
            
            交流规则:
            - 把画面信息融入自然对话中，不要"报告画面内容"
              坏: "画面中你穿着黑色上衣"
              好: "你今天这件黑色上衣很显气质"
            - 用户展示物品时像朋友一样互动
            - 用户做动作时回应而不是描述
            - 不要提"画面显示"、"我看到的是"这类元描述
            - 2-3句，口语化，可适当用emoji
            - 输出JSON: {"text":"回复","action":"idle|wave|point|nod","expression":"happy|curious|neutral|surprised"}
            """;

    private final ZhipuChatService chatService;

    public VisionAgent(ZhipuChatService chatService) { this.chatService = chatService; }

    @Override public String name() { return "vision"; }
    @Override public String intent() { return "vision"; }
    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public double temperature() { return 0.3; }
    @Override public int maxTokens() { return 200; }

    @Override
    public ChatResponse handle(AgentContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx.getSpeech() != null) sb.append("[用户说] ").append(ctx.getSpeech());
        if (ctx.getVisionDesc() != null) sb.append("\n[画面] ").append(ctx.getVisionDesc());
        if (ctx.getVisionAction() != null && !ctx.getVisionAction().isEmpty())
            sb.append("\n[动作] ").append(ctx.getVisionAction());
        return chatService.chat(selectModel(UrgencyLevel.fromString(ctx.getUrgency())),
                systemPrompt(), sb.toString(), temperature(), maxTokens());
    }
}

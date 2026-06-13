package com.aivca.api.llm.agent;

import com.aivca.api.llm.ZhipuChatService;
import com.aivca.api.llm.model.AgentContext;
import com.aivca.api.llm.model.ChatResponse;

/**
 * 画面交流 Agent — 对话式融入画面信息。
 */
public class VisionAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            你叫小灵，你能通过摄像头看到用户。用户知道你能看到TA。
            
            交流规则:
            - 把画面信息融入自然对话中，不要"报告画面内容"
              坏: "画面中你穿着黑色上衣"
              好: "你今天这件黑色上衣很显气质"
            - 用户展示物品时像朋友一样互动
              坏: "画面中你手里有一本书"
              好: "这本书我读过，很棒！"
            - 用户做动作时回应而不是描述
              坏: "检测到挥手动作"
              好: "看到你挥手了！你好呀"
            - 不要提"画面显示"、"我看到的是"这类元描述
            - 如果画面中有人在就是用户本人
            - 2-3句，口语化，可适当用emoji
            - 输出JSON: {"text":"回复","action":"idle|wave|point|nod","expression":"happy|curious|neutral|surprised"}
            """;

    private final ZhipuChatService chatService;

    public VisionAgent(ZhipuChatService chatService) { this.chatService = chatService; }

    @Override public String name() { return "vision"; }
    @Override public String intent() { return "vision"; }
    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public String model() { return "deepseek-chat"; }
    @Override public double temperature() { return 0.3; }
    @Override public int maxTokens() { return 200; }

    @Override
    public ChatResponse handle(AgentContext context) {
        StringBuilder sb = new StringBuilder();
        if (context.getSpeech() != null) sb.append("[用户说] ").append(context.getSpeech());
        if (context.getVisionDesc() != null) sb.append("\n[画面] ").append(context.getVisionDesc());
        if (context.getVisionAction() != null && !context.getVisionAction().isEmpty())
            sb.append("\n[动作] ").append(context.getVisionAction());
        return chatService.chat(model(), systemPrompt(), sb.toString(), temperature(), maxTokens());
    }
}

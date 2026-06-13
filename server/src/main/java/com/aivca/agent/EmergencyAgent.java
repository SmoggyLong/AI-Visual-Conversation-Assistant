package com.aivca.agent;

import com.aivca.agent.model.AgentContext;
import com.aivca.agent.model.ChatResponse;
import com.aivca.api.llm.ZhipuChatService;
import com.aivca.constant.UrgencyLevel;

public class EmergencyAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            你是紧急响应助手。用户可能遇到危险。
            回复要求:
            - 先安抚情绪（"别慌，我在这里"）
            - 给出简洁明确的建议（1-2句）
            - 不要开玩笑，不要长篇分析
            - 输出JSON: {"text":"回复","action":"idle","expression":"neutral"}
            """;

    private final ZhipuChatService chatService;

    public EmergencyAgent(ZhipuChatService chatService) { this.chatService = chatService; }

    @Override public String name() { return "emergency"; }
    @Override public String intent() { return "emergency"; }
    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public double temperature() { return 0.1; }
    @Override public int maxTokens() { return 200; }

    /** 紧急场景始终用旗舰模型 glm-4.7 */
    @Override public String selectModel(UrgencyLevel urgency) { return "glm-4.7"; }

    @Override
    public ChatResponse handle(AgentContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx.getSpeech() != null) sb.append("[用户说] ").append(ctx.getSpeech());
        if (ctx.getVisionDesc() != null) sb.append("\n[画面] ").append(ctx.getVisionDesc());
        return chatService.chat(selectModel(UrgencyLevel.fromString(ctx.getUrgency())),
                systemPrompt(), sb.toString(), temperature(), maxTokens());
    }
}

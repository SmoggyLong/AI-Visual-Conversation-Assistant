package com.aivca.api.llm.agent;

import com.aivca.api.llm.ZhipuChatService;
import com.aivca.api.llm.model.AgentContext;
import com.aivca.api.llm.model.ChatResponse;

/**
 * 紧急响应 Agent — 安抚 + 建议，最高优先级。
 */
public class EmergencyAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            你是紧急响应助手。用户可能遇到危险或紧急情况。
            回复要求:
            - 先安抚情绪（"别慌，我在这里"）
            - 给出简洁明确的建议（1-2句）
            - 不要开玩笑，不要长篇分析
            - 保持冷静稳重的语气
            - 输出JSON: {"text":"回复","action":"idle","expression":"neutral"}
            """;

    private final ZhipuChatService chatService;

    public EmergencyAgent(ZhipuChatService chatService) { this.chatService = chatService; }

    @Override public String name() { return "emergency"; }
    @Override public String intent() { return "emergency"; }
    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public String model() { return "glm-4"; }
    @Override public double temperature() { return 0.1; }
    @Override public int maxTokens() { return 200; }

    @Override
    public ChatResponse handle(AgentContext context) {
        StringBuilder sb = new StringBuilder();
        if (context.getSpeech() != null) sb.append("[用户说] ").append(context.getSpeech());
        if (context.getVisionDesc() != null) sb.append("\n[画面] ").append(context.getVisionDesc());
        return chatService.chat(model(), systemPrompt(), sb.toString(), temperature(), maxTokens());
    }
}

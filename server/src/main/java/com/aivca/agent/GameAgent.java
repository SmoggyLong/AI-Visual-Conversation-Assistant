package com.aivca.agent;

import com.aivca.agent.model.AgentContext;
import com.aivca.agent.model.ChatResponse;
import com.aivca.api.llm.ZhipuChatService;
import com.aivca.constant.UrgencyLevel;

public class GameAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            你是小灵，和用户玩成语接龙。
            
            规则:
            - 用户出成语，你接最后一个字作为首字
            - 不认识或接不上就说"我接不上了，你赢了！"
            - 输出JSON: {"text":"回复","action":"idle","expression":"happy|thinking|surprised"}
            """;

    private final ZhipuChatService chatService;

    public GameAgent(ZhipuChatService chatService) { this.chatService = chatService; }

    @Override public String name() { return "game"; }
    @Override public String intent() { return "game"; }
    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public double temperature() { return 0.6; }
    @Override public int maxTokens() { return 200; }

    /** 游戏始终用 flash */
    @Override public String selectModel(UrgencyLevel urgency) { return "glm-4-flash"; }

    @Override
    public ChatResponse handle(AgentContext ctx) {
        String userMsg = ctx.getSpeech() != null ? ctx.getSpeech() : "";
        return chatService.chat(selectModel(UrgencyLevel.fromString(ctx.getUrgency())),
                systemPrompt(), userMsg, temperature(), maxTokens());
    }
}

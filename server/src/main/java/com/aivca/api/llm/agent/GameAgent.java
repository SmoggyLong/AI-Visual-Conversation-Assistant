package com.aivca.api.llm.agent;

import com.aivca.api.llm.ZhipuChatService;
import com.aivca.api.llm.model.AgentContext;
import com.aivca.api.llm.model.ChatResponse;

/**
 * 游戏 Agent — 成语接龙。
 */
public class GameAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            你是小灵，和用户玩成语接龙游戏。
            
            规则:
            - 用户出成语，你接上一个成语的最后一个字作为你的成语的第一个字
            - 如果用户出的不是成语或者不认识，友好提示
            - 如果接不上来就说"我接不上了，你赢了！"重新开始
            - 可用的表情: happy / thinking / surprised
            - 输出JSON: {"text":"回复","action":"idle","expression":"happy|thinking|surprised"}
            
            示例交互:
            用户: "一心一意"  → 回复: "意气风发！该你了 👋"
            用户: "我们开始玩成语接龙吧" → 回复: "好呀！我先来：一心一意，该你了！"
            """;

    private final ZhipuChatService chatService;

    public GameAgent(ZhipuChatService chatService) { this.chatService = chatService; }

    @Override public String name() { return "game"; }
    @Override public String intent() { return "game"; }
    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public String model() { return "glm-4-flash"; }
    @Override public double temperature() { return 0.6; }
    @Override public int maxTokens() { return 200; }

    @Override
    public ChatResponse handle(AgentContext context) {
        String userMsg = context.getSpeech() != null ? context.getSpeech() : "";
        return chatService.chat(model(), systemPrompt(), userMsg, temperature(), maxTokens());
    }
}

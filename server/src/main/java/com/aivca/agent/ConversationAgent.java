package com.aivca.agent;

import com.aivca.agent.model.AgentContext;
import com.aivca.agent.model.ChatResponse;
import com.aivca.util.HistoryFormatter;
import com.aivca.util.ResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

public class ConversationAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            你叫小灵，是一个友善的AI视觉助手。你能通过摄像头看到用户。
            
            交流规则:
            - 热情但简洁（2-3句），可以适当使用 emoji 😊
            - 如果画面描述中有用户动作，自然融入对话
            - 用户打招呼时热情回应，用户说再见时温馨告别
            - 不确定时可以说"我不太确定呢"
            - 输出JSON: {"text":"回复","action":"idle|wave|point|nod","expression":"happy|curious|neutral|surprised"}
            """;

    private final ChatLanguageModel chatModel;
    private final ChatLanguageModel summarizeModel;
    private final ObjectMapper objectMapper;

    public ConversationAgent(ChatLanguageModel chatModel, ChatLanguageModel summarizeModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.summarizeModel = summarizeModel;
        this.objectMapper = objectMapper;
    }

    @Override public String name() { return "conversation"; }
    @Override public String intent() { return "conversation"; }
    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public double temperature() { return 0.7; }
    @Override public int maxTokens() { return 150; }
    @Override public ChatLanguageModel getChatModel() { return chatModel; }
    @Override public ChatLanguageModel getSummarizeModel() { return summarizeModel; }

    @Override public boolean useSummary() { return false; }
    @Override public int maxRawTurns() { return 0; }
    @Override public String fallbackText() { return "嗨，我暂时有点卡，稍等一下哦～"; }

    @Override
    public ChatResponse handle(AgentContext ctx) {
        String userMsg = HistoryFormatter.buildContext(this, ctx);
        try {
            var resp = chatModel.generate(
                    SystemMessage.from(systemPrompt()),
                    UserMessage.from(userMsg));
            return ResponseParser.parse(resp.content().text(), objectMapper);
        } catch (Exception e) {
            return new ChatResponse(fallbackText(), "idle", "neutral");
        }
    }
}

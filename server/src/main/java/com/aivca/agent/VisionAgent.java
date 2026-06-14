package com.aivca.agent;

import com.aivca.agent.model.AgentContext;
import com.aivca.agent.model.ChatResponse;
import com.aivca.constant.UrgencyLevel;
import com.aivca.util.HistoryFormatter;
import com.aivca.util.ResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

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

    private final ChatLanguageModel chatModel;
    private final ChatLanguageModel summarizeModel;
    private final ObjectMapper objectMapper;

    public VisionAgent(ChatLanguageModel chatModel, ChatLanguageModel summarizeModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.summarizeModel = summarizeModel;
        this.objectMapper = objectMapper;
    }

    @Override public String name() { return "vision"; }
    @Override public String intent() { return "vision"; }
    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public double temperature() { return 0.3; }
    @Override public int maxTokens() { return 200; }
    @Override public ChatLanguageModel getChatModel() { return chatModel; }
    @Override public ChatLanguageModel getSummarizeModel() { return summarizeModel; }
    @Override public String fallbackText() { return "抱歉画面分析出了点问题，能再说一遍吗？"; }

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

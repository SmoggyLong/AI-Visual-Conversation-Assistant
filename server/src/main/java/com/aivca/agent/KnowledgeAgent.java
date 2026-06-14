package com.aivca.agent;

import com.aivca.agent.model.AgentContext;
import com.aivca.agent.model.ChatResponse;
import com.aivca.util.HistoryFormatter;
import com.aivca.util.ResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

public class KnowledgeAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            你是知识技术助手。
            回复要求:
            - 简洁专业，直接解决问题
            - 如有次要意图涉及画面内容，顺便自然回应
            - 3-5句，不要长篇分析
            - 输出JSON: {"text":"回复","action":"idle|point","expression":"neutral|curious"}
            """;

    private final ChatLanguageModel chatModel;
    private final ChatLanguageModel summarizeModel;
    private final ObjectMapper objectMapper;

    public KnowledgeAgent(ChatLanguageModel chatModel, ChatLanguageModel summarizeModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.summarizeModel = summarizeModel;
        this.objectMapper = objectMapper;
    }

    @Override public String name() { return "knowledge"; }
    @Override public String intent() { return "knowledge"; }
    @Override public String systemPrompt() { return SYSTEM_PROMPT; }
    @Override public double temperature() { return 0.3; }
    @Override public int maxTokens() { return 300; }
    @Override public ChatLanguageModel getChatModel() { return chatModel; }
    @Override public ChatLanguageModel getSummarizeModel() { return summarizeModel; }

    @Override public int maxRawTurns() { return 5; }
    @Override public String fallbackText() { return "抱歉，当前无法查询，请稍后再试。"; }

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

package com.aivca.agent;

import com.aivca.agent.model.AgentContext;
import com.aivca.agent.model.ChatResponse;
import com.aivca.rag.KnowledgeBase;
import com.aivca.rag.QueryRewriter;
import com.aivca.rag.Reranker;
import com.aivca.rag.SearchHit;
import com.aivca.util.HistoryFormatter;
import com.aivca.util.ResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class KnowledgeAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            你是知识技术助手。你可以参考知识库内容回答问题。
            
            回复要求:
            - 简洁专业，直接解决问题
            - 如果知识库提供了答案，优先依据知识库内容回答，必要时标注来源
            - 如果知识库内容不足以回答，结合你的知识补充
            - 3-5句，不要长篇分析
            - 输出JSON: {"text":"回复","action":"idle|point","expression":"neutral|curious"}
            """;

    private final ChatLanguageModel chatModel;
    private final ChatLanguageModel summarizeModel;
    private final ObjectMapper objectMapper;
    private final KnowledgeBase knowledgeBase;
    private final QueryRewriter rewriter;
    private final Reranker reranker;

    public KnowledgeAgent(ChatLanguageModel chatModel, ChatLanguageModel summarizeModel,
                           ObjectMapper objectMapper, KnowledgeBase knowledgeBase,
                           QueryRewriter rewriter, Reranker reranker) {
        this.chatModel = chatModel;
        this.summarizeModel = summarizeModel;
        this.objectMapper = objectMapper;
        this.knowledgeBase = knowledgeBase;
        this.rewriter = rewriter;
        this.reranker = reranker;
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
        String query = ctx.getSpeech();
        String knowledgeCtx = null;

        // RAG 检索管线（KNOWLEDGE 意图必然执行）
        if (knowledgeBase.isReady() && query != null && !query.isBlank()) {
            try {
                // 1. Query Rewrite
                List<String> queries = rewriter.rewrite(query, 3);

                // 2. 混合检索
                List<SearchHit> hits = knowledgeBase.searchHybrid(queries, 5);

                // 3. Rerank → topK=3
                if (!hits.isEmpty()) {
                    List<SearchHit> top = reranker.rerank(query, hits, 3);
                    knowledgeCtx = formatKnowledge(top);
                    log.info("[RAG] 检索完成 | query={} | hits={} | top={}",
                            query.substring(0, Math.min(30, query.length())),
                            hits.size(), top.size());
                }
            } catch (Exception e) {
                log.warn("[RAG] 检索异常: {}", e.getMessage());
            }
        }

        // 4. 拼接 prompt
        String userMsg = HistoryFormatter.buildContext(this, ctx);
        String prompt;
        if (knowledgeCtx != null && !knowledgeCtx.isEmpty()) {
            prompt = knowledgeCtx + "\n\n[用户输入]\n" + userMsg;
        } else {
            prompt = userMsg;
        }

        try {
            var resp = chatModel.generate(
                    SystemMessage.from(systemPrompt()),
                    UserMessage.from(prompt));
            return ResponseParser.parse(resp.content().text(), objectMapper);
        } catch (Exception e) {
            return new ChatResponse(fallbackText(), "idle", "neutral");
        }
    }

    private String formatKnowledge(List<SearchHit> hits) {
        StringBuilder sb = new StringBuilder("[知识库]\n");
        for (int i = 0; i < hits.size(); i++) {
            var h = hits.get(i);
            sb.append(i + 1).append(". 来源: ").append(h.sourceLabel())
              .append(" (相关度: ").append(String.format("%.2f", h.score())).append(")\n")
              .append("   内容: ").append(h.content()).append("\n");
        }
        sb.append("\n请优先依据以上知识库内容回答。如果知识库内容不足以回答，再结合你的知识。");
        return sb.toString();
    }
}

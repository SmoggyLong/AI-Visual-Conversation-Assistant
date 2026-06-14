package com.aivca.rag.eval;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * LLM 裁判 —— 4 维度评测 Agent 回答质量。
 */
@Slf4j
@Component
public class LLMJudge {

    private static final String SYSTEM_PROMPT = """
            你是回答质量评估专家。对回答按4个维度打分(0.0-1.0)，只输出JSON。
            """;

    private static final String PROMPT = """
            评估以下对话的回答质量。按0.0-1.0打分，只输出JSON，不要解释:
            {"relevance":0.0,"accuracy":0.0,"completeness":0.0,"helpfulness":0.0}
            
            评分标准:
            - relevance(相关性): 是否直接回应用户话题(视觉对话中自然口语也算回应)
            - accuracy(准确性): 信息是否正确(视觉描述是否与画面上下文一致)
            - completeness(完整性): 是否覆盖了关键信息
            - helpfulness(实用性): 用户能否据此继续对话或采取行动
            
            注意: 视觉对话Agent用自然口语(如"你今天这件衬衫很好看")而非报告式回答,这不算跑题。
            
            用户: %s
            回答: %s
            %s
            """;

    private final ChatLanguageModel model;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public LLMJudge(@Qualifier("deepseekModel") ChatLanguageModel model) {
        this.model = model;
    }

    /** 对回答打分 */
    public JudgeScores judge(String query, String response, String knowledgeContext) {
        String kctx = knowledgeContext != null && !knowledgeContext.isEmpty()
                ? "知识库参考: " + knowledgeContext : "";

        try {
            var resp = model.generate(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(String.format(PROMPT, query, truncate(response, 500), kctx)));

            String json = resp.content().text();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) json = json.substring(start, end + 1);

            var node = mapper.readTree(json);
            return new JudgeScores(
                    node.has("relevance") ? node.get("relevance").asDouble(0.5) : 0.5,
                    node.has("accuracy") ? node.get("accuracy").asDouble(0.5) : 0.5,
                    node.has("completeness") ? node.get("completeness").asDouble(0.5) : 0.5,
                    node.has("helpfulness") ? node.get("helpfulness").asDouble(0.5) : 0.5
            );
        } catch (Exception e) {
            log.warn("[JUDGE] 打分失败: {}", e.getMessage());
            return new JudgeScores(0.5, 0.5, 0.5, 0.5);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 4 维评分 */
    public record JudgeScores(double relevance, double accuracy, double completeness, double helpfulness) {
        public double overall() {
            return (relevance + accuracy + completeness + helpfulness) / 4.0;
        }
    }
}

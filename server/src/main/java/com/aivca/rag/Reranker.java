package com.aivca.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排序器 —— 用 LLM 对检索候选打分排序。
 */
@Slf4j
@Component
public class Reranker {

    private static final String SYSTEM_PROMPT = "你是搜索结果评估助手。对候选文档与查询的相关性打分(0-10)，只输出JSON数组。";

    private final ChatLanguageModel model;
    private final ObjectMapper objectMapper;

    public Reranker(@Qualifier("deepseekModel") ChatLanguageModel model,
                     ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;
    }

    /**
     * 对候选结果重排序，返回 topK。
     *
     * @param query      原始用户问题
     * @param candidates 待排序的候选列表
     * @param topK       返回数量
     * @return 重排序后的 topK 结果
     */
    public List<SearchHit> rerank(String query, List<SearchHit> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (candidates.size() <= topK) return candidates;

        // 先按 score 初排 → 取前 10 给 LLM 精排
        List<SearchHit> trimmed = candidates.stream()
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(10)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("查询: ").append(query).append("\n\n");
        for (int i = 0; i < trimmed.size(); i++) {
            var h = trimmed.get(i);
            sb.append("候选").append(i).append(": ").append(h.title()).append("\n");
            String content = h.content();
            sb.append(content, 0, Math.min(250, content.length())).append("\n\n");
        }
        sb.append("按相关性打0-10分，输出: [{\"id\":0,\"score\":9}]");

        try {
            var resp = model.generate(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(sb.toString()));
            String json = resp.content().text();
            // 提取 JSON 数组
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            List<Map<String, Object>> rawScores = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class,
                            objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)));
            if (rawScores != null && !rawScores.isEmpty()) {
                var scoreMap = new LinkedHashMap<Integer, Double>();
                for (var item : rawScores) {
                    int id = ((Number) item.getOrDefault("id", -1)).intValue();
                    double score = ((Number) item.getOrDefault("score", 0.0)).doubleValue();
                    if (id >= 0 && id < trimmed.size()) {
                        scoreMap.put(id, score);
                    }
                }
                return trimmed.stream()
                        .filter(h -> scoreMap.containsKey(trimmed.indexOf(h)))
                        .sorted((a, b) -> Double.compare(
                                scoreMap.getOrDefault(trimmed.indexOf(b), 0.0),
                                scoreMap.getOrDefault(trimmed.indexOf(a), 0.0)))
                        .limit(topK)
                        .toList();
            }
        } catch (Exception e) {
            log.warn("[RERANK] 重排序失败, 降级原始顺序: {}", e.getMessage());
        }

        return trimmed.stream().limit(topK).toList();
    }
}

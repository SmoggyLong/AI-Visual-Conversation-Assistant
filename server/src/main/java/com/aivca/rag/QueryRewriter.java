package com.aivca.rag;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 查询改写器 —— 用 LLM 将用户问题展开为多个不同角度的子查询。
 */
@Slf4j
@Component
public class QueryRewriter {

    private static final String PROMPT = """
            将以下用户问题改写为3个简短的搜索查询（每行一个，不要编号）：
            每个查询覆盖问题的不同侧面（步骤、政策、排查等）。
            
            用户问题: %s
            """;

    private final ChatLanguageModel model;

    public QueryRewriter(@Qualifier("deepseekModel") ChatLanguageModel model) {
        this.model = model;
    }

    /**
     * 改写查询为 n 个不同角度的子查询。
     *
     * @param query 原始用户问题
     * @param n     目标子查询数量
     * @return 子查询列表（始终包含原始 query）
     */
    public List<String> rewrite(String query, int n) {
        if (query == null || query.isBlank()) return List.of();
        if (query.length() < 10) return List.of(query);

        var result = new LinkedHashSet<String>();
        result.add(query);  // 原始 query 始终保留

        try {
            var resp = model.generate(UserMessage.from(String.format(PROMPT, query)));
            String text = resp.content().text();
            if (text != null) {
                for (String line : text.split("\n")) {
                    String q = line.trim()
                            .replaceFirst("^[\\d]+[、.。）]\\s*", "")  // 去数字前缀
                            .replaceAll("^[\"']|[\"']$", "")            // 去引号
                            .trim();
                    if (!q.isEmpty() && q.length() >= 2 && !q.equals(query)) {
                        result.add(q);
                    }
                    if (result.size() >= n + 1) break;
                }
            }
        } catch (Exception e) {
            log.warn("[REWRITE] 查询改写失败: {}", e.getMessage());
        }

        log.debug("[REWRITE] {} → {} 个子查询", query, result.size());
        return new ArrayList<>(result);
    }
}

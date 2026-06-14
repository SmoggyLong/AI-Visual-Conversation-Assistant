package com.aivca.util;

import com.aivca.agent.model.ChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * LLM 响应解析器 —— 从模型输出的文本中提取 JSON 并解析为 ChatResponse。
 */
@Slf4j
public final class ResponseParser {

    private ResponseParser() {}

    /**
     * 从 LLM 原始输出解析 ChatResponse。
     *
     * @param rawContent 模型返回的 text 内容（可能包含 markdown 包围或前置文本）
     * @param mapper     Jackson ObjectMapper
     * @return 解析后的 ChatResponse，解析失败时 text 为原始内容
     */
    public static ChatResponse parse(String rawContent, ObjectMapper mapper) {
        String text = rawContent;
        String action = "idle";
        String expression = "neutral";
        String idiom = null;

        String content = extractJsonContent(rawContent);

        try {
            JsonNode root = mapper.readTree(content);
            if (root.has("text")) text = root.get("text").asText();
            if (root.has("action")) action = root.get("action").asText();
            if (root.has("expression")) expression = root.get("expression").asText();
            if (root.has("idiom")) idiom = root.get("idiom").asText();
        } catch (Exception e) {
            if (!content.equals(rawContent)) {
                log.debug("[PARSE] JSON 解析失败，使用剥离后文本");
            }
        }

        if (action == null || action.isEmpty()) action = "idle";
        if (expression == null || expression.isEmpty()) expression = "neutral";

        ChatResponse resp = new ChatResponse(text, action, expression);
        resp.setIdiom(idiom);
        return resp;
    }

    /** 从任意文本中提取 JSON 对象（{...}），兼容前置文本和各种代码块包围 */
    public static String extractJsonContent(String content) {
        String s = content.trim();
        int braceStart = s.indexOf('{');
        int braceEnd = s.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return s.substring(braceStart, braceEnd + 1).trim();
        }
        return s;
    }
}

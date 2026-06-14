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
            // 第一次解析失败 → 尝试修复中文标点后重试
            String fixed = fixChinesePunctuation(content);
            if (!fixed.equals(content)) {
                try {
                    JsonNode root = mapper.readTree(fixed);
                    if (root.has("text")) text = root.get("text").asText();
                    if (root.has("action")) action = root.get("action").asText();
                    if (root.has("expression")) expression = root.get("expression").asText();
                    if (root.has("idiom")) idiom = root.get("idiom").asText();
                    log.debug("[PARSE] 修复中文标点后解析成功");
                } catch (Exception ignored) {
                    log.debug("[PARSE] JSON 解析失败，修复重试也失败");
                }
            } else {
                log.debug("[PARSE] JSON 解析失败");
            }
        }

        if (action == null || action.isEmpty()) action = "idle";
        if (expression == null || expression.isEmpty()) expression = "neutral";

        ChatResponse resp = new ChatResponse(text, action, expression);
        resp.setIdiom(idiom);
        return resp;
    }

    /**
     * 修复 LLM 输出的中文标点 → 标准 JSON 标点。
     * 模型有时会用中文标点当 JSON 分隔符：，→,  ：→:  "→"  "→"
     */
    private static String fixChinesePunctuation(String json) {
        return json
                .replace('\uff0c', ',')   // 中文逗号 ，
                .replace('\uff1a', ':')    // 中文冒号 ：
                .replace('\u201c', '"')    // 左双引号 "
                .replace('\u201d', '"')    // 右双引号 "
                .replace('\u3001', ',');    // 顿号 、
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

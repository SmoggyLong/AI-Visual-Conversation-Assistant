package com.aivca.api.llm;

import com.aivca.api.llm.model.ChatResponse;
import com.aivca.util.HttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * LLM 调用封装（兼容智谱和 DeepSeek）。
 */
@Slf4j
public class ZhipuChatService {

    private final String apiKey;
    private final String baseUrl;
    private final ObjectMapper objectMapper;

    public ZhipuChatService(String apiKey, String baseUrl, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
    }

    /** DeepSeek 专用构造 */
    public static ZhipuChatService forDeepSeek(String apiKey, ObjectMapper om) {
        return new ZhipuChatService(apiKey, "https://api.deepseek.com/v1/chat/completions", om);
    }

    /** 智谱专用构造 */
    public static ZhipuChatService forZhipu(String apiKey, ObjectMapper om) {
        return new ZhipuChatService(apiKey, "https://open.bigmodel.cn/api/paas/v4/chat/completions", om);
    }

    /**
     * 调用 LLM 获取回复。
     */
    public ChatResponse chat(String model, String systemPrompt, String userMessage,
                              double temperature, int maxTokens) {
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "temperature", temperature,
                    "messages", java.util.List.of(
                            java.util.Map.of("role", "system", "content", systemPrompt),
                            java.util.Map.of("role", "user", "content", userMessage)
                    )
            ));

            log.debug("[CHAT] 请求 {} | model={} | bodySize={}", baseUrl, model, body.length());
            JsonNode root = HttpUtil.postJsonWithAuth(baseUrl, body, apiKey, objectMapper);

            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode msg = choices.get(0).get("message");
                if (msg != null) {
                    String content = extractContent(msg);
                    if (content != null) {
                        log.info("[CHAT] 回复: {}", content.length() > 100 ? content.substring(0, 100) : content);
                        return parseChatResponse(content);
                    }
                }
            }

            if (root.has("error")) {
                log.error("[CHAT] 返回错误: {}", root.get("error").get("message").asText("?"));
            }
        } catch (Exception e) {
            log.error("[CHAT] 调用失败", e);
        }
        return new ChatResponse("我暂时无法回复，请稍后再试。", "idle", "neutral");
    }

    private String extractContent(JsonNode message) {
        JsonNode node = message.get("content");
        if (node == null) return null;
        if (node.isTextual()) return node.asText().trim();
        if (node.isArray()) {
            for (JsonNode p : node) {
                if ("text".equals(p.get("type").asText(""))) return p.get("text").asText().trim();
            }
        }
        return null;
    }

    private ChatResponse parseChatResponse(String content) {
        String text = content;
        String action = "idle";
        String expression = "neutral";

        try {
            JsonNode root = objectMapper.readTree(content);
            if (root.has("text")) text = root.get("text").asText();
            if (root.has("action")) action = root.get("action").asText();
            if (root.has("expression")) expression = root.get("expression").asText();
        } catch (Exception e) {
            // 不是 JSON，直接用原始文本
        }

        // 容错：action/expression 做默认值
        if (action == null || action.isEmpty()) action = "idle";
        if (expression == null || expression.isEmpty()) expression = "neutral";

        return new ChatResponse(text, action, expression);
    }
}

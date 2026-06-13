package com.aivca.api.llm.router;

import com.aivca.constant.IntentType;
import com.aivca.constant.UrgencyLevel;
import com.aivca.api.llm.model.IntentResult;
import com.aivca.util.HttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 意图识别器。
 *
 * 调用 glm-4.5-air 对用户语音+视觉上下文的组合文本做联合分类，
 * 同时输出意图类别和紧急程度。
 */
@Slf4j
public class IntentRecognizer {

    private static final String ZHIPU_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String MODEL = "glm-4.5-air";
    private static final int MAX_TOKENS = 300;   // reasoning ~100 + JSON ~50
    private static final double MIN_CONFIDENCE = 0.6;

    private final String apiKey;
    private final ObjectMapper objectMapper;

    public IntentRecognizer(String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    /**
     * 识别用户意图和紧急程度。
     *
     * @param context ContextBuilder.build() 的输出文本（可为 null）
     * @return 识别结果；失败时降级为 (GENERAL, NORMAL)
     */
    public IntentResult recognize(String context) {
        if (context == null || context.isBlank()) {
            return new IntentResult(IntentType.GENERAL, UrgencyLevel.LOW, "上下文为空", 0.0);
        }

        try {
            String textPrompt = buildPrompt(context);
            String body = buildRequestBody(textPrompt);
            log.debug("[INTENT] 请求 LLM | bodySize={}", body.length());

            JsonNode root = HttpUtil.postJsonWithAuth(ZHIPU_URL, body, apiKey, objectMapper);
            log.debug("[INTENT] HTTP 原始响应 | body={}",
                    root.toString().length() > 300 ? root.toString().substring(0, 300) : root.toString());

            JsonNode choices = root.get("choices");

            if (choices != null && choices.isArray() && choices.size() > 0) {
                String content = extractContent(choices.get(0));
                if (content != null) {
                    return parseResult(content);
                }
            }

            if (root.has("error")) {
                log.error("[INTENT] 智谱返回错误: {}", root.get("error").get("message").asText("?"));
            }

        } catch (Exception e) {
            log.error("[INTENT] 识别失败", e);
        }

        return new IntentResult(IntentType.GENERAL, UrgencyLevel.NORMAL, "识别失败，降级", 0.0);
    }

    // ==================== private ====================

    private String buildPrompt(String context) {
        return String.format("""
                %s
                
                意图: %s。紧急度: %s。
                只输出JSON: {"intent":"xxx","urgency":"xxx","confidence":0.0-1.0,"reasoning":"xxx"}
                """,
                context != null ? context : "",
                IntentType.promptOptions(),
                UrgencyLevel.promptOptions()
        );
    }

    private String buildRequestBody(String textPrompt) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "model", MODEL,
                    "max_tokens", MAX_TOKENS,
                    "temperature", 0.1,
                    "response_format", java.util.Map.of("type", "json_object"),
                    "messages", java.util.List.of(java.util.Map.of(
                            "role", "user",
                            "content", java.util.List.of(java.util.Map.of("type", "text", "text", textPrompt))
                    ))
            ));
        } catch (Exception e) {
            log.error("[INTENT] 构建请求体失败", e);
            return "";
        }
    }

    private String extractContent(JsonNode choice) {
        JsonNode message = choice.get("message");
        if (message == null) return null;

        JsonNode contentNode = message.get("content");
        if (contentNode == null) return null;

        if (contentNode.isTextual()) return contentNode.asText().trim();
        if (contentNode.isArray()) {
            for (JsonNode part : contentNode) {
                if ("text".equals(part.get("type").asText(""))) {
                    return part.get("text").asText().trim();
                }
            }
        }
        return null;
    }

    private IntentResult parseResult(String content) {
        log.info("[INTENT] 识别结果: {}", content);
        try {
            JsonNode root = objectMapper.readTree(content);
            IntentType intent = IntentType.fromString(root.get("intent").asText(""));
            UrgencyLevel urgency = UrgencyLevel.fromString(root.get("urgency").asText(""));
            String reasoning = root.has("reasoning") ? root.get("reasoning").asText("") : "";
            double confidence = root.has("confidence") ? root.get("confidence").asDouble(0.5) : 0.5;

            // 置信度过低 → 降级为 general
            if (confidence < MIN_CONFIDENCE) {
                log.info("[INTENT] 置信度过低({})，降级为 general", confidence);
                return new IntentResult(IntentType.GENERAL, UrgencyLevel.NORMAL, reasoning, confidence);
            }

            return new IntentResult(intent, urgency, reasoning, confidence);
        } catch (Exception e) {
            log.warn("[INTENT] JSON 解析失败: {}", content);
            return new IntentResult(IntentType.GENERAL, UrgencyLevel.NORMAL, "JSON解析失败", 0.0);
        }
    }
}

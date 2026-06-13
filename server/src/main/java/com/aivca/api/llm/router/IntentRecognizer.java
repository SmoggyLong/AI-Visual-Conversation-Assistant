package com.aivca.api.llm.router;

import com.aivca.constant.IntentType;
import com.aivca.constant.UrgencyLevel;
import com.aivca.api.llm.model.IntentResult;
import com.aivca.util.HttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 意图识别器 — DeepSeek 版。
 *
 * 调用 deepseek-chat 对用户语音+视觉上下文的组合文本做联合分类。
 */
@Slf4j
public class IntentRecognizer {

    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String MODEL = "deepseek-chat";
    private static final int MAX_TOKENS = 150;
    private static final double MIN_CONFIDENCE = 0.6;

    private static final String SYSTEM_PROMPT = "你是意图分类器。只输出JSON，不要任何解释或额外文字。";

    private final String apiKey;
    private final ObjectMapper objectMapper;

    public IntentRecognizer(String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    /**
     * 识别用户意图和紧急程度。
     *
     * @param context ContextBuilder.build() 的输出文本
     * @return 识别结果；失败时降级为 (GENERAL, NORMAL)
     */
    public IntentResult recognize(String context) {
        if (context == null || context.isBlank()) {
            return new IntentResult(IntentType.GENERAL, UrgencyLevel.LOW, "上下文为空", 0.0);
        }

        try {
            String body = buildRequestBody(context);
            log.debug("[INTENT] 请求 DeepSeek | bodySize={}", body.length());

            JsonNode root = HttpUtil.postJsonWithAuth(DEEPSEEK_URL, body, apiKey, objectMapper);
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
                log.error("[INTENT] DeepSeek 返回错误: {}", root.get("error").get("message").asText("?"));
            }

        } catch (Exception e) {
            log.error("[INTENT] 识别失败", e);
        }

        return new IntentResult(IntentType.GENERAL, UrgencyLevel.NORMAL, "识别失败，降级", 0.0);
    }

    // ==================== private ====================

    private String buildRequestBody(String context) {
        String userPrompt = String.format("""
                %s

                意图(%s) 紧急度(%s)
                示例: {"intent":"greeting","urgency":"low","confidence":0.95,"reasoning":"打招呼"}
                现在输出:
                """,
                context != null ? context : "",
                IntentType.promptOptions(),
                UrgencyLevel.promptOptions()
        );

        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "model", MODEL,
                    "max_tokens", MAX_TOKENS,
                    "temperature", 0.0,
                    "messages", java.util.List.of(
                            java.util.Map.of("role", "system", "content", SYSTEM_PROMPT),
                            java.util.Map.of("role", "user", "content", userPrompt)
                    )
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

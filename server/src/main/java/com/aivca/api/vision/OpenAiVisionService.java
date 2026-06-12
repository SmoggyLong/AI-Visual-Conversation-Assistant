package com.aivca.api.vision;

import com.aivca.constant.VisionConstants;
import com.aivca.util.HttpUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Vision 服务（GPT-4o-mini）。
 *
 * 通过 REST API POST base64 JPEG 图片，返回画面自然语言描述。
 * 使用 low detail 模式（固定 85 tokens），成本最优。
 */
@Slf4j
public class OpenAiVisionService implements VisionService {

    private final String apiKey;
    private final ObjectMapper objectMapper;

    public OpenAiVisionService(String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public String describe(String base64Jpeg) {
        try {
            String body = buildRequestBody(base64Jpeg);
            log.debug("[VISION] 请求 OpenAI | bodySize={}B", body.length());

            JsonNode root = HttpUtil.postJson(VisionConstants.OPENAI_VISION_URL, body, objectMapper);

            // 解析响应
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null) {
                    String content = message.get("content").asText();
                    log.info("[VISION] 分析结果: {}", content);
                    return content.trim();
                }
            }

            // API 返回了错误
            if (root.has("error")) {
                String errMsg = root.get("error").get("message").asText("未知错误");
                log.error("[VISION] OpenAI 返回错误: {}", errMsg);
                return "";
            }

            log.warn("[VISION] 响应格式异常: {}", root.toString());
            return "";

        } catch (Exception e) {
            log.error("[VISION] 请求失败", e);
            return "";
        }
    }

    @Override
    public void close() {}

    // ==================== private ====================

    private String buildRequestBody(String base64Jpeg) throws JsonProcessingException {
        // messages[0].content = [image_url part, text part]
        List<Map<String, Object>> content = new ArrayList<>();

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", Map.of(
                "url", "data:image/jpeg;base64," + base64Jpeg,
                "detail", VisionConstants.VISION_DETAIL
        ));
        content.add(imagePart);

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", VisionConstants.VISION_PROMPT);
        content.add(textPart);

        // 完整请求体
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", VisionConstants.VISION_MODEL);
        body.put("max_tokens", VisionConstants.MAX_TOKENS);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));

        return objectMapper.writeValueAsString(body);
    }
}

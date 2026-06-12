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
 * 智谱 GLM-4V 视觉服务。
 *
 * 通过 REST API POST base64 JPEG 图片，返回画面自然语言描述。
 * 端点：open.bigmodel.cn/api/paas/v4/chat/completions
 *
 * 注意：GLM-4V 不支持 OpenAI 的 detail 参数，图片 URL 直接传。
 */
@Slf4j
public class ZhipuVisionService implements VisionService {

    private final String apiKey;
    private final ObjectMapper objectMapper;

    public ZhipuVisionService(String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public String describe(String base64Jpeg) {
        try {
            String body = buildRequestBody(base64Jpeg);
            log.debug("[VISION] 请求智谱 GLM-4V | bodySize={}B", body.length());

            JsonNode root = HttpUtil.postJsonWithAuth(
                    VisionConstants.ZHIPU_VISION_URL, body, apiKey, objectMapper);

            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null) {
                    // 智谱 content 可能是字符串，也可能是对象数组
                    JsonNode contentNode = message.get("content");
                    String content = null;
                    if (contentNode != null) {
                        if (contentNode.isTextual()) {
                            content = contentNode.asText();
                        } else if (contentNode.isArray()) {
                            // content 是数组时取第一个 text 部分
                            for (JsonNode part : contentNode) {
                                if ("text".equals(part.get("type").asText(""))) {
                                    content = part.get("text").asText();
                                    break;
                                }
                            }
                        }
                    }
                    if (content == null) {
                        log.warn("[VISION] content 格式未知 | message={}", message.toString());
                        return "";
                    }
                    log.info("[VISION] 分析结果: {}", content);
                    return content.trim();
                }
            }

            if (root.has("error")) {
                String errMsg = root.get("error").get("message").asText("未知错误");
                log.error("[VISION] 智谱返回错误: {}", errMsg);
                return "";
            }

            log.warn("[VISION] 响应无有效内容 | body={}",
                    root.toString().length() > 200 ? root.toString().substring(0, 200) : root.toString());
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
        List<Map<String, Object>> content = new ArrayList<>();

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", Map.of("url", "data:image/jpeg;base64," + base64Jpeg));
        content.add(imagePart);

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", VisionConstants.VISION_PROMPT);
        content.add(textPart);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", VisionConstants.VISION_MODEL);
        body.put("max_tokens", VisionConstants.MAX_TOKENS);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));

        return objectMapper.writeValueAsString(body);
    }
}

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
 * 智谱 GLM-4V 视觉服务（单帧 + 连续帧批次）。
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
            String body = buildRequest(List.of(base64Jpeg));
            log.debug("[VISION] 请求智谱 GLM-4V | bodySize={}B", body.length());
            return parseResponse(HttpUtil.postJsonWithAuth(
                    VisionConstants.ZHIPU_VISION_URL, body, apiKey, objectMapper));
        } catch (Exception e) {
            log.error("[VISION] 请求失败", e);
            return "";
        }
    }

    @Override
    public String describeBatch(List<String> base64Frames) {
        if (base64Frames == null || base64Frames.isEmpty()) return "";
        if (base64Frames.size() == 1) return describe(base64Frames.get(0));

        log.info("[VISION] 批量分析 {} 帧", base64Frames.size());
        try {
            String body = buildRequest(base64Frames);
            log.debug("[VISION] 请求智谱 GLM-4V | frames={} | bodySize={}B",
                    base64Frames.size(), body.length());
            return parseResponse(HttpUtil.postJsonWithAuth(
                    VisionConstants.ZHIPU_VISION_URL, body, apiKey, objectMapper));
        } catch (Exception e) {
            log.error("[VISION] 批量请求失败", e);
            return "";
        }
    }

    @Override
    public void close() {}

    // ==================== private ====================

    private String parseResponse(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode message = choices.get(0).get("message");
            if (message != null) {
                JsonNode contentNode = message.get("content");
                String content = null;
                if (contentNode != null) {
                    if (contentNode.isTextual()) {
                        content = contentNode.asText();
                    } else if (contentNode.isArray()) {
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
    }

    private String buildRequest(List<String> base64Frames) throws JsonProcessingException {
        List<Map<String, Object>> content = new ArrayList<>();

        for (String frame : base64Frames) {
            Map<String, Object> imagePart = new LinkedHashMap<>();
            imagePart.put("type", "image_url");
            imagePart.put("image_url", Map.of("url", "data:image/jpeg;base64," + frame));
            content.add(imagePart);
        }

        String prompt = base64Frames.size() == 1
                ? VisionConstants.VISION_PROMPT
                : "这" + base64Frames.size() + "帧是连续的摄像头画面（200ms间隔），描述画面内容和人物的动作行为。输出格式：\n主体类型：xxx\n描述：xxx\n动作：xxx（如有）";

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        content.add(textPart);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", VisionConstants.VISION_MODEL);
        body.put("max_tokens", VisionConstants.MAX_TOKENS);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));

        return objectMapper.writeValueAsString(body);
    }
}

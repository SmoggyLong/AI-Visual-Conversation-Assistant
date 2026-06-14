package com.aivca.api.tts;

import com.aivca.util.HttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 智谱 TTS 服务 — 文本转语音（MP3 base64）。
 */
@Slf4j
public class ZhipuTtsService implements TtsService {

    private static final String TTS_URL = "https://open.bigmodel.cn/api/paas/v4/tts";

    private final String apiKey;
    private final ObjectMapper objectMapper;

    public ZhipuTtsService(String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public String synthesize(String text) {
        if (text == null || text.isBlank()) return null;

        // 截断到 200 字，避免 TTS 太长
        String shortText = text.length() > 200 ? text.substring(0, 200) : text;

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "tts-1");
            body.put("input", shortText);
            body.put("voice", "xiaoling");  // 小灵的声音
            body.put("response_format", "mp3");
            body.put("speed", 1.0);

            JsonNode root = HttpUtil.postJsonWithAuth(TTS_URL,
                    objectMapper.writeValueAsString(body), apiKey, objectMapper);

            if (root.has("audio")) {
                String audio = root.get("audio").asText();
                log.info("[TTS] 合成成功 | textLen={} | audioLen={}", shortText.length(), audio.length());
                return audio;
            }

            if (root.has("error")) {
                log.warn("[TTS] 返回错误: {}", root.get("error"));
            }
        } catch (Exception e) {
            log.warn("[TTS] 合成失败: {}", e.getMessage());
        }
        return null;
    }
}

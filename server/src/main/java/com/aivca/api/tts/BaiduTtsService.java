package com.aivca.api.tts;

import com.aivca.util.HttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 百度 TTS 服务 — 文本转语音（情感女声，MP3 base64）。
 *
 * 百度 TTS REST API: https://ai.baidu.com/ai-doc/SPEECH/Qk38y8lrl
 * 发音人 per=4 (度丫丫) — 情感女声，支持 happy/sad 等情绪。
 */
@Slf4j
public class BaiduTtsService implements TtsService {

    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String TTS_URL = "https://tsn.baidu.com/text2audio";

    private final String apiKey;
    private final String secretKey;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    private String cachedToken;
    private long tokenExpiresAt;

    public BaiduTtsService(String apiKey, String secretKey, ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.mapper = mapper;
    }

    @Override
    public String synthesize(String text) {
        if (text == null || text.isBlank()) return null;
        String shortText = text.length() > 200 ? text.substring(0, 200) : text;

        try {
            String token = getAccessToken();
            if (token == null) return null;

            String params = "tex=" + urlEncode(shortText)
                    + "&tok=" + urlEncode(token)
                    + "&cuid=avca"
                    + "&ctp=1"
                    + "&lan=zh"
                    + "&per=4"       // 度丫丫 — 情感女声
                    + "&spd=5"       // 语速 0-15, 5=正常
                    + "&pit=5"       // 音调
                    + "&vol=5"       // 音量
                    + "&aue=3";      // 音频格式: mp3

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(TTS_URL + "?" + params))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() == 200 && resp.body().length > 0) {
                String audio = Base64.getEncoder().encodeToString(resp.body());
                log.info("[TTS] 百度合成成功 | textLen={} | audioLen={}", shortText.length(), audio.length());
                return audio;
            }

            // 错误响应是 JSON
            if (resp.body().length > 0 && resp.body()[0] == '{') {
                String errJson = new String(resp.body(), StandardCharsets.UTF_8);
                log.warn("[TTS] 百度返回错误: {}", errJson);
            }

        } catch (Exception e) {
            log.warn("[TTS] 百度合成失败: {}", e.getMessage());
        }
        return null;
    }

    private String getAccessToken() throws IOException, InterruptedException {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return cachedToken;
        }

        String url = TOKEN_URL + "?grant_type=client_credentials"
                + "&client_id=" + apiKey
                + "&client_secret=" + secretKey;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body());

        if (root.has("access_token")) {
            cachedToken = root.get("access_token").asText();
            tokenExpiresAt = System.currentTimeMillis() + root.get("expires_in").asLong() * 1000 - 60000;
            return cachedToken;
        }

        log.error("[TTS] 获取access_token失败: {}", resp.body());
        return null;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

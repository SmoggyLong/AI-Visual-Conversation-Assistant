package com.aivca.service.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
public class BaiduSttService implements SttService {

    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String ASR_URL = "https://vop.baidu.com/server_api";

    private final String apiKey;
    private final String secretKey;
    private final ObjectMapper objectMapper;

    private SttCallback callback;
    private String accessToken;
    private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();

    public BaiduSttService(String apiKey, String secretKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start(SttCallback callback) {
        this.callback = callback;
        this.audioBuffer.reset();
        try {
            this.accessToken = fetchAccessToken();
            log.info("[STT] 百度 access_token 获取成功 | len={}", accessToken.length());
        } catch (Exception e) {
            log.error("[STT] 获取百度 access_token 失败", e);
            callback.onError("百度认证失败: " + e.getMessage());
        }
    }

    @Override
    public void sendAudio(byte[] pcmData) {
        try { audioBuffer.write(pcmData); } catch (IOException ignored) {}
    }

    @Override
    public void finish() {
        byte[] pcmBytes = audioBuffer.toByteArray();
        audioBuffer.reset();
        if (pcmBytes.length == 0) { callback.onFinal(""); return; }

        String base64Speech = Base64.getEncoder().encodeToString(pcmBytes);
        log.info("[STT] 发起百度识别 | pcm={}B", pcmBytes.length);
        try {
            String result = callAsr(base64Speech, pcmBytes.length);
            log.info("[STT] 识别结果: {}", result.isEmpty() ? "(空)" : result);
            callback.onFinal(result.isEmpty() ? "" : result);
        } catch (Exception e) {
            log.error("[STT] 识别请求失败", e);
            callback.onError("识别失败: " + e.getMessage());
        }
    }

    @Override
    public void close() { audioBuffer.reset(); }

    private String fetchAccessToken() throws IOException, InterruptedException {
        String url = TOKEN_URL + "?grant_type=client_credentials&client_id=" + apiKey + "&client_secret=" + secretKey;
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(resp.body());
        if (root.has("error")) throw new IOException(root.get("error").asText());
        return root.get("access_token").asText();
    }

    private String callAsr(String base64Speech, int rawLen) throws IOException, InterruptedException {
        String body = "format=pcm&rate=16000&channel=1&cuid=aivca"
                + "&token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8)
                + "&speech=" + base64Speech + "&len=" + rawLen;
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create(ASR_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        log.debug("[STT] ASR 响应: HTTP {}", resp.statusCode());
        JsonNode root = objectMapper.readTree(resp.body());
        if (root.has("err_no") && root.get("err_no").asInt() != 0)
            throw new IOException("err_no=" + root.get("err_no").asInt() + " err_msg=" + root.get("err_msg").asText("?"));
        if (root.has("result") && root.get("result").isArray() && root.get("result").size() > 0)
            return root.get("result").get(0).asText().trim();
        return "";
    }
}

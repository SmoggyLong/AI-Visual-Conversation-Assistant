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

/**
 * 百度短语音识别服务（REST API 实现）。
 *
 * 流程：
 * 1. start() → 获取 access_token
 * 2. sendAudio(pcm) → 累积 PCM 音频
 * 3. finish()  → 转 WAV → Base64 → POST /server_api → 返回文字
 */
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

        if (pcmBytes.length == 0) {
            log.warn("[STT] 音频数据为空，跳过识别");
            callback.onFinal("");
            return;
        }

        // PCM → WAV → Base64
        byte[] wavBytes = pcmToWav(pcmBytes, 16000, 1, 16);
        String base64Speech = Base64.getEncoder().encodeToString(wavBytes);

        log.info("[STT] 发起百度识别 | pcm={}B | wav={}B", pcmBytes.length, wavBytes.length);

        try {
            String result = callAsr(base64Speech, wavBytes.length);
            log.info("[STT] 识别结果: {}", result.isEmpty() ? "(空)" : result);
            callback.onFinal(result.isEmpty() ? "" : result);
        } catch (Exception e) {
            log.error("[STT] 识别请求失败", e);
            callback.onError("识别失败: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        audioBuffer.reset();
    }

    // ==================== private ====================

    private String fetchAccessToken() throws IOException, InterruptedException {
        String url = TOKEN_URL
                + "?grant_type=client_credentials"
                + "&client_id=" + apiKey
                + "&client_secret=" + secretKey;

        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        log.debug("[STT] access_token 响应: HTTP {}", resp.statusCode());

        JsonNode root = objectMapper.readTree(resp.body());
        if (root.has("error")) {
            throw new IOException(root.get("error").asText() + " - " + root.get("error_description").asText(""));
        }
        return root.get("access_token").asText();
    }

    private String callAsr(String base64Speech, int rawLen) throws IOException, InterruptedException {
        String body = "format=pcm"
                + "&rate=16000"
                + "&channel=1"
                + "&cuid=aivca"
                + "&token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8)
                + "&speech=" + URLEncoder.encode(base64Speech, StandardCharsets.UTF_8)
                + "&len=" + rawLen;

        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ASR_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        log.debug("[STT] ASR 响应: HTTP {} | {}",
                resp.statusCode(),
                resp.body().length() > 300 ? resp.body().substring(0, 300) : resp.body());

        JsonNode root = objectMapper.readTree(resp.body());

        if (root.has("err_no") && root.get("err_no").asInt() != 0) {
            throw new IOException("err_no=" + root.get("err_no").asInt()
                    + " err_msg=" + root.get("err_msg").asText("?"));
        }
        if (root.has("result") && root.get("result").isArray() && root.get("result").size() > 0) {
            return root.get("result").get(0).asText().trim();
        }
        return "";
    }

    // PCM Int16 → WAV (44-byte header + data)
    private static byte[] pcmToWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int dataSize = pcm.length;
        ByteArrayOutputStream wav = new ByteArrayOutputStream(44 + dataSize);
        try {
            wav.write("RIFF".getBytes());
            wav.write(intLE(36 + dataSize));
            wav.write("WAVE".getBytes());
            wav.write("fmt ".getBytes());
            wav.write(intLE(16));
            wav.write(shortLE((short) 1));
            wav.write(shortLE((short) channels));
            wav.write(intLE(sampleRate));
            wav.write(intLE(byteRate));
            wav.write(shortLE((short) (channels * bitsPerSample / 8)));
            wav.write(shortLE((short) bitsPerSample));
            wav.write("data".getBytes());
            wav.write(intLE(dataSize));
            wav.write(pcm);
        } catch (IOException ignored) {}
        return wav.toByteArray();
    }

    private static byte[] intLE(int v) { return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)}; }
    private static byte[] shortLE(short v) { return new byte[]{(byte) v, (byte) (v >> 8)}; }
}

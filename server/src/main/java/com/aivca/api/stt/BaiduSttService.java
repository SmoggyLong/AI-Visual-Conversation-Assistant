package com.aivca.api.stt;

import com.aivca.constant.SttConstants;
import com.aivca.util.HttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 百度短语音识别服务（REST API）。
 *
 * @see SttService 接口定义
 * @see SttConstants 音频/API 常量
 */
@Slf4j
public class BaiduSttService implements SttService {

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

    // ==================== SttService 实现 ====================

    @Override
    public void start(SttCallback callback) {
        this.callback = callback;
        this.audioBuffer.reset();
        try {
            this.accessToken = fetchAccessToken();
            log.info("[STT] 百度 access_token 获取成功 | len={}", accessToken.length());
        } catch (Exception e) {
            log.error("[STT] 获取 access_token 失败", e);
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
        callback.onInterim("正在识别...");

        try {
            String result = callAsr(base64Speech, pcmBytes.length);
            log.info("[STT] 识别结果: {}", result.isEmpty() ? "(空)" : result);
            streamText(result);
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
        String url = SttConstants.BAIDU_TOKEN_URL
                + "?grant_type=client_credentials"
                + "&client_id=" + apiKey
                + "&client_secret=" + secretKey;
        JsonNode root = HttpUtil.postEmpty(url, objectMapper);
        if (root.has("error")) throw new IOException(root.get("error").asText());
        return root.get("access_token").asText();
    }

    private String callAsr(String base64Speech, int rawLen) throws IOException, InterruptedException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("format", SttConstants.AUDIO_FORMAT);
        json.put("rate", SttConstants.AUDIO_RATE);
        json.put("channel", SttConstants.AUDIO_CHANNELS);
        json.put("cuid", SttConstants.BAIDU_CUID);
        json.put("token", accessToken);
        json.put("speech", base64Speech);
        json.put("len", rawLen);
        json.put("dev_pid", SttConstants.BAIDU_DEV_PID);

        String body = objectMapper.writeValueAsString(json);
        log.debug("[STT] ASR 请求体: {}B", body.length());

        JsonNode root = HttpUtil.postJson(SttConstants.BAIDU_ASR_URL, body, objectMapper);
        if (root.has("err_no") && root.get("err_no").asInt() != 0)
            throw new IOException("err_no=" + root.get("err_no").asInt()
                    + " err_msg=" + root.get("err_msg").asText("?"));
        if (root.has("result") && root.get("result").isArray() && root.get("result").size() > 0)
            return root.get("result").get(0).asText().trim();
        return "";
    }

    private void streamText(String text) {
        if (text.isEmpty()) { callback.onFinal(""); return; }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            sb.append(text.charAt(i));
            callback.onInterim(sb.toString());
            try { Thread.sleep(SttConstants.STREAM_DELAY_MS); } catch (InterruptedException ignored) {}
        }
        callback.onFinal(text);
    }
}

package com.aivca.service.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Base64;

/**
 * 百度实时流式语音识别服务（org.java-websocket 实现）。
 *
 * 通过纯 Java WebSocket 客户端连接百度 wss://vop.baidu.com/realtime_asr，
 * 流式发送 PCM 音频帧，实时接收中间/最终结果。
 *
 * token 放在 START 帧中，URL 不带参数。
 */
@Slf4j
public class BaiduSttService implements SttService {

    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String ASR_WS_URL = "wss://vop.baidu.com/realtime_asr";

    private final String apiKey;
    private final String secretKey;
    private final ObjectMapper objectMapper;

    private SttCallback callback;
    private String accessToken;
    private WebSocketClient wsClient;
    private final ByteArrayOutputStream preStartBuffer = new ByteArrayOutputStream();
    private volatile boolean started;

    public BaiduSttService(String apiKey, String secretKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start(SttCallback callback) {
        this.callback = callback;
        this.started = false;
        this.preStartBuffer.reset();

        try {
            this.accessToken = fetchAccessToken();
            log.info("[STT] 百度 access_token 获取成功 | len={}", accessToken.length());
        } catch (Exception e) {
            log.error("[STT] 获取百度 access_token 失败", e);
            callback.onError("百度认证失败: " + e.getMessage());
            return;
        }
        connectWebSocket();
    }

    @Override
    public void sendAudio(byte[] pcmData) {
        if (started && wsClient != null && wsClient.isOpen()) {
            // 已 START：直接发送
            wsClient.send(ByteBuffer.wrap(pcmData));
        } else {
            // 未 START：暂存
            try { preStartBuffer.write(pcmData); } catch (IOException ignored) {}
        }
    }

    @Override
    public void finish() {
        if (wsClient != null && wsClient.isOpen()) {
            try {
                wsClient.send("{\"type\":\"FINISH\"}");
                log.info("[STT] FINISH 帧已发送");
            } catch (Exception e) {
                log.error("[STT] 发送 FINISH 失败", e);
            }
        }
    }

    @Override
    public void close() {
        if (wsClient != null && wsClient.isOpen()) {
            wsClient.close();
        }
        preStartBuffer.reset();
    }

    // ==================== private ====================

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

    private void connectWebSocket() {
        try {
            log.info("[STT] 连接百度 ASR WebSocket: {}", ASR_WS_URL);
            wsClient = new WebSocketClient(URI.create(ASR_WS_URL)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    log.info("[STT] 百度 ASR WebSocket 已连接 | status={}", handshake.getHttpStatus());
                    // 发送 START 帧（token 放这里）
                    sendStartFrame();
                }

                @Override
                public void onMessage(String message) {
                    log.debug("[STT] 百度返回: {}", message.length() > 200 ? message.substring(0, 200) + "..." : message);
                    try {
                        JsonNode root = objectMapper.readTree(message);
                        String type = root.has("type") ? root.get("type").asText() : "";
                        String result = root.has("result") ? root.get("result").asText("") : "";

                        if ("MID_TEXT".equals(type) || "PARTIAL_RESULT".equals(type)) {
                            if (!result.isEmpty()) callback.onInterim(result);
                        } else if ("FIN_TEXT".equals(type)) {
                            if (!result.isEmpty()) {
                                log.info("[STT] 最终识别结果: {}", result);
                                callback.onFinal(result);
                            }
                        } else if ("ERROR".equals(type)) {
                            String msg = root.has("message") ? root.get("message").asText() : "未知错误";
                            log.error("[STT] 百度返回错误: {}", msg);
                            callback.onError(msg);
                        }
                    } catch (Exception e) {
                        log.warn("[STT] 解析百度响应失败: {}", message);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.info("[STT] 百度 WS 关闭 | code={} reason={} remote={}", code, reason, remote);
                }

                @Override
                public void onError(Exception ex) {
                    log.error("[STT] 百度 WS 错误", ex);
                    callback.onError("连接异常: " + ex.getMessage());
                }
            };
            wsClient.connectBlocking();

        } catch (Exception e) {
            log.error("[STT] 连接百度 ASR 失败", e);
            callback.onError("连接百度失败: " + e.getMessage());
        }
    }

    private void sendStartFrame() {
        try {
            java.util.Map<String, Object> startData = new java.util.LinkedHashMap<>();
            startData.put("format", "pcm");
            startData.put("rate", 16000);
            startData.put("channel", 1);
            startData.put("token", accessToken);
            startData.put("cuid", "aivca");

            java.util.Map<String, Object> frame = new java.util.LinkedHashMap<>();
            frame.put("type", "START");
            frame.put("data", startData);

            String json = objectMapper.writeValueAsString(frame);
            wsClient.send(json);
            log.info("[STT] START 帧已发送");
            started = true;

            // 发送累积的音频数据
            flushBuffer();
        } catch (Exception e) {
            log.error("[STT] 发送 START 帧失败", e);
            callback.onError("启动识别失败");
        }
    }

    /** 发送 start 前累积的音频数据 */
    private void flushBuffer() {
        byte[] data = preStartBuffer.toByteArray();
        preStartBuffer.reset();
        if (data.length > 0 && wsClient != null && wsClient.isOpen()) {
            wsClient.send(ByteBuffer.wrap(data));
            log.debug("[STT] 补发累积音频 | bytes={}", data.length);
        }
    }
}

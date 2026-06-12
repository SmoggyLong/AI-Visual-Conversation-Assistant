package com.aivca.service.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.*;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Base64;

/**
 * 百度实时流式语音识别服务（Jakarta WebSocket Client）。
 *
 * 使用 Jakarta WebSocket API 连接百度 wss://vop.baidu.com/realtime_asr，
 * 流式发送音频帧，实时接收 MID_TEXT（中间结果）和 FIN_TEXT（最终结果）。
 *
 * 替代了有 bug 的 JDK 24 java.net.http.WebSocket。
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
    private Session wsSession;
    private boolean started;

    public BaiduSttService(String apiKey, String secretKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start(SttCallback callback) {
        this.callback = callback;
        this.started = false;

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
        if (wsSession != null && wsSession.isOpen()) {
            try {
                wsSession.getBasicRemote().sendBinary(ByteBuffer.wrap(pcmData));
            } catch (IOException e) {
                log.warn("[STT] 发送音频帧失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public void finish() {
        if (wsSession != null && wsSession.isOpen()) {
            try {
                wsSession.getBasicRemote().sendText("{\"type\":\"FINISH\"}");
                log.info("[STT] FINISH 帧已发送");
            } catch (IOException e) {
                log.error("[STT] 发送 FINISH 失败", e);
            }
        }
    }

    @Override
    public void close() {
        if (wsSession != null && wsSession.isOpen()) {
            try { wsSession.close(); } catch (IOException ignored) {}
        }
    }

    // ==================== private ====================

    private String fetchAccessToken() throws IOException, InterruptedException {
        String url = TOKEN_URL
                + "?grant_type=client_credentials"
                + "&client_id=" + apiKey
                + "&client_secret=" + secretKey;

        log.debug("[STT] 请求百度 access_token...");
        HttpClient http = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.of(
                        new java.net.InetSocketAddress("127.0.0.1", 7897)))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        log.debug("[STT] access_token 响应: HTTP {}", resp.statusCode());

        JsonNode root = objectMapper.readTree(resp.body());
        if (root.has("error")) {
            String err = root.get("error").asText() + " - " + root.get("error_description").asText("");
            throw new IOException(err);
        }
        return root.get("access_token").asText();
    }

    private void connectWebSocket() {
        try {
            String url = ASR_WS_URL + "?access_token=" + accessToken;
            log.info("[STT] 连接百度 ASR WebSocket: {}", ASR_WS_URL);

            // 配置代理 — 国内环境需要通过 127.0.0.1:7897 访问百度 WSS
            System.setProperty("https.proxyHost", "127.0.0.1");
            System.setProperty("https.proxyPort", "7897");

            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            wsSession = container.connectToServer(new BaiduAsrEndpoint(), URI.create(url));

        } catch (Exception e) {
            log.error("[STT] 连接百度 ASR 失败", e);
            callback.onError("连接百度失败: " + e.getMessage());
        }
    }

    /**
     * Jakarta WebSocket 客户端端点 —— 处理百度 ASR 消息。
     */
    @ClientEndpoint
    public class BaiduAsrEndpoint {

        @OnOpen
        public void onOpen(Session session) {
            log.info("[STT] 百度 ASR WebSocket 已连接");
            try {
                String startJson = objectMapper.writeValueAsString(java.util.Map.of(
                        "type", "START",
                        "data", java.util.Map.of(
                                "format", "pcm",
                                "rate", 16000,
                                "channels", 1
                        )
                ));
                session.getBasicRemote().sendText(startJson);
                log.info("[STT] START 帧已发送");
                started = true;
            } catch (Exception e) {
                log.error("[STT] 发送 START 帧失败", e);
                callback.onError("启动识别失败");
            }
        }

        @OnMessage
        public void onMessage(String message) {
            log.debug("[STT] 百度返回: {}", message.length() > 200 ? message.substring(0, 200) + "..." : message);
            try {
                JsonNode root = objectMapper.readTree(message);
                String type = root.has("type") ? root.get("type").asText() : "";
                String result = root.has("result") ? root.get("result").asText("") : "";

                if ("MID_TEXT".equals(type) || "PARTIAL_RESULT".equals(type)) {
                    if (!result.isEmpty()) callback.onInterim(result);
                } else if ("FIN_TEXT".equals(type)) {
                    if (!result.isEmpty()) callback.onFinal(result);
                } else if ("ERROR".equals(type)) {
                    String msg = root.has("message") ? root.get("message").asText() : "未知错误";
                    log.error("[STT] 百度返回错误: {}", msg);
                    callback.onError(msg);
                }
            } catch (Exception e) {
                log.warn("[STT] 解析百度响应失败: {}", message);
            }
        }

        @OnClose
        public void onClose(Session session, CloseReason reason) {
            log.info("[STT] 百度 WS 关闭 | code={} reason={}", reason.getCloseCode(), reason.getReasonPhrase());
        }

        @OnError
        public void onError(Session session, Throwable error) {
            log.error("[STT] 百度 WS 错误", error);
            callback.onError("识别连接异常: " + error.getMessage());
        }
    }
}

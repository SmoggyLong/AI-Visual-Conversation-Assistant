package com.aivca.service.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 百度实时语音识别服务。
 *
 * 通过 WebSocket 连接百度 ASR 端点，流式发送 PCM 音频帧，
 * 实时接收中间结果 (MID_TEXT) 和最终结果 (FIN_TEXT)。
 *
 * 使用方式：
 * <pre>{@code
 *   BaiduSttService stt = new BaiduSttService(apiKey, secretKey, objectMapper);
 *   stt.start(new SttCallback() {
 *       void onInterim(String t) { ... }
 *       void onFinal(String t)   { ... }
 *       void onError(String e)   { ... }
 *   });
 *   stt.sendAudio(pcmBytes);
 *   stt.finish();
 * }</pre>
 */
@Slf4j
public class BaiduSttService implements SttService {

    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String ASR_WS_URL = "wss://vop.baidu.com/realtime_asr";

    private final String apiKey;
    private final String secretKey;
    private final ObjectMapper objectMapper;

    private WebSocket ws;
    private SttCallback callback;
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private String accessToken;

    public BaiduSttService(String apiKey, String secretKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start(SttCallback callback) {
        this.callback = callback;
        this.finished.set(false);

        try {
            this.accessToken = fetchAccessToken();
            log.debug("[STT] 百度 access_token 获取成功");
        } catch (Exception e) {
            log.error("[STT] 获取百度 access_token 失败", e);
            callback.onError("百度认证失败: " + e.getMessage());
            return;
        }

        connectWebSocket();
    }

    @Override
    public void sendAudio(byte[] pcmData) {
        if (ws != null && !ws.isOutputClosed() && !finished.get()) {
            ws.sendBinary(ByteBuffer.wrap(pcmData), true);
        }
    }

    @Override
    public void finish() {
        if (finished.compareAndSet(false, true) && ws != null && !ws.isOutputClosed()) {
            try {
                String json = objectMapper.writeValueAsString(
                        java.util.Map.of("type", "FINISH"));
                ws.sendText(json, true);
            } catch (Exception e) {
                log.error("[STT] 发送 FINISH 失败", e);
            }
        }
    }

    @Override
    public void close() {
        if (ws != null && !ws.isOutputClosed()) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            } catch (Exception ignored) {}
        }
    }

    // ==================== private ====================

    private String fetchAccessToken() throws IOException, InterruptedException {
        String url = TOKEN_URL
                + "?grant_type=client_credentials"
                + "&client_id=" + apiKey
                + "&client_secret=" + secretKey;

        log.debug("[STT] 请求百度 access_token...");
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
            String err = root.get("error").asText() + " - " + root.get("error_description").asText("");
            log.error("[STT] 获取 access_token 失败: {}", err);
            throw new IOException(err);
        }

        String token = root.get("access_token").asText();
        log.info("[STT] 百度 access_token 获取成功 | len={}", token.length());
        return token;
    }

    private void connectWebSocket() {
        try {
            String url = ASR_WS_URL + "?access_token=" + accessToken;

            HttpClient http = createInsecureHttpClient();
            WebSocket.Builder builder = http.newWebSocketBuilder();

            CompletableFuture<WebSocket> future = builder
                    .buildAsync(URI.create(url), new WebSocket.Listener() {
                        final StringBuilder jsonBuilder = new StringBuilder();

                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                            // 发送 START 帧
                            try {
                                String startJson = objectMapper.writeValueAsString(java.util.Map.of(
                                        "type", "START",
                                        "data", java.util.Map.of(
                                                "format", "pcm",
                                                "rate", 16000,
                                                "channels", 1
                                        )
                                ));
                                webSocket.sendText(startJson, true);
                                log.info("[STT] 百度 ASR WebSocket 已连接，START 帧已发送");
                            } catch (Exception e) {
                                log.error("[STT] 发送 START 帧失败", e);
                                callback.onError("启动识别失败");
                            }
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            jsonBuilder.append(data);
                            if (last) {
                                String full = jsonBuilder.toString();
                                jsonBuilder.setLength(0);
                                try {
                                    JsonNode root = objectMapper.readTree(full);
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
                                    log.warn("[STT] 解析百度响应失败: {}", full);
                                }
                            }
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            log.info("[STT] 百度 WS 关闭 | code={} reason={}", statusCode, reason);
                            return null;
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            log.error("[STT] 百度 WS 错误", error);
                            callback.onError("识别连接异常: " + error.getMessage());
                        }
                    });

            ws = future.join();
            log.info("[STT] 百度 ASR WebSocket 连接完成");
        } catch (Exception e) {
            log.error("[STT] 连接百度 ASR 失败", e);
            callback.onError("连接百度失败: " + e.getMessage());
        }
    }

    /** 创建忽略 SSL 证书验证的 HttpClient（用于代理环境） */
    private static HttpClient createInsecureHttpClient() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAll, new java.security.SecureRandom());
        return HttpClient.newBuilder().sslContext(sslContext).build();
    }
}

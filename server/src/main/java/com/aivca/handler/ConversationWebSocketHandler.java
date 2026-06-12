package com.aivca.handler;

import com.aivca.model.enums.MessageType;
import com.aivca.model.message.*;
import com.aivca.model.session.ConversationSession;
import com.aivca.service.SessionManager;
import com.aivca.service.stt.SttService;
import com.aivca.service.stt.BaiduSttService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ConversationWebSocketHandler extends TextWebSocketHandler {

    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private final String baiduApiKey;
    private final String baiduSecretKey;

    /** 当前活跃的 WebSocket 连接，以 Spring WebSocket sessionId 为键 */
    private final Map<String, WebSocketSession> activeConnections = new ConcurrentHashMap<>();

    /** 每个 wsId 对应的 STT 服务实例 */
    private final Map<String, SttService> sttServices = new ConcurrentHashMap<>();

    public ConversationWebSocketHandler(SessionManager sessionManager, ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;

        // 直接从 server/.env 读取百度密钥，不走 Spring 配置
        Map<String, String> env = loadDotenv();
        this.baiduApiKey = env.getOrDefault("BAIDU_ASR_API_KEY", "");
        this.baiduSecretKey = env.getOrDefault("BAIDU_ASR_SECRET_KEY", "");
        log.info("[CONFIG] 百度 ASR 配置 | apiKey={}... | secretKey={}...",
                baiduApiKey.isEmpty() ? "(未设置)" : baiduApiKey.substring(0, Math.min(6, baiduApiKey.length())),
                baiduSecretKey.isEmpty() ? "(未设置)" : "****");
    }

    /** 读取 .env 文件为 Map */
    private static Map<String, String> loadDotenv() {
        Map<String, String> map = new HashMap<>();
        Path path = Paths.get(".env");
        if (!Files.exists(path)) {
            System.err.println("[DOTENV] .env 文件不存在: " + path.toAbsolutePath());
            return map;
        }
        try {
            for (String line : Files.readAllLines(path)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    map.put(parts[0].trim(), parts[1].trim());
                }
            }
            System.out.println("[DOTENV] 已加载 " + map.size() + " 个变量");
        } catch (IOException e) {
            System.err.println("[DOTENV] 读取失败: " + e.getMessage());
        }
        return map;
    }

    /**
     * WebSocket 连接建立回调。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) {
        String wsId = wsSession.getId();
        activeConnections.put(wsId, wsSession);
        log.info("[CONNECT] WebSocket 连接建立 | wsId={} | 当前连接数={}", wsId, activeConnections.size());
    }

    /**
     * 处理客户端发来的文本消息，根据 type 字段路由。
     */
    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage textMessage) {
        String wsId = wsSession.getId();
        String payload = textMessage.getPayload();

        try {
            JsonNode root = objectMapper.readTree(payload);
            MessageType type = MessageType.valueOf(root.get("type").asText());

            switch (type) {
                case CONNECTION_INIT -> handleConnectionInit(wsSession, root);
                case CAMERA_CONTROL -> handleDeviceControl(wsSession, root, true);
                case MICROPHONE_CONTROL -> handleDeviceControl(wsSession, root, false);
                case FRAME_DATA -> handleFrameData(wsSession, root);
                case AUDIO_DATA -> handleAudioData(wsSession, root);
                case SPEECH_START -> handleSpeechEvent(wsSession, root, true);
                case SPEECH_END -> handleSpeechEvent(wsSession, root, false);
                case PING -> handlePing(wsSession);
                default -> sendError(wsSession, ErrorPayload.ErrorCode.INVALID_MESSAGE,
                        "不支持的消息类型: " + type);
            }
        } catch (IllegalArgumentException e) {
            log.warn("[CONNECT] 未知消息类型 | wsId={} | payload={}", wsId,
                    payload.length() > 200 ? payload.substring(0, 200) + "..." : payload);
            sendError(wsSession, ErrorPayload.ErrorCode.INVALID_MESSAGE, "未知消息类型");
        } catch (Exception e) {
            log.error("[ERROR] 消息处理异常 | wsId={}", wsId, e);
            sendError(wsSession, ErrorPayload.ErrorCode.PROCESSING_ERROR, "处理异常: " + e.getMessage());
        }
    }

    /**
     * WebSocket 连接关闭回调 —— 清理连接与会话。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        String wsId = wsSession.getId();
        closeSttSession(wsId);
        activeConnections.remove(wsId);
        sessionManager.remove(wsId);
        log.info("[CONNECT] WebSocket 连接关闭 | wsId={} | 状态={} | 剩余连接={}",
                wsId, status, activeConnections.size());
    }

    /**
     * WebSocket 传输异常回调。
     */
    @Override
    public void handleTransportError(WebSocketSession wsSession, Throwable exception) {
        log.error("[ERROR] WebSocket 传输异常 | wsId={}", wsSession.getId(), exception);
    }

    // ==================== 消息处理方法 ====================

    /**
     * 处理 CONNECTION_INIT 消息 —— 创建业务会话并返回 ACK。
     */
    private void handleConnectionInit(WebSocketSession wsSession, JsonNode root) {
        ConnectionInitPayload init = objectMapper.convertValue(
                root.get("payload"), ConnectionInitPayload.class);

        ConversationSession session = sessionManager.create();
        if (init != null) {
            session.setDeviceInfo(init.getDeviceInfo());
        }
        sessionManager.bindWsSession(wsSession.getId(), session.getSessionId());

        sendMessage(wsSession, MessageType.CONNECTION_ACK,
                ConnectionAckPayload.builder()
                        .sessionId(session.getSessionId())
                        .serverTime(System.currentTimeMillis())
                        .build());

        sendStatus(wsSession, StatusUpdatePayload.State.idle, "已就绪");

        String ua = init != null && init.getDeviceInfo() != null
                ? init.getDeviceInfo().getUserAgent() : "unknown";
        log.info("[SESSION] 会话初始化完成 | sessionId={} | UA={}", session.getSessionId(), ua);
    }

    /**
     * 处理设备控制消息（摄像头/麦克风开关）。
     *
     * @param isCamera true=摄像头, false=麦克风
     */
    private void handleDeviceControl(WebSocketSession wsSession, JsonNode root, boolean isCamera) {
        ConversationSession session = sessionManager.getByWsId(wsSession.getId());
        if (session == null) return;

        DeviceControlPayload ctrl = objectMapper.convertValue(
                root.get("payload"), DeviceControlPayload.class);
        if (ctrl == null) return;

        String deviceLabel = isCamera ? "摄像头" : "麦克风";
        String action = ctrl.isEnabled() ? "开启" : "关闭";

        if (isCamera) {
            session.setCameraEnabled(ctrl.isEnabled());
            session.setCameraDeviceId(ctrl.getDeviceId());
        } else {
            session.setMicrophoneEnabled(ctrl.isEnabled());
            session.setMicrophoneDeviceId(ctrl.getDeviceId());
        }

        log.info("[DEVICE] {}已{} | sessionId={} | deviceId={}",
                deviceLabel, action, session.getSessionId(), ctrl.getDeviceId());
        sendStatus(wsSession, session.getCurrentState(), deviceLabel + "已" + action);
    }

    /**
     * 处理 FRAME_DATA 消息 —— 接收视频帧，检查帧差后缓存。
     * 后续接入 Vision API 进行画面分析。
     */
    private void handleFrameData(WebSocketSession wsSession, JsonNode root) {
        ConversationSession session = sessionManager.getByWsId(wsSession.getId());
        if (session == null) return;

        FrameDataPayload frame = objectMapper.convertValue(
                root.get("payload"), FrameDataPayload.class);
        if (frame == null) return;

        if (frame.isChanged()) {
            session.setCurrentState(StatusUpdatePayload.State.watching);
            sendStatus(wsSession, StatusUpdatePayload.State.watching, "正在分析画面...");

            if (frame.getImageChecksum() != null
                    && !frame.getImageChecksum().equals(session.getCachedImageChecksum())) {
                session.setCachedImageChecksum(frame.getImageChecksum());
                log.debug("[FRAME] 收到新视频帧 | sessionId={} | size={}x{} | checksum={}",
                        session.getSessionId(), frame.getWidth(), frame.getHeight(),
                        frame.getImageChecksum().substring(0, Math.min(8, frame.getImageChecksum().length())));
                // TODO: 后续接入 Vision API
            } else {
                log.debug("[FRAME] 帧差无变化，跳过分析 | sessionId={}", session.getSessionId());
            }

            session.setCurrentState(StatusUpdatePayload.State.idle);
        }
    }

    /**
     * 处理 AUDIO_DATA 消息 —— 流式转发音频到百度 STT。
     */
    private void handleAudioData(WebSocketSession wsSession, JsonNode root) {
        ConversationSession session = sessionManager.getByWsId(wsSession.getId());
        if (session == null) {
            log.warn("[STT] 会话不存在，忽略音频 | wsId={}", wsSession.getId());
            return;
        }

        AudioDataPayload audio = objectMapper.convertValue(
                root.get("payload"), AudioDataPayload.class);
        if (audio == null || audio.getData() == null) {
            log.warn("[STT] 音频数据为空 | wsId={}", wsSession.getId());
            return;
        }

        String wsId = wsSession.getId();

        try {
            byte[] pcmData = Base64.getDecoder().decode(audio.getData());

            // 首帧音频 → 创建 STT 会话
            SttService stt = sttServices.get(wsId);
            if (stt == null) {
                log.info("[STT] 创建百度 STT 会话 | wsId={} | sessionId={}", wsId, session.getSessionId());
                stt = new BaiduSttService(baiduApiKey, baiduSecretKey, objectMapper);
                sttServices.put(wsId, stt);
                session.setCurrentState(StatusUpdatePayload.State.listening);
                sendStatus(wsSession, StatusUpdatePayload.State.listening, "正在识别语音...");

                stt.start(new SttService.SttCallback() {
                    @Override
                    public void onInterim(String text) {
                        log.debug("[STT] 中间结果 | wsId={} | text={}", wsId, text);
                        sendMessage(wsSession, MessageType.RESPONSE_TEXT,
                                ResponseTextPayload.builder()
                                        .messageId("stt_interim")
                                        .role("assistant")
                                        .content("[INTERIM]" + text)
                                        .conversationRound(session.getConversationRound())
                                        .build());
                    }

                    @Override
                    public void onFinal(String text) {
                        log.info("[STT] 最终识别结果 | wsId={} | text={}", wsId, text);
                        session.addTurn(text, "");
                        sendMessage(wsSession, MessageType.RESPONSE_TEXT,
                                ResponseTextPayload.builder()
                                        .messageId("stt_" + System.currentTimeMillis())
                                        .role("assistant")
                                        .content(text)
                                        .conversationRound(session.getConversationRound())
                                        .build());
                        sendStatus(wsSession, StatusUpdatePayload.State.idle, "识别完成");
                    }

                    @Override
                    public void onError(String message) {
                        log.warn("[STT] 识别错误 | wsId={} | msg={}", wsId, message);
                        sendStatus(wsSession, StatusUpdatePayload.State.error, "识别失败: " + message);
                    }
                });
            }

            log.debug("[STT] 转发音频块 | wsId={} | bytes={}", wsId, pcmData.length);
            stt.sendAudio(pcmData);

        } catch (Exception e) {
            log.error("[STT] 音频处理异常 | wsId={}", wsId, e);
            closeSttSession(wsId);
        }
    }

    /**
     * 处理 VAD 语音活动事件。
     */
    private void handleSpeechEvent(WebSocketSession wsSession, JsonNode root, boolean isStart) {
        ConversationSession session = sessionManager.getByWsId(wsSession.getId());
        if (session == null) return;

        log.debug("[AUDIO] 用户{} | sessionId={}",
                isStart ? "开始说话" : "停止说话", session.getSessionId());
    }

    /**
     * 处理 PING 心跳 —— 回复 PONG。
     */
    private void handlePing(WebSocketSession wsSession) {
        sendMessage(wsSession, MessageType.PONG, null);
    }

    // ==================== 发送工具方法 ====================

    /**
     * 向指定 WebSocket 连接发送消息。
     *
     * @param wsSession WebSocket 会话
     * @param type      消息类型
     * @param payload   消息载荷（可为 null）
     */
    private void sendMessage(WebSocketSession wsSession, MessageType type, Object payload) {
        try {
            ConversationSession session = sessionManager.getByWsId(wsSession.getId());
            Message<Object> msg = Message.<Object>builder()
                    .type(type)
                    .timestamp(System.currentTimeMillis())
                    .sessionId(session != null ? session.getSessionId() : "")
                    .payload(payload)
                    .build();
            wsSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
        } catch (IOException e) {
            log.error("[ERROR] 发送消息失败 | wsId={}", wsSession.getId(), e);
        }
    }

    /**
     * 推送状态更新消息。
     */
    private void sendStatus(WebSocketSession wsSession, StatusUpdatePayload.State state, String detail) {
        sendMessage(wsSession, MessageType.STATUS_UPDATE,
                StatusUpdatePayload.builder().state(state).detail(detail).build());
    }

    /**
     * 推送错误消息。
     */
    private void sendError(WebSocketSession wsSession, ErrorPayload.ErrorCode code, String message) {
        sendMessage(wsSession, MessageType.ERROR,
                ErrorPayload.builder().code(code).message(message).build());
    }

    /** 关闭并清理 STT 会话 */
    private void closeSttSession(String wsId) {
        SttService stt = sttServices.remove(wsId);
        if (stt != null) {
            try { stt.close(); } catch (Exception ignored) {}
        }
    }
}

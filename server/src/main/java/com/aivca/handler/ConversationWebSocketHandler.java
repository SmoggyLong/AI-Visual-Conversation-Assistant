package com.aivca.handler;

import com.aivca.model.enums.MessageType;
import com.aivca.model.message.*;
import com.aivca.model.session.ConversationSession;
import com.aivca.service.SessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 消息处理器 —— 接收客户端消息并路由到对应处理方法。
 *
 * 负责：
 * - WebSocket 连接生命周期管理（建立/关闭/异常）
 * - JSON 消息反序列化与类型路由
 * - 会话创建与 WebSocket 绑定
 * - 设备状态同步（摄像头/麦克风 开关通知）
 * - 心跳维护（PING/PONG）
 * - 状态推送与错误响应
 *
 * 不负责：
 * - 实际的 AI 推理（由后续 Orchestrator 处理）
 * - 帧的视觉分析（后续接入 Vision API）
 * - 音频的语音识别（后续接入 STT API）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationWebSocketHandler extends TextWebSocketHandler {

    /** 会话管理器 —— 负责业务会话的创建、查找和销毁 */
    private final SessionManager sessionManager;

    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;

    /** 当前活跃的 WebSocket 连接，以 Spring WebSocket sessionId 为键 */
    private final Map<String, WebSocketSession> activeConnections = new ConcurrentHashMap<>();

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
     * 处理 AUDIO_DATA 消息 —— 接收音频数据块。
     * 后续接入 Whisper STT 进行语音识别。
     */
    private void handleAudioData(WebSocketSession wsSession, JsonNode root) {
        ConversationSession session = sessionManager.getByWsId(wsSession.getId());
        if (session == null) return;

        AudioDataPayload audio = objectMapper.convertValue(
                root.get("payload"), AudioDataPayload.class);
        if (audio == null) return;

        session.setCurrentState(StatusUpdatePayload.State.listening);
        sendStatus(wsSession, StatusUpdatePayload.State.listening, "正在识别语音...");

        log.debug("[AUDIO] 收到音频数据 | sessionId={} | duration={}ms | sampleRate={}Hz | channels={}",
                session.getSessionId(), (int) (audio.getDuration() * 1000),
                audio.getSampleRate(), audio.getChannels());
        // TODO: 后续接入 STT API

        session.setCurrentState(StatusUpdatePayload.State.idle);
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
}

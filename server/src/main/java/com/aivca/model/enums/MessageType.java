package com.aivca.model.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * WebSocket 消息类型枚举。
 *
 * 分为两组：
 * - C2S（客户端→服务端）：CONNECTION_INIT 到 PING
 * - S2C（服务端→客户端）：CONNECTION_ACK 到 PONG
 */
public enum MessageType {

    // ===== 客户端 → 服务端 =====
    /** 初始化连接，携带设备信息 */
    CONNECTION_INIT,
    /** 摄像头开关控制 */
    CAMERA_CONTROL,
    /** 麦克风开关控制 */
    MICROPHONE_CONTROL,
    /** 视频帧数据（Base64 JPEG） */
    FRAME_DATA,
    /** 音频数据块（Base64） */
    AUDIO_DATA,
    /** 语音活动开始（VAD 检测） */
    SPEECH_START,
    /** 语音活动结束（VAD 检测） */
    SPEECH_END,
    /** 心跳保活 */
    PING,

    // ===== 服务端 → 客户端 =====
    /** 连接确认，返回 sessionId */
    CONNECTION_ACK,
    /** AI 文字回复 */
    RESPONSE_TEXT,
    /** AI 语音回复（TTS 音频） */
    RESPONSE_AUDIO,
    /** 视觉识别结果 */
    VISION_RESULT,
    /** 服务端处理状态更新 */
    STATUS_UPDATE,
    /** 错误信息 */
    ERROR,
    /** 心跳响应 */
    PONG;

    @JsonValue
    public String toValue() {
        return name();
    }
}

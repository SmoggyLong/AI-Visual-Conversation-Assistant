package com.aivca.model.message;

import com.aivca.model.enums.MessageType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket 消息信封 —— 所有客户端-服务端通信均以此格式打包。
 *
 * @param <T> payload 的具体类型，如 FrameDataPayload、AudioDataPayload 等
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message<T> {

    /** 消息类型，决定 payload 的结构 */
    @JsonProperty(required = true)
    private MessageType type;

    /** Unix 毫秒时间戳 */
    @JsonProperty(required = true)
    private long timestamp;

    /** 会话 ID —— CONNECTION_INIT 时为空，CONNECTION_ACK 后由服务端分配 */
    private String sessionId;

    /** 消息载荷，具体类型由 type 决定 */
    private T payload;
}

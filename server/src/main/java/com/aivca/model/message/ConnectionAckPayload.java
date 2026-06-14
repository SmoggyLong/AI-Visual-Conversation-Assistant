package com.aivca.model.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CONNECTION_ACK 消息的载荷 —— 服务端确认连接，返回会话标识。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConnectionAckPayload {

    /** 服务端生成的唯一会话 ID */
    private String sessionId;

    /** 服务端当前时间（毫秒），客户端可据此校准本地时钟 */
    private long serverTime;
}

package com.aivca.model.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ERROR 消息的载荷 —— 服务端错误信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorPayload {

    /** 错误码 */
    private ErrorCode code;

    /** 人类可读的错误描述 */
    private String message;

    /**
     * 错误码枚举。
     */
    public enum ErrorCode {
        /** API 调用频率超限 */
        RATE_LIMIT,
        /** 认证失败 */
        AUTH_ERROR,
        /** 服务端处理异常 */
        PROCESSING_ERROR,
        /** 消息格式不合法 */
        INVALID_MESSAGE,
        /** 会话已过期 */
        SESSION_EXPIRED
    }
}

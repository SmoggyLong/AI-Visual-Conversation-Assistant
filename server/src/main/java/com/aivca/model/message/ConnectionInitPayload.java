package com.aivca.model.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CONNECTION_INIT 消息的载荷 —— 客户端首次连接时上报设备信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConnectionInitPayload {

    /** 客户端设备信息 */
    private DeviceInfo deviceInfo;

    /**
     * 客户端设备元数据。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceInfo {
        /** 浏览器 User-Agent 字符串 */
        private String userAgent;
        /** 操作系统平台 */
        private String platform;
        /** 屏幕宽度（像素） */
        private int screenWidth;
        /** 屏幕高度（像素） */
        private int screenHeight;
    }
}

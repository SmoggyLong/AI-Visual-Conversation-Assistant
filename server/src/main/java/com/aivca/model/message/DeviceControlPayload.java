package com.aivca.model.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CAMERA_CONTROL / MICROPHONE_CONTROL 消息的载荷 —— 客户端通知服务端设备开关状态变化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceControlPayload {

    /** 设备是否启用 */
    private boolean enabled;

    /** 设备 ID（可选），多设备时指定具体的摄像头/麦克风 */
    private String deviceId;
}

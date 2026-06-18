package com.aivca.model.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SPEECH_START / SPEECH_END 消息的载荷 —— 客户端 VAD 语音活动检测结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SpeechEventPayload {

    /** 事件发生的客户端时间戳（毫秒） */
    private long timestamp;
}

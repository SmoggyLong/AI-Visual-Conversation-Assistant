package com.aivca.model.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RESPONSE_AUDIO 消息的载荷 —— TTS 生成的 AI 语音回复。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseAudioPayload {

    /** 消息唯一标识，与 RESPONSE_TEXT 的 messageId 关联 */
    private String messageId;

    /** 对应的文字内容（用于客户端显示） */
    private String text;

    /** 音频编码格式："mp3" / "wav" */
    private String format;

    /** Base64 编码的音频数据 */
    private String data;

    /** 音频时长（秒） */
    private double duration;
}

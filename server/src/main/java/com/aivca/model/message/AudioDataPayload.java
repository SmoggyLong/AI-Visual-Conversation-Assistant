package com.aivca.model.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AUDIO_DATA 消息的载荷 —— 客户端采集的音频数据块。
 *
 * 由端侧 VAD 检测到语音活动后分段发送，服务端累积后送 Whisper STT。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AudioDataPayload {

    /** 音频编码格式："opus" / "pcm" */
    private String format;

    /** 采样率（Hz），如 16000 */
    private int sampleRate;

    /** 声道数：1=单声道 */
    private int channels;

    /** Base64 编码的音频数据 */
    private String data;

    /** 该音频块的时长（秒） */
    private double duration;
}

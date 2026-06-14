package com.aivca.api.tts;

/**
 * TTS 服务接口。
 */
public interface TtsService {

    /**
     * 文本转语音，返回 base64 编码的 MP3 音频。
     *
     * @param text 文本内容
     * @return base64 MP3 音频数据；失败时返回 null
     */
    String synthesize(String text);
}

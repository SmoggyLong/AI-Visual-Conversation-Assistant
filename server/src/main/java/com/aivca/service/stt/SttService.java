package com.aivca.service.stt;

import java.io.Closeable;

/**
 * 语音识别服务接口。
 *
 * 支持流式识别：实时接收 PCM 音频帧，推送中间/最终结果。
 */
public interface SttService extends Closeable {

    /**
     * 启动识别会话。
     *
     * @param callback 识别结果回调
     */
    void start(SttCallback callback);

    /**
     * 发送音频数据。
     *
     * @param pcmData Int16 PCM 原始字节数组（16kHz, mono）
     */
    void sendAudio(byte[] pcmData);

    /**
     * 结束音频发送，等待最终结果。
     */
    void finish();

    @Override
    void close();

    /**
     * 识别结果回调。
     */
    interface SttCallback {
        /** 中间结果（边说边出） */
        void onInterim(String text);
        /** 最终结果（一句说完） */
        void onFinal(String text);
        /** 识别出错 */
        void onError(String message);
    }
}

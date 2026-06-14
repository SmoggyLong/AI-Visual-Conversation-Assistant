package com.aivca.api.stt;

import java.io.Closeable;

/**
 * 语音识别服务接口。
 *
 * 定义 start → sendAudio → finish 的流式风格 API。
 * 各厂商（百度/腾讯/讯飞）通过实现本接口接入。
 */
public interface SttService extends Closeable {

    void start(SttCallback callback);

    void sendAudio(byte[] pcmData);

    void finish();

    @Override
    void close();

    /** 识别结果回调 */
    interface SttCallback {
        /** 中间结果（流式识别进行中） */
        void onInterim(String text);
        /** 最终结果（识别完成） */
        void onFinal(String text);
        /** 错误 */
        void onError(String message);
    }
}

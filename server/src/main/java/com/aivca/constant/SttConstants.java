package com.aivca.constant;

/**
 * 语音识别相关常量。
 */
public final class SttConstants {

    private SttConstants() {}

    /** 百度 OAuth token 端点 */
    public static final String BAIDU_TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";

    /** 百度短语音识别 REST 端点 */
    public static final String BAIDU_ASR_URL = "https://vop.baidu.com/server_api";

    /** 百度实时流式 ASR WebSocket 端点（国内不可用） */
    public static final String BAIDU_ASR_WS_URL = "wss://vop.baidu.com/realtime_asr";

    /** 音频格式 */
    public static final String AUDIO_FORMAT = "pcm";
    /** 采样率 Hz */
    public static final int AUDIO_RATE = 16000;
    /** 声道数 */
    public static final int AUDIO_CHANNELS = 1;
    /** 百度中文普通话模型 */
    public static final int BAIDU_DEV_PID = 1537;
    /** 百度客户端唯一标识 */
    public static final String BAIDU_CUID = "aivca";
    /** 逐字流式间隔 ms */
    public static final int STREAM_DELAY_MS = 50;
}

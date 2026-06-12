package com.aivca.service.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 百度短语音识别服务（REST API 方式）。
 *
 * 不走 WebSocket，改用 POST https://vop.baidu.com/server_api。
 * 一句话说完后发送完整 WAV 音频，阻塞等待返回结果。
 *
 * 接口定义保持流式风格（start → sendAudio 多次 → finish），
 * 方便以后切回真正的 WebSocket。
 */
public interface SttService extends Closeable {
    void start(SttCallback callback);
    void sendAudio(byte[] pcmData);
    void finish();
    void close();

    interface SttCallback {
        void onInterim(String text);
        void onFinal(String text);
        void onError(String message);
    }
}

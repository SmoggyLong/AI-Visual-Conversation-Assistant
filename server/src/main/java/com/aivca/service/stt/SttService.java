package com.aivca.service.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

/**
 * 百度语音识别服务接口（流式接口）。
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

package com.aivca.model.session;

import java.util.List;

/**
 * 触发事件 —— 放入 EpisodeConsumer 队列的事件载体。
 */
public class TriggerEvent {

    private final Type type;
    private final long timestamp;

    // VISION 类型字段
    private boolean isSpeaking;
    private List<String> frames;

    // SPEECH 类型字段
    private String speech;

    public TriggerEvent(Type type) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    public enum Type {
        VISION,
        SPEECH,
        SPEECH_BATCH    // 语音+累积帧 一体化事件
    }

    public TriggerEvent withFrames(boolean speaking, List<String> frameData) {
        this.isSpeaking = speaking;
        this.frames = frameData;
        return this;
    }

    public TriggerEvent withSpeech(String text) {
        this.speech = text;
        return this;
    }

    /** SPEECH_BATCH: 语音+累积帧一起打包 */
    public TriggerEvent withSpeechAndFrames(String text, List<String> frameData) {
        this.speech = text;
        this.frames = frameData;
        return this;
    }

    public Type getType() { return type; }
    public long getTimestamp() { return timestamp; }
    public boolean isSpeaking() { return isSpeaking; }
    public List<String> getFrames() { return frames; }
    public String getSpeech() { return speech; }
}

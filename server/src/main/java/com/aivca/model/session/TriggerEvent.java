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
        SPEECH
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

    public Type getType() { return type; }
    public long getTimestamp() { return timestamp; }
    public boolean isSpeaking() { return isSpeaking; }
    public List<String> getFrames() { return frames; }
    public String getSpeech() { return speech; }
}

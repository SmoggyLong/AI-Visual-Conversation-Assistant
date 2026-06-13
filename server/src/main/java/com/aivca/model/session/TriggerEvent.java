package com.aivca.model.session;

/**
 * 触发事件 —— 放入 EpisodeConsumer 队列的事件载体。
 */
public class TriggerEvent {

    /** 事件类型 */
    private final Type type;

    /** 事件时间戳 */
    private final long timestamp;

    // VISION 类型字段
    private String visionDesc;
    private String action;

    // SPEECH 类型字段
    private String speech;

    public TriggerEvent(Type type) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    public enum Type {
        VISION,        // 画面分析完成
        SPEECH          // 语音识别完成
    }

    // fluent setters
    public TriggerEvent withVision(String desc, String act) {
        this.visionDesc = desc;
        this.action = act;
        return this;
    }

    public TriggerEvent withSpeech(String text) {
        this.speech = text;
        return this;
    }

    // getters
    public Type getType() { return type; }
    public long getTimestamp() { return timestamp; }
    public String getVisionDesc() { return visionDesc; }
    public String getAction() { return action; }
    public String getSpeech() { return speech; }
}

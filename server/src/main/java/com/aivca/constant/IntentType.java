package com.aivca.constant;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 用户意图枚举（4 类）。
 */
public enum IntentType {

    VISION("vision",              "询问或展示画面内容"),
    KNOWLEDGE("knowledge",        "知识、技术、编程问答"),
    CONVERSATION("conversation",  "日常闲聊、问候、告别"),
    GAME("game",                  "玩游戏（成语接龙、猜谜等）");

    private final String value;
    private final String label;

    IntentType(String value, String label) {
        this.value = value;
        this.label = label;
    }

    @JsonValue
    public String toValue() { return value; }
    public String getLabel() { return label; }

    /** 容错解析，兼容旧意图名称 */
    public static IntentType fromString(String s) {
        for (IntentType t : values()) {
            if (t.value.equalsIgnoreCase(s)) return t;
        }
        // 向后兼容: 旧枚举值映射
        return switch (s.toLowerCase()) {
            case "greeting", "general" -> CONVERSATION;
            case "technical"          -> KNOWLEDGE;
            case "emergency"          -> CONVERSATION;
            default                   -> CONVERSATION;
        };
    }

    public static String promptOptions() {
        return Arrays.stream(values())
                .map(IntentType::toValue)
                .collect(Collectors.joining("|"));
    }

    public static String promptWithLabels() {
        return Arrays.stream(values())
                .map(i -> i.value + " - " + i.label)
                .collect(Collectors.joining("\n    "));
    }
}

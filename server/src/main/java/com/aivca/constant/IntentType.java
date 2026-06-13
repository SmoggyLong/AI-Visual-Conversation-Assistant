package com.aivca.constant;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 用户意图枚举。
 *
 * 新增意图只需加一行枚举值，prompt 约束自动更新。
 */
public enum IntentType {

    GREETING("greeting",       "问候、打招呼、告别"),
    VISION("vision",           "询问或展示画面内容（衣着、物体、场景）"),
    TECHNICAL("technical",     "编程、技术、知识问答"),
    EMERGENCY("emergency",     "求救、受伤、危险、恐慌"),
    GAME("game",               "玩游戏（成语接龙、猜谜等）"),
    GENERAL("general",         "其他闲谈");

    private final String value;
    private final String label;

    IntentType(String value, String label) {
        this.value = value;
        this.label = label;
    }

    @JsonValue
    public String toValue() { return value; }
    public String getLabel() { return label; }

    /** 容错解析，未知值降级为 GENERAL */
    public static IntentType fromString(String s) {
        for (IntentType t : values()) {
            if (t.value.equalsIgnoreCase(s)) return t;
        }
        return GENERAL;
    }

    /** 拼入 prompt 约束值：greeting|vision|technical|emergency|general */
    public static String promptOptions() {
        return Arrays.stream(values())
                .map(IntentType::toValue)
                .collect(Collectors.joining("|"));
    }

    /** 拼入 prompt 标签列表：greeting - 问候、打招呼\nvision - 询问画面... */
    public static String promptWithLabels() {
        return Arrays.stream(values())
                .map(i -> i.value + " - " + i.label)
                .collect(Collectors.joining("\n    "));
    }
}

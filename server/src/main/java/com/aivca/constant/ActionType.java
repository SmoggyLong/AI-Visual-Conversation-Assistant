package com.aivca.constant;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 虚拟人物动作枚举。
 *
 * 新增动作只需加一行枚举值，prompt 约束自动更新。
 */
public enum ActionType {

    IDLE("idle"),
    WAVE("wave"),
    POINT("point"),
    NOD("nod");

    private final String value;

    ActionType(String value) { this.value = value; }

    @JsonValue
    public String toValue() { return value; }

    /** 容错解析（未知值降级为 IDLE） */
    public static ActionType fromString(String s) {
        for (ActionType a : values()) {
            if (a.value.equalsIgnoreCase(s)) return a;
        }
        return IDLE;
    }

    /** 用于拼入 prompt：idle|wave|point|nod */
    public static String promptOptions() {
        return Arrays.stream(values())
                .map(ActionType::toValue)
                .collect(Collectors.joining("|"));
    }
}

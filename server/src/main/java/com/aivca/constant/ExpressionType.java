package com.aivca.constant;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 虚拟人物表情枚举。
 *
 * 新增表情只需加一行枚举值，prompt 约束自动更新。
 */
public enum ExpressionType {

    HAPPY("happy"),
    CURIOUS("curious"),
    NEUTRAL("neutral"),
    SURPRISED("surprised"),
    THINKING("thinking");

    private final String value;

    ExpressionType(String value) { this.value = value; }

    @JsonValue
    public String toValue() { return value; }

    /** 容错解析（未知值降级为 NEUTRAL） */
    public static ExpressionType fromString(String s) {
        for (ExpressionType e : values()) {
            if (e.value.equalsIgnoreCase(s)) return e;
        }
        return NEUTRAL;
    }

    /** 用于拼入 prompt：happy|curious|neutral|surprised|thinking */
    public static String promptOptions() {
        return Arrays.stream(values())
                .map(ExpressionType::toValue)
                .collect(Collectors.joining("|"));
    }
}

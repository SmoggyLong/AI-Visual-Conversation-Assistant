package com.aivca.constant;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 紧急程度枚举。
 */
public enum UrgencyLevel {

    LOW("low",         "闲聊、问候"),
    NORMAL("normal",   "普通问答"),
    HIGH("high",       "需要及时处理"),
    CRITICAL("critical","紧急，需要立即关注");

    private final String value;
    private final String label;

    UrgencyLevel(String value, String label) {
        this.value = value;
        this.label = label;
    }

    @JsonValue
    public String toValue() { return value; }
    public String getLabel() { return label; }

    /** 容错解析，未知值降级为 NORMAL */
    public static UrgencyLevel fromString(String s) {
        for (UrgencyLevel u : values()) {
            if (u.value.equalsIgnoreCase(s)) return u;
        }
        return NORMAL;
    }

    public static String promptOptions() {
        return Arrays.stream(values())
                .map(UrgencyLevel::toValue)
                .collect(Collectors.joining("|"));
    }

    public static String promptWithLabels() {
        return Arrays.stream(values())
                .map(u -> u.value + " - " + u.label)
                .collect(Collectors.joining("\n    "));
    }
}

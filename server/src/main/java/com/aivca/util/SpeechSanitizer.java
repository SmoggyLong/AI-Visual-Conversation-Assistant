package com.aivca.util;

import java.util.regex.Pattern;

/**
 * 语音文本清洗器。
 *
 * 去除 STT 返回的原始口语中的语气助词、重复字、纯噪声，
 * 输出干净的文本供后续处理使用。
 */
public final class SpeechSanitizer {

    private SpeechSanitizer() {}

    /** 语气助词正则（呃、嗯、啊、哦、呵、嗨、哎、哟等） */
    private static final Pattern FILLER = Pattern.compile("[呃嗯啊哦呵嗨哎哟]+");

    /** 连续重复字（3次及以上合并为1个，如"今今今天"→"今天"） */
    private static final Pattern REPEAT = Pattern.compile("(.)\\1{2,}");

    /** 纯标点/空白 */
    private static final Pattern PUNCT_ONLY = Pattern.compile("[\\p{Punct}\\p{Space}]+");

    /**
     * 清洗语音文本。
     *
     * @param raw STT 原始输出
     * @return 清洗后文本；如果清洗后无有效内容，返回 null
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // 1. 去语气助词
        String s = FILLER.matcher(raw).replaceAll("");

        // 2. 合并连续重复字
        s = REPEAT.matcher(s).replaceAll("$1");

        // 3. 去首尾空白
        s = s.trim();

        // 4. 纯标点/纯空白 → null
        if (s.isEmpty() || PUNCT_ONLY.matcher(s).matches()) return null;

        return s;
    }

    /**
     * 判断文本是否为纯噪声/语气词（清洗后无有效内容）。
     * 用于跳过完整管线，直接回复简短语。
     */
    public static boolean isNoise(String raw) {
        return sanitize(raw) == null;
    }
}

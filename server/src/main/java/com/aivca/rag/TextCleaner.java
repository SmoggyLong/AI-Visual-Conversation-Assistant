package com.aivca.rag;

/**
 * 文本清洗器 —— 去 markdown 标记 / URL / 噪声行。
 */
public final class TextCleaner {

    private static final int MIN_CHINESE_CHARS = 3;   // 行内中文字少于该值丢弃
    private static final int MIN_ENGLISH_CHARS = 5;    // 行内英文字少于该值丢弃

    private TextCleaner() {}

    /**
     * 清洗原始文本。
     *
     * @param raw 原始 Markdown 文本
     * @return 清洗后纯文本
     */
    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) return "";

        String s = raw;

        // 1. 去掉 front matter 块（如果 parser 没去掉）
        s = s.replaceAll("(?s)^---\\s*\\n.*?\\n---\\s*\\n", "");

        // 2. 代码块 → 占位
        s = s.replaceAll("(?s)```[^\\n]*\\n.*?```", "[代码块]");
        s = s.replaceAll("`([^`]+)`", "$1");

        // 3. Markdown 标记
        s = s.replaceAll("(?m)^#{1,6}\\s+", "");         // 标题
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "$1");     // 粗体
        s = s.replaceAll("\\*(.+?)\\*", "$1");           // 斜体
        s = s.replaceAll("~~(.+?)~~", "$1");             // 删除线
        s = s.replaceAll("(?m)^\\s*[-*+]\\s+", "· ");    // 无序列表
        s = s.replaceAll("(?m)^\\s*\\d+\\.\\s+", "");    // 有序列表
        s = s.replaceAll("!\\[.*?]\\(.*?\\)", "");       // 图片
        s = s.replaceAll("\\[(.+?)]\\(.*?\\)", "$1");    // 链接
        s = s.replaceAll("(?m)^\\s*>\\s*", "");          // 引用

        // 4. HTML 标签
        s = s.replaceAll("<[^>]+>", "");

        // 5. URL
        s = s.replaceAll("https?://\\S+", "[链接]");

        // 6. 连续空白
        s = s.replaceAll(" {2,}", " ");
        s = s.replaceAll("\\n{3,}", "\n\n");

        // 7. 逐行过滤噪声
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append("\n");
                }
                continue;
            }
            if (isNoiseLine(line)) continue;
            sb.append(line).append("\n");
        }

        return sb.toString().trim();
    }

    /** 判断是否为噪声行 */
    private static boolean isNoiseLine(String line) {
        if (line.equals("---") || line.equals("***") || line.equals("___")) return true;
        if (line.matches("^[\\p{Punct}\\s]+$")) return true;  // 纯标点
        int chinese = 0, english = 0;
        for (char c : line.toCharArray()) {
            if (c >= 0x4e00 && c <= 0x9fff) chinese++;
            else if (Character.isLetter(c)) english++;
        }
        return chinese + english == 0 || (chinese < MIN_CHINESE_CHARS && english < MIN_ENGLISH_CHARS);
    }
}

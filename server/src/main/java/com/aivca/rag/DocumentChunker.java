package com.aivca.rag;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分块器 —— 按句号切句，500 字/块，50 字 overlap。
 */
@Slf4j
public final class DocumentChunker {

    private DocumentChunker() {}

    static final int CHUNK_SIZE = 500;
    static final int OVERLAP_SIZE = 50;
    static final int MIN_CHUNK_SIZE = 50;

    /** 切句分隔符 */
    private static final String DELIMITER_REGEX = "[。\\n；;]";

    /**
     * 将清洗后的文本切分为 chunk 列表。
     *
     * @param cleanText 清洗后的纯文本
     * @return chunk 列表；文本过短且无有效内容时返回空列表
     */
    public static List<String> chunk(String cleanText) {
        if (cleanText == null || cleanText.isBlank()) return List.of();

        String text = cleanText.trim();
        if (text.length() <= CHUNK_SIZE) {
            return text.length() < MIN_CHUNK_SIZE ? List.of() : List.of(text);
        }

        // 1. 拆句子
        List<String> sentences = new ArrayList<>();
        for (String part : text.split(DELIMITER_REGEX)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        if (sentences.isEmpty()) return List.of();

        // 2. 贪婪累积
        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String sent : sentences) {
            if (buf.length() + sent.length() > CHUNK_SIZE && buf.length() >= MIN_CHUNK_SIZE) {
                chunks.add(buf.toString().trim());
                // overlap: 取旧块末尾 overlapSize 字符
                String overlap = buf.substring(Math.max(0, buf.length() - OVERLAP_SIZE));
                buf = new StringBuilder(overlap);
            }
            buf.append(sent).append("。");
        }

        if (buf.length() >= MIN_CHUNK_SIZE) {
            chunks.add(buf.toString().trim());
        }

        log.debug("[CHUNK] 原文 {} 字 → {} 块", text.length(), chunks.size());
        return chunks;
    }
}

package com.aivca.rag;

/**
 * 检索命中结果。
 */
public record SearchHit(
        String docId,
        String title,
        String content,
        double score,
        String source,
        DocType type
) {
    /** 短格式：source > title */
    public String sourceLabel() {
        return source + " > " + title;
    }
}

package com.aivca.rag;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * MongoDB 知识库文档 POJO —— 每个 chunk 一条记录。
 */
@Document(collection = "avca_knowledge")
@Data
public class KnowledgeDoc {

    @Id
    private String id;

    /** 文档唯一 ID = MD5(title + content 前200字) */
    @Indexed
    private String docId;

    /** 文档标题 */
    private String title;

    /** SOP / GENERAL */
    private DocType type;

    /** 清洗后的 chunk 文本 */
    private String content;

    /** BM25 关键词列表 */
    private List<String> keywords;

    /** 向量（float 数组） */
    private List<Float> embedding;

    /** 第几块 */
    private int chunkIndex;

    /** 总共几块 */
    private int totalChunks;

    /** 源文件名 */
    private String sourceFile;

    /** 源文件最后修改时间，用于增量更新判断 */
    private long fileModifiedAt;

    /** 入库时间 */
    private Instant createdAt;
}

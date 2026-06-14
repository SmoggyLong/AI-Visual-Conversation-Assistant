package com.aivca.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档入库引擎 —— parse → clean → chunk → embed → store。
 */
@Slf4j
@Component
public class DocumentIngester {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final MongoTemplate mongoTemplate;
    private final LlmTextCleaner llmCleaner;

    private static final int EMBED_BATCH_SIZE = 16;

    public DocumentIngester(EmbeddingModel embeddingModel,
                             EmbeddingStore<TextSegment> embeddingStore,
                             MongoTemplate mongoTemplate,
                             LlmTextCleaner llmCleaner) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.mongoTemplate = mongoTemplate;
        this.llmCleaner = llmCleaner;
    }

    /**
     * 完整入库流程。
     *
     * @param file 知识库文档文件
     * @return 入库结果
     */
    public IngestResult ingestFile(Path file) {
        String sourceFile = file.getFileName().toString();
        log.info("[INGEST] 开始处理: {}", sourceFile);

        // 1. 解析
        List<DocumentParser.ParsedDocument> docs = DocumentParser.parse(file);
        if (docs.isEmpty()) {
            log.info("[INGEST] 无有效文档: {}", sourceFile);
            return new IngestResult(0, 0, sourceFile, "EMPTY");
        }

        int totalDocs = 0;
        int totalChunks = 0;

        for (DocumentParser.ParsedDocument doc : docs) {
            // 2. 清洗（LLM 优先，失败降级正则）
            String clean = llmCleaner.clean(doc.content());
            if (clean == null || clean.isBlank()) {
                log.info("[INGEST] LLM 清洗失败/为空，降级正则 | title={}", doc.title());
                clean = TextCleaner.clean(doc.content());
            }
            if (clean == null || clean.isBlank()) {
                log.info("[INGEST] 清洗后为空 | title={}", doc.title());
                continue;
            }

            // 3. 分块
            List<String> chunks = DocumentChunker.chunk(clean);
            if (chunks.isEmpty()) {
                log.info("[INGEST] 分块为空 | title={}", doc.title());
                continue;
            }

            log.info("[INGEST] {} → {} 字 → {} 块", doc.title(), clean.length(), chunks.size());

            // 4. 批量 embedding
            List<Embedding> embeddings = batchEmbed(chunks);
            if (embeddings.size() != chunks.size()) {
                log.error("[INGEST] embedding 数量不匹配 | expected={} actual={}", chunks.size(), embeddings.size());
                continue;
            }

            // 5. 获取文件修改时间
            long fileModified = lastModified(file);

            String docId = doc.id();

            // 6. 逐块入库
            for (int i = 0; i < chunks.size(); i++) {
                String chunkId = docId + "_" + i;
                String chunkText = chunks.get(i);
                Embedding emb = embeddings.get(i);

                // 6a. InMemoryEmbeddingStore
                TextSegment segment = TextSegment.from(chunkText,
                        dev.langchain4j.data.document.Metadata
                                .from("doc_id", docId)
                                .put("title", doc.title())
                                .put("type", doc.type().name())
                                .put("chunk_index", String.valueOf(i))
                                .put("total_chunks", String.valueOf(chunks.size()))
                                .put("source", sourceFile));
                embeddingStore.add(chunkId, emb);

                // 6b. MongoDB
                KnowledgeDoc kDoc = new KnowledgeDoc();
                kDoc.setId(chunkId);
                kDoc.setDocId(docId);
                kDoc.setTitle(doc.title());
                kDoc.setType(doc.type());
                kDoc.setContent(chunkText);
                kDoc.setKeywords(doc.keywords());
                kDoc.setEmbedding(toFloatList(emb.vector()));
                kDoc.setChunkIndex(i);
                kDoc.setTotalChunks(chunks.size());
                kDoc.setSourceFile(sourceFile);
                kDoc.setFileModifiedAt(fileModified);
                kDoc.setCreatedAt(Instant.now());
                mongoTemplate.save(kDoc);
            }

            totalDocs++;
            totalChunks += chunks.size();
        }

        log.info("[INGEST] 完成 | file={} | docs={} | chunks={}", sourceFile, totalDocs, totalChunks);
        return new IngestResult(totalDocs, totalChunks, sourceFile, "OK");
    }

    /** 批量调用 embedding API（16 条/批） */
    private List<Embedding> batchEmbed(List<String> texts) {
        List<Embedding> all = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += EMBED_BATCH_SIZE) {
            int end = Math.min(i + EMBED_BATCH_SIZE, texts.size());
            List<TextSegment> batch = texts.subList(i, end).stream()
                    .map(TextSegment::from).toList();
            try {
                var resp = embeddingModel.embedAll(batch);
                all.addAll(resp.content());
                log.debug("[EMBED] 批次 {}-{} / {} 完成", i, end, texts.size());
            } catch (Exception e) {
                log.error("[EMBED] 批次失败 | range=[{},{}] | {}", i, end, e.getMessage());
                for (int j = i; j < end; j++) all.add(null);
            }
        }
        return all;
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) list.add(v);
        return list;
    }

    private static long lastModified(Path file) {
        try { return Files.getLastModifiedTime(file).toMillis(); }
        catch (Exception e) { return 0L; }
    }

    /** 入库结果 */
    public record IngestResult(int docCount, int chunkCount, String sourceFile, String status) {}
}

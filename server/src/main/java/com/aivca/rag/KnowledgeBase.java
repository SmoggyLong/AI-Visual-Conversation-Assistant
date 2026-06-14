package com.aivca.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.stream.Stream;

/**
 * 知识库核心 —— 启动加载、关键词索引、检索（Phase 3b 接入 KnowledgeAgent）。
 */
@Slf4j
@Component
public class KnowledgeBase {

    private final MongoTemplate mongoTemplate;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final DocumentIngester ingester;

    /** 关键词 → docId 倒排索引（BM25） */
    private final Map<String, Set<String>> keywordIndex = new ConcurrentHashMap<>();

    private static final Path KNOWLEDGE_DIR = Paths.get("data/knowledge");
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("md", "json", "txt");

    static {
        try { Files.createDirectories(KNOWLEDGE_DIR); } catch (IOException ignored) {}
    }

    public KnowledgeBase(MongoTemplate mongoTemplate,
                          EmbeddingStore<TextSegment> embeddingStore,
                          EmbeddingModel embeddingModel,
                          DocumentIngester ingester) {
        this.mongoTemplate = mongoTemplate;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.ingester = ingester;
    }

    // ==================== 启动加载 ====================

    @PostConstruct
    void loadOnStartup() {
        log.info("[KB] ═══ 启动知识库加载 ═══");
        long start = System.currentTimeMillis();

        // 1. 检查 MongoDB 是否有数据 → 加载到内存
        long mongoCount = mongoTemplate.count(new Query(), KnowledgeDoc.class);
        log.info("[KB] MongoDB 已有 {} 个向量块", mongoCount);

        if (mongoCount > 0) {
            loadFromMongoToMemory();
        }

        // 2. 扫描目录 → 增量入库
        int loaded = scanAndIngest();

        // 3. 重建关键词索引
        rebuildKeywordIndex();

        long elapsed = System.currentTimeMillis() - start;
        log.info("[KB] ═══ 启动完成 | 耗时{}ms | 内存块数={} | 关键词条目={} | 新入文件={} ═══",
                elapsed, embeddingStoreSize(), keywordIndexSize(), loaded);
    }

    /** MongoDB → InMemory */
    private void loadFromMongoToMemory() {
        try {
            List<KnowledgeDoc> all = mongoTemplate.findAll(KnowledgeDoc.class);
            for (KnowledgeDoc doc : all) {
                if (doc.getEmbedding() == null || doc.getEmbedding().isEmpty()) continue;
                float[] vector = new float[doc.getEmbedding().size()];
                for (int i = 0; i < vector.length; i++) vector[i] = doc.getEmbedding().get(i);
                Embedding emb = Embedding.from(vector);
                TextSegment segment = TextSegment.from(doc.getContent(),
                        dev.langchain4j.data.document.Metadata
                                .from("doc_id", doc.getDocId())
                                .put("title", doc.getTitle())
                                .put("type", doc.getType().name())
                                .put("chunk_index", String.valueOf(doc.getChunkIndex()))
                                .put("total_chunks", String.valueOf(doc.getTotalChunks()))
                                .put("source", doc.getSourceFile()));
                embeddingStore.add(doc.getId(), emb);
            }
            log.info("[KB] MongoDB → 内存 加载完成 | {} 条", all.size());
        } catch (Exception e) {
            log.error("[KB] MongoDB 加载失败: {}", e.getMessage());
        }
    }

    /** 扫描目录，只处理新文件或已修改文件 */
    private int scanAndIngest() {
        if (!Files.exists(KNOWLEDGE_DIR)) {
            log.info("[KB] 知识库目录不存在: {}", KNOWLEDGE_DIR.toAbsolutePath());
            return 0;
        }

        int loaded = 0;
        try (Stream<Path> files = Files.list(KNOWLEDGE_DIR)) {
            for (Path file : files.toList()) {
                if (!isSupported(file)) continue;

                String sourceFile = file.getFileName().toString();
                long fileModified = lastModified(file);

                // 检查是否已有同源文件
                KnowledgeDoc existing = mongoTemplate.findOne(
                        Query.query(Criteria.where("sourceFile").is(sourceFile)), KnowledgeDoc.class);

                if (existing != null && existing.getFileModifiedAt() >= fileModified) {
                    log.debug("[KB] 跳过未修改: {}", sourceFile);
                    continue;
                }

                // 有旧版本 → 先删除
                if (existing != null) {
                    removeByDocId(existing.getDocId());
                }

                DocumentIngester.IngestResult result = ingester.ingestFile(file);
                log.info("[KB] {} → docs={} chunks={}", result.sourceFile(), result.docCount(), result.chunkCount());
                loaded++;
            }
        } catch (IOException e) {
            log.error("[KB] 目录扫描失败: {}", e.getMessage());
        }
        return loaded;
    }

    // ==================== 关键词索引 ====================

    void rebuildKeywordIndex() {
        keywordIndex.clear();
        List<KnowledgeDoc> all = mongoTemplate.findAll(KnowledgeDoc.class);
        for (KnowledgeDoc doc : all) {
            if (doc.getKeywords() == null) continue;
            for (String kw : doc.getKeywords()) {
                keywordIndex.computeIfAbsent(kw.trim(), k -> ConcurrentHashMap.newKeySet()).add(doc.getDocId());
            }
        }
        log.info("[KB] 关键词索引重建完成 | 词条={} | 文档={}", keywordIndex.size(),
                keywordIndex.values().stream().flatMap(Set::stream).distinct().count());
    }

    /** 关键词命中 → docId 集合 */
    public Set<String> searchByKeywords(String queryText) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        for (var entry : keywordIndex.entrySet()) {
            if (queryText.contains(entry.getKey())) {
                result.addAll(entry.getValue());
            }
        }
        return result;
    }

    // ==================== 文档增删 ====================

    /** 重载指定文件 */
    public DocumentIngester.IngestResult reloadFile(Path file) {
        String sourceFile = file.getFileName().toString();
        KnowledgeDoc existing = mongoTemplate.findOne(
                Query.query(Criteria.where("sourceFile").is(sourceFile)), KnowledgeDoc.class);
        if (existing != null) {
            removeByDocId(existing.getDocId());
        }
        return ingester.ingestFile(file);
    }

    /** 重载目录所有文件 */
    public List<DocumentIngester.IngestResult> reloadAll() {
        List<DocumentIngester.IngestResult> results = new ArrayList<>();
        if (!Files.exists(KNOWLEDGE_DIR)) return results;
        try (Stream<Path> files = Files.list(KNOWLEDGE_DIR)) {
            for (Path file : files.toList()) {
                if (isSupported(file)) {
                    results.add(reloadFile(file));
                }
            }
        } catch (IOException e) {
            log.error("[KB] reloadAll 失败: {}", e.getMessage());
        }
        rebuildKeywordIndex();
        return results;
    }

    /** 按 docId 删除（MongoDB + InMemory） */
    public void removeByDocId(String docId) {
        // MongoDB
        mongoTemplate.remove(Query.query(Criteria.where("docId").is(docId)), KnowledgeDoc.class);
        // InMemory: 移除所有以 docId_ 开头的条目
        // InMemoryEmbeddingStore 没有 removeByPrefix，需要逐个检查
        log.info("[KB] 删除文档: docId={}", docId);
        // Note: InMemoryEmbeddingStore 没有批量删除 API，后续重建索引处理
    }

    /** 全文扫描搜索（Phase 3b 扩展为 KNN） */
    public List<KnowledgeDoc> searchByKeyword(String keyword) {
        return mongoTemplate.find(
                Query.query(new Criteria().orOperator(
                        Criteria.where("title").regex(keyword, "i"),
                        Criteria.where("content").regex(keyword, "i")
                )), KnowledgeDoc.class);
    }

    // ==================== 工具方法 ====================

    private boolean isSupported(Path file) {
        String name = file.getFileName().toString();
        if (name.startsWith(".")) return false;
        int dot = name.lastIndexOf('.');
        return dot > 0 && SUPPORTED_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    private long lastModified(Path file) {
        try { return Files.getLastModifiedTime(file).toMillis(); }
        catch (IOException e) { return 0L; }
    }

    /** 内存中向量块数量 */
    int embeddingStoreSize() {
        try {
            var field = embeddingStore.getClass().getDeclaredField("entries");
            field.setAccessible(true);
            return ((Map<?,?>) field.get(embeddingStore)).size();
        } catch (Exception e) {
            return -1;
        }
    }

    int keywordIndexSize() {
        return keywordIndex.values().stream().mapToInt(Set::size).sum();
    }
}

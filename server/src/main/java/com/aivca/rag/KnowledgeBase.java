package com.aivca.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
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
import java.util.stream.Stream;

/**
 * 知识库核心 —— 异步入库、定时扫描、关键词索引、检索。
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

    @Value("${knowledge.dir:../data/knowledge}")
    private String knowledgeDirStr;

    private Path knowledgeDir;

    /** 知识库是否已加载完毕 */
    private volatile boolean ready = false;

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("md", "json", "txt");

    /** 文件写入保护：修改时间和当前时间差 < 此值 → 文件可能还在写入中，跳过 */
    private static final long WRITE_GUARD_MS = 2000;

    public KnowledgeBase(MongoTemplate mongoTemplate,
                          EmbeddingStore<TextSegment> embeddingStore,
                          EmbeddingModel embeddingModel,
                          DocumentIngester ingester) {
        this.mongoTemplate = mongoTemplate;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.ingester = ingester;
    }

    // ==================== 启动加载（异步，不阻塞服务） ====================

    @PostConstruct
    void init() {
        knowledgeDir = Paths.get(knowledgeDirStr).toAbsolutePath().normalize();
        log.info("[KB] 知识库目录: {}", knowledgeDir);
        try { Files.createDirectories(knowledgeDir); } catch (IOException ignored) {}

        // 异步加载，不阻塞 Spring 启动
        new Thread(this::loadOnStartup, "kb-loader").start();
    }

    /** 是否可检索 */
    public boolean isReady() { return ready; }

    /** 后台加载：MongoDB → 内存 + 目录扫描 */
    private void loadOnStartup() {
        log.info("[KB] ═══ 后台知识库加载开始 ═══");
        long start = System.currentTimeMillis();

        // 1. MongoDB → InMemory
        try {
            long mongoCount = mongoTemplate.count(new Query(), KnowledgeDoc.class);
            log.info("[KB] MongoDB 已有 {} 个向量块", mongoCount);
            if (mongoCount > 0) {
                loadFromMongoToMemory();
            }
        } catch (Exception e) {
            log.warn("[KB] MongoDB 加载失败: {}", e.getMessage());
        }

        // 2. 扫描目录 → 增量入库
        int loaded = 0;
        try {
            loaded = scanAndIngest();
        } catch (Exception e) {
            log.warn("[KB] 文档入库失败: {}", e.getMessage());
        }

        // 3. 重建关键词索引
        try {
            rebuildKeywordIndex();
        } catch (Exception e) {
            log.warn("[KB] 关键词索引重建失败: {}", e.getMessage());
        }

        ready = true;
        long elapsed = System.currentTimeMillis() - start;
        log.info("[KB] ═══ 加载完成 | 耗时{}ms | 内存块数={} | 关键词条目={} | 新入文件={} ═══",
                elapsed, embeddingStoreSize(), keywordIndexSize(), loaded);
    }

    // ==================== 定时自动扫描 ====================

    /** 每 30 秒扫描一次目录，自动入库新增/修改的文件 */
    @Scheduled(fixedDelay = 30_000)
    void autoScan() {
        if (!ready) return;  // 首次加载完成前不重复扫
        try {
            int loaded = scanAndIngest();
            if (loaded > 0) {
                log.info("[KB] 自动扫描入库 {} 个文件", loaded);
                rebuildKeywordIndex();
            }
        } catch (Exception e) {
            log.warn("[KB] 自动扫描失败: {}", e.getMessage());
        }
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
                embeddingStore.add(emb, segment);
            }
            log.info("[KB] MongoDB → 内存 加载完成 | {} 条", all.size());
        } catch (Exception e) {
            log.error("[KB] MongoDB 加载失败: {}", e.getMessage());
        }
    }

    /** 扫描目录，只处理新文件或已修改文件 */
    private int scanAndIngest() {
        if (!Files.exists(knowledgeDir)) {
            log.info("[KB] 知识库目录不存在: {}", knowledgeDir.toAbsolutePath());
            return 0;
        }

        int loaded = 0;
        try (Stream<Path> files = Files.list(knowledgeDir)) {
            for (Path file : files.toList()) {
                if (!isSupported(file)) continue;

                String sourceFile = file.getFileName().toString();
                long fileModified = lastModified(file);

                // 写入保护：文件刚被修改（>0 且距现在 < 2s），可能还在写入中，跳过等下次扫描
                if (fileModified > 0 && System.currentTimeMillis() - fileModified < WRITE_GUARD_MS) {
                    log.debug("[KB] 跳过可能正在写入的文件: {}", sourceFile);
                    continue;
                }

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

    /** 获取所有领域术语（知识库关键词词条集合），供语音纠错使用 */
    public List<String> getDomainTerms() {
        return new ArrayList<>(keywordIndex.keySet());
    }

    // ==================== 检索 ====================

    /** 查询缓存：query → results，TTL 300s */
    private final Map<String, List<SearchHit>> searchCache = new ConcurrentHashMap<>();

    /**
     * KNN 向量检索。
     *
     * @param query 查询文本
     * @param topK  返回数量
     * @return 检索命中列表
     */
    public List<SearchHit> search(String query, int topK) {
        if (!ready || query == null || query.isBlank()) return List.of();

        String cacheKey = query.trim().toLowerCase();
        List<SearchHit> cached = searchCache.get(cacheKey);
        if (cached != null) return cached;

        try {
            var queryEmb = embeddingModel.embed(query).content();
            var result = embeddingStore.search(
                    dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                            .queryEmbedding(queryEmb)
                            .maxResults(topK)
                            .minScore(0.5)
                            .build());

            List<SearchHit> hits = new ArrayList<>();
            for (var match : result.matches()) {
                var meta = match.embedded().metadata();
                KnowledgeDoc doc = mongoTemplate.findById(meta.getString("doc_id"), KnowledgeDoc.class);
                if (doc != null) {
                    hits.add(new SearchHit(doc.getDocId(), doc.getTitle(), doc.getContent(),
                            match.score(), doc.getSourceFile(), doc.getType()));
                }
            }

            searchCache.put(cacheKey, hits);
            scheduleEvict(cacheKey);
            return hits;
        } catch (Exception e) {
            log.warn("[KB] 向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 混合检索：KNN 向量 + BM25 关键词，SOP 结果优先。
     */
    public List<SearchHit> searchHybrid(List<String> queries, int topK) {
        var merged = new java.util.LinkedHashMap<String, SearchHit>();  // docId → hit, 去重

        for (String q : queries) {
            // 1. KNN
            for (SearchHit hit : search(q, topK)) {
                merged.putIfAbsent(hit.docId(), hit);
            }
            // 2. BM25 关键词
            for (String docId : searchByKeywords(q)) {
                if (!merged.containsKey(docId)) {
                    KnowledgeDoc doc = mongoTemplate.findById(docId, KnowledgeDoc.class);
                    if (doc != null) {
                        merged.put(docId, new SearchHit(doc.getDocId(), doc.getTitle(),
                                doc.getContent(), 0.8, doc.getSourceFile(), doc.getType()));
                    }
                }
            }
        }

        // SOP 优先 → score 降序
        return merged.values().stream()
                .sorted(java.util.Comparator
                        .comparing(SearchHit::type, (a, b) -> a == DocType.SOP ? -1 : b == DocType.SOP ? 1 : 0)
                        .thenComparing(SearchHit::score, java.util.Comparator.reverseOrder()))
                .toList();
    }

    /** 缓存逐出（300s 后清理） */
    private void scheduleEvict(String key) {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
                .schedule(() -> searchCache.remove(key), 300, java.util.concurrent.TimeUnit.SECONDS);
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
        if (!Files.exists(knowledgeDir)) return results;
        try (Stream<Path> files = Files.list(knowledgeDir)) {
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

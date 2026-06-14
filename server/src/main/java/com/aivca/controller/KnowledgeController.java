package com.aivca.controller;

import com.aivca.rag.DocType;
import com.aivca.rag.DocumentIngester;
import com.aivca.rag.DocumentParser;
import com.aivca.rag.KnowledgeBase;
import com.aivca.rag.KnowledgeDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理 API。
 *
 * POST /api/knowledge/reload  → 重载 data/knowledge/ 目录
 * GET  /api/knowledge/list    → 文档列表
 * GET  /api/knowledge/stats   → 统计信息
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeBase knowledgeBase;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;
    private static final Path KNOWLEDGE_DIR = Paths.get("..", "data", "knowledge").toAbsolutePath().normalize();

    public KnowledgeController(KnowledgeBase knowledgeBase, MongoTemplate mongoTemplate,
                                ObjectMapper objectMapper) {
        this.knowledgeBase = knowledgeBase;
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
    }

    /** 前端表单添加文档 */
    @PostMapping("/add")
    public Map<String, Object> addDocs(@RequestBody List<Map<String, Object>> docs) {
        int added = 0;
        int totalChunks = 0;
        for (var d : docs) {
            try {
                String title = (String) d.getOrDefault("title", "未命名");
                String content = (String) d.getOrDefault("content", "");
                String typeStr = (String) d.getOrDefault("type", "general");
                @SuppressWarnings("unchecked")
                List<String> keywords = (List<String>) d.getOrDefault("keywords", List.of());

                if (title.isBlank() || content.isBlank()) continue;

                // 写入 JSON 文件
                String fileName = sanitizeFileName(title) + ".json";
                Path outFile = KNOWLEDGE_DIR.resolve(fileName);
                Map<String, Object> jsonDoc = new LinkedHashMap<>();
                jsonDoc.put("title", title);
                jsonDoc.put("content", content);
                jsonDoc.put("type", typeStr);
                jsonDoc.put("keywords", keywords);
                Files.createDirectories(KNOWLEDGE_DIR);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(outFile.toFile(), List.of(jsonDoc));

                // 触发入库
                List<DocumentParser.ParsedDocument> parsed = DocumentParser.parse(outFile);
                for (var pd : parsed) {
                    var result = knowledgeBase.reloadFile(outFile);
                    if (result != null) {
                        added++;
                        totalChunks += result.chunkCount();
                    }
                }
            } catch (Exception e) {
                log.warn("[API] 添加文档失败: {}", e.getMessage());
            }
        }
        knowledgeBase.rebuildKeywordIndex();
        return Map.of("status", "ok", "addedDocs", added, "totalChunks", totalChunks);
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        log.info("[API] 收到重载请求");
        List<DocumentIngester.IngestResult> results = knowledgeBase.reloadAll();
        long totalDocs = results.stream().mapToInt(DocumentIngester.IngestResult::docCount).sum();
        long totalChunks = results.stream().mapToInt(DocumentIngester.IngestResult::chunkCount).sum();
        log.info("[API] 重载完成 | files={} | docs={} | chunks={}", results.size(), totalDocs, totalChunks);
        return Map.of("status", "ok", "files", results.size(), "newDocs", totalDocs, "newChunks", totalChunks);
    }

    /** 文件上传 */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        try {
            String originalName = file.getOriginalFilename();
            if (originalName == null || originalName.isBlank()) {
                return Map.of("status", "error", "message", "文件名为空");
            }
            Path dest = KNOWLEDGE_DIR.resolve(originalName);
            Files.createDirectories(KNOWLEDGE_DIR);
            file.transferTo(dest.toFile());
            log.info("[API] 文件上传完成: {}", originalName);

            // 触发入库
            var result = knowledgeBase.reloadFile(dest);
            knowledgeBase.rebuildKeywordIndex();
            return Map.of("status", "ok", "fileName", originalName,
                    "chunks", result != null ? result.chunkCount() : 0);
        } catch (Exception e) {
            log.error("[API] 上传失败: {}", e.getMessage());
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        // 按 docId 去重
        var pipeline = List.of(
                new org.springframework.data.mongodb.core.aggregation.AggregationOperation() {
                    @Override
                    public org.bson.Document toDocument(org.springframework.data.mongodb.core.aggregation.AggregationOperationContext ctx) {
                        return new org.bson.Document("$group", new org.bson.Document("_id", "$docId")
                                .append("title", new org.bson.Document("$first", "$title"))
                                .append("type", new org.bson.Document("$first", "$type"))
                                .append("sourceFile", new org.bson.Document("$first", "$sourceFile"))
                                .append("keywords", new org.bson.Document("$first", "$keywords"))
                                .append("totalChunks", new org.bson.Document("$sum", 1))
                                .append("createdAt", new org.bson.Document("$first", "$createdAt")));
                    }
                }
        );

        var aggResult = mongoTemplate.aggregate(
                org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(pipeline),
                KnowledgeDoc.class, org.bson.Document.class);

        return aggResult.getMappedResults().stream().map(d -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docId", d.get("_id"));
            item.put("title", d.get("title"));
            item.put("type", d.get("type"));
            item.put("sourceFile", d.get("sourceFile"));
            item.put("keywords", d.get("keywords"));
            item.put("totalChunks", d.get("totalChunks"));
            item.put("createdAt", d.get("createdAt"));
            return item;
        }).toList();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        long totalChunks = mongoTemplate.count(new Query(), KnowledgeDoc.class);
        long sopCount = mongoTemplate.count(
                Query.query(Criteria.where("type").is("SOP")), KnowledgeDoc.class);
        var docIds = mongoTemplate.findDistinct(new Query(), "docId", KnowledgeDoc.class, String.class);
        return Map.of(
                "totalDocs", docIds.size(),
                "totalChunks", totalChunks,
                "sopCount", sopCount,
                "generalCount", totalChunks - sopCount
        );
    }

    private static String sanitizeFileName(String title) {
        return title.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "-");
    }
}

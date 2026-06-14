package com.aivca.controller;

import com.aivca.rag.DocumentIngester;
import com.aivca.rag.KnowledgeBase;
import com.aivca.rag.KnowledgeDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public KnowledgeController(KnowledgeBase knowledgeBase, MongoTemplate mongoTemplate) {
        this.knowledgeBase = knowledgeBase;
        this.mongoTemplate = mongoTemplate;
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
}

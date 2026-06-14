# PR: RAG 检索管线 + 语音识别纠错

## 标题

**feat: RAG 检索管线 — Query Rewrite + 混合检索 + LLM Rerank + 语言纠错**

---

## 功能描述

在知识库入库（Phase 3a）基础上，实现完整的 RAG 检索管线。KNOWLEDGE 意图触发后，自动检索知识库并将结果注入 LLM prompt。

### 检索管线

```
KNOWLEDGE intent → KnowledgeAgent.handle()
    │
    ├── QueryRewriter.rewrite(query, 3)    → ["如何退款","退款流程","退款政策"]
    ├── KnowledgeBase.searchHybrid(queries, 5)
    │     ├── KNN 向量检索 (Ollama) × 3    → 缓存命中跳过
    │     └── BM25 关键词检索 (keywordIndex) × 3
    ├── Merge + Dedup → SOP 优先排序
    ├── Reranker.rerank(query, merged, 3)  → LLM 打分取 topK
    ├── formatKnowledge(top3)              → [知识库] prompt 块
    └── LLM.generate(prompt + knowledge)
```

### 语音纠错

```
STT "家娃" → SpeechCorrector.correct("家娃", context, ["java","退款"...])
    → glm-4-flash → "Java" → 前端显示 "Java"
```

---

## 实现思路

### Query Rewrite (`QueryRewriter`)

- deepseek-chat 生成 3 个不同角度的子查询
- 原始 query 始终保留
- 自动去数字前缀和引号
- query < 10 字不重写

### 混合检索 (`KnowledgeBase.search/searchHybrid`)

- KNN: InMemoryEmbeddingStore, minScore=0.5
- BM25: keywordIndex 倒排索引
- SOP 文档自动排前
- 缓存: query → results, TTL 300s

### Rerank (`Reranker`)

- 候选截断到 10 条
- deepseek-chat 打分 0-10
- JSON 解析分数 → 排序取 topK
- 失败降级原始顺序

### 语音纠错 (`SpeechCorrector`)

- glm-4-flash 轻量快响应
- 上下文: 最近对话摘要
- 领域术语: 知识库关键词词条
- 短文本 (<5 字) 不纠错
- 字数差异 >50% 不使用

---

## 测试方式

### 检索验证

```bash
# 问一个知识库中有答案的问题
用户: "怎么退款"
# 预期: KnowledgeAgent 回复带来源标注
# "根据退款处理SOP，退款需要以下步骤..."

# 问一个闲聊问题
用户: "你好"
# 预期: IntentRecognizer → CONVERSATION → ConversationAgent → 不检索
```

### 纠错验证

```
用户语音: "我想问家娃"
# 预期: 前端显示 "我想问Java"
# 知识库关键词包含 ["java", "spring"] → 同音纠正
```

### 日志验证

```
[REWRITE] 怎么退款 → 4 个子查询
[KB] 向量检索 命中 5 条
[RERANK] 重排序 → topK=3
[RAG] 检索完成 | query=怎么退款 | hits=8 | top=3
[CORRECT] 家娃 → Java
```

---

## 变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| **新建** | `rag/SearchHit.java` | 检索结果 DTO |
| **新建** | `rag/QueryRewriter.java` | LLM 子查询生成 |
| **新建** | `rag/Reranker.java` | LLM 打分排序 |
| **新建** | `rag/SpeechCorrector.java` | STT 同音纠错 |
| 修改 | `rag/KnowledgeBase.java` | +search/searchHybrid/缓存/getDomainTerms |
| 修改 | `rag/DocumentIngester.java` | 修复 add(emb,segment) |
| 修改 | `agent/KnowledgeAgent.java` | 接入完整 RAG 管线 |
| 修改 | `agent/AgentRouter.java` | KnowledgeAgent 注入 RAG 依赖 |
| 修改 | `config/RAGConfig.java` | +SpeechCorrector Bean |
| 修改 | `handler/ConversationWSHandler.java` | onFinal 中插语音纠错 |

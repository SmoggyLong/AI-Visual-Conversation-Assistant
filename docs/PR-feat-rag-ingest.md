# PR: RAG 知识库文档入库管线

## 标题

**feat: RAG 文档入库——解析/清洗/分块/embedding 完整管线 + MongoDB/InMemory 双存储**

---

## 功能描述

实现知识库文档从原始文件到可检索向量的完整入库管线，支持自动扫描、异步入库、格式兼容。

### 整体架构

```
data/knowledge/*.md  ← 管理员放文件
       │
       ▼ @Scheduled(30s) 或 服务启动异步
DocumentParser (解析 .md/.json/.txt, front matter)
       │
       ▼
TextCleaner (去 Markdown/URL/HTML/噪声行)
       │
       ▼
DocumentChunker (按。切句, 500字/块, 50字 overlap)
       │
       ▼
OllamaEmbeddingModel (nomic-embed-text, 本地 768 维)
       │
       ▼
┌──────┴──────┐
InMemoryStore   MongoDB avca_knowledge
(实时KNN)       (持久化+重启恢复)
```

### 核心特性

| 特性 | 说明 |
|------|------|
| **格式兼容** | `.md`(YAML front matter) / `.json` / `.txt`；不支持格式自动跳过 |
| **防竞态** | 文件修改后 2 秒静默等待，避免读取不完整文件 |
| **异步入库** | `@PostConstruct` 不阻塞启动，后台线程加载 |
| **定时扫描** | `@Scheduled(fixedDelay=30000)` 每 30 秒自动发现新文件 |
| **增量更新** | 对比 `fileModifiedAt` 只处理新增/修改的文件 |
| **重启恢复** | MongoDB 已有数据 → 直接加载到内存，无需重 embedding |
| **关键词索引** | BM25 倒排索引，支持 metadata.keywords 精确匹配 |
| **LLM 清洗** | 代码保留（`LlmTextCleaner`），当前使用正则清洗确保启动速度 |

### 格式支持矩阵

| 格式 | 支持 | 说明 |
|------|:---:|------|
| `.md` (含 front matter) | ✅ | 推荐格式，标题/类型/关键词元数据 |
| `.md` (无 front matter) | ✅ | 退化：标题=文件名，类型=GENERAL |
| `.json` | ✅ | 数组格式，支持单文件多文档 |
| `.txt` | ✅ | 整文件当一篇，标题=文件名 |
| `.pdf` | ❌ | 自动跳过并记录日志 |
| `.html` | ❌ | 同上 |
| `.csv` | ❌ | 同上 |
| 无后缀 | ❌ | 同上 |
| 空文件 | ❌ | 同上 |

### 存储

| 层 | 位置 | 作用 |
|------|------|------|
| 文档源 | `data/knowledge/*.md` | 管理员编辑 |
| 向量持久化 | MongoDB `avca_knowledge` 集合 | 重启恢复 |
| 向量检索 | InMemoryEmbeddingStore | 实时 KNN (微秒级) |
| 关键词索引 | `HashMap<String, Set<String>>` | BM25 精确匹配 |

### 文档管理

| 方式 | 触发时机 |
|------|----------|
| 丢文件 + 等 30 秒 | `@Scheduled` 自动扫描 |
| 丢文件 + curl | `POST /api/knowledge/reload` |
| 服务重启 | `@PostConstruct` 异步 |
| 查看列表 | `GET /api/knowledge/list` |
| 查看统计 | `GET /api/knowledge/stats` |

### Embedding 方案演化

| 尝试 | 结果 |
|------|------|
| Zhipu `embedding-2` | 余额不足 |
| DeepSeek | 不支持 embeddings API |
| LangChain4j `all-minilm-l6-v2` | jar 不存在 |
| HashEmbeddingModel (自研) | 低质量，作 backup |
| **Ollama `nomic-embed-text`** | ✅ 最终方案 |

---

## 实现思路

### 文档解析 (`DocumentParser`)

- `.md`：正则 `^---\n(.*?)\n---\n(.*)` 提取 front matter，逐行解析 YAML 字段
- `.json`：Jackson deserialize 为数组，遍历构建 `ParsedDocument`
- `.txt`：文件名去扩展名作 title，type=GENERAL
- 文档 MD5 去重（`generateId(title+content[:200])`）

### 文本清洗 (`TextCleaner`)

7 步正则管线：
1. 去 front matter → 2. 代码块占位 → 3. Markdown 标记剥离 → 4. HTML 标签 → 5. URL 替换 → 6. 空白压缩 → 7. 噪声行过滤（中文<3字且英文<5字符）

### 分块 (`DocumentChunker`)

- 按 `。\n；;` 切句
- 贪婪累积到 500 字 flush
- 块间 50 字重叠防止语义断裂
- < 50 字块丢弃

### Embedding

- Ollama `nomic-embed-text`，768 维
- 批量 16 条/批调 API
- 超时 60s，失败不填 null，跳批继续

### 异步入库 + 定时扫描

```java
@PostConstruct void init() { new Thread(this::loadOnStartup, "kb-loader").start(); }
@Scheduled(fixedDelay=30_000) void autoScan() { scanAndIngest(); }
```

启动 2 秒服务就绪，kb-loader 后台跑。每 30 秒自动扫目录。

---

## 测试方式

### 验证入库

```bash
# 查看统计
curl http://localhost:8080/api/knowledge/stats
# → {"totalDocs":16, "totalChunks":18, "sopCount":4}

# 手动重载
curl -X POST http://localhost:8080/api/knowledge/reload
# → {"status":"ok", "newDocs":3, "newChunks":5}
```

### 验证 MongoDB

```bash
mongosh mongodb://localhost:27017/avca
db.avca_knowledge.countDocuments()
db.avca_knowledge.findOne({}, {embedding: {$slice: 3}})
```

### 验证格式兼容

| 操作 | 预期 |
|------|------|
| 丢一个 `.md` 到 `data/knowledge/` | 30 秒内自动入库 |
| 丢一个 `.pdf` | 跳过，日志 `跳过不支持格式` |
| 修改已入 `.md` 文件 | 下次扫描检测到 `fileModifiedAt` 变化，重新入库 |
| 重启服务 | MongoDB 数据直接加载到 InMemory，不再调 Ollama |

---

## 变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| **新建** | `rag/DocType.java` | SOP/GENERAL 枚举 |
| **新建** | `rag/KnowledgeDoc.java` | MongoDB POJO |
| **新建** | `rag/DocumentParser.java` | 解析 .md/.json/.txt |
| **新建** | `rag/TextCleaner.java` | 正则清洗 |
| **新建** | `rag/DocumentChunker.java` | 分块 |
| **新建** | `rag/DocumentIngester.java` | 完整入库流程 |
| **新建** | `rag/KnowledgeBase.java` | 检索+索引+启停管理 |
| **新建** | `rag/LlmTextCleaner.java` | LLM 清洗(备用) |
| **新建** | `rag/HashEmbeddingModel.java` | 兜底嵌入模型 |
| **新建** | `config/RAGConfig.java` | Ollama EmbeddingModel Bean |
| **新建** | `controller/KnowledgeController.java` | REST API |
| **新建** | `data/knowledge/` | 20 个示例/测试文档 |
| 修改 | `pom.xml` | + MongoDB + Ollama 依赖 |
| 修改 | `application.yml` | + MongoDB + knowledge.dir 配置 |
| 修改 | `AiVisualConversationApplication.java` | + @EnableScheduling |

# AI 视觉对话助手 — 设计计划书

---

## 一、用户故事

### 计划实现 & 实际实现对照

| 编号 | 用户故事 | 优先级 | 计划 | 实际 |
|------|----------|--------|------|------|
| US-01 | 打开应用即看到摄像头实时预览画面，确认 AI 能看到自己 | P0 | ✅ | ✅ |
| US-02 | 按住按钮说话，松手后 AI 自动识别语音并给出回复 | P0 | ✅ | ✅ |
| US-03 | AI 能描述摄像头中看到的物体/场景，结合我说的话做回应 | P0 | ✅ | ✅ |
| US-04 | AI 的回复以**语音播报**出来，同时显示文字 | P0 | ✅ | ✅ (百度 TTS 度丫丫 + 浏览器兜底) |
| US-05 | 对话历史以聊天气泡形式展示，可滚动回溯 | P0 | ✅ | ✅ |
| US-06 | 支持**自动检测说话结束**（VAD 模式），无需按键也可对话 | P1 | ✅ | ✅ |
| US-07 | 对话中可看到 AI "正在看"的那一帧截图 | P1 | ✅ | ❌ |
| US-08 | 暂停/恢复摄像头，保护隐私 | P1 | ✅ | ✅ |
| US-09 | 对话过程中展示 AI 的状态（正在听/正在看/正在思考/正在说） | P1 | ✅ | ✅ |
| US-10 | 屏幕共享模式：分享屏幕内容让 AI 辅助操作 | P2 | ❌ | ❌ |
| US-11 | 对话历史持久化存储，刷新不丢失 | P2 | ❌ | ✅ (Redis + MongoDB) |
| US-12 | 多语言支持（中/英切换） | P3 | ❌ | ❌ |
| US-13 | 离线降级：网络断开时用本地模型做基础 STT | P3 | ❌ | ❌ |

**覆盖率**: P0 4/4 ✅ | P1 3/4 ✅ | P2 1/2 ✅ | P3 0/2

---

## 二、运营成本控制策略

### 成本来源

每轮对话成本 = Vision API + STT API + LLM 推理 + TTS API + Embedding

### 策略清单

| 编号 | 策略 | 原理 | 实际效果 |
|------|------|------|----------|
| C-01 | **帧采样间隔控制 (5fps)** | 非每帧都发给 AI，200ms 取一帧 | 视觉调用量降低 60% |
| C-02 | **帧差检测** | 端侧像素对比，画面没变就复用上次描述 | 静态场景节省 100% |
| C-03 | **VAD 语音端点检测** | 只在用户说话时触发管线 | 减少无效对话 80%+ |
| C-04 | **视觉描述缓存** | 说话期间 VISION 事件持续更新 session 缓存，SPEECH_BATCH 直接复用 | 每轮节省 1 次 Vision API |
| C-05 | **上下文窗口裁剪 + 摘要压缩** | 保留最近 3-5 轮 raw，超过 8 轮触发 LLM 摘要压缩 | 控制每轮 token 消耗 |
| C-06 | **模型分级策略** | 闲聊/游戏用 glm-4-flash(便宜)，技术/画面用 deepseek-chat | 高频场景成本降低 70% |
| C-07 | **Vision 频率限流** | VISION 事件每 3 秒最多分析 1 次 | Vision API 调用降 93% |
| C-08 | **关键帧采样** | 说话期间帧缓冲均匀采样 5 帧，体积恒定 ~60KB | 避免 WebSocket 1009 断开 |
| C-09 | **空闲会话回收** | 30 分钟无交互断开 WebSocket + Redis TTL 30min | 释放服务端资源 |
| C-10 | **噪声过滤** | 纯语气词跳过 Intent+Agent 管线 | 无效 LLM 调用为零 |
| C-11 | **熔断器保护** | Vision 10 次失败熔断 30s，Agent 5 次失败熔断 30s | 阻止雪崩式 API 浪费 |
| C-12 | **Embedding 本地化** | Ollama nomic-embed-text 本地运行 | Embedding API 费用 = 0 |
| C-13 | **TTS 免费化** | 百度 TTS 免费额度 + 浏览器 SpeechSynthesis 兜底 | TTS 成本接近零 |
| C-14 | **RAG 检索缓存** | 相同 query 300s 内不重复检索 | 减少 Ollama Embedding 调用 |

**核心思路**: **端侧过滤，云侧推理，能省则省，能用本地不用远程。**

---

## 三、端云架构设计

### 3.1 总体架构图 (v3)

```
┌─────────────────────────────────────────────────────────────────────┐
│                      BROWSER (客户端 - React + TypeScript)           │
│                                                                      │
│  ┌──────────┐  ┌──────────┐  ┌────────────┐  ┌──────────────────┐  │
│  │ 摄像头    │  │ 麦克风    │  │ Canvas     │  │ Web Audio API    │  │
│  │ 5fps+帧差 │  │ PCM+VAD  │  │ 帧截图     │  │ 16kHz单声道采集  │  │
│  └────┬─────┘  └────┬─────┘  └──────┬─────┘  └────────┬─────────┘  │
│       │              │               │                 │             │
│       ▼              ▼               ▼                 ▼             │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                 WebSocket 客户端 (单一长连接)                   │   │
│  │  右侧面板: [对话] [知识库] [评测]  |  Agent身份徽章 | Toast反馈 │   │
│  └──────────────────────────┬───────────────────────────────────┘   │
└─────────────────────────────┼───────────────────────────────────────┘
                              │  WebSocket (ws://)
                              │
┌─────────────────────────────▼───────────────────────────────────────┐
│                  SERVER (Java 17 + Spring Boot 3.3)                  │
│                                                                      │
│  ┌──────────────────────────────┐  ┌────────────────────────────┐   │
│  │ ConversationWSHandler        │  │ SessionManager             │   │
│  │ (消息路由 8C2S → 8S2C)      │  │ (会话CRUD + Redis双写)     │   │
│  │ + SpeechCorrector(纠错)      │  └────────────┬───────────────┘   │
│  │ + Welcome(首次引导)           │               │                    │
│  └──────────────┬───────────────┘               │                    │
│                 │                               ▼                    │
│                 ▼                    ┌──────────────────────────┐   │
│  ┌─────────────────────────────┐    │  Redis + MongoDB          │   │
│  │ EpisodeConsumer (串行消费)   │    │  avca:hist/sum/idiom      │   │
│  │ Vision+Intent 并行处理       │    │  avca_knowledge(向量)     │   │
│  │ CircuitBreaker 熔断保护      │    └──────────────────────────┘   │
│  └──────────────┬──────────────┘                                    │
│                 │                                                    │
│                 ▼                                                    │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    Orchestrator                                │   │
│  │  SpeechSanitizer → VisionStructurer → IntentRecognizer         │   │
│  │  (含对话状态注入: 游戏中/知识库中)                               │   │
│  │  → AgentRouter(4 Agent) → Agent.handle() → ChatResponse       │   │
│  └───────┬──────────────────────────┬────────────────────────────┘   │
│          │                          │                                 │
│          ▼                          ▼                                 │
│  ┌────────────────┐  ┌────────────────────────────────────────┐     │
│  │ Baidu ASR (STT)│  │ LangChain4j ChatLanguageModel           │     │
│  │ PCM 流式识别    │  │ • deepseekModel  (Vision/Knowledge/Game)│     │
│  └────────────────┘  │ • zhipuFlashModel (Conversation)        │     │
│                      └────────────────────────────────────────┘     │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ RAG 知识库 (KnowledgeAgent)                                    │   │
│  │ QueryRewrite → KNN+BM25混合检索 → Rerank → [知识库] context    │   │
│  │ Ollama nomic-embed-text 本地 768 维 | MongoDB+InMemory 存储    │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ LLM 评测框架                                                    │   │
│  │ 10 测试用例 → 4 Agent → LLMJudge(4维打分) → 评测报告+RAG命中率 │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 Agent 架构

```
用户语音 → STT → SpeechCorrector(纠错) → IntentRecognizer(DeepSeek)
                                               │
                                          AgentRouter (4 Agent)
                                               │
             ┌────────┬──────────┼──────────┬────────┐
             ▼        ▼          ▼          ▼        ▼
        VisionAgent  Knowledge  Conversation  GameAgent
        (deepseek)   Agent      Agent        (deepseek)
                     (deepseek)  (glm-4-flash)
             │        │          │          │
             │        │          │          │
     ┌───────┘   ┌───┘     ┌───┘     ┌───┘
     ▼           ▼         ▼         ▼
  画面描述   知识库检索   自然回复   成语追踪
+ 动作检测   +来源标注   +跨轮记忆  +不重复
```

### 3.3 技术栈

| 层 | 技术 |
|----|------|
| 前端 | React 18 + TypeScript + Vite + Tailwind CSS 3.4 |
| 后端 | Spring Boot 3.3.1 + Java 17 |
| LLM | LangChain4j 0.36.2 (DeepSeek V3 + 智谱 GLM-4) |
| Vision | 智谱 GLM-4V (HTTP, 动作检测强化) |
| STT | 百度 ASR (REST API, 流式返字) |
| TTS | 百度 TTS (度丫丫情感女声) + 浏览器兜底 |
| Embedding | Ollama nomic-embed-text (本地 768 维, 免费) |
| 向量存储 | MongoDB + InMemoryEmbeddingStore |
| RAG | KNN 向量 + BM25 关键词 + LLM Rerank |
| 会话 | Redis (双写, TTL 30min) |
| 评测 | LLMJudge 4 维打分 + 基线对比 + RAG 命中率 |
| 保护 | CircuitBreaker 熔断 + 文件写入保护 |
| 语音纠错 | glm-4-flash 同音词纠正 |

---

## 四、RAG 知识库

### 4.1 入库管线

```
data/knowledge/*.md ← 管理员放文件
    │  @Scheduled(30s) 或 POST /reload 或 前端上传
    ▼
DocumentParser (解析 .md/.json/.txt/.pdf)
    ▼
TextCleaner (正则去噪声)
    ▼
DocumentChunker (500字/块, 50字重叠)
    ▼
OllamaEmbeddingModel (768维)
    ▼
┌──────┴──────┐
InMemoryStore   MongoDB avca_knowledge
(实时KNN)       (持久化+重启恢复)
```

### 4.2 检索管线

```
KNOWLEDGE intent → KnowledgeAgent
    ├── QueryRewriter → 3个子查询
    ├── searchHybrid → KNN + BM25 混合检索
    ├── Reranker → LLM 打分取 topK=3
    └── 注入 prompt → LLM 生成回复（含来源标注）
```

### 4.3 文档管理

```
前端 [知识库] Tab:
  ├── [↑上传] → .md/.json/.txt/.pdf 自动入库
  ├── [+添加] → 表单填写入库
  ├── [↻重载] → 从磁盘重新加载
  └── 文档列表 + Toast 操作反馈

后端:
  POST /api/knowledge/add     → 批量添加
  POST /api/knowledge/upload  → 文件上传
  POST /api/knowledge/reload  → 磁盘重载
  GET  /api/knowledge/list    → 文档列表
  GET  /api/knowledge/stats   → 统计信息
```

---

## 五、评测框架

```
POST /api/eval/run → 10 测试用例
    → IntentRecognizer + Agent.handle() + LLMJudge(4维)
    → 通过率 + 各Agent平均分 + RAG命中率
    → 前端 [评测] Tab 渲染 + [保存基线]
```

---

## 六、异常保护体系

```
CircuitBreaker:
  Vision: 10次失败 → 熔断30s → 跳过画面分析
  Agent:  5次失败 → 熔断30s → 直接返回fallbackText

降级链路:
  TTS失败 → 静默跳过, 文字正常
  Embedding失败 → 知识库为空, 纯模型回答
  Vision熔断 → 跳过画面, 仅文本回复
  评测Judge失败 → 默认0.5分

写入保护:
  文件修改后2秒静默等待 → 跳过可能正在写入的文件
```

---

## 七、成本分析

| API | 模型 | 每轮成本(估算) | 频率控制 |
|-----|------|:---:|------|
| Vision | glm-4.6v | ¥0.05 | 3s限流 + 帧差 + 缓存 |
| LLM (闲聊) | glm-4-flash | ¥0.001 | VAD过滤噪声 |
| LLM (技术) | deepseek-chat | ¥0.003 | KnowledgeAgent最多 |
| STT | 百度 ASR | 免费额度 | VAD仅识别有效语音 |
| TTS | 百度 TTS | 免费额度 | 200字截断 |
| Embedding | Ollama 本地 | ¥0 | 100%免费 |
| 总计(单轮) | — | ~¥0.05-0.08 | — |

---

## 八、分支地图

| 分支 | 内容 |
|------|------|
| `feat/agent-router` | 6 Agent + DeepSeek/智谱 + 多意图 |
| `feat/session-memory` | 会话历史压缩 + Agent 回复推送 |
| `refactor/langchain4j-agent-redis` | LangChain4j 迁移 + 4 Agent + Redis |
| `feat/rag-ingest` | RAG 文档入库 (解析/清洗/分块/embedding) |
| `feat/rag-retrieval` | RAG 检索管线 + 语音纠错 |
| `feat/tts-circuit-eval` | TTS语音 + CircuitBreaker + 知识库面板 + PDF + 评测框架 |
| `feat/agent-identity` | Agent身份展示(欢迎消息+状态栏徽章+气泡标签) |
| **`feat/async-pipeline`** | **异步并行管线 + Vision动作检测 + thinking字幕 + RAG命中率** ← 当前 |

---

*文档版本：v4 — 2026-06-14*

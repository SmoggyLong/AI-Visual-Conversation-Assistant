# AI 视觉对话助手

> **一款基于端云协同架构的 AI 视觉对话应用。打开摄像头与麦克风，AI 能看见你、听懂你、用语音回复你。**

🎥 [**演示视频**](https://www.bilibili.com/video/BV1nkJK6qEmx/)

---

## 核心功能

| 功能 | 说明 |
|------|------|
| 👁 **视觉理解** | 摄像头 5fps 采集 + 帧差检测 + GLM-4V 分析画面内容与动作 |
| 🎤 **语音对话** | VAD 自动检测 + 百度 STT 流式识别 + 同音词纠错 |
| 🤖 **4 Agent 路由** | Vision / Knowledge / Conversation / Game，DeepSeek + 智谱双模型 |
| 🔊 **语音播报** | 百度 TTS 度丫丫情感女声 + 浏览器兜底 |
| 📚 **RAG 知识库** | 文档入库(解析/清洗/分块/embedding) → 检索(QueryRewrite+混合检索+Rerank) |
| 📊 **LLM 评测** | 10 测试用例 → LLMJudge 4 维打分 → RAG 命中率 → 基线对比 |
| ⚡ **异步并行管线** | Vision + Intent 并行处理，延迟降低 40% |
| 🛡 **异常保护** | CircuitBreaker 熔断 + 噪声过滤 + 空文本过滤 |
| 🎨 **前端体验** | 字幕框 + 对话气泡 + Agent 身份徽章 + [对话/知识库/评测] Tab |

---

## 技术栈

| 层 | 技术 |
|----|------|
| 前端 | React 18 + TypeScript + Vite + Tailwind CSS 3.4 |
| 后端 | Spring Boot 3.3.1 + Java 17 |
| LLM | LangChain4j 0.36.2 (DeepSeek V3 + 智谱 GLM-4) |
| Vision | 智谱 GLM-4V (动作检测强化) |
| STT | 百度 ASR (流式返字) |
| TTS | 百度 TTS (度丫丫) + 浏览器 SpeechSynthesis 兜底 |
| Embedding | Ollama nomic-embed-text (本地 768 维, 免费) |
| 向量存储 | MongoDB + InMemoryEmbeddingStore |
| 会话 | Redis (双写, TTL 30min) |
| 文档解析 | PDFBox 3.0.3 (支持 .md/.json/.txt/.pdf) |

---

## 快速启动

### 前置依赖

```bash
# MongoDB (Windows 服务, 端口 27017)
# Redis (Windows, 端口 6379)
# Ollama (本地 Embedding 模型)

ollama pull nomic-embed-text
```

### 1. 启动服务端

```bash
cd server
# 确保 .env 文件包含百度/智谱/DeepSeek API Key
mvn spring-boot:run
# → http://localhost:8080
# → ws://localhost:8080/ws/conversation
```

### 2. 启动客户端

```bash
cd client
npm install
npm run dev
# → http://localhost:5173
```

### 3. 使用

1. 浏览器打开 `http://localhost:5173`
2. 授权摄像头 + 麦克风
3. 说话与 AI 对话 — AI 会播报语音回复
4. 右侧面板切换 `[对话] [知识库] [评测]`

---

## 知识库管理

```bash
# 文档放这里 (支持 .md/.json/.txt/.pdf)
data/knowledge/
├── sop/           # SOP 文档
├── general/       # 通用知识
└── imported/      # 上传的文件

# 自动扫描 (每30秒) 或手动重载
curl -X POST http://localhost:8080/api/knowledge/reload

# 前端 [知识库] Tab → 上传/添加/查看文档列表
```

### 评测框架

```bash
# 前端 [评测] Tab → [运行评测]
# 或 API
curl -X POST http://localhost:8080/api/eval/run
curl -X POST http://localhost:8080/api/eval/baseline
```

---

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/health` | GET | 健康检查 |
| `/ws/conversation` | WebSocket | 全双工通信 |
| `/api/knowledge/reload` | POST | 重载知识库 |
| `/api/knowledge/list` | GET | 文档列表 |
| `/api/knowledge/stats` | GET | 知识库统计 |
| `/api/knowledge/add` | POST | 添加文档 |
| `/api/knowledge/upload` | POST | 上传文件 |
| `/api/eval/run` | POST | 运行评测 |
| `/api/eval/baseline` | POST | 保存评测基线 |

---

## 成本控制

| 策略 | 效果 |
|------|------|
| 帧差检测 + 3s Vision 限流 | Vision API 调用降 93% |
| 模型分级 (闲聊用 flash, 技术用 deepseek) | 高频场景降 70% |
| Ollama 本地 Embedding | 零 Embedding 费用 |
| VAD 噪声过滤 | 无效管线调用为零 |
| CircuitBreaker 熔断 | 阻止 API 雪崩浪费 |
| 百度免费额度 (STT+TTS) | 核心语音零成本 |
| 视觉缓存复用 | 每轮节省 1 次 Vision API |

**单轮对话成本约 ¥0.05-0.08**

---

## 项目结构

```
AI Visual Conversation Assistant/
├── client/                              # React 前端
│   └── src/
│       ├── App.tsx
│       ├── components/
│       │   ├── CameraView.tsx           # 摄像头预览
│       │   ├── SpeechOverlay.tsx        # 字幕框 (含 thinking 动画)
│       │   ├── ConversationPanel.tsx    # 对话气泡 + Agent标签
│       │   ├── ControlBar.tsx           # 设备控制栏
│       │   ├── StatusIndicator.tsx      # 状态栏 + Agent徽章
│       │   ├── KnowledgePanel.tsx       # 知识库管理面板
│       │   └── EvalPanel.tsx            # 评测面板
│       ├── hooks/
│       │   ├── useCamera.ts
│       │   ├── useMicrophone.ts
│       │   ├── useAudioRecorder.ts
│       │   ├── useWebSocket.ts
│       │   ├── useKnowledge.ts
│       │   └── useEval.ts
│       └── types/messages.ts
│
├── server/                              # Java 后端
│   └── src/main/java/com/aivca/
│       ├── agent/                       # 4 Agent
│       │   ├── VisionAgent.java
│       │   ├── KnowledgeAgent.java      # + RAG 检索管线
│       │   ├── ConversationAgent.java
│       │   └── GameAgent.java
│       ├── api/
│       │   ├── llm/router/              # IntentRecognizer
│       │   ├── vision/                  # GLM-4V
│       │   ├── stt/                     # 百度 ASR
│       │   └── tts/                     # 百度 TTS
│       ├── rag/                         # RAG 知识库
│       │   ├── DocumentParser.java      # .md/.json/.txt/.pdf
│       │   ├── DocumentIngester.java    # 完整入库管线
│       │   ├── KnowledgeBase.java       # 检索 + 索引管理
│       │   ├── QueryRewriter.java       # 查询改写
│       │   ├── Reranker.java            # 重排序
│       │   └── eval/                    # 评测框架
│       │       ├── LLMJudge.java
│       │       └── Evaluator.java
│       ├── handler/                     # WebSocket + Orchestrator
│       ├── service/                     # EpisodeConsumer + SessionManager
│       ├── config/                      # LangChain4j + Redis + MongoDB
│       ├── model/                       # 数据模型
│       └── util/                        # 工具 (CircuitBreaker等)
│
├── data/
│   ├── knowledge/                       # 知识库文档
│   │   ├── sop/                         # SOP 文档
│   │   ├── general/                     # 通用知识
│   │   └── imported/                    # 上传文件
│   └── eval/                            # 评测用例
│       └── test_cases.json
│
├── docs/                                # PR 文档
├── DESIGN.md                            # 详细设计文档
├── README.md                            # 本文件
└── .opencode/skills/                    # AI 编码规范
```

---

## 分支演进

```
feat/agent-router         6 Agent + 多意图识别
    ↓
feat/session-memory       会话历史 + 压缩摘要
    ↓
refactor/langchain4j       LangChain4j 迁移 + 4 Agent + Redis
    ↓
feat/rag-ingest           RAG 文档入库管线
    ↓
feat/rag-retrieval        RAG 检索管线 + 语音纠错
    ↓
feat/tts-circuit-eval     TTS + CircuitBreaker + 知识库面板 + 评测
    ↓
feat/agent-identity       Agent 身份展示 (欢迎+徽章+标签)
    ↓
feat/async-pipeline ←     异步并行管线 (Vision+Intent) + Vision动作检测 + thinking字幕

当前活跃分支: feat/async-pipeline
```

---

*@SmoggyLong · 2026*

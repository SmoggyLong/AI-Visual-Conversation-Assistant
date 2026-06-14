# PR: TTS 语音 + 熔断器 + 知识库面板 + PDF + 评测框架 + 语音纠错

## 标语

**feat: TTS语音播报 · CircuitBreaker熔断 · 知识库管理面板 · PDF解析 · LLM评测框架 · STT语音纠错**

---

## 目录

1. [功能概述](#功能概述)
2. [TTS 语音输出](#1-tts-语音输出)
3. [Circuit Breaker 熔断器](#2-circuit-breaker-熔断器)
4. [前端知识库管理 + PDF 解析](#3-前端知识库管理--pdf-解析)
5. [STT 语音纠错](#4-stt-语音纠错)
6. [LLM 评测框架](#5-llm-评测框架)
7. [交互流程](#6-交互流程)
8. [评测报告解读](#7-评测报告解读)
9. [变更文件清单](#8-变更文件清单)

---

## 功能概述

| # | 功能 | 解决的问题 | 涉及层面 |
|---|------|-----------|---------|
| 1 | **TTS 语音** | AI 只能打字不能说话 (US-04) | 后端 + 前端 |
| 2 | **Circuit Breaker** | API 连续故障时卡住用户 | 后端通用组件 |
| 3 | **知识库面板 + PDF** | 管理员无法可视化管理文档 | 前端 + 后端 |
| 4 | **STT 语音纠错** | "java" 被识别为"家娃" | 后端 LLM 纠错 |
| 5 | **LLM 评测框架** | 改完 prompt 全靠人试 | 全栈 |

---

## 1. TTS 语音输出

### 为什么做

US-04"AI 回复以语音播报出来"是整个项目最高优先级的未完成用户故事。用户不盯着屏幕时就无法得知 AI 说了什么。

### 数据流

```
Agent 回复文本
    │
    ▼ EpisodeConsumer.respond()
    ├─ 先推文字 → callback.onResponse() → RESPONSE_TEXT → 前端显示
    │                                        (不阻塞)
    └─ 后合成语音 → ttsService.synthesize()
                    │ Zhipu TTS API (tts-1)
                    ▼ base64 MP3
                  callback.onAudio() → RESPONSE_AUDIO → 前端 Audio.play()
```

### 关键设计决策

| 决策 | 原因 |
|------|------|
| **非阻塞** | TTS 合成在推文字之后执行，失败不影响文字显示 |
| **截断 200 字** | 避免 TTS 过长（20字/秒，200字≈10秒） |
| **静默失败** | TTS API 不可用时只记日志，不给用户错误提示 |
| **前端自动播放** | 收到 RESPONSE_AUDIO 后创建 Audio 元素直接播放 |

### 涉及文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `api/tts/TtsService.java` | **新建** | TTS 接口 |
| `api/tts/ZhipuTtsService.java` | **新建** | 智谱 TTS 实现 (tts-1, voice=xiaoling) |
| `config/RAGConfig.java` | 修改 | + TtsService Bean |
| `service/EpisodeConsumer.java` | 修改 | respond() 末尾调 TTS |
| `handler/ConversationWSHandler.java` | 修改 | + onAudio 回调 → RESPONSE_AUDIO |
| `client/App.tsx` | 修改 | + audioRef + 自动播放 |

---

## 2. Circuit Breaker 熔断器

### 为什么做

LLM API 或 Vision API 故障时，当前重试机制会等待 30 秒超时，多个事件排队导致用户数分钟无响应。熔断器在连续失败后直接跳过调用，用户至少能立刻看到 fallback 回复。

### 三态流转

```
CLOSED          正常通行，记录成败
  │
  │ 连续 failures ≥ failureThreshold
  ▼
OPEN            拒绝请求，直接返回 fallback
  │              
  │ recoveryTimeout 过后
  ▼
HALF_OPEN       允许 1 次探测请求
  │ 
  ├─ 成功 → CLOSED (恢复)
  └─ 失败 → OPEN (重新熔断)
```

### 参数配置

```
visionBreaker:  3 次失败 → 熔断 → 60s 后探测
agentBreaker:   5 次失败 → 熔断 → 30s 后探测
```

### 接入位置

```java
// EpisodeConsumer.handleVision()
if (!visionBreaker.allowRequest()) return;  // 熔断中，跳过
String desc = visionService.describeBatch(frames);
if (desc == null) visionBreaker.recordFailure();
else visionBreaker.recordSuccess();

// EpisodeConsumer.respond()
if (!agentBreaker.allowRequest()) {
    chatResp = new ChatResponse(agent.fallbackText(), "idle", "neutral");
} else {
    chatResp = agent.handle(agentCtx);
    if (chatResp.getText().contains(fallbackText()))
        agentBreaker.recordFailure();
    else agentBreaker.recordSuccess();
}
```

### 涉及文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `util/CircuitBreaker.java` | **新建** | 通用熔断器 (独立于业务，其他模块可复用) |
| `service/EpisodeConsumer.java` | 修改 | 注入 visionBreaker + agentBreaker |

---

## 3. 前端知识库管理 + PDF 解析

### 为什么做

知识库文档入库全凭命令行和 API，需要可视化面板。同时扩展文档格式支持。

### 知识库面板

右侧面板新增 Tab：`[对话] [知识库] [评测]`

```
[知识库] Tab:
  ├── 标题栏: 16 篇 / 18 块
  ├── [↑上传] 按钮 → <input type="file" accept=".md,.json,.txt,.pdf">
  ├── [↻重载] 按钮 → POST /api/knowledge/reload
  ├── [+添加] 按钮 → 弹窗表单 → POST /api/knowledge/add
  └── 文档列表: 每条 = 标题/类型(SOP绿标/通用灰标)/文件名/块数
```

### PDF 解析

```java
// PdfBox 3.0.3
PDDocument.load(file) → PDFTextStripper.getText() → 纯文本
→ TextCleaner (去 markdown/URL/噪声)
→ DocumentChunker (500字/块, 50字 overlap)
→ OllamaEmbeddingModel (nomic-embed-text, 768维)
→ InMemoryStore (实时 KNN) + MongoDB avca_knowledge (持久化)
```

### 格式支持矩阵

| 格式 | 支持 | 解析方式 |
|------|:---:|------|
| `.md` (front matter) | ✅ | title/type/keywords + body |
| `.md` (无 front matter) | ✅ | title=文件名, type=GENERAL |
| `.json` | ✅ | JSON 数组, 单文件多文档 |
| `.txt` | ✅ | 整文件 = 一篇, title=文件名 |
| `.pdf` | ✅ (新增) | PDFBox 提取文本 |
| `.html` | ❌ | 跳过 |
| `.csv` | ❌ | 跳过 |

### 涉及文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `client/components/KnowledgePanel.tsx` | **新建** | 知识库面板 (表单+上传+列表) |
| `client/hooks/useKnowledge.ts` | **新建** | fetchList/fetchStats/reload/addDoc/uploadFile |
| `client/types/messages.ts` | 修改 | + KnowledgeDoc/DocInput/KnowledgeStats 类型 |
| `client/App.tsx` | 修改 | + [知识库] Tab, + min-h-0 滚动修复 |
| `pom.xml` | 修改 | + spring-boot-starter-data-mongodb + PDFBox 3.0.3 |
| `rag/DocumentParser.java` | 修改 | + PDF 解析 (支持 4 种格式) |
| `controller/KnowledgeController.java` | 修改 | + POST /add + POST /upload |
| `rag/KnowledgeBase.java` | 修改 | rebuildKeywordIndex → public |

---

## 4. STT 语音纠错

### 为什么做

百度 STT 将 "Java" 误识别为"家娃"——同音词导致后续意图识别和知识库检索全部错误，用户体验极差。

### 纠正流程

```
STT onFinal("我想问家娃")
    │
    ▼ SpeechCorrector.correct()
    ├─ context = 最近对话摘要
    ├─ domainTerms = 知识库关键词 ["java","spring","退款","docker"...]
    ├─ glm-4-flash 纠正
    │  "家蛙 → Java"
    ▼ "我想问Java"
    │
    ▼ addTurn → IntentRecognizer → KnowledgeAgent → 正确检索
```

### 安全机制

```
短文本 (< 5 字)    → 不纠错 (避免过度修正)
字数差异 > 50%     → 不使用 (纠正结果不可信)
API 调用失败        → 直接用原文 (不阻塞)
```

### 涉及文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `rag/SpeechCorrector.java` | **新建** | LLM 纠错器 |
| `config/RAGConfig.java` | 修改 | + SpeechCorrector Bean |
| `rag/KnowledgeBase.java` | 修改 | + getDomainTerms() |
| `handler/ConversationWSHandler.java` | 修改 | onFinal 中插纠错层 |

---

## 5. LLM 评测框架

### 为什么做

Agent 的 system prompt 频繁改动，缺乏自动化回归测试。评测框架提供：

- **意图识别正确率** — 改 prompt 不会让 IntentRecognizer 误判
- **Agent 回答质量** — 4 维度量化评分
- **RAG 检索命中率** — 知识库检索是否命中预期文档
- **回归检测** — 对比基线发现质量退化
- **单例诊断** — 展开查看每条用例的 4 维分 + RAG 命中 + 检查项

### 架构

```
POST /api/eval/run
    → Evaluator.run(test_cases)        ← 串行, 10 例 ~50s
        │
        for each case:
        ├─ ① IntentRecognizer                      → 验证意图
        ├─ ② Agent.handle()                         → 生成回答
        ├─ ③ KnowledgeBase.searchHybrid()           → 检查 RAG 命中
        └─ ④ LLMJudge.judge(query, response, kctx)  → 4 维打分 (0.0-1.0)

前端: [评测] Tab → [运行评测] → 渲染报告 → [保存基线]
```

### 组件

#### 5.1 LLMJudge — 裁判

用 deepseek-chat 对每条 Agent 回复打 4 个维度的分：

| 维度 | 评判 | 0 分 | 1 分 |
|------|------|------|------|
| **relevance** | 是否回应用户话题 | 答非所问 | 自然回应 |
| **accuracy** | 信息正确性 | 胡说 | 与知识库一致 |
| **completeness** | 关键信息覆盖度 | 只说一半 | 步骤完整 |
| **helpfulness** | 可操作性 | 空洞无用 | 用户知道下一步 |

**关键设计**：失败兜底 0.5 分（不给 0，避免一次 JSON 解析失败拉低总分）; Vision 适配 — prompt 声明"自然口语不算跑题"。

#### 5.2 Evaluator — 跑批器

串行遍历测试用例，直接构造 AgentContext 调用 Agent（不走 WebSocket）：

```
1. 意图识别验证   — 临时 ConversationSession → recognizeIntent → 对比 expectedIntent
2. Agent 回答生成  — AgentContext 构造 → AgentRouter.route → Agent.handle → ChatResponse
3. RAG 检索验证    — searchHybrid → 检查命中文件名 → 记录 retrievedSource
4. LLM 裁判打分    — judge(query, response, knowledgeContext)
5. 断言汇总       — expectedKeywords 命中 + expectedSource 命中 + intent 匹配
```

#### 5.3 测试用例

```json
{
  "id": "TC-KNOWLEDGE-01",
  "agent": "knowledge",           // 用哪个 Agent (null = 只测意图)
  "query": "怎么退款",             // 用户输入
  "expectedIntent": "knowledge",  // 期望意图
  "context": null,                // 画面上下文 (Vision 用例用)
  "expectedKeywords": ["退款"],    // 回答应含的关键词
  "expectedSource": "sop-refund.md" // RAG 应命中的文档 (null = 不检查)
}
```

**10 个覆盖**：Knowledge 3 + Vision 2 + Conversation 2 + Game 1 + Intent 2

#### 5.4 前端面板

```
[评测] Tab:
  ├── 标题栏: 基线分 + [保存基线] + [运行评测]
  ├── 通过率汇总: 8/10 (80%) · 49s
  └── byAgent 可展开折叠:
        ▶ knowledge · 2/3 · 综合 0.89
            ├── TC-01 "怎么退款"  R0.97 A0.93 C0.80 H0.87
            │   ✅ intent=knowledge  ✅ source:sop-refund.md  ✅ contains:退款
            │   📄 sop-refund.md
            ├── TC-02 "NullPointerException怎么修"  R0.85 A0.92 C0.75 H0.82
            │   ✅ intent=knowledge  ❌ contains:null
            │   📄 tech-springboot.md, dev-tools.json
            └── TC-03 "Docker镜像怎么清理"  R0.88 A0.90 C0.82 H0.85
                ✅ intent=knowledge  ❌ contains:docker
                📄 dev-tools.json

        ▶ vision · 2/2 · 综合 0.82
            ├── TC-01 "你看到了什么"  R0.90 A0.95 C0.70 H0.85
            │   ✅ intent=vision  ✅ contains:耳机  ✅ contains:电脑
            └── TC-02 "我今天穿什么了"  R0.92 A0.85 C0.75 H0.80
                ✅ intent=vision  ✅ contains:蓝色  ❌ contains:衬衫

        综合平均: rele 0.90 | accu 0.92 | comp 0.79 | help 0.85
```

#### 5.5 保存基线

将当前综合分保存为 baseline.json。后续每次改 prompt 后重新评测，对比基线判断是否有退化。

### 涉及文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `rag/eval/LLMJudge.java` | **新建** | deepseek-chat 4 维裁判 |
| `rag/eval/Evaluator.java` | **新建** | 跑批 + 汇总 |
| `controller/EvalController.java` | **新建** | API: /run /baseline /report |
| `data/eval/test_cases.json` | **新建** | 10 个测试用例 |
| `client/components/EvalPanel.tsx` | **新建** | 评测面板 |
| `client/hooks/useEval.ts` | **新建** | runEval/saveBaseline/fetchReport |
| `client/types/messages.ts` | 修改 | + EvalReport/EvalCaseResult/JudgeScores 类型 |
| `client/App.tsx` | 修改 | + [评测] Tab |

---

## 6. 交互流程

### 6.1 语音对话全链路

```
用户说话 → VAD → PCM 采集 → WebSocket AUDIO_DATA
    │
    ▼ 服务端
Baidu STT 流式识别 → onInterim → 前端暂显
    │
    ▼ SPEECH_END
SpeechCorrector 纠错 ("家蛙 → Java")
    │
    ▼ addTurn → SPEECH_BATCH 入队
    │
    ▼ EpisodeConsumer 串行消费
    ├─ Vision 分析 (visionBreaker 保护, 只积累不回复)
    ├─ IntentRecognizer (DeepSeek)
    ├─ Agent 路由 (4 Agent)
    │   └─ KnowledgeAgent → RAG 检索 (Query Rewrite + KNN + BM25 + Rerank)
    ├─ 回填轮次 + 压缩摘要 + Redis 同步
    ├─ RESPONSE_TEXT → 前端字幕框 + 对话面板 (agentBreaker 保护)
    └─ TTS 合成 → RESPONSE_AUDIO → 前端自动播放 (失败不影响)
```

### 6.2 知识库管理流程

```
管理员操作:
  方式 1: 写 .md → 丢 data/knowledge/ → 等 30s 自动扫描 (@Scheduled)
  方式 2: 写 .md → curl POST /api/knowledge/reload
  方式 3: 前端 [知识库] → [↑] 上传本地文件 (.md/.json/.txt/.pdf)
  方式 4: 前端 [知识库] → [+] 填写表单 (标题/类型/关键词/内容)

检查流程:
  DocumentParser → TextCleaner → DocumentChunker → Ollama → MongoDB + InMemory
  文件修改时间 + fileModifiedAt 对比 → 增量更新
  写入保护 2s → 跳过可能正在写入的文件
```

### 6.3 评测流程

```
1. 前端 [评测] → [运行评测]
2. 后端串行遍历 10 个测试用例
3. 每例: 测意图 → 调 Agent → 查 RAG → LLM 打分 → 断言汇总
4. 报告返回 → 前端渲染 → 展开查看每条用例的 4 维分 + 检查项 + RAG 命中
5. 如需 → [保存基线] → 后续作为回归检测基准
```

### 6.4 异常降级链路

```
Agent LLM 连续 5 次失败 → breaker OPEN → 直接返回 fallbackText "请稍后再试"
                                                   30s 后自动探测
Vision API 连续 3 次失败 → breaker OPEN → 跳过画面分析 (60s 恢复)
TTS API 失败          → 静默跳过, 文字正常显示
评测 Judge JSON 解析失败 → 默认 0.5 分 (不给 0 分)
STT 纠错 API 失败       → 直接用原文, 不阻塞识别
```

---

## 7. 评测报告解读

### 指标速查表

| 指标 | 显示位置 | 高分 | 低分 | 含义 |
|------|---------|:---:|:---:|------|
| 通过率 | 汇总块 | 10/10 | < 5/10 | 断言检查通过的用例占比 |
| R(relevance) | 每例 R 列 | 0.9+ | < 0.3 | 回答是否回归主题 |
| A(accuracy) | 每例 A 列 | 0.9+ | < 0.5 | 信息是否正确 |
| C(completeness) | 每例 C 列 | 0.9+ | < 0.5 | 关键信息是否完整 |
| H(helpfulness) | 每例 H 列 | 0.9+ | < 0.3 | 是否可操作 |
| 综合 (overall) | Agent 折叠标题 | R+A+C+H/4 | |
| ✅ check | 用例内 | 通过 | ❌ 失败 |
| 📄 source | 用例内 | 实际 RAG 命中文档 | |
| 基线 | 标题栏 | 上次保存的分数 | 对比判断退化 |

### 示例解读

```
knowledge · 2/3 · 综合 0.89
  TC-01 "怎么退款"  R0.97 A0.93 C0.80 H0.87
  ✅ intent=knowledge   ← 意图识别正确
  ✅ source:sop-refund   ← RAG 命中正确文档
  ✅ contains:退款        ← 回答包含关键词
  📄 sop-refund.md       ← 实际检索到的文档

  TC-02 "NullPointerException怎么修"  R0.85 A0.92 C0.75 H0.82
  ✅ intent=knowledge
  ❌ contains:null        ← 回答没说"null"这个词 (但 LLM 可能说"空指针")
  📄 tech-springboot.md, dev-tools.json  ← 检索到的文档
```

---

## 8. 变更文件清单

### 新建文件 (14)

| 文件 | 类别 |
|------|------|
| `api/tts/TtsService.java` | TTS 接口 |
| `api/tts/ZhipuTtsService.java` | 智谱 TTS |
| `util/CircuitBreaker.java` | 熔断器 |
| `rag/eval/LLMJudge.java` | 评测裁判 |
| `rag/eval/Evaluator.java` | 评测跑批 |
| `rag/SpeechCorrector.java` | 语音纠错 |
| `client/components/KnowledgePanel.tsx` | 知识库面板 |
| `client/components/EvalPanel.tsx` | 评测面板 |
| `client/hooks/useKnowledge.ts` | 知识库 API |
| `client/hooks/useEval.ts` | 评测 API |
| `controller/EvalController.java` | 评测 API |
| `controller/KnowledgeController.java` | 知识库 API |
| `data/eval/test_cases.json` | 测试用例 |
| `data/eval/baseline.json` | 评测基线 |

### 修改文件 (9)

| 文件 | 改动内容 |
|------|---------|
| `pom.xml` | + MongoDB + PDFBox 3.0.3 |
| `config/RAGConfig.java` | + TtsService + SpeechCorrector Bean |
| `service/EpisodeConsumer.java` | + CircuitBreaker (vision + agent) + TTS + 中断队列清理 |
| `handler/ConversationWSHandler.java` | + TTS onAudio 回调 + STT 纠错 + 空文本过滤 |
| `rag/KnowledgeBase.java` | rebuildKeywordIndex→public + getDomainTerms + search/searchHybrid/缓存 |
| `rag/DocumentParser.java` | + PDF 解析 |
| `client/App.tsx` | Tab 切换 (对话/知识库/评测) + 音频播放 + 关键帧采样 |
| `client/types/messages.ts` | 知识库 + 评测类型扩展 |
| `client/components/ControlBar.tsx` | 下拉框 portalRef 修复 |

### 汇总

```
14 新建 + 9 修改 = 23 个文件
语言: Java 11 个, TypeScript 10 个, JSON 2 个
```

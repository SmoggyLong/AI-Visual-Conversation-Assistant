# PR: TTS 语音 + CircuitBreaker 熔断 + 知识库面板 + PDF + 评测框架

## 标题

**feat: TTS语音播报 + 熔断保护 + 前端知识库管理 + PDF解析 + LLM评测框架**

---

## 一、功能概述

本 PR 在 RAG 检索管线基础上，补齐 **5 项功能**：

| # | 功能 | 用户故事 | 状态 |
|---|------|----------|:--:|
| 1 | **TTS 语音输出** | US-04 | ✅ |
| 2 | **Circuit Breaker 熔断** | 异常保护 | ✅ |
| 3 | **前端知识库面板** | 管理员体验 | ✅ |
| 4 | **PDF 文档解析** | 格式扩展 | ✅ |
| 5 | **LLM 评测框架** | 质量保障 | ✅ |

---

## 二、TTS 语音输出

### 为什么做

当前 AI 回复只在字幕框和对话面板显示文字。US-04"AI 回复以语音播报出来"是整个项目中第二高的优先级缺口——用户不盯着屏幕时无法得知 AI 说了什么。

### 实现

```
Agent 回复文本 → ZhipuTtsService (tts-1, glm-4-flash)
    → base64 MP3 → RESPONSE_AUDIO 消息 → 前端 HTML5 Audio 播放
```

**关键设计**：

- **非阻塞**：TTS 合成在推文字之后执行，失败不影响文字显示
- **截断保护**：文本超过 200 字自动截断，避免 TTS 过长
- **静默失败**：TTS API 不可用时仅记录日志，不报错给用户
- **前端自动播放**：收到 RESPONSE_AUDIO 后创建 `Audio` 元素自动播放，用户无需点击

### 涉及文件

| 操作 | 文件 | 说明 |
|------|------|------|
| 新建 | `api/tts/TtsService.java` | TTS 接口 |
| 新建 | `api/tts/ZhipuTtsService.java` | 智谱 TTS 实现 |
| 修改 | `config/RAGConfig.java` | + TtsService Bean |
| 修改 | `service/EpisodeConsumer.java` | respond() 末尾调 TTS |
| 修改 | `handler/ConversationWSHandler.java` | + onAudio 回调 → RESPONSE_AUDIO |
| 修改 | `client/App.tsx` | + audioRef + 自动播放 |

---

## 三、Circuit Breaker 熔断

### 为什么做

LLM API 或 Vision API 故障时，重试机制会导致每次等待 30 秒超时，多个事件排队 → 用户长达数分钟无回复。熔断器在连续失败后直接跳过调用，用户至少能立刻看到 fallback 文字。

### 实现

```java
public class CircuitBreaker {
    // 三态: CLOSED → OPEN → HALF_OPEN → CLOSED

    CLOSED:   正常通过, 记录成败
    OPEN:     直接拒绝, 返回 fallback, 等待 recoveryTimeout
    HALF_OPEN: 允许 1 次探测请求, 成功→CLOSED, 失败→OPEN
}
```

### 参数配置

| 熔断器 | failureThreshold | recoveryTimeout | 保护对象 |
|--------|:---:|:---:|------|
| visionBreaker | 3 次 | 60s | ZhipuVisionService |
| agentBreaker | 5 次 | 30s | Agent.handle() (LLM 调用) |

### 接入位置

```
EpisodeConsumer
  ├── handleVision() → visionBreaker.allowRequest()? → Vision API
  └── respond()     → agentBreaker.allowRequest()?  → Agent.handle()
                                                  否 → agent.fallbackText()
```

### 涉及文件

| 操作 | 文件 | 说明 |
|------|------|------|
| 新建 | `util/CircuitBreaker.java` | 通用熔断器 (独立于业务, 可复用) |
| 修改 | `service/EpisodeConsumer.java` | 注入 visionBreaker + agentBreaker |

---

## 四、前端知识库管理 + PDF 解析

### 为什么做

知识库文档从入库到管理完全靠命令行和 API。管理员需要一个可视化面板来查看文档、添加文档、支持更多格式。

### 实现

#### 前端

右侧面板新增 Tab `[对话] [知识库] [评测]`：

```
[知识库] Tab:
  ├── 标题栏: 文档统计 + [↑上传] [↻重载] [+添加]
  ├── 文档列表: 标题/类型(SOP/通用)/文件名/块数
  ├── [+添加] 弹窗: 标题/类型/关键词/内容 → POST /api/knowledge/add
  └── [↑上传] 按钮: <input type="file"> → FormData → POST /api/knowledge/upload
```

#### 后端

```
POST /api/knowledge/add
  → 前端表单 JSON → 写入 data/knowledge/{title}.json
  → DocumentParser.parse → 清洗 → 分块 → embedding → MongoDB + InMemory

POST /api/knowledge/upload
  → MultipartFile → 保存到 data/knowledge/
  → DocumentParser.parse (根据扩展名 .md/.json/.txt/.pdf)
  → 入库
```

#### PDF 支持

```java
// PdfBox 3.0.3
PDDocument.load(file) → PDFTextStripper.getText() → 纯文本
→ TextCleaner → DocumentChunker → embedding → 入库
```

### 涉及文件

| 操作 | 文件 | 说明 |
|------|------|------|
| 新建 | `client/components/KnowledgePanel.tsx` | 知识库面板 |
| 新建 | `client/hooks/useKnowledge.ts` | API hooks |
| 修改 | `client/App.tsx` | + [知识库] Tab, + min-h-0 滚动修复 |
| 修改 | `client/types/messages.ts` | + KnowledgeDoc/DocInput 等类型 |
| 修改 | `pom.xml` | + spring-boot-starter-data-mongodb + PDFBox 3.0.3 |
| 修改 | `rag/DocumentParser.java` | + PDF 解析 (+4 格式: .md/.json/.txt/.pdf) |
| 修改 | `controller/KnowledgeController.java` | + POST /add + POST /upload |
| 修改 | `rag/KnowledgeBase.java` | rebuildKeywordIndex → public |

---

## 五、LLM 评测框架

### 为什么做

Agent 的 system prompt 频繁改动，缺少自动化的回归测试。评测框架提供：

- **意图识别正确率**：改完 prompt 不会让 IntentRecognizer 把 "怎么退款" 误判为 vision
- **Agent 回答质量**：4 维度量化评分 (相关性/准确性/完整性/实用性)
- **RAG 检索命中率**：知识库检索是否命中了正确的文档
- **回归检测**：对比基线发现质量退化

### 架构

```
POST /api/eval/run
    → Evaluator.run(test_cases)  [串行, 10 个案例 ~50s]
        → for each case:
            ├─ IntentRecognizer → 对比 expectedIntent
            ├─ AgentRouter → Agent.handle() → 生成回答
            ├─ KnowledgeBase.searchHybrid() → 检查 expectedSource 命中
            └─ LLMJudge.judge(query, response, knowledgeCtx) → 4 维打分
        → 汇总 EvalReport → 返回 JSON

前端 → [评测] Tab → [运行评测] → 渲染报告 → [保存基线]
```

### 组件

#### LLMJudge — LLM 裁判

```java
deepseek-chat 作为裁判, 对每一条 Agent 回复打 4 个维度的分:
    relevance     (相关性)  — 是否回应用户话题
    accuracy      (准确性)  — 信息是否正确
    completeness  (完整性)  — 是否覆盖关键信息
    helpfulness   (实用性)  — 用户能否据此行动

失败兜底: 每维默认 0.5 分 (不给 0, 避免一次 JSON 解析失败拉低总分)
Vision 适配: prompt 声明 "自然口语不算跑题"
```

#### Evaluator — 评测跑批器

```java
串行遍历 10 个测试用例:
  1. 意图识别验证: 创建临时 ConversationSession → recognizeIntent → 对比
  2. Agent 回答生成: 构造 AgentContext → route → handle → ChatResponse
  3. RAG 检索验证: searchHybrid → 检查命中文件名
  4. LLM 裁判打分: judge(query, response, knowledgeContext)
  5. 断言汇总: expectedKeywords 命中 + expectedSource 命中 + intent 匹配
```

#### EvalController — HTTP API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/eval/run` | POST | 加载 test_cases.json → 跑批 → 返回报告 |
| `/api/eval/baseline` | POST | 保存当前综合分为基线 |
| `/api/eval/report` | GET | 返回上一次评测报告 (缓存) |

#### 测试用例

```json
{
  "id": "TC-KNOWLEDGE-01",
  "agent": "knowledge",           // 用哪个Agent (null=只测意图)
  "query": "怎么退款",             // 用户输入
  "expectedIntent": "knowledge",  // 期望意图
  "context": null,                // 画面上下文
  "expectedKeywords": ["退款"],    // 回答应含的关键词
  "expectedSource": "sop-refund.md" // RAG 应命中的文档
}
```

#### 前端 EvalPanel

```
[评测] Tab:
  ├── 标题栏: 基线分 + [保存基线] + [运行评测]
  ├── 通过率汇总: 8/10 (80%) · 49s
  ├── byAgent 分组折叠:
  │     knowledge · 综合 0.89
  │       rele 0.97 | accu 0.93 | comp 0.80 | help 0.87
  │     vision · 综合 0.82
  │       ...
  └── 耗时
```

### 指标说明

| 指标 | 含义 | 高分 | 低分 |
|------|------|:---:|:---:|
| **通过率** | 断言检查通过比例 | 10/10 | 问题严重 |
| **relevance** | 是否回应话题 | 自然回应 | 答非所问 |
| **accuracy** | 信息正确性 | 与知识库一致 | 胡说 |
| **completeness** | 关键信息覆盖 | 步骤完整 | 只说一半 |
| **helpfulness** | 可操作性 | 用户知道下一步 | 空洞 |
| **基线** | 历史参照值 | — | 对比是否退化 |

### 涉及文件

| 操作 | 文件 | 说明 |
|------|------|------|
| 新建 | `rag/eval/LLMJudge.java` | deepseek-chat 4 维裁判 |
| 新建 | `rag/eval/Evaluator.java` | 跑批 + 汇总 |
| 新建 | `controller/EvalController.java` | API: run/baseline/report |
| 新建 | `data/eval/test_cases.json` | 10 个测试用例 |
| 新建 | `client/components/EvalPanel.tsx` | 评测面板 |
| 新建 | `client/hooks/useEval.ts` | API hooks |
| 修改 | `client/App.tsx` | + [评测] Tab |
| 修改 | `client/types/messages.ts` | + 评测类型 |

---

## 六、交互流程

### 语音对话全链路

```
用户说话 → VAD → STT → SpeechCorrector 纠错 → addTurn
    → SPEECH_BATCH → EpisodeConsumer 串行消费
        ├─ Vision 分析 (visionBreaker 保护)
        ├─ IntentRecognizer (DeepSeek)
        ├─ AgentRouter → Agent.handle (agentBreaker 保护)
        │     └─ KnowledgeAgent → RAG 检索
        ├─ RESPONSE_TEXT → 前端显示文字 + 字幕框
        ├─ TtsService.synthesize → RESPONSE_AUDIO → 前端播放语音
        └─ 历史压缩 + Redis 同步
```

### 知识库管理流程

```
管理员:
  方式1: 写 .md → 丢 data/knowledge/ → 等 30s 自动扫描
  方式2: 写 .md → curl POST /api/knowledge/reload
  方式3: 前端 [知识库] → [↑上传] → 选择本地文件
  方式4: 前端 [知识库] → [+添加] → 表单填写

前端 [知识库] Tab:
  查看文档列表 → 标题/类型/块数
  [保存基线] 按钮记录当前评测分数
```

### 评测流程

```
1. 前端 [评测] Tab → [运行评测]
2. 后端遍历 10 个测试用例
3. 对每个用例:
   a. 验证意图识别 (TestCase vs IntentRecognizer)
   b. 调 Agent 生成回答
   c. LLM Judge 4 维打分
   d. 断言检查 (关键词 + 来源)
4. 汇总报告 → 前端渲染
5. [保存基线] → 后续对比
```

### 异常降级链路

```
正常: Agent LLM → 回答
  │
连续 5 次失败? → agentBreaker OPEN → 直接返回 fallbackText()
  │                                     30s 后自动探测
  │
Vision 连续 3 次失败? → visionBreaker OPEN → 跳过画面分析
  │                                           60s 后自动恢复
  │
TTS API 失败? → 静默跳过, 不影响文字回复
  │
评测 LLM Judge 解析失败? → 默认 0.5 分
```

---

## 七、变更文件总览

| 操作 | 文件 | 功能 |
|------|------|------|
| 新建 | `api/tts/TtsService.java` | TTS 接口 |
| 新建 | `api/tts/ZhipuTtsService.java` | 智谱 TTS |
| 新建 | `util/CircuitBreaker.java` | 熔断器 |
| 新建 | `rag/eval/LLMJudge.java` | 评测裁判 |
| 新建 | `rag/eval/Evaluator.java` | 评测跑批 |
| 新建 | `controller/EvalController.java` | 评测 API |
| 新建 | `data/eval/test_cases.json` | 测试用例 |
| 新建 | `client/components/KnowledgePanel.tsx` | 知识库面板 |
| 新建 | `client/components/EvalPanel.tsx` | 评测面板 |
| 新建 | `client/hooks/useKnowledge.ts` | 知识库 hooks |
| 新建 | `client/hooks/useEval.ts` | 评测 hooks |
| 修改 | `pom.xml` | + MongoDB + PDFBox |
| 修改 | `service/EpisodeConsumer.java` | + breaker + TTS |
| 修改 | `handler/ConversationWSHandler.java` | + TTS 回调 |
| 修改 | `config/RAGConfig.java` | + TtsService Bean |
| 修改 | `rag/DocumentParser.java` | + PDF 解析 |
| 修改 | `rag/KnowledgeBase.java` | KeywordIndex public |
| 修改 | `controller/KnowledgeController.java` | + /add + /upload |
| 修改 | `client/App.tsx` | Tab 切换 + 音频播放 |
| 修改 | `client/types/messages.ts` | 类型扩展 |

**合计: 10 新建 + 8 修改**

# PR: TTS 语音 + CircuitBreaker 熔断 + 前端知识库面板 + PDF + 评测框架

## 标题

**feat: TTS语音 + CircuitBreaker熔断 + 知识库面板 + PDF + LLM评测框架**

---

## 功能描述

### 1. TTS 语音输出 (US-04)

AI 回复不仅显示文字，还能通过语音播报出来。

```
Agent 回复文本 → ZhipuTtsService (tts-1) → base64 MP3 → RESPONSE_AUDIO → 前端播放
```

- 智谱 TTS API `tts-1` 模型
- 截断 200 字以内
- TTS 失败不影响主流程（非阻塞）

### 2. Circuit Breaker 熔断器

保护 API 调用不被重复失败打爆。

| 熔断器 | 阈值 | 恢复时间 |
|--------|:---:|:---:|
| Vision Breaker | 3次失败 | 60s |
| Agent Breaker | 5次失败 | 30s |

三态流转: `CLOSED → OPEN → HALF_OPEN → CLOSED`

### 3. 前端知识库管理面板

右侧面板 Tab 切换 `[对话] [知识库]`：

- 文档列表（标题/类型/文件名/块数）
- `[↑]` 从本地文件导入 (.md/.json/.txt/.pdf)
- `[↻]` 从磁盘重新加载
- `[+ 添加]` 弹窗填写表单入库

### 4. PDF 文档支持

```java
// PDFBox 提取文本
PDDocument.load(file) → PDFTextStripper.getText() → 纯文本 → 分词块 → 入库
```

---

## 实现思路

### Circuit Breaker

```java
public class CircuitBreaker {
    CLOSED  → 允许请求, 记录成败
    OPEN    → 拒绝请求, wait 60s
    HALF_OPEN → 允许1次探测, 成功→CLOSED, 失败→OPEN
}
```

接入两个位置：
- `EpisodeConsumer.handleVision()` → Vision API 调用前
- `EpisodeConsumer.respond()` → Agent LLM 调用前

### TTS 集成

```java
// EpisodeConsumer.respond()
callback.onResponse(chatResp);  // 先发文字

// 异步合成语音
String audio = ttsService.synthesize(text);
callback.onAudio(audio);  // 发语音

// ConversationWSHandler
onAudio(base64Mp3) → RESPONSE_AUDIO → 前端 Audio.play()
```

### 前端知识库面板

```
useKnowledge() → fetch/list/reload/addDoc/uploadFile
    │
KnowledgePanel.tsx
    ├── 文档列表 (useKnowledge.docs)
    ├── [+ 添加] 弹窗 (addDoc)
    ├── [↑] 本地文件 (uploadFile → FormData)
    └── [↻] 重新加载 (reload)
```

---

## 测试方式

### TTS
```
用户: "你好"
→ 前端显示文字 "你好呀！😊"
→ 同时播放语音读出这句话
```

### 熔断
```
连续多次问"你好" → 正常回复
模拟 LLM API 挂了 → 连续 5 次失败 → 直接返回 fallback 文字（不再请求LLM）
30s 后自动恢复
```

### 知识库面板
```
浏览器右侧 → [知识库] Tab
→ 查看文档列表
→ 点 [+ 添加] → 填写表单 → 入库
→ 点 ↑ → 选择本地 .md 文件 → 自动上传入库
```

---

## 变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| **新建** | `util/CircuitBreaker.java` | 通用熔断器 |
| **新建** | `api/tts/TtsService.java` | TTS 接口 |
| **新建** | `api/tts/ZhipuTtsService.java` | 智谱 TTS 实现 |
| **新建** | `client/src/components/KnowledgePanel.tsx` | 知识库面板 |
| **新建** | `client/src/hooks/useKnowledge.ts` | 知识库 API hooks |
| 修改 | `pom.xml` | + spring-boot-starter-data-mongodb + PDFBox |
| 修改 | `client/src/App.tsx` | Tab切换 + 音频播放 |
| 修改 | `client/src/types/messages.ts` | + 知识库类型 |
| 修改 | `service/EpisodeConsumer.java` | + CircuitBreaker + TTS |
| 修改 | `handler/ConversationWSHandler.java` | + TtsService + onAudio 回调 |
| 修改 | `config/RAGConfig.java` | + TtsService Bean |
| 修改 | `rag/KnowledgeBase.java` | rebuildKeywordIndex→public |
| 修改 | `rag/DocumentParser.java` | + PDF 解析 |
| 修改 | `controller/KnowledgeController.java` | + /add + /upload |

---

## 评测框架

### 架构

```
POST /api/eval/run
    → Evaluator.run(test_cases)
        → for each case:
            ├─ IntentRecognizer.recognizeIntent() → 验证意图
            ├─ AgentRouter.route() → Agent.handle() → 生成回答
            ├─ KnowledgeBase.searchHybrid() → 检查检索命中
            └─ LLMJudge.judge(query, response, context) → 4维打分
        → 汇总 EvalReport → 返回 JSON

前端 → [评测] Tab → [运行评测] → 渲染报告 → [保存基线]
```

### 实现思路

#### 1. LLMJudge — LLM 裁判

```java
@Component
public class LLMJudge {
    // 注入 deepseek-chat
    // judge(query, response, knowledgeContext) → JudgeScores

    private static final String SYSTEM_PROMPT = "你是回答质量评估专家...";

    // 核心逻辑:
    // 1. 将 query + response + knowledgeContext 拼成 prompt
    // 2. deepseek-chat 返回 JSON: {"relevance":0.9,"accuracy":0.85,...}
    // 3. Jackson 解析 → JudgeScores(relevance, accuracy, completeness, helpfulness)
    // 4. 解析失败 → 默认 0.5 分 (不给 0, 避免误杀)

    public JudgeScores judge(String query, String response, String kctx) {
        var resp = model.generate(SystemMessage.from(...), UserMessage.from(prompt));
        String json = extractJson(resp.content().text());  // { 到 } 提取
        var node = mapper.readTree(json);
        return new JudgeScores(
            node.has("relevance") ? node.get("relevance").asDouble(0.5) : 0.5,
            ...
        );
    }
}
```

**关键设计决策**:
- 失败默认 0.5 而非 0: LLM Judge 偶发 JSON 解析失败时, 不因一次异常拉低整体分数
- Vision 对话适配: prompt 中声明 "自然口语不算跑题"
- 截断保护: response > 500 字截断, 避免 token 超限

#### 2. Evaluator — 评测跑批器

```java
@Component
public class Evaluator {
    // 注入 AgentRouter, Orchestrator, KnowledgeBase, LLMJudge

    public EvalReport run(List<EvalCase> cases) {
        // 串行遍历 (避免并发干扰 LLM 调用), 逐个 evalOne
        // 汇总: 通过率、分组(by agent)、耗时
    }

    private EvalResult evalOne(EvalCase tc) {
        // 1. 意图识别验证
        //    new ConversationSession → addTurn → recognizeIntent
        //    对比 actual intent vs expectedIntent

        // 2. Agent 回答生成
        //    构造 AgentContext → AgentRouter.route → Agent.handle → ChatResponse

        // 3. RAG 检索验证 (如 expectedSource 非空)
        //    KnowledgeBase.searchHybrid → 检查命中文件名

        // 4. LLM 裁判打分
        //    LLMJudge.judge(query, response, knowledgeContext)

        // 5. 断言检查
        //    expectedKeywords → response.contains(kw)
        //    expectedSource → knowledgeContext.contains(source)
        //    全部通过 → allChecksPassed = true
    }
}
```

**关键设计决策**:
- **串行而非并行**: 10 个案例逐个执行, 避免多个 LLM 调用互相干扰 (49 秒可接受)
- **实时调 Agent**: 不走 WebSocket, 直接构造 AgentContext 调用 Agent.handle()
- **分离测意图 vs 测回答**: `agent=null` 的用例只测 IntentRecognizer, 不调 Agent

#### 3. EvalController — HTTP API

```java
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    @PostMapping("/run")    // 加载 test_cases.json → Evaluator.run → 返回报告
    @PostMapping("/baseline") // 保存当前分数为基线 data/eval/baseline.json
    @GetMapping("/report")   // 返回上一次评测报告 (缓存)
}
```

#### 4. Test Cases 结构

```json
{
  "id": "TC-KNOWLEDGE-01",
  "agent": "knowledge",           // 用哪个 Agent (null=只测意图)
  "query": "怎么退款",             // 用户问题
  "expectedIntent": "knowledge",  // 期望的意图
  "context": null,                // 画面上下文 (Vision 用例用)
  "expectedKeywords": ["退款"],    // 回复中应包含的关键词
  "expectedSource": "sop-refund.md" // RAG 检索应命中的文档
}
```

#### 5. 前端 EvalPanel

```
useEval() → runEval/saveBaseline/fetchReport
    │
EvalPanel.tsx
    ├── 标题栏: 评测 + 基线分 + 保存基线 + 运行按钮
    ├── 通过率 汇总块
    ├── byAgent → <details> 折叠
    │     knowledge · 综合 0.89
    │       rele 0.97 | accu 0.93 | comp 0.80 | help 0.87
    │     vision · 综合 0.82
    │       ...
    └── 耗时
```

### LLM 裁判 4 维打分

| 维度 | 说明 | 0分 vs 1分 |
|------|------|-----------|
| **relevance** | 是否回应用户话题 | 答非所问 vs 自然回应 |
| **accuracy** | 信息正确性 | 胡说 vs 正确（与知识库一致） |
| **completeness** | 信息覆盖度 | 只说一半 vs 完整步骤 |
| **helpfulness** | 可操作性 | 空洞 vs 用户知道下一步 |

> 注意: VisionAgent 的自然口语风格不算跑题——Judge prompt 已适配。

### 测试用例 (10个)

| 类别 | 数量 | 验证项 |
|------|:---:|------|
| KnowledgeAgent | 3 | 意图+关键词+知识库来源 |
| VisionAgent | 2 | 意图+画面描述关键词 |
| ConversationAgent | 2 | 意图+自然回复 |
| GameAgent | 1 | 意图+游戏规则关键词 |
| 意图识别 | 2 | 仅意图，无 Agent 回复 |

### 报告解读

```
通过率 8/10 = 80%
  └── 8个用例检查项全部通过, 2个有关键词检查未命中

知识 knowledge · 综合 0.89
  relevance 0.97    ← 回答问题很准确
  accuracy  0.93    ← 知识库信息正确
  completeness 0.80 ← 有些步骤没说完
  helpfulness 0.87  ← 用户可以据此操作

视觉 vision · 综合 0.82
  relevance x.xx    ← 自然口语回应，不跑题
  accuracy  x.xx    ← 画面描述与上下文一致

对话 conversation · 综合 0.68
  ← 开放性问题（"今天天气怎么样"），无法验证关键词通过

游戏 game · 综合 1.0
  ← 成语接龙规则明确，回答格式匹配度高
```

### 保存基线

将当前评测结果保存为参照值。后续每次改 prompt 后重新评测，对比基线判断是否有退化。

---

## 变更文件 (评测框架)

| 操作 | 文件 | 说明 |
|------|------|------|
| **新建** | `rag/eval/LLMJudge.java` | deepseek-chat 4维裁判 |
| **新建** | `rag/eval/Evaluator.java` | 跑批 + 汇总 |
| **新建** | `controller/EvalController.java` | API: run/baseline/report |
| **新建** | `data/eval/test_cases.json` | 10个测试用例 |
| **新建** | `client/src/components/EvalPanel.tsx` | 评测面板 |
| **新建** | `client/src/hooks/useEval.ts` | 评测 API hooks |
| 修改 | `client/src/App.tsx` | + [评测] Tab |
| 修改 | `client/src/types/messages.ts` | + 评测类型 |

# PR: TTS 语音输出 + Circuit Breaker 熔断 + 前端知识库面板 + PDF 支持

## 标题

**feat: TTS语音 + CircuitBreaker熔断 + 前端知识库面板 + PDF文档支持**

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

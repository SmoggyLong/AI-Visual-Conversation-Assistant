# PR: LangChain4j 迁移 + Agent 重构 + Redis 会话存储

## 标题

**refactor: LangChain4j 0.36.2 引入 + Agent 6→4 精简 + Redis 会话持久化**

---

## 功能描述

本 PR 完成三大重构：

### 1. LangChain4j 0.36.2 引入

将手写 HTTP 调用的 `ZhipuChatService` 替换为 LangChain4j `ChatLanguageModel`，统一 LLM 调用层：

- **删除** `ZhipuChatService.java`（~200行手写 HTTP + JSON 解析 + 重试）
- **新建** `config/LangChain4jConfig.java` — 3 个 `ChatLanguageModel` Spring Bean：
  - `deepseekModel` (deepseek-chat)
  - `zhipuFlashModel` (glm-4-flash)
  - `zhipu7Model` (glm-4.7)
- **新建** `util/ResponseParser.java` — JSON 解析逻辑独立（含 markdown 剥离、前置文本容错）
- `IntentRecognizer` 从裸 HTTP 改为 `ChatLanguageModel.generate()`
- `Orchestrator` / `AgentRouter` 改为 Spring `@Component`，构造器注入

DeepSeek 和智谱均走 OpenAI 兼容协议，无需额外 `langchain4j-zhipu` 模块。

### 2. Agent 重构（6 → 4）

| 旧 Agent | 新 Agent | 说明 |
|----------|----------|------|
| GreetingAgent + GeneralAgent | **ConversationAgent** | 合并闲聊/招呼，emoji 风格 |
| TechnicalAgent | **KnowledgeAgent** | 知识技术问答，预留 RAG 接入 |
| EmergencyAgent | — | 删除（视觉对话场景无实际用途） |
| VisionAgent | VisionAgent | 保留 |
| GameAgent | GameAgent | 保留 |

`IntentType` 枚举同步精简：`VISION \| KNOWLEDGE \| CONVERSATION \| GAME`，旧值向后兼容自动映射。

### 3. Redis 会话存储（参考 EchoMind）

参考 EchoMind 的 `wm:{user}:{conv}` / `summary:{user}:{conv}` 设计，将会话数据双写到 Redis（TTL 30min）：

| Redis Key | 类型 | 内容 |
|-----------|------|------|
| `avca:hist:{sessionId}` | List | ConversationTurn JSON（右追加） |
| `avca:sum:{sessionId}` | String | 累积压缩摘要 |
| `avca:idiom:{sessionId}` | List | 已用成语 |

Redis 不可用时自动降级为纯内存模式。

---

## 实现思路

### LangChain4j 迁移

```
旧架构:
  Agent → ZhipuChatService.chat(model, prompt, msg, temp, maxTokens)
         → HttpUtil.postJsonWithAuth()
         → 手写重试 + fallback

新架构:
  Agent → ChatLanguageModel.generate(SystemMessage, UserMessage)
         → LangChain4j 内置重试 + 超时
         → ResponseParser.parse(content) → ChatResponse
```

### Agent 模型分配

```java
@Configuration
public class LangChain4jConfig {
    @Bean deepseekModel    // deepseek-chat — VisionAgent, KnowledgeAgent, GameAgent
    @Bean zhipuFlashModel  // glm-4-flash — ConversationAgent
    @Bean zhipu7Model      // glm-4.7    — 高紧急度降级
}
```

### Redis 同步点

```
addTurn() / updateLastAssistantText() / updateLastAgentType()
  → syncHistoryToRedis()   // delete + rightPush × N + expire

appendSummary()
  → syncSummaryToRedis()   // SET key value EX 1800

addUsedIdiom()
  → syncIdiomToRedis()     // RPUSH + expire
```

---

## 测试方式

### 启动

```bash
# Redis（可选，不启动则降级为纯内存）
docker run -d --name redis -p 6379:6379 redis:7-alpine

# 服务端
cd server && mvn spring-boot:run

# 前端
cd client && npm run dev
```

### 验证项

| 测试项 | 预期 |
|--------|------|
| 服务启动 | LangChain4jConfig Bean 加载，无 `ZhipuChatService` 引用 |
| 前端对话 | 4 Agent 正常路由，回复显示在字幕框 + 对话面板 |
| 成语接龙 | GameAgent 不列选项，直接出成语，`avca:idiom:*` 有记录 |
| Redis 验证 | `redis-cli KEYS avca:*` 能看到 hist/sum/idiom key |
| Redis 降级 | 关闭 Redis 后服务正常运行，日志无异常 |

### 编译

```bash
cd server && mvn compile   # BUILD SUCCESS
cd client && npx tsc --noEmit  # ✅
```

---

## 变更文件

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `pom.xml` | + langchain4j (BOM + core + open-ai) + spring-boot-starter-data-redis |
| 修改 | `application.yml` | + spring.data.redis 配置 |
| **新建** | `config/LangChain4jConfig.java` | 3 ChatLanguageModel Bean |
| **新建** | `config/RedisConfig.java` | RedisTemplate Bean (Jackson 序列化) |
| **新建** | `util/ResponseParser.java` | JSON 解析器（从 ZhipuChatService 迁出） |
| **新建** | `agent/ConversationAgent.java` | 合 Greeting + General，emoji 风格 |
| 重命名 | `agent/TechnicalAgent.java` → `agent/KnowledgeAgent.java` | 技术→知识，预留 RAG |
| 修改 | `agent/Agent.java` | getChatService→getChatModel，+ getSummarizeModel |
| 修改 | `agent/AgentRouter.java` | Spring @Component，注入 ChatLanguageModel，4 Agent 映射 |
| 修改 | `agent/VisionAgent.java` | 适配 ChatLanguageModel |
| 修改 | `agent/GameAgent.java` | 适配 ChatLanguageModel |
| 修改 | `api/llm/router/IntentRecognizer.java` | HTTP 手写 → ChatLanguageModel.generate() |
| 修改 | `handler/Orchestrator.java` | Spring @Component，移除手写 API key |
| 修改 | `handler/ConversationWSHandler.java` | 移除 loadDotenv()，Spring @Value 注入，空文本过滤 |
| 修改 | `model/session/ConversationSession.java` | Redis 双写，syncHistoryToRedis/syncSummaryToRedis/syncIdiomToRedis |
| 修改 | `service/SessionManager.java` | 注入 RedisTemplate，创建时传给 session |
| 修改 | `service/EpisodeConsumer.java` | 适配新 Agent 接口 |
| 修改 | `util/HistoryFormatter.java` | summarize() 使用 ChatLanguageModel |
| 修改 | `constant/IntentType.java` | 6→4 枚举，向后兼容映射 |
| **删除** | `api/llm/ZhipuChatService.java` | 完全替代 |
| **删除** | `agent/EmergencyAgent.java` | 无实际用途 |
| **删除** | `agent/GreetingAgent.java` | 合入 ConversationAgent |
| **删除** | `agent/GeneralAgent.java` | 合入 ConversationAgent |

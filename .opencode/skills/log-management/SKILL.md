# AI 视觉对话助手 — 日志规范

## 适用范围

本规范适用于 `server/`（Java/Spring Boot）所有代码的日志输出。`client/` 端在浏览器环境中按需采纳。

---

## 一、日志框架

| 端 | 框架 | 获取方式 |
|----|------|----------|
| **server/** | SLF4J + Logback | `@Slf4j` 注解 或 `LoggerFactory.getLogger()` |
| **client/** | `console` | 浏览器原生 console（生产环境关闭） |

---

## 二、日志级别决策树

```
ERROR   — 操作失败且需要人工介入。
          示例：WebSocket 连接异常断开且重连耗尽、AI API 认证失败

WARN    — 异常发生但系统可降级或恢复。
          示例：帧差检测跳过（客户端帧间隔过短）、空闲会话被回收

INFO    — 关键业务事件，证明系统正常工作。
          示例：会话创建/销毁、摄像头/麦克风开关、AI 调用开始/结束

DEBUG   — 开发调试信息，生产环境通常关闭。
          示例：消息载荷内容、音频数据大小、帧尺寸
```

## 三、日志格式规范

### 3.1 统一前缀标签

每条日志必须使用 `[标签]` 前缀，便于 grep 检索：

| 标签 | 含义 | 使用场景 |
|------|------|----------|
| `[CONNECT]` | 连接事件 | WebSocket 建立/关闭 |
| `[SESSION]` | 会话事件 | 创建/销毁/回收 |
| `[DEVICE]` | 设备事件 | 摄像头/麦克风 开关 |
| `[FRAME]` | 帧数据 | 视频帧接收/处理 |
| `[AUDIO]` | 音频数据 | 音频块接收/处理 |
| `[AI_CALL]` | AI 调用 | Vision/STT/LLM/TTS API 调用 |
| `[AI_RESP]` | AI 响应 | AI 返回结果 |
| `[ERROR]` | 错误 | 异常信息 |

### 3.2 模板

```java
// ✅ 正确示例
log.info("[CONNECT] WebSocket 连接建立 | wsId={} | 当前连接数={}", wsId, count);
log.info("[SESSION] 会话已创建 | sessionId={}", sessionId);
log.info("[DEVICE] 摄像头已开启 | sessionId={} | deviceId={}", sessionId, deviceId);
log.debug("[FRAME] 收到视频帧 | sessionId={} | size={}x{} | checksum={}", sessionId, w, h, cs);
log.warn("[FRAME] 帧差无变化，跳过分析 | sessionId={}", sessionId);
log.error("[ERROR] 消息处理异常 | wsId={} | error={}", wsId, e.getMessage(), e);

// ✅ AI 调用日志 —— 记录入参、耗时、结果
log.info("[AI_CALL] Vision 调用开始 | sessionId={} | imageSize={}", sessionId, imageSize);
// ... 调用 ...
log.info("[AI_RESP] Vision 调用完成 | sessionId={} | costMs={} | descLength={}", sessionId, ms, len);
```

```java
// ❌ 错误示例
log.info("连接建立: " + wsId);                         // 无标签，字符串拼接
log.error(e.getMessage());                              // 无上下文，无异常对象
log.info("Entering handleFrameData");                   // 无业务价值
System.out.println("收到消息");                          // 绕过日志框架
e.printStackTrace();                                    // 绕过日志框架
```

---

## 四、关键日志埋点清单

以下位置**必须**有日志：

### 4.1 ConversationWebSocketHandler

| 位置 | 级别 | 内容 |
|------|------|------|
| 连接建立 | `INFO` | `[CONNECT] wsId + 当前连接数` |
| 连接关闭 | `INFO` | `[CONNECT] wsId + 关闭原因 + 剩余连接数` |
| 传输异常 | `ERROR` | `[ERROR] wsId + 异常堆栈` |
| 会话初始化完成 | `INFO` | `[SESSION] sessionId + UA` |
| 设备状态变更 | `INFO` | `[DEVICE] 摄像头/麦克风 开/关 + sessionId` |
| 收到视频帧 | `DEBUG` | `[FRAME] 尺寸 + checksum + sessionId` |
| 帧差无变化 | `DEBUG` | `[FRAME] 跳过 + sessionId` |
| 收到音频数据 | `DEBUG` | `[AUDIO] 时长 + 采样率 + sessionId` |
| 消息解析失败 | `WARN` | 原始 payload（截断后） |
| 消息处理异常 | `ERROR` | `[ERROR] wsId + 完整异常` |

### 4.2 SessionManager

| 位置 | 级别 | 内容 |
|------|------|------|
| 会话创建 | `INFO` | `[SESSION] sessionId` |
| 会话销毁 | `INFO` | `[SESSION] sessionId` |
| 空闲会话批量清理 | `INFO` | `[SESSION] 清理数量` |

### 4.3 后续 AI 服务调用（预留）

| 位置 | 级别 | 内容 |
|------|------|------|
| Vision API 调用 | `INFO` | `[AI_CALL] Vision | sessionId + 图片大小` |
| Vision API 返回 | `INFO` | `[AI_RESP] Vision | sessionId + 耗时 + 描述长度` |
| STT API 调用 | `INFO` | `[AI_CALL] STT | sessionId + 音频时长` |
| STT API 返回 | `INFO` | `[AI_RESP] STT | sessionId + 耗时 + 文本长度` |
| LLM API 调用 | `INFO` | `[AI_CALL] LLM | sessionId + prompt 长度` |
| LLM API 返回 | `INFO` | `[AI_RESP] LLM | sessionId + 耗时 + token 数` |
| TTS API 调用 | `INFO` | `[AI_CALL] TTS | sessionId + 文本长度` |
| TTS API 返回 | `INFO` | `[AI_RESP] TTS | sessionId + 耗时 + 音频时长` |
| API 调用失败 | `ERROR` | `[ERROR] API名称 | sessionId + 重试次数 + 异常` |
| API 限流 | `WARN` | `[AI_CALL] 限流 | API名称 + 等待秒数` |

---

## 五、生产环境配置

### application.yml（生产 profile）

```yaml
logging:
  level:
    com.aivca: INFO              # 业务日志 INFO
    com.aivca.handler: INFO      # WebSocket 事件 INFO
    com.aivca.service: INFO      # 服务层 INFO
    org.springframework: WARN     # 框架日志 WARN
  pattern:
    console: '{"ts":"%d{ISO8601}","level":"%level","logger":"%logger{36}","thread":"%thread","msg":"%msg"}%n'
```

### application.yml（开发 profile）

```yaml
logging:
  level:
    com.aivca: DEBUG
    com.aivca.handler: DEBUG
  pattern:
    console: "%d{HH:mm:ss.SSS} %-5level [%thread] %logger{20} - %msg%n"
```

---

## 六、检查清单

提交代码前确认：

- [ ] 每条日志有 `[标签]` 前缀
- [ ] 使用 `{}` 占位符，不用字符串拼接
- [ ] `log.error()` 的最后一个参数是异常对象 `e`
- [ ] 没有 `System.out.println` 或 `e.printStackTrace()`
- [ ] 没有在循环内打 INFO 级别日志
- [ ] 敏感信息（token、key）没有被打印
- [ ] AI 调用有开始+结束成对日志（含耗时）

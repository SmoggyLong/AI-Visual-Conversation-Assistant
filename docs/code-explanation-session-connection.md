# AI 视觉对话助手 — 会话连接代码详解

> 面向开发者，逐类逐方法解释代码。代码位置格式：`文件名:行号`

---

## 目录

1. [项目结构概览](#1-项目结构概览)
2. [消息协议层 — 15 种消息类型](#2-消息协议层--15-种消息类型)
3. [实体层 — 12 个 Payload 类 + 消息信封](#3-实体层--12-个-payload-类--消息信封)
4. [业务会话 — ConversationSession](#4-业务会话--conversationsession)
5. [会话管理 — SessionManager](#5-会话管理--sessionmanager)
6. [消息处理器 — ConversationWebSocketHandler](#6-消息处理器--conversationwebsockethandler)
7. [配置层 — WebSocketConfig + CorsConfig](#7-配置层--websocketconfig--corsconfig)
8. [监控 — HealthController](#8-监控--healthcontroller)
9. [前端如何对接 — 数据流](#9-前端如何对接--数据流)

---

## 1. 项目结构概览

```
server/src/main/java/com/aivca/
├── AiVisualConversationApplication.java     ← Spring Boot 入口
├── config/
│   ├── WebSocketConfig.java                 ← 注册 WS 端点 /ws/conversation
│   └── CorsConfig.java                      ← 允许前端跨域
├── handler/
│   └── ConversationWebSocketHandler.java    ← 核心消息路由（281 行）
├── controller/
│   └── HealthController.java                ← /health 监控
├── service/
│   └── SessionManager.java                  ← 会话 CRUD
└── model/
    ├── enums/
    │   └── MessageType.java                 ← 15 种消息类型
    ├── message/
    │   ├── Message.java                     ← 消息信封（泛型 T）
    │   ├── ConnectionInitPayload.java       ← 连接初始化
    │   ├── ConnectionAckPayload.java        ← 连接确认
    │   ├── DeviceControlPayload.java        ← 设备开关（摄像头/麦克风共用）
    │   ├── FrameDataPayload.java            ← 视频帧
    │   ├── AudioDataPayload.java            ← 音频数据
    │   ├── SpeechEventPayload.java          ← VAD 事件
    │   ├── ResponseTextPayload.java         ← AI 文字回复
    │   ├── ResponseAudioPayload.java        ← AI 语音回复
    │   ├── VisionResultPayload.java         ← 视觉分析结果
    │   ├── StatusUpdatePayload.java         ← 状态更新
    │   └── ErrorPayload.java                ← 错误
    └── session/
        └── ConversationSession.java         ← 业务会话
```

---

## 2. 消息协议层 — 15 种消息类型

`model/enums/MessageType.java` —— 所有通信的"菜单"

```java
public enum MessageType {
    // 客户端 → 服务端（8 种）
    CONNECTION_INIT,       // 初始化连接
    CAMERA_CONTROL,        // 摄像头开关
    MICROPHONE_CONTROL,    // 麦克风开关
    FRAME_DATA,            // 视频帧（Base64 JPEG）
    AUDIO_DATA,            // 音频块（Base64）
    SPEECH_START,          // 开始说话
    SPEECH_END,            // 说话结束
    PING,                  // 心跳

    // 服务端 → 客户端（7 种）
    CONNECTION_ACK,        // 连接确认（含 sessionId）
    RESPONSE_TEXT,         // AI 文字回复
    RESPONSE_AUDIO,        // AI 语音回复
    VISION_RESULT,         // 视觉分析结果
    STATUS_UPDATE,         // 状态变更
    ERROR,                 // 错误
    PONG;                  // 心跳回复
}
```

**设计要点**：枚举名就是 JSON 中的 `type` 字段值，`@JsonValue` 注解确保序列化时使用 `name()`。

---

## 3. 实体层 — 12 个 Payload 类 + 消息信封

所有消息使用统一信封格式：

```json
{
  "type": "MESSAGE_TYPE",
  "timestamp": 1718123456789,
  "sessionId": "a1b2c3d4e5f6",
  "payload": { ... }     ← 具体类型由 type 决定
}
```

### 3.1 Message\<T\> — 消息信封

`model/message/Message.java`

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Message<T> {
    @JsonProperty(required = true)
    private MessageType type;      // 决定 payload 的类型
    @JsonProperty(required = true)
    private long timestamp;        // Unix 毫秒时间戳
    private String sessionId;      // CONNECTION_INIT 时为空，之后由服务端分配
    private T payload;             // 泛型载荷
}
```

**泛型设计**：`T` 根据 `type` 决定：
- `type=FRAME_DATA` → `Message<FrameDataPayload>`
- `type=AUDIO_DATA` → `Message<AudioDataPayload>`

### 3.2 ConnectionInitPayload

`model/message/ConnectionInitPayload.java`

```json
{
  "deviceInfo": {
    "userAgent": "Mozilla/5.0 ...",
    "platform": "Win32",
    "screenWidth": 1920,
    "screenHeight": 1080
  }
}
```

用途：客户端连接后第一条消息，让服务端了解设备环境。

### 3.3 ConnectionAckPayload

`model/message/ConnectionAckPayload.java`

```json
{ "sessionId": "a1b2c3d4e5f6", "serverTime": 1718123456789 }
```

用途：服务端回应，返回 12 位业务会话 ID。客户端收到后存在 `useWebSocket` 的 state 中，后续消息都带上。

### 3.4 DeviceControlPayload

`model/message/DeviceControlPayload.java`

```json
{ "enabled": true, "deviceId": "camera-uuid-xxx" }
```

用途：摄像头和麦克风开关共用同一个 Payload，通过 `CAMERA_CONTROL` / `MICROPHONE_CONTROL` 区分。

### 3.5 FrameDataPayload

`model/message/FrameDataPayload.java`

```json
{
  "format": "jpeg",
  "width": 640, "height": 480,
  "data": "/9j/4AAQSkZJRg...",     ← Base64 JPEG，不含前缀
  "changed": true,
  "imageChecksum": "2a3b4c5d"       ← 64x64 缩略图哈希
}
```

**关键字段**：
- `changed`: 端侧帧差检测结果，`false` 时服务端跳过处理
- `imageChecksum`: 服务端据此缓存，相同画面不重复调 Vision API

### 3.6 AudioDataPayload

`model/message/AudioDataPayload.java`

```json
{
  "format": "opus", "sampleRate": 16000, "channels": 1,
  "data": "AAAA...", "duration": 1.5
}
```

用途：端侧 VAD 检测到语音后分段发送的音频块。

### 3.7 SpeechEventPayload

```json
{ "timestamp": 1718123456789 }
```

用途：`SPEECH_START` / `SPEECH_END` 消息，通知服务端用户何时开始/停止说话。

### 3.8 StatusUpdatePayload

`model/message/StatusUpdatePayload.java`

```json
{ "state": "listening", "detail": "正在识别语音..." }
```

`state` 枚举值：`idle | listening | thinking | speaking | watching | error`

用途：服务端主动推送当前处理状态，前端据此更新状态指示器。

---

## 4. 业务会话 — ConversationSession

`model/session/ConversationSession.java` —— 每个客户端对应一个实例

### 4.1 字段

```java
@Data
public class ConversationSession {
    private final String sessionId;            // 12 位 UUID "a1b2c3d4e5f6"
    private final Instant createdAt;           // 创建时间
    private Instant lastActiveAt;              // 最后活跃（空闲回收依据）

    private DeviceInfo deviceInfo;             // 客户端 UA、屏幕

    private boolean cameraEnabled;             // 摄像头状态
    private boolean microphoneEnabled;
    private String cameraDeviceId;
    private String microphoneDeviceId;

    private String cachedVisionDescription;    // 视觉缓存（画面不变时复用）
    private String cachedImageChecksum;        // 对应帧哈希

    private int conversationRound;             // 对话轮次
    private List<ConversationTurn> history;    // 对话历史（最多 12 条）
    private State currentState;                // 当前处理状态
}
```

### 4.2 构造函数

```java
public ConversationSession() {
    this.sessionId = UUID.randomUUID().toString()
            .replace("-", "").substring(0, 12);  // 36 位 → 12 位
    this.createdAt = Instant.now();
    this.lastActiveAt = Instant.now();
}
```

`UUID.randomUUID()` 生成 `550e8400-e29b-41d4-a716-446655440000`，去横杠后取前 12 位得 `550e8400e29b`。

### 4.3 touch() — 防空闲回收

```java
public void touch() {
    this.lastActiveAt = Instant.now();
}
```

每次通过 `SessionManager.get()` 查找会话时自动调用，更新活跃时间。30 分钟无 `touch()` → `cleanIdleSessions()` 回收。

### 4.4 addTurn() — 对话历史

```java
public void addTurn(String userText, String assistantText) {
    history.add(new ConversationTurn(conversationRound++,
                  userText, assistantText, Instant.now()));
    while (history.size() > MAX_HISTORY) {  // MAX_HISTORY = 12
        history.remove(0);                  // 超过上限删除最早的
    }
}
```

---

## 5. 会话管理 — SessionManager

`service/SessionManager.java` —— 会话的"电话簿"

### 5.1 核心数据结构

```java
// 业务会话池：sessionId → 会话对象
private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();

// WebSocket 连接 ID → 业务会话 ID 映射
private final Map<String, String> wsToSession = new ConcurrentHashMap<>();
```

### 5.2 create() — 创建会话

```java
public ConversationSession create() {
    ConversationSession session = new ConversationSession();
    sessions.put(session.getSessionId(), session);
    log.info("[SESSION] 会话已创建 | sessionId={}", session.getSessionId());
    return session;
}
```

### 5.3 bindWsSession() — 绑定两层 ID

```java
public void bindWsSession(String wsSessionId, String appSessionId) {
    wsToSession.put(wsSessionId, appSessionId);
}
```

这是**连接流程中最关键的一步**：将 Spring 自动生成的物理连接 ID（如 `abc123`）与我们生成的业务会话 ID（如 `a1b2c3d4e5f6`）关联。

### 5.4 getByWsId() — 反查会话

```java
public ConversationSession getByWsId(String wsSessionId) {
    String appSessionId = wsToSession.get(wsSessionId);  // wsId → sessionId
    if (appSessionId == null) return null;
    return get(appSessionId);                            // sessionId → Session 对象
}
```

handler 中所有消息处理方法都通过 `getByWsId(wsSession.getId())` 获取当前会话。

### 5.5 remove() — 清理

```java
public void remove(String wsSessionId) {
    String appSessionId = wsToSession.remove(wsSessionId);  // 解除绑定
    if (appSessionId != null) {
        sessions.remove(appSessionId);                      // 销毁会话
    }
}
```

在 `afterConnectionClosed` 中调用。

### 5.6 cleanIdleSessions() — 空闲回收

遍历所有会话，`lastActiveAt` 超过 30 分钟的移除。防止内存泄漏。

---

## 6. 消息处理器 — ConversationWebSocketHandler

`handler/ConversationWebSocketHandler.java` —— **281 行，整个后端的核心**

### 6.1 类结构

```java
@Slf4j @Component @RequiredArgsConstructor
public class ConversationWebSocketHandler extends TextWebSocketHandler {

    private final SessionManager sessionManager;           // 注入
    private final ObjectMapper objectMapper;               // JSON 工具
    private final Map<String, WebSocketSession> activeConnections = new ConcurrentHashMap<>();

    // === 4 个生命周期回调 ===
    afterConnectionEstablished()    // 连接建立 → 放入连接池
    handleTextMessage()             // 收到消息 → 路由分发
    afterConnectionClosed()         // 连接关闭 → 清理
    handleTransportError()          // 传输异常 → 记录日志

    // === 8 个消息处理方法 ===
    handleConnectionInit()          // CONNECTION_INIT → 创建会话 + 返回 ACK
    handleDeviceControl()           // CAMERA/MICROPHONE_CONTROL → 记录状态
    handleFrameData()               // FRAME_DATA → 缓存帧差
    handleAudioData()               // AUDIO_DATA → 待接入 STT
    handleSpeechEvent()             // SPEECH_START/END → VAD 事件
    handlePing()                    // PING → 回复 PONG

    // === 3 个发送工具方法 ===
    sendMessage()                   // 序列化 + 发送
    sendStatus()                    // 推送状态
    sendError()                     // 推送错误
}
```

### 6.2 handleTextMessage() — 消息路由

```java
@Override
protected void handleTextMessage(WebSocketSession wsSession, TextMessage textMessage) {
    JsonNode root = objectMapper.readTree(textMessage.getPayload());  // JSON → JsonNode
    MessageType type = MessageType.valueOf(root.get("type").asText());// 提取 type 字段

    switch (type) {                    // Java 14 箭头语法
        case CONNECTION_INIT   -> handleConnectionInit(wsSession, root);
        case CAMERA_CONTROL    -> handleDeviceControl(wsSession, root, true);
        case MICROPHONE_CONTROL-> handleDeviceControl(wsSession, root, false);
        case FRAME_DATA        -> handleFrameData(wsSession, root);
        case AUDIO_DATA        -> handleAudioData(wsSession, root);
        case SPEECH_START      -> handleSpeechEvent(wsSession, root, true);
        case SPEECH_END        -> handleSpeechEvent(wsSession, root, false);
        case PING              -> handlePing(wsSession);
        default                -> sendError(...);      // 未知类型 → 报错
    }
}
```

### 6.3 handleConnectionInit() — 最核心的方法

```java
private void handleConnectionInit(WebSocketSession wsSession, JsonNode root) {
    // Step 1: 反序列化设备信息
    ConnectionInitPayload init = objectMapper.convertValue(
            root.get("payload"), ConnectionInitPayload.class);

    // Step 2: 创建业务会话
    ConversationSession session = sessionManager.create();
    if (init != null) {
        session.setDeviceInfo(init.getDeviceInfo());
    }

    // Step 3: 绑定 wsId ↔ sessionId
    sessionManager.bindWsSession(wsSession.getId(), session.getSessionId());

    // Step 4: 返回 ACK
    sendMessage(wsSession, MessageType.CONNECTION_ACK,
            ConnectionAckPayload.builder()
                    .sessionId(session.getSessionId())
                    .serverTime(System.currentTimeMillis())
                    .build());

    // Step 5: 通知就绪
    sendStatus(wsSession, StatusUpdatePayload.State.idle, "已就绪");
}
```

### 6.4 sendMessage() — 统一的发送方法

```java
private void sendMessage(WebSocketSession wsSession, MessageType type, Object payload) {
    // 1. 构建消息信封
    Message<Object> msg = Message.builder()
            .type(type)
            .timestamp(System.currentTimeMillis())
            .sessionId(session != null ? session.getSessionId() : "")
            .payload(payload)
            .build();

    // 2. 序列化为 JSON 字符串
    String json = objectMapper.writeValueAsString(msg);

    // 3. 通过 WebSocket 发送
    wsSession.sendMessage(new TextMessage(json));
}
```

---

## 7. 配置层 — WebSocketConfig + CorsConfig

### 7.1 WebSocketConfig

`config/WebSocketConfig.java`

```java
@Configuration @EnableWebSocket @RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {
    private final ConversationWebSocketHandler handler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/conversation")
                .setAllowedOriginPatterns("*");  // 允许所有来源
    }
}
```

这就决定了 WebSocket 地址为 `ws://localhost:8080/ws/conversation`。

### 7.2 CorsConfig

`config/CorsConfig.java`

允许前端（`localhost:5173`）跨域访问 `/health` 等 HTTP 接口。

---

## 8. 监控 — HealthController

`controller/HealthController.java`

```java
@RestController @RequiredArgsConstructor
public class HealthController {
    private final SessionManager sessionManager;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "activeSessions", sessionManager.getActiveCount()
        ));
    }
}
```

访问 `http://localhost:8080/health` → `{"status": "UP", "activeSessions": 0}`

---

## 9. 前端如何对接 — 数据流

### 9.1 完整交互流程

```
1. 前端连接
   const ws = new WebSocket('ws://localhost:8080/ws/conversation')
   → TCP + HTTP Upgrade
   → 服务端 afterConnectionEstablished() → 存入 activeConnections

2. 前端发送 CONNECTION_INIT
   ws.send({ type: 'CONNECTION_INIT', payload: { deviceInfo: {...} } })
   → 服务端 handleConnectionInit()
   → 创建 ConversationSession，生成 sessionId
   → wsId ↔ sessionId 绑定
   → 返回 CONNECTION_ACK { sessionId: "xxx" }

3. 前端存储 sessionId
   ws.onmessage → if (msg.type === 'CONNECTION_ACK')
                  setSessionId(msg.payload.sessionId)

4. 后续通信都带上 sessionId
   sendMessage('CAMERA_CONTROL', { enabled: true })
   → 服务端 getByWsId(wsId) → 找到 Session → 更新 cameraEnabled

5. 心跳
   每 30 秒发 PING → 服务端回 PONG

6. 断开
   ws.close() → 服务端 afterConnectionClosed()
   → activeConnections.remove(wsId)
   → sessionManager.remove(wsId) → 销毁会话
```

### 9.2 前端关键代码位置

| 文件 | 行号 | 功能 |
|------|:----:|------|
| `client/src/hooks/useWebSocket.ts` | `:101` | `new WebSocket()` 发起连接 |
| `client/src/hooks/useWebSocket.ts` | `:109` | 发送 CONNECTION_INIT |
| `client/src/hooks/useWebSocket.ts` | `:136` | 接收 CONNECTION_ACK，存 sessionId |
| `client/src/hooks/useWebSocket.ts` | `:124` | 启动心跳定时器 |
| `client/src/hooks/useWebSocket.ts` | `:152` | 断线重连逻辑 |
| `client/src/types/messages.ts` | 全文 | 与后端一致的消息协议类型 |

---

## 附录：快速定位

| 想了解什么 | 看哪个文件 |
|-----------|-----------|
| 有哪些消息类型 | `model/enums/MessageType.java` |
| 消息长什么样 | `model/message/*.java`（每个类一个 JSON 示例） |
| 会话有什么属性 | `model/session/ConversationSession.java` |
| 收到消息后怎么处理 | `handler/ConversationWebSocketHandler.java:63` |
| CONNECTION_INIT 的完整流程 | `handler/ConversationWebSocketHandler.java:118` |
| wsId 和 sessionId 的关系 | `service/SessionManager.java:26-32` |
| 怎么发消息给客户端 | `handler/ConversationWebSocketHandler.java:251` |
| WebSocket 路径怎么配置 | `config/WebSocketConfig.java:25` |
| 前端怎么连 | `client/src/hooks/useWebSocket.ts:101` |

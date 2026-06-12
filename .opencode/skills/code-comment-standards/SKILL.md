# AI 视觉对话助手 — 代码注释规范

## 适用范围

本规范适用于 `client/`（React/TypeScript）和 `server/`（Java/Spring Boot）全项目代码。

## 核心原则

1. **每个类/组件必须有类级注释**，说明用途和职责
2. **每个属性/字段必须有中文注释**，解释含义
3. **每个 public 方法必须有 Javadoc/JSDoc**，包含 `@param` 和 `@return`
4. **注释说"为什么"，代码说"是什么"** — 不重复代码本身

---

## 一、Java 后端 (server/)

### 1.1 类注释模板

```java
/**
 * [一句话描述类的职责]
 *
 * 负责：
 * - [职责1]
 * - [职责2]
 *
 * 不负责：
 * - [明确排除的职责]
 */
```

### 1.2 实体类 (model/message)

```java
/**
 * FRAME_DATA 消息的载荷 —— 客户端发送的视频帧数据。
 *
 * 字段说明：
 * - format:  图片编码格式，固定 "jpeg"
 * - width:   图片宽度（像素）
 * - height:  图片高度（像素）
 * - data:    Base64 编码的 JPEG 数据
 * - changed: 帧差检测结果，true=画面有变化
 * - imageChecksum: 缩略图哈希值，用于服务端去重
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FrameDataPayload {

    /** 图片编码格式： "jpeg" */
    private String format;

    /** 图片宽度（像素） */
    private int width;

    /** 图片高度（像素） */
    private int height;

    /** Base64 编码的 JPEG 图片数据 */
    private String data;

    /** 帧差检测结果：与上一帧相比是否有显著变化 */
    private boolean changed;

    /** 缩略图哈希值（64x64），用于服务端缓存去重 */
    private String imageChecksum;
}
```

### 1.3 枚举类

```java
/**
 * WebSocket 消息类型枚举。
 *
 * 分为两组：
 * - C2S（客户端→服务端）：CONNECTION_INIT 到 PING
 * - S2C（服务端→客户端）：CONNECTION_ACK 到 PONG
 */
public enum MessageType {

    // ===== 客户端 → 服务端 =====
    /** 初始化连接，携带设备信息 */
    CONNECTION_INIT,
    /** 摄像头开关控制 */
    CAMERA_CONTROL,
    // ...

    // ===== 服务端 → 客户端 =====
    /** 连接确认，返回 sessionId */
    CONNECTION_ACK,
    /** AI 文字回复 */
    RESPONSE_TEXT,
    // ...
}
```

### 1.4 服务类 / Handler 类

```java
/**
 * WebSocket 消息处理器 —— 接收客户端消息并路由到对应处理方法。
 *
 * 负责：
 * - WebSocket 连接生命周期管理（建立/关闭/异常）
 * - 消息反序列化与类型路由
 * - 会话创建与绑定
 * - 状态推送与错误响应
 *
 * 不负责：
 * - 实际的 AI 推理（由后续 Orchestrator 处理）
 * - 数据持久化（由后续 Repository 处理）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationWebSocketHandler extends TextWebSocketHandler {

    /** 会话管理器 —— 负责会话的创建、查找、销毁 */
    private final SessionManager sessionManager;

    /** JSON 序列化/反序列化 */
    private final ObjectMapper objectMapper;

    /** 当前活跃的 WebSocket 连接，以 wsSessionId 为键 */
    private final Map<String, WebSocketSession> activeConnections = new ConcurrentHashMap<>();

    /**
     * 处理客户端发来的文本消息，根据 type 字段路由到具体处理方法。
     *
     * @param wsSession   WebSocket 会话
     * @param textMessage 客户端发来的原始 JSON 消息
     */
    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage textMessage) {
        // ...
    }
}
```

---

## 二、TypeScript 前端 (client/)

### 2.1 类型定义 (types/)

```typescript
/**
 * 消息类型枚举 —— 与后端 MessageType.java 严格保持一致。
 *
 * 客户端 → 服务端 和 服务端 → 客户端 的消息类型统一定义，
 * 通过 ClientMessageType 和 ServerMessageType 区隔。
 */

/** 客户端 → 服务端 的消息类型 */
export type ClientMessageType =
  | 'CONNECTION_INIT'   /** 初始化连接，携带设备信息 */
  | 'CAMERA_CONTROL'    /** 摄像头开关控制 */
  | 'MICROPHONE_CONTROL'/** 麦克风开关控制 */
  // ...

/**
 * 视频帧载荷 —— 从 canvas 截取的 JPEG 帧数据。
 *
 * 字段说明：
 * - format:        图片格式，固定 "jpeg"
 * - width:         图片宽度（像素）
 * - height:        图片高度（像素）
 * - data:          Base64 编码（不含 data:image/... 前缀）
 * - changed:       帧差检测结果，true=与上一帧不同
 * - imageChecksum: 64x64 缩略图哈希，用于服务端去重
 */
export interface FrameDataPayload {
  format: 'jpeg';
  width: number;
  height: number;
  data: string;
  changed: boolean;
  imageChecksum: string;
}
```

### 2.2 React Hook

```typescript
/**
 * 摄像头管理 Hook。
 *
 * 负责：
 * - 调用 getUserMedia 申请摄像头权限
 * - 管理 MediaStream 生命周期
 * - 提供 videoRef 用于渲染预览
 * - 通过 onStateChange 回调通知服务端摄像头状态变更
 *
 * @param options.onStateChange — 摄像头状态变更时回调，发送 CAMERA_CONTROL 消息
 * @returns state — 摄像头当前状态（enabled, stream, error 等）
 * @returns videoRef — 绑定到 <video> 元素的 ref
 * @returns start — 开启摄像头（可指定 deviceId）
 * @returns stop — 关闭摄像头，释放 MediaStream
 * @returns toggle — 切换开关
 */
export function useCamera(options?: UseCameraOptions) {
  // ...
}
```

---

## 三、检查清单

提交代码前确认：

- [ ] 每个 `public class` / 组件 有类级注释（用途+职责边界）
- [ ] 每个字段/属性 有中文注释（说明含义）
- [ ] 每个 `public` 方法有方法注释（`@param` + `@return`）
- [ ] 数据类型转换处有行内注释（标注转换前后类型）
- [ ] 注释不重复代码本身逻辑（说"为什么"，不说"是什么"）
- [ ] 没有注释掉的旧代码

---

## 快捷参考：注释标签

| 标签 | 用途 | 示例 |
|------|------|------|
| `TODO:` | 待实现 | `// TODO: 后续接入 Vision API` |
| `FIXME:` | 需修复 | `// FIXME: 帧差阈值需根据光线条件动态调整` |
| `NOTE:` | 重要提示 | `// NOTE: Map 无上限，生产环境需加 LRU 淘汰` |
| `HACK:` | 临时方案 | `// HACK: 暂时用 toDataURL 截帧，后续改用 OffscreenCanvas` |

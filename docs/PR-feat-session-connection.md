# PR: 添加 WebSocket 会话连接后端

## 标题

**feat: 添加 WebSocket 会话连接 — 消息协议定义、实体类、Handler 与会话管理**

---

## 功能描述

本 PR 为服务端添加了 WebSocket 通信与会话管理能力，实现以下功能：

- **WebSocket 服务端点**：注册 `/ws/conversation`，支持全双工 JSON 通信
- **15 种标准消息类型**：C2S 8 种（`CONNECTION_INIT` 至 `PING`）+ S2C 7 种（`CONNECTION_ACK` 至 `PONG`）
- **会话管理**：两层 ID 体系（wsId ↔ sessionId），支持会话创建、查找、销毁、空闲回收
- **连接生命周期**：`afterConnectionEstablished` → 消息路由 → `afterConnectionClosed` 完整链路
- **消息路由分发**：JSON 反序列化后按 `type` 字段 switch 分发到 8 个处理方法
- **设备状态同步**：接收并记录客户端摄像头/麦克风开关状态
- **心跳保活**：PING/PONG 机制
- **帧差缓存**：服务端按 `imageChecksum` 缓存帧，重复画面不处理
- **健康检查**：`GET /health` 返回服务状态和活跃会话数

---

## 实现思路

### 目录结构

```
server/src/main/java/com/aivca/
├── model/
│   ├── enums/MessageType.java              # 15 种消息类型枚举
│   ├── message/                            # 消息载荷（12 个 Payload 类）
│   └── session/ConversationSession.java    # 业务会话上下文
├── handler/ConversationWebSocketHandler.java  # 消息路由 + 连接管理
├── service/SessionManager.java             # 会话 CRUD + 空闲回收
├── config/WebSocketConfig.java             # WS 端点注册
└── config/CorsConfig.java                  # 跨域配置
```

### 核心设计

**两层 ID 体系**：Spring 自动生成的 wsId（物理寻址）与业务 sessionId（上下文关联）解耦：

```
wsId ──→ SessionManager.wsToSession ──→ sessionId ──→ ConversationSession
                                              ├── 对话历史
                                              ├── 设备状态
                                              └── 视觉缓存
```

**消息信封 Message\<T\>**：泛型设计，type 决定 payload 类型，Jackson 自动反序列化。

### 消息路由

`ConversationWebSocketHandler.handleTextMessage()` 解析 JSON → 提取 type → switch 分发到 8 个私有方法。

---

## 测试方式

### 启动

```bash
# 服务端
cd server && mvn spring-boot:run

# 前端
cd client && npm run dev
```

### 验证项

| 测试项 | 预期 |
|--------|------|
| `http://localhost:8080/health` | `{"status":"UP","activeSessions":0}` |
| 浏览器打开前端，点击麦克风 | 顶部显示"已连接"，服务端日志 `[CONNECT]` |
| WebSocket 连接建立 | 服务端日志 `[SESSION] 会话初始化完成` |
| 前端发送 `CAMERA_CONTROL` | 服务端日志 `[DEVICE] 摄像头已开启` |
| 断网 | 前端 3 秒后自动重连，新 sessionId |
| 30 秒无操作 | 前端发送 PING，服务端回复 PONG |

### 编译

```bash
cd server && mvn compile   # ✅ BUILD SUCCESS
cd client && npx tsc --noEmit  # ✅
```

---

## 变更文件

| 目录 | 文件数 |
|------|:------:|
| `server/src/main/java/com/aivca/model/` | 14 |
| `server/src/main/java/com/aivca/handler/` | 1 |
| `server/src/main/java/com/aivca/service/` | 1 |
| `server/src/main/java/com/aivca/config/` | 2 |
| `server/src/main/java/com/aivca/controller/` | 1 |
| **合计** | **19** |

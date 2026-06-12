# AI 视觉对话助手

一款基于 **端云协同架构** 的 AI 视觉对话应用。打开摄像头与麦克风，让 AI 看到你、听到你，并给予恰当的语音+文字回应。

---

## 功能清单

### 已实现

| 编号 | 功能 | 状态 |
|------|------|:--:|
| US-01 | 摄像头实时预览，确认 AI 看到自己 | ✅ |
| US-02 | 麦克风语音输入，说话后 AI 识别并回复 | ✅ |
| US-03 | 对话历史以聊天气泡形式展示 | ✅ |
| US-04 | 摄像头/麦克风 **独立开关**，用户随时关闭 | ✅ |
| US-05 | AI 状态实时展示（正在听 / 正在看 / 思考中） | ✅ |
| US-06 | 画面中央 **玻璃字幕条** 实时显示用户说话内容 | ✅ |
| US-07 | WebSocket 双向通信，自动重连 + 心跳保活 | ✅ |
| US-08 | 端侧帧差检测（画面无变化不发送，节省带宽和 API 调用） | ✅ |
| US-09 | 对话上下文管理，自动裁剪早期历史 | ✅ |
| US-10 | 15 种标准化 WebSocket 消息协议，前后端类型一致 | ✅ |
| US-11 | 代码注释规范 + 日志规范 Skill（`.opencode/`） | ✅ |
| US-12 | 会话空闲自动回收（30 分钟超时） | ✅ |
| US-13 | 摄像头错误友好提示（权限拒绝 / 设备未找到） | ✅ |

### 待实现

| 编号 | 功能 | 状态 |
|------|------|:--:|
| US-14 | 云端 AI 视觉理解（Vision API 分析摄像头画面） | ❌ |
| US-15 | 云端语音识别（Whisper STT 替代浏览器 Speech API） | ❌ |
| US-16 | AI 对话推理（GPT-4o 基于视觉+语音生成回复） | ❌ |
| US-17 | AI 语音播报（TTS 文字转语音输出） | ❌ |
| US-18 | 端侧 VAD 语音活动检测（彻底消除对浏览器 STT 的依赖） | ❌ |
| US-19 | 用户视觉缓存（相同画面不重复调 Vision API） | ❌ |
| US-20 | 模型分级策略（视觉用便宜模型，对话用强模型） | ❌ |
| US-21 | 屏幕共享模式，分享屏幕内容给 AI | ❌ |
| US-22 | 对话历史持久化存储（当前为内存模式） | ❌ |
| US-23 | 多语言支持（中/英切换） | ❌ |
| US-24 | 离线降级（网络断开时本地小模型兜底） | ❌ |
| US-25 | 音频格式压缩（opus/mono/16kHz） | ❌ |
| US-26 | TTS 缓存（相同回复复用音频） | ❌ |

---

## 项目架构

### 三层架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        BROWSER (client/)                         │
│                                                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  ┌─────────────┐ │
│  │ 摄像头    │  │ 麦克风    │  │ Canvas 帧截图 │  │ Web Audio   │ │
│  │ Media-   │  │ Media-   │  │              │  │ API 音频采集 │ │
│  │ Stream   │  │ Stream   │  │              │  │              │ │
│  └────┬─────┘  └────┬─────┘  └──────┬───────┘  └──────┬──────┘ │
│       │              │               │                  │        │
│       ▼              ▼               ▼                  ▼        │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │                    端侧预处理层                              │  │
│  │  • 帧差检测（像素哈希对比）   • 语音实时识别（Web Speech）    │  │
│  │  • 帧采样间隔控制（2~3s）    • 音频电平可视化               │  │
│  └──────────────────────────┬─────────────────────────────────┘  │
│                             │                                    │
│                    ┌────────▼────────┐                           │
│                    │  WebSocket 客户端 │  ◄── 单一长连接           │
│                    └────────┬────────┘                           │
│                             │                                    │
│  ┌──────────────────────────▼─────────────────────────────────┐  │
│  │                    UI 层 (React 18 + Tailwind)               │  │
│  │  CameraView · SpeechOverlay · ControlBar · ConversationPanel │  │
│  └─────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                     WebSocket (wss://)
                              │
┌─────────────────────────────▼───────────────────────────────────┐
│                       SERVER (server/)                           │
│                                                                  │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────────┐  │
│  │ WebSocket Handler│  │ Session Manager  │  │ Health API    │  │
│  │ (消息路由分发)    │  │ (会话生命周期)    │  │ (运维监控)     │  │
│  └────────┬─────────┘  └────────┬─────────┘  └───────────────┘  │
│           │                     │                                │
│           ▼                     ▼                                │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │                   编排层 (Orchestrator) —— 待实现            │  │
│  │  • 端侧消息 → 路由到 AI 服务 → 聚合结果 → 返回端侧          │  │
│  │  • 对话上下文管理 + Token 预算控制                          │  │
│  └────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                ┌─────────────┼─────────────┐
                ▼             ▼             ▼
          ┌──────────┐ ┌──────────┐ ┌──────────┐
          │ Vision   │ │ STT      │ │ LLM +    │
          │ API      │ │ API      │ │ TTS      │
          │ (GPT-4o  │ │ (Whisper)│ │ (GPT-4o) │
          │  mini)   │ │          │ │          │
          └──────────┘ └──────────┘ └──────────┘
              ↑ 真正的云侧 AI 服务（OpenAI / 其他）
```

### 设计原则

- **端侧做过滤，云侧做推理** — 端侧负责 VAD、帧差检测，只把有价值的数据发给云
- **摄像头/麦克风完全独立** — 用户可以只开摄像头不开麦克风，反之亦然
- **成本可控** — 帧差检测 + 视觉缓存 + 上下文裁剪 + 模型分级，预计节省 85% API 费用
- **前后端分离** — React (port 5173) ↔ WebSocket ↔ Java (port 8080)

---

## 技术栈

| 层 | 技术 | 说明 |
|----|------|------|
| **客户端框架** | React 18 + TypeScript + Vite | SPA 单页应用 |
| **客户端样式** | Tailwind CSS 3.4 | 玻璃拟态 (Glassmorphism) |
| **视频采集** | MediaStream API (`getUserMedia`) | 浏览器原生 |
| **音频处理** | Web Audio API + AudioContext | 实时音量电平 |
| **语音识别** | Web Speech API (SpeechRecognition) | 浏览器内置 STT（Chrome） |
| **通信** | WebSocket (JSON) | 全双工 + 自动重连 + 心跳 |
| **服务端框架** | Spring Boot 3.3 + Java 17 | |
| **服务端通信** | Spring WebSocket | `TextWebSocketHandler` |
| **序列化** | Jackson | JSON ↔ Java POJO |
| **简化代码** | Lombok 1.18.38 | `@Data` `@Builder` `@Slf4j` |
| **构建工具** | Maven (server) / npm (client) | |

---

## 项目结构

```
AI Visual Conversation Assistant/
├── client/                              # 端侧：React 前端
│   ├── index.html
│   ├── package.json
│   ├── vite.config.ts
│   ├── tailwind.config.js
│   ├── tsconfig.json
│   └── src/
│       ├── main.tsx                     # 入口
│       ├── App.tsx                      # 根组件（布局 + 数据流串联）
│       ├── index.css                    # 全局样式 + Tailwind
│       ├── types/
│       │   ├── messages.ts              # 消息协议类型（与后端一致）
│       │   └── speech-recognition.d.ts  # Web Speech API 类型声明
│       ├── hooks/
│       │   ├── useCamera.ts             # 摄像头管理
│       │   ├── useMicrophone.ts         # 麦克风管理
│       │   ├── useWebSocket.ts          # WebSocket 连接
│       │   └── useSpeechRecognition.ts  # 端侧语音识别
│       ├── components/
│       │   ├── CameraView.tsx           # 摄像头预览
│       │   ├── ControlBar.tsx           # 底部控制栏（设备按钮）
│       │   ├── StatusIndicator.tsx      # 顶部状态栏
│       │   ├── SpeechOverlay.tsx        # 视频底部玻璃字幕条
│       │   └── ConversationPanel.tsx    # 右侧对话记录
│       └── utils/
│           └── frameCapture.ts          # 帧截图 + 帧差检测
│
├── server/                              # 服务端：Java Spring Boot
│   ├── pom.xml
│   └── src/main/java/com/aivca/
│       ├── AiVisualConversationApplication.java   # 启动入口
│       ├── config/
│       │   ├── WebSocketConfig.java               # WebSocket 端点注册
│       │   └── CorsConfig.java                    # CORS 跨域
│       ├── handler/
│       │   └── ConversationWebSocketHandler.java   # 消息路由分发
│       ├── controller/
│       │   └── HealthController.java              # 健康检查
│       ├── service/
│       │   └── SessionManager.java                # 会话管理
│       └── model/
│           ├── enums/MessageType.java             # 15 种消息类型枚举
│           ├── message/                           # 消息载荷实体（15 个类）
│           └── session/ConversationSession.java   # 会话上下文
│
├── .opencode/skills/                    # AI 编程助手规范
│   ├── code-comment-standards/SKILL.md  # 代码注释规范
│   └── log-management/SKILL.md          # 日志输出规范
│
├── DESIGN.md                            # 详细设计文档
└── README.md                            # 本文件
```

---

## 消息协议

前后端通过 **WebSocket JSON** 通信，共 15 种消息类型：

### 客户端 → 服务端

| 类型 | 说明 | Payload |
|------|------|---------|
| `CONNECTION_INIT` | 初始化连接 | 设备信息（UA、屏幕尺寸） |
| `CAMERA_CONTROL` | 摄像头开关 | `{ enabled, deviceId }` |
| `MICROPHONE_CONTROL` | 麦克风开关 | `{ enabled, deviceId }` |
| `FRAME_DATA` | 视频帧 | `{ format, width, height, data(base64), changed, imageChecksum }` |
| `AUDIO_DATA` | 音频数据块 | `{ format, sampleRate, channels, data(base64), duration }` |
| `SPEECH_START` | 开始说话 | `{ timestamp }` |
| `SPEECH_END` | 说话结束 | `{ timestamp }` |
| `PING` | 心跳 | `null` |

### 服务端 → 客户端

| 类型 | 说明 | Payload |
|------|------|---------|
| `CONNECTION_ACK` | 连接确认 | `{ sessionId, serverTime }` |
| `RESPONSE_TEXT` | AI 文字回复 | `{ messageId, content, role, conversationRound }` |
| `RESPONSE_AUDIO` | AI 语音回复 | `{ messageId, text, format, data(base64), duration }` |
| `VISION_RESULT` | 视觉分析结果 | `{ frameChecksum, description, detectedObjects[], timestamp }` |
| `STATUS_UPDATE` | 状态更新 | `{ state, detail }` |
| `ERROR` | 错误信息 | `{ code, message }` |
| `PONG` | 心跳响应 | `null` |

---

## 快速启动

### 1. 启动服务端（Java）

```bash
cd server
mvn spring-boot:run
# → http://localhost:8080
# → ws://localhost:8080/ws/conversation
# → http://localhost:8080/health
```

### 2. 启动客户端（React）

```bash
cd client
npm install
npm run dev
# → http://localhost:5173
```

### 3. 使用

1. 浏览器打开 `http://localhost:5173`
2. 点击 **麦克风** 按钮 → 授权后开始说话
3. 点击 **摄像头** 按钮 → 授权后画面显示
4. 说话内容实时显示在画面下方玻璃字幕条中
5. 说完整句后，文字移入右侧对话记录

---

## 端侧预处理逻辑

```
用户说话：
  麦克风开启 → Web Speech API 识别 → 中间文本 → SpeechOverlay 浮字显示
                                     → 说完整句 → ConversationPanel 记录

摄像头画面：
  每 500ms Canvas 截帧 → 64x64 缩略图哈希 → 与上一帧对比
    → 无变化：不发请求
    → 有变化：压缩为 640px JPEG → 标记 changed=true → WebSocket 发服务端
```

## 待实现路线图

```
Phase 2: 云侧 AI 接入
  ├─ Vision API（GPT-4o-mini）
  ├─ STT API（Whisper）
  ├─ LLM API（GPT-4o）
  └─ TTS API（OpenAI TTS）

Phase 3: 端侧增强
  ├─ 端侧 VAD（替代 Web Speech API）
  ├─ 音频采集 + opus 编码
  └─ 帧截图与音频并行发送

Phase 4: 体验优化
  ├─ 对话持久化
  ├─ 屏幕共享
  └─ 多语言支持
```

---

*@SmoggyLong · 2026*

# AI 视觉对话助手 — 设计计划书

---

## 一、用户故事

### 计划实现 & 实际实现对照

| 编号 | 用户故事 | 优先级 | 计划 | 实际 |
|------|----------|--------|------|------|
| US-01 | 打开应用即看到摄像头实时预览画面，确认 AI 能看到自己 | P0 | ✅ | |
| US-02 | 按住按钮说话，松手后 AI 自动识别语音并给出回复 | P0 | ✅ | |
| US-03 | AI 能描述摄像头中看到的物体/场景，结合我说的话做回应 | P0 | ✅ | |
| US-04 | AI 的回复以**语音播报**出来，同时显示文字 | P0 | ✅ | |
| US-05 | 对话历史以聊天气泡形式展示，可滚动回溯 | P0 | ✅ | |
| US-06 | 支持**自动检测说话结束**（VAD 模式），无需按键也可对话 | P1 | ✅ | |
| US-07 | 对话中可看到 AI "正在看"的那一帧截图 | P1 | ✅ | |
| US-08 | 暂停/恢复摄像头，保护隐私 | P1 | ✅ | |
| US-09 | 对话过程中展示 AI 的状态（正在听/正在看/正在思考/正在说） | P1 | ✅ | |
| US-10 | 屏幕共享模式：分享屏幕内容让 AI 辅助操作 | P2 | ❌ | |
| US-11 | 对话历史持久化存储，刷新不丢失 | P2 | ❌ | |
| US-12 | 多语言支持（中/英切换） | P3 | ❌ | |
| US-13 | 离线降级：网络断开时用本地模型做基础 STT | P3 | ❌ | |

**说明**：P0 为 MVP 必做，P1 为体验增强，P2/P3 留待后续迭代。本次实现覆盖 P0+P1 共 9 个用户故事。

---

## 二、运营成本控制策略

### 成本主要来源

每轮对话的成本 = 视觉 API 调用 + STT API 调用 + LLM 推理 + TTS API 调用

以 OpenAI 为例（GPT-4o），单轮对话若无控制可达 **$0.05~$0.15**，日活 100 人每人 20 轮 = 日均 **$100~$300**。

### 策略清单

| 编号 | 策略 | 原理 | 预估节省 | 计划 | 实际 |
|------|------|------|----------|------|------|
| C-01 | **帧采样间隔控制** | 不是每帧都发给 AI，每隔 2~3 秒取一帧分析 | 减少 90%+ 视觉调用 | ✅ | |
| C-02 | **帧差检测** | 端侧用像素对比判断画面是否真的变了，没变就复用上次描述 | 静态场景下节省 100% | ✅ | |
| C-03 | **VAD 语音端点检测** | 只在用户真正说话时才触发 STT + LLM，无声音不调用 | 减少无效对话 80%+ | ✅ | |
| C-04 | **视觉描述缓存** | 相同/相似画面复用已生成的描述文本，不重复调 API | 减少重复调用 | ✅ | |
| C-05 | **上下文窗口裁剪** | 对话历史只保留最近 N 轮，超过的做摘要压缩而非全量携带 | 控制每轮 token 消耗 | ✅ | |
| C-06 | **模型分级策略** | 视觉描述用 gpt-4o-mini（便宜），对话推理用 gpt-4o | 视觉成本降 90% | ✅ | |
| C-07 | **音频格式压缩** | STT 前将音频转为低码率 opus/mono/16kHz，减少传输和处理量 | 带宽+延迟优化 | ❌ | |
| C-08 | **TTS 缓存** | 相同回复文本不重复调 TTS，复用已生成的音频 | 节省 TTS 调用 | ❌ | |
| C-09 | **空闲会话回收** | 超过 N 分钟无交互自动断开 WebSocket，释放资源 | 节省服务端资源 | ❌ | |
| C-10 | **本地 STT 降级** | 高并发时切换本地 Whisper.cpp 处理 STT，削峰填谷 | 降低云 STT 费用 | ❌ | |

**核心思路**：**端侧做过滤，云侧做推理**。让端侧充当"守门员"，把无价值的请求拦截在本地。

---

## 三、端云架构设计

### 3.1 总体架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                           BROWSER (客户端)                           │
│                                                                      │
│  ┌──────────┐  ┌──────────┐  ┌────────────┐  ┌──────────────────┐  │
│  │ 摄像头    │  │ 麦克风    │  │ Canvas     │  │ Web Audio API    │  │
│  │ Media-   │  │ Media-   │  │ 帧截图     │  │ 音频采集+播放    │  │
│  │ Stream   │  │ Stream   │  │            │  │                  │  │
│  └────┬─────┘  └────┬─────┘  └──────┬─────┘  └────────┬─────────┘  │
│       │              │               │                 │             │
│       ▼              ▼               ▼                 ▼             │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    预处理层 (端侧)                              │   │
│  │  • 帧差检测 (画面是否变化)                                      │   │
│  │  • VAD (是否有语音活动)                                         │   │
│  │  • 音频分段 (切出有效语音段)                                     │   │
│  └──────────────────────────┬───────────────────────────────────┘   │
│                             │                                       │
│                    ┌────────▼────────┐                              │
│                    │  WebSocket 客户端 │  ◄── 单一长连接，双向通信     │
│                    └────────┬────────┘                              │
│                             │                                       │
│  ┌──────────────────────────▼───────────────────────────────────┐   │
│  │                    UI 层 (React)                               │   │
│  │  • 摄像头预览  • 对话气泡  • 状态指示器  • 按钮交互             │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                              │
                     WebSocket (wss://)
                              │
┌─────────────────────────────▼───────────────────────────────────────┐
│                       SERVER (云侧 - Python)                         │
│                                                                      │
│  ┌────────────────┐   ┌────────────────┐   ┌──────────────────┐    │
│  │ FastAPI Server │   │ WebSocket      │   │ Session Manager  │    │
│  │ (HTTP + WS)    │   │ Handler        │   │ (连接/状态管理)   │    │
│  └───────┬────────┘   └───────┬────────┘   └────────┬─────────┘    │
│          │                    │                      │              │
│          ▼                    ▼                      ▼              │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                   编排层 (Orchestrator)                        │   │
│  │  • 接收端侧消息 → 路由到对应 AI 服务 → 聚合结果 → 返回端侧      │   │
│  │  • 管理对话上下文 (Conversation Context)                       │   │
│  │  • 控制调用频率 & Token 预算                                    │   │
│  └───────┬──────────────────┬──────────────────┬────────────────┘   │
│          │                  │                  │                    │
│          ▼                  ▼                  ▼                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐             │
│  │ Vision API   │  │ Chat API     │  │ STT API      │             │
│  │ (GPT-4o mini)│  │ (GPT-4o)     │  │ (Whisper)    │             │
│  └──────────────┘  └──────────────┘  └──────────────┘             │
│          │                                                        │
│          ▼                                                        │
│  ┌──────────────┐                                                 │
│  │ TTS API      │                                                 │
│  │ (OpenAI TTS) │                                                 │
│  └──────────────┘                                                 │
└────────────────────────────────────────────────────────────────────┘
```

### 3.2 数据流详解

```
一轮完整对话的数据流：

[用户说话]
    │
    ▼
[端侧: VAD 检测语音活动] ─── 无语音 → 忽略，不发请求
    │ 有语音
    ▼
[端侧: 采集音频段] → WebSocket → [云侧: Whisper STT] → 文本
    │
    ▼ 同时进行（并行优化）
[端侧: Canvas 截图] → 帧差检测 ─── 无变化 → 使用缓存的视觉描述
    │ 有变化                     │
    ▼                             ▼
[端侧: 压缩为 JPEG] → WebSocket → [云侧: Vision API] → 视觉描述文本
    │                                                      │
    ▼                                                      ▼
[云侧编排层: 组装 Prompt] ←──── 视觉描述 + 用户文本 + 对话历史
    │
    ▼
[云侧: Chat API (GPT-4o)] → AI 回复文本
    │
    ├──→ WebSocket → [端侧: 显示文字气泡]
    │
    └──→ [云侧: TTS API] → 音频 → WebSocket → [端侧: 播放语音]
```

### 3.3 通信协议设计

WebSocket 消息采用 JSON 格式，按类型区分：

```json
// ===== 端侧 → 云侧 =====

// 1. 发送音频数据（用于 STT）
{
  "type": "audio",
  "data": "<base64 encoded audio>",
  "format": "opus",
  "timestamp": 1718123456789
}

// 2. 发送视频帧（用于视觉分析）
{
  "type": "frame",
  "data": "<base64 encoded jpeg>",
  "timestamp": 1718123456789,
  "changed": true
}

// 3. 通知对话结束（VAD 检测到静音）
{
  "type": "speech_end",
  "timestamp": 1718123456789
}

// 4. 暂停/恢复 摄像头
{
  "type": "camera_control",
  "action": "pause" | "resume"
}

// ===== 云侧 → 端侧 =====

// 1. AI 文字回复
{
  "type": "response_text",
  "content": "你面前是一杯咖啡...",
  "timestamp": 1718123457000
}

// 2. AI 语音回复
{
  "type": "response_audio",
  "data": "<base64 encoded audio>",
  "format": "mp3",
  "timestamp": 1718123457000
}

// 3. 视觉描述结果
{
  "type": "vision_description",
  "content": "画面中有一杯咖啡、一台笔记本电脑...",
  "frame_id": "xxx"
}

// 4. 状态通知
{
  "type": "status",
  "state": "listening" | "thinking" | "speaking" | "idle"
}

// 5. 错误
{
  "type": "error",
  "message": "...",
  "code": "RATE_LIMIT"
}
```

### 3.4 客户端详细设计

**技术栈**：React 18 + TypeScript + Vite

**组件树**：

```
App
├── CameraView          — 摄像头预览 + 帧截图能力
│   ├── VideoElement    — 播放摄像头流
│   └── FrameIndicator  — 显示"AI 正在看"的帧
├── ConversationPanel   — 对话气泡列表
│   └── MessageBubble   — 单条消息（用户/AI）
├── ControlBar          — 底部控制栏
│   ├── TalkButton      — 按住说话 / 点击切换
│   ├── VADToggle       — VAD 自动模式开关
│   └── CameraToggle    — 暂停/恢复摄像头
└── StatusIndicator     — AI 状态（待机/听/看/想/说）
```

**核心 Hooks**：

| Hook | 职责 |
|------|------|
| `useCamera` | 打开/关闭摄像头，获取 MediaStream |
| `useMicrophone` | 打开/关闭麦克风，获取音频流 |
| `useVAD` | 基于 AudioWorklet 的实时语音活动检测 |
| `useFrameCapture` | 定时从 video 元素截图，做帧差对比 |
| `useWebSocket` | WebSocket 连接管理，消息收发，自动重连 |
| `useConversation` | 对话状态管理，历史记录 |
| `useAudioPlayer` | 接收并播放 TTS 返回的音频 |

**端侧预处理逻辑**：

```
帧差检测 (Frame Differencing):
  1. 每隔 500ms 从 video 取一帧到离屏 Canvas
  2. 缩放到 64x64 做像素灰度对比
  3. 差异 > 阈值(5%) → 标记 changed=true，发送原图
  4. 差异 <= 阈值 → 复用缓存描述，不发请求

VAD (语音活动检测):
  - 方案 A (推荐): 使用 @ricky0123/vad-web (ONNX 本地推理)
  - 方案 B: AudioWorklet 计算 RMS 能量值 + 阈值判断
  - 检测到 speech_start → 开始采集音频
  - 检测到 speech_end (连续静音 1.5s) → 发送音频到云侧
```

### 3.5 云侧详细设计

**技术栈**：Python 3.11 + FastAPI + asyncio + OpenAI SDK

**目录结构**：

```
server/
├── main.py                  # FastAPI 入口，挂载 WebSocket 路由
├── config.py                # 环境变量配置（API Key 等）
├── orchestrator.py          # 核心编排逻辑
├── services/
│   ├── vision.py            # Vision API 封装
│   ├── speech_to_text.py    # Whisper STT 封装
│   ├── chat.py              # GPT-4o 对话封装
│   └── text_to_speech.py    # TTS 封装
├── session.py               # 会话管理器（对话历史、视觉缓存）
└── types.py                 # 消息类型定义
```

**编排器核心逻辑**（伪代码）：

```python
class Orchestrator:
    async def handle_message(self, ws, msg):
        session = self.sessions[ws.id]

        match msg["type"]:
            case "audio":
                # 1. STT 语音转文字
                user_text = await stt.transcribe(msg["data"])
                session.add_user_message(user_text)

                # 2. 获取当前视觉上下文
                vision_desc = session.get_cached_vision()

                # 3. 并行调用: LLM 对话 + TTS
                prompt = self.build_prompt(vision_desc, session.history)
                reply_task = chat.generate(prompt)
                tts_task = asyncio.create_task(...)  # 稍后用

                reply_text = await reply_task
                session.add_assistant_message(reply_text)

                # 4. 发送文字 + TTS 音频
                await ws.send_json({"type": "response_text", "content": reply_text})
                audio = await tts.generate(reply_text)
                await ws.send_json({"type": "response_audio", "data": audio})

            case "frame":
                if msg["changed"]:
                    vision_desc = await vision.describe(msg["data"])
                    session.cache_vision(vision_desc)
                    # 可选：不发描述，只等下次对话时用
```

**成本控制要点在云侧落地**：

| 控制点 | 实现方式 |
|--------|----------|
| 帧采样 | 端侧已控制间隔，云侧不额外处理 |
| 帧差检测 | 端侧带 `changed` 字段，false 时云侧直接跳过 Vision API |
| VAD | 端侧只发有效语音段，云侧直接做 STT |
| 模型分级 | Vision 调 `gpt-4o-mini`，Chat 调 `gpt-4o` |
| 上下文裁剪 | SessionManager 保留最近 6 轮，超过做摘要压缩 |
| 异步并行 | STT 和 Vision 并行调用，减少用户等待 |

---

## 四、开发计划

| 阶段 | 内容 | 预估 |
|------|------|------|
| Phase 1 | 客户端骨架：React 项目 + 摄像头/麦克风采集 + 基础 UI | 第一天 |
| Phase 2 | 云侧骨架：FastAPI + WebSocket + OpenAI SDK 连通 | 第一天 |
| Phase 3 | 串联联调：端到端跑通"说话→识别→视觉→回复→播报" | 第二天 |
| Phase 4 | 端侧优化：VAD + 帧差检测 + 状态指示器 | 第二天 |
| Phase 5 | 体验打磨：对话历史、摄像头控制、异常处理 | 第三天 |

---

## 五、技术选型总结

| 层 | 技术 | 理由 |
|----|------|------|
| 客户端框架 | React 18 + TypeScript + Vite | 生态好，Web API 兼容佳 |
| 视频采集 | MediaStream API (getUserMedia) | 浏览器原生，零依赖 |
| 音频处理 | Web Audio API + AudioWorklet | 高性能低延迟音频处理 |
| 帧差检测 | OffscreenCanvas + ImageData | 纯浏览器端，不影响 UI 线程 |
| VAD | @ricky0123/vad-web (ONNX) | 端侧推理，零成本，隐私安全 |
| 通信 | WebSocket | 全双工低延迟，单一长连接 |
| 服务端 | Python FastAPI | 异步支持好，AI SDK 生态全 |
| Vision | OpenAI GPT-4o-mini | 便宜（$0.15/1M input），视觉能力强 |
| STT | OpenAI Whisper-1 | $0.006/分钟，准确率高 |
| Chat | OpenAI GPT-4o | 多模态推理，对话自然 |
| TTS | OpenAI TTS-1 | $0.015/1K字符，音色自然 |

---

*文档版本：v1.0 — 待实现*

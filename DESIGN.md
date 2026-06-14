# AI 视觉对话助手 — 设计计划书

---

## 一、用户故事

### 计划实现 & 实际实现对照

| 编号 | 用户故事 | 优先级 | 计划 | 实际 |
|------|----------|--------|------|------|
| US-01 | 打开应用即看到摄像头实时预览画面，确认 AI 能看到自己 | P0 | ✅ | ✅ |
| US-02 | 按住按钮说话，松手后 AI 自动识别语音并给出回复 | P0 | ✅ | ✅ |
| US-03 | AI 能描述摄像头中看到的物体/场景，结合我说的话做回应 | P0 | ✅ | ✅ |
| US-04 | AI 的回复以**语音播报**出来，同时显示文字 | P0 | ✅ | ❌ (协议预留, 未实现 TTS) |
| US-05 | 对话历史以聊天气泡形式展示，可滚动回溯 | P0 | ✅ | ✅ (v2: 字幕框 + 右侧面板可滚动) |
| US-06 | 支持**自动检测说话结束**（VAD 模式），无需按键也可对话 | P1 | ✅ | ✅ |
| US-07 | 对话中可看到 AI "正在看"的那一帧截图 | P1 | ✅ | ❌ |
| US-08 | 暂停/恢复摄像头，保护隐私 | P1 | ✅ | ✅ |
| US-09 | 对话过程中展示 AI 的状态（正在听/正在看/正在思考/正在说） | P1 | ✅ | ✅ |
| US-10 | 屏幕共享模式：分享屏幕内容让 AI 辅助操作 | P2 | ❌ | ❌ |
| US-11 | 对话历史持久化存储，刷新不丢失 | P2 | ❌ | ✅ (Redis 双写, TTL 30min) |
| US-12 | 多语言支持（中/英切换） | P3 | ❌ | ❌ |
| US-13 | 离线降级：网络断开时用本地模型做基础 STT | P3 | ❌ | ❌ |

**说明**：P0 为 MVP 必做，P1 为体验增强，P2/P3 留待后续迭代。当前已覆盖 P0+P1 共 8/9 个（TTS 和截图待实现）。

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
| C-05 | **上下文窗口裁剪** | 对话历史只保留最近 N 轮，超过的做摘要压缩而非全量携带 | 控制每轮 token 消耗 | ✅ | ✅ |
| C-06 | **模型分级策略** | 闲聊/游戏用 glm-4-flash，技术/画面用 deepseek-chat | 高频场景降成本 | ✅ | ✅ (LangChain4j 多模型 Bean) |
| C-07 | **音频格式压缩** | STT 前将音频转为低码率 opus/mono/16kHz，减少传输和处理量 | 带宽+延迟优化 | ❌ | ❌ |
| C-08 | **TTS 缓存** | 相同回复文本不重复调 TTS，复用已生成的音频 | 节省 TTS 调用 | ❌ | ❌ |
| C-09 | **空闲会话回收** | 超过 N 分钟无交互自动断开 WebSocket，释放资源 | 节省服务端资源 | ❌ | ✅ (30min IDLE_TIMEOUT + Redis TTL) |
| C-10 | **本地 STT 降级** | 高并发时切换本地 Whisper.cpp 处理 STT，削峰填谷 | 降低云 STT 费用 | ❌ | ❌ |

**核心思路**：**端侧做过滤，云侧做推理**。让端侧充当"守门员"，把无价值的请求拦截在本地。

---

## 三、端云架构设计

### 3.1 总体架构图 (v2 实际实现)

```
┌─────────────────────────────────────────────────────────────────────┐
│                      BROWSER (客户端 - React + TypeScript)           │
│                                                                      │
│  ┌──────────┐  ┌──────────┐  ┌────────────┐  ┌──────────────────┐  │
│  │ 摄像头    │  │ 麦克风    │  │ Canvas     │  │ Web Audio API    │  │
│  │ 5fps+帧差 │  │ PCM+VAD  │  │ 帧截图     │  │ 16kHz单声道采集  │  │
│  └────┬─────┘  └────┬─────┘  └──────┬─────┘  └────────┬─────────┘  │
│       │              │               │                 │             │
│       ▼              ▼               ▼                 ▼             │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                 WebSocket 客户端 (单一长连接)                   │   │
│  │  消息类型: FRAME_DATA | AUDIO_DATA | SPEECH_START/END | PING  │   │
│  └──────────────────────────┬───────────────────────────────────┘   │
└─────────────────────────────┼───────────────────────────────────────┘
                              │  WebSocket (ws://)
                              │
┌─────────────────────────────▼───────────────────────────────────────┐
│                  SERVER (云侧 - Java 17 + Spring Boot 3.3)            │
│                                                                      │
│  ┌──────────────────────────────┐  ┌────────────────────────────┐   │
│  │ ConversationWSHandler        │  │ SessionManager             │   │
│  │ (消息路由 8种C2S→8种S2C)     │  │ (会话CRUD + Redis双写)     │   │
│  └──────────────┬───────────────┘  └────────────┬───────────────┘   │
│                 │                               │                    │
│                 ▼                               ▼                    │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │               EpisodeConsumer (串行消费线程)                    │   │
│  │  • VISION事件 → 智谱GLM-4V          • SPEECH_BATCH → 完整管线 │   │
│  └─────────────────┬────────────────────────────────────────────┘   │
│                    │                                                 │
│                    ▼                                                 │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                  Orchestrator (编排层)                          │   │
│  │   SpeechSanitizer → VisionStructurer → IntentRecognizer        │   │
│  │   → AgentRouter → Agent.handle() → ChatResponse               │   │
│  └───────┬──────────────────────────┬────────────────────────────┘   │
│          │                          │                                 │
│          ▼                          ▼                                 │
│  ┌────────────────┐  ┌────────────────────────────────────────┐     │
│  │ Baidu ASR (STT)│  │ LangChain4j ChatLanguageModel (LLM)     │     │
│  │ PCM流式识别     │  │ • deepseekModel  (Vision/Knowledge/Game)│     │
│  └────────────────┘  │ • zhipuFlashModel (Conversation)        │     │
│                      │ • zhipu7Model     (高紧急度)             │     │
│                      └────────────────────────────────────────┘     │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    Redis (会话持久化)                           │   │
│  │  avca:hist:{sid} → List     avca:sum:{sid} → String           │   │
│  │  avca:idiom:{sid} → List    TTL: 30min                        │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 Agent 架构 (v2)

```
用户语音 → STT文本 → IntentRecognizer (DeepSeek)
                           │
                           ▼  intent + urgency + confidence
                    AgentRouter.route()
                           │
          ┌────────┬───────┼────────┬────────┐
          ▼        ▼       ▼        ▼
    VisionAgent  Knowledge Conversation GameAgent
    (deepseek)  Agent       Agent    (deepseek)
                (deepseek)  (glm-flash)
          │        │       │        │
          └────────┴───────┴────────┘
                           │
                    ChatResponse
                     ├── text  → 前端字幕框 + 对话面板
                     ├── action → 前端表情动画（预留）
                     └── idiom  → session.addUsedIdiom() → Redis
```

### 3.3 数据流详解

```
[用户说话]
    │
    ▼
[端侧: VAD检测] ─── 无语音 → 忽略
    │ 有语音
    ▼
[端侧: PCM采集] → WebSocket AUDIO_DATA → [百度STT] → 文本
    │                                          │
    ▼                   并行                    ▼
[端侧: 摄像头5fps] ──→ WebSocket FRAME_DATA    [云侧: STT onFinal]
    │                      │                   │
    ▼                      ▼                   ▼ isSpeaking?→缓冲
[帧差检测]           [静默→VISION事件入队]    [语音结束→SPEECH_BATCH]
                         │                   │
                         └───────┬───────────┘
                                 ▼
                         EpisodeConsumer (串行消费)
                                 │
                    ┌────────────┼────────────┐
                    ▼            ▼            ▼
              Vision API   IntentRecognizer  SpeechSanitizer
              (GLM-4V)     (DeepSeek)       + VisionStructurer
                    │            │            │
                    └────────────┼────────────┘
                                 ▼
                          Agent.handle()
                          ┌─────┴─────┐
                          │ LangChain4j│
                          │ ChatModel  │
                          └─────┬─────┘
                                 ▼
                          ChatResponse
                    ┌─────────┼─────────┐
                    ▼         ▼         ▼
              前端推送    回填轮次    历史压缩
           RESPONSE_TEXT   +agentType  +摘要追加
            +Redis同步    +Redis同步   +Redis同步
```

### 3.4 通信协议 (v2 实际)

WebSocket JSON 消息，15 种类型（8 C2S + 7 S2C）：

```json
// ===== 客户端 → 服务端 =====
// CONNECTION_INIT | CAMERA_CONTROL | MICROPHONE_CONTROL
// FRAME_DATA | AUDIO_DATA | SPEECH_START | SPEECH_END | PING

// ===== 服务端 → 客户端 =====
// CONNECTION_ACK | RESPONSE_TEXT | RESPONSE_AUDIO(预留)
// VISION_RESULT | STATUS_UPDATE | ERROR | PONG
```

### 3.5 技术栈 (v2 实际)

| 层 | 技术 |
|----|------|
| 前端框架 | React 18 + TypeScript + Vite |
| 前端样式 | Tailwind CSS 3.4 |
| 后端框架 | Spring Boot 3.3.1 + Java 17 |
| WebSocket | Spring TextWebSocketHandler |
| LLM 调用 | LangChain4j 0.36.2 (`langchain4j-open-ai`) |
| 模型 | DeepSeek V3 (deepseek-chat) + 智谱 GLM-4 (flash/4.7) |
| 视觉分析 | 智谱 GLM-4V (HTTP) |
| STT | 百度 ASR (REST API) |
| 会话存储 | Redis (内存双写 + JSON 序列化, TTL 30min) |
| 序列化 | Jackson |
| 代码简化 | Lombok |

### 3.6 依赖

```xml
<!-- server/pom.xml 核心依赖 -->
<dependency>spring-boot-starter-web</dependency>
<dependency>spring-boot-starter-websocket</dependency>
<dependency>spring-boot-starter-data-redis</dependency>
<dependency>dev.langchain4j:langchain4j:0.36.2</dependency>
<dependency>dev.langchain4j:langchain4j-open-ai:0.36.2</dependency>
```

---

### 3.7 客户端详细设计

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

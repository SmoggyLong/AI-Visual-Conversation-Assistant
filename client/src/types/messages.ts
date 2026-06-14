// ============================================================
// 消息类型枚举 — 与后端 MessageType.java 严格保持一致
// ============================================================

/** 客户端 → 服务端 */
export type ClientMessageType =
  | 'CONNECTION_INIT'   // 初始化连接，携带设备信息
  | 'CAMERA_CONTROL'    // 摄像头开关控制
  | 'MICROPHONE_CONTROL'// 麦克风开关控制
  | 'FRAME_DATA'        // 视频帧数据（base64 JPEG）
  | 'AUDIO_DATA'        // 音频数据块（base64）
  | 'SPEECH_START'      // VAD 检测到语音开始
  | 'SPEECH_END'        // VAD 检测到语音结束
  | 'PING';             // 心跳保活

/** 服务端 → 客户端 */
export type ServerMessageType =
  | 'CONNECTION_ACK'    // 连接确认，返回 sessionId
  | 'RESPONSE_TEXT'     // AI 文字回复
  | 'RESPONSE_AUDIO'    // AI 语音回复
  | 'VISION_RESULT'     // 视觉识别结果
  | 'STATUS_UPDATE'     // 服务端状态更新
  | 'ERROR'             // 错误信息
  | 'PONG';             // 心跳响应

export type MessageType = ClientMessageType | ServerMessageType;

// ============================================================
// 基础消息信封
// ============================================================

export interface Message<T = unknown> {
  type: MessageType;
  timestamp: number;
  sessionId: string;
  payload: T;
}

// ============================================================
// 客户端 → 服务端 Payload
// ============================================================

export interface ConnectionInitPayload {
  deviceInfo: {
    userAgent: string;
    platform: string;
    screenWidth: number;
    screenHeight: number;
  };
}

export interface CameraControlPayload {
  enabled: boolean;
  deviceId?: string;
}

export interface MicrophoneControlPayload {
  enabled: boolean;
  deviceId?: string;
}

export interface FrameDataPayload {
  format: 'jpeg';
  width?: number;
  height?: number;
  data?: string;
  changed: boolean;
  isSpeaking?: boolean;     // 是否在说话期间截取
  imageChecksum?: string;
  frames?: FrameItem[];
}

/** 批次中的单帧 */
export interface FrameItem {
  data: string;
  format: 'jpeg';
  checksum: string;
  offsetMs: number;        // 相对当前时刻的偏移（-1000 = 1秒前）
}

export interface AudioDataPayload {
  format: 'opus' | 'pcm';
  sampleRate: number;
  channels: number;
  data: string;          // base64
  duration: number;      // 秒
}

export interface SpeechEventPayload {
  timestamp: number;
}

export type ClientPayload =
  | ConnectionInitPayload
  | CameraControlPayload
  | MicrophoneControlPayload
  | FrameDataPayload
  | AudioDataPayload
  | SpeechEventPayload
  | null;

/** 消息类型 → Payload 映射，用于类型安全的 sendMessage */
export interface ClientPayloadMap {
  CONNECTION_INIT: ConnectionInitPayload;
  CAMERA_CONTROL: CameraControlPayload;
  MICROPHONE_CONTROL: MicrophoneControlPayload;
  FRAME_DATA: FrameDataPayload;
  AUDIO_DATA: AudioDataPayload;
  SPEECH_START: SpeechEventPayload;
  SPEECH_END: SpeechEventPayload;
  PING: null;
}

// ============================================================
// 服务端 → 客户端 Payload
// ============================================================

export interface ConnectionAckPayload {
  sessionId: string;
  serverTime: number;
}

export interface ResponseTextPayload {
  messageId: string;
  content: string;
  role: 'assistant';
  conversationRound: number;
}

export interface ResponseAudioPayload {
  messageId: string;
  text: string;          // 对应的文字
  format: 'mp3' | 'wav';
  data: string;          // base64
  duration: number;      // 秒
}

export interface VisionResultPayload {
  frameChecksum: string;
  description: string;
  detectedObjects: string[];
  timestamp: number;
}

export interface StatusUpdatePayload {
  state: 'idle' | 'listening' | 'thinking' | 'speaking' | 'watching' | 'error';
  detail: string;
}

export interface ErrorPayload {
  code: 'RATE_LIMIT' | 'AUTH_ERROR' | 'PROCESSING_ERROR' | 'INVALID_MESSAGE' | 'SESSION_EXPIRED';
  message: string;
}

export type ServerPayload =
  | ConnectionAckPayload
  | ResponseTextPayload
  | ResponseAudioPayload
  | VisionResultPayload
  | StatusUpdatePayload
  | ErrorPayload;

// ============================================================
// 设备状态
// ============================================================

export interface CameraState {
  enabled: boolean;
  deviceId: string | null;
  resolution: string | null;  // "1280x720"
  stream: MediaStream | null;
  error: string | null;
}

export interface MicrophoneState {
  enabled: boolean;
  deviceId: string | null;
  stream: MediaStream | null;
  error: string | null;
  audioLevel: number;  // 0.0 ~ 1.0
}

export interface DeviceCapabilities {
  hasCamera: boolean;
  hasMicrophone: boolean;
  cameras: MediaDeviceInfo[];
  microphones: MediaDeviceInfo[];
}

// ============================================================
// WebSocket 连接状态
// ============================================================

export type ConnectionState =
  | 'disconnected'
  | 'connecting'
  | 'connected'
  | 'reconnecting'
  | 'error';

// ============================================================
// 对话消息（本地 UI 使用，与服务端协议无关）
// ============================================================

/** 对话消息 */
export interface ConversationMessage {
  /** 消息唯一 ID */
  id: string;
  /** 消息角色 */
  role: 'user' | 'assistant' | 'system';
  /** 消息文本内容 */
  text: string;
  /** 是否为中间结果（用户正在说话，文字尚未最终确定） */
  isInterim?: boolean;
  /** 消息时间戳（毫秒） */
  timestamp: number;
}

// ============================================================
// 知识库文档类型
// ============================================================

export interface KnowledgeDoc {
  docId: string;
  title: string;
  type: 'SOP' | 'GENERAL';
  sourceFile: string;
  keywords: string[];
  totalChunks: number;
  createdAt?: string;
}

export interface KnowledgeStats {
  totalDocs: number;
  totalChunks: number;
  sopCount: number;
  generalCount: number;
}

export interface KnowledgeReloadResult {
  status: string;
  files: number;
  newDocs: number;
  newChunks: number;
}

export interface DocInput {
  title: string;
  content: string;
  type: 'SOP' | 'GENERAL';
  keywords: string;
}

// ============================================================
// 评测类型
// ============================================================

export interface JudgeScores {
  relevance: number;
  accuracy: number;
  completeness: number;
  helpfulness: number;
  overall: number;
}

export interface AgentEvalSummary {
  cases: number;
  passedChecks: number;
  avgScores: JudgeScores;
}

export interface EvalReport {
  totalCases: number;
  passedCases: number;
  elapsedMs: number;
  baselineScore: number;
  byAgent: Record<string, AgentEvalSummary>;
  details: Record<string, EvalCaseResult[]>;
}

export interface EvalCaseResult {
  caseId: string;
  agent: string;
  query: string;
  intent: string;
  response: string;
  scores: JudgeScores | null;
  checks: Record<string, boolean>;
  retrievedSource: string | null;
  error: string | null;
}

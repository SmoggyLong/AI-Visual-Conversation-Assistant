import { useCallback, useState, useEffect, useRef } from 'react';
import { useCamera } from './hooks/useCamera';
import { useMicrophone } from './hooks/useMicrophone';
import { useWebSocket } from './hooks/useWebSocket';
import { useAudioRecorder } from './hooks/useAudioRecorder';
import { CameraView } from './components/CameraView';
import { ControlBar } from './components/ControlBar';
import { StatusIndicator } from './components/StatusIndicator';
import { SpeechOverlay } from './components/SpeechOverlay';
import { ConversationPanel, nextMessageId } from './components/ConversationPanel';
import { KnowledgePanel } from './components/KnowledgePanel';
import { EvalPanel } from './components/EvalPanel';
import { captureFrame, hasFrameChanged } from './utils/frameCapture';
import type {
  ConversationMessage,
  StatusUpdatePayload,
  ServerPayload,
  Message,
} from './types/messages';

/**
 * AI 视觉对话助手 —— 流式 STT 版。
 *
 * 语音链路（新）：
 * 麦克风 → useAudioRecorder(PCM) → WebSocket AUDIO_DATA → 后端 Baidu STT
 *   → 后端 RESPONSE_TEXT 返回 → 前端 ConversationPanel 显示
 *
 * Google Web Speech API 已停用，由后端百度服务替换。
 */
export default function App() {
  const { connectionState, sendMessage, onMessage } = useWebSocket();

  const [serverStatus, setServerStatus] = useState<StatusUpdatePayload['state'] | null>(null);
  const [conversationMessages, setConversationMessages] = useState<ConversationMessage[]>([]);
  const [interimText, setInterimText] = useState('');
  const [assistantText, setAssistantText] = useState('');
  const [currentAgent, setCurrentAgent] = useState<string | null>(null);
  const [rightTab, setRightTab] = useState<'chat' | 'knowledge' | 'eval'>('chat');

  const camera = useCamera({
    onStateChange: (payload) => sendMessage('CAMERA_CONTROL', payload),
  });

  const microphone = useMicrophone({
    onStateChange: (payload) => sendMessage('MICROPHONE_CONTROL', payload),
  });

  // === 视频帧定时发送（5fps 连续帧 + 环形缓冲）===
  const prevPixelsRef = useRef<ImageData | null>(null);
  const frameBufferRef = useRef<{ data: string; checksum: string; timestamp: number }[]>([]);
  const BUFFER_MAX = 60;  // 保留最近 12 秒（5fps × 12s，覆盖最长一句话）
  const SEND_COUNT = 5;   // 每次发送最近 1 秒（5 帧）
  const KEY_FRAME_COUNT = 5;  // 说话期间均匀采样帧数（覆盖全时间跨度）

  /** 从帧缓冲中均匀采样 count 帧，始终包含首尾帧，保持时间跨度 */
  const sampleKeyFrames = <T,>(frames: T[], count: number): T[] => {
    if (frames.length <= count) return frames.slice();
    const result: T[] = [];
    const step = (frames.length - 1) / (count - 1);
    for (let i = 0; i < count; i++) {
      result.push(frames[Math.round(i * step)]);
    }
    return result;
  };

  useEffect(() => {
    if (!camera.state.enabled) return;
    frameBufferRef.current = [];

    const timer = setInterval(() => {
      if (!camera.videoRef.current) return;
      const captured = captureFrame(camera.videoRef.current, 480);
      if (!captured) return;

      const now = Date.now();

      // 帧入缓冲
      const buf = frameBufferRef.current;
      buf.push({ data: captured.fullFrame.data!, checksum: captured.fullFrame.imageChecksum!, timestamp: now });
      if (buf.length > BUFFER_MAX) frameBufferRef.current = buf.slice(-BUFFER_MAX);

      // 帧差检测（说话期间跳过容差，全帧发送）
      const speaking = isSpeakingRef.current;
      const changed = hasFrameChanged(captured.thumbPixels, prevPixelsRef.current);
      prevPixelsRef.current = captured.thumbPixels;
      if (!speaking && !changed) return;

      // 说话期间：均匀采样关键帧（体积恒定），静默时：发最近 N 帧
      const batch = speaking
        ? sampleKeyFrames(frameBufferRef.current, KEY_FRAME_COUNT)
        : frameBufferRef.current.slice(-SEND_COUNT);
      sendMessage('FRAME_DATA', {
        format: 'jpeg',
        changed: speaking || changed,
        isSpeaking: speaking,
        imageChecksum: captured.fullFrame.imageChecksum,
        frames: batch.map((f, i) => ({
          data: f.data,
          format: 'jpeg',
          checksum: f.checksum,
          offsetMs: -(batch.length - 1 - i) * 200,
        })),
      });
    }, 200); // 200ms = 5fps

    return () => clearInterval(timer);
  }, [camera.state.enabled, sendMessage]);

  // === 音频录制 + VAD 检测 ===
  const handleAudioChunk = useCallback((base64Pcm: string) => {
    sendMessage('AUDIO_DATA', {
      format: 'pcm',
      sampleRate: 16000,
      channels: 1,
      data: base64Pcm,
      duration: 0.2,
    });
  }, [sendMessage]);

  const handleSpeechStart = useCallback(() => {
    sendMessage('SPEECH_START', { timestamp: Date.now() });
    setInterimText('');
    setAssistantText('');
  }, [sendMessage]);

  const handleSpeechEnd = useCallback(() => {
    sendMessage('SPEECH_END', { timestamp: Date.now() });
  }, [sendMessage]);

  const speechState = useAudioRecorder({
    enabled: microphone.state.enabled,
    stream: microphone.state.stream,
    onAudioChunk: handleAudioChunk,
    onSpeechStart: handleSpeechStart,
    onSpeechEnd: handleSpeechEnd,
  });
  const isSpeakingRef = useRef(false);
  isSpeakingRef.current = speechState.isSpeaking;

  const audioRef = useRef<HTMLAudioElement | null>(null);
  const welcomedRef = useRef(false);
  const playAudio = (base64Mp3: string) => {
    if (!audioRef.current) audioRef.current = new Audio();
    audioRef.current.src = 'data:audio/mp3;base64,' + base64Mp3;
    audioRef.current.play().catch(() => {});
  };

  /** 浏览器内置语音合成（根据表情调整语调） */
  const speechSynthReady = useRef(false);
  const pendingSpeakRef = useRef<string | null>(null);

  // 首次用户点击时预热 SpeechSynthesis（浏览器自动播放策略）
  useEffect(() => {
    const warmup = () => {
      if (!speechSynthReady.current && window.speechSynthesis) {
        window.speechSynthesis.cancel();
        const u = new SpeechSynthesisUtterance('');
        u.volume = 0;
        window.speechSynthesis.speak(u);
        speechSynthReady.current = true;
        // 处理积压的待播消息
        if (pendingSpeakRef.current) {
          doSpeak(pendingSpeakRef.current, 'happy');
          pendingSpeakRef.current = null;
        }
      }
    };
    document.addEventListener('click', warmup, { once: true });
    return () => document.removeEventListener('click', warmup);
  }, []);

  const doSpeak = (text: string, expression?: string) => {
    if (!window.speechSynthesis) return;
    window.speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(text);
    u.lang = 'zh-CN';

    switch (expression) {
      case 'happy':
        u.rate = 1.15; u.pitch = 1.15; break;
      case 'curious':
        u.rate = 0.95; u.pitch = 1.05; break;
      case 'surprised':
        u.rate = 1.05; u.pitch = 1.25; break;
      case 'thinking':
        u.rate = 0.85; u.pitch = 0.95; break;
      default:
        u.rate = 1.0; u.pitch = 1.0; break;
    }
    u.volume = 0.8;
    window.speechSynthesis.speak(u);
  };

  const speakText = (text: string, expression?: string) => {
    if (!speechSynthReady.current) {
      pendingSpeakRef.current = text;
      return;
    }
    doSpeak(text, expression);
  };

  // === 监听后端消息 ===
  onMessage(useCallback((msg: Message<ServerPayload>) => {
    switch (msg.type) {
      case 'STATUS_UPDATE': {
        setServerStatus((msg.payload as StatusUpdatePayload).state);
        break;
      }
      case 'RESPONSE_AUDIO': {
        const payload = msg.payload as { data: string };
        if (payload.data) playAudio(payload.data);
        break;
      }
      case 'RESPONSE_TEXT': {
        const payload = msg.payload as { content: string; role: string; messageId: string; agent?: string; expression?: string };
        const content = payload.content;

        // 中间结果（流式识别进行中）
        if (content.startsWith('[INTERIM]')) {
          setInterimText(content.substring(9));
          return;
        }

        // 欢迎消息去重（React Strict Mode 双挂载导致）
        if (payload.messageId?.startsWith('welcome_')) {
          if (welcomedRef.current) break;
          welcomedRef.current = true;
        }

        // 区分 assistant 回复 vs user 识别结果
        const trimmed = content.trim();
        if (!trimmed) break;  // 空文本不记录
        const role = payload.role === 'assistant' ? 'assistant' : 'user';
        setConversationMessages((prev) => [
          ...prev,
          {
            id: nextMessageId(),
            role,
            text: trimmed,
            timestamp: Date.now(),
            agent: payload.agent || undefined,
          },
        ]);
        if (role === 'assistant') {
          setAssistantText(content.trim());
          setCurrentAgent(payload.agent || null);
          speakText(trimmed, payload.expression);
        }
        setInterimText('');
        break;
      }
    }
  }, []));

  return (
    <div className="h-screen flex flex-col bg-gray-950 text-white overflow-hidden">
      <StatusIndicator
        connectionState={connectionState}
        serverStatus={serverStatus}
        cameraEnabled={camera.state.enabled}
        micEnabled={microphone.state.enabled}
        agentName={currentAgent}
      />

      <div className="flex-1 flex min-h-0">
        <div className="flex-[7] flex flex-col relative min-w-0">
          <CameraView camera={camera} />
          <SpeechOverlay
            interimText={interimText}
            assistantText={assistantText}
            isListening={microphone.state.enabled}
            isNetworkUnavailable={false}
          />
        </div>
        <div className="flex-[3] min-w-[280px] max-w-[400px] min-h-0 overflow-hidden flex flex-col">
          {/* Tab 切换 */}
          <div className="flex-shrink-0 flex border-b border-white/[0.04]">
            <button
              onClick={() => setRightTab('chat')}
              className={`flex-1 py-2.5 text-[10px] font-medium tracking-wider uppercase transition-colors ${
                rightTab === 'chat'
                  ? 'text-blue-400/80 border-b border-blue-400/40 bg-blue-400/[0.02]'
                  : 'text-gray-600 hover:text-gray-400'
              }`}
            >
              对话
            </button>
            <button
              onClick={() => setRightTab('knowledge')}
              className={`flex-1 py-2.5 text-[10px] font-medium tracking-wider uppercase transition-colors ${
                rightTab === 'knowledge'
                  ? 'text-emerald-400/80 border-b border-emerald-400/40 bg-emerald-400/[0.02]'
                  : 'text-gray-600 hover:text-gray-400'
              }`}
            >
              知识库
            </button>
            <button
              onClick={() => setRightTab('eval')}
              className={`flex-1 py-2.5 text-[10px] font-medium tracking-wider uppercase transition-colors ${
                rightTab === 'eval'
                  ? 'text-orange-400/80 border-b border-orange-400/40 bg-orange-400/[0.02]'
                  : 'text-gray-600 hover:text-gray-400'
              }`}
            >
              评测
            </button>
          </div>
          {rightTab === 'chat' ? (
            <ConversationPanel messages={conversationMessages} />
          ) : rightTab === 'knowledge' ? (
            <KnowledgePanel />
          ) : (
            <EvalPanel />
          )}
        </div>
      </div>

      <ControlBar
        camera={camera}
        microphone={microphone}
        connectionState={connectionState}
      />
    </div>
  );
}

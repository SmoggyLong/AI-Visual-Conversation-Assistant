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

  const camera = useCamera({
    onStateChange: (payload) => sendMessage('CAMERA_CONTROL', payload),
  });

  const microphone = useMicrophone({
    onStateChange: (payload) => sendMessage('MICROPHONE_CONTROL', payload),
  });

  // === 视频帧定时发送（5fps 连续帧 + 环形缓冲）===
  const prevPixelsRef = useRef<ImageData | null>(null);
  const frameBufferRef = useRef<{ data: string; checksum: string; timestamp: number }[]>([]);
  const BUFFER_MAX = 10;   // 保留最近 2 秒（5fps × 2s）
  const SEND_COUNT = 5;    // 每次发送最近 1 秒（5 帧）

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

      // 帧差检测
      const changed = hasFrameChanged(captured.thumbPixels, prevPixelsRef.current);
      prevPixelsRef.current = captured.thumbPixels;
      if (!changed) return;

      // 取最近 SEND_COUNT 帧，带时间偏移
      const batch = frameBufferRef.current.slice(-SEND_COUNT);
      sendMessage('FRAME_DATA', {
        format: 'jpeg',
        changed: true,
        imageChecksum: captured.fullFrame.imageChecksum,
        frames: batch.map((f, i) => ({
          data: f.data,
          format: 'jpeg',
          checksum: f.checksum,
          offsetMs: -(batch.length - 1 - i) * 200,  // 相对当前帧的时间偏移
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
  }, [sendMessage]);

  const handleSpeechEnd = useCallback(() => {
    sendMessage('SPEECH_END', { timestamp: Date.now() });
  }, [sendMessage]);

  useAudioRecorder({
    enabled: microphone.state.enabled,
    stream: microphone.state.stream,
    onAudioChunk: handleAudioChunk,
    onSpeechStart: handleSpeechStart,
    onSpeechEnd: handleSpeechEnd,
  });

  // === 监听后端消息 ===
  onMessage(useCallback((msg: Message<ServerPayload>) => {
    switch (msg.type) {
      case 'STATUS_UPDATE': {
        setServerStatus((msg.payload as StatusUpdatePayload).state);
        break;
      }
      case 'RESPONSE_TEXT': {
        const payload = msg.payload as { content: string; messageId: string };
        const content = payload.content;

        // 中间结果（流式识别进行中）
        if (content.startsWith('[INTERIM]')) {
          setInterimText(content.substring(9));
          return;
        }

        // 最终结果 → 写入对话记录
        setConversationMessages((prev) => [
          ...prev,
          {
            id: nextMessageId(),
            role: 'user',
            text: content.trim(),
            timestamp: Date.now(),
          },
        ]);
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
      />

      <div className="flex-1 flex min-h-0">
        <div className="flex-[7] flex flex-col relative min-w-0">
          <CameraView camera={camera} />
          <SpeechOverlay
            interimText={interimText}
            isListening={microphone.state.enabled}
            isNetworkUnavailable={false}
          />
        </div>
        <div className="flex-[3] min-w-[280px] max-w-[400px]">
          <ConversationPanel messages={conversationMessages} />
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

import { useCallback, useState } from 'react';
import { useCamera } from './hooks/useCamera';
import { useMicrophone } from './hooks/useMicrophone';
import { useWebSocket } from './hooks/useWebSocket';
import { useSpeechRecognition } from './hooks/useSpeechRecognition';
import { CameraView } from './components/CameraView';
import { ControlBar } from './components/ControlBar';
import { StatusIndicator } from './components/StatusIndicator';
import { SpeechOverlay } from './components/SpeechOverlay';
import { ConversationPanel, nextMessageId } from './components/ConversationPanel';
import type {
  ConversationMessage,
  StatusUpdatePayload,
  ServerPayload,
  Message,
} from './types/messages';

/**
 * AI 视觉对话助手 —— 玻璃拟态风格。
 *
 * 布局：
 * ┌──────────────────────────────────────────┐
 * │ StatusIndicator  (h-10)                  │
 * ├────────────────────────┬─────────────────┤
 * │ CameraView (70%)       │ 记录 (30%)       │
 * │                        │                 │
 * │  ┌─────────────────┐   │  [气泡列表]      │
 * │  │ SpeechOverlay   │   │                 │
 * │  └─────────────────┘   │                 │
 * ├────────────────────────┴─────────────────┤
 * │ ControlBar                               │
 * └──────────────────────────────────────────┘
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

  const handleFinalResult = useCallback((text: string) => {
    setConversationMessages((prev) => [...prev, {
      id: nextMessageId(),
      role: 'user',
      text: text.trim(),
      timestamp: Date.now(),
    }]);
    setInterimText('');
  }, []);

  const handleInterimResult = useCallback((text: string) => {
    setInterimText(text);
  }, []);

  const handleRecognitionError = useCallback((error: string) => {
    setConversationMessages((prev) => [...prev, {
      id: nextMessageId(),
      role: 'system',
      text: error,
      timestamp: Date.now(),
    }]);
  }, []);

  const speechRecognition = useSpeechRecognition({
    enabled: microphone.state.enabled,
    lang: 'zh-CN',
    onFinalResult: handleFinalResult,
    onInterimResult: handleInterimResult,
    onError: handleRecognitionError,
  });

  onMessage(useCallback((msg: Message<ServerPayload>) => {
    if (msg.type === 'STATUS_UPDATE') {
      setServerStatus((msg.payload as StatusUpdatePayload).state);
    }
  }, []));

  return (
    <div className="h-screen flex flex-col bg-gray-950 text-white overflow-hidden">
      {/* 顶部状态栏 */}
      <StatusIndicator
        connectionState={connectionState}
        serverStatus={serverStatus}
        cameraEnabled={camera.state.enabled}
        micEnabled={microphone.state.enabled}
      />

      {/* 主区域：摄像头 70% + 记录 30% */}
      <div className="flex-1 flex min-h-0">
        {/* 左侧：摄像头 + 浮字层 */}
        <div className="flex-[7] flex flex-col relative min-w-0">
          <CameraView camera={camera} />
          <SpeechOverlay
            interimText={interimText}
            isListening={speechRecognition.isListening}
            isNetworkUnavailable={speechRecognition.isNetworkUnavailable}
          />
        </div>

        {/* 右侧：对话记录 */}
        <div className="flex-[3] min-w-[280px] max-w-[400px]">
          <ConversationPanel messages={conversationMessages} />
        </div>
      </div>

      {/* 底部控制栏 */}
      <ControlBar
        camera={camera}
        microphone={microphone}
        connectionState={connectionState}
      />
    </div>
  );
}

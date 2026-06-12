import { useCamera } from '../hooks/useCamera';
import { useMicrophone } from '../hooks/useMicrophone';
import type { ConnectionState } from '../types/messages';

interface ControlBarProps {
  camera: ReturnType<typeof useCamera>;
  microphone: ReturnType<typeof useMicrophone>;
  connectionState: ConnectionState;
}

/**
 * 底部控制栏 —— 玻璃拟态风格的设备开关按钮。
 */
export function ControlBar({ camera, microphone, connectionState }: ControlBarProps) {
  const connected = connectionState === 'connected';

  return (
    <div className="flex items-center justify-center gap-4 px-6 py-4 border-t border-white/5 bg-gray-950/60 backdrop-blur-xl">
      {/* 麦克风按钮 */}
      <button
        onClick={microphone.toggle}
        disabled={!connected}
        className={`
          group relative flex flex-col items-center gap-1.5 w-20 py-2.5
          rounded-xl transition-all duration-300
          ${!connected ? 'opacity-30 cursor-not-allowed' : 'cursor-pointer'}
          ${microphone.state.enabled
            ? 'bg-green-500/10 hover:bg-green-500/15 border border-green-500/20 shadow-[0_0_20px_rgba(34,197,94,0.08)]'
            : 'bg-white/5 hover:bg-white/8 border border-white/5'
          }
        `}
      >
        <div className={`
          relative w-8 h-8 rounded-full flex items-center justify-center transition-colors duration-300
          ${microphone.state.enabled ? 'bg-green-500/20 text-green-400' : 'bg-white/5 text-gray-500'}
        `}>
          {microphone.state.enabled ? (
            <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 24 24">
              <path d="M12 14a3 3 0 003-3V5a3 3 0 10-6 0v6a3 3 0 003 3zm5-3a5 5 0 01-10 0H5a7 7 0 0014 0h-2zm-5 8a7 7 0 01-7-7H3a9 9 0 0018 0h-2a7 7 0 01-7 7z" />
            </svg>
          ) : (
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z" />
              <line x1="3" y1="3" x2="21" y2="21" strokeWidth={1.5} />
            </svg>
          )}
        </div>
        <span className={`text-[11px] font-medium tracking-wide ${microphone.state.enabled ? 'text-green-400/80' : 'text-gray-600'}`}>
          麦克风
        </span>
        {/* 脉冲光环 */}
        {microphone.state.enabled && (
          <span className="absolute inset-0 rounded-xl border border-green-400/10 animate-ping pointer-events-none" />
        )}
      </button>

      {/* 摄像头按钮 */}
      <button
        onClick={camera.toggle}
        disabled={!connected}
        className={`
          group relative flex flex-col items-center gap-1.5 w-20 py-2.5
          rounded-xl transition-all duration-300
          ${!connected ? 'opacity-30 cursor-not-allowed' : 'cursor-pointer'}
          ${camera.state.enabled
            ? 'bg-blue-500/10 hover:bg-blue-500/15 border border-blue-500/20 shadow-[0_0_20px_rgba(59,130,246,0.08)]'
            : 'bg-white/5 hover:bg-white/8 border border-white/5'
          }
        `}
      >
        <div className={`
          relative w-8 h-8 rounded-full flex items-center justify-center transition-colors duration-300
          ${camera.state.enabled ? 'bg-blue-500/20 text-blue-400' : 'bg-white/5 text-gray-500'}
        `}>
          {camera.state.enabled ? (
            <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 24 24">
              <path d="M15 10l4.5-2.5v9L15 14H5V8h10v2zm0 0V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2h8a2 2 0 002-2v-2l4.5 2.5V7.5L15 10z" />
            </svg>
          ) : (
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
              <line x1="3" y1="3" x2="21" y2="21" strokeWidth={1.5} />
            </svg>
          )}
        </div>
        <span className={`text-[11px] font-medium tracking-wide ${camera.state.enabled ? 'text-blue-400/80' : 'text-gray-600'}`}>
          摄像头
        </span>
        {camera.state.enabled && (
          <span className="absolute inset-0 rounded-xl border border-blue-400/10 animate-ping pointer-events-none" />
        )}
      </button>
    </div>
  );
}

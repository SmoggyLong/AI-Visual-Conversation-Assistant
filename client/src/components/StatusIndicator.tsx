import type { ConnectionState, StatusUpdatePayload } from '../types/messages';

interface StatusIndicatorProps {
  connectionState: ConnectionState;
  serverStatus: StatusUpdatePayload['state'] | null;
  cameraEnabled: boolean;
  micEnabled: boolean;
}

const connectionLabel: Record<ConnectionState, string> = {
  disconnected: '未连接',
  connecting: '连接中',
  connected: '在线',
  reconnecting: '重连中',
  error: '错误',
};

const connectionDot: Record<ConnectionState, string> = {
  disconnected: 'bg-gray-600',
  connecting: 'bg-yellow-400 animate-pulse',
  connected: 'bg-emerald-400 shadow-[0_0_6px_rgba(52,211,153,0.5)]',
  reconnecting: 'bg-yellow-400 animate-pulse',
  error: 'bg-red-400 shadow-[0_0_6px_rgba(248,113,113,0.5)]',
};

const serverStatusLabel: Record<StatusUpdatePayload['state'], string> = {
  idle: '待机',
  listening: '正在听',
  thinking: '思考中',
  speaking: '回复中',
  watching: '分析画面',
  error: '异常',
};

/**
 * 顶部状态指示器 —— 极简玻璃条。
 */
export function StatusIndicator({
  connectionState,
  serverStatus,
  cameraEnabled,
  micEnabled,
}: StatusIndicatorProps) {
  return (
    <div className="flex items-center justify-between px-5 py-2 border-b border-white/5 bg-gray-950/60 backdrop-blur-xl">
      <div className="flex items-center gap-4">
        {/* 连接状态 */}
        <div className="flex items-center gap-2">
          <span className={`w-2 h-2 rounded-full ${connectionDot[connectionState]}`} />
          <span className="text-[11px] text-gray-500 font-medium tracking-wide uppercase">
            {connectionLabel[connectionState]}
          </span>
        </div>

        {/* 服务端状态 */}
        {serverStatus && serverStatus !== 'idle' && (
          <>
            <span className="text-white/5 select-none">|</span>
            <span className="text-[11px] text-gray-500 animate-pulse">
              {serverStatusLabel[serverStatus]}
            </span>
          </>
        )}
      </div>

      {/* 设备指示 */}
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-1.5">
          <span className={`w-1 h-1 rounded-full ${cameraEnabled ? 'bg-blue-400/70' : 'bg-gray-700'}`} />
          <span className={`text-[10px] tracking-wide ${cameraEnabled ? 'text-blue-400/60' : 'text-gray-700'}`}>
            镜头
          </span>
        </div>
        <div className="flex items-center gap-1.5">
          <span className={`w-1 h-1 rounded-full ${micEnabled ? 'bg-emerald-400/70 animate-pulse' : 'bg-gray-700'}`} />
          <span className={`text-[10px] tracking-wide ${micEnabled ? 'text-emerald-400/60' : 'text-gray-700'}`}>
            麦克
          </span>
        </div>
      </div>
    </div>
  );
}

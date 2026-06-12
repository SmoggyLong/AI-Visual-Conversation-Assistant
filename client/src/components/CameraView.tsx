import { useCamera } from '../hooks/useCamera';

interface CameraViewProps {
  camera: ReturnType<typeof useCamera>;
}

/**
 * 摄像头预览组件 —— 三种状态各有独特视觉。
 */
export function CameraView({ camera }: CameraViewProps) {
  // 未开启
  if (!camera.state.enabled) {
    return (
      <div className="flex-1 flex items-center justify-center bg-gradient-to-b from-gray-900 to-gray-950">
        <div className="text-center">
          <div className="w-20 h-20 mx-auto mb-4 rounded-2xl bg-white/[0.02] border border-white/[0.04] flex items-center justify-center">
            <svg className="w-8 h-8 text-gray-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1}
                d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
            </svg>
          </div>
          <p className="text-gray-600 text-sm font-light tracking-wide">摄像头未开启</p>
        </div>
      </div>
    );
  }

  // 错误
  if (camera.state.error) {
    return (
      <div className="flex-1 flex items-center justify-center bg-gradient-to-b from-gray-900 to-gray-950">
        <div className="text-center">
          <div className="w-16 h-16 mx-auto mb-3 rounded-2xl bg-red-500/5 border border-red-500/10 flex items-center justify-center">
            <svg className="w-6 h-6 text-red-400/60" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L3.732 16.5c-.77.833.192 2.5 1.732 2.5z" />
            </svg>
          </div>
          <p className="text-red-400/60 text-sm">{camera.state.error}</p>
        </div>
      </div>
    );
  }

  // 正常播放
  return (
    <div className="flex-1 relative bg-black overflow-hidden">
      <video
        ref={camera.videoRef}
        autoPlay
        playsInline
        muted
        className="w-full h-full object-cover"
      />
      {/* 分辨率标签 */}
      {camera.state.resolution && (
        <span className="absolute top-3 right-3 px-2.5 py-1 rounded-lg bg-black/40 backdrop-blur-md border border-white/5 text-[10px] text-white/40 font-mono">
          {camera.state.resolution}
        </span>
      )}
    </div>
  );
}

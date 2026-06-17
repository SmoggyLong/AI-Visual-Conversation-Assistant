import { useState, useRef, useEffect } from 'react';
import { useCamera } from '../hooks/useCamera';
import { useMicrophone } from '../hooks/useMicrophone';
import type { ConnectionState } from '../types/messages';

interface ControlBarProps {
  camera: ReturnType<typeof useCamera>;
  microphone: ReturnType<typeof useMicrophone>;
  connectionState: ConnectionState;
}

/**
 * 底部控制栏 —— 设备开关按钮 + 多设备选择。
 */
export function ControlBar({ camera, microphone, connectionState }: ControlBarProps) {
  const connected = connectionState === 'connected';

  return (
    <div className="flex items-center justify-center gap-4 px-6 py-4 border-t border-white/5 bg-gray-950/60 backdrop-blur-xl">
      {/* ===== 麦克风区域 ===== */}
      <DeviceButton
        label="麦克风"
        enabled={microphone.state.enabled}
        disabled={!connected}
        colorClass="green"
        onToggle={microphone.toggle}
        devices={microphone.devices}
        currentDeviceId={microphone.state.deviceId}
        onSwitchDevice={microphone.switchDevice}
        audioLevel={microphone.state.audioLevel}
      />

      {/* ===== 摄像头区域 ===== */}
      <DeviceButton
        label="摄像头"
        enabled={camera.state.enabled}
        disabled={!connected}
        colorClass="blue"
        onToggle={camera.toggle}
        devices={camera.devices}
        currentDeviceId={camera.state.deviceId}
        onSwitchDevice={camera.switchDevice}
      />
    </div>
  );
}

interface DeviceButtonProps {
  label: string;
  enabled: boolean;
  disabled: boolean;
  colorClass: 'green' | 'blue';
  onToggle: () => void;
  devices: MediaDeviceInfo[];
  currentDeviceId: string | null;
  onSwitchDevice: (deviceId: string) => void;
  audioLevel?: number;
}

function DeviceButton({
  label,
  enabled,
  disabled,
  colorClass,
  onToggle,
  devices,
  currentDeviceId,
  onSwitchDevice,
  audioLevel,
}: DeviceButtonProps) {
  const colors = colorClass === 'green'
    ? { bg: 'bg-green-500/10', hover: 'hover:bg-green-500/15', border: 'border-green-500/20', dot: 'bg-green-400',
        text: 'text-green-400', shadow: 'shadow-[0_0_20px_rgba(34,197,94,0.08)]', ring: 'border-green-400/10' }
    : { bg: 'bg-blue-500/10', hover: 'hover:bg-blue-500/15', border: 'border-blue-500/20', dot: 'bg-blue-400',
        text: 'text-blue-400', shadow: 'shadow-[0_0_20px_rgba(59,130,246,0.08)]', ring: 'border-blue-400/10' };

  const hasMultipleDevices = devices.length > 1;
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!dropdownOpen) return;
    const handler = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [dropdownOpen]);

  const currentLabel = devices.find((d) => d.deviceId === currentDeviceId)?.label
    || (label === '麦克风' ? '默认麦克风' : '默认摄像头');

  return (
    <div className="relative flex items-center gap-2">
      {/* 主按钮 */}
      <button
        onClick={onToggle}
        disabled={disabled}
        className={`
          group relative flex flex-col items-center gap-1.5 w-20 py-2.5
          rounded-xl transition-all duration-300
          ${disabled ? 'opacity-30 cursor-not-allowed' : 'cursor-pointer'}
          ${enabled ? `${colors.bg} ${colors.hover} border ${colors.border} ${colors.shadow}` : 'bg-white/5 hover:bg-white/8 border border-white/5'}
        `}
      >
        <div className={`
          relative w-8 h-8 rounded-full flex items-center justify-center transition-colors duration-300
          ${enabled ? (colorClass === 'green' ? 'bg-green-500/20 text-green-400' : 'bg-blue-500/20 text-blue-400') : 'bg-white/5 text-gray-500'}
        `}>
          {label === '麦克风' ? (
            enabled ? (
              <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 24 24">
                <path d="M12 14a3 3 0 003-3V5a3 3 0 10-6 0v6a3 3 0 003 3z" />
              </svg>
            ) : (
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                  d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z" />
                <line x1="3" y1="3" x2="21" y2="21" strokeWidth={1.5} />
              </svg>
            )
          ) : (
            enabled ? (
              <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 24 24">
                <path d="M15 10l4.5-2.5v9L15 14H5V8h10v2zm0 0V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2h8a2 2 0 002-2v-2l4.5 2.5V7.5L15 10z" />
              </svg>
            ) : (
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                  d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
                <line x1="3" y1="3" x2="21" y2="21" strokeWidth={1.5} />
              </svg>
            )
          )}
          {/* 音频电平指示环 */}
          {audioLevel !== undefined && enabled && (
            <div
              className="absolute inset-0 rounded-full border-2 border-green-400/30 transition-all duration-75"
              style={{ transform: `scale(${0.8 + audioLevel * 0.4})` }}
            />
          )}
        </div>
        <span className={`text-[11px] font-medium tracking-wide ${enabled ? `${colors.text}/80` : 'text-gray-600'}`}>
          {label}
        </span>
        {enabled && (
          <span className={`absolute inset-0 rounded-xl border ${colors.ring} animate-ping pointer-events-none`} />
        )}
      </button>

      {/* ===== 设备选择下拉 ===== */}
      {hasMultipleDevices && (
        <div className="relative" ref={dropdownRef}>
          <button
            onClick={() => setDropdownOpen(!dropdownOpen)}
            className="w-6 h-6 rounded-lg bg-white/5 hover:bg-white/10 flex items-center justify-center transition-colors"
            title="选择设备"
          >
            <svg className="w-3 h-3 text-gray-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
            </svg>
          </button>

          {dropdownOpen && (
            <div className="absolute bottom-10 left-1/2 -translate-x-1/2 w-48 max-h-40 overflow-y-auto rounded-xl bg-gray-900 border border-white/10 shadow-2xl z-50 py-1">
              <p className="px-3 py-1.5 text-[10px] text-gray-600 uppercase tracking-wider">{label}设备</p>
              {devices.map((d) => (
                <button
                  key={d.deviceId}
                  onClick={() => { onSwitchDevice(d.deviceId); setDropdownOpen(false); }}
                  className={`
                    w-full text-left px-3 py-1.5 text-xs transition-colors truncate
                    ${d.deviceId === currentDeviceId ? 'text-white/80 bg-white/5' : 'text-gray-400 hover:bg-white/5'}
                  `}
                >
                  {d.label || `设备 ${d.deviceId.slice(0, 8)}...`}
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

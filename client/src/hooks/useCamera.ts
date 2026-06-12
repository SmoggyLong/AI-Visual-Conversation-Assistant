import { useState, useRef, useCallback, useEffect } from 'react';
import type { CameraState, CameraControlPayload } from '../types/messages';

interface UseCameraOptions {
  onStateChange?: (payload: CameraControlPayload) => void;
}

/**
 * 摄像头管理 Hook。
 *
 * 负责：
 * - 枚举可用摄像头设备列表
 * - 调用 getUserMedia 申请权限
 * - 管理 MediaStream 生命周期
 * - 切换摄像头设备
 * - 通过 onStateChange 回调通知服务端状态变更
 */
export function useCamera(options?: UseCameraOptions) {
  const [state, setState] = useState<CameraState>({
    enabled: false,
    deviceId: null,
    resolution: null,
    stream: null,
    error: null,
  });

  /** 可用摄像头设备列表 */
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([]);

  const videoRef = useRef<HTMLVideoElement>(null);
  const currentDeviceIdRef = useRef<string | null>(null);

  /**
   * 枚举所有视频输入设备。
   * 组件挂载时执行一次，监听设备插拔事件。
   */
  const enumerate = useCallback(async () => {
    try {
      // 先请求一次权限（否则 device.label 为空）
      const all = await navigator.mediaDevices.enumerateDevices();
      const cameras = all.filter((d) => d.kind === 'videoinput');
      setDevices(cameras);
    } catch {
      // 枚举失败，保持旧列表
    }
  }, []);

  useEffect(() => {
    enumerate();
    navigator.mediaDevices.addEventListener('devicechange', enumerate);
    return () => {
      navigator.mediaDevices.removeEventListener('devicechange', enumerate);
    };
  }, [enumerate]);

  /**
   * 开启摄像头。
   * @param deviceId 可选，指定设备 ID；不传则使用当前选中的设备或默认设备
   */
  const start = useCallback(async (deviceId?: string) => {
    const targetId = deviceId ?? currentDeviceIdRef.current;
    try {
      const constraints: MediaStreamConstraints = {
        video: {
          deviceId: targetId ? { exact: targetId } : undefined,
          width: { ideal: 1280 },
          height: { ideal: 720 },
        },
        audio: false,
      };

      const stream = await navigator.mediaDevices.getUserMedia(constraints);
      const track = stream.getVideoTracks()[0];
      const settings = track.getSettings();

      currentDeviceIdRef.current = settings.deviceId ?? null;

      setState({
        enabled: true,
        deviceId: settings.deviceId ?? null,
        resolution: `${settings.width}x${settings.height}`,
        stream,
        error: null,
      });

      if (videoRef.current) {
        videoRef.current.srcObject = stream;
      }

      options?.onStateChange?.({ enabled: true, deviceId: settings.deviceId ?? undefined });

      // 刷新设备列表（拿到 label）
      enumerate();
    } catch (err) {
      const message =
        err instanceof DOMException
          ? err.name === 'NotAllowedError'
            ? '摄像头权限被拒绝'
            : err.name === 'NotFoundError'
            ? '未找到摄像头设备'
            : err.message
          : '摄像头启动失败';
      setState((prev) => ({ ...prev, enabled: false, error: message }));
    }
  }, [options, enumerate]);

  /** 关闭摄像头 */
  const stop = useCallback(() => {
    if (state.stream) {
      state.stream.getTracks().forEach((track) => track.stop());
    }
    if (videoRef.current) {
      videoRef.current.srcObject = null;
    }
    setState({
      enabled: false,
      deviceId: null,
      resolution: null,
      stream: null,
      error: null,
    });
    options?.onStateChange?.({ enabled: false });
  }, [state.stream, options]);

  /** 切换开关 */
  const toggle = useCallback(async () => {
    if (state.enabled) {
      stop();
    } else {
      await start();
    }
  }, [state.enabled, start, stop]);

  /** 切换到指定设备（如果已开启则重启） */
  const switchDevice = useCallback(async (deviceId: string) => {
    if (state.enabled) {
      stop();
      await start(deviceId);
    } else {
      currentDeviceIdRef.current = deviceId;
    }
  }, [state.enabled, start, stop]);

  return { state, devices, videoRef, start, stop, toggle, switchDevice, refreshDevices: enumerate };
}

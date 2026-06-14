import { useState, useRef, useCallback, useEffect } from 'react';
import type { CameraState, CameraControlPayload } from '../types/messages';

interface UseCameraOptions {
  onStateChange?: (payload: CameraControlPayload) => void;
}

/**
 * 摄像头管理 Hook。
 *
 * v2: 原子切换（不发中间 false）+ generation counter 防竞态。
 */
export function useCamera(options?: UseCameraOptions) {
  const [state, setState] = useState<CameraState>({
    enabled: false,
    deviceId: null,
    resolution: null,
    stream: null,
    error: null,
  });

  const [devices, setDevices] = useState<MediaDeviceInfo[]>([]);

  const videoRef = useRef<HTMLVideoElement>(null);
  const currentDeviceIdRef = useRef<string | null>(null);
  const genRef = useRef(0);       // generation counter，取消过期请求

  const enumerate = useCallback(async () => {
    try {
      const all = await navigator.mediaDevices.enumerateDevices();
      const cameras = all.filter((d) => d.kind === 'videoinput');
      setDevices(cameras);
    } catch { /* 忽略 */ }
  }, []);

  useEffect(() => {
    enumerate();
    navigator.mediaDevices.addEventListener('devicechange', enumerate);
    return () => navigator.mediaDevices.removeEventListener('devicechange', enumerate);
  }, [enumerate]);

  const start = useCallback(async (deviceId?: string) => {
    const targetId = deviceId ?? currentDeviceIdRef.current;
    const myGen = ++genRef.current;

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

      // 有更新的 start() 进来了，丢弃本次结果
      if (myGen !== genRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }

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

      options?.onStateChange?.({ enabled: true, deviceId: settings.deviceId ?? undefined });
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
      // 通知后端关闭（不管是首次开还是切换，失败时都要让后端知道不可用）
      options?.onStateChange?.({ enabled: false });
    }
  }, [options, enumerate]);

  const stop = useCallback(() => {
    if (state.stream) {
      state.stream.getTracks().forEach((track) => track.stop());
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

  /** stream 变化时绑定到 video 元素 */
  useEffect(() => {
    if (videoRef.current) {
      videoRef.current.srcObject = state.stream ?? null;
    }
  }, [state.stream]);

  const toggle = useCallback(async () => {
    if (state.enabled) {
      stop();
    } else {
      await start();
    }
  }, [state.enabled, start, stop]);

  /**
   * 切换设备：先停旧流释放硬件 → 开新设备。
   * 失败时尝试回退到默认设备。
   */
  const switchDevice = useCallback(async (deviceId: string) => {
    if (!state.enabled) {
      currentDeviceIdRef.current = deviceId;
      return;
    }

    // 1. 先停旧流，释放硬件
    const oldStream = state.stream;
    oldStream?.getTracks().forEach((t) => t.stop());
    setState((prev) => ({ ...prev, stream: null }));

    // 2. 尝试打开新设备
    const myGen = ++genRef.current;
    try {
      const constraints: MediaStreamConstraints = {
        video: {
          deviceId: { exact: deviceId },
          width: { ideal: 1280 },
          height: { ideal: 720 },
        },
        audio: false,
      };
      const stream = await navigator.mediaDevices.getUserMedia(constraints);

      if (myGen !== genRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }

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

      options?.onStateChange?.({ enabled: true, deviceId: settings.deviceId ?? undefined });
      enumerate();
    } catch {
      // 3. 失败 → 尝试回退到默认设备
      try {
        const fallback: MediaStreamConstraints = {
          video: { width: { ideal: 1280 }, height: { ideal: 720 } },
          audio: false,
        };
        const stream = await navigator.mediaDevices.getUserMedia(fallback);

        if (myGen !== genRef.current) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }

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

        options?.onStateChange?.({ enabled: true, deviceId: settings.deviceId ?? undefined });
        enumerate();
      } catch {
        setState((prev) => ({
          ...prev,
          enabled: false,
          stream: null,
          error: '摄像头切换失败',
        }));
        options?.onStateChange?.({ enabled: false });
      }
    }
  }, [state.enabled, state.stream, options, enumerate]);

  return { state, devices, videoRef, start, stop, toggle, switchDevice, refreshDevices: enumerate };
}

import { useState, useRef, useCallback } from 'react';
import type { CameraState, CameraControlPayload } from '../types/messages';

interface UseCameraOptions {
  /** 摄像头状态变更回调，用于通知服务端 */
  onStateChange?: (payload: CameraControlPayload) => void;
}

/**
 * 摄像头管理 Hook。
 *
 * 负责：
 * - 调用 getUserMedia 申请摄像头权限
 * - 管理 MediaStream 生命周期（获取/释放）
 * - 提供 videoRef 用于绑定 <video> 元素渲染预览
 * - 通过 onStateChange 回调通知服务端摄像头状态变更
 *
 * 权限被拒绝或设备不存在时，通过 state.error 返回中文错误提示。
 *
 * @param options.onStateChange — 摄像头开关状态变更时回调，发送 CAMERA_CONTROL 消息到服务端
 * @returns state     — 摄像头状态（enabled, stream, error, resolution）
 * @returns videoRef  — 绑定到 <video> 元素的 ref
 * @returns start     — 开启摄像头（可传入 deviceId 指定设备）
 * @returns stop      — 关闭摄像头，释放 MediaStream
 * @returns toggle    — 切换开关（开→关，关→开）
 */
export function useCamera(options?: UseCameraOptions) {
  /** 摄像头状态 */
  const [state, setState] = useState<CameraState>({
    enabled: false,
    deviceId: null,
    resolution: null,
    stream: null,
    error: null,
  });

  /** video 元素引用，用于绑定 MediaStream 渲染预览 */
  const videoRef = useRef<HTMLVideoElement>(null);

  /**
   * 开启摄像头。
   * 优先使用 1280x720 分辨率，失败时使用默认分辨率。
   *
   * @param deviceId 可选，指定摄像头设备 ID
   */
  const start = useCallback(async (deviceId?: string) => {
    try {
      const constraints: MediaStreamConstraints = {
        video: deviceId
          ? { deviceId: { exact: deviceId }, width: 1280, height: 720 }
          : { width: 1280, height: 720, facingMode: 'user' },
        audio: false,
      };

      // MediaStream → 绑定 video 元素 + 记录状态
      const stream = await navigator.mediaDevices.getUserMedia(constraints);
      const track = stream.getVideoTracks()[0];
      const settings = track.getSettings();

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

      options?.onStateChange?.({ enabled: true, deviceId });
    } catch (err) {
      // 错误分类 → 中文提示
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
  }, [options]);

  /**
   * 关闭摄像头，停止所有 track 并清除 video 绑定。
   */
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

  /**
   * 切换摄像头开关。
   */
  const toggle = useCallback(async () => {
    if (state.enabled) {
      stop();
    } else {
      await start();
    }
  }, [state.enabled, start, stop]);

  return { state, videoRef, start, stop, toggle };
}

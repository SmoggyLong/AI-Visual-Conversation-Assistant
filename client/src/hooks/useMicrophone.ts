import { useState, useRef, useCallback, useEffect } from 'react';
import type { MicrophoneState, MicrophoneControlPayload } from '../types/messages';

interface UseMicrophoneOptions {
  onStateChange?: (payload: MicrophoneControlPayload) => void;
}

/**
 * 麦克风管理 Hook。
 *
 * 负责：
 * - 枚举可用麦克风设备列表
 * - 调用 getUserMedia 申请权限
 * - 管理 MediaStream 生命周期
 * - AudioContext + AnalyserNode 实时音量电平
 * - 切换麦克风设备
 * - 通过 onStateChange 回调通知服务端状态变更
 */
export function useMicrophone(options?: UseMicrophoneOptions) {
  const [state, setState] = useState<MicrophoneState>({
    enabled: false,
    deviceId: null,
    stream: null,
    error: null,
    audioLevel: 0,
  });

  /** 可用麦克风设备列表 */
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([]);

  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const animationFrameRef = useRef<number>(0);
  const currentDeviceIdRef = useRef<string | null>(null);

  /** 枚举音频输入设备 */
  const enumerate = useCallback(async () => {
    try {
      const all = await navigator.mediaDevices.enumerateDevices();
      const mics = all.filter((d) => d.kind === 'audioinput');
      setDevices(mics);
    } catch {
      // 忽略
    }
  }, []);

  useEffect(() => {
    enumerate();
    navigator.mediaDevices.addEventListener('devicechange', enumerate);
    return () => {
      navigator.mediaDevices.removeEventListener('devicechange', enumerate);
    };
  }, [enumerate]);

  /** 音频电平循环 */
  const updateAudioLevel = useCallback(() => {
    const analyser = analyserRef.current;
    if (!analyser) return;
    const dataArray = new Uint8Array(analyser.frequencyBinCount);
    analyser.getByteFrequencyData(dataArray);
    const avg = dataArray.reduce((a, b) => a + b, 0) / dataArray.length;
    setState((prev) => ({ ...prev, audioLevel: avg / 255 }));
    animationFrameRef.current = requestAnimationFrame(updateAudioLevel);
  }, []);

  /** 开启麦克风 */
  const start = useCallback(async (deviceId?: string) => {
    const targetId = deviceId ?? currentDeviceIdRef.current;
    try {
      const constraints: MediaStreamConstraints = {
        audio: {
          deviceId: targetId ? { exact: targetId } : undefined,
          echoCancellation: true,
          noiseSuppression: true,
          sampleRate: { ideal: 16000 },
        },
        video: false,
      };

      const stream = await navigator.mediaDevices.getUserMedia(constraints);
      const track = stream.getAudioTracks()[0];
      const settings = track.getSettings();

      currentDeviceIdRef.current = settings.deviceId ?? null;

      const audioCtx = new AudioContext();
      const source = audioCtx.createMediaStreamSource(stream);
      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 256;
      source.connect(analyser);
      audioContextRef.current = audioCtx;
      analyserRef.current = analyser;
      updateAudioLevel();

      setState({
        enabled: true,
        deviceId: settings.deviceId ?? null,
        stream,
        error: null,
        audioLevel: 0,
      });

      options?.onStateChange?.({ enabled: true, deviceId: settings.deviceId ?? undefined });

      enumerate();
    } catch (err) {
      const message =
        err instanceof DOMException
          ? err.name === 'NotAllowedError'
            ? '麦克风权限被拒绝'
            : err.name === 'NotFoundError'
            ? '未找到麦克风设备'
            : err.message
          : '麦克风启动失败';
      setState((prev) => ({ ...prev, enabled: false, error: message }));
    }
  }, [options, updateAudioLevel, enumerate]);

  /** 关闭麦克风 */
  const stop = useCallback(() => {
    if (state.stream) {
      state.stream.getTracks().forEach((track) => track.stop());
    }
    if (audioContextRef.current) {
      audioContextRef.current.close();
      audioContextRef.current = null;
    }
    analyserRef.current = null;
    cancelAnimationFrame(animationFrameRef.current);
    setState({
      enabled: false,
      deviceId: null,
      stream: null,
      error: null,
      audioLevel: 0,
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

  /** 切换到指定设备 */
  const switchDevice = useCallback(async (deviceId: string) => {
    if (state.enabled) {
      stop();
      await start(deviceId);
    } else {
      currentDeviceIdRef.current = deviceId;
    }
  }, [state.enabled, start, stop]);

  useEffect(() => {
    return () => {
      cancelAnimationFrame(animationFrameRef.current);
      if (audioContextRef.current) {
        audioContextRef.current.close();
      }
    };
  }, []);

  return { state, devices, start, stop, toggle, switchDevice, refreshDevices: enumerate };
}

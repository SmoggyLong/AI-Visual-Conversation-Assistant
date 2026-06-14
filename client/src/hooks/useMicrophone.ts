import { useState, useRef, useCallback, useEffect } from 'react';
import type { MicrophoneState, MicrophoneControlPayload } from '../types/messages';

interface UseMicrophoneOptions {
  onStateChange?: (payload: MicrophoneControlPayload) => void;
}

/**
 * 麦克风管理 Hook。
 *
 * v2: 原子切换（不发中间 false）+ generation counter 防竞态。
 */
export function useMicrophone(options?: UseMicrophoneOptions) {
  const [state, setState] = useState<MicrophoneState>({
    enabled: false,
    deviceId: null,
    stream: null,
    error: null,
    audioLevel: 0,
  });

  const [devices, setDevices] = useState<MediaDeviceInfo[]>([]);

  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const animationFrameRef = useRef<number>(0);
  const currentDeviceIdRef = useRef<string | null>(null);
  const genRef = useRef(0);

  const enumerate = useCallback(async () => {
    try {
      const all = await navigator.mediaDevices.enumerateDevices();
      const mics = all.filter((d) => d.kind === 'audioinput');
      setDevices(mics);
    } catch { /* 忽略 */ }
  }, []);

  useEffect(() => {
    enumerate();
    navigator.mediaDevices.addEventListener('devicechange', enumerate);
    return () => navigator.mediaDevices.removeEventListener('devicechange', enumerate);
  }, [enumerate]);

  const updateAudioLevel = useCallback(() => {
    const analyser = analyserRef.current;
    if (!analyser) return;
    const dataArray = new Uint8Array(analyser.frequencyBinCount);
    analyser.getByteFrequencyData(dataArray);
    const avg = dataArray.reduce((a, b) => a + b, 0) / dataArray.length;
    setState((prev) => ({ ...prev, audioLevel: avg / 255 }));
    animationFrameRef.current = requestAnimationFrame(updateAudioLevel);
  }, []);

  /** 新建 AudioContext + AnalyserNode 并绑定到 stream */
  const connectAudioAnalyzer = (stream: MediaStream) => {
    // 先关闭旧的
    if (audioContextRef.current) {
      audioContextRef.current.close();
    }
    cancelAnimationFrame(animationFrameRef.current);
    analyserRef.current = null;

    const audioCtx = new AudioContext();
    const source = audioCtx.createMediaStreamSource(stream);
    const analyser = audioCtx.createAnalyser();
    analyser.fftSize = 256;
    source.connect(analyser);
    audioContextRef.current = audioCtx;
    analyserRef.current = analyser;
    updateAudioLevel();
  };

  const start = useCallback(async (deviceId?: string) => {
    const targetId = deviceId ?? currentDeviceIdRef.current;
    const myGen = ++genRef.current;

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

      if (myGen !== genRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }

      const track = stream.getAudioTracks()[0];
      const settings = track.getSettings();

      currentDeviceIdRef.current = settings.deviceId ?? null;
      connectAudioAnalyzer(stream);

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
      options?.onStateChange?.({ enabled: false });
    }
  }, [options, updateAudioLevel, enumerate]);

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
    if (audioContextRef.current) {
      audioContextRef.current.close();
      audioContextRef.current = null;
    }
    cancelAnimationFrame(animationFrameRef.current);
    setState((prev) => ({ ...prev, stream: null }));

    // 2. 尝试打开新设备
    const myGen = ++genRef.current;
    try {
      const constraints: MediaStreamConstraints = {
        audio: {
          deviceId: { exact: deviceId },
          echoCancellation: true,
          noiseSuppression: true,
          sampleRate: { ideal: 16000 },
        },
        video: false,
      };
      const stream = await navigator.mediaDevices.getUserMedia(constraints);

      if (myGen !== genRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }

      const track = stream.getAudioTracks()[0];
      const settings = track.getSettings();
      currentDeviceIdRef.current = settings.deviceId ?? null;
      connectAudioAnalyzer(stream);

      setState({
        enabled: true,
        deviceId: settings.deviceId ?? null,
        stream,
        error: null,
        audioLevel: 0,
      });

      options?.onStateChange?.({ enabled: true, deviceId: settings.deviceId ?? undefined });
      enumerate();
    } catch {
      // 3. 失败 → 尝试回退到默认设备
      try {
        const fallback: MediaStreamConstraints = {
          audio: { echoCancellation: true, noiseSuppression: true, sampleRate: { ideal: 16000 } },
          video: false,
        };
        const stream = await navigator.mediaDevices.getUserMedia(fallback);

        if (myGen !== genRef.current) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }

        const track = stream.getAudioTracks()[0];
        const settings = track.getSettings();
        currentDeviceIdRef.current = settings.deviceId ?? null;
        connectAudioAnalyzer(stream);

        setState({
          enabled: true,
          deviceId: settings.deviceId ?? null,
          stream,
          error: null,
          audioLevel: 0,
        });

        options?.onStateChange?.({ enabled: true, deviceId: settings.deviceId ?? undefined });
        enumerate();
      } catch {
        setState((prev) => ({
          ...prev,
          enabled: false,
          stream: null,
          error: '麦克风切换失败',
        }));
        options?.onStateChange?.({ enabled: false });
      }
    }
  }, [state.enabled, state.stream, options, updateAudioLevel, enumerate]);

  useEffect(() => {
    return () => {
      cancelAnimationFrame(animationFrameRef.current);
      audioContextRef.current?.close();
    };
  }, []);

  return { state, devices, start, stop, toggle, switchDevice, refreshDevices: enumerate };
}

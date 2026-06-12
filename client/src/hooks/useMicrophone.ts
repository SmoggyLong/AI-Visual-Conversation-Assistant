import { useState, useRef, useCallback, useEffect } from 'react';
import type { MicrophoneState, MicrophoneControlPayload } from '../types/messages';

interface UseMicrophoneOptions {
  /** 麦克风状态变更回调，用于通知服务端 */
  onStateChange?: (payload: MicrophoneControlPayload) => void;
}

/**
 * 麦克风管理 Hook。
 *
 * 负责：
 * - 调用 getUserMedia 申请麦克风权限
 * - 管理 MediaStream 生命周期
 * - 通过 AudioContext + AnalyserNode 实时计算音量电平
 * - 通过 onStateChange 回调通知服务端麦克风状态变更
 *
 * @param options.onStateChange — 麦克风开关状态变更时回调
 * @returns state   — 麦克风状态（enabled, stream, error, audioLevel）
 * @returns start   — 开启麦克风
 * @returns stop    — 关闭麦克风，释放资源
 * @returns toggle  — 切换开关
 */
export function useMicrophone(options?: UseMicrophoneOptions) {
  /** 麦克风状态 */
  const [state, setState] = useState<MicrophoneState>({
    enabled: false,
    deviceId: null,
    stream: null,
    error: null,
    audioLevel: 0,
  });

  /** AudioContext 引用 */
  const audioContextRef = useRef<AudioContext | null>(null);

  /** 音频分析器，用于提取频域数据计算音量 */
  const analyserRef = useRef<AnalyserNode | null>(null);

  /** requestAnimationFrame ID，用于音频电平循环 */
  const animationFrameRef = useRef<number>(0);

  /**
   * 音频电平可视化循环。
   * 通过 AnalyserNode 获取频域数据，计算平均值作为音量指示。
   * 以 requestAnimationFrame 驱动，约 60fps。
   */
  const updateAudioLevel = useCallback(() => {
    const analyser = analyserRef.current;
    if (!analyser) return;

    const dataArray = new Uint8Array(analyser.frequencyBinCount);
    analyser.getByteFrequencyData(dataArray);

    // 频域数据平均 → 归一化到 0~1
    const avg = dataArray.reduce((a, b) => a + b, 0) / dataArray.length;
    setState((prev) => ({ ...prev, audioLevel: avg / 255 }));

    animationFrameRef.current = requestAnimationFrame(updateAudioLevel);
  }, []);

  /**
   * 开启麦克风，构建 AudioContext → MediaStreamSource → AnalyserNode 链路。
   *
   * @param deviceId 可选，指定麦克风设备 ID
   */
  const start = useCallback(async (deviceId?: string) => {
    try {
      const constraints: MediaStreamConstraints = {
        audio: deviceId
          ? { deviceId: { exact: deviceId } }
          : {
              echoCancellation: true,   // 回声消除
              noiseSuppression: true,   // 降噪
              sampleRate: 16000,        // 16kHz 采样率（STT 最优）
            },
        video: false,
      };

      const stream = await navigator.mediaDevices.getUserMedia(constraints);
      const track = stream.getAudioTracks()[0];

      // 构建音频处理链路：MediaStream → AudioContext → AnalyserNode
      const audioCtx = new AudioContext();
      const source = audioCtx.createMediaStreamSource(stream);
      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 256;           // FFT 点数：256 = 128 个频段
      source.connect(analyser);         // 不连接到 destination，避免回声

      audioContextRef.current = audioCtx;
      analyserRef.current = analyser;
      updateAudioLevel();

      setState({
        enabled: true,
        deviceId: track.getSettings().deviceId ?? null,
        stream,
        error: null,
        audioLevel: 0,
      });

      options?.onStateChange?.({ enabled: true, deviceId });
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
  }, [options, updateAudioLevel]);

  /**
   * 关闭麦克风，停止 track、关闭 AudioContext、取消动画帧。
   */
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

  /**
   * 切换麦克风开关。
   */
  const toggle = useCallback(async () => {
    if (state.enabled) {
      stop();
    } else {
      await start();
    }
  }, [state.enabled, start, stop]);

  // 组件卸载时清理 AudioContext 和动画帧
  useEffect(() => {
    return () => {
      cancelAnimationFrame(animationFrameRef.current);
      if (audioContextRef.current) {
        audioContextRef.current.close();
      }
    };
  }, []);

  return { state, start, stop, toggle };
}

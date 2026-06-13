import { useRef, useCallback, useEffect, useState } from 'react';

interface UseAudioRecorderOptions {
  /** 是否启用录音 */
  enabled: boolean;
  /** 音频流 */
  stream: MediaStream | null;
  /** 采样率，默认 16000 */
  sampleRate?: number;
  /** 每次发送的音频块时长（毫秒），默认 200ms */
  chunkDuration?: number;
  /** 音频数据回调（PCM Int16 base64） */
  onAudioChunk: (base64Pcm: string) => void;
  /** 语音开始回调 */
  onSpeechStart: () => void;
  /** 语音结束回调（静音 1s 后触发） */
  onSpeechEnd: () => void;
  /** VAD 静音阈值 RMS，默认 0.01 */
  silenceThreshold?: number;
  /** 静音持续多久视为说话结束（毫秒），默认 1000 */
  silenceDuration?: number;
}

/**
 * 音频录制 Hook — 从麦克风流中提取 PCM 音频，VAD 检测，实时发送。
 *
 * 工作流程：
 * 1. AudioContext.createMediaStreamSource(stream)
 * 2. ScriptProcessorNode 每 4096 采样回调一次
 * 3. 计算 RMS 音量 → 判断是否在说话
 * 4. 将 float32 PCM → Int16 PCM → base64
 * 5. 每 200ms 通过 onAudioChunk 回调发送一个音频块
 * 6. VAD 检测到静音 1s → onSpeechEnd
 */
export function useAudioRecorder(options: UseAudioRecorderOptions) {
  const {
    enabled,
    stream,
    sampleRate = 16000,
    chunkDuration = 200,
    onAudioChunk,
    onSpeechStart,
    onSpeechEnd,
    silenceThreshold = 0.01,
    silenceDuration = 1000,
  } = options;

  const ctxRef = useRef<AudioContext | null>(null);
  const processorRef = useRef<ScriptProcessorNode | null>(null);
  const bufferRef = useRef<Float32Array[]>([]);
  const speakingRef = useRef(false);
  const silenceStartRef = useRef<number | null>(null);
  const chunkTimerRef = useRef<ReturnType<typeof setInterval>>();

  /** 当前是否在说话（暴露给调用方） */
  const [isSpeaking, setIsSpeaking] = useState(false);

  useEffect(() => {
    if (!enabled || !stream) {
      cleanup();
      return;
    }

    const ctx = new AudioContext({ sampleRate });
    const source = ctx.createMediaStreamSource(stream);

    // 下采样处理器：浏览器可能给 44100/48000，我们降采样到 16000
    const processor = ctx.createScriptProcessor(4096, 1, 1);
    ctxRef.current = ctx;
    processorRef.current = processor;

    let sampleBuffer: Float32Array[] = [];
    let inputSampleRate = ctx.sampleRate;
    const ratio = inputSampleRate / sampleRate;

    processor.onaudioprocess = (e) => {
      const input = e.inputBuffer.getChannelData(0);
      const raw = new Float32Array(input);

      // 降采样
      const downsampledLength = Math.floor(raw.length / ratio);
      const downsampled = new Float32Array(downsampledLength);
      for (let i = 0; i < downsampledLength; i++) {
        downsampled[i] = raw[Math.floor(i * ratio)];
      }

      // RMS 音量
      let sum = 0;
      for (let i = 0; i < downsampled.length; i++) sum += downsampled[i] * downsampled[i];
      const rms = Math.sqrt(sum / downsampled.length);

      const now = Date.now();

      if (rms > silenceThreshold) {
        // 语音检测
        if (!speakingRef.current) {
          speakingRef.current = true;
          setIsSpeaking(true);
          silenceStartRef.current = null;
          onSpeechStart();
        }
        bufferRef.current.push(downsampled);
        silenceStartRef.current = null;
      } else if (speakingRef.current) {
        // 静音检测
        if (silenceStartRef.current === null) {
          silenceStartRef.current = now;
        } else if (now - silenceStartRef.current > silenceDuration) {
          speakingRef.current = false;
          setIsSpeaking(false);
          silenceStartRef.current = null;
          onSpeechEnd();
        }
      }
    };

    source.connect(processor);
    processor.connect(ctx.destination);

    // 定时发送音频块
    chunkTimerRef.current = setInterval(() => {
      if (bufferRef.current.length === 0) return;

      const chunks = bufferRef.current;
      bufferRef.current = [];

      // 合并所有 Float32Array
      let totalLen = 0;
      for (const c of chunks) totalLen += c.length;
      const combined = new Float32Array(totalLen);
      let offset = 0;
      for (const c of chunks) {
        combined.set(c, offset);
        offset += c.length;
      }

      // Float32 → Int16 PCM
      const int16 = new Int16Array(combined.length);
      for (let i = 0; i < combined.length; i++) {
        const s = Math.max(-1, Math.min(1, combined[i]));
        int16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
      }

      // Int16 → base64
      const bytes = new Uint8Array(int16.buffer);
      let binary = '';
      for (let i = 0; i < bytes.byteLength; i++) {
        binary += String.fromCharCode(bytes[i]);
      }
      const base64 = btoa(binary);
      onAudioChunk(base64);
    }, chunkDuration);

    return cleanup;
  }, [enabled, stream, sampleRate, chunkDuration, silenceThreshold, silenceDuration, onAudioChunk, onSpeechStart, onSpeechEnd]);

  return { isSpeaking };

  function cleanup() {
    if (chunkTimerRef.current) { clearInterval(chunkTimerRef.current); chunkTimerRef.current = undefined; }
    if (processorRef.current) {
      processorRef.current.disconnect();
      processorRef.current.onaudioprocess = null;
      processorRef.current = null;
    }
    if (ctxRef.current) {
      ctxRef.current.close().catch(() => {});
      ctxRef.current = null;
    }
    bufferRef.current = [];
    speakingRef.current = false;
    silenceStartRef.current = null;
  }
}

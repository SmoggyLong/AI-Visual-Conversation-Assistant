import { useEffect, useRef, useState, useCallback } from 'react';

interface UseSpeechRecognitionOptions {
  /** 是否启用语音识别（通常绑定麦克风开关状态） */
  enabled: boolean;
  /** 识别语言，默认 zh-CN */
  lang?: string;
  /** 最终识别结果回调（一句话说完） */
  onFinalResult: (text: string) => void;
  /** 中间识别结果回调（正在说的话，实时变化） */
  onInterimResult: (text: string) => void;
  /** 错误回调（已去重，同类型错误仅触发一次） */
  onError?: (error: string) => void;
}

interface UseSpeechRecognitionReturn {
  /** 是否正在监听语音 */
  isListening: boolean;
  /** 浏览器是否支持 SpeechRecognition */
  isSupported: boolean;
  /** 是否因网络问题不可用（国内 Google 服务被墙） */
  isNetworkUnavailable: boolean;
}

/**
 * 端侧本地语音识别 Hook。
 *
 * 基于 Web Speech API (Chrome 内置)，将语音实时转为文字。
 * 注意：Chrome 将音频发送到 Google 云端做 STT，**国内网络需要 VPN**。
 *
 * 错误处理：
 * - "no-speech" → 静默忽略（麦克风开着但没人说话是正常的）
 * - "network" → 首次提示"需要 VPN"，后续不再重复
 * - 其他错误 → 通知调用方
 */
export function useSpeechRecognition(
  options: UseSpeechRecognitionOptions
): UseSpeechRecognitionReturn {
  const { enabled, lang = 'zh-CN', onFinalResult, onInterimResult, onError } = options;

  const recognitionRef = useRef<SpeechRecognition | null>(null);
  const [isListening, setIsListening] = useState(false);
  const shouldRestartRef = useRef(false);

  /** 错误计数：用于去重 */
  const errorCountRef = useRef<Map<string, number>>(new Map());

  /** 网络不可用标记 */
  const [isNetworkUnavailable, setIsNetworkUnavailable] = useState(false);

  /** 浏览器是否支持 */
  const [isSupported] = useState(() => {
    return 'SpeechRecognition' in window || 'webkitSpeechRecognition' in window;
  });

  /**
   * 去重错误上报：同类型错误最多触发 1 次，
   * "no-speech" 和 "aborted" 完全不上报。
   */
  const reportError = useCallback(
    (code: string, message: string) => {
      // 静默忽略：没人说话或中断是正常行为
      if (code === 'no-speech' || code === 'aborted') return;

      const count = errorCountRef.current.get(code) || 0;
      if (count >= 1) return; // 已上报过，不再重复

      errorCountRef.current.set(code, count + 1);
      onError?.(message);
    },
    [onError]
  );

  useEffect(() => {
    if (!isSupported) return;

    const SpeechRecognitionAPI = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognitionAPI) return;

    const recognition = new SpeechRecognitionAPI();

    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = lang;
    recognition.maxAlternatives = 1;

    recognition.onresult = (event: SpeechRecognitionEvent) => {
      let interimText = '';

      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i];
        const transcript = result[0].transcript.trim();

        if (result.isFinal) {
          if (transcript) {
            onFinalResult(transcript);
          }
        } else {
          interimText += transcript;
        }
      }

      if (interimText) {
        onInterimResult(interimText);
      }
    };

    recognition.onend = () => {
      setIsListening(false);
      if (shouldRestartRef.current) {
        try {
          recognition.start();
          setIsListening(true);
        } catch {
          // 重启失败，忽略
        }
      }
    };

    recognition.onerror = (event: SpeechRecognitionErrorEvent) => {
      const errorMap: Record<string, string> = {
        'not-allowed': '麦克风权限被拒绝，请在浏览器设置中允许',
        'network': '语音识别需要 VPN 连接 Google 服务。国内网络不可用，将改用服务端 STT。',
        'audio-capture': '无法获取音频输入',
        'language-not-supported': `不支持语言: ${lang}`,
        'service-not-allowed': '语音识别服务不可用',
        'bad-grammar': '语法错误',
      };

      const message = errorMap[event.error] || `语音识别错误: ${event.error}`;

      if (event.error === 'network') {
        setIsNetworkUnavailable(true);
      }

      reportError(event.error, message);

      // no-speech → 继续尝试
      if (event.error === 'no-speech' || event.error === 'aborted') {
        return;
      }

      // network → 停止识别（等 VPN 恢复）
      if (event.error === 'network') {
        shouldRestartRef.current = false;
        try { recognition.stop(); } catch { /* */ }
        setIsListening(false);
        return;
      }

      setIsListening(false);
    };

    recognitionRef.current = recognition;

    return () => {
      shouldRestartRef.current = false;
      try { recognition.abort(); } catch { /* */ }
    };
  }, [isSupported, lang, onFinalResult, onInterimResult, reportError]);

  /** enabled 变化时 启动/停止 */
  useEffect(() => {
    const recognition = recognitionRef.current;
    if (!recognition) return;

    // 网络不可用时不再尝试启动
    if (isNetworkUnavailable) return;

    if (enabled) {
      shouldRestartRef.current = true;
      try {
        recognition.start();
        setIsListening(true);
        // 错误计数重置（新会话）
        errorCountRef.current.clear();
      } catch {
        // 已在运行
      }
    } else {
      shouldRestartRef.current = false;
      try { recognition.stop(); } catch { /* */ }
      setIsListening(false);
    }
  }, [enabled, isNetworkUnavailable]);

  return { isListening, isSupported, isNetworkUnavailable };
}

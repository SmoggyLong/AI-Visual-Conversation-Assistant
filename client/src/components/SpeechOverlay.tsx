import { useState, useEffect } from 'react';

interface SpeechOverlayProps {
  /** 当前正在识别的中间文本 */
  interimText: string;
  /** 是否正在监听语音 */
  isListening: boolean;
  /** 网络是否不可用 */
  isNetworkUnavailable: boolean;
}

/**
 * 语音浮字层 —— 视频底部的半透明玻璃字幕条。
 *
 * 三种状态：
 * - 空闲（麦克风开着但没说话）：显示"等待语音输入..."
 * - 说话中：虚化大字显示识别文字 + 闪烁光标
 * - 网络异常：黄色警告卡片
 */
export function SpeechOverlay({
  interimText,
  isListening,
  isNetworkUnavailable,
}: SpeechOverlayProps) {
  const [visible, setVisible] = useState(false);
  const [displayText, setDisplayText] = useState('');

  useEffect(() => {
    if (interimText) {
      setDisplayText(interimText);
      setVisible(true);
    } else {
      // 空文本时延迟 3 秒再隐藏，让最终结果停留一会
      const timer = setTimeout(() => setVisible(false), 3000);
      return () => clearTimeout(timer);
    }
  }, [interimText]);

  if (!isListening && !isNetworkUnavailable) return null;

  return (
    <div className="absolute bottom-0 left-0 right-0 z-10 flex flex-col items-center pointer-events-none">
      {/* ===== 网络异常提示 ===== */}
      {isNetworkUnavailable && (
        <div className="mb-4 mx-4 px-6 py-4 rounded-2xl bg-yellow-900/40 backdrop-blur-xl border border-yellow-600/30 shadow-2xl max-w-lg pointer-events-auto">
          <div className="flex items-start gap-3">
            <div className="flex-shrink-0 w-8 h-8 rounded-full bg-yellow-500/20 flex items-center justify-center mt-0.5">
              <svg className="w-4 h-4 text-yellow-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                  d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L3.732 16.5c-.77.833.192 2.5 1.732 2.5z" />
              </svg>
            </div>
            <div>
              <p className="text-yellow-200 text-sm font-medium">语音识别不可用</p>
              <p className="text-yellow-400/60 text-xs mt-1 leading-relaxed">
                Chrome 语音需连接 Google，国内请使用 VPN 或等待服务端 STT
              </p>
            </div>
          </div>
        </div>
      )}

      {/* ===== 玻璃字幕条 ===== */}
      {isListening && !isNetworkUnavailable && (
        <div className="w-full px-6 pb-6 pointer-events-none">
          <div className={`
            relative rounded-2xl overflow-hidden
            bg-gray-950/30 backdrop-blur-2xl
            border border-white/5
            shadow-[0_8px_32px_rgba(0,0,0,0.4)]
            transition-all duration-500 ease-out
            ${visible && displayText ? 'min-h-[96px]' : 'min-h-[64px]'}
          `}>
            {/* 顶部微光 */}
            <div className="absolute top-0 left-0 right-0 h-px bg-gradient-to-r from-transparent via-white/10 to-transparent" />

            <div className="px-6 py-4">
              {/* ===== 有语音内容 ===== */}
              {visible && displayText && (
                <div className="flex items-center gap-3">
                  {/* 声波动画 */}
                  <div className="flex-shrink-0 flex items-center gap-0.5">
                    {[1, 2, 3].map((i) => (
                      <span
                        key={i}
                        className="w-0.5 bg-blue-400/60 rounded-full animate-pulse"
                        style={{
                          height: `${8 + i * 4}px`,
                          animationDelay: `${i * 0.15}s`,
                        }}
                      />
                    ))}
                  </div>

                  {/* 识别文字 */}
                  <div className="flex-1 min-w-0">
                    <p className="text-xl md:text-2xl text-white/80 font-light tracking-wide leading-relaxed break-words">
                      {displayText}
                      <span className="inline-block w-0.5 h-6 bg-blue-400/60 ml-1 animate-pulse align-[-2px]" />
                    </p>
                  </div>
                </div>
              )}

              {/* ===== 空闲状态 ===== */}
              {!visible && !displayText && (
                <div className="flex items-center gap-3">
                  <span className="w-1.5 h-1.5 rounded-full bg-white/20" />
                  <p className="text-white/30 text-base font-light tracking-wide">
                    等待语音输入...
                  </p>
                </div>
              )}

              {/* 底部状态行 */}
              <div className="flex items-center justify-between mt-2">
                <div className="flex items-center gap-1.5">
                  <span className={`w-1.5 h-1.5 rounded-full ${
                    visible ? 'bg-green-400/70 animate-pulse' : 'bg-white/20'
                  }`} />
                  <span className="text-[10px] text-white/25 uppercase tracking-widest">
                    {visible ? '识别中' : '就绪'}
                  </span>
                </div>
                {displayText && (
                  <span className="text-[10px] text-white/20">
                    {displayText.length} 字
                  </span>
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

import { useEffect, useRef } from 'react';
import type { ConversationMessage } from '../types/messages';

interface ConversationPanelProps {
  messages: ConversationMessage[];
}

let messageIdCounter = 0;
export function nextMessageId(): string {
  return `msg_${Date.now()}_${++messageIdCounter}`;
}

/**
 * 对话记录面板 —— 右侧窄栏，仅展示已完成的历史消息。
 */
export function ConversationPanel({ messages }: ConversationPanelProps) {
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  return (
    <div className="h-full flex flex-col border-l border-white/[0.04] bg-gray-950/40 backdrop-blur-sm">
      {/* 标题 */}
      <div className="flex-shrink-0 px-4 py-3 border-b border-white/[0.04]">
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium text-gray-500 tracking-wider uppercase">记录</span>
          {messages.length > 0 && (
            <span className="text-[10px] text-gray-700 font-mono">
              {messages.filter((m) => m.role === 'user' && m.text && m.text.trim()).length}
            </span>
          )}
        </div>
      </div>

      {/* 消息列表 */}
      <div className="flex-1 overflow-y-auto px-3 py-3 space-y-2.5">
        {messages.length === 0 ? (
          <div className="flex items-center justify-center h-full">
            <div className="text-center">
              <div className="w-10 h-10 mx-auto mb-2 rounded-xl bg-white/[0.02] flex items-center justify-center">
                <svg className="w-4 h-4 text-gray-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                    d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
                </svg>
              </div>
              <p className="text-[11px] text-gray-700">说完的话会出现在这里</p>
            </div>
          </div>
        ) : (
          messages
            .filter((msg) => msg.text && msg.text.trim())
            .map((msg) => (
            <div
              key={msg.id}
              className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
            >
              <div
                className={`
                  max-w-[88%] px-3 py-2 rounded-xl text-xs leading-relaxed break-words
                  ${msg.role === 'user'
                    ? 'bg-blue-500/8 text-blue-200/80 border border-blue-400/10'
                    : msg.role === 'assistant'
                      ? 'bg-white/[0.03] text-gray-300/70 border border-white/[0.04]'
                      : 'bg-yellow-500/5 text-yellow-300/60 border border-yellow-500/10 text-[11px]'
                  }
                `}
              >
                <p>{msg.text}</p>
                <p className={`mt-1 text-[9px] ${
                  msg.role === 'user' ? 'text-blue-400/30' : 'text-gray-700'
                }`}>
                  {formatTime(msg.timestamp)}
                </p>
              </div>
            </div>
          ))
        )}
        <div ref={bottomRef} />
      </div>
    </div>
  );
}

function formatTime(ts: number): string {
  const d = new Date(ts);
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

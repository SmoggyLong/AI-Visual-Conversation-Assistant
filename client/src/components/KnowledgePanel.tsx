import { useState, useEffect } from 'react';
import { useKnowledge } from '../hooks/useKnowledge';
import type { DocInput } from '../types/messages';

const emptyInput: DocInput = { title: '', content: '', type: 'GENERAL', keywords: '' };

export function KnowledgePanel() {
  const { docs, stats, loading, fetchList, fetchStats, reload, addDoc, uploadFile } = useKnowledge();
  const [showAdd, setShowAdd] = useState(false);
  const [input, setInput] = useState<DocInput>(emptyInput);

  useEffect(() => { fetchList(); fetchStats(); }, [fetchList, fetchStats]);

  const handleAdd = async () => {
    if (!input.title.trim() || !input.content.trim()) return;
    await addDoc(input);
    setInput(emptyInput);
    setShowAdd(false);
  };

  return (
    <div className="h-full flex flex-col border-l border-white/[0.04] bg-gray-950/40 backdrop-blur-sm">
      {/* 标题栏 */}
      <div className="flex-shrink-0 px-4 py-3 border-b border-white/[0.04]">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-xs font-medium text-gray-500 tracking-wider uppercase">知识库</span>
            {stats && (
              <span className="text-[10px] text-gray-700 font-mono">
                {stats.totalDocs} 篇 / {stats.totalChunks} 块
              </span>
            )}
          </div>
          <div className="flex items-center gap-1.5">
            <label className="px-2 py-1 rounded-lg text-[10px] bg-white/5 hover:bg-white/10 text-gray-500 transition-colors cursor-pointer" title="从文件导入">
              ↑
              <input type="file" accept=".md,.json,.txt,.pdf" className="hidden"
                onChange={async (e) => {
                  const file = e.target.files?.[0];
                  if (file) await uploadFile(file);
                  e.target.value = '';
                }}
              />
            </label>
            <button
              onClick={reload}
              disabled={loading}
              className="px-2 py-1 rounded-lg text-[10px] bg-white/5 hover:bg-white/10 text-gray-500 transition-colors"
              title="从磁盘重新加载"
            >
              ↻
            </button>
            <button
              onClick={() => setShowAdd(true)}
              className="px-2.5 py-1 rounded-lg text-[10px] font-medium bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 border border-emerald-500/20 transition-colors"
            >
              + 添加
            </button>
          </div>
        </div>
      </div>

      {/* 文档列表 */}
      <div className="flex-1 overflow-y-auto px-3 py-2 space-y-1.5">
        {loading && docs.length === 0 && (
          <p className="text-[11px] text-gray-700 text-center py-8">加载中...</p>
        )}
        {!loading && docs.length === 0 && (
          <p className="text-[11px] text-gray-700 text-center py-8">暂无文档</p>
        )}
        {docs.map((doc) => (
          <div key={doc.docId} className="px-3 py-2 rounded-lg bg-white/[0.02] border border-white/[0.03]">
            <div className="flex items-center justify-between">
              <span className="text-[11px] text-white/70 truncate flex-1">{doc.title}</span>
              <span className={`text-[9px] px-1.5 py-0.5 rounded ${
                doc.type === 'SOP'
                  ? 'bg-orange-500/10 text-orange-400/70'
                  : 'bg-white/5 text-gray-600'
              }`}>
                {doc.type === 'SOP' ? 'SOP' : '通用'}
              </span>
            </div>
            <div className="flex items-center justify-between mt-1">
              <span className="text-[9px] text-gray-700 truncate max-w-[140px]">{doc.sourceFile}</span>
              <span className="text-[9px] text-gray-700">{doc.totalChunks} 块</span>
            </div>
          </div>
        ))}
      </div>

      {/* 添加弹窗 */}
      {showAdd && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
             onClick={() => setShowAdd(false)}>
          <div className="w-[420px] max-h-[80vh] rounded-2xl bg-gray-900 border border-white/10 shadow-2xl p-5 overflow-y-auto"
               onClick={(e) => e.stopPropagation()}>
            <h3 className="text-sm font-medium text-white/80 mb-4">添加知识库文档</h3>

            <div className="space-y-3">
              <div>
                <label className="text-[10px] text-gray-500 uppercase tracking-wider">标题 *</label>
                <input
                  value={input.title}
                  onChange={(e) => setInput((p) => ({ ...p, title: e.target.value }))}
                  className="w-full mt-1 px-3 py-2 rounded-lg bg-white/5 border border-white/10 text-white/80 text-xs
                             focus:outline-none focus:border-emerald-500/30"
                  placeholder="退款处理SOP"
                />
              </div>

              <div>
                <label className="text-[10px] text-gray-500 uppercase tracking-wider">类型</label>
                <div className="flex gap-2 mt-1">
                  {(['GENERAL', 'SOP'] as const).map((t) => (
                    <button
                      key={t}
                      onClick={() => setInput((p) => ({ ...p, type: t }))}
                      className={`px-3 py-1.5 rounded-lg text-[11px] transition-colors ${
                        input.type === t
                          ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20'
                          : 'bg-white/5 text-gray-500 border border-white/5 hover:bg-white/10'
                      }`}
                    >
                      {t === 'SOP' ? 'SOP（操作流程）' : '通用知识'}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="text-[10px] text-gray-500 uppercase tracking-wider">关键词（逗号分隔）</label>
                <input
                  value={input.keywords}
                  onChange={(e) => setInput((p) => ({ ...p, keywords: e.target.value }))}
                  className="w-full mt-1 px-3 py-2 rounded-lg bg-white/5 border border-white/10 text-white/80 text-xs
                             focus:outline-none focus:border-emerald-500/30"
                  placeholder="退款,退钱,返钱"
                />
              </div>

              <div>
                <label className="text-[10px] text-gray-500 uppercase tracking-wider">内容 *</label>
                <textarea
                  value={input.content}
                  onChange={(e) => setInput((p) => ({ ...p, content: e.target.value }))}
                  rows={8}
                  className="w-full mt-1 px-3 py-2 rounded-lg bg-white/5 border border-white/10 text-white/80 text-xs
                             focus:outline-none focus:border-emerald-500/30 resize-none font-mono"
                  placeholder="步骤1: ...&#10;步骤2: ..."
                />
              </div>
            </div>

            <div className="flex justify-end gap-2 mt-4">
              <button
                onClick={() => setShowAdd(false)}
                className="px-4 py-2 rounded-lg text-[11px] bg-white/5 hover:bg-white/10 text-gray-400 transition-colors"
              >
                取消
              </button>
              <button
                onClick={handleAdd}
                disabled={!input.title.trim() || !input.content.trim() || loading}
                className="px-4 py-2 rounded-lg text-[11px] font-medium bg-emerald-500/10 hover:bg-emerald-500/20
                           text-emerald-400 border border-emerald-500/20 transition-colors
                           disabled:opacity-30 disabled:cursor-not-allowed"
              >
                {loading ? '入库中...' : '入库'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

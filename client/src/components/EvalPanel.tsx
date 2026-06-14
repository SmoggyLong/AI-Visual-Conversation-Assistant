import { useEffect } from 'react';
import { useEval } from '../hooks/useEval';

export function EvalPanel() {
  const { report, loading, runEval, saveBaseline, fetchReport } = useEval();

  useEffect(() => { fetchReport(); }, [fetchReport]);

  return (
    <div className="h-full flex flex-col border-l border-white/[0.04] bg-gray-950/40 backdrop-blur-sm">
      {/* 标题栏 */}
      <div className="flex-shrink-0 px-4 py-3 border-b border-white/[0.04]">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-xs font-medium text-gray-500 tracking-wider uppercase">评测</span>
            {report && (
              <span className="text-[10px] text-gray-700 font-mono">
                {report.passedCases}/{report.totalCases}
              </span>
            )}
          </div>
          <div className="flex items-center gap-1.5">
            {report && report.baselineScore >= 0 && (
              <span className="text-[10px] text-gray-600">基线: {report.baselineScore}</span>
            )}
            <button
              onClick={saveBaseline}
              disabled={!report || loading}
              className="px-2 py-1 rounded-lg text-[10px] bg-white/5 hover:bg-white/10 text-gray-500 transition-colors disabled:opacity-30"
              title="保存当前分数为基线"
            >
              保存基线
            </button>
            <button
              onClick={runEval}
              disabled={loading}
              className="px-2.5 py-1 rounded-lg text-[10px] font-medium bg-orange-500/10 hover:bg-orange-500/20 text-orange-400 border border-orange-500/20 transition-colors disabled:opacity-30"
            >
              {loading ? '评测中...' : '运行'}
            </button>
          </div>
        </div>
      </div>

      {/* 结果 */}
      <div className="flex-1 overflow-y-auto px-3 py-2 space-y-3">
        {!report && !loading && (
          <p className="text-[11px] text-gray-700 text-center py-8">点击 [运行] 开始评测</p>
        )}
        {loading && <p className="text-[11px] text-gray-700 text-center py-8 animate-pulse">评测中...</p>}
        {report && (
          <>
            {/* 汇总 */}
            <div className="px-3 py-2 rounded-lg bg-white/[0.02] border border-white/[0.03]">
              <div className="flex justify-between text-[11px]">
                <span className="text-gray-500">通过率</span>
                <span className={`font-mono ${report.passedCases === report.totalCases ? 'text-emerald-400' : 'text-yellow-400'}`}>
                  {report.passedCases}/{report.totalCases} ({report.totalCases > 0 ? Math.round((report.passedCases / report.totalCases) * 100) : 0}%)
                </span>
              </div>
              <div className="flex justify-between text-[11px] mt-1">
                <span className="text-gray-500">耗时</span>
                <span className="text-gray-600 font-mono">{(report.elapsedMs / 1000).toFixed(1)}s</span>
              </div>
            </div>

            {/* 各Agent详情 */}
            {report.byAgent && Object.entries(report.byAgent).map(([agent, summary]) => (
              <details key={agent} className="group">
                <summary className="px-3 py-2 rounded-lg bg-white/[0.02] border border-white/[0.03] cursor-pointer text-[11px] text-white/70 hover:text-white/90">
                  <span className="font-medium">{agent}</span>
                  <span className="ml-2 text-gray-600">
                    {summary.passedChecks}/{summary.cases} · 综合 {summary.avgScores?.overall ?? '-'}
                  </span>
                </summary>
                <div className="mt-1 space-y-1">
                  <div className="px-3 py-1.5 grid grid-cols-4 gap-2 text-[9px]">
                    {['relevance','accuracy','completeness','helpfulness'].map((dim) => (
                      <div key={dim} className="text-center">
                        <div className="text-gray-500">{dim.slice(0, 4)}</div>
                        <div className="font-mono text-white/60">
                          {summary.avgScores?.[dim as keyof typeof summary.avgScores] ?? '-'}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              </details>
            ))}
          </>
        )}
      </div>
    </div>
  );
}

import { useState, useCallback } from 'react';
import type { EvalReport } from '../types/messages';

const API_BASE = 'http://localhost:8080/api/eval';

export function useEval() {
  const [report, setReport] = useState<EvalReport | null>(null);
  const [loading, setLoading] = useState(false);

  const runEval = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/run`, { method: 'POST' });
      const data = await res.json();
      setReport(data as EvalReport);
      return data;
    } catch (e) {
      console.warn('评测失败', e);
      return null;
    } finally {
      setLoading(false);
    }
  }, []);

  const saveBaseline = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/baseline`, { method: 'POST' });
      return await res.json();
    } catch (e) {
      console.warn('保存基线失败', e);
      return null;
    }
  }, []);

  const fetchReport = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/report`);
      const data = await res.json();
      setReport(data as EvalReport);
    } catch (e) {
      console.warn('获取报告失败', e);
    } finally {
      setLoading(false);
    }
  }, []);

  return { report, loading, runEval, saveBaseline, fetchReport };
}

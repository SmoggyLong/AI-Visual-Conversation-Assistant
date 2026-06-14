import { useState, useCallback } from 'react';
import type { KnowledgeDoc, KnowledgeStats, KnowledgeReloadResult, DocInput } from '../types/messages';

const API_BASE = 'http://localhost:8080/api/knowledge';

export function useKnowledge() {
  const [docs, setDocs] = useState<KnowledgeDoc[]>([]);
  const [stats, setStats] = useState<KnowledgeStats | null>(null);
  const [loading, setLoading] = useState(false);

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/list`);
      const data = await res.json();
      setDocs(data as KnowledgeDoc[]);
    } catch (e) {
      console.warn('获取知识库列表失败', e);
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchStats = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/stats`);
      setStats(await res.json());
    } catch (e) {
      console.warn('获取统计失败', e);
    }
  }, []);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/reload`, { method: 'POST' });
      const result = await res.json() as KnowledgeReloadResult;
      await fetchList();
      await fetchStats();
      return result;
    } catch (e) {
      console.warn('重载失败', e);
      return null;
    } finally {
      setLoading(false);
    }
  }, [fetchList, fetchStats]);

  const addDoc = useCallback(async (input: DocInput) => {
    setLoading(true);
    try {
      const keywords = input.keywords
        ? input.keywords.split(/[,，]/).map((k) => k.trim()).filter(Boolean)
        : [];
      const res = await fetch(`${API_BASE}/add`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify([{
          title: input.title,
          content: input.content,
          type: input.type.toLowerCase(),
          keywords,
        }]),
      });
      const result = await res.json();
      await fetchList();
      await fetchStats();
      return result;
    } catch (e) {
      console.warn('添加文档失败', e);
      return null;
    } finally {
      setLoading(false);
    }
  }, [fetchList, fetchStats]);

  const uploadFile = useCallback(async (file: File) => {
    setLoading(true);
    try {
      const form = new FormData();
      form.append('file', file);
      const res = await fetch(`${API_BASE}/upload`, { method: 'POST', body: form });
      const result = await res.json();
      await fetchList();
      await fetchStats();
      return result;
    } catch (e) {
      console.warn('上传文件失败', e);
      return null;
    } finally {
      setLoading(false);
    }
  }, [fetchList, fetchStats]);

  return { docs, stats, loading, fetchList, fetchStats, reload, addDoc, uploadFile };
}

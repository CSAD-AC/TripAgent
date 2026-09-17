import { useCallback, useEffect, useRef, useState } from 'react'
import type {
  KnowledgeChunk,
  KnowledgeDoc,
  KnowledgeDocPage,
  KnowledgeStats,
  ReindexResult,
  UploadDocResult,
} from '../types'

const API_BASE = '/api/knowledge'

/**
 * 从自动/手放响应解包: 知识库管理接口(经 agent-core 转发)返回裸 JSON 体,
 * 非统一 ApiResult 信封, 故直接返回 body 本身.
 */
async function unwrap<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return (await res.json()) as T
}

/**
 * 知识库文档管理 hook
 *
 * 封装后端 /api/knowledge 一系列接口:
 *  - 分页列表 list
 *  - 上传文档 upload
 *  - 文档详情 get
 *  - 删除文档 remove
 *  - 全量重建索引 reindex
 *
 * 遵循现有 useConversations 的 fetch + ApiResult 解包风格。
 */
export function useKnowledge() {
  // ── 文档分页列表 ──
  const [docs, setDocs] = useState<KnowledgeDoc[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)

  /** 防止卸载后 setState */
  const mountedRef = useRef(true)
  useEffect(() => {
    return () => {
      mountedRef.current = false
    }
  }, [])

  /** 拉取文档分页列表 */
  const list = useCallback(async (page = 0, size = 20): Promise<void> => {
    setLoading(true)
    try {
      const data = await unwrap<KnowledgeDocPage>(
        await fetch(`${API_BASE}/documents?page=${page}&size=${size}`)
      )
      if (mountedRef.current) {
        setDocs(data.items)
        setTotal(data.total ?? 0)
      }
    } catch (err) {
      console.error('[useKnowledge] list error:', err)
      throw err
    } finally {
      if (mountedRef.current) setLoading(false)
    }
  }, [])

  /** 首次挂载拉取一次列表 */

  // ── 知识库统计(透明度: 文档/分块总数, 状态与来源分布) ──
  const [stats, setStats] = useState<KnowledgeStats | null>(null)
  const [statsLoading, setStatsLoading] = useState(false)

  /** 拉取知识库统计 */
  const loadStats = useCallback(async (): Promise<void> => {
    setStatsLoading(true)
    try {
      const data = await unwrap<KnowledgeStats>(
        await fetch(`${API_BASE}/documents/stats`)
      )
      if (mountedRef.current) setStats(data)
    } catch (err) {
      console.error('[useKnowledge] loadStats error:', err)
    } finally {
      if (mountedRef.current) setStatsLoading(false)
    }
  }, [])

  /** 首次挂载拉取列表与统计 */
  useEffect(() => {
    list()
    loadStats()
  }, [list, loadStats])

  // ── 上传文档 ──
  const upload = useCallback(
    async (payload: { title: string; content: string; destination?: string }): Promise<void> => {
      await unwrap<UploadDocResult>(
        await fetch(`${API_BASE}/documents`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        })
      )
      // 上传成功后刷新列表与统计(新文档可能处于 pending,由后端异步索引)
      await Promise.all([list(), loadStats()])
    },
    [list, loadStats]
  )

  // ── 文档详情 ──
  const get = useCallback(async (id: string): Promise<KnowledgeDoc> => {
    return unwrap<KnowledgeDoc>(await fetch(`${API_BASE}/documents/${id}`))
  }, [])

  // ── 文档分块(阅读文档内容) ──
  const getChunks = useCallback(async (id: string): Promise<KnowledgeChunk[]> => {
    return unwrap<KnowledgeChunk[]>(await fetch(`${API_BASE}/documents/${id}/chunks`))
  }, [])

  // ── 删除文档 ──
  const remove = useCallback(
    async (id: string): Promise<void> => {
      await unwrap<{ success: boolean }>(
        await fetch(`${API_BASE}/documents/${id}`, { method: 'DELETE' })
      )
      setDocs((prev) => prev.filter((d) => d.id !== id))
    },
    []
  )

  // ── 全量重建索引 ──
  const reindex = useCallback(async (): Promise<ReindexResult> => {
    const data = await unwrap<ReindexResult>(
      await fetch(`${API_BASE}/reindex`, { method: 'POST' })
    )
    // 重建后索引状态可能变化,刷新列表与统计
    await Promise.all([list(), loadStats()])
    return data
  }, [list, loadStats])

  return { docs, total, loading, stats, statsLoading, list, loadStats, upload, get, getChunks, remove, reindex }
}
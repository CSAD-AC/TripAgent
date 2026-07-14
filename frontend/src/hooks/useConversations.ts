import { useState, useCallback, useEffect, useRef } from 'react'
import type { Conversation, ApiResult, PageResult } from '../types'

const API_BASE = '/api/conversations'

/**
 * 会话管理 hook
 *
 * 职责：
 *  - 会话 ID 本地状态(URL hash ↔ conversationId 双向绑定)
 *  - 对话列表 CRUD（列表获取 / 选择 / 删除 / 重命名）
 *
 * 设计：
 *  - conversationId 的唯一权威源是后端;前端只维护"当前活动的会话 ID"
 *  - 读取:从 location.hash 取(浏览器自带,刷新保活)
 *  - 更新:收到 session_init / 用户选择对话时写回 hash
 *  - 新建:清空 hash,后端下次会生成新 ID
 */
export function useConversations() {
  // ── 当前会话 ID(URL hash 双向绑定) ──
  const [conversationId, setConversationIdState] = useState<string | undefined>(() => {
    if (typeof window === 'undefined') return undefined
    const hash = window.location.hash.slice(1)
    return hash || undefined
  })

  // ── 对话列表 ──
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [loading, setLoading] = useState(false)
  const [total, setTotal] = useState(0)

  /** 防止卸载后 setState */
  const mountedRef = useRef(true)
  useEffect(() => {
    return () => { mountedRef.current = false }
  }, [])

  /** 设置 conversationId 并写回 URL hash */
  const setConversationId = useCallback((id: string | undefined) => {
    setConversationIdState(id)
    if (typeof window === 'undefined') return
    if (id) {
      window.history.replaceState(null, '', '#' + id)
    } else {
      window.history.replaceState(null, '', window.location.pathname + window.location.search)
    }
  }, [])

  /** 监听浏览器前进后退(hash 变化) */
  useEffect(() => {
    const onHashChange = () => {
      const hash = window.location.hash.slice(1)
      setConversationIdState(hash || undefined)
    }
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

  // ── 获取对话列表 ──
  const fetchConversations = useCallback(async (page = 0, size = 50) => {
    setLoading(true)
    try {
      const res = await fetch(`${API_BASE}?page=${page}&size=${size}`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const body: ApiResult<PageResult<Conversation>> = await res.json()
      if (body.code === 200 && mountedRef.current) {
        setConversations(body.data.data)
        setTotal(body.data.total)
      }
    } catch (err) {
      console.error('[useConversations] fetch error:', err)
    } finally {
      if (mountedRef.current) setLoading(false)
    }
  }, [])

  /** 首次挂载 + conversationId 变化时刷新列表 */
  useEffect(() => {
    fetchConversations()
  }, [fetchConversations])

  // ── 删除对话 ──
  const deleteConversation = useCallback(async (id: string) => {
    try {
      const res = await fetch(`${API_BASE}/${id}`, { method: 'DELETE' })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      // 如果删除的是当前对话,清空
      setConversations((prev) => prev.filter((c) => c.id !== id))
      if (conversationId === id) {
        setConversationId(undefined)
      }
    } catch (err) {
      console.error('[useConversations] delete error:', err)
      throw err
    }
  }, [conversationId, setConversationId])

  // ── 重命名对话 ──
  const renameConversation = useCallback(async (id: string, title: string) => {
    try {
      const res = await fetch(`${API_BASE}/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title }),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      setConversations((prev) =>
        prev.map((c) => (c.id === id ? { ...c, title } : c))
      )
    } catch (err) {
      console.error('[useConversations] rename error:', err)
      throw err
    }
  }, [])

  // ── 选择对话 ──
  const selectConversation = useCallback((id: string) => {
    setConversationId(id)
  }, [setConversationId])

  // ── 新建对话 ──
  const newConversation = useCallback(() => {
    setConversationId(undefined)
  }, [setConversationId])

  return {
    conversationId,
    setConversationId,
    conversations,
    loading,
    total,
    fetchConversations,
    deleteConversation,
    renameConversation,
    selectConversation,
    newConversation,
  }
}

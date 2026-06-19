import { useState, useCallback } from 'react'
import type { Conversation } from '../types'

// 模拟数据（后端 API 就绪后替换为真实 fetch）
const MOCK_CONVERSATIONS: Conversation[] = [
  { id: 1, title: '北京3日游规划', createdAt: '2026-06-19', messageCount: 4 },
  { id: 2, title: '杭州周末去哪玩', createdAt: '2026-06-18', messageCount: 2 },
]

export function useConversations() {
  const [conversations, setConversations] = useState<Conversation[]>(MOCK_CONVERSATIONS)
  const [activeId, setActiveId] = useState<number | undefined>()

  const selectConversation = useCallback((id: number) => {
    setActiveId(id)
    // TODO: GET /api/conversations/{id}/messages
  }, [])

  const newConversation = useCallback(() => {
    setActiveId(undefined)
    // TODO: POST /api/conversations
  }, [])

  const deleteConversation = useCallback((id: number) => {
    setConversations((prev) => prev.filter((c) => c.id !== id))
    if (activeId === id) setActiveId(undefined)
    // TODO: DELETE /api/conversations/{id}
  }, [activeId])

  return {
    conversations,
    activeId,
    selectConversation,
    newConversation,
    deleteConversation,
  }
}

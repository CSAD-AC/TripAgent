import { useState, useCallback, useEffect } from 'react'

/**
 * 会话管理 hook（URL hash 模式）
 *
 * <p>设计：后端是 conversationId 的唯一权威源,前端只维护一个"当前会话 ID"状态
 * <ul>
 *   <li>读取:从 location.hash 取(浏览器自带,刷新保活)</li>
 *   <li>更新:收到 session_init 时写回 hash(用 history.replaceState,不污染历史栈)</li>
 *   <li>新建:清空 hash,后端下次会生成新 ID</li>
 * </ul>
 *
 * <p>为什么不用 localStorage:
 * <ul>
 *   <li>URL hash 浏览器自带,零代码</li>
 *   <li>天然支持分享(复制 URL = 继续同会话)</li>
 *   <li>关 tab 才丢,新 tab 重新开又是新会话(可接受的代价)</li>
 * </ul>
 */
export function useConversations() {
  const [conversationId, setConversationIdState] = useState<string | undefined>(() => {
    // 初始从 URL hash 读
    if (typeof window === 'undefined') return undefined
    const hash = window.location.hash.slice(1)
    return hash || undefined
  })

  /** 设置 conversationId 并写回 URL hash(用 replaceState,不污染历史栈) */
  const setConversationId = useCallback((id: string | undefined) => {
    setConversationIdState(id)
    if (typeof window === 'undefined') return
    if (id) {
      // 写进 hash,浏览器自动保留,刷新可恢复
      window.history.replaceState(null, '', '#' + id)
    } else {
      // 清空 hash
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

  /** 新建对话(清空当前 ID,下次发消息由后端生成新 ID) */
  const newConversation = useCallback(() => {
    setConversationId(undefined)
  }, [setConversationId])

  return {
    conversationId,
    setConversationId,
    newConversation,
  }
}

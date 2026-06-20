import { useState, useRef, useCallback } from 'react'
import type { Message, SSEEvent } from '../types'

/** 工具名称 → 中文标签 */
function toolLabel(name: string): string {
  const map: Record<string, string> = {
    getWeather: '查询天气',
    planRoute: '规划路线',
    searchPOI: '搜索景点',
    getTraffic: '查询交通',
  }
  return map[name] || name
}

export function useChat() {
  const [messages, setMessages] = useState<Message[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [streamContent, setStreamContent] = useState('')
  const abortRef = useRef<AbortController | null>(null)
  const accumulatedRef = useRef('')
  /** 是否已进入 thinking 阶段（true 后 tool 提示不再更新） */
  const thinkingStartedRef = useRef(false)

  const sendMessage = useCallback(async (content: string, conversationId?: number | string) => {
    const userMsg: Message = {
      id: `user-${Date.now()}`,
      role: 'user',
      content,
      timestamp: Date.now(),
    }
    const assistantId = `assistant-${Date.now()}`
    const assistantMsg: Message = {
      id: assistantId,
      role: 'assistant',
      content: '',
      type: 'thinking' as const,
      timestamp: Date.now(),
    }

    setMessages((prev) => [...prev, userMsg, assistantMsg])
    setIsLoading(true)
    accumulatedRef.current = ''
    thinkingStartedRef.current = false
    setStreamContent('')

    abortRef.current = new AbortController()

    try {
      const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ conversationId, message: content }),
        signal: abortRef.current.signal,
      })

      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      if (!response.body) throw new Error('response.body is null')

      const reader = response.body.getReader()
      const decoder = new TextDecoder()

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        const text = decoder.decode(value, { stream: true })
        const lines = text.split('\n')

        for (const line of lines) {
          const trimmed = line.trim()
          if (!trimmed || !trimmed.startsWith('data:')) continue
          const data = trimmed.slice(5).trim()
          if (data === '[DONE]') break

          try {
            const event: SSEEvent = JSON.parse(data)
            switch (event.type) {
              case 'tool_call': {
                if (!thinkingStartedRef.current) {
                  accumulatedRef.current += `🔧 正在${toolLabel(event.toolName || '')}…\n`
                }
                break
              }
              case 'tool_result': {
                if (!thinkingStartedRef.current) {
                  accumulatedRef.current += `✅ ${toolLabel(event.toolName || '')}完成\n`
                }
                break
              }
              case 'thinking': {
                thinkingStartedRef.current = true
                accumulatedRef.current += event.content || ''
                break
              }
              case 'final': {
                accumulatedRef.current = event.content || ''
                break
              }
              case 'error': {
                accumulatedRef.current = event.content || '出错了'
                break
              }
            }
          } catch {
            // ignore parse errors
          }
        }

        // 每个 chunk 结束强制刷一次
        setStreamContent(accumulatedRef.current)
      }

      // 流结束，更新消息
      const finalContent = accumulatedRef.current
      setMessages((prev) =>
        prev.map((msg) =>
          msg.id === assistantId
            ? { ...msg, content: finalContent, type: 'final' as const }
            : msg
        )
      )
    } catch (err: unknown) {
      if (err instanceof Error && err.name === 'AbortError') return
      const errorContent = `出错了：${err instanceof Error ? err.message : String(err)}`
      setMessages((prev) =>
        prev.map((msg) =>
          msg.id === assistantId
            ? { ...msg, content: errorContent, type: 'final' as const }
            : msg
        )
      )
    } finally {
      setIsLoading(false)
      setStreamContent('')
      accumulatedRef.current = ''
      abortRef.current = null
    }
  }, [])

  const stopStreaming = useCallback(() => {
    abortRef.current?.abort()
  }, [])

  const clearMessages = useCallback(() => {
    setMessages([])
  }, [])

  return {
    messages,
    isLoading,
    streamContent,
    sendMessage,
    stopStreaming,
    clearMessages,
  }
}

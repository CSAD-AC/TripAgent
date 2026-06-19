import { useState, useRef, useCallback } from 'react'
import type { Message, SSEEvent } from '../types'

interface UseChatOptions {
  onStream?: (chunk: string) => void
}

export function useChat({ onStream }: UseChatOptions = {}) {
  const [messages, setMessages] = useState<Message[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [currentStreamContent, setCurrentStreamContent] = useState('')
  const [workflowNode, setWorkflowNode] = useState<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)

  const sendMessage = useCallback(async (content: string, conversationId?: number) => {
    // 添加用户消息
    const userMsg: Message = {
      id: `user-${Date.now()}`,
      role: 'user',
      content,
      timestamp: Date.now(),
    }
    setMessages((prev) => [...prev, userMsg])
    setIsLoading(true)
    setWorkflowNode('intent_analysis')

    // 创建 Assistant 占位消息
    const assistantId = `assistant-${Date.now()}`
    const assistantMsg: Message = {
      id: assistantId,
      role: 'assistant',
      content: '',
      type: 'thinking',
      timestamp: Date.now(),
    }
    setMessages((prev) => [...prev, assistantMsg])
    setCurrentStreamContent('')

    abortRef.current = new AbortController()

    try {
      const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          conversationId,
          message: content,
        }),
        signal: abortRef.current.signal,
      })

      if (!response.ok) throw new Error(`HTTP ${response.status}`)

      const reader = response.body!.getReader()
      const decoder = new TextDecoder()
      let accumulated = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        const chunk = decoder.decode(value, { stream: true })
        const lines = chunk.split('\n')

        for (const line of lines) {
          if (!line.startsWith('data: ')) continue
          const data = line.slice(6).trim()
          if (data === '[DONE]') break

          try {
            const event: SSEEvent = JSON.parse(data)
            switch (event.type) {
              case 'thinking':
                setCurrentStreamContent(event.content || '')
                break
              case 'node':
                setWorkflowNode(event.content || null)
                setCurrentStreamContent(event.content || '')
                break
              case 'tool_call':
                setCurrentStreamContent(`正在调用 ${event.tool}...`)
                break
              case 'final':
                accumulated += event.content || ''
                setCurrentStreamContent(accumulated)
                onStream?.(event.content || '')
                break
            }
          } catch {
            // 忽略解析错误
          }
        }
      }

      // 流结束，更新消息
      setMessages((prev) =>
        prev.map((msg) =>
          msg.id === assistantId
            ? { ...msg, content: accumulated || currentStreamContent, type: 'final' }
            : msg
        )
      )
    } catch (err: any) {
      if (err.name === 'AbortError') return
      setMessages((prev) =>
        prev.map((msg) =>
          msg.id === assistantId
            ? { ...msg, content: `出错了：${err.message}`, type: 'final' }
            : msg
        )
      )
    } finally {
      setIsLoading(false)
      setWorkflowNode(null)
      setCurrentStreamContent('')
      abortRef.current = null
    }
  }, [onStream])

  const stopStreaming = useCallback(() => {
    abortRef.current?.abort()
  }, [])

  const clearMessages = useCallback(() => {
    setMessages([])
  }, [])

  return {
    messages,
    isLoading,
    currentStreamContent,
    workflowNode,
    sendMessage,
    stopStreaming,
    clearMessages,
  }
}

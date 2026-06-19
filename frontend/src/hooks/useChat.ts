import { useState, useRef, useCallback } from 'react'
import type { Message, SSEEvent } from '../types'

export function useChat() {
  const [messages, setMessages] = useState<Message[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [streamContent, setStreamContent] = useState('')
  const [workflowNode, setWorkflowNode] = useState<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  const accumulatedRef = useRef('')

  const sendMessage = useCallback(async (content: string, conversationId?: number | string) => {
    console.log('[useChat] sendMessage called', { content, conversationId })

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

    setMessages((prev) => {
      console.log('[useChat] setMessages (initial)', { prevLen: prev.length, newLen: prev.length + 2, assistantId })
      return [...prev, userMsg, assistantMsg]
    })
    setIsLoading(true)
    setWorkflowNode(null)
    accumulatedRef.current = ''
    setStreamContent('')
    console.log('[useChat] state set: isLoading=true, streamContent=""')

    abortRef.current = new AbortController()

    try {
      console.log('[useChat] starting fetch to /api/chat/stream')
      const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ conversationId, message: content }),
        signal: abortRef.current.signal,
      })

      console.log('[useChat] fetch response', { ok: response.ok, status: response.status, type: response.type })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)

      if (!response.body) {
        console.error('[useChat] response.body is null!')
        throw new Error('response.body is null')
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let chunkCount = 0
      let totalChars = 0

      while (true) {
        const { done, value } = await reader.read()
        if (done) {
          console.log('[useChat] stream done', { chunkCount, totalChars, accumulatedFinal: accumulatedRef.current.length })
          break
        }

        chunkCount++
        const text = decoder.decode(value, { stream: true })
        console.log(`[useChat] chunk #${chunkCount}`, { rawLength: text.length, preview: text.slice(0, 100) })

        const lines = text.split('\n')
        let eventCount = 0

        for (const line of lines) {
          const trimmed = line.trim()
          if (!trimmed) continue
          if (!trimmed.startsWith('data:')) {
            console.log('[useChat] skip non-data line:', trimmed.slice(0, 80))
            continue
          }
          const data = trimmed.slice(5).trim()
          if (data === '[DONE]') {
            console.log('[useChat] received [DONE] marker')
            break
          }

          try {
            const event: SSEEvent = JSON.parse(data)
            eventCount++

            switch (event.type) {
      case 'thinking': {
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
              case 'node':
                console.log('[useChat] node event', { content: event.content })
                setWorkflowNode(event.content || null)
                break
              case 'tool_call':
                console.log('[useChat] tool_call event', { tool: (event as any).tool })
                accumulatedRef.current += `[调用 ${(event as any).tool}]`
                break
            }
          } catch (parseErr) {
            console.warn('[useChat] parse error for line:', data.slice(0, 80), parseErr)
          }
        }

        // 每个 read() chunk 结束，强制更新 streamContent
        console.log(`[useChat] after chunk #${chunkCount}`, {
          eventCount,
          accumulatedLen: accumulatedRef.current.length,
          accumulatedPreview: accumulatedRef.current.slice(-50),
        })
        setStreamContent(accumulatedRef.current)
      }

      // 流结束
      const finalContent = accumulatedRef.current
      console.log('[useChat] stream complete, updating message', { finalContentLen: finalContent.length, contentPreview: finalContent.slice(0, 50) })

      setMessages((prev) => {
        const updated = prev.map((msg) =>
          msg.id === assistantId
            ? { ...msg, content: finalContent, type: 'final' as const }
            : msg
        )
        const assistant = updated.find(m => m.id === assistantId)
        console.log('[useChat] setMessages (final update)', { assistantContentLen: assistant?.content?.length, assistantContent: assistant?.content?.slice(0, 50) })
        return updated
      })
    } catch (err: any) {
      if (err.name === 'AbortError') {
        console.log('[useChat] fetch aborted')
        return
      }
      console.error('[useChat] fetch error:', err.message)
      const errorContent = `出错了：${(err as Error).message}`
      setMessages((prev) =>
        prev.map((msg) =>
          msg.id === assistantId
            ? { ...msg, content: errorContent, type: 'final' as const }
            : msg
        )
      )
    } finally {
      console.log('[useChat] finally block', {
        accumulatedWas: accumulatedRef.current.length,
        isLoadingWillBe: false,
        streamContentWillBe: '',
      })
      setIsLoading(false)
      setWorkflowNode(null)
      setStreamContent('')
      accumulatedRef.current = ''
      abortRef.current = null
    }
  }, [])

  const stopStreaming = useCallback(() => {
    console.log('[useChat] stopStreaming called')
    abortRef.current?.abort()
  }, [])

  const clearMessages = useCallback(() => {
    console.log('[useChat] clearMessages called')
    setMessages([])
  }, [])

  return {
    messages,
    isLoading,
    streamContent,
    workflowNode,
    sendMessage,
    stopStreaming,
    clearMessages,
  }
}

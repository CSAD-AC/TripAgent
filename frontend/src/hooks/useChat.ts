import { useState, useRef, useCallback } from 'react'
import type { Message, SSEEvent, ToolCallInfo, StreamIteration } from '../types'

export function useChat() {
  const [messages, setMessages] = useState<Message[]>([])
  const [isLoading, setIsLoading] = useState(false)
  /** 所有迭代的思考文字拼接（兼容旧版 + 简单文本展示） */
  const [streamContent, setStreamContent] = useState('')
  /** 当前流输出中正在构建的工具调用（仅最新一轮） */
  const [currentToolCalls, setCurrentToolCalls] = useState<ToolCallInfo[]>([])
  /** 按迭代分段的完整流内容 */
  const [streamIterations, setStreamIterations] = useState<StreamIteration[]>([])
  /** 当前迭代编号 */
  const [currentIteration, setCurrentIteration] = useState(1)

  const abortRef = useRef<AbortController | null>(null)

  // 按迭代存储（0-indexed；index 0 = iteration 1）
  const iterationTextsRef = useRef<string[]>([''])
  const iterationToolCallsRef = useRef<ToolCallInfo[][]>([[]])
  const iterationRef = useRef(1)

  /** 从 refs 构建 StreamIteration[] */
  function buildIterations(): StreamIteration[] {
    const result: StreamIteration[] = []
    const maxLen = Math.max(
      iterationTextsRef.current.length,
      iterationToolCallsRef.current.length
    )
    for (let i = 0; i < maxLen; i++) {
      const text = iterationTextsRef.current[i] || ''
      const calls = iterationToolCallsRef.current[i] || []
      if (text || calls.length > 0) {
        result.push({ iteration: i + 1, text, toolCalls: calls })
      }
    }
    return result
  }

  /** 从 refs 刷新所有 UI 状态 */
  function flushUI() {
    const joined = iterationTextsRef.current.join('\n\n')
    setStreamContent(joined)
    setStreamIterations(buildIterations())

    // 当前迭代的工具调用
    const idx = iterationRef.current - 1
    setCurrentToolCalls(iterationToolCallsRef.current[idx] || [])
    setCurrentIteration(iterationRef.current)
  }

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

    // 重置所有状态
    iterationTextsRef.current = ['']
    iterationToolCallsRef.current = [[]]
    iterationRef.current = 1
    setStreamContent('')
    setStreamIterations([])
    setCurrentToolCalls([])
    setCurrentIteration(1)

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
              // ── thinking_token: 追加到当前迭代 ──
              case 'thinking_token': {
                const idx = iterationRef.current - 1
                iterationTextsRef.current[idx] =
                  (iterationTextsRef.current[idx] || '') + (event.content || '')
                break
              }

              // ── tool_call_start: 进入工具调用阶段 ──
              case 'tool_call_start': {
                // 保证当前迭代的工具列表存在但不重置（允许多个工具）
                break
              }

              // ── tool_call: 向当前迭代追加一个工具 ──
              case 'tool_call': {
                const idx = iterationRef.current - 1
                const calls = iterationToolCallsRef.current[idx] || []
                calls.push({
                  toolName: event.toolName || '未知工具',
                  toolArguments: event.toolArguments,
                  status: 'running',
                })
                iterationToolCallsRef.current[idx] = calls
                break
              }

              // ── tool_result: 更新当前迭代中最后一个 running 工具 ──
              case 'tool_result': {
                const idx = iterationRef.current - 1
                const calls = [...(iterationToolCallsRef.current[idx] || [])]
                for (let i = calls.length - 1; i >= 0; i--) {
                  if (calls[i].status === 'running') {
                    calls[i] = { ...calls[i], status: 'success', result: event.toolResult || event.content }
                    break
                  }
                }
                iterationToolCallsRef.current[idx] = calls
                break
              }

              // ── tool_error: 更新当前迭代中最后一个 running 工具 ──
              case 'tool_error': {
                const idx = iterationRef.current - 1
                const calls = [...(iterationToolCallsRef.current[idx] || [])]
                for (let i = calls.length - 1; i >= 0; i--) {
                  if (calls[i].status === 'running') {
                    calls[i] = { ...calls[i], status: 'error', error: event.toolResult || event.content }
                    break
                  }
                }
                iterationToolCallsRef.current[idx] = calls
                break
              }

              // ── iteration_separator: 开始下一轮 ──
              case 'iteration_separator': {
                iterationRef.current += 1
                // 为下一轮初始化
                const nextIdx = iterationRef.current - 1
                if (!iterationTextsRef.current[nextIdx]) {
                  iterationTextsRef.current[nextIdx] = ''
                }
                if (!iterationToolCallsRef.current[nextIdx]) {
                  iterationToolCallsRef.current[nextIdx] = []
                }
                break
              }

              // ── final: 最终答案——替换最后迭代的文本 ──
              case 'final': {
                const lastIdx = iterationRef.current - 1
                if (event.content) {
                  iterationTextsRef.current[lastIdx] = event.content
                }
                break
              }

              // ── error: 不可恢复异常 ──
              case 'error': {
                const lastIdx = iterationRef.current - 1
                iterationTextsRef.current[lastIdx] = event.content || '出错了'
                break
              }
            }
          } catch {
            // ignore parse errors
          }
        }

        // 每个 chunk 结束强制刷新 UI
        flushUI()
      }

      // 流结束，将完整数据写入消息
      const finalTexts = [...iterationTextsRef.current]
      const finalToolCalls = [...iterationToolCallsRef.current]
      const finalIter = iterationRef.current
      const finalIterations = buildIterations()

      setMessages((prev) =>
        prev.map((msg) =>
          msg.id === assistantId
            ? {
                ...msg,
                content: finalTexts.join('\n\n'),
                type: 'final' as const,
                toolCalls: finalToolCalls.flat().length > 0 ? finalToolCalls.flat() : undefined,
                iterationData: finalIterations.length > 0 ? finalIterations : undefined,
                iterationCount: finalIter > 1 ? finalIter : undefined,
              }
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
      setStreamIterations([])
      setCurrentToolCalls([])
      setCurrentIteration(1)
      iterationTextsRef.current = ['']
      iterationToolCallsRef.current = [[]]
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
    streamIterations,
    currentToolCalls,
    currentIteration,
    sendMessage,
    stopStreaming,
    clearMessages,
  }
}

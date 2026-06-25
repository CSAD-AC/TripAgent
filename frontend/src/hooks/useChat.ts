import { useState, useRef, useCallback } from 'react'
import type {
  Message,
  SSEEvent,
  ToolCallInfo,
  StreamIteration,
  PendingClarification,
} from '../types'

export function useChat() {
  const [messages, setMessages] = useState<Message[]>([])
  const [isLoading, setIsLoading] = useState(false)
  /** 所有迭代的思考文字拼接（兼容旧版 + 简单文本展示） */
  const [streamContent, setStreamContent] = useState('')
  /** 按迭代分段的完整流内容 */
  const [streamIterations, setStreamIterations] = useState<StreamIteration[]>([])
  /** 当前迭代编号 */
  const [currentIteration, setCurrentIteration] = useState(1)
  /** 当前等待回答的反问（null 表示无） */
  const [pendingClarification, setPendingClarification] = useState<PendingClarification | null>(null)

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

    const idx = iterationRef.current - 1
    setCurrentToolCalls(iterationToolCallsRef.current[idx] || [])
    setCurrentIteration(iterationRef.current)
  }

  // 临时保留 setCurrentToolCalls 以保持导出形状;实际用本地 setter
  const [, setCurrentToolCalls] = useState<ToolCallInfo[]>([])

  /**
   * 处理 SSE 事件(集中分发)
   * @param event 单个事件
   * @param onSessionInit 收到 session_init 时的回调(用于写 URL hash)
   */
  function handleEvent(event: SSEEvent, onSessionInit?: (id: string) => void) {
    switch (event.type) {
      // ── session_init: 后端权威下发 conversationId ──
      case 'session_init': {
        if (event.conversationId && onSessionInit) {
          onSessionInit(event.conversationId)
        }
        break
      }

      // ── heartbeat: 静默忽略(防反向代理 timeout,前端不需要展示) ──
      case 'heartbeat': {
        // 透传,什么都不做
        break
      }

      // ── clarification_request: 弹出反问问题卡 ──
      case 'clarification_request': {
        if (event.questionId && event.conversationId && event.content) {
          let options: { label: string; value: string }[] = []
          if (event.toolArguments) {
            try {
              options = JSON.parse(event.toolArguments)
            } catch {
              options = []
            }
          }
          setPendingClarification({
            questionId: event.questionId,
            conversationId: event.conversationId,
            question: event.content,
            options,
            allowCustom: event.allowCustom ?? true,
          })
        }
        break
      }

      // ── thinking_token: 追加到当前迭代 ──
      case 'thinking_token': {
        const idx = iterationRef.current - 1
        iterationTextsRef.current[idx] =
          (iterationTextsRef.current[idx] || '') + (event.content || '')
        break
      }

      case 'tool_call_start': {
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
  }

  const sendMessage = useCallback(
    async (content: string, conversationId?: string, onSessionInit?: (id: string) => void) => {
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
      setPendingClarification(null)  // 清空上一轮的反问

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
              handleEvent(event, onSessionInit)
            } catch {
              // ignore parse errors
            }
          }

          flushUI()
        }

        // 流结束,将完整数据写入消息
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
    },
    []
  )

  /**
   * 提交反问答案(由 ClarificationCard 回调)
   *
   * <p>不重新打开 SSE,直接 POST 到 /api/chat/answer
   * <p>答案提交后,后端解除阻塞,继续通过现有 SSE 推后续事件
   */
  const submitClarificationAnswer = useCallback(async (answer: string) => {
    const current = pendingClarification
    if (!current) {
      console.warn('[useChat] submitClarificationAnswer: 没有等待中的反问')
      return
    }
    try {
      const res = await fetch('/api/chat/answer', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          conversationId: current.conversationId,
          questionId: current.questionId,
          answer,
        }),
      })
      if (!res.ok) {
        const errText = await res.text()
        throw new Error(`提交失败: HTTP ${res.status} ${errText}`)
      }
      // 答案已提交,清掉卡片(后端会通过现有 SSE 推后续事件)
      setPendingClarification(null)
    } catch (err) {
      console.error('[useChat] submitClarificationAnswer error:', err)
      throw err
    }
  }, [pendingClarification])

  const stopStreaming = useCallback(() => {
    abortRef.current?.abort()
  }, [])

  const clearMessages = useCallback(() => {
    setMessages([])
    setPendingClarification(null)
  }, [])

  return {
    messages,
    isLoading,
    streamContent,
    streamIterations,
    currentIteration,
    pendingClarification,
    sendMessage,
    submitClarificationAnswer,
    stopStreaming,
    clearMessages,
  }
}

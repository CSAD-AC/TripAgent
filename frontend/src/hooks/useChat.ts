import { useState, useRef, useCallback } from 'react'
import type {
  Message,
  SSEEvent,
  ToolCallInfo,
  StreamIteration,
  PendingClarification,
  ApiMode,
  GraphTrace,
  ConversationDetail,
  ApiResult,
} from '../types'

export function useChat() {
  const [messages, setMessages] = useState<Message[]>([])
  const [isLoading, setIsLoading] = useState(false)
  /** 所有迭代的思考文字拼接（兼容旧版 + 简单文本展示） */
  const [streamContent, setStreamContent] = useState('')
  /** 链路追踪 ID (trace_id 优化 #8) -- 后端 session_init 下发, 内部用 (error 事件写入 message.traceId) */
  const [traceId, setTraceId] = useState<string | null>(null)
  /** 按迭代分段的完整流内容 */
  const [streamIterations, setStreamIterations] = useState<StreamIteration[]>([])
  /** 当前迭代编号 */
  const [currentIteration, setCurrentIteration] = useState(1)
  /** 当前等待回答的反问（null 表示无） */
  const [pendingClarification, setPendingClarification] = useState<PendingClarification | null>(null)
  /** Graph 流追踪（仅 Graph 模式有值,用于 GraphFlow DAG 可视化） */
  const [graphTrace, setGraphTrace] = useState<GraphTrace | null>(null)

  const abortRef = useRef<AbortController | null>(null)
  /** 当前正在流式写入的 assistant 消息 ID(error 事件需要标记这条消息) */
  const currentAssistantIdRef = useRef<string | null>(null)

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
      // ── session_init: 后端权威下发 conversationId 与 traceId(#8 trace_id) ──
      case 'session_init': {
        if (event.conversationId && onSessionInit) {
          onSessionInit(event.conversationId)
        }
        if (event.traceId) {
          setTraceId(event.traceId)
        }
        break
      }

      // ── heartbeat: 静默忽略(防反向代理 timeout,前端不需要展示) ──
      case 'heartbeat': {
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
      // 并行模式: 多个 tool_call 可能在 T=0 同时到达, 都先记为 running 等待 tool_result 归属
      case 'tool_call': {
        const idx = iterationRef.current - 1
        const calls = iterationToolCallsRef.current[idx] || []
        calls.push({
          toolCallId: event.toolCallId,
          toolName: event.toolName || '未知工具',
          toolArguments: event.toolArguments,
          status: 'running',
        })
        iterationToolCallsRef.current[idx] = calls
        break
      }

      // ── tool_result: 通过 toolCallId 匹配具体调用 (兼容并行模式乱序到达) ──
      // 旧实现采用"找最后一个 running"的匹配策略, 在串行模式下有效, 但并行模式下
      // 后端会按完成顺序发射 tool_result, 必须按 ID 精确归属
      case 'tool_result': {
        const idx = iterationRef.current - 1
        const calls = [...(iterationToolCallsRef.current[idx] || [])]
        const targetIdx = event.toolCallId
          ? calls.findIndex(c => c.toolCallId === event.toolCallId)
          : calls.findIndex(c => c.status === 'running')
        if (targetIdx >= 0) {
          calls[targetIdx] = {
            ...calls[targetIdx],
            status: 'success',
            result: event.toolResult || event.content,
          }
        }
        iterationToolCallsRef.current[idx] = calls
        break
      }

      // ── tool_error: 通过 toolCallId 匹配具体调用 (兼容并行模式乱序到达) ──
      case 'tool_error': {
        const idx = iterationRef.current - 1
        const calls = [...(iterationToolCallsRef.current[idx] || [])]
        const targetIdx = event.toolCallId
          ? calls.findIndex(c => c.toolCallId === event.toolCallId)
          : calls.findIndex(c => c.status === 'running')
        if (targetIdx >= 0) {
          calls[targetIdx] = {
            ...calls[targetIdx],
            status: 'error',
            error: event.toolResult || event.content,
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

      // ── node_status: Graph 新模式 - 节点生命周期状态变化 ──
      case 'node_status': {
        const nodeName = event.node || ''
        const status = event.nodeStatus || ''
        setGraphTrace((prev) => {
          if (!prev) {
            return {
              currentNode: status === 'running' ? nodeName : null,
              completedNodes: status === 'done' ? [nodeName] : [],
              branches: [],
              warnings: [],
              nodeStatusMap: { [nodeName]: status },
            }
          }
          const nodeStatusMap = { ...(prev.nodeStatusMap || {}), [nodeName]: status }
          let completedNodes = prev.completedNodes
          if (status === 'done' && !completedNodes.includes(nodeName)) {
            completedNodes = [...completedNodes, nodeName]
          }
          return {
            ...prev,
            currentNode: status === 'running' ? nodeName : null,
            completedNodes,
            nodeStatusMap,
          }
        })
        break
      }

      // ── graph_topology: 存储拓扑数据 ──
      case 'graph_topology': {
        const topologyData = event.data as {
          nodes: { id: string; label: string; type: string; description: string }[]
          edges: { from: string; to: string; label: string; conditions: string[] }[]
          startNode: string
          endNode: string
        } | undefined
        if (topologyData) {
          setGraphTrace((prev) => ({
            currentNode: prev?.currentNode || null,
            completedNodes: prev?.completedNodes || [],
            branches: prev?.branches || [],
            warnings: prev?.warnings || [],
            topology: {
              nodes: topologyData.nodes || [],
              edges: topologyData.edges || [],
              startNode: topologyData.startNode || 'manager',
              endNode: topologyData.endNode || 'report',
            },
            nodeStatusMap: prev?.nodeStatusMap || {},
            nodeDataMap: prev?.nodeDataMap || {},
          }))
        }
        break
      }

      // ── node_data: 存储节点输出数据 ──
      case 'node_data': {
        setGraphTrace((prev) => {
          if (!prev) return prev
          const nodeName = event.node || ''
          const existing = prev.nodeDataMap || {}
          return {
            ...prev,
            nodeDataMap: {
              ...existing,
              [nodeName]: {
                node: nodeName,
                dataType: event.dataType || '',
                data: (event.data as Record<string, unknown>) || {},
              },
            },
          }
        })
        break
      }

      // ── node_error: Graph 模式 - 节点执行失败 (M3/M4 修复配套)
      //    在 nodeStatusMap 标 error, 同时累积到 warnings 供 GraphFlow 展示
      case 'node_error': {
        const nodeName = event.node || ''
        if (!nodeName) break
        setGraphTrace((prev) => {
          const base = prev || {
            currentNode: null,
            completedNodes: [],
            branches: [],
            warnings: [],
          }
          return {
            ...base,
            currentNode: null,
            nodeStatusMap: { ...(base.nodeStatusMap || {}), [nodeName]: 'error' },
            warnings: [
              ...(base.warnings || []),
              `[${nodeName}] ${event.content || '节点执行失败'}`,
            ],
          }
        })
        break
      }

      // ── node_progress: 累积节点实时进度(LLM 思考 / 工具调用中间结果) ──
      //    并行场景下 route 和 itinerary 会同时累积各自的进度,
      //    NodeDetailPanel 选中节点时按时间序列展示
      case 'node_progress': {
        const nodeName = event.node || ''
        if (!nodeName) break
        setGraphTrace((prev) => {
          const existingMap = prev?.nodeProgressMap || {}
          const prevList = existingMap[nodeName] || []
          // 同 progressType 连续多条累积成一条,避免抖动
          const lastEntry = prevList[prevList.length - 1]
          let nextList: typeof prevList
          if (lastEntry && lastEntry.progressType === event.progressType) {
            nextList = prevList.map((e, i) =>
              i === prevList.length - 1
                ? { ...e, content: e.content + (event.content || ''), timestamp: Date.now() }
                : e
            )
          } else {
            nextList = [
              ...prevList,
              {
                progressType: event.progressType || 'thinking',
                content: event.content || '',
                timestamp: Date.now(),
              },
            ]
          }
          return {
            ...(prev || {
              currentNode: null,
              completedNodes: [],
              branches: [],
              warnings: [],
            }),
            nodeProgressMap: {
              ...existingMap,
              [nodeName]: nextList,
            },
          }
        })
        break
      }

      // ── graph_iteration: 更新迭代信息 ──
      case 'graph_iteration': {
        setGraphTrace((prev) => ({
          currentNode: prev?.currentNode || null,
          completedNodes: prev?.completedNodes || [],
          branches: prev?.branches || [],
          warnings: prev?.warnings || [],
          topology: prev?.topology,
          nodeStatusMap: prev?.nodeStatusMap || {},
          nodeDataMap: prev?.nodeDataMap || {},
          iterationCount: event.iterationCount || 0,
          maxIterations: event.maxIterations || 3,
          iterationReason: event.content || '',
        }))
        break
      }

      // ── branch_taken: Graph 模式 - 条件边选择 ──
      case 'branch_taken': {
        setGraphTrace((prev) => {
          // 优先使用新协议的 from/to 字段，降级到旧协议的 toolName/toolArguments
          const from = event.from || event.toolName || ''
          const to = event.to || event.toolArguments || ''
          if (!from || !to) return prev
          if (!prev) return null
          return {
            ...prev,
            branches: [...(prev.branches || []), { from, to, condition: event.condition }],
          }
        })
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

      // ── error: 不可恢复异常
      //    标记当前 assistant 消息为 error 状态并附 traceId(#8),
      //    流结束 setMessages 时会把 content 写到 message 上,这里直接标记即可
      case 'error': {
        const lastIdx = iterationRef.current - 1
        iterationTextsRef.current[lastIdx] = event.content || '出错了'
        const assistantId = currentAssistantIdRef.current
        if (assistantId) {
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantId
                ? { ...msg, error: true, traceId: traceId ?? undefined }
                : msg
            )
          )
        }
        break
      }
    }
  }

  /**
   * 发送消息(根据 apiMode 选择端点)
   *
   * @param content 消息内容
   * @param apiMode 'react' = /api/chat/stream  (ReAct 工具调用)
   *               'graph' = /api/chat/graph   (Graph 工作流)
   * @param conversationId 会话 ID(续聊时携带)
   * @param modelId 模型业务 ID(GET /api/models 的 id 字段; null = 后端默认)
   * @param superMode 超能模式(仅 react 模式生效; true = 轮次无限制, 默认关闭)
   * @param onSessionInit 收到 session_init 时的回调
   */
  const sendMessage = useCallback(
    async (
      content: string,
      apiMode: ApiMode,
      conversationId?: string,
      modelId?: string,
      superMode?: boolean,
      onSessionInit?: (id: string) => void
    ) => {
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
        apiMode,
      }

      setMessages((prev) => [...prev, userMsg, assistantMsg])
      setIsLoading(true)
      setPendingClarification(null)  // 清空上一轮的反问
      currentAssistantIdRef.current = assistantId  // error 事件标记用

      // 重置所有状态
      iterationTextsRef.current = ['']
      iterationToolCallsRef.current = [[]]
      iterationRef.current = 1
      setStreamContent('')
      setStreamIterations([])
      setCurrentToolCalls([])
      setCurrentIteration(1)
      // Graph 模式重置追踪;ReAct 模式清空
      if (apiMode === 'graph') {
        setGraphTrace({ currentNode: null, completedNodes: [], branches: [], warnings: [] })
      } else {
        setGraphTrace(null)
      }

      abortRef.current = new AbortController()

      // 根据 mode 选择 endpoint
      const endpoint = apiMode === 'graph' ? '/api/chat/graph' : '/api/chat/stream'

      try {
        const response = await fetch(endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ conversationId, message: content, modelId, superMode: superMode || undefined }),
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
              ? {
                  ...msg,
                  content: errorContent,
                  type: 'final' as const,
                  error: true,
                  traceId: traceId ?? undefined,
                }
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
        currentAssistantIdRef.current = null
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
    setGraphTrace(null)
  }, [])

  /**
   * 加载已有对话的历史消息。
   * 当用户从侧边栏选择对话时调用,替代 sendMessage 的初始化流程。
   */
  const loadConversation = useCallback(async (id: string) => {
    try {
      const res = await fetch(`/api/conversations/${id}`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const body: ApiResult<ConversationDetail> = await res.json()
      if (body.code !== 200 || !body.data) {
        throw new Error(body.message || '加载失败')
      }
      const detail = body.data

      // 对后端消息流进行分组：user / assistant → (tool)*, 把 tool 消息合并到前一条 assistant 的 toolCalls 中
      const grouped: Message[] = []
      let pendingAssistant: Message | null = null

      /** flush pendingAssistant 到 grouped, 如有 toolCalls 则构建 iterationData */
      function flushAssistant(msg: Message | null) {
        if (!msg) return
        if (msg.toolCalls && msg.toolCalls.length > 0) {
          msg.iterationData = [{
            iteration: 1,
            text: msg.content,
            toolCalls: msg.toolCalls,
          }]
        }
        grouped.push(msg)
      }

      for (const m of (detail.messages || [])) {
        if (m.role === 'user') {
          // 先 flush 上一个 pending 的 assistant
          flushAssistant(pendingAssistant)
          pendingAssistant = null
          grouped.push({
            id: `hist-${detail.id}-${m.sequenceNum ?? Date.now()}`,
            role: 'user',
            content: m.content || '',
            type: 'final' as const,
            timestamp: m.createdAt ? new Date(m.createdAt).getTime() : Date.now(),
            apiMode: (detail.mode as ApiMode) || 'react',
          })
        } else if (m.role === 'assistant') {
          // flush previous assistant
          flushAssistant(pendingAssistant)
          // 解析 metadata 中的 tool_calls
          let toolCalls: ToolCallInfo[] | undefined
          if (m.metadata) {
            try {
              const parsed = JSON.parse(m.metadata)
              if (Array.isArray(parsed)) {
                toolCalls = parsed.map((tc: any) => ({
                  toolCallId: tc.id,
                  toolName: tc.name || tc.toolName,
                  toolArguments: tc.arguments,
                  status: 'success' as const,
                  result: '',
                }))
              }
            } catch { /* ignore parse errors */ }
          }
          pendingAssistant = {
            id: `hist-${detail.id}-${m.sequenceNum ?? Date.now()}`,
            role: 'assistant',
            content: m.content || '',
            type: 'final' as const,
            timestamp: m.createdAt ? new Date(m.createdAt).getTime() : Date.now(),
            apiMode: (detail.mode as ApiMode) || 'react',
            toolCalls,
          }
        } else if (m.role === 'tool' && pendingAssistant) {
          // 把 tool 响应合并到上一个 assistant 的 toolCalls 中
          const summary = m.content || ''
          let toolName = ''
          let responseData = ''
          // 从 metadata 解析
          if (m.metadata) {
            try {
              const parsed = JSON.parse(m.metadata)
              if (Array.isArray(parsed) && parsed.length > 0) {
                toolName = parsed[0].name || ''
                responseData = parsed[0].responseData || ''
              }
            } catch { /* ignore parse errors */ }
          }
          if (!toolName) {
            // 降级：从 content 摘要解析, 如 "amapWeather(北京 晴 25°C)"
            const parenIdx = summary.indexOf('(')
            toolName = parenIdx > 0 ? summary.slice(0, parenIdx) : 'history'
          }
          const existing: ToolCallInfo[] = pendingAssistant.toolCalls || []
          // 找同名的 toolCall 注入 result
          const matchIdx = existing.findIndex((tc: ToolCallInfo) => tc.toolName === toolName)
          if (matchIdx >= 0) {
            existing[matchIdx] = { ...existing[matchIdx], result: responseData || summary }
          } else {
            existing.push({
              toolCallId: '',
              toolName,
              status: 'success' as const,
              result: responseData || summary,
            })
          }
          pendingAssistant = { ...(pendingAssistant as Message), toolCalls: [...existing] }
        }
      }
      // flush last assistant
      flushAssistant(pendingAssistant)

      setMessages(grouped)
      setPendingClarification(null)
      setGraphTrace(null)
    } catch (err) {
      console.error('[useChat] loadConversation error:', err)
    }
  }, [])

  return {
    messages,
    isLoading,
    streamContent,
    streamIterations,
    currentIteration,
    pendingClarification,
    graphTrace,
    sendMessage,
    submitClarificationAnswer,
    stopStreaming,
    clearMessages,
    loadConversation,
  }
}

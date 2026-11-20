import { useState, useRef, useEffect, useCallback, useMemo } from 'react'
import { Sidebar } from './components/Sidebar'
import { ChatMessage } from './components/ChatMessage'
import { ChatInput } from './components/ChatInput'
import { ClarificationCard } from './components/ClarificationCard'
import { GraphFlow } from './components/GraphFlow'
import { MultiAgentProgress } from './components/MultiAgentProgress'
import { useChat } from './hooks/useChat'
import { useConversations } from './hooks/useConversations'
import { Knowledge } from './pages/Knowledge'
import { MemoryPanel } from './components/MemoryPanel'
import { Menu, MapPin, GitBranch, Zap, Cpu, Rocket, Users, ArrowLeft, LogOut } from 'lucide-react'
import { cn } from './lib/utils'
import type { ApiMode, MemoryEventStatus, ModelInfo } from './types'
import { LoginPage } from './auth/LoginPage'
import { useAuth } from './auth/AuthProvider'
import { apiFetch } from './lib/apiClient'
import { attachMemoryEvents, isMemoryCommand } from './lib/memory'

export default function App() {
  const { user, loading } = useAuth()

  if (loading) {
    return (
      <div className="h-screen bg-warm-white dark:bg-[#1a1a1a] flex items-center justify-center">
        <div className="w-8 h-8 rounded-full border-2 border-terracotta-500 border-t-transparent animate-spin" />
      </div>
    )
  }
  if (!user) {
    return <LoginPage />
  }
  return <AuthenticatedApp />
}

function AuthenticatedApp() {
  const { user, logout } = useAuth()
  const [sidebarOpen, setSidebarOpen] = useState(false)
  /** 主视图: 'chat' = 聊天, 'knowledge' = 知识库管理 */
  const [view, setView] = useState<'chat' | 'knowledge' | 'memory'>('chat')
  /** API 模式: 'react' = ReAct 工具调用, 'graph' = Graph 工作流 */
  const [apiMode, setApiMode] = useState<ApiMode>('react')
  /** 模型列表(来自 GET /api/models, 顺序即后端配置, 第一项 deepseek 为默认) */
  const [models, setModels] = useState<ModelInfo[]>([])
  /** 当前选中模型 ID(默认选中列表第一项 = deepseek) */
  const [selectedModelId, setSelectedModelId] = useState<string>('')
  /** 超能模式: 工具调用与迭代轮次无限制(仅 ReAct 模式生效, 默认关闭) */
  const [superMode, setSuperMode] = useState(false)
  const messagesEndRef = useRef<HTMLDivElement>(null)

  // 首次挂载拉取模型列表; 失败时静默(不阻塞聊天, 后端会回退默认模型)
  useEffect(() => {
    apiFetch('/api/models')
      .then((r) => r.json())
      .then((body: { code: number; data?: ModelInfo[] }) => {
        if (body.code === 200 && body.data && body.data.length > 0) {
          setModels(body.data)
          // 默认选中第一项: 后端配置 deepseek 置顶, 即默认模型
          setSelectedModelId(body.data[0].id)
        }
      })
      .catch(() => {
        /* ignore */
      })
  }, [])

  const {
    conversationId,
    setConversationId,
    conversations,
    loading: conversationsLoading,
    total: conversationsTotal,
    deleteConversation,
    renameConversation,
    selectConversation,
    newConversation,
    fetchConversations,
  } = useConversations()

  const {
    messages,
    isLoading,
    streamContent,
    streamIterations,
    pendingClarification,
    graphTrace,
    sendMessage,
    submitClarificationAnswer,
    stopStreaming,
    clearMessages,
    loadConversation,
  } = useChat()

  /** 已载入消息区的会话 ID：避免重复请求，也用于判断是否需要重新载入 */
  const loadedConversationRef = useRef<string | undefined>(undefined)

  // ── 长期记忆变更：随会话持久化，作为消息时间线的一部分回放 ──
  const [memoryEvents, setMemoryEvents] = useState<MemoryEventStatus[]>([])
  const [pendingMemoryEvent, setPendingMemoryEvent] = useState<MemoryEventStatus | null>(null)
  const activeConversationIdRef = useRef<string | undefined>(undefined)
  const memoryResolvedRef = useRef(false)
  const memoryEventIdsAtSendRef = useRef<Set<string>>(new Set())
  const memoryLoadTokenRef = useRef(0)
  const memoryPollTimersRef = useRef<number[]>([])

  const fetchConversationMemoryEvents = useCallback(async (
    conversationId: string
  ): Promise<MemoryEventStatus[]> => {
    try {
      const res = await apiFetch(
        `/api/memory/events?conversationId=${encodeURIComponent(conversationId)}&limit=100`
      )
      if (!res.ok) return []
      const body = await res.json()
      const items = body?.data
      return Array.isArray(items) ? items : []
    } catch {
      return []
    }
  }, [])

  const loadConversationMemoryEvents = useCallback(async (conversationId: string) => {
    const token = ++memoryLoadTokenRef.current
    const events = await fetchConversationMemoryEvents(conversationId)
    if (
      memoryLoadTokenRef.current === token
      && activeConversationIdRef.current === conversationId
    ) {
      setMemoryEvents(events)
    }
  }, [fetchConversationMemoryEvents])

  const checkMemoryChanges = useCallback(async (conversationId?: string) => {
    if (!conversationId || memoryResolvedRef.current) return memoryResolvedRef.current
    const events = await fetchConversationMemoryEvents(conversationId)
    if (activeConversationIdRef.current !== conversationId) return false

    if (events.length > 0) {
      setMemoryEvents(events)
    }
    const newEvent = events.find(
      (event) => !memoryEventIdsAtSendRef.current.has(event.id)
    )
    if (newEvent) {
      setPendingMemoryEvent(null)
      if (newEvent.eventStatus === 'DONE' || newEvent.eventStatus === 'FAILED') {
        memoryResolvedRef.current = true
        return true
      }
      return false
    }
    return false
  }, [fetchConversationMemoryEvents])

  /** 一轮对话结束后分几次检查，覆盖 worker 的延迟窗口 */
  const scheduleMemoryCheck = useCallback((conversationId?: string) => {
    memoryPollTimersRef.current.forEach((id) => window.clearTimeout(id))
    memoryPollTimersRef.current = [2500, 6000, 10000, 16000, 25000].map((delay) =>
      window.setTimeout(() => {
        void checkMemoryChanges(conversationId)
      }, delay)
    )
    memoryPollTimersRef.current.push(
      window.setTimeout(async () => {
        const handled = await checkMemoryChanges(conversationId)
        if (!handled && !memoryResolvedRef.current) {
          memoryResolvedRef.current = true
          setPendingMemoryEvent((current) => current
            ? {
                ...current,
                eventStatus: 'DONE',
                resultStatus: 'NO_CHANGE',
                resultJson: JSON.stringify({
                  changed: false,
                  reason: 'duplicate_or_not_triggered',
                }),
                processedAt: new Date().toISOString(),
              }
            : null)
        }
      }, 35000)
    )
  }, [checkMemoryChanges])

  useEffect(() => {
    return () => {
      memoryPollTimersRef.current.forEach((id) => window.clearTimeout(id))
    }
  }, [])

  // 把后端下发的 session_init.conversationId 写回 URL hash
  // 新对话创建后刷新列表，使其出现在侧边栏
  const handleSessionInit = useCallback(
    (id: string) => {
      activeConversationIdRef.current = id
      // 本轮消息已由流式渲染写进消息区，标记为"已载入"，避免流结束后再回库覆盖
      loadedConversationRef.current = id
      void loadConversationMemoryEvents(id)
      if (id !== conversationId) {
        setConversationId(id)
        // 新 conversationId 说明是新创建的对话，刷新列表使其出现在侧边栏
        fetchConversations()
      }
    },
    [conversationId, setConversationId, fetchConversations, loadConversationMemoryEvents]
  )

  /**
   * 打开一个会话：必须先切回聊天视图，否则在知识库/记忆视图下点了历史对话
   * 只会改 hash 与侧栏高亮，消息区不可见（表现为"点了没反应/加载不出来"）。
   */
  const openConversation = useCallback(
    async (id: string) => {
      setView('chat')
      if (id === loadedConversationRef.current) {
        activeConversationIdRef.current = id
        if (memoryEvents.length === 0) void loadConversationMemoryEvents(id)
        return
      }
      activeConversationIdRef.current = id
      setPendingMemoryEvent(null)
      setMemoryEvents([])
      memoryResolvedRef.current = false
      clearMessages()
      const ok = await loadConversation(id)
      loadedConversationRef.current = ok ? id : undefined
      if (ok) void loadConversationMemoryEvents(id)
    },
    [
      clearMessages,
      loadConversation,
      loadConversationMemoryEvents,
      memoryEvents.length,
    ]
  )

  /**
   * conversationId 的权威变化入口：刷新页面(hash 恢复)、点击历史对话、
   * 浏览器前进/后退(hashchange) 都走这里统一载入消息。
   */
  useEffect(() => {
    if (!conversationId) {
      loadedConversationRef.current = undefined
      activeConversationIdRef.current = undefined
      setMemoryEvents([])
      setPendingMemoryEvent(null)
      return
    }
    // 流式生成中(含新会话的 session_init)不覆盖正在渲染的消息
    if (isLoading) return
    if (conversationId === loadedConversationRef.current) return
    void openConversation(conversationId)
  }, [conversationId, isLoading, openConversation])

  /** 一轮结束: 刷新侧栏 + 检查是否有新记忆被抽取出来 */
  const handleTurnComplete = useCallback(() => {
    fetchConversations()
    scheduleMemoryCheck(activeConversationIdRef.current)
  }, [fetchConversations, scheduleMemoryCheck])

  const handleSend = useCallback(
    (content: string) => {
      void (async () => {
        memoryResolvedRef.current = false
        memoryEventIdsAtSendRef.current = new Set(memoryEvents.map((event) => event.id))
        if (isMemoryCommand(content)) {
          // The optimistic record must sort after the user bubble created by
          // sendMessage on the same event-loop turn.
          const createdAt = new Date(Date.now() + 1000).toISOString()
          setPendingMemoryEvent({
            id: `pending-${Date.now()}`,
            conversationId,
            source: 'KEYWORD',
            eventStatus: 'PENDING',
            resultStatus: null,
            content,
            createdAt,
            processedAt: null,
          })
        } else {
          setPendingMemoryEvent(null)
        }
        sendMessage(
          content,
          apiMode,
          conversationId,
          selectedModelId || undefined,
          apiMode === 'react' ? superMode : false,
          handleSessionInit,
          handleTurnComplete
        )
      })()
    },
    [
      apiMode,
      conversationId,
      handleSessionInit,
      handleTurnComplete,
      memoryEvents,
      selectedModelId,
      sendMessage,
      superMode,
    ]
  )

  const openMemoryView = useCallback(() => {
    setView('memory')
    setSidebarOpen(false)
  }, [])

  const timelineMessages = useMemo(() => {
    const timelineEvents = pendingMemoryEvent
      ? [
          ...memoryEvents.filter((event) => event.id !== pendingMemoryEvent.id),
          pendingMemoryEvent,
        ]
      : memoryEvents
    return attachMemoryEvents(messages, timelineEvents)
  }, [memoryEvents, messages, pendingMemoryEvent])

  const lastAssistantId = useMemo(
    () => [...messages].reverse().find((message) => message.role === 'assistant')?.id,
    [messages]
  )

  // ── 从侧边栏选择对话 ──
  const handleSelectConversation = useCallback(
    (id: string) => {
      // 无论当前在哪个视图，点历史对话都应回到聊天
      setView('chat')
      setSidebarOpen(false)
      // 生成过程中不打断，避免流式内容被清空
      if (isLoading) return
      if (id !== conversationId) {
        // 交给 conversationId 的监听 effect 统一载入
        selectConversation(id)
        return
      }
      // 同一会话但消息区是空的(例如刚从记忆/知识库视图回来)，补载一次
      if (loadedConversationRef.current !== id) {
        loadedConversationRef.current = undefined
        void openConversation(id)
      }
    },
    [conversationId, isLoading, selectConversation, openConversation]
  )

  // ── 新建对话 ──
  const handleNewConversation = useCallback(() => {
    setView('chat')
    newConversation()
    loadedConversationRef.current = undefined
    activeConversationIdRef.current = undefined
    setMemoryEvents([])
    setPendingMemoryEvent(null)
    memoryResolvedRef.current = false
    clearMessages()
    setSidebarOpen(false)
  }, [newConversation, clearMessages])

  // ── 删除对话 ──
  const handleDeleteConversation = useCallback(
    async (id: string) => {
      await deleteConversation(id)
      if (id === conversationId) {
        loadedConversationRef.current = undefined
        activeConversationIdRef.current = undefined
        setMemoryEvents([])
        setPendingMemoryEvent(null)
        clearMessages()
      }
    },
    [deleteConversation, conversationId, clearMessages]
  )

  // ── 重命名对话 ──
  const handleRenameConversation = useCallback(
    async (id: string, title: string) => {
      await renameConversation(id, title)
    },
    [renameConversation]
  )

  // ── 聊天 ↔ 知识库视图切换(侧边栏"知识库"入口触发) ──
  const toggleView = useCallback(() => {
    setView((v) => (v === 'chat' ? 'knowledge' : 'chat'))
    setSidebarOpen(false)
  }, [])

  const toggleMemoryView = useCallback(() => {
    setView((v) => (v === 'memory' ? 'chat' : 'memory'))
    setSidebarOpen(false)
  }, [])

  // 自动滚动到底部(尊重 prefers-reduced-motion)
  useEffect(() => {
    const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    messagesEndRef.current?.scrollIntoView({
      behavior: prefersReduced ? 'auto' : 'smooth',
    })
  }, [messages, streamContent, pendingClarification, memoryEvents, pendingMemoryEvent])

  return (
    <div className="h-screen flex overflow-hidden bg-warm-white dark:bg-[#1a1a1a]">
      {/* 侧栏 */}
      <Sidebar
        conversationId={conversationId}
        conversations={conversations}
        loading={conversationsLoading}
        total={conversationsTotal}
        onSelect={handleSelectConversation}
        onNew={handleNewConversation}
        onDelete={handleDeleteConversation}
        onRename={handleRenameConversation}
        open={sidebarOpen}
        onToggle={() => setSidebarOpen(!sidebarOpen)}
        view={view}
        onToggleView={toggleView}
        onToggleMemoryView={toggleMemoryView}
      />

      {/* 主区域 */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* 顶栏 */}
        <header className="flex items-center gap-3 px-4 h-14 border-b border-warm-white-200 dark:border-charcoal-700 bg-white/80 dark:bg-charcoal-900/80 backdrop-blur-sm flex-shrink-0">
          <button
            onClick={() => setSidebarOpen(!sidebarOpen)}
            aria-label="打开侧边栏"
            className="p-3 -ml-2 rounded-lg hover:bg-warm-white dark:hover:bg-charcoal-800 transition-colors text-charcoal-500 dark:text-charcoal-300 md:hidden focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500 focus-visible:ring-offset-2 dark:focus-visible:ring-offset-charcoal-900"
          >
            <Menu className="w-5 h-5" />
          </button>
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-terracotta-500 flex items-center justify-center">
              <MapPin className="w-4 h-4 text-white" />
            </div>
            <div>
              <h1 className="font-display text-base font-semibold text-charcoal-900 dark:text-warm-white leading-tight">
                TravelPal
              </h1>
              <p className="text-[11px] text-charcoal-400 dark:text-charcoal-500">旅途规划助手</p>
            </div>
          </div>

          {/* 知识库视图下的返回聊天按钮 */}
          {(view === 'knowledge' || view === 'memory') && (
            <button
              onClick={toggleView}
              className="ml-auto flex flex-shrink-0 items-center gap-1.5 whitespace-nowrap px-2.5 py-1.5 rounded-md text-xs font-medium text-warm-white bg-charcoal-900 dark:bg-charcoal-700 hover:bg-charcoal-700 dark:hover:bg-charcoal-600 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500"
            >
              <ArrowLeft className="w-3.5 h-3.5" />
              返回聊天
            </button>
          )}

          {/* API 模式切换器 */}
          <div
            className={cn(
              'ml-auto flex items-center gap-1 p-1 rounded-lg bg-warm-white dark:bg-charcoal-800 border border-warm-white-200 dark:border-charcoal-700',
              (view === 'knowledge' || view === 'memory') && 'hidden'
            )}
            role="tablist"
            aria-label="API 模式"
          >
            <button
              role="tab"
              aria-selected={apiMode === 'react'}
              onClick={() => setApiMode('react')}
              disabled={isLoading}
              title="ReAct 工具调用路径(单 Agent 推理)"
              className={cn(
                'flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-xs font-medium transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500',
                apiMode === 'react'
                  ? 'bg-white dark:bg-charcoal-700 text-terracotta-600 dark:text-terracotta-400 shadow-sm'
                  : 'text-charcoal-500 dark:text-charcoal-400 hover:text-charcoal-700 dark:hover:text-charcoal-200',
                isLoading && 'opacity-50 cursor-not-allowed'
              )}
            >
              <Zap className="w-3.5 h-3.5" />
              <span className="hidden sm:inline">ReAct</span>
            </button>
            <button
              role="tab"
              aria-selected={apiMode === 'graph'}
              onClick={() => setApiMode('graph')}
              disabled={isLoading}
              title="Graph 工作流路径(Phase 3 SWV 三件套)"
              className={cn(
                'flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-xs font-medium transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500',
                apiMode === 'graph'
                  ? 'bg-white dark:bg-charcoal-700 text-terracotta-600 dark:text-terracotta-400 shadow-sm'
                  : 'text-charcoal-500 dark:text-charcoal-400 hover:text-charcoal-700 dark:hover:text-charcoal-200',
                isLoading && 'opacity-50 cursor-not-allowed'
              )}
            >
              <GitBranch className="w-3.5 h-3.5" />
              <span className="hidden sm:inline">Graph</span>
            </button>
            <button
              role="tab"
              aria-selected={apiMode === 'multi'}
              onClick={() => setApiMode('multi')}
              disabled={isLoading}
              title="Multi-Agent 主管调度路径(Manager 调度子代理)"
              className={cn(
                'flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-xs font-medium transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500',
                apiMode === 'multi'
                  ? 'bg-white dark:bg-charcoal-700 text-terracotta-600 dark:text-terracotta-400 shadow-sm'
                  : 'text-charcoal-500 dark:text-charcoal-400 hover:text-charcoal-700 dark:hover:text-charcoal-200',
                isLoading && 'opacity-50 cursor-not-allowed'
              )}
            >
              <Users className="w-3.5 h-3.5" />
              <span className="hidden sm:inline">多Agent</span>
            </button>
          </div>

          {/* 超能模式开关(仅 ReAct 模式显示, 默认关闭): 工具调用与迭代轮次无限制 */}
          {apiMode === 'react' && (
            <div
              className="hidden md:flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg bg-warm-white dark:bg-charcoal-800 border border-warm-white-200 dark:border-charcoal-700"
              title="超能模式: 工具调用与迭代轮次无限制(默认关闭)"
            >
              <Rocket className="w-3.5 h-3.5 text-terracotta-500 flex-shrink-0" />
              <button
                role="switch"
                aria-checked={superMode}
                aria-label="超能模式"
                onClick={() => setSuperMode(!superMode)}
                disabled={isLoading}
                className={cn(
                  'relative w-8 h-[18px] rounded-full transition-colors flex-shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500 focus-visible:ring-offset-2 dark:focus-visible:ring-offset-charcoal-900',
                  superMode
                    ? 'bg-terracotta-500'
                    : 'bg-charcoal-300 dark:bg-charcoal-600',
                  isLoading && 'opacity-50 cursor-not-allowed'
                )}
              >
                <span
                  className={cn(
                    'absolute top-[2px] left-[2px] w-[14px] h-[14px] rounded-full bg-white shadow-sm transition-transform',
                    superMode && 'translate-x-[14px]'
                  )}
                />
              </button>
              <span className="text-xs font-medium text-charcoal-500 dark:text-charcoal-400">
                超能
              </span>
            </div>
          )}

          {/* 模型选择器(列表来自 GET /api/models, 默认选中第一项 = deepseek) */}
          {models.length > 0 && (
            <div
              className="hidden lg:flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg bg-warm-white dark:bg-charcoal-800 border border-warm-white-200 dark:border-charcoal-700"
              title="选择 LLM 模型"
            >
              <Cpu className="w-3.5 h-3.5 text-terracotta-500 flex-shrink-0" />
              <select
                value={selectedModelId}
                onChange={(e) => setSelectedModelId(e.target.value)}
                disabled={isLoading}
                aria-label="选择模型"
                className="bg-transparent text-xs font-medium text-charcoal-600 dark:text-charcoal-300 focus:outline-none focus-visible:ring-0 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {models.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.displayName}
                  </option>
                ))}
              </select>
            </div>
          )}

          {/* 当前会话 ID 标识(8 位缩写) */}
          {conversationId && (
            <div className="hidden md:flex items-center gap-1.5 text-xs text-charcoal-400 dark:text-charcoal-500 font-mono">
              <span className="opacity-50">#</span>
              {conversationId.slice(0, 8)}
            </div>
          )}

          <button
            type="button"
            onClick={() => void logout()}
            title={`退出登录 ${user?.displayName || ''}`}
            className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium text-charcoal-500 dark:text-charcoal-400 hover:bg-warm-white dark:hover:bg-charcoal-800 border border-transparent hover:border-warm-white-200 dark:hover:border-charcoal-700"
          >
            <LogOut className="w-3.5 h-3.5" />
            <span className="hidden lg:inline max-w-24 truncate">
              {user?.displayName || '退出'}
            </span>
          </button>
        </header>

        {/* 知识库视图: 渲染管理页 */}
        {view === 'memory' ? (
          <MemoryPanel onBack={toggleMemoryView} />
        ) : view === 'knowledge' ? (
          <Knowledge />
        ) : (
          <>
        {/* Graph 模式 DAG 流程图 */}
        {apiMode === 'graph' && graphTrace && (
          <GraphFlow
            graphTrace={
              isLoading || graphTrace.completedNodes.length > 0
                ? graphTrace
                : null
            }
            visible={isLoading || graphTrace.completedNodes.length > 0}
          />
        )}

        {/* Multi-Agent 子代理调度进度面板(修复 2: node_progress 可视化) */}
        {apiMode === 'multi' && graphTrace &&
          Object.keys(graphTrace.nodeProgressMap || {}).length > 0 && (
            <MultiAgentProgress progressMap={graphTrace.nodeProgressMap || {}} />
          )}

        {/* 消息列表 */}
        <div className="flex-1 overflow-y-auto">
          <div className="max-w-3xl mx-auto px-4 py-6 space-y-4">
            {/* 空状态 */}
            {messages.length === 0 && (
              <div className="flex flex-col items-center justify-center py-20 text-center" role="status">
                <div className="w-16 h-16 rounded-2xl bg-terracotta-50 dark:bg-terracotta-900/30 flex items-center justify-center mb-4">
                  <MapPin className="w-7 h-7 text-terracotta-500" />
                </div>
                <h2 className="font-display text-xl font-semibold text-charcoal-900 dark:text-warm-white mb-2">
                  想去哪里看看？
                </h2>
                <p className="text-sm text-charcoal-400 dark:text-charcoal-500 max-w-sm">
                  告诉我你的目的地和天数，让我为你规划一段独特的旅程
                </p>
                {/* 模式提示 */}
                <p className="text-xs text-charcoal-300 dark:text-charcoal-600 mt-4">
                  当前模式: {apiMode === 'graph' ? 'Graph 工作流(SWV 三件套)' : apiMode === 'multi' ? 'Multi-Agent(主管调度子代理)' : 'ReAct 工具调用'}
                </p>
                {/* 超能模式提示(仅 ReAct 且开启时) */}
                {apiMode === 'react' && superMode && (
                  <p className="text-xs text-terracotta-500 dark:text-terracotta-400 mt-1">
                    超能模式已开启: 工具调用与迭代轮次无限制
                  </p>
                )}
              </div>
            )}

            {/* 消息列表 */}
            {timelineMessages.map((msg) => {
              const isLastAssistant = msg.id === lastAssistantId
              return (
                <ChatMessage
                  key={msg.id}
                  message={msg}
                  isStreaming={isLastAssistant && isLoading}
                  streamContent={isLastAssistant ? streamContent : undefined}
                  streamIterations={isLastAssistant ? streamIterations : undefined}
                  conversationId={conversationId}
                  showSettle={isLastAssistant && !isLoading}
                  onOpenMemory={openMemoryView}
                />
              )
            })}

            {/* 反问问题卡(在最后一条 assistant 消息之后) */}
            {pendingClarification && (
              <ClarificationCard
                pending={pendingClarification}
                onSubmit={submitClarificationAnswer}
              />
            )}

            <div ref={messagesEndRef} />
          </div>
        </div>

        {/* 输入区 */}
        <ChatInput
          onSend={handleSend}
          onStop={stopStreaming}
          isLoading={isLoading}
          awaitingClarification={!!pendingClarification}
        />
          </>
        )}
      </div>

    </div>
  )
}

import { useState, useRef, useEffect, useCallback } from 'react'
import { Sidebar } from './components/Sidebar'
import { ChatMessage } from './components/ChatMessage'
import { ChatInput } from './components/ChatInput'
import { ClarificationCard } from './components/ClarificationCard'
import { GraphFlow } from './components/GraphFlow'
import { useChat } from './hooks/useChat'
import { useConversations } from './hooks/useConversations'
import { Menu, MapPin, GitBranch, Zap } from 'lucide-react'
import { cn } from './lib/utils'
import type { ApiMode } from './types'

export default function App() {
  const [sidebarOpen, setSidebarOpen] = useState(false)
  /** API 模式: 'react' = ReAct 工具调用, 'graph' = Graph 工作流 */
  const [apiMode, setApiMode] = useState<ApiMode>('react')
  const messagesEndRef = useRef<HTMLDivElement>(null)

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

  // 把后端下发的 session_init.conversationId 写回 URL hash
  // 新对话创建后刷新列表，使其出现在侧边栏
  const handleSessionInit = useCallback(
    (id: string) => {
      if (id !== conversationId) {
        setConversationId(id)
        // 新 conversationId 说明是新创建的对话，刷新列表使其出现在侧边栏
        fetchConversations()
      }
    },
    [conversationId, setConversationId, fetchConversations]
  )

  // 首次挂载时，如果 URL hash 中有 conversationId，自动恢复历史消息
  const initialLoadDoneRef = useRef(false)
  useEffect(() => {
    if (initialLoadDoneRef.current) return
    initialLoadDoneRef.current = true
    if (conversationId) {
      loadConversation(conversationId)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const handleSend = useCallback(
    (content: string) => {
      sendMessage(content, apiMode, conversationId, handleSessionInit)
    },
    [apiMode, conversationId, handleSessionInit, sendMessage]
  )

  // ── 从侧边栏选择对话 ──
  const handleSelectConversation = useCallback(
    (id: string) => {
      if (id === conversationId) return
      selectConversation(id)
      clearMessages()
      loadConversation(id)
      setSidebarOpen(false)
    },
    [conversationId, selectConversation, clearMessages, loadConversation]
  )

  // ── 新建对话 ──
  const handleNewConversation = useCallback(() => {
    newConversation()
    clearMessages()
    setSidebarOpen(false)
  }, [newConversation, clearMessages])

  // ── 删除对话 ──
  const handleDeleteConversation = useCallback(
    async (id: string) => {
      await deleteConversation(id)
      if (id === conversationId) {
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

  // 自动滚动到底部(尊重 prefers-reduced-motion)
  useEffect(() => {
    const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    messagesEndRef.current?.scrollIntoView({
      behavior: prefersReduced ? 'auto' : 'smooth',
    })
  }, [messages, streamContent, pendingClarification])

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

          {/* API 模式切换器 */}
          <div
            className="ml-auto flex items-center gap-1 p-1 rounded-lg bg-warm-white dark:bg-charcoal-800 border border-warm-white-200 dark:border-charcoal-700"
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
          </div>

          {/* 当前会话 ID 标识(8 位缩写) */}
          {conversationId && (
            <div className="hidden md:flex items-center gap-1.5 text-xs text-charcoal-400 dark:text-charcoal-500 font-mono">
              <span className="opacity-50">#</span>
              {conversationId.slice(0, 8)}
            </div>
          )}
        </header>

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
                  当前模式: {apiMode === 'graph' ? 'Graph 工作流(SWV 三件套)' : 'ReAct 工具调用'}
                </p>
              </div>
            )}

            {/* 消息列表 */}
            {messages.map((msg, idx) => {
              const isLastAssistant =
                idx === messages.length - 1 && msg.role === 'assistant'
              return (
                <ChatMessage
                  key={msg.id}
                  message={msg}
                  isStreaming={isLastAssistant && isLoading}
                  streamContent={isLastAssistant ? streamContent : undefined}
                  streamIterations={isLastAssistant ? streamIterations : undefined}
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
      </div>
    </div>
  )
}

import { useState, useRef, useEffect } from 'react'
import { Sidebar } from './components/Sidebar'
import { ChatMessage } from './components/ChatMessage'
import { ChatInput } from './components/ChatInput'
import { WorkflowStatus } from './components/WorkflowStatus'
import { useChat } from './hooks/useChat'
import { useConversations } from './hooks/useConversations'
import { Menu, MapPin } from 'lucide-react'

export default function App() {
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const messagesEndRef = useRef<HTMLDivElement>(null)

  const {
    conversations,
    activeId,
    selectConversation,
    newConversation,
    deleteConversation,
  } = useConversations()

  const { messages, isLoading, streamContent, workflowNode, sendMessage, stopStreaming } =
    useChat()

  console.log('[App] render', { messagesLen: messages.length, isLoading, streamContentLen: streamContent.length })

  // 自动滚动到底部（尊重 prefers-reduced-motion）
  useEffect(() => {
    const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    messagesEndRef.current?.scrollIntoView({
      behavior: prefersReduced ? 'auto' : 'smooth',
    })
  }, [messages, streamContent])

  const handleSend = (content: string) => {
    sendMessage(content, activeId)
  }

  return (
    <div className="h-screen flex overflow-hidden bg-warm-white dark:bg-[#1a1a1a]">
      {/* 侧栏 */}
      <Sidebar
        conversations={conversations}
        activeId={activeId}
        onSelect={selectConversation}
        onNew={newConversation}
        onDelete={deleteConversation}
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
        </header>

        {/* 工作流状态指示器 */}
        <WorkflowStatus currentNode={workflowNode} visible={isLoading} />

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
                />
              )
            })}

            <div ref={messagesEndRef} />
          </div>
        </div>

        {/* 输入区 */}
        <ChatInput
          onSend={handleSend}
          onStop={stopStreaming}
          isLoading={isLoading}
        />
      </div>
    </div>
  )
}

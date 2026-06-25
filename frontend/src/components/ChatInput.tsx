import { useState, useRef, useEffect } from 'react'
import { Send, Square, Sparkles } from 'lucide-react'
import { cn } from '../lib/utils'

interface ChatInputProps {
  onSend: (message: string) => void
  onStop: () => void
  isLoading: boolean
  disabled?: boolean
  /** 等待反问回答时禁用输入（用户在反问卡片里回答,不应在主输入框输入） */
  awaitingClarification?: boolean
}

const SUGGESTIONS = [
  '帮我规划一个北京3日游',
  '杭州周末去哪玩比较好？',
  '从上海到成都怎么走最划算？',
  '推荐一些大理的景点和美食',
]

export function ChatInput({ onSend, onStop, isLoading, disabled, awaitingClarification }: ChatInputProps) {
  const [input, setInput] = useState('')
  const inputRef = useRef<HTMLTextAreaElement>(null)

  // 自动调整 textarea 高度
  useEffect(() => {
    const el = inputRef.current
    if (el) {
      el.style.height = 'auto'
      el.style.height = Math.min(el.scrollHeight, 160) + 'px'
    }
  }, [input])

  // 是否真正禁用输入（反问等待 OR 正在流式 OR 显式 disabled）
  const isDisabled = awaitingClarification || isLoading || disabled

  const handleSend = () => {
    const trimmed = input.trim()
    if (!trimmed || isDisabled) return
    console.log('[ChatInput] handleSend', { content: trimmed })
    onSend(trimmed)
    setInput('')
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  return (
    <div className="border-t border-warm-white-200 dark:border-charcoal-700 bg-white/90 dark:bg-charcoal-900/90 backdrop-blur-sm">
      <div className="max-w-3xl mx-auto px-4 py-3 space-y-2">
        {/* 快捷建议(无消息且不在反问等待时显示) */}
        {!disabled && !isLoading && !awaitingClarification && (
          <div className="flex gap-2 overflow-x-auto pb-1 scrollbar-none" role="tablist" aria-label="快捷建议">
            {SUGGESTIONS.map((s, idx) => (
              <button
                key={idx}
                onClick={() => setInput(s)}
                aria-label={s}
                className="flex-shrink-0 flex items-center gap-1 px-3 py-2.5 rounded-full text-xs font-medium bg-warm-white dark:bg-charcoal-800 text-charcoal-500 dark:text-charcoal-300 hover:bg-terracotta-50 dark:hover:bg-terracotta-900/30 hover:text-terracotta-600 dark:hover:text-terracotta-400 transition-colors border border-warm-white-200 dark:border-charcoal-700 min-h-[44px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500"
              >
                <Sparkles className="w-3 h-3" />
                {s}
              </button>
            ))}
          </div>
        )}

        {/* 反问等待提示 */}
        {awaitingClarification && (
          <div
            className="text-xs text-terracotta-600 dark:text-terracotta-400 px-2 py-1.5 rounded-lg bg-terracotta-50 dark:bg-terracotta-900/20 flex items-center gap-1.5"
            role="status"
          >
            <span className="cursor-blink">●</span>
            等待你回答上方问题...
          </div>
        )}

        {/* 输入框 */}
        <div className="flex items-end gap-2">
          <div className="flex-1 relative">
            <textarea
              ref={inputRef}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={
                awaitingClarification
                  ? '请在上方回答问题...'
                  : '输入你的旅行需求...'
              }
              rows={1}
              disabled={isDisabled}
              aria-label="输入旅行需求"
              className={cn(
                'w-full resize-none rounded-xl border border-charcoal-200 dark:border-charcoal-700 bg-white dark:bg-charcoal-800 px-4 py-2.5 text-sm text-charcoal-900 dark:text-warm-white placeholder:text-charcoal-300 dark:placeholder:text-charcoal-500 focus-visible:outline-none focus-visible:border-terracotta-400 focus-visible:ring-2 focus-visible:ring-terracotta-100 dark:focus-visible:ring-terracotta-900 transition-colors',
                isDisabled && 'opacity-50'
              )}
            />
          </div>

          {isLoading ? (
            <button
              onClick={onStop}
              aria-label="停止生成"
              className="flex-shrink-0 flex items-center gap-1.5 px-4 py-3.5 rounded-xl bg-charcoal-100 dark:bg-charcoal-800 text-charcoal-600 dark:text-charcoal-300 hover:bg-charcoal-200 dark:hover:bg-charcoal-700 transition-colors text-sm font-medium min-h-[44px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500"
            >
              <Square className="w-3.5 h-3.5 fill-current" />
              停止
            </button>
          ) : (
            <button
              onClick={handleSend}
              disabled={!input.trim() || isDisabled}
              aria-label="发送消息"
              className={cn(
                'flex-shrink-0 flex items-center gap-1.5 px-4 py-3.5 rounded-xl text-sm font-medium transition-colors min-h-[44px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500',
                input.trim() && !isDisabled
                  ? 'bg-terracotta-500 text-white hover:bg-terracotta-600'
                  : 'bg-charcoal-100 dark:bg-charcoal-800 text-charcoal-300 dark:text-charcoal-500 cursor-not-allowed'
              )}
            >
              <Send className="w-3.5 h-3.5" />
              发送
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

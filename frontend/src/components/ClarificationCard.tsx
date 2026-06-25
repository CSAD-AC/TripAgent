import { useState } from 'react'
import { HelpCircle, Send } from 'lucide-react'
import { cn } from '../lib/utils'
import type { PendingClarification } from '../types'

interface ClarificationCardProps {
  /** 当前等待回答的反问 */
  pending: PendingClarification
  /** 提交答案给后端;返回 Promise 让调用方处理 loading 态 */
  onSubmit: (answer: string) => Promise<void> | void
}

/**
 * 反问问题卡（Phase 4 新增）
 *
 * <p>展示 LLM 抛出的反问问题,提供预设选项 + 自定义输入
 * <p>设计原则:
 * <ul>
 *   <li>选项卡横向滚动,移动端友好</li>
 *   <li>自定义输入框可折叠,避免视觉嘈杂</li>
 *   <li>提交后禁用所有按钮,防止重复提交</li>
 *   <li>使用 terracotta 主色 + 圆角,符合设计语言</li>
 * </ul>
 */
export function ClarificationCard({ pending, onSubmit }: ClarificationCardProps) {
  const [customValue, setCustomValue] = useState('')
  const [showCustom, setShowCustom] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const handleSelect = async (value: string) => {
    if (submitting) return
    setSubmitting(true)
    try {
      await onSubmit(value)
    } finally {
      setSubmitting(false)
    }
  }

  const handleCustomSubmit = () => {
    const trimmed = customValue.trim()
    if (!trimmed || submitting) return
    handleSelect(trimmed)
    setCustomValue('')
    setShowCustom(false)
  }

  return (
    <div
      className="rounded-2xl border-2 border-terracotta-300 dark:border-terracotta-700 bg-terracotta-50/50 dark:bg-terracotta-900/20 p-4 my-2 shadow-sm"
      role="region"
      aria-label="AI 询问"
    >
      {/* 头部:图标 + 提示 */}
      <div className="flex items-start gap-2 mb-3">
        <div className="flex-shrink-0 w-7 h-7 rounded-full bg-terracotta-500 flex items-center justify-center">
          <HelpCircle className="w-4 h-4 text-white" />
        </div>
        <div className="flex-1 min-w-0">
          <div className="text-[11px] font-semibold uppercase tracking-wider text-terracotta-600 dark:text-terracotta-400 mb-0.5">
            AI 询问
          </div>
          <div className="text-sm font-medium text-charcoal-900 dark:text-warm-white leading-relaxed">
            {pending.question}
          </div>
        </div>
      </div>

      {/* 预设选项(横向按钮) */}
      {pending.options.length > 0 && (
        <div className="flex flex-wrap gap-2 mb-2">
          {pending.options.map((opt, idx) => (
            <button
              key={idx}
              onClick={() => handleSelect(opt.value)}
              disabled={submitting}
              className={cn(
                'px-4 py-2.5 rounded-full text-sm font-medium transition-all min-h-[40px]',
                'bg-white dark:bg-charcoal-800 text-charcoal-700 dark:text-charcoal-200',
                'border border-charcoal-200 dark:border-charcoal-700',
                'hover:bg-terracotta-100 dark:hover:bg-terracotta-900/40',
                'hover:border-terracotta-400 hover:text-terracotta-700 dark:hover:text-terracotta-300',
                'disabled:opacity-50 disabled:cursor-not-allowed',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500'
              )}
            >
              {opt.label}
            </button>
          ))}
        </div>
      )}

      {/* 自定义输入(可折叠) */}
      {pending.allowCustom && (
        <>
          {!showCustom ? (
            <button
              onClick={() => setShowCustom(true)}
              disabled={submitting}
              className="text-xs text-charcoal-500 dark:text-charcoal-400 hover:text-terracotta-600 dark:hover:text-terracotta-400 underline-offset-2 hover:underline transition-colors min-h-[32px] focus-visible:outline-none focus-visible:underline"
            >
              其它(自己输入)
            </button>
          ) : (
            <div className="flex items-end gap-2 mt-2">
              <textarea
                value={customValue}
                onChange={(e) => setCustomValue(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault()
                    handleCustomSubmit()
                  }
                }}
                placeholder="输入你的回答..."
                rows={1}
                autoFocus
                disabled={submitting}
                className="flex-1 resize-none rounded-lg border border-charcoal-200 dark:border-charcoal-700 bg-white dark:bg-charcoal-800 px-3 py-2 text-sm focus-visible:outline-none focus-visible:border-terracotta-400 focus-visible:ring-2 focus-visible:ring-terracotta-100"
              />
              <button
                onClick={handleCustomSubmit}
                disabled={!customValue.trim() || submitting}
                aria-label="提交回答"
                className={cn(
                  'flex-shrink-0 flex items-center gap-1 px-3 py-2 rounded-lg text-sm font-medium transition-colors min-h-[40px]',
                  customValue.trim() && !submitting
                    ? 'bg-terracotta-500 text-white hover:bg-terracotta-600'
                    : 'bg-charcoal-100 dark:bg-charcoal-800 text-charcoal-300 cursor-not-allowed',
                  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500'
                )}
              >
                <Send className="w-3.5 h-3.5" />
                发送
              </button>
            </div>
          )}
        </>
      )}

      {submitting && (
        <div className="text-[11px] text-charcoal-400 dark:text-charcoal-500 mt-2">提交中...</div>
      )}
    </div>
  )
}

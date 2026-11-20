import {
  AlertTriangle,
  ArrowUpRight,
  Brain,
  CheckCircle2,
  Clock3,
  Info,
  Loader2,
  Plus,
} from 'lucide-react'
import { cn } from '../lib/utils'
import { parseMemoryEventDetails } from '../lib/memory'
import type { MemoryEventStatus } from '../types'

interface MemoryChangeCardProps {
  event: MemoryEventStatus
  onOpenMemory: () => void
}

type MemoryCardState = 'processing' | 'updated' | 'no_change' | 'failed'

function cardState(event: MemoryEventStatus): MemoryCardState {
  if (event.eventStatus === 'PENDING' || event.eventStatus === 'PROCESSING') {
    return 'processing'
  }
  if (event.eventStatus === 'FAILED' || event.resultStatus === 'FAILED') {
    return 'failed'
  }
  return event.resultStatus === 'UPDATED' ? 'updated' : 'no_change'
}

const STATE_STYLE: Record<MemoryCardState, {
  label: string
  icon: typeof Brain
  marker: string
  badge: string
  iconTone: string
}> = {
  processing: {
    label: '整理中',
    icon: Loader2,
    marker: 'border-terracotta-300 dark:border-terracotta-700',
    badge: 'bg-terracotta-50 text-terracotta-700 dark:bg-terracotta-900/30 dark:text-terracotta-300',
    iconTone: 'bg-terracotta-100 text-terracotta-700 dark:bg-terracotta-900/50 dark:text-terracotta-300',
  },
  updated: {
    label: '已写入',
    icon: CheckCircle2,
    marker: 'border-sage-300 dark:border-sage-700',
    badge: 'bg-sage-50 text-sage-700 dark:bg-sage-900/30 dark:text-sage-300',
    iconTone: 'bg-sage-100 text-sage-700 dark:bg-sage-900/50 dark:text-sage-300',
  },
  no_change: {
    label: '无需更新',
    icon: Info,
    marker: 'border-mustard-300 dark:border-mustard-700',
    badge: 'bg-mustard-50 text-mustard-700 dark:bg-mustard-900/30 dark:text-mustard-300',
    iconTone: 'bg-mustard-100 text-mustard-700 dark:bg-mustard-900/50 dark:text-mustard-300',
  },
  failed: {
    label: '处理失败',
    icon: AlertTriangle,
    marker: 'border-red-300 dark:border-red-800',
    badge: 'bg-red-50 text-red-700 dark:bg-red-950/30 dark:text-red-300',
    iconTone: 'bg-red-100 text-red-600 dark:bg-red-950/50 dark:text-red-300',
  },
}

function formatEventTime(event: MemoryEventStatus): string {
  const raw = event.processedAt || event.createdAt
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function FactList({
  label,
  facts,
}: {
  label: string
  facts: string[]
}) {
  if (facts.length === 0) return null
  return (
    <div>
      <div className="mb-1.5 text-[10px] font-semibold uppercase tracking-[0.14em] text-charcoal-400 dark:text-charcoal-500">
        {label}
      </div>
      <ul className="space-y-1.5">
        {facts.map((fact) => (
          <li
            key={fact}
            className="flex items-start gap-2 text-sm leading-5 text-charcoal-800 dark:text-charcoal-200"
          >
            <span className="mt-0.5 flex h-4 w-4 flex-shrink-0 items-center justify-center rounded-sm bg-sage-100 text-sage-700 dark:bg-sage-900/50 dark:text-sage-300">
              <Plus className="h-3 w-3" strokeWidth={2.5} />
            </span>
            <span className="min-w-0 break-words">{fact}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

export function MemoryChangeCard({ event, onOpenMemory }: MemoryChangeCardProps) {
  const state = cardState(event)
  const style = STATE_STYLE[state]
  const Icon = style.icon
  const details = parseMemoryEventDetails(event)
  const facts = [...details.profileCandidates, ...details.eventCandidates]
  const command = event.content?.trim()

  return (
    <div className="flex w-full justify-start" role="status" aria-live="polite">
      <div className={cn('relative ml-1 w-full max-w-[680px] border-l-2 pl-4', style.marker)}>
        <span
          className={cn(
            'absolute -left-[7px] top-4 h-3 w-3 rounded-full border-2 border-white dark:border-charcoal-900',
            state === 'updated' && 'bg-sage-500',
            state === 'processing' && 'bg-terracotta-500',
            state === 'no_change' && 'bg-mustard-400',
            state === 'failed' && 'bg-red-500'
          )}
          aria-hidden="true"
        />

        <div className="overflow-hidden rounded-lg border border-warm-white-200 bg-[#fffdf9] shadow-[0_2px_10px_rgba(78,66,53,0.06)] dark:border-charcoal-700 dark:bg-charcoal-800">
          <div className="flex items-center gap-2.5 border-b border-dashed border-warm-white-200 px-4 py-3 dark:border-charcoal-700">
            <span className={cn('flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-md', style.iconTone)}>
              <Icon className={cn('h-4 w-4', state === 'processing' && 'animate-spin')} />
            </span>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                <span className="text-sm font-semibold text-charcoal-900 dark:text-warm-white">
                  长期记忆
                </span>
                <span className={cn('rounded px-1.5 py-0.5 text-[10px] font-semibold', style.badge)}>
                  {style.label}
                </span>
              </div>
              <div className="mt-0.5 flex items-center gap-1 text-[11px] text-charcoal-400 dark:text-charcoal-500">
                <Clock3 className="h-3 w-3" />
                <span>{formatEventTime(event)}</span>
              </div>
            </div>
          </div>

          <div className="px-4 py-3.5">
            {state === 'processing' && (
              <div>
                <p className="text-sm font-medium text-charcoal-800 dark:text-charcoal-200">
                  正在筛选需要长期保留的内容
                </p>
                {command && (
                  <p className="mt-2 line-clamp-2 border-l-2 border-terracotta-200 pl-2.5 text-xs leading-5 text-charcoal-500 dark:border-terracotta-800 dark:text-charcoal-400">
                    {command}
                  </p>
                )}
                <div className="mt-3 h-1 overflow-hidden rounded-full bg-terracotta-100 dark:bg-terracotta-900/30">
                  <div className="h-full w-2/5 animate-pulse rounded-full bg-terracotta-400" />
                </div>
              </div>
            )}

            {state === 'updated' && (
              <div className="space-y-3">
                <p className="text-sm font-medium text-charcoal-800 dark:text-charcoal-200">
                  {facts.length > 0 ? `本次写入 ${facts.length} 项长期信息` : '自动记忆内容已更新'}
                </p>
                {facts.length > 0 && (
                  <div className="space-y-3">
                    <FactList label="用户档案" facts={details.profileCandidates} />
                    <FactList label="事件记录" facts={details.eventCandidates} />
                  </div>
                )}
                {facts.length === 0 && details.profile && (
                  <p className="break-words text-xs leading-5 text-charcoal-500 dark:text-charcoal-400">
                    {details.profile}
                  </p>
                )}
              </div>
            )}

            {state === 'no_change' && (
              <div>
                <p className="text-sm font-medium text-charcoal-800 dark:text-charcoal-200">
                  本次没有产生新的长期记忆
                </p>
                <p className="mt-1 text-xs leading-5 text-charcoal-500 dark:text-charcoal-400">
                  {details.reason === 'no_candidates'
                    ? '内容仅对当前行程有效，或已经存在于记忆中。'
                    : '现有记忆已经覆盖了这条内容，无需重复写入。'}
                </p>
                {command && (
                  <p className="mt-2 line-clamp-2 border-l-2 border-mustard-200 pl-2.5 text-xs leading-5 text-charcoal-500 dark:border-mustard-800 dark:text-charcoal-400">
                    {command}
                  </p>
                )}
              </div>
            )}

            {state === 'failed' && (
              <div>
                <p className="text-sm font-medium text-red-700 dark:text-red-300">
                  长期记忆未能完成更新
                </p>
                <p className="mt-1 break-words text-xs leading-5 text-red-600/90 dark:text-red-400/90">
                  {event.errorMessage || '处理服务暂时不可用，稍后可重新发送这条记忆。'}
                </p>
              </div>
            )}

            {details.discarded.length > 0 && (
              <details className="mt-3 border-t border-dashed border-warm-white-200 pt-2.5 dark:border-charcoal-700">
                <summary className="cursor-pointer text-[11px] font-medium text-charcoal-400 hover:text-charcoal-600 dark:text-charcoal-500 dark:hover:text-charcoal-300">
                  查看未写入内容（{details.discarded.length}）
                </summary>
                <ul className="mt-2 space-y-1 pl-4 text-xs leading-5 text-charcoal-500 dark:text-charcoal-400">
                  {details.discarded.map((item) => (
                    <li key={item} className="list-disc break-words">{item}</li>
                  ))}
                </ul>
              </details>
            )}
          </div>

          <div className="flex items-center justify-between border-t border-warm-white-200 bg-warm-white/40 px-4 py-2 dark:border-charcoal-700 dark:bg-charcoal-900/20">
            <span className="text-[10px] uppercase tracking-[0.12em] text-charcoal-400 dark:text-charcoal-500">
              自动记忆 · 可编辑
            </span>
            <button
              type="button"
              onClick={onOpenMemory}
              className="inline-flex items-center gap-1 rounded px-1.5 py-1 text-[11px] font-medium text-terracotta-600 hover:bg-terracotta-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500 dark:text-terracotta-300 dark:hover:bg-terracotta-900/20"
            >
              查看记忆
              <ArrowUpRight className="h-3 w-3" />
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

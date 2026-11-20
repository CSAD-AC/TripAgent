import { useEffect, useState } from 'react'
import { History, Loader2, RotateCcw, Save } from 'lucide-react'
import type { MemoryBlock, MemoryBlockRevision } from '../types'
import { cn } from '../lib/utils'

interface MemoryBlockEditorProps {
  block: MemoryBlock
  title: string
  description: string
  onSave: (content: string) => Promise<void>
  loadRevisions: () => Promise<MemoryBlockRevision[]>
  onRollback: (revisionId: string) => Promise<void>
}

function formatTime(value?: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

const SOURCE_LABELS: Record<string, string> = {
  INIT: '初始',
  KEYWORD: '显式记忆',
  AUTO_CONSOLIDATION: '自动整理',
  DAILY: '每日整理',
  MANUAL: '手动编辑',
}

export function MemoryBlockEditor({
  block,
  title,
  description,
  onSave,
  loadRevisions,
  onRollback,
}: MemoryBlockEditorProps) {
  const [value, setValue] = useState(block.content)
  const [saving, setSaving] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [revisions, setRevisions] = useState<MemoryBlockRevision[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    setValue(block.content)
  }, [block.content, block.version])

  const ratio = value.length / block.maxChars
  const overLimit = value.length > block.maxChars
  const dirty = value !== block.content

  const save = async () => {
    if (overLimit || !dirty) return
    setSaving(true)
    setError('')
    try {
      await onSave(value)
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const openHistory = async () => {
    const next = !historyOpen
    setHistoryOpen(next)
    if (!next) return
    setHistoryLoading(true)
    setError('')
    try {
      setRevisions(await loadRevisions())
    } catch (err) {
      setError(err instanceof Error ? err.message : '读取历史失败')
    } finally {
      setHistoryLoading(false)
    }
  }

  const rollback = async (revisionId: string) => {
    setError('')
    try {
      await onRollback(revisionId)
      setHistoryOpen(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : '回滚失败')
    }
  }

  return (
    <section className="border border-warm-white-200 dark:border-charcoal-700 bg-white dark:bg-charcoal-900 rounded-lg p-4">
      <div className="flex items-start justify-between gap-3 mb-3">
        <div>
          <h3 className="text-sm font-semibold text-charcoal-900 dark:text-warm-white">{title}</h3>
          <p className="text-[11px] text-charcoal-400 dark:text-charcoal-500 mt-1">{description}</p>
        </div>
        <button
          type="button"
          onClick={() => void openHistory()}
          className="flex flex-shrink-0 items-center gap-1.5 whitespace-nowrap px-2 py-1.5 rounded-md text-xs text-charcoal-500 hover:bg-warm-white dark:hover:bg-charcoal-800"
        >
          <History className="w-3.5 h-3.5" />
          历史
        </button>
      </div>

      <textarea
        value={value}
        onChange={(event) => setValue(event.target.value)}
        rows={block.type === 'profile' ? 8 : 12}
        aria-label={title}
        className="w-full resize-y rounded-md border border-warm-white-200 dark:border-charcoal-700 bg-transparent px-3 py-2 text-sm text-charcoal-800 dark:text-warm-white focus:outline-none focus:ring-2 focus:ring-terracotta-500/40"
      />
      <div className="flex items-center justify-between mt-2">
        <div className="flex items-center gap-3 text-xs">
          <span
            className={cn(
              overLimit
                ? 'text-red-500'
                : ratio > 0.9
                  ? 'text-terracotta-500'
                  : 'text-charcoal-400'
            )}
          >
            {value.length}/{block.maxChars}
          </span>
          <span className="text-charcoal-300 dark:text-charcoal-600">
            {SOURCE_LABELS[block.changeSource] || block.changeSource}
            {block.generatedEdited ? ' · 已手动编辑' : ''}
          </span>
        </div>
        <button
          type="button"
          onClick={() => void save()}
          disabled={!dirty || overLimit || saving}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium bg-terracotta-500 text-white hover:bg-terracotta-600 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Save className="w-3.5 h-3.5" />}
          保存
        </button>
      </div>

      {error && <p role="alert" className="text-xs text-red-500 mt-2">{error}</p>}

      {historyOpen && (
        <div className="mt-4 border-t border-warm-white-200 dark:border-charcoal-700 pt-3">
          <div className="flex items-center justify-between mb-2">
            <p className="text-xs font-medium text-charcoal-700 dark:text-charcoal-300">最近历史</p>
            <button type="button" onClick={() => setHistoryOpen(false)} className="text-xs text-charcoal-400">
              关闭
            </button>
          </div>
          {historyLoading ? (
            <Loader2 className="w-4 h-4 animate-spin text-terracotta-500" />
          ) : revisions.length === 0 ? (
            <p className="text-xs text-charcoal-400">暂无历史记录</p>
          ) : (
            <ul className="space-y-2">
              {revisions.map((revision) => (
                <li key={revision.id} className="flex items-start gap-3 text-xs">
                  <div className="flex-1 min-w-0">
                    <p className="text-charcoal-600 dark:text-charcoal-300 line-clamp-2">
                      {revision.newContent || '空内容'}
                    </p>
                    <p className="text-[11px] text-charcoal-400 mt-0.5">
                      {SOURCE_LABELS[revision.changeSource] || revision.changeSource}
                      {' · '}
                      {formatTime(revision.createdAt)}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => void rollback(revision.id)}
                    className="p-1.5 rounded text-charcoal-400 hover:text-terracotta-500 hover:bg-terracotta-50 dark:hover:bg-terracotta-900/20"
                    aria-label="回滚到此版本"
                  >
                    <RotateCcw className="w-3.5 h-3.5" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </section>
  )
}

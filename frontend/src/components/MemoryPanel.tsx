import { useEffect, useState } from 'react'
import { ArrowLeft, Brain, History, Loader2, ShieldOff, Trash2 } from 'lucide-react'
import { useMemory } from '../hooks/useMemory'
import type { MemoryRevision } from '../types'
import { cn } from '../lib/utils'

interface MemoryPanelProps {
  onBack: () => void
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

export function MemoryPanel({ onBack }: MemoryPanelProps) {
  const { memories, loading, error, load, remove, clear, correct, veto, revisions } = useMemory()
  const [vetoRule, setVetoRule] = useState('')
  const [actionError, setActionError] = useState('')
  const [revisionsFor, setRevisionsFor] = useState<string | null>(null)
  const [revisionList, setRevisionList] = useState<MemoryRevision[]>([])

  useEffect(() => {
    void load()
  }, [load])

  const runAction = async (action: () => Promise<void>) => {
    setActionError('')
    try {
      await action()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '操作失败')
    }
  }

  const openRevisions = async (id: string) => {
    await runAction(async () => {
      setRevisionList(await revisions(id))
      setRevisionsFor(id)
    })
  }

  return (
    <div className="h-full overflow-y-auto bg-warm-white dark:bg-[#1a1a1a]">
      <div className="max-w-3xl mx-auto px-4 py-6">
        <div className="flex items-center gap-3 mb-6">
          <button
            type="button"
            onClick={onBack}
            className="p-2 rounded-md text-charcoal-500 dark:text-charcoal-400 hover:bg-white dark:hover:bg-charcoal-800"
            aria-label="返回聊天"
          >
            <ArrowLeft className="w-4 h-4" />
          </button>
          <div>
            <h2 className="font-display text-xl font-semibold text-charcoal-900 dark:text-warm-white">
              偏好记忆
            </h2>
            <p className="text-sm text-charcoal-400 dark:text-charcoal-500">
              这里只保存你明确表达的偏好和常用设置
            </p>
          </div>
        </div>

        <div className="border border-warm-white-200 dark:border-charcoal-700 bg-white dark:bg-charcoal-900 rounded-lg p-4 mb-4">
          <div className="flex items-center gap-2 mb-2">
            <ShieldOff className="w-4 h-4 text-terracotta-500" />
            <p className="text-sm font-medium text-charcoal-900 dark:text-warm-white">不要再记住</p>
          </div>
          <div className="flex gap-2">
            <input
              value={vetoRule}
              onChange={(event) => setVetoRule(event.target.value)}
              placeholder="例如：不要再提美食相关偏好"
              className="flex-1 rounded-md border border-warm-white-200 dark:border-charcoal-700 bg-transparent px-3 py-2 text-sm"
            />
            <button
              type="button"
              onClick={() => {
                if (!vetoRule.trim()) return
                void runAction(async () => {
                  await veto(vetoRule.trim())
                  setVetoRule('')
                })
              }}
              className="px-3 py-2 rounded-md text-sm bg-terracotta-500 text-white hover:bg-terracotta-600"
            >
              添加规则
            </button>
          </div>
          <p className="text-[11px] text-charcoal-400 dark:text-charcoal-500 mt-2">
            命中规则的记忆不会进入后续提示词；原始对话不会被删除。
          </p>
        </div>

        {actionError && (
          <p role="alert" className="text-sm text-red-600 dark:text-red-400 mb-4">{actionError}</p>
        )}

        {memories.length > 0 && (
          <div className="flex justify-end mb-4">
            <button
              type="button"
              onClick={() => {
                if (window.confirm('确定清空所有偏好记忆？')) void clear()
              }}
              className="flex items-center gap-1.5 px-3 py-2 rounded-md text-sm text-red-600 dark:text-red-400 border border-red-200 dark:border-red-900 hover:bg-red-50 dark:hover:bg-red-950/30"
            >
              <Trash2 className="w-3.5 h-3.5" />
              清空全部
            </button>
          </div>
        )}

        {loading ? (
          <div className="flex justify-center py-16">
            <Loader2 className="w-6 h-6 text-terracotta-500 animate-spin" />
          </div>
        ) : error ? (
          <p role="alert" className="text-sm text-red-600 dark:text-red-400">
            {error}
          </p>
        ) : memories.length === 0 ? (
          <div className="border border-warm-white-200 dark:border-charcoal-700 bg-white dark:bg-charcoal-900 rounded-lg p-10 text-center">
            <Brain className="w-8 h-8 mx-auto mb-3 text-charcoal-300 dark:text-charcoal-600" />
            <p className="text-sm text-charcoal-500 dark:text-charcoal-400">
              还没有记录。你可以在聊天中直接说“我喜欢美食”“我一般预算 5000 元”。
            </p>
          </div>
        ) : (
          <div className="border border-warm-white-200 dark:border-charcoal-700 bg-white dark:bg-charcoal-900 rounded-lg divide-y divide-warm-white-200 dark:divide-charcoal-700">
            {memories.map((memory) => (
              <div key={memory.id} className="flex items-center gap-3 p-4">
                <div className="w-8 h-8 rounded-md bg-terracotta-50 dark:bg-terracotta-900/30 flex items-center justify-center flex-shrink-0">
                  <Brain className="w-4 h-4 text-terracotta-500" />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-charcoal-900 dark:text-warm-white">
                    {memory.label} <span className="text-[11px] text-charcoal-400">({memory.memoryType})</span>
                  </p>
                  <p className="text-sm text-charcoal-500 dark:text-charcoal-400 truncate">
                    {memory.value}
                  </p>
                  <p className="text-[11px] text-charcoal-300 dark:text-charcoal-600 mt-1">
                    {formatTime(memory.updatedAt)}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => void openRevisions(memory.id)}
                  className="p-2 rounded-md text-charcoal-400 hover:text-terracotta-500 hover:bg-terracotta-50 dark:hover:bg-terracotta-900/20"
                  aria-label={`查看 ${memory.label} 的修订历史`}
                >
                  <History className="w-4 h-4" />
                </button>
                <button
                  type="button"
                  onClick={() => {
                    const next = window.prompt(`纠正记忆值：${memory.label}`, memory.value)
                    if (next !== null && next.trim() && next !== memory.value) {
                      void runAction(() => correct(memory.id, next.trim()))
                    }
                  }}
                  className="px-2 py-1 rounded-md text-xs border border-warm-white-200 dark:border-charcoal-700 text-charcoal-500"
                >
                  纠正
                </button>
                <button
                  type="button"
                  onClick={() => {
                    if (window.confirm(`删除这条记忆？\n${memory.label}：${memory.value}`)) {
                      void runAction(() => remove(memory.id))
                    }
                  }}
                  className={cn(
                    'p-2 rounded-md text-charcoal-400 hover:text-red-500',
                    'hover:bg-red-50 dark:hover:bg-red-950/30'
                  )}
                  aria-label={`删除 ${memory.label}`}
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </div>
            ))}
          </div>
        )}

        {revisionsFor && (
          <div className="border border-warm-white-200 dark:border-charcoal-700 bg-white dark:bg-charcoal-900 rounded-lg p-4 mt-4">
            <div className="flex items-center justify-between mb-3">
              <p className="text-sm font-medium">修订历史</p>
              <button type="button" onClick={() => setRevisionsFor(null)} className="text-xs text-charcoal-400">关闭</button>
            </div>
            {revisionList.length === 0 ? (
              <p className="text-sm text-charcoal-400">暂无修订记录</p>
            ) : (
              <ul className="space-y-2 text-sm">
                {revisionList.map((revision) => (
                  <li key={revision.id} className="border-b border-warm-white-200 dark:border-charcoal-700 pb-2">
                    <p className="text-charcoal-700 dark:text-charcoal-300">
                      {revision.operation}: {revision.previousValue || '空'} → {revision.newValue || '空'}
                    </p>
                    <p className="text-[11px] text-charcoal-400">{revision.reason} · {formatTime(revision.createdAt)}</p>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

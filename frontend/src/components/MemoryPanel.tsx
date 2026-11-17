import { useEffect } from 'react'
import { ArrowLeft, Brain, Loader2, Trash2 } from 'lucide-react'
import { useMemory } from '../hooks/useMemory'
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
  const { memories, loading, error, load, remove, clear } = useMemory()

  useEffect(() => {
    void load()
  }, [load])

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
                    {memory.label}
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
                  onClick={() => {
                    if (window.confirm(`删除这条记忆？\n${memory.label}：${memory.value}`)) {
                      void remove(memory.id)
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
      </div>
    </div>
  )
}

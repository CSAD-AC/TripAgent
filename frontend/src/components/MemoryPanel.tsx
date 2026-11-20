import { useEffect } from 'react'
import { ArrowLeft, Brain, Loader2 } from 'lucide-react'
import { useMemoryBlocks } from '../hooks/useMemoryBlocks'
import { MemoryBlockEditor } from './MemoryBlockEditor'
import { MemoryPromptEditor } from './MemoryPromptEditor'

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
  const {
    prompt,
    blocks,
    loading,
    error,
    reload,
    savePrompt,
    saveBlock,
    promptRevisions,
    blockRevisions,
    rollbackPrompt,
    rollbackBlock,
  } = useMemoryBlocks()

  useEffect(() => {
    void reload()
  }, [reload])

  const profile = blocks.find((block) => block.type === 'profile')
  const events = blocks.find((block) => block.type === 'event')
  const dailyBlocks = blocks.filter((block) => block.changeSource === 'DAILY')

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
              长期记忆
            </h2>
            <p className="text-sm text-charcoal-400 dark:text-charcoal-500">
              固定规则由你维护，自动记忆可在后续整理时更新。
            </p>
          </div>
        </div>

        {loading && !prompt ? (
          <div className="flex justify-center py-16">
            <Loader2 className="w-6 h-6 text-terracotta-500 animate-spin" />
          </div>
        ) : error ? (
          <div className="border border-red-200 dark:border-red-900 rounded-lg p-4">
            <p role="alert" className="text-sm text-red-600 dark:text-red-400">{error}</p>
            <button
              type="button"
              onClick={() => void reload()}
              className="mt-3 px-3 py-1.5 rounded-md text-xs border border-red-200 dark:border-red-900 text-red-600 dark:text-red-400"
            >
              重试
            </button>
          </div>
        ) : prompt ? (
          <div className="space-y-4">
            <MemoryPromptEditor
              prompt={prompt}
              onSave={savePrompt}
              loadRevisions={promptRevisions}
              onRollback={rollbackPrompt}
            />
            {profile && (
              <MemoryBlockEditor
                block={profile}
                title="自动用户信息"
                description="可编辑，但后续自动整理可能重建；重要内容请放入用户固定提示词。"
                onSave={(content) => saveBlock('profile', content)}
                loadRevisions={() => blockRevisions('profile')}
                onRollback={(revisionId) => rollbackBlock('profile', revisionId)}
              />
            )}
            {events && (
              <MemoryBlockEditor
                block={events}
                title="自动事件记忆"
                description="记录已发生或明确计划的长期事件；自动整理可能重建这一层。"
                onSave={(content) => saveBlock('event', content)}
                loadRevisions={() => blockRevisions('event')}
                onRollback={(revisionId) => rollbackBlock('event', revisionId)}
              />
            )}
            {dailyBlocks.length > 0 && (
              <div className="flex items-center gap-2 text-xs text-charcoal-400 dark:text-charcoal-500">
                <Brain className="w-3.5 h-3.5" />
                最近自动整理：
                {formatTime(dailyBlocks
                  .map((block) => block.changedAt || block.updatedAt)
                  .sort()
                  .at(-1))}
              </div>
            )}
          </div>
        ) : null}
      </div>
    </div>
  )
}

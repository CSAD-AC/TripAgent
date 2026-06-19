import { cn } from '../lib/utils'
import type { Conversation } from '../types'
import { MessageSquare, Plus, Trash2 } from 'lucide-react'

interface SidebarProps {
  conversations: Conversation[]
  activeId?: number
  onSelect: (id: number) => void
  onNew: () => void
  onDelete: (id: number) => void
  open: boolean
  onToggle: () => void
}

export function Sidebar({
  conversations,
  activeId,
  onSelect,
  onNew,
  onDelete,
  open,
  onToggle,
}: SidebarProps) {
  return (
    <>
      {/* 遮罩（移动端） */}
      {open && (
        <button
          className="fixed inset-0 bg-black/20 z-20 md:hidden"
          onClick={onToggle}
          aria-label="关闭侧边栏"
        />
      )}

      <aside
        className={cn(
          'fixed md:relative z-30 h-full bg-white dark:bg-charcoal-900 border-r border-warm-white-200 dark:border-charcoal-700 flex flex-col transition-transform duration-300 motion-safe:transition-transform',
          open ? 'translate-x-0' : '-translate-x-full md:translate-x-0',
          'w-72'
        )}
        aria-label="对话历史"
      >
        {/* 头部 */}
        <div className="p-4 border-b border-warm-white-200 dark:border-charcoal-700">
          <button
            onClick={onNew}
            aria-label="新建对话"
            className="w-full flex items-center gap-2 px-4 py-3 rounded-xl bg-terracotta-50 dark:bg-terracotta-900/30 text-terracotta-600 dark:text-terracotta-400 hover:bg-terracotta-100 dark:hover:bg-terracotta-900/50 transition-colors text-sm font-medium min-h-[44px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500"
          >
            <Plus className="w-4 h-4" />
            新建对话
          </button>
        </div>

        {/* 对话列表 */}
        <div className="flex-1 overflow-y-auto p-2 space-y-1">
          {conversations.length === 0 ? (
            <div className="px-3 py-8 text-center text-sm text-charcoal-300 dark:text-charcoal-500">
              <MessageSquare className="w-8 h-8 mx-auto mb-2 opacity-50" />
              <p>暂无对话历史</p>
            </div>
          ) : (
            conversations.map((conv) => (
              <div
                key={conv.id}
                onClick={() => onSelect(conv.id)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    onSelect(conv.id)
                  }
                }}
                role="button"
                tabIndex={0}
                className={cn(
                  'group flex items-center gap-2 w-full px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-sm text-left min-h-[44px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500',
                  conv.id === activeId
                    ? 'bg-terracotta-50 dark:bg-terracotta-900/30 text-terracotta-700 dark:text-terracotta-300'
                    : 'text-charcoal-600 dark:text-charcoal-300 hover:bg-warm-white dark:hover:bg-charcoal-800'
                )}
              >
                <MessageSquare className="w-4 h-4 flex-shrink-0" />
                <span className="flex-1 truncate">{conv.title}</span>
                <button
                  onClick={(e) => {
                    e.stopPropagation()
                    onDelete(conv.id)
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.stopPropagation()
                    }
                  }}
                  aria-label={`删除对话 ${conv.title}`}
                  className="flex-shrink-0 opacity-0 group-hover:opacity-100 group-focus-within:opacity-100 p-2 rounded hover:bg-charcoal-100 dark:hover:bg-charcoal-700 text-charcoal-400 dark:text-charcoal-500 hover:text-red-500 dark:hover:text-red-400 transition-all min-h-[44px] min-w-[44px] flex items-center justify-center focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-400 focus-visible:opacity-100"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            ))
          )}
        </div>

        {/* 底部 branding */}
        <div className="p-4 border-t border-warm-white-200 dark:border-charcoal-700">
          <p className="text-xs text-charcoal-300 dark:text-charcoal-600 font-display italic text-center">
            TravelPal · 旅途规划助手
          </p>
        </div>
      </aside>
    </>
  )
}

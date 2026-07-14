import { useState } from 'react'
import { cn } from '../lib/utils'
import { MapPin, Plus, Hash, Trash2, Pencil, Check, X, MessageSquare, Loader2 } from 'lucide-react'
import type { Conversation } from '../types'

interface SidebarProps {
  conversationId?: string
  conversations: Conversation[]
  loading: boolean
  total?: number
  onSelect: (id: string) => void
  onNew: () => void
  onDelete: (id: string) => Promise<void>
  onRename: (id: string, title: string) => Promise<void>
  open: boolean
  onToggle: () => void
}

/** 格式化时间: 今天/昨天/日期 */
function formatTime(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffDays = Math.floor(diffMs / 86400000)

  if (diffDays === 0) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  if (diffDays === 1) return '昨天'
  if (diffDays < 7) return `${diffDays}天前`
  return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}

/** 单个对话条目（支持选中、删除、行内重命名） */
function ConversationItem({
  conv,
  active,
  onSelect,
  onDelete,
  onRename,
}: {
  conv: Conversation
  active: boolean
  onSelect: () => void
  onDelete: () => void
  onRename: (title: string) => Promise<void>
}) {
  const [editing, setEditing] = useState(false)
  const [editValue, setEditValue] = useState(conv.title)
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    const trimmed = editValue.trim()
    if (!trimmed || trimmed === conv.title) {
      setEditing(false)
      setEditValue(conv.title)
      return
    }
    setSaving(true)
    try {
      await onRename(trimmed)
      setEditing(false)
    } catch {
      setEditValue(conv.title)
    } finally {
      setSaving(false)
    }
  }

  const handleCancel = () => {
    setEditing(false)
    setEditValue(conv.title)
  }

  const displayTitle = conv.title || '新对话'

  if (editing) {
    return (
      <div
        className={cn(
          'flex items-center gap-1 px-3 py-2 rounded-xl',
          active ? 'bg-terracotta-50 dark:bg-terracotta-900/30' : ''
        )}
      >
        <input
          value={editValue}
          onChange={(e) => setEditValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') handleSave()
            if (e.key === 'Escape') handleCancel()
          }}
          autoFocus
          className="flex-1 min-w-0 text-sm bg-white dark:bg-charcoal-700 border border-terracotta-300 dark:border-terracotta-600 rounded-lg px-2 py-1 text-charcoal-900 dark:text-warm-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500"
          onClick={(e) => e.stopPropagation()}
          aria-label="重命名对话"
        />
        <button
          onClick={(e) => { e.stopPropagation(); handleSave() }}
          disabled={saving}
          className="p-1 rounded hover:bg-sage-100 dark:hover:bg-sage-900/30 text-sage-600 dark:text-sage-400"
          aria-label="保存"
        >
          {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
        </button>
        <button
          onClick={(e) => { e.stopPropagation(); handleCancel() }}
          className="p-1 rounded hover:bg-charcoal-100 dark:hover:bg-charcoal-700 text-charcoal-400"
          aria-label="取消"
        >
          <X className="w-3.5 h-3.5" />
        </button>
      </div>
    )
  }

  return (
    <div
      onClick={onSelect}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') onSelect() }}
      aria-label={`选择对话: ${displayTitle}`}
      className={cn(
        'group flex items-start gap-2 px-3 py-2.5 rounded-xl cursor-pointer transition-colors',
        active
          ? 'bg-terracotta-50 dark:bg-terracotta-900/30'
          : 'hover:bg-warm-white dark:hover:bg-charcoal-800'
      )}
    >
      <Hash className="w-4 h-4 flex-shrink-0 mt-0.5 text-charcoal-300 dark:text-charcoal-600 group-hover:text-terracotta-400 transition-colors" />
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className={cn(
            'text-sm truncate flex-1',
            active
              ? 'font-medium text-terracotta-700 dark:text-terracotta-300'
              : 'text-charcoal-700 dark:text-charcoal-300'
          )}>
            {displayTitle}
          </span>
          <span className="text-[10px] text-charcoal-400 dark:text-charcoal-500 flex-shrink-0">
            {conv.createdAt ? formatTime(conv.createdAt) : ''}
          </span>
        </div>
        <div className="flex items-center gap-2 mt-0.5">
          <span className="text-[11px] text-charcoal-400 dark:text-charcoal-500">
            {conv.messageCount} 条消息
          </span>
        </div>
      </div>

      {/* 操作按钮(悬停显示) */}
      <div className="hidden group-hover:flex items-center gap-0.5 flex-shrink-0">
        <button
          onClick={(e) => {
            e.stopPropagation()
            setEditValue(conv.title)
            setEditing(true)
          }}
          className="p-1 rounded hover:bg-charcoal-100 dark:hover:bg-charcoal-700 text-charcoal-400 hover:text-charcoal-600 dark:hover:text-charcoal-300"
          aria-label="重命名"
        >
          <Pencil className="w-3.5 h-3.5" />
        </button>
        <button
          onClick={(e) => {
            e.stopPropagation()
            if (window.confirm('确定删除此对话？')) onDelete()
          }}
          className="p-1 rounded hover:bg-red-100 dark:hover:bg-red-900/30 text-charcoal-400 hover:text-red-500"
          aria-label="删除"
        >
          <Trash2 className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  )
}

/**
 * 侧边栏（对话列表版）
 *
 * 展示用户所有 active 对话，支持：
 *  - 切换选择对话
 *  - 重命名（行内编辑）
 *  - 删除（软删除，确认弹窗）
 *  - 新建对话
 */
export function Sidebar({
  conversationId,
  conversations,
  loading,
  total: totalProp,
  onSelect,
  onNew,
  onDelete,
  onRename,
  open,
  onToggle,
}: SidebarProps & { total?: number }) {
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
        aria-label="侧边栏"
      >
        {/* 头部: logo + 标题 */}
        <div className="p-4 border-b border-warm-white-200 dark:border-charcoal-700">
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
        </div>

        {/* 对话列表 */}
        <div className="flex-1 overflow-y-auto p-3">
          <div className="flex items-center justify-between mb-2 px-2">
            <span className="text-[10px] font-semibold uppercase tracking-wider text-charcoal-400 dark:text-charcoal-500">
              对话历史
            </span>
            <span className="text-[10px] text-charcoal-300 dark:text-charcoal-600">
              {totalProp ? `${totalProp} 条` : ''}
            </span>
          </div>

          {loading && conversations.length === 0 ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="w-5 h-5 text-terracotta-500 animate-spin" />
            </div>
          ) : conversations.length === 0 ? (
            <div className="px-3 py-6 text-center">
              <MessageSquare className="w-8 h-8 mx-auto mb-2 text-charcoal-300 dark:text-charcoal-600" />
              <p className="text-sm text-charcoal-400 dark:text-charcoal-500">暂无对话</p>
              <p className="text-[11px] text-charcoal-300 dark:text-charcoal-600 mt-1">
                发消息后将自动创建
              </p>
            </div>
          ) : (
            <div className="space-y-0.5">
              {conversations.map((conv) => (
                <ConversationItem
                  key={conv.id}
                  conv={conv}
                  active={conv.id === conversationId}
                  onSelect={() => onSelect(conv.id)}
                  onDelete={() => onDelete(conv.id)}
                  onRename={(title) => onRename(conv.id, title)}
                />
              ))}
            </div>
          )}
        </div>

        {/* 新建对话按钮 */}
        <div className="p-4 border-t border-warm-white-200 dark:border-charcoal-700">
          <button
            onClick={onNew}
            aria-label="新建对话"
            className="w-full flex items-center gap-2 px-4 py-3 rounded-xl bg-terracotta-50 dark:bg-terracotta-900/30 text-terracotta-600 dark:text-terracotta-400 hover:bg-terracotta-100 dark:hover:bg-terracotta-900/50 transition-colors text-sm font-medium min-h-[44px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500"
          >
            <Plus className="w-4 h-4" />
            新建对话
          </button>
        </div>

        {/* 底部 branding */}
        <div className="px-4 pb-4">
          <p className="text-xs text-charcoal-300 dark:text-charcoal-600 font-display italic text-center">
            TravelPal · 旅途规划助手
          </p>
        </div>
      </aside>
    </>
  )
}

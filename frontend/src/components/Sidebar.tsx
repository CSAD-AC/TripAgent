import { cn } from '../lib/utils'
import { MapPin, Plus, Hash } from 'lucide-react'

interface SidebarProps {
  /** 当前会话 ID(后端权威生成,前端不存) */
  conversationId?: string
  onNew: () => void
  open: boolean
  onToggle: () => void
}

/**
 * 侧边栏(MVP 简化版)
 *
 * <p>设计取舍:不做历史会话列表(那需要用户身份系统,Phase 5+ 的事)
 * <p>当前 tab 只显示"当前会话",允许新建对话
 * <p>conversationId 简短展示前 8 位,方便用户辨识但不全显示
 */
export function Sidebar({ conversationId, onNew, open, onToggle }: SidebarProps) {
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
        {/* 头部:logo + 标题 */}
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

        {/* 当前会话信息 */}
        <div className="flex-1 overflow-y-auto p-3">
          <div className="text-[10px] font-semibold uppercase tracking-wider text-charcoal-400 dark:text-charcoal-500 mb-2 px-2">
            当前会话
          </div>
          {conversationId ? (
            <div className="flex items-start gap-2 px-3 py-2.5 rounded-xl bg-warm-white dark:bg-charcoal-800 text-sm">
              <Hash className="w-4 h-4 flex-shrink-0 mt-0.5 text-charcoal-400 dark:text-charcoal-500" />
              <div className="flex-1 min-w-0">
                <div className="font-mono text-xs text-charcoal-700 dark:text-charcoal-300 truncate">
                  {conversationId.slice(0, 8)}...
                </div>
                <div className="text-[10px] text-charcoal-400 dark:text-charcoal-500 mt-0.5">
                  URL hash 保留,刷新可继续
                </div>
              </div>
            </div>
          ) : (
            <div className="px-3 py-4 text-center text-sm text-charcoal-400 dark:text-charcoal-500">
              <p>新对话</p>
              <p className="text-[11px] mt-1">发消息后由后端生成 ID</p>
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

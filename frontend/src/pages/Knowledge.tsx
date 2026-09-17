import { useState } from 'react'
import { cn } from '../lib/utils'
import { useKnowledge } from '../hooks/useKnowledge'
import { Upload, RefreshCw, Trash2, Loader2, Database, MapPin, Info, FileText } from 'lucide-react'
import type { KnowledgeChunk, KnowledgeDoc } from '../types'

/** 来源类型中文映射: 后端 sourceType 枚举 → 界面展示文案 */
const SOURCE_TYPE_LABEL: Record<string, string> = {
  manual: '手工',
  self_precip: '自沉淀',
  graph_report: '规划报告',
  seed: '内置',
  url: '网页',
}

/** 状态 → 中文文案 */
const STATUS_LABEL: Record<string, string> = {
  indexed: '已索引',
  pending: '待处理',
  processing: '处理中',
  failed: '失败',
  deleted: '已删除',
}

/** 渲染统计卡片: 大数字 + 副标签 */
function StatCard({ label, value, sub }: { label: string; value: number | string; sub?: string }) {
  return (
    <div className="rounded-xl border border-charcoal-200/60 dark:border-charcoal-700/60 bg-white/80 dark:bg-charcoal-800/80 px-3 py-2.5">
      <div className="text-xs text-charcoal-400 dark:text-charcoal-500">{label}</div>
      <div className="text-xl font-semibold text-charcoal-900 dark:text-warm-white mt-0.5">
        {value}
      </div>
      {sub && <div className="text-[10px] text-charcoal-300 dark:text-charcoal-600 mt-0.5">{sub}</div>}
    </div>
  )
}

/** 状态 → 徽章样式(success 用 terracotta 主色, failed 用红, pending 用灰) */
function statusBadge(status?: string): { label: string; cls: string } {
  switch (status) {
    case 'indexed':
      return { label: '已索引', cls: 'bg-terracotta-100 dark:bg-terracotta-900/30 text-terracotta-700 dark:text-terracotta-300 border-terracotta-200 dark:border-terracotta-700' }
    case 'failed':
      return { label: '失败', cls: 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400 border-red-200 dark:border-red-800/60' }
    default:
      return { label: '待处理', cls: 'bg-charcoal-100 dark:bg-charcoal-800 text-charcoal-500 dark:text-charcoal-400 border-charcoal-200 dark:border-charcoal-700' }
  }
}

/** 格式化时间戳为本地日期字符串 */
function formatDate(dateStr?: string): string {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  if (Number.isNaN(d.getTime())) return dateStr
  return d.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * 知识库管理页
 *
 * 功能:
 *  - 文档分页列表(标题 / 来源 / 状态 / chunk 数 / 创建时间)
 *  - 上传文档(手工录入 title + content, destination 可选)
 *  - 删除单条文档
 *  - 全量重建索引并展示重建结果
 *
 * 样式遵循 terracotta 主色 + 暖灰暗色风格。
 */
export function Knowledge() {
  const { docs, total, loading, stats, statsLoading, list, upload, remove, reindex, getChunks } = useKnowledge()

  // ── 阅读文档内容状态 ──
  const [chunkMap, setChunkMap] = useState<Record<string, KnowledgeChunk[]>>({})
  const [readingId, setReadingId] = useState<string | null>(null)
  const [readingLoading, setReadingLoading] = useState(false)

  /** 展开/收起某篇文档的分块内容(阅读) */
  const toggleRead = async (id: string) => {
    if (readingId === id) {
      setReadingId(null)
      return
    }
    setReadingId(id)
    setReadingLoading(true)
    try {
      if (!chunkMap[id]) {
        const chunks = await getChunks(id)
        setChunkMap((prev) => ({ ...prev, [id]: chunks }))
      }
    } catch (err) {
      console.error('[Knowledge] read chunks error:', err)
    } finally {
      setReadingLoading(false)
    }
  }

  // ── 上传表单状态 ──
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [destination, setDestination] = useState('')
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState('')

  // ── reindex 状态 ──
  const [reindexing, setReindexing] = useState(false)

  // ── 操作反馈提示(上传/重建结果) ──
  const [notice, setNotice] = useState('')
  const [noticeType, setNoticeType] = useState<'info' | 'success'>('info')

  /** 展示一条可自动消失的操作反馈 */
  const flash = (msg: string, type: 'info' | 'success' = 'info') => {
    setNotice(msg)
    setNoticeType(type)
    window.setTimeout(() => setNotice(''), 4000)
  }

  /** 分页大小(知识库文档通常量小, 一次拉取 50 条足够; 可后续扩展翻页) */
  const PAGE_SIZE = 50

  /** 提交上传表单 */
  const handleUpload = async () => {
    if (!title.trim() || !content.trim()) {
      setUploadError('标题与内容不能为空')
      return
    }
    setUploading(true)
    setUploadError('')
    try {
      await upload({
        title: title.trim(),
        content: content.trim(),
        destination: destination.trim() || undefined,
      })
      setTitle('')
      setContent('')
      setDestination('')
      flash('已提交入库，正在后台分块/索引', 'success')
    } catch (err) {
      console.error('[Knowledge] upload error:', err)
      setUploadError('上传失败, 请稍后重试')
    } finally {
      setUploading(false)
    }
  }

  /** 初始化重建索引并展示结果 */
  const handleReindex = async () => {
    if (!window.confirm('将全量重建索引, 确定继续?')) return
    setReindexing(true)
    try {
      const result = await reindex()
      if (result.status === 'busy') {
        flash('索引已在重建中, 请稍后查看')
      } else {
        flash(`重建完成: 共 ${result.total ?? 0} 块, 成功 ${result.success ?? 0}, 失败 ${result.failed ?? 0}`, 'success')
      }
    } catch (err) {
      console.error('[Knowledge] reindex error:', err)
      flash('重建失败, 请查看服务日志')
    } finally {
      setReindexing(false)
    }
  }

  /** 渲染单张文档卡片 */
  const renderDoc = (doc: KnowledgeDoc) => {
    const badge = statusBadge(doc.status)
    const sourceLabel = SOURCE_TYPE_LABEL[doc.sourceType || ''] || doc.sourceType || '未知'
    return (
      <div key={doc.id} className="space-y-2">
      <div
        className="group flex items-center gap-3 px-4 py-3 rounded-xl bg-white/80 dark:bg-charcoal-800/80 border border-charcoal-200/60 dark:border-charcoal-700/60 hover:border-terracotta-300 dark:hover:border-terracotta-700 transition-colors"
      >
        {/* 类型图标 */}
        <div className="w-9 h-9 rounded-lg bg-terracotta-50 dark:bg-terracotta-900/30 flex items-center justify-center flex-shrink-0">
          <Database className="w-4 h-4 text-terracotta-500" />
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium text-charcoal-900 dark:text-warm-white truncate">
              {doc.title}
            </span>
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-charcoal-100 dark:bg-charcoal-700 text-charcoal-400 dark:text-charcoal-500 flex-shrink-0">
              {sourceLabel}
            </span>
            <span className={cn('text-[10px] px-1.5 py-0.5 rounded border flex-shrink-0', badge.cls)}>
              {badge.label}
            </span>
          </div>
          <div className="mt-0.5 flex items-center gap-3 text-[11px] text-charcoal-400 dark:text-charcoal-500">
            {doc.sourceRef && <span>来源: {doc.sourceRef}</span>}
            {doc.chunkCount !== undefined && <span>chunk: {doc.chunkCount}</span>}
            {doc.createdAt && <span>创建于 {formatDate(doc.createdAt)}</span>}
          </div>
          {doc.errorMsg && (
            <div className="mt-1 text-[11px] text-red-500 truncate">错误: {doc.errorMsg}</div>
          )}
        </div>

        {/* 阅读按钮 */}
        <button
          onClick={() => toggleRead(doc.id)}
          disabled={readingLoading && readingId === doc.id}
          className="p-1.5 rounded-lg hover:bg-terracotta-50 dark:hover:bg-terracotta-900/30 text-charcoal-300 dark:text-charcoal-500 hover:text-terracotta-500 transition-colors flex-shrink-0"
          aria-label="阅读文档内容"
          title="阅读文档内容"
        >
          {readingLoading && readingId === doc.id ? (
            <Loader2 className="w-4 h-4 animate-spin" />
          ) : (
            <FileText className="w-4 h-4" />
          )}
        </button>

        {/* 删除按钮 */}
        <button
          onClick={async () => {
            if (window.confirm(`确定删除文档「${doc.title}」?`)) {
              try {
                await remove(doc.id)
              } catch (err) {
                console.error('[Knowledge] delete error:', err)
              }
            }
          }}
          className="p-1.5 rounded-lg hover:bg-red-100 dark:hover:bg-red-900/30 text-charcoal-300 dark:text-charcoal-500 hover:text-red-500 transition-colors flex-shrink-0"
          aria-label="删除文档"
        >
          <Trash2 className="w-4 h-4" />
        </button>
      </div>

      {/* 阅读预览: 文档分块内容 */}
      {readingId === doc.id && (
        <div className="rounded-xl border border-charcoal-200/60 dark:border-charcoal-700/60 bg-white/80 dark:bg-charcoal-800/80 overflow-hidden">
          <div className="px-3 py-2 text-[11px] font-semibold uppercase tracking-wider text-charcoal-400 dark:text-charcoal-500 border-b border-charcoal-200/30 dark:border-charcoal-700/30">
            文档内容（{chunkMap[doc.id]?.length ?? 0} 个分块）
          </div>
          <div className="max-h-72 overflow-y-auto divide-y divide-charcoal-100 dark:divide-charcoal-700/40">
            {chunkMap[doc.id]?.map((c, i) => (
              <div key={c.id || i} className="px-3 py-2">
                <div className="flex items-center gap-1.5 mb-1 text-[10px] text-charcoal-300 dark:text-charcoal-500">
                  <span>#{c.seq ?? i + 1}</span>
                  {c.destination && <span>· {c.destination}</span>}
                  {c.theme && <span>· {c.theme}</span>}
                </div>
                <div className="text-xs text-charcoal-700 dark:text-charcoal-200 leading-relaxed whitespace-pre-wrap">
                  {c.content}
                </div>
              </div>
            ))}
            {!chunkMap[doc.id] && (
              <div className="px-3 py-6 text-center text-xs text-charcoal-400">
                加载中...
              </div>
            )}
            {chunkMap[doc.id]?.length === 0 && (
              <div className="px-3 py-6 text-center text-xs text-charcoal-400">暂无分块内容</div>
            )}
          </div>
        </div>
      )}
      </div>
    )
  }

  return (
    <div className="flex-1 overflow-y-auto">
      <div className="max-w-3xl mx-auto px-4 py-6 space-y-6">
        {/* 操作反馈提示(上传/重建) */}
        {notice && (
          <div
            className={cn(
              'flex items-center gap-2 px-3 py-2 rounded-lg border text-xs transition-opacity',
              noticeType === 'success'
                ? 'bg-terracotta-50 dark:bg-terracotta-900/30 text-terracotta-700 dark:text-terracotta-300 border-terracotta-200 dark:border-terracotta-700'
                : 'bg-charcoal-100 dark:bg-charcoal-800 text-charcoal-700 dark:text-charcoal-300 border-charcoal-200 dark:border-charcoal-700',
            )}
          >
            <Info className="w-3.5 h-3.5 flex-shrink-0" />
            <span>{notice}</span>
          </div>
        )}

        {/* 页头 */}
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-terracotta-500 flex items-center justify-center">
            <Database className="w-5 h-5 text-white" />
          </div>
          <div>
            <h1 className="font-display text-xl font-semibold text-charcoal-900 dark:text-warm-white">
              知识库
            </h1>
            <p className="text-xs text-charcoal-400 dark:text-charcoal-500">
              管理 RAG 文档, 用于对话中引用领域知识 · 共 {total} 篇
            </p>
          </div>
          <button
            onClick={handleReindex}
            disabled={reindexing}
            className="ml-auto flex items-center gap-1.5 px-3 py-2 rounded-lg border border-terracotta-300 dark:border-terracotta-700 text-terracotta-600 dark:text-terracotta-400 text-xs font-medium hover:bg-terracotta-50 dark:hover:bg-terracotta-900/30 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {reindexing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
            全量重建
          </button>
        </div>

        {/* 统计总览(透明度: 规模 + 状态/来源分布) */}
        <section className="grid grid-cols-2 sm:grid-cols-4 gap-2">
          <StatCard label="文档" value={statsLoading ? '…' : (stats?.docTotal ?? total)} />
          <StatCard label="分块" value={statsLoading ? '…' : (stats?.chunkTotal ?? '-')} />
        </section>
        {(stats?.docByStatus || stats?.docBySourceType) && (
          <div className="flex flex-wrap gap-1.5 text-[11px]">
            {Object.entries(stats?.docByStatus || {}).map(([k, v]) => (
              <span key={`s-${k}`} className="px-2 py-0.5 rounded-full bg-charcoal-100 dark:bg-charcoal-800 text-charcoal-500 dark:text-charcoal-400">
                {STATUS_LABEL[k] || k}: {v}
              </span>
            ))}
            {Object.entries(stats?.docBySourceType || {}).map(([k, v]) => (
              <span key={`t-${k}`} className="px-2 py-0.5 rounded-full bg-terracotta-50 dark:bg-terracotta-900/30 text-terracotta-600 dark:text-terracotta-400">
                {SOURCE_TYPE_LABEL[k] || k}: {v}
              </span>
            ))}
          </div>
        )}

        {/* 上传表单 */}
        <section className="rounded-xl border border-charcoal-200/60 dark:border-charcoal-700/60 bg-white/80 dark:bg-charcoal-800/80 p-4 space-y-3">
          <h2 className="font-display text-sm font-semibold text-charcoal-900 dark:text-warm-white">
            新增文档
          </h2>
          <div className="space-y-2">
            <div>
              <label className="text-xs text-charcoal-500 dark:text-charcoal-400 mb-1 block">标题</label>
              <input
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="文档标题"
                className="w-full px-3 py-2 rounded-lg bg-warm-white dark:bg-charcoal-700 border border-charcoal-200 dark:border-charcoal-600 text-sm text-charcoal-900 dark:text-warm-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500"
              />
            </div>
            <div>
              <label className="text-xs text-charcoal-500 dark:text-charcoal-400 mb-1 block">内容</label>
              <textarea
                value={content}
                onChange={(e) => setContent(e.target.value)}
                placeholder="输入要入库的领域知识 / 规划资料内容"
                rows={6}
                className="w-full px-3 py-2 rounded-lg bg-warm-white dark:bg-charcoal-700 border border-charcoal-200 dark:border-charcoal-600 text-sm text-charcoal-900 dark:text-warm-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500 resize-none"
              />
            </div>
            <div>
              <label className="text-xs text-charcoal-500 dark:text-charcoal-400 mb-1 block">目的地（可选）</label>
              <input
                value={destination}
                onChange={(e) => setDestination(e.target.value)}
                placeholder="可选, 与特定目的地绑定以便更精确检索"
                className="w-full px-3 py-2 rounded-lg bg-warm-white dark:bg-charcoal-700 border border-charcoal-200 dark:border-charcoal-600 text-sm text-charcoal-900 dark:text-warm-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500"
              />
            </div>
            {uploadError && (
              <div className="text-xs text-red-500">{uploadError}</div>
            )}
            <div className="flex justify-end">
              <button
                onClick={handleUpload}
                disabled={uploading}
                className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-terracotta-500 text-white text-sm font-medium hover:bg-terracotta-600 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {uploading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Upload className="w-4 h-4" />}
                上传
              </button>
            </div>
          </div>
        </section>

        {/* 文档列表 */}
        <section className="space-y-2">
          <div className="flex items-center justify-between px-1">
            <h2 className="font-display text-sm font-semibold text-charcoal-900 dark:text-warm-white">
              文档列表
            </h2>
            <button
              onClick={() => list(0, PAGE_SIZE)}
              className="text-xs text-terracotta-600 dark:text-terracotta-400 hover:underline"
            >
              刷新
            </button>
          </div>

          {loading && docs.length === 0 ? (
            <div className="flex items-center justify-center py-10">
              <Loader2 className="w-5 h-5 text-terracotta-500 animate-spin" />
            </div>
          ) : docs.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <MapPin className="w-8 h-8 mb-2 text-charcoal-300 dark:text-charcoal-600" />
              <p className="text-sm text-charcoal-400 dark:text-charcoal-500">暂无文档</p>
              <p className="text-[11px] text-charcoal-300 dark:text-charcoal-600 mt-1">
                通过上方表单上传, 或使用消息点赞沉淀对话内容
              </p>
            </div>
          ) : (
            <div className="space-y-2">{docs.map(renderDoc)}</div>
          )}
        </section>
      </div>
    </div>
  )
}
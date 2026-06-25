import { cn } from '../lib/utils'
import type { Message, ToolCallInfo, StreamIteration } from '../types'
import { ItineraryCard } from './ItineraryCard'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Loader2, CheckCircle2, XCircle, ChevronDown, ChevronRight } from 'lucide-react'
import { useState } from 'react'

interface ChatMessageProps {
  message: Message
  isStreaming?: boolean
  streamContent?: string
  /** 流式过程中按迭代分段的完整内容 */
  streamIterations?: StreamIteration[]
  /** 流式过程中实时更新的工具调用列表（当前迭代） */
  currentToolCalls?: ToolCallInfo[]
  /** 当前流式迭代编号 */
  currentIteration?: number
}

/** 工具名称转中文 */
function toolLabel(name: string): string {
  const map: Record<string, string> = {
    getWeather: '查询天气',
    planRoute: '规划路线',
    searchPOI: '搜索景点',
    getTraffic: '查询交通',
    getNearbyHotels: '查找酒店',
    calculateRoute: '计算路线',
    weatherQuery: '天气查询',
    amapWeather: '高德天气',
    amapPoiSearch: '搜索景点',
    webSearch: '上网搜索',
    pageFetch: '抓取网页',
    askUser: '询问用户',
  }
  return map[name] || name
}

/** 单条工具调用行 */
function ToolCallRow({ call }: { call: ToolCallInfo }) {
  const [expanded, setExpanded] = useState(false)
  const hasDetails = call.toolArguments || call.result || call.error

  return (
    <div
      className={cn(
        'flex items-start gap-2 px-3 py-2 rounded-lg text-xs transition-colors',
        call.status === 'running' && 'bg-terracotta-50 dark:bg-terracotta-900/20',
        call.status === 'success' && 'bg-sage-50 dark:bg-sage-900/20',
        call.status === 'error' && 'bg-red-50 dark:bg-red-900/20'
      )}
    >
      {/* 状态图标 */}
      <span className="mt-0.5 flex-shrink-0">
        {call.status === 'running' && (
          <Loader2 className="w-3.5 h-3.5 text-terracotta-500 animate-spin" />
        )}
        {call.status === 'success' && (
          <CheckCircle2 className="w-3.5 h-3.5 text-sage-600 dark:text-sage-400" />
        )}
        {call.status === 'error' && (
          <XCircle className="w-3.5 h-3.5 text-red-500" />
        )}
      </span>

      {/* 内容 */}
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-1.5 flex-wrap">
          <span className="font-medium text-charcoal-800 dark:text-charcoal-200">
            {toolLabel(call.toolName)}
          </span>
          {call.status === 'success' && call.result && (
            <span className="text-charcoal-500 dark:text-charcoal-400 truncate max-w-[300px]">
              → {call.result.length > 80 ? call.result.slice(0, 80) + '…' : call.result}
            </span>
          )}
          {call.status === 'error' && call.error && (
            <span className="text-red-500 truncate max-w-[300px]">
              ✗ {call.error.length > 80 ? call.error.slice(0, 80) + '…' : call.error}
            </span>
          )}
          {hasDetails && (
            <button
              onClick={() => setExpanded(!expanded)}
              className="ml-auto flex-shrink-0 p-0.5 rounded hover:bg-black/5 dark:hover:bg-white/5 text-charcoal-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500"
              aria-label={expanded ? '收起详情' : '展开详情'}
            >
              {expanded ? (
                <ChevronDown className="w-3 h-3" />
              ) : (
                <ChevronRight className="w-3 h-3" />
              )}
            </button>
          )}
        </div>
        {/* 展开详情 */}
        {expanded && (
          <div className="mt-1.5 space-y-1">
            {call.toolArguments && (
              <div className="bg-black/5 dark:bg-white/5 rounded px-2 py-1 font-mono text-[11px] text-charcoal-500 dark:text-charcoal-400 overflow-x-auto">
                <span className="text-charcoal-400 dark:text-charcoal-500">参数:</span>{' '}
                {call.toolArguments.length > 200
                  ? call.toolArguments.slice(0, 200) + '…'
                  : call.toolArguments}
              </div>
            )}
            {call.status === 'success' && call.result && (
              <div className="bg-sage-100/50 dark:bg-sage-900/30 rounded px-2 py-1 text-[11px] text-charcoal-600 dark:text-charcoal-300">
                <span className="text-sage-600 dark:text-sage-400">结果:</span>{' '}
                {call.result.length > 300 ? call.result.slice(0, 300) + '…' : call.result}
              </div>
            )}
            {call.status === 'error' && call.error && (
              <div className="bg-red-100/50 dark:bg-red-900/30 rounded px-2 py-1 text-[11px] text-red-600 dark:text-red-400">
                {call.error}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

/** 工具调用面板（一块迭代的工具列表） */
function ToolCallPanel({
  toolCalls,
  iteration,
}: {
  toolCalls: ToolCallInfo[]
  iteration?: number
}) {
  if (!toolCalls.length) return null

  return (
    <div className="rounded-xl border border-charcoal-200/60 dark:border-charcoal-700/60 bg-white/80 dark:bg-charcoal-800/80 overflow-hidden">
      {/* 头部 */}
      <div className="flex items-center gap-2 px-3 py-2 bg-charcoal-50/50 dark:bg-charcoal-800/50 border-b border-charcoal-200/30 dark:border-charcoal-700/30">
        <span className="text-[10px] font-semibold uppercase tracking-wider text-charcoal-400 dark:text-charcoal-500">
          工具调用
        </span>
        {iteration && iteration > 0 && (
          <span className="ml-auto text-[10px] text-charcoal-400 dark:text-charcoal-500 bg-charcoal-100 dark:bg-charcoal-700 px-1.5 py-0.5 rounded">
            第{iteration}轮
          </span>
        )}
      </div>
      {/* 工具列表 */}
      <div className="pb-2 space-y-0.5">
        {toolCalls.map((call, idx) => (
          <ToolCallRow key={`${call.toolName}-${idx}`} call={call} />
        ))}
      </div>
    </div>
  )
}

/** 共享的 Markdown 组件（表格边框 + 左对齐 + 设计语言一致） */
const markdownComponents = {
  a: ({ href, children }: { href?: string; children?: React.ReactNode }) => (
    <a href={href} target="_blank" rel="noopener noreferrer" className="text-terracotta-600 dark:text-terracotta-400 underline hover:text-terracotta-700">
      {children}
    </a>
  ),
  code: ({ className, children, ...props }: { className?: string; children?: React.ReactNode }) => {
    const isInline = !className
    return isInline ? (
      <code className="bg-charcoal-100 dark:bg-charcoal-700 px-1.5 py-0.5 rounded text-xs font-mono text-charcoal-800 dark:text-charcoal-200" {...props}>
        {children}
      </code>
    ) : (
      <pre className="bg-charcoal-900 dark:bg-black rounded-lg p-3 overflow-x-auto my-2">
        <code className={`${className} text-xs text-warm-white`} {...props}>
          {children}
        </code>
      </pre>
    )
  },
  strong: ({ children }: { children?: React.ReactNode }) => (
    <strong className="font-semibold text-charcoal-900 dark:text-warm-white">{children}</strong>
  ),
  em: ({ children }: { children?: React.ReactNode }) => (
    <em className="italic">{children}</em>
  ),
  ul: ({ children }: { children?: React.ReactNode }) => (
    <ul className="list-disc pl-5 my-2 space-y-1">{children}</ul>
  ),
  ol: ({ children }: { children?: React.ReactNode }) => (
    <ol className="list-decimal pl-5 my-2 space-y-1">{children}</ol>
  ),
  li: ({ children }: { children?: React.ReactNode }) => (
    <li className="text-charcoal-800 dark:text-charcoal-200">{children}</li>
  ),
  h1: ({ children }: { children?: React.ReactNode }) => (
    <h1 className="text-lg font-bold mt-4 mb-2 text-charcoal-900 dark:text-warm-white">{children}</h1>
  ),
  h2: ({ children }: { children?: React.ReactNode }) => (
    <h2 className="text-base font-bold mt-3 mb-2 text-charcoal-900 dark:text-warm-white">{children}</h2>
  ),
  h3: ({ children }: { children?: React.ReactNode }) => (
    <h3 className="text-sm font-bold mt-3 mb-1 text-charcoal-900 dark:text-warm-white">{children}</h3>
  ),
  p: ({ children }: { children?: React.ReactNode }) => (
    <p className="mb-2 last:mb-0">{children}</p>
  ),
  // 表格：带边框 + 左对齐 + 响应式滚动
  table: ({ children }: { children?: React.ReactNode }) => (
    <div className="my-3 overflow-x-auto rounded-lg border border-charcoal-200 dark:border-charcoal-700">
      <table className="w-full text-left text-xs border-collapse">{children}</table>
    </div>
  ),
  thead: ({ children }: { children?: React.ReactNode }) => (
    <thead className="bg-charcoal-50 dark:bg-charcoal-800">{children}</thead>
  ),
  tbody: ({ children }: { children?: React.ReactNode }) => (
    <tbody className="divide-y divide-charcoal-200 dark:divide-charcoal-700">{children}</tbody>
  ),
  tr: ({ children }: { children?: React.ReactNode }) => (
    <tr className="even:bg-warm-white/50 dark:even:bg-charcoal-800/30">{children}</tr>
  ),
  th: ({ children }: { children?: React.ReactNode }) => (
    <th className="px-3 py-2 font-semibold text-charcoal-700 dark:text-charcoal-300 border-r border-charcoal-200 dark:border-charcoal-700 last:border-r-0 text-left">
      {children}
    </th>
  ),
  td: ({ children }: { children?: React.ReactNode }) => (
    <td className="px-3 py-2 text-charcoal-800 dark:text-charcoal-200 border-r border-charcoal-200 dark:border-charcoal-700 last:border-r-0 text-left align-top">
      {children}
    </td>
  ),
}

/** 渲染一段迭代的思考文字（流式状态为纯文本 + 光标，完成态为 Markdown） */
function IterationText({
  text,
  isLast,
  isStreaming,
}: {
  text: string
  isLast: boolean
  isStreaming?: boolean
}) {
  if (!text) return null

  // 流式中的最后一段：带光标
  if (isStreaming && isLast) {
    return (
      <div
        className="bg-warm-white dark:bg-charcoal-800 rounded-2xl rounded-tl-sm px-4 py-3 text-sm leading-relaxed text-charcoal-800 dark:text-charcoal-200"
        aria-live="polite"
      >
        <span>{text}</span>
        <span className="cursor-blink" />
      </div>
    )
  }

  // 已完成的一段
  return (
    <div className="bg-warm-white dark:bg-charcoal-800 rounded-2xl rounded-tl-sm px-4 py-3 text-sm leading-relaxed text-charcoal-800 dark:text-charcoal-200 prose prose-sm max-w-none">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={markdownComponents}
      >
        {text}
      </ReactMarkdown>
    </div>
  )
}

export function ChatMessage({
  message,
  isStreaming,
  streamContent: _streamContent,
  streamIterations,
  currentToolCalls: _currentToolCalls,
  currentIteration: _currentIteration,
}: ChatMessageProps) {
  const isUser = message.role === 'user'

  // ── 决定用哪种数据源渲染 ──
  // 流式阶段：streamIterations 优先；如果没有，回退到 streamContent
  // 完成阶段：message.iterationData 优先；如果没有，回退到 message.content
  const iterations: StreamIteration[] | undefined = isStreaming
    ? streamIterations
    : message.iterationData

  // 用户消息直接显示
  if (isUser) {
    return (
      <div className="flex w-full justify-end" role="log" aria-live="polite" aria-atomic="true">
        <div className="max-w-[80%] md:max-w-[70%] space-y-2">
          <div className="bg-terracotta-500 text-white rounded-2xl rounded-br-sm px-4 py-3 text-sm leading-relaxed">
            {message.content}
          </div>
        </div>
      </div>
    )
  }

  // ── 无 segments 时的降级渲染（旧格式兼容） ──
  if (!iterations || iterations.length === 0) {
    return (
      <div className="flex w-full justify-start" role="log" aria-live="polite" aria-atomic="true">
        <div className="w-full space-y-2">
          {(isStreaming || message.type === 'thinking') && (
            <div className="bg-warm-white dark:bg-charcoal-800 rounded-2xl rounded-tl-sm px-4 py-3 text-sm leading-relaxed text-charcoal-800 dark:text-charcoal-200" aria-live="polite">
              <span>{message.content}</span>
              {isStreaming && <span className="cursor-blink" />}
            </div>
          )}
          {!isStreaming && message.content && (
            <div className="bg-warm-white dark:bg-charcoal-800 rounded-2xl rounded-tl-sm px-4 py-3 text-sm leading-relaxed text-charcoal-800 dark:text-charcoal-200 prose prose-sm max-w-none">
              <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>{message.content}</ReactMarkdown>
            </div>
          )}
          {!isStreaming && !iterations?.length && message.itinerary && (
            <ItineraryCard itinerary={message.itinerary} />
          )}
        </div>
      </div>
    )
  }

  // ── 按迭代分段渲染 ──
  return (
    <div className="flex w-full justify-start" role="log" aria-live="polite" aria-atomic="true">
      <div className="w-full space-y-2">
        {iterations.map((iter, idx) => {
          const isLast = idx === iterations.length - 1
          return (
            <div key={iter.iteration} className="space-y-3">
              {/* 思考文字 */}
              <IterationText
                text={iter.text}
                isLast={isLast}
                isStreaming={isStreaming}
              />
              {/* 该迭代的工具调用面板 */}
              {iter.toolCalls && iter.toolCalls.length > 0 && (
                <ToolCallPanel
                  toolCalls={iter.toolCalls}
                  iteration={iter.iteration}
                />
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}

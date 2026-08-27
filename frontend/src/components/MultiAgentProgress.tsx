import { useState } from 'react'
import type { ReactNode } from 'react'
import {
  Brain,
  Wrench,
  CheckCircle2,
  Flag,
  XCircle,
  Activity,
  GitBranch,
  RotateCcw,
  Users,
  ChevronDown,
} from 'lucide-react'
import type { NodeProgressEntry } from '../types'
import { cn } from '../lib/utils'

interface MultiAgentProgressProps {
  progressMap: Record<string, NodeProgressEntry[]>
}

/** 子代理名称 → 展示名(与后端 SubAgentBase.agentName / TripPlanningTool.NODE_TRIP_PLANNER 对应) */
const SUB_AGENT_LABELS: Record<string, string> = {
  weatherExpert: '天气专家',
  knowledgeExpert: '知识检索',
  tripPlanner: '旅游规划',
  askUser: '反问用户',
  calculator: '计算器',
}

/** 方案 C 进度子类型 → 图标 / 标签 / 配色 */
const PROGRESS_META: Record<string, { icon: ReactNode; label: string; className: string }> = {
  thinking_start: {
    icon: <Brain className="w-3 h-3" />,
    label: '思考',
    className: 'text-terracotta-500',
  },
  thinking_token: {
    icon: <Brain className="w-3 h-3" />,
    label: '推理过程',
    className: 'text-charcoal-500 dark:text-charcoal-300',
  },
  tool_call: {
    icon: <Wrench className="w-3 h-3" />,
    label: '调用工具',
    className: 'text-mustard-500',
  },
  tool_result: {
    icon: <CheckCircle2 className="w-3 h-3" />,
    label: '工具结果',
    className: 'text-sage-500',
  },
  final: {
    icon: <Flag className="w-3 h-3" />,
    label: '结论',
    className: 'text-sage-500',
  },
  error: {
    icon: <XCircle className="w-3 h-3" />,
    label: '错误',
    className: 'text-red-500',
  },
  // TripPlanningTool 转发的事件类型
  node_status: {
    icon: <Activity className="w-3 h-3" />,
    label: '节点',
    className: 'text-terracotta-500',
  },
  branch: {
    icon: <GitBranch className="w-3 h-3" />,
    label: '流转',
    className: 'text-mustard-500',
  },
  graph_iteration: {
    icon: <RotateCcw className="w-3 h-3" />,
    label: '重试',
    className: 'text-mustard-500',
  },
  // 兼容旧协议
  thinking: {
    icon: <Brain className="w-3 h-3" />,
    label: '思考',
    className: 'text-terracotta-500',
  },
}

/**
 * Multi-Agent 子代理执行过程面板(方案 C)
 *
 * <p>消费后端 node_progress 事件(经 useChat.graphTrace.nodeProgressMap 累积),
 * 按子代理分组展示完整生命周期: 思考(thinking_start) → 推理过程(thinking_token,
 * 长文本可展开) → 工具调用(tool_call) → 工具结果(tool_result) → 结论(final) / 错误(error);
 * tripPlanner 转发的 Graph 节点流转(node_status / branch / graph_iteration)同面板展示.
 */
export function MultiAgentProgress({ progressMap }: MultiAgentProgressProps) {
  const agents = Object.entries(progressMap)
  if (agents.length === 0) return null

  return (
    <div className="max-w-3xl mx-auto w-full px-4 pt-3">
      <div className="rounded-xl bg-white dark:bg-charcoal-800 border border-warm-white-200 dark:border-charcoal-700 shadow-sm p-3">
        <div className="flex items-center gap-1.5 text-xs font-semibold text-charcoal-700 dark:text-warm-white mb-2">
          <Users className="w-3.5 h-3.5 text-terracotta-500" />
          子代理执行过程
        </div>
        <div className="space-y-2">
          {agents.map(([node, entries]) => (
            <AgentCard key={node} node={node} entries={entries} />
          ))}
        </div>
      </div>
    </div>
  )
}

type AgentStatus = 'done' | 'error' | 'running'

/** 从条目序列推导子代理状态: 出现 error → error; 出现 final → done; 否则 running */
function deriveStatus(entries: NodeProgressEntry[]): AgentStatus {
  for (let i = entries.length - 1; i >= 0; i--) {
    if (entries[i].progressType === 'error') return 'error'
    if (entries[i].progressType === 'final') return 'done'
  }
  return 'running'
}

function StatusBadge({ status }: { status: AgentStatus }) {
  if (status === 'done') return <CheckCircle2 className="w-3 h-3 text-sage-500 flex-shrink-0" />
  if (status === 'error') return <XCircle className="w-3 h-3 text-red-500 flex-shrink-0" />
  return <span className="w-2 h-2 rounded-full bg-terracotta-500 animate-pulse flex-shrink-0" />
}

function AgentCard({ node, entries }: { node: string; entries: NodeProgressEntry[] }) {
  const [expanded, setExpanded] = useState(false)
  const [longTextExpanded, setLongTextExpanded] = useState(false)
  const status = deriveStatus(entries)
  const label = SUB_AGENT_LABELS[node] || node
  const hasMore = entries.length > 4
  const visible = expanded ? entries : entries.slice(-4)

  return (
    <div className="rounded-lg bg-warm-white dark:bg-charcoal-900/60 p-2">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center justify-between gap-2 text-left focus:outline-none focus-visible:ring-1 focus-visible:ring-terracotta-500 rounded"
        aria-expanded={expanded}
      >
        <span className="text-[11px] font-medium text-charcoal-600 dark:text-charcoal-300 truncate">
          {label}
        </span>
        <span className="flex items-center gap-1 flex-shrink-0">
          <StatusBadge status={status} />
          {hasMore && (
            <ChevronDown
              className={cn('w-3 h-3 text-charcoal-400 transition-transform', expanded && 'rotate-180')}
            />
          )}
        </span>
      </button>
      <div className="mt-1 space-y-0.5">
        {visible.map((e, i) => {
          const m = PROGRESS_META[e.progressType] || PROGRESS_META.thinking
          const isLong = e.progressType === 'thinking_token' && e.content.length > 120
          const expandedText = isLong && longTextExpanded
          return (
            <div key={`${i}-${e.timestamp}`} className="flex items-start gap-1.5 py-0.5">
              <span className={cn('flex-shrink-0 mt-0.5', m.className)}>{m.icon}</span>
              <div className="flex-1 min-w-0">
                <span className="text-[9px] text-charcoal-400 mr-1">{m.label}</span>
                {isLong ? (
                  <button
                    type="button"
                    onClick={() => setLongTextExpanded(!longTextExpanded)}
                    className={cn(
                      'text-[10px] text-charcoal-600 dark:text-charcoal-300 break-all text-left align-top',
                      !expandedText && 'line-clamp-3'
                    )}
                  >
                    {e.content}
                  </button>
                ) : (
                  <span className="text-[10px] text-charcoal-600 dark:text-charcoal-300 break-all">
                    {e.content}
                  </span>
                )}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

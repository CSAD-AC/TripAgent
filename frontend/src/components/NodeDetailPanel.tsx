import { X, Brain, Wrench, CheckCircle2 } from 'lucide-react'
import type { NodeDataPayload, NodeProgressEntry } from '../types'

interface NodeDetailPanelProps {
  nodeId: string
  nodeData: NodeDataPayload
  progressEntries?: NodeProgressEntry[]
  onClose: () => void
}

const NODE_LABELS: Record<string, string> = {
  manager: '主管',
  route: '路线',
  itinerary: '行程',
  budget: '预算',
  validation: '校验',
  report: '报告',
}

const NODE_ICONS: Record<string, string> = {
  manager: '🔷',
  route: '🚗',
  itinerary: '📅',
  budget: '💰',
  validation: '🔍',
  report: '📋',
}

export function NodeDetailPanel({ nodeId, nodeData, progressEntries, onClose }: NodeDetailPanelProps) {
  const label = NODE_LABELS[nodeId] || nodeId
  const icon = NODE_ICONS[nodeId] || '⬜'

  return (
    <div className="absolute right-2 top-2 z-20 w-80 max-h-[260px] overflow-y-auto rounded-xl bg-white dark:bg-charcoal-800 border border-warm-white-200 dark:border-charcoal-700 shadow-lg">
      {/* header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-warm-white-100 dark:border-charcoal-700">
        <span className="text-sm font-semibold text-charcoal-900 dark:text-warm-white">
          {icon} {label}
        </span>
        <button
          onClick={onClose}
          className="p-0.5 rounded hover:bg-warm-white dark:hover:bg-charcoal-700 text-charcoal-400"
        >
          <X className="w-3.5 h-3.5" />
        </button>
      </div>

      {/* content */}
      <div className="p-3 text-xs text-charcoal-600 dark:text-charcoal-300 space-y-2">
        {nodeData.dataType === 'constraints' && (
          <ConstraintsView data={nodeData.data} />
        )}
        {nodeData.dataType === 'route' && (
          <RouteView data={nodeData.data} />
        )}
        {nodeData.dataType === 'dayplans' && (
          <DayPlansView data={nodeData.data} />
        )}
        {nodeData.dataType === 'budget' && (
          <BudgetView data={nodeData.data} />
        )}
        {nodeData.dataType === 'validation' && (
          <ValidationView data={nodeData.data} />
        )}
        {nodeData.dataType === 'decision' && (
          <DecisionView data={nodeData.data} />
        )}
        {nodeData.dataType === 'report' && (
          <ReportView data={nodeData.data} />
        )}
        {!['constraints', 'route', 'dayplans', 'budget', 'validation', 'decision', 'report'].includes(nodeData.dataType) && (
          <pre className="text-[10px] text-charcoal-400 whitespace-pre-wrap">
            {JSON.stringify(nodeData.data, null, 2).slice(0, 500)}
          </pre>
        )}

        {/* 节点实时进度(LLM 思考片段 / 工具调用中间结果) */}
        {progressEntries && progressEntries.length > 0 && (
          <ProgressList entries={progressEntries} />
        )}
      </div>
    </div>
  )
}

// ─── 进度列表 ───

const PROGRESS_ICON: Record<string, React.ReactNode> = {
  thinking: <Brain className="w-3 h-3 text-terracotta-500" />,
  tool_call: <Wrench className="w-3 h-3 text-mustard-500" />,
  tool_result: <CheckCircle2 className="w-3 h-3 text-sage-500" />,
}

const PROGRESS_LABEL: Record<string, string> = {
  thinking: '思考',
  tool_call: '调用工具',
  tool_result: '工具结果',
}

function ProgressList({ entries }: { entries: NodeProgressEntry[] }) {
  // 最多展示最近 6 条,避免面板过高
  const recent = entries.slice(-6)
  return (
    <div className="pt-2 border-t border-warm-white-100 dark:border-charcoal-700 space-y-1">
      <div className="text-[10px] font-semibold text-charcoal-400 uppercase tracking-wide">
        实时进度 ({entries.length})
      </div>
      {recent.map((e, i) => (
        <div key={i} className="flex items-start gap-1.5">
          <span className="flex-shrink-0 mt-0.5">
            {PROGRESS_ICON[e.progressType] || <Brain className="w-3 h-3 text-charcoal-400" />}
          </span>
          <div className="flex-1 min-w-0">
            <div className="text-[9px] text-charcoal-400">
              {PROGRESS_LABEL[e.progressType] || e.progressType}
            </div>
            <div className="text-[10px] text-charcoal-600 dark:text-charcoal-300 break-all line-clamp-2">
              {e.content}
            </div>
          </div>
        </div>
      ))}
    </div>
  )
}

// ─── 各数据类型渲染视图 ───

function ConstraintsView({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="space-y-1">
      <Row label="目的地" value={String(data.destination || '-')} />
      <Row label="天数" value={String(data.days || '-')} />
      <Row label="预算" value={data.budget ? `${data.budget} 元` : '-'} />
      <Row label="人数" value={String(data.companions || '-')} />
      {Array.isArray(data.preferences) && data.preferences.length > 0 && (
        <div className="flex gap-1 flex-wrap mt-1">
          {(data.preferences as string[]).map((p) => (
            <span key={p} className="px-1.5 py-0.5 rounded-full bg-terracotta-50 dark:bg-terracotta-900/30 text-terracotta-600 dark:text-terracotta-400 text-[9px]">
              {p}
            </span>
          ))}
        </div>
      )}
    </div>
  )
}

function RouteView({ data }: { data: Record<string, unknown> }) {
  const segments = (data.segments as Array<Record<string, unknown>>) || []
  return (
    <div className="space-y-1">
      <Row label="总费用" value={`${data.totalCost || 0} 元`} />
      <Row label="总时长" value={`${data.totalDurationMin || 0} 分`} />
      {segments.slice(0, 3).map((seg, i) => (
        <div key={i} className="text-[10px] text-charcoal-500 dark:text-charcoal-400">
          {String(seg.mode || '')}: {String(seg.from || '')} → {String(seg.to || '')}
        </div>
      ))}
      {segments.length > 3 && (
        <div className="text-[9px] text-charcoal-400">+{segments.length - 3} 段...</div>
      )}
    </div>
  )
}

function DayPlansView({ data }: { data: Record<string, unknown> }) {
  const days = (data.days as Array<Record<string, unknown>>) || []
  return (
    <div className="space-y-1">
      <Row label="天数" value={`${days.length} 天`} />
      {days.slice(0, 2).map((day) => {
        const pois = (day.pois as Array<Record<string, unknown>>) || []
        return (
          <div key={String(day.dayIndex || '')} className="text-[10px] text-charcoal-500">
            第{String(day.dayIndex)}天: {pois.map((p) => String(p.name || '')).join(', ').slice(0, 40)}
          </div>
        )
      })}
      {days.length > 2 && (
        <div className="text-[9px] text-charcoal-400">+{days.length - 2} 天...</div>
      )}
    </div>
  )
}

function BudgetView({ data }: { data: Record<string, unknown> }) {
  const breakdown = (data.breakdown as Record<string, number>) || {}
  return (
    <div className="space-y-1">
      <Row label="总费用" value={`${String(data.totalCost ?? 0)} 元`} />
      <Row label="预算" value={`${String(data.budget ?? 0)} 元`} />
      {(data.isOverBudget as boolean) && (
        <div className="text-[10px] text-red-500 font-medium">
          ⚠️ 超支 {String(data.overageAmount ?? 0)} 元
        </div>
      )}
      {Object.keys(breakdown).length > 0 && (
        <div className="text-[9px] text-charcoal-400">
          {Object.entries(breakdown).slice(0, 4).map(([k, v]) => (
            <div key={k}>{k}: {v}元</div>
          ))}
        </div>
      )}
    </div>
  )
}

function ValidationView({ data }: { data: Record<string, unknown> }) {
  const failures = (data.failures as Array<Record<string, unknown>>) || []
  const warnings = (data.warnings as string[]) || []
  return (
    <div className="space-y-1">
      <div className="flex items-center gap-1">
        {(data.passed as boolean) ? '✅ 通过' : '❌ 未通过'}
      </div>
      {failures.length > 0 && (
        <div className="text-[10px] text-red-500">
          {failures.slice(0, 2).map((f, i) => (
            <div key={i}>• {String(f.reason || '')}</div>
          ))}
        </div>
      )}
      {warnings.length > 0 && (
        <div className="text-[9px] text-mustard-600 dark:text-mustard-400">
          ⚠️ {warnings.slice(0, 2).join('; ')}
        </div>
      )}
    </div>
  )
}

function DecisionView({ data }: { data: Record<string, unknown> }) {
  const decisionMap: Record<string, string> = {
    retry: '🔄 重试',
    ask_user: '💬 反问用户',
    give_up: '🏳️ 放弃',
  }
  const decision = String(data.decision || '')
  return (
    <div className="space-y-1">
      <Row label="决策" value={decisionMap[decision] || decision} />
      {String(data.targetWorker ?? '') && <Row label="目标" value={String(data.targetWorker)} />}
      {String(data.reason ?? '') && (
        <div className="text-[10px] text-charcoal-500 dark:text-charcoal-400 italic">
          {String(data.reason)}
        </div>
      )}
    </div>
  )
}

function ReportView({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="space-y-1">
      <Row label="报告长度" value={`${String(data.length ?? 0)} 字符`} />
      {String(data.summary ?? '') && (
        <div className="text-[10px] text-charcoal-500 dark:text-charcoal-400 leading-relaxed">
          {String(data.summary).slice(0, 200)}
        </div>
      )}
    </div>
  )
}

// ─── 通用行组件 ───

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between">
      <span className="text-charcoal-400 dark:text-charcoal-500">{label}</span>
      <span className="font-medium text-charcoal-700 dark:text-charcoal-200">{value}</span>
    </div>
  )
}

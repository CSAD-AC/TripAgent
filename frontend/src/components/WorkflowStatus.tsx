import { cn } from '../lib/utils'
import { WORKFLOW_STEPS } from '../types'
import { Check } from 'lucide-react'

interface WorkflowStatusProps {
  currentNode: string | null
  visible?: boolean
}

export function WorkflowStatus({ currentNode, visible = true }: WorkflowStatusProps) {
  if (!visible || !currentNode) return null

  const currentIdx = WORKFLOW_STEPS.findIndex((s) => s.node === currentNode)

  return (
    <div
      className="px-4 py-3 bg-white/80 dark:bg-charcoal-900/80 backdrop-blur-sm border-b border-warm-white-200 dark:border-charcoal-700"
      role="status"
      aria-live="polite"
      aria-label="处理进度"
    >
      <div className="flex items-center gap-0 max-w-2xl mx-auto">
        {WORKFLOW_STEPS.map((step, idx) => {
          const isCompleted = idx < currentIdx
          const isActive = idx === currentIdx
          const isPending = idx > currentIdx

          return (
            <div key={step.node} className="flex items-center flex-1 min-w-0" aria-current={isActive ? 'step' : undefined}>
              {/* 节点圆点 */}
              <div className="flex flex-col items-center">
                <div
                  className={cn(
                    'step-dot flex items-center justify-center text-white text-[8px] font-bold',
                    isCompleted && 'completed',
                    isActive && 'active',
                    isPending && 'opacity-40'
                  )}
                >
                  {isCompleted && <Check className="w-3 h-3" />}
                </div>
                <span
                  className={cn(
                    'text-[10px] mt-1 whitespace-nowrap font-medium transition-colors',
                    isActive && 'text-terracotta-600 dark:text-terracotta-400',
                    isCompleted && 'text-sage-600 dark:text-sage-400',
                    isPending && 'text-charcoal-300 dark:text-charcoal-600'
                  )}
                >
                  {step.label}
                </span>
              </div>
              {/* 连接线 */}
              {idx < WORKFLOW_STEPS.length - 1 && (
                <div
                  className={cn(
                    'step-line mx-1',
                    (isCompleted || (isActive && currentIdx > idx)) && 'completed'
                  )}
                />
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}

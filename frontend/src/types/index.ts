/** 工具调用记录（一次工具调用全生命周期） */
export interface ToolCallInfo {
  toolName: string
  toolArguments?: string
  status: 'running' | 'success' | 'error'
  result?: string
  error?: string
}

/** 一轮迭代的完整内容：思考文字 + 该轮的工具调用 */
export interface StreamIteration {
  iteration: number
  text: string
  toolCalls: ToolCallInfo[]
}

/** 对话消息 */
export interface Message {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  /** SSE 流式事件类型（最终定型后的状态） */
  type?: 'thinking' | 'tool_call' | 'tool_result' | 'final'
  timestamp: number
  /** 结构化行程数据（从 markdown 解析） */
  itinerary?: Itinerary
  /** 本轮完整的工具调用记录（仅 assistant 消息最终态携带） */
  toolCalls?: ToolCallInfo[]
  /** 按迭代分段的完整内容（仅 assistant 消息最终态携带） */
  iterationData?: StreamIteration[]
  /** 最后迭代编号 */
  iterationCount?: number
  /** API 模式（react/graph）— 用于消息气泡标识 */
  apiMode?: ApiMode
}

/** API 模式: react=ReAct 工具调用路径, graph=Graph 工作流路径 */
export type ApiMode = 'react' | 'graph'

/** SWV 工作流 6 节点（Graph 模式专用） */
export type WorkflowNode =
  | 'manager'
  | 'route'
  | 'itinerary'
  | 'budget'
  | 'validation'
  | 'report'

export const WORKFLOW_STEPS: { node: WorkflowNode; label: string }[] = [
  { node: 'manager', label: '主管' },
  { node: 'route', label: '路线' },
  { node: 'itinerary', label: '行程' },
  { node: 'budget', label: '预算' },
  { node: 'validation', label: '校验' },
  { node: 'report', label: '报告' },
]

/** 结构化行程 */
export interface Itinerary {
  destination: string
  days: number
  budget?: string
  dayPlans: DayPlan[]
  route?: RouteInfo
  recommendations?: Recommendation[]
}

export interface DayPlan {
  day: number
  date?: string
  weather?: string
  morning: string[]
  afternoon: string[]
  evening: string[]
}

export interface RouteInfo {
  origin: string
  destination: string
  options: {
    mode: string
    duration: string
    cost: string
    details: string
  }[]
}

export interface Recommendation {
  name: string
  type: 'attraction' | 'food' | 'itinerary'
  description: string
}

/** 对话会话 */
export interface Conversation {
  id: number
  title: string
  createdAt: string
  messageCount: number
}

/** API 请求 */
export interface ChatRequest {
  /** 后端权威生成;前端不带表示新会话,带表示续聊(UUID 格式) */
  conversationId?: string
  message: string
}

/** API 响应（同步） */
export interface ChatResponse {
  answer: string
  conversationId: string
  displayStatus: string
  durationMs: number
}

/** 反问选项 */
export interface ClarificationOption {
  label: string
  value: string
}

/** 等待回答的反问 */
export interface PendingClarification {
  questionId: string
  conversationId: string
  question: string
  options: ClarificationOption[]
  allowCustom: boolean
}

/** SSE 事件数据类型(全流程流式协议) */
export interface SSEEvent {
  type:
    | 'thinking_token'
    | 'tool_call_start'
    | 'tool_call'
    | 'tool_result'
    | 'tool_error'
    | 'iteration_separator'
    | 'final'
    | 'error'
    | 'session_init'
    | 'heartbeat'
    | 'clarification_request'
    | 'node_start'
    | 'node_end'
    | 'node_warning'
    | 'branch_taken'
  content?: string
  toolName?: string
  toolArguments?: string
  toolResult?: string
  conversationId?: string
  durationMs?: number
  iterationInfo?: string
  /** 反问事件专属 */
  questionId?: string
  allowCustom?: boolean
}

/** Graph 流式执行追踪(用于 WorkflowStatus 组件) */
export interface GraphTrace {
  /** 当前正在执行的节点 */
  currentNode: string | null
  /** 已完成的节点列表（用于连接线动画） */
  completedNodes: string[]
  /** 路由序列（用于可视化流向） */
  branches: { from: string; to: string }[]
  /** 警告信息 */
  warnings: string[]
}

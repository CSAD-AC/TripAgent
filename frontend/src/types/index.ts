/** 工具调用记录（一次工具调用全生命周期） */
export interface ToolCallInfo {
  /** 后端 Spring AI 生成的 toolCall.id,用于并行模式下 tool_call ↔ tool_result 精确匹配 */
  toolCallId?: string
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
  /** 错误标记 (true 表示流式过程发生不可恢复异常, ChatMessage 渲染红色边框) */
  error?: boolean
  /** 链路追踪 ID (仅 error=true 时携带, 用户报错反馈时用, #8 trace_id) */
  traceId?: string
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
    // Graph 工作流事件
    | 'graph_topology'
    | 'node_status'
    | 'node_progress'
    | 'node_data'
    | 'node_error'
    | 'graph_iteration'
    | 'branch_taken'
  content?: string
  toolName?: string
  toolArguments?: string
  toolResult?: string
  /** 工具调用 ID (tool_call/tool_result/tool_error) — 用于并行模式下匹配具体调用 */
  toolCallId?: string
  conversationId?: string
  durationMs?: number
  iterationInfo?: string
  /** 链路追踪 ID (trace_id 优化 #8) -- session_init 携带, 前端展示在错误页 */
  traceId?: string
  /** 反问事件专属 */
  questionId?: string
  allowCustom?: boolean
  // ============ Graph 流可视化字段 ============
  /** 节点名称（node_status / node_progress / node_data） */
  node?: string
  /** 节点状态（node_status: queued / running / done / error / skipped） */
  nodeStatus?: string
  /** 数据类型（node_data: constraints / route / dayplans / budget / validation / report / decision） */
  dataType?: string
  /** 复杂数据负载（node_data / graph_topology） */
  data?: Record<string, unknown>
  /** 进度子类型（node_progress: thinking / tool_call / tool_result） */
  progressType?: string
  /** 当前迭代次数（graph_iteration） */
  iterationCount?: number
  /** 最大迭代次数（graph_iteration） */
  maxIterations?: number
  /** 条件边标签（branch_taken: passed / failed / confirmed 等） */
  condition?: string
  /** 起始节点（branch_taken） */
  from?: string
  /** 目标节点（branch_taken） */
  to?: string
}

/** 拓扑定义中的节点 */
export interface TopologyNode {
  id: string
  label: string
  type: string
  description: string
}

/** 拓扑定义中的边 */
export interface TopologyEdge {
  from: string
  to: string
  label: string
  conditions: string[]
}

/** 节点输出数据 */
export interface NodeDataPayload {
  node: string
  dataType: string
  data: Record<string, unknown>
}

/** 节点实时进度条目(LLM 思考片段 / 工具调用中间结果) */
export interface NodeProgressEntry {
  progressType: 'thinking' | 'tool_call' | 'tool_result' | string
  content: string
  timestamp: number
}

/** Graph 流式执行追踪(用于 GraphFlow 组件) */
export interface GraphTrace {
  /** 当前正在执行的节点 */
  currentNode: string | null
  /** 已完成的节点列表（按完成顺序） */
  completedNodes: string[]
  /** 路由序列（用于可视化流向） */
  branches: { from: string; to: string; condition?: string }[]
  /** 警告信息 */
  warnings: string[]
  // ============ 新协议字段 ============
  /** Graph 拓扑定义（从 graph_topology 事件获取） */
  topology?: {
    nodes: TopologyNode[]
    edges: TopologyEdge[]
    startNode: string
    endNode: string
  }
  /** 节点状态映射（nodeId → status） */
  nodeStatusMap?: Record<string, string>
  /** 节点数据映射（nodeId → NodeDataPayload） */
  nodeDataMap?: Record<string, NodeDataPayload>
  /** 节点进度映射（nodeId → NodeProgressEntry[]）,由 node_progress 事件累积 */
  nodeProgressMap?: Record<string, NodeProgressEntry[]>
  /** 当前迭代次数 */
  iterationCount?: number
  /** 最大迭代次数 */
  maxIterations?: number
  /** 迭代回退原因 */
  iterationReason?: string
}

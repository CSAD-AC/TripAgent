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
}

/** 工作流节点 */
export type WorkflowNode =
  | 'intent_analysis'
  | 'route_planning'
  | 'weather_check'
  | 'knowledge_retrieval'
  | 'daily_itinerary'
  | 'result_assembly'

export const WORKFLOW_STEPS: { node: WorkflowNode; label: string }[] = [
  { node: 'intent_analysis', label: '解析意图' },
  { node: 'route_planning', label: '规划路线' },
  { node: 'weather_check', label: '查询天气' },
  { node: 'knowledge_retrieval', label: '检索知识' },
  { node: 'daily_itinerary', label: '编排行程' },
  { node: 'result_assembly', label: '生成报告' },
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
  conversationId?: number | string
  message: string
}

/** API 响应（同步） */
export interface ChatResponse {
  answer: string
  conversationId: string
  displayStatus: string
  durationMs: number
}

/** SSE 事件数据类型（全流程流式协议 8 种事件） */
export interface SSEEvent {
  type: 'thinking_token' | 'tool_call_start' | 'tool_call' | 'tool_result' | 'tool_error' | 'iteration_separator' | 'final' | 'error'
  content?: string
  toolName?: string
  toolArguments?: string
  toolResult?: string
  conversationId?: string
  durationMs?: number
  iterationInfo?: string
}

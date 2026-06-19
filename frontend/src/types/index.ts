/** 对话消息 */
export interface Message {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  /** SSE 流式事件：thinking / node / tool_call / final */
  type?: 'thinking' | 'node' | 'tool_call' | 'final'
  timestamp: number
  /** 结构化行程数据（从 markdown 解析） */
  itinerary?: Itinerary
  /** 工作流节点状态 */
  workflowNode?: WorkflowNode
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

/** SSE 事件数据类型 */
export interface SSEEvent {
  type: 'thinking' | 'node' | 'tool_call' | 'final' | 'error'
  content?: string
  tool?: string
  duration?: number
}

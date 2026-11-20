import type { MemoryEventStatus, Message } from '../types'

const MEMORY_KEYWORD = /(记住|记一下|帮我记住|记下来|请记住)/
const OTHER_REQUEST = /(推荐|规划|查询|怎么|吗|顺便|帮我安排|路线|天气|酒店|景点)/

export function isMemoryCommand(content: string): boolean {
  return MEMORY_KEYWORD.test(content)
}

export function isPureMemoryCommand(content: string): boolean {
  const text = content.trim()
  if (!isMemoryCommand(text) || /[?？]/.test(text) || OTHER_REQUEST.test(text)) {
    return false
  }
  return text.length <= 100
}

export interface MemoryEventDetails {
  profileCandidates: string[]
  eventCandidates: string[]
  discarded: string[]
  changed: boolean
  profile: string
  events: string
  reason: string
}

function safeJson(value?: string | null): Record<string, unknown> {
  if (!value) return {}
  try {
    const parsed: unknown = JSON.parse(value)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : {}
  } catch {
    return {}
  }
}

function textArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string' && !!item.trim())
    : []
}

function textValue(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

export function parseMemoryEventDetails(event: MemoryEventStatus): MemoryEventDetails {
  const candidate = safeJson(event.candidateJson)
  const result = safeJson(event.resultJson)
  return {
    profileCandidates: textArray(candidate.profileCandidates),
    eventCandidates: textArray(candidate.eventCandidates),
    discarded: textArray(candidate.discarded),
    changed: result.changed === true,
    profile: textValue(result.profile),
    events: textValue(result.events),
    reason: textValue(result.reason),
  }
}

export function memoryEventTime(event: MemoryEventStatus): number {
  const raw = event.processedAt || event.createdAt
  const value = new Date(raw).getTime()
  return Number.isNaN(value) ? Date.now() : value
}

/**
 * Place memory events at the end of the user turn that produced them.
 * Persisted messages stay in their original order; memory cards are a
 * separate, replayable timeline item and never masquerade as chat bubbles.
 */
export function attachMemoryEvents(
  messages: Message[],
  events: MemoryEventStatus[]
): Message[] {
  if (events.length === 0) return messages

  const userMessages = messages
    .map((message, index) => ({ message, index }))
    .filter(({ message }) => message.role === 'user')

  const eventsByUserIndex = new Map<number, MemoryEventStatus[]>()
  for (const event of events) {
    const eventCreatedAt = new Date(event.createdAt).getTime()
    let ownerIndex = userMessages[0]?.index ?? -1
    const command = event.content?.trim()
    const commandOwner = command
      ? [...userMessages].reverse().find(
          (candidate) => candidate.message.content.trim() === command
        )
      : undefined
    if (commandOwner) {
      ownerIndex = commandOwner.index
    } else {
      for (const candidate of userMessages) {
        if (candidate.message.timestamp <= eventCreatedAt) {
          ownerIndex = candidate.index
        } else {
          break
        }
      }
    }
    if (ownerIndex < 0) continue
    const bucket = eventsByUserIndex.get(ownerIndex) || []
    bucket.push(event)
    eventsByUserIndex.set(ownerIndex, bucket)
  }

  const result: Message[] = []
  const appendEvents = (userIndex: number) => {
    const bucket = eventsByUserIndex.get(userIndex) || []
    for (const event of [...bucket].sort((a, b) =>
      new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime())) {
      result.push({
        id: `memory-event-${event.id}`,
        role: 'system',
        content: '',
        type: 'final',
        timestamp: memoryEventTime(event),
        memoryEvent: event,
      })
    }
  }

  let currentUserIndex = -1
  for (let index = 0; index < messages.length; index += 1) {
    const message = messages[index]
    if (message.role === 'user') {
      if (currentUserIndex >= 0 && messages[index - 1]?.role !== 'user') {
        appendEvents(currentUserIndex)
      }
      currentUserIndex = index
    }
    result.push(message)
  }
  if (currentUserIndex >= 0) appendEvents(currentUserIndex)

  const attachedIds = new Set(result
    .filter((message) => message.memoryEvent)
    .map((message) => message.memoryEvent!.id))
  for (const event of events) {
    if (!attachedIds.has(event.id)) {
      result.push({
        id: `memory-event-${event.id}`,
        role: 'system',
        content: '',
        type: 'final',
        timestamp: memoryEventTime(event),
        memoryEvent: event,
      })
    }
  }
  return result
}

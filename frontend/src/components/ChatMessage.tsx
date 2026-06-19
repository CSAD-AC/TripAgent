import { cn } from '../lib/utils'
import type { Message } from '../types'
import { ItineraryCard } from './ItineraryCard'

interface ChatMessageProps {
  message: Message
  isStreaming?: boolean
  streamContent?: string
}

export function ChatMessage({ message, isStreaming, streamContent }: ChatMessageProps) {
  const isUser = message.role === 'user'
  const content = isStreaming ? streamContent || message.content : message.content

  return (
    <div
      className={cn('flex w-full', isUser ? 'justify-end' : 'justify-start')}
      role="log"
      aria-live="polite"
      aria-atomic="true"
    >
      <div
        className={cn(
          'max-w-[80%] md:max-w-[70%] space-y-2',
          isUser ? 'order-1' : 'order-1'
        )}
      >
        {/* 用户消息 */}
        {isUser && (
          <div className="bg-charcoal-100 dark:bg-charcoal-800 rounded-2xl rounded-br-sm px-4 py-3 text-charcoal-900 dark:text-warm-white text-sm leading-relaxed">
            {content}
          </div>
        )}

        {/* Agent 回复 */}
        {!isUser && (
          <div className="space-y-3">
            {/* 思考中 / 流式输出 */}
            {isStreaming && (
              <div
                className="bg-white dark:bg-charcoal-800 rounded-2xl rounded-tl-sm px-4 py-3 shadow-sm border border-warm-white-100 dark:border-charcoal-700 text-sm leading-relaxed text-charcoal-800 dark:text-charcoal-200"
                aria-live="polite"
              >
                <span>{content}</span>
                <span className="cursor-blink" />
              </div>
            )}

            {/* 最终回复 */}
            {!isStreaming && content && !message.itinerary && (
              <div className="bg-white dark:bg-charcoal-800 rounded-2xl rounded-tl-sm px-4 py-3 shadow-sm border border-warm-white-100 dark:border-charcoal-700 text-sm leading-relaxed text-charcoal-800 dark:text-charcoal-200 prose prose-sm max-w-none">
                <div className="whitespace-pre-wrap">{content}</div>
              </div>
            )}

            {/* 结构化行程卡片 */}
            {!isStreaming && message.itinerary && (
              <ItineraryCard itinerary={message.itinerary} />
            )}
          </div>
        )}
      </div>
    </div>
  )
}

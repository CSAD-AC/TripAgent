import { cn } from '../lib/utils'
import type { Message } from '../types'
import { ItineraryCard } from './ItineraryCard'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

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
          isUser ? 'max-w-[80%] md:max-w-[70%]' : 'w-full',
          'space-y-2'
        )}
      >
        {/* 用户消息 */}
        {isUser && (
          <div className="bg-terracotta-500 text-white rounded-2xl rounded-br-sm px-4 py-3 text-sm leading-relaxed">
            {content}
          </div>
        )}

        {/* Agent 回复 */}
        {!isUser && (
          <div className="space-y-3">
            {/* 思考中 / 流式输出 */}
            {isStreaming && (
              <div
                className="bg-warm-white dark:bg-charcoal-800 rounded-2xl rounded-tl-sm px-4 py-3 text-sm leading-relaxed text-charcoal-800 dark:text-charcoal-200"
                aria-live="polite"
              >
                <span>{content}</span>
                <span className="cursor-blink" />
              </div>
            )}

            {/* 最终回复 */}
            {!isStreaming && content && !message.itinerary && (
              <div className="bg-warm-white dark:bg-charcoal-800 rounded-2xl rounded-tl-sm px-4 py-3 text-sm leading-relaxed text-charcoal-800 dark:text-charcoal-200 prose prose-sm max-w-none">
                <ReactMarkdown
                  remarkPlugins={[remarkGfm]}
                  components={{
                    a: ({ href, children }) => (
                      <a href={href} target="_blank" rel="noopener noreferrer" className="text-terracotta-600 dark:text-terracotta-400 underline hover:text-terracotta-700">
                        {children}
                      </a>
                    ),
                    code: ({ className, children, ...props }) => {
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
                    strong: ({ children }) => <strong className="font-semibold text-charcoal-900 dark:text-warm-white">{children}</strong>,
                    em: ({ children }) => <em className="italic">{children}</em>,
                    ul: ({ children }) => <ul className="list-disc pl-5 my-2 space-y-1">{children}</ul>,
                    ol: ({ children }) => <ol className="list-decimal pl-5 my-2 space-y-1">{children}</ol>,
                    li: ({ children }) => <li className="text-charcoal-800 dark:text-charcoal-200">{children}</li>,
                    h1: ({ children }) => <h1 className="text-lg font-bold mt-4 mb-2 text-charcoal-900 dark:text-warm-white">{children}</h1>,
                    h2: ({ children }) => <h2 className="text-base font-bold mt-3 mb-2 text-charcoal-900 dark:text-warm-white">{children}</h2>,
                    h3: ({ children }) => <h3 className="text-sm font-bold mt-3 mb-1 text-charcoal-900 dark:text-warm-white">{children}</h3>,
                    p: ({ children }) => <p className="mb-2 last:mb-0">{children}</p>,
                  }}
                >
                  {content}
                </ReactMarkdown>
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

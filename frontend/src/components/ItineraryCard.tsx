import { useState } from 'react'
import { cn } from '../lib/utils'
import type { Itinerary } from '../types'
import { Sun, Cloud, MapPin, Utensils, Landmark, Bus, ChevronDown, ChevronUp } from 'lucide-react'

interface ItineraryCardProps {
  itinerary: Itinerary
}

export function ItineraryCard({ itinerary }: ItineraryCardProps) {
  const [activeDay, setActiveDay] = useState(0)
  const [routeExpanded, setRouteExpanded] = useState(false)

  return (
    <div className="corner-fold p-5 space-y-4" role="region" aria-label="行程规划">
      {/* 头部：目的地概览 */}
      <div className="flex items-start justify-between">
        <div>
          <h3 className="font-display text-lg font-semibold text-charcoal-900 dark:text-warm-white">
            <MapPin className="inline-block w-4 h-4 mr-1 text-terracotta-500" />
            {itinerary.destination}
          </h3>
          <div className="flex gap-2 mt-1">
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-terracotta-50 dark:bg-terracotta-900/30 text-terracotta-600 dark:text-terracotta-400">
              {itinerary.days} 天行程
            </span>
            {itinerary.budget && (
              <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-sage-50 dark:bg-sage-900/30 text-sage-600 dark:text-sage-400">
                {itinerary.budget}
              </span>
            )}
          </div>
        </div>
      </div>

      {/* 每日行程 Tab */}
      <div className="flex gap-1 border-b border-warm-white-200 dark:border-charcoal-700 pb-1 overflow-x-auto" role="tablist" aria-label="每日行程">
        {itinerary.dayPlans.map((day, idx) => (
          <button
            key={idx}
            onClick={() => setActiveDay(idx)}
            role="tab"
            aria-selected={idx === activeDay}
            aria-controls={`day-panel-${idx}`}
            className={cn(
              'px-3 py-2.5 text-xs font-medium rounded-t-md transition-colors whitespace-nowrap min-h-[44px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500',
              idx === activeDay
                ? 'bg-terracotta-50 dark:bg-terracotta-900/30 text-terracotta-600 dark:text-terracotta-400 border-b-2 border-terracotta-500'
                : 'text-charcoal-400 dark:text-charcoal-500 hover:text-charcoal-600 dark:hover:text-charcoal-300'
            )}
          >
            第 {day.day} 天
            {day.weather && (
              <span className="ml-1 inline-block">
                {day.weather.includes('晴') || day.weather.includes('多云') ? (
                  <Sun className="w-3 h-3 inline text-mustard-500" />
                ) : (
                  <Cloud className="w-3 h-3 inline text-charcoal-400" />
                )}
              </span>
            )}
          </button>
        ))}
      </div>

      {/* 当前日行程详情 */}
      {itinerary.dayPlans[activeDay] && (
        <div
          className="space-y-3 text-sm"
          role="tabpanel"
          id={`day-panel-${activeDay}`}
          aria-label={`第 ${itinerary.dayPlans[activeDay].day} 天行程`}
        >
          <TimeSlot
            icon={<Sun className="w-3.5 h-3.5 text-mustard-500" />}
            label="上午"
            items={itinerary.dayPlans[activeDay].morning}
          />
          <TimeSlot
            icon={<Sun className="w-3.5 h-3.5 text-terracotta-400" />}
            label="下午"
            items={itinerary.dayPlans[activeDay].afternoon}
          />
          <TimeSlot
            icon={<Utensils className="w-3.5 h-3.5 text-charcoal-400 dark:text-charcoal-500" />}
            label="晚上"
            items={itinerary.dayPlans[activeDay].evening}
          />
        </div>
      )}

      {/* 交通方案折叠面板 */}
      {itinerary.route && itinerary.route.options.length > 0 && (
        <div className="border-t border-warm-white-200 dark:border-charcoal-700 pt-3">
          <button
            onClick={() => setRouteExpanded(!routeExpanded)}
            aria-expanded={routeExpanded}
            aria-controls="route-panel"
            className="flex items-center gap-1.5 text-xs font-medium text-charcoal-500 dark:text-charcoal-400 hover:text-charcoal-700 dark:hover:text-charcoal-200 transition-colors min-h-[44px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta-500"
          >
            <Bus className="w-3.5 h-3.5" />
            交通方案
            {routeExpanded ? (
              <ChevronUp className="w-3 h-3" />
            ) : (
              <ChevronDown className="w-3 h-3" />
            )}
          </button>
          {routeExpanded && (
            <div id="route-panel" className="mt-2 space-y-1.5">
              {itinerary.route.options.map((opt, idx) => (
                <div
                  key={idx}
                  className="flex items-center justify-between px-3 py-2 bg-warm-white dark:bg-charcoal-800 rounded-lg text-xs"
                >
                  <span className="font-medium text-charcoal-700 dark:text-charcoal-200">{opt.mode}</span>
                  <span className="text-charcoal-500 dark:text-charcoal-400">{opt.duration}</span>
                  <span className="text-terracotta-600 dark:text-terracotta-400 font-medium">{opt.cost}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* 推荐景点 */}
      {itinerary.recommendations && itinerary.recommendations.length > 0 && (
        <div className="border-t border-warm-white-200 dark:border-charcoal-700 pt-3">
          <h4 className="text-xs font-medium text-charcoal-500 dark:text-charcoal-400 mb-2 flex items-center gap-1">
            <Landmark className="w-3 h-3" />
            推荐景点
          </h4>
          <div className="flex gap-2 overflow-x-auto pb-1">
            {itinerary.recommendations.map((rec, idx) => (
              <div
                key={idx}
                className="flex-shrink-0 px-3 py-2 bg-warm-white dark:bg-charcoal-800 rounded-lg text-xs min-w-[120px]"
              >
                <span className="font-medium text-charcoal-700 dark:text-charcoal-200 block">{rec.name}</span>
                <span className="text-charcoal-400 dark:text-charcoal-500 text-[10px]">
                  {rec.type === 'attraction' ? '景点' : rec.type === 'food' ? '美食' : '路书'}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

function TimeSlot({ icon, label, items }: { icon: React.ReactNode; label: string; items: string[] }) {
  if (!items || items.length === 0) return null
  return (
    <div className="flex gap-2">
      <div className="flex-shrink-0 mt-0.5">{icon}</div>
      <div className="space-y-0.5">
        <span className="text-xs font-medium text-charcoal-400 dark:text-charcoal-500">{label}</span>
        {items.map((item, idx) => (
          <p key={idx} className="text-charcoal-700 dark:text-charcoal-300 leading-relaxed">
            {item}
          </p>
        ))}
      </div>
    </div>
  )
}

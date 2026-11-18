import type { GeoPlanScheduleView, GeoWeekday } from './types'

/**
 * The one thing the report surface says when it cannot show a report.
 *
 * 403 and 404 collapse into this single sentence on purpose: telling them apart would let anyone
 * holding an id learn whether another account's report exists. The trace id still travels with it,
 * so support can resolve the real cause without the page ever answering that question.
 */
export const GEO_REPORT_UNAVAILABLE_MESSAGE = '报告不存在或已不可用'

const GEO_WEEKDAY_LABELS: Record<GeoWeekday, string> = {
  1: '周一',
  2: '周二',
  3: '周三',
  4: '周四',
  5: '周五',
  6: '周六',
  7: '周日',
}

export function formatGeoCount(value: number | undefined) {
  return value !== undefined && Number.isSafeInteger(value) && value >= 0
    ? value.toLocaleString('zh-CN')
    : '—'
}

export function formatGeoDateTime(value: string | undefined) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}

export function formatGeoPoints(value: number | undefined) {
  const formatted = formatGeoCount(value)
  return formatted === '—' ? formatted : `${formatted} 积分`
}

export function formatGeoSchedule(schedule: GeoPlanScheduleView) {
  switch (schedule.frequency) {
    case 'HOURLY':
      return `每小时 ${schedule.localTime.slice(3)} 分`
    case 'DAILY':
      return `每天 ${schedule.localTime}`
    case 'WEEKLY': {
      const days = schedule.weekdays
        .map((day) => GEO_WEEKDAY_LABELS[day])
        .join('、')
      return `${days || '未选择星期'} ${schedule.localTime}`
    }
    case 'INTERVAL_DAYS':
      return `每 ${schedule.intervalDays} 天 ${schedule.localTime}`
  }
}

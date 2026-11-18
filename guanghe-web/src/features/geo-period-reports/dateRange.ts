const DAY_MS = 86_400_000

/** The report contract uses Shanghai calendar dates, independent of the device timezone. */
export function shanghaiToday(now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now)
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}`
}

function calendarTime(value: string): number | undefined {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined
  const time = Date.parse(`${value}T00:00:00.000Z`)
  return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === value ? time : undefined
}

export function previousCompleteWeek(now = new Date()): { startDate: string; endDate: string } {
  const today = calendarTime(shanghaiToday(now))!
  const mondayOffset = (new Date(today).getUTCDay() + 6) % 7
  const currentMonday = today - mondayOffset * DAY_MS
  return { startDate: new Date(currentMonday - 7 * DAY_MS).toISOString().slice(0, 10), endDate: new Date(currentMonday - DAY_MS).toISOString().slice(0, 10) }
}

export function periodDateError(startDate: string, endDate: string, now = new Date()): string | undefined {
  const start = calendarTime(startDate)
  const end = calendarTime(endDate)
  if (start === undefined || end === undefined) return '请选择有效的开始和结束日期。'
  if (end < start) return '结束日期不能早于开始日期。'
  if (end > calendarTime(shanghaiToday(now))!) return '不能选择未来日期，日期以北京时间为准。'
  if ((end - start) / DAY_MS + 1 > 92) return '每份报告最多包含 92 个自然日，请缩短日期范围。'
  return undefined
}

/**
 * Display helpers shared by the user-facing and operator-facing commercial surfaces.
 *
 * Amounts arrive as integer cents and points as safe integers, so they are formatted from the
 * integer rather than from a float: `1_000_000_000_000_001` cents has no exact double
 * representation, and a money column that is off by a cent is worse than no column.
 */

export function isSafeIntegerValue(value: number | undefined | null): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value)
}

export function formatPoints(value: number | undefined | null): string {
  return isSafeIntegerValue(value) ? value.toLocaleString('zh-CN') : '—'
}

export function formatSignedPoints(value: number | undefined | null): string {
  if (!isSafeIntegerValue(value)) return '—'
  return `${value > 0 ? '+' : ''}${value.toLocaleString('zh-CN')}`
}

export function formatCents(value: number | undefined | null): string {
  if (!isSafeIntegerValue(value) || value < 0) return '—'
  const cents = BigInt(value)
  return `¥${cents / 100n}.${String(cents % 100n).padStart(2, '0')}`
}

/** Returns the raw value when it is not a date the browser can parse, so nothing is invented. */
export function formatDateTime(value: string | undefined | null): string {
  if (!value) return '—'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime())
    ? value
    : new Intl.DateTimeFormat('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      }).format(parsed)
}

/**
 * Users are referred to by a masked identifier. The back office works from the id it was given and
 * never renders a full account identifier it does not have.
 */
export function maskUserId(userId: string | undefined | null): string {
  if (!userId) return '—'
  return userId.length <= 4 ? `••${userId.slice(-2)}` : `••${userId.slice(-4)}`
}

/** Error data shared between user and operator views; visibility is decided by each view. */
export interface ApiErrorView {
  message: string
  traceId?: string
  code?: string
}

/** Whole yuan are the priced unit, so a yuan amount is converted to cents in one place. */
export function yuanToCents(yuan: number): number | undefined {
  if (!Number.isSafeInteger(yuan)) return undefined
  const cents = yuan * 100
  return Number.isSafeInteger(cents) ? cents : undefined
}

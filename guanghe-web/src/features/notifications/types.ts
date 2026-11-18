// Presentation models, not business API DTOs. Page containers will map Java
// Browser OpenAPI contracts when available. No raw HTML or external link URLs.
export const notificationCategories = { TASK: '任务通知', POINTS: '积分通知', SUBMISSION: '投稿通知', INVOICE: '发票通知', SYSTEM: '系统通知' } as const
export interface NotificationView {
  id: string
  category: keyof typeof notificationCategories
  kind: 'IMAGE' | 'GEO' | 'VIDEO' | 'ARTICLE' | 'POINTS' | 'INVOICE' | 'SYSTEM'
  title: string; summary: string; body: string; createdAt: string; unread: boolean
  status?: string
  product?: { name: string; id: string; priceCents?: number }
  execution?: { startedAt: string; durationLabel: string }
  platforms?: readonly { id: string; name: string; status: string; outcome: 'SUCCESS' | 'FAILED' | 'PENDING' }[]
  settlement?: { title: string; summary: string; pointsDelta?: number }
  related?: { type: 'TASK' | 'PRODUCT' | 'ASSET' | 'ARTICLE' | 'BILLING'; id: string; label: string }
}
export function notificationPoints(value: number | undefined) {
  return value !== undefined && Number.isSafeInteger(value) ? `${value > 0 ? '+' : ''}${value.toLocaleString('en-US')} 积分` : '—'
}
export function notificationPrice(value: number | undefined) {
  if (value === undefined || !Number.isSafeInteger(value) || value < 0) return '—'
  const cents = BigInt(value)
  return `¥${cents / 100n}.${String(cents % 100n).padStart(2, '0')}`
}

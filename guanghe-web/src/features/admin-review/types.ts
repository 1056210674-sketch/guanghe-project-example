// Presentation-only, redacted view models. Not browser API contracts.
// Future page containers must map server DTOs; never pass raw provider payloads.
export interface ReviewEventView { id: string; actor: string; time: string; summary: string }
export type FeedbackStatus = 'NEW' | 'PROCESSING' | 'RESOLVED'
export const feedbackStatuses: Record<FeedbackStatus, string> = { NEW: '新增反馈', PROCESSING: '处理中', RESOLVED: '已解决' }
export interface FeedbackView {
  id: string; account: string; business: string; description: string; taskId: string; createdAt: string
  status: FeedbackStatus; assignee?: string; problemType: string; runId?: string; taskStatus?: string
  provider?: string; profileHash?: string; attachments: readonly { id: string; name: string }[]; events: readonly ReviewEventView[]
}
export interface FeedbackAggregateView { id: string; business: string; problemType: string; count: number; resolved: number }

export function displayCount(value: number | undefined) {
  return value !== undefined && Number.isSafeInteger(value) && value >= 0 ? value.toLocaleString('en-US') : '—'
}
export function displayPercent(value: number | undefined) {
  return value !== undefined && Number.isFinite(value) && value >= 0 && value <= 100 ? `${value}%` : '—'
}
const eventsFingerprint = (events: readonly ReviewEventView[]) => events.map(event => [event.id, event.actor, event.time, event.summary])
export function feedbackFingerprint(item: FeedbackView) {
  return JSON.stringify([item.id, item.account, item.business, item.description, item.taskId, item.createdAt,
    item.status, item.assignee ?? null, item.problemType, item.runId ?? null, item.taskStatus ?? null, item.provider ?? null,
    item.profileHash ?? null, item.attachments.map(file => [file.id, file.name]), eventsFingerprint(item.events)])
}

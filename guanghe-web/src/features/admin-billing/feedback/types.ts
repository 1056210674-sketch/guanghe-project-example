import type {
  AdminFeedbackListQuery,
  HandleFeedbackRequest,
} from '../../../api/adminBillingApi'
// `adminBillingApi` aliases these two from `billingApi` but does not re-export them, so the alias is
// taken from the module that declares it. Both point at the same generated schema.
import type { FeedbackDto } from '../../../api/billingApi'

/**
 * View models for the 用户反馈 console.
 *
 * Every field is read off the generated contract (`Feedback`, `HandleFeedbackRequest`), so a change
 * on the Java side surfaces here as a type error instead of as a silently wrong column.
 */

export type FeedbackStatus = FeedbackDto['status']
export type FeedbackCategory = FeedbackDto['category']
export type FeedbackHandleStatus = HandleFeedbackRequest['status']
export type FeedbackStatusFilter = FeedbackStatus | 'ALL'
export type FeedbackListQuery = AdminFeedbackListQuery

export interface AdminFeedbackFiltersView {
  /** The server matches the author id exactly, so this is passed through unchanged (trimmed). */
  userId?: string
  status?: FeedbackStatusFilter
}

export interface AdminFeedbackRowView {
  id: string
  category: FeedbackCategory
  content: string
  contact?: string
  status: FeedbackStatus
  reply?: string
  handledAt?: string
  createdAt: string
  updatedAt: string
  /**
   * The published list row carries no author field, so this is absent unless the deployment sends
   * one. The console masks it when present and shows a placeholder when not — it never invents an
   * author it was not given.
   */
  authorId?: string
}

export interface AdminFeedbackHandleDraft {
  feedbackId: string
  status: FeedbackHandleStatus
  reply: string
}

/** Built from the DTO the server returned, never from an optimistic guess. */
export interface AdminFeedbackHandleResultView {
  feedbackId: string
  status: FeedbackStatus
}

export const FEEDBACK_STATUS_META: Record<FeedbackStatus, { label: string; color: string }> = {
  SUBMITTED: { label: '待处理', color: 'processing' },
  ACKNOWLEDGED: { label: '已受理', color: 'warning' },
  RESOLVED: { label: '已解决', color: 'success' },
  CLOSED: { label: '已关闭', color: 'default' },
}

export const FEEDBACK_STATUS_FILTER_OPTIONS: readonly FeedbackStatus[] = [
  'SUBMITTED',
  'ACKNOWLEDGED',
  'RESOLVED',
  'CLOSED',
]

export const FEEDBACK_CATEGORY_LABELS: Record<FeedbackCategory, string> = {
  PRODUCT: '产品建议',
  BILLING: '计费问题',
  BUG: '功能异常',
  CONTENT: '内容问题',
  OTHER: '其他',
}

/**
 * Status only moves forward, and the server enforces the same order. The console offers exactly the
 * transitions the contract accepts: never a step back, never a status the current one has passed.
 */
const FORWARD_STATUSES: Record<FeedbackStatus, readonly FeedbackHandleStatus[]> = {
  SUBMITTED: ['ACKNOWLEDGED', 'RESOLVED', 'CLOSED'],
  ACKNOWLEDGED: ['RESOLVED', 'CLOSED'],
  RESOLVED: ['CLOSED'],
  CLOSED: [],
}

export function feedbackForwardStatuses(status: FeedbackStatus): readonly FeedbackHandleStatus[] {
  return FORWARD_STATUSES[status]
}

export function feedbackStatusIsTerminal(status: FeedbackStatus): boolean {
  return FORWARD_STATUSES[status].length === 0
}

/** Spells out the options that are deliberately missing, so an omitted choice is explained. */
export function feedbackTransitionNote(status: FeedbackStatus): string {
  switch (status) {
    case 'SUBMITTED':
      return '待处理只能向前推进：可受理为「已受理」，也可直接「已解决」或「已关闭」。'
    case 'ACKNOWLEDGED':
      return '已受理只能向前推进为「已解决」或「已关闭」，不能再退回「待处理」。'
    case 'RESOLVED':
      return '已解决只能推进为「已关闭」，不能再退回「已受理」或「待处理」。'
    case 'CLOSED':
      return '「已关闭」是终态：不能改回其他状态，也不能补写回复。'
    default:
      return '状态只能向前推进，不能回退。'
  }
}

function optionalText(value: string | null | undefined): string | undefined {
  return typeof value === 'string' && value.trim().length > 0 ? value : undefined
}

export function toAdminFeedbackRow(dto: FeedbackDto): AdminFeedbackRowView {
  return {
    id: dto.id,
    category: dto.category,
    content: dto.content,
    contact: optionalText(dto.contact),
    status: dto.status,
    reply: optionalText(dto.reply),
    handledAt: optionalText(dto.handledAt),
    createdAt: dto.createdAt,
    updatedAt: dto.updatedAt,
    authorId: optionalText((dto as { userId?: string | null }).userId),
  }
}

export function toAdminFeedbackQuery(
  filters: AdminFeedbackFiltersView,
  page: number,
  size: number,
): FeedbackListQuery {
  const userId = filters.userId?.trim()
  return {
    userId: userId ? userId : undefined,
    status: filters.status && filters.status !== 'ALL' ? filters.status : undefined,
    page,
    size,
  }
}

import { isApiError } from '../../../api/apiError'
import type {
  NotificationCapabilityDto,
  NotificationDto,
  UnreadNotificationCountDto,
} from '../../../api/billingApi'
import { parseReturnContext } from '../../billing/types'
import { APP_PATHS, appLinks } from '../../../routes/paths'

/**
 * DTO -> view mapping for the user notification centre.
 *
 * The views carry only what the server actually said: a category, a timestamp, a read state and the
 * opaque `targetType`/`targetId` pair. Nothing here invents a link, an email or a delivery claim —
 * the generated contract has no such fields, and the client must not pretend otherwise.
 */

/** zh-CN rendering of a server timestamp; an unparseable value is shown verbatim, never guessed. */
export function formatDateTime(value: string): string {
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

export type NotificationCategory = NotificationDto['category']

/** One label per category the contract defines. */
export const NOTIFICATION_CATEGORY_LABELS: Record<NotificationCategory, string> = {
  RECHARGE: '充值通知',
  POINTS: '积分通知',
  INVOICE: '发票通知',
  FEEDBACK: '反馈通知',
  SYSTEM: '系统通知',
}

export type NotificationTabKey = 'ALL' | NotificationCategory

export interface NotificationTab {
  key: NotificationTabKey
  label: string
}

/** 全部 plus one server-side filter per real category. */
export const notificationTabs: readonly NotificationTab[] = [
  { key: 'ALL', label: '全部' },
  { key: 'RECHARGE', label: NOTIFICATION_CATEGORY_LABELS.RECHARGE },
  { key: 'POINTS', label: NOTIFICATION_CATEGORY_LABELS.POINTS },
  { key: 'INVOICE', label: NOTIFICATION_CATEGORY_LABELS.INVOICE },
  { key: 'FEEDBACK', label: NOTIFICATION_CATEGORY_LABELS.FEEDBACK },
  { key: 'SYSTEM', label: NOTIFICATION_CATEGORY_LABELS.SYSTEM },
]

export interface NotificationListItemView {
  id: string
  title: string
  category: NotificationCategory
  categoryLabel: string
  createdAt: string
  read: boolean
  readLabel: '已读' | '未读'
}

export interface NotificationDetailView extends NotificationListItemView {
  body: string
  readAt?: string
  targetType?: string
  targetId?: string
  targetLink?: NotificationTargetLink
}

export interface NotificationTargetLink {
  path: string
  label: string
}

/**
 * Resolve only resource kinds owned by this Web application.
 *
 * `targetType` and `targetId` are opaque server data, never URLs.  The path prefix is therefore
 * selected from this local allowlist and the id is always encoded before it enters the route.  An
 * unknown kind deliberately produces no link instead of guessing a destination.
 */
export function notificationTargetLink(
  targetType: string | null | undefined,
  targetId: string | null | undefined,
): NotificationTargetLink | undefined {
  const id = targetId?.trim()
  if (!targetType || !id || id.length > 128) return undefined

  // Recharge notifications carry the caller's verified return context (for example
  // TASK_DRAFT:123), not the recharge order itself. Reuse the billing parser so this
  // notification path cannot drift from the path used by the recharge console.
  const returnContext = parseReturnContext(`${targetType}:${id}`)
  if (returnContext) {
    return { path: returnContext.path, label: returnContext.pathLabel }
  }

  switch (targetType) {
    case 'TASK':
      return { path: appLinks.taskDetail(id), label: '查看任务' }
    case 'WORKFLOW_PLAN':
      return { path: appLinks.workflowPlanDetail(id), label: '查看组合计划' }
    case 'ARTICLE':
      return { path: appLinks.articleDetail(id), label: '查看文章' }
    case 'ASSET':
      return { path: appLinks.assetDetail(id), label: '查看资产' }
    case 'GEO_REPORT':
      return { path: appLinks.geoReportDetail(id), label: '查看 GEO 报告' }
    case 'RECHARGE_ORDER':
      return {
        path: `${APP_PATHS.pointsBilling}?tab=orders&targetId=${encodeURIComponent(id)}`,
        label: '查看充值记录',
      }
    case 'POINT_LEDGER':
      return {
        path: `${APP_PATHS.pointsBilling}?tab=ledger&targetId=${encodeURIComponent(id)}`,
        label: '查看积分明细',
      }
    case 'INVOICE_APPLICATION':
      return {
        path: `${APP_PATHS.pointsBilling}?tab=invoices&targetId=${encodeURIComponent(id)}`,
        label: '查看发票申请',
      }
    case 'USER_FEEDBACK':
      return {
        path: `${APP_PATHS.pointsBilling}?tab=feedback&targetId=${encodeURIComponent(id)}`,
        label: '查看反馈',
      }
    default:
      return undefined
  }
}

export interface NotificationViewError {
  message: string
  traceId?: string
}

function titleOf(notification: NotificationDto): string {
  return notification.title.trim() ? notification.title : '未命名通知'
}

export function toNotificationListItem(notification: NotificationDto): NotificationListItemView {
  return {
    id: notification.id,
    title: titleOf(notification),
    category: notification.category,
    categoryLabel: NOTIFICATION_CATEGORY_LABELS[notification.category] ?? notification.category,
    createdAt: formatDateTime(notification.createdAt),
    read: notification.read,
    readLabel: notification.read ? '已读' : '未读',
  }
}

export function toNotificationDetail(notification: NotificationDto): NotificationDetailView {
  return {
    ...toNotificationListItem(notification),
    body: notification.body,
    readAt: notification.readAt ? formatDateTime(notification.readAt) : undefined,
    targetType: notification.targetType ?? undefined,
    targetId: notification.targetId ?? undefined,
    targetLink: notificationTargetLink(notification.targetType, notification.targetId),
  }
}

export type NotificationInAppStatus = NotificationCapabilityDto['inAppStatus']
export type NotificationMailStatus = NotificationCapabilityDto['mailStatus']

export const NOTIFICATION_IN_APP_STATUS_LABELS: Record<NotificationInAppStatus, string> = {
  NOTIFICATION_IN_APP_AVAILABLE: '应用内通知可用',
  NOTIFICATION_MODULE_DISABLED: '应用内通知已关闭',
}

export const NOTIFICATION_MAIL_STATUS_LABELS: Record<NotificationMailStatus, string> = {
  MAIL_DELIVERY_READY: '邮件通知已配置',
  MAIL_DELIVERY_NOT_CONFIGURED: '邮件通知未配置',
  MAIL_DELIVERY_NOT_IMPLEMENTED: '邮件通知尚未实现',
}

export interface NotificationCapabilityView {
  inApp: boolean
  inAppStatus: NotificationInAppStatus
  inAppStatusLabel: string
  mailProvider: string
  mailConfigured: boolean
  mailStatus: NotificationMailStatus
  mailStatusLabel: string
  /** Names of absent prerequisites, as reported by the server. Never values. */
  mailMissing: readonly string[]
}

/** An unknown enum value is rendered as the raw server token instead of being labelled away. */
function labelOf<TKey extends string>(labels: Record<TKey, string>, key: TKey): string {
  return labels[key] ?? key
}

export function toNotificationCapabilityView(
  capability: NotificationCapabilityDto,
): NotificationCapabilityView {
  return {
    inApp: capability.inApp,
    inAppStatus: capability.inAppStatus,
    inAppStatusLabel: labelOf(
      NOTIFICATION_IN_APP_STATUS_LABELS,
      capability.inAppStatus,
    ),
    mailProvider: capability.mailProvider,
    mailConfigured: capability.mailConfigured,
    mailStatus: capability.mailStatus,
    mailStatusLabel: labelOf(NOTIFICATION_MAIL_STATUS_LABELS, capability.mailStatus),
    mailMissing: [...capability.mailMissing],
  }
}

/** The count is the server's global number; a malformed value is reported as unknown, not as zero. */
export function toUnreadCount(count: UnreadNotificationCountDto): number | undefined {
  return Number.isSafeInteger(count.unread) && count.unread >= 0 ? count.unread : undefined
}

export function toNotificationViewError(error: unknown): NotificationViewError {
  if (isApiError(error)) {
    return { message: error.message, traceId: error.traceId }
  }
  return { message: '通知请求失败，请稍后重试。' }
}

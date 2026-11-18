import type { InvoiceApplicationDto } from '../../../api/billingApi'

/**
 * Presentation model for the invoice console.
 *
 * The honesty rule this file exists to protect: `APPROVED` means internal review passed. It is not
 * an issue date or mail confirmation. `DELIVERED` means only that an administrator recorded
 * external manual sending; it is not a recipient receipt.
 */

export type AdminInvoiceApplicationStatus = InvoiceApplicationDto['status']
export type AdminInvoiceType = InvoiceApplicationDto['invoiceType']
export type AdminInvoiceTitleType = InvoiceApplicationDto['titleType']

export interface AdminInvoiceApplicationView {
  id: InvoiceApplicationDto['id']
  applicationNo: InvoiceApplicationDto['applicationNo']
  status: AdminInvoiceApplicationStatus
  invoiceType: AdminInvoiceType
  titleType: AdminInvoiceTitleType
  title: InvoiceApplicationDto['title']
  taxNumber: InvoiceApplicationDto['taxNumber']
  amountCents: InvoiceApplicationDto['amountCents']
  deliveryEmail: InvoiceApplicationDto['deliveryEmail']
  remark: InvoiceApplicationDto['remark']
  rejectReason: InvoiceApplicationDto['rejectReason']
  deliveryProvider: InvoiceApplicationDto['deliveryProvider']
  invoiceNumber: InvoiceApplicationDto['invoiceNumber']
  manualSentBy: InvoiceApplicationDto['manualSentBy']
  webhookStatus: InvoiceApplicationDto['webhookStatus']
  webhookErrorCode: InvoiceApplicationDto['webhookErrorCode']
  submittedAt: InvoiceApplicationDto['submittedAt']
  reviewedAt: InvoiceApplicationDto['reviewedAt']
  approvedAt: InvoiceApplicationDto['approvedAt']
  deliveredAt: InvoiceApplicationDto['deliveredAt']
  rejectedAt: InvoiceApplicationDto['rejectedAt']
  cancelledAt: InvoiceApplicationDto['cancelledAt']
  createdAt: InvoiceApplicationDto['createdAt']
  updatedAt: InvoiceApplicationDto['updatedAt']
  /**
   * The account this row is scoped to.
   *
   * `InvoiceApplication` carries no user id, so the only truthful source is the `userId` the page
   * was fetched with; it is `null` when the page was not filtered by user and the column renders a
   * placeholder instead of guessing.
   */
  scopedUserId: string | null
}

export interface AdminInvoiceStatusMeta {
  /** Honest gloss. It is shown next to the raw status code, never instead of it. */
  label: string
  color: string
}

/**
 * `Record<AdminInvoiceApplicationStatus, …>` cannot be written without every contract member, so
 * this object is the single source of truth for the filter options as well.
 */
export const INVOICE_APPLICATION_STATUS_META: Record<
  AdminInvoiceApplicationStatus,
  AdminInvoiceStatusMeta
> = {
  SUBMITTED: { label: '已提交，等待受理', color: 'processing' },
  UNDER_REVIEW: { label: '已受理，等待审核', color: 'processing' },
  APPROVED: {
    label: '内部审核通过（尚未开票、尚未投递）',
    color: 'warning',
  },
  DELIVERED: { label: '完成记录（须核实发件渠道；非客户收件回执）', color: 'success' },
  REJECTED: { label: '已驳回', color: 'error' },
  CANCELLED: { label: '已取消（申请人撤销）', color: 'default' },
}

export const INVOICE_APPLICATION_STATUSES = Object.keys(
  INVOICE_APPLICATION_STATUS_META,
) as readonly AdminInvoiceApplicationStatus[]

export const INVOICE_TYPE_LABEL: Record<AdminInvoiceType, string> = {
  VAT_NORMAL: '增值税普通发票',
  VAT_SPECIAL: '增值税专用发票',
}

export const INVOICE_TITLE_TYPE_LABEL: Record<AdminInvoiceTitleType, string> = {
  PERSONAL: '个人',
  COMPANY: '单位',
}

/** The single most important sentence on the page. */
export const APPROVED_IS_NOT_ISSUED_NOTICE =
  'APPROVED 只表示内部审核通过，不代表发票已开具或寄出。DELIVERED 仅表示管理员确认已线下开票并人工发送至申请邮箱，不代表申请人实际收件。'

export const DELIVERY_CAPABILITY_NOTICE =
  '请在线下实际开票并人工发送邮件后，填写发票号并确认；系统不会替您发送发票邮件。飞书通知仅传递申请信息，不代表发票已寄出。'

export const REJECT_REASON_NOTICE =
  '驳回原因为必填，会原样展示给申请人，因此不能为空。'

// ---------------------------------------------------------------- state machine

export type AdminInvoiceAction = 'ACCEPT' | 'APPROVE' | 'REJECT' | 'DELIVER'

export const INVOICE_ACTIONS: readonly AdminInvoiceAction[] = [
  'ACCEPT',
  'APPROVE',
  'REJECT',
  'DELIVER',
]

export const INVOICE_ACTION_LABEL: Record<AdminInvoiceAction, string> = {
  ACCEPT: '受理',
  APPROVE: '审核通过',
  REJECT: '驳回',
  DELIVER: '确认已人工发件',
}

/**
 * The states each transition is legal in, straight from the contract's state machine. A control for
 * an action is only offered when the current status is in this list.
 */
export const INVOICE_ACTION_LEGAL_STATUSES: Record<
  AdminInvoiceAction,
  readonly AdminInvoiceApplicationStatus[]
> = {
  ACCEPT: ['SUBMITTED'],
  APPROVE: ['UNDER_REVIEW'],
  REJECT: ['SUBMITTED', 'UNDER_REVIEW'],
  DELIVER: ['APPROVED'],
}

export const INVOICE_ACTION_REQUIREMENT: Record<AdminInvoiceAction, string> = {
  ACCEPT: '只有 SUBMITTED（已提交）的申请可以受理',
  APPROVE: '只有 UNDER_REVIEW（已受理）的申请可以审核通过',
  REJECT: '只有 SUBMITTED（已提交）或 UNDER_REVIEW（已受理）的申请可以驳回',
  DELIVER: '只有 APPROVED 的申请在实际开票并人工发件后才可确认',
}

export function isInvoiceActionLegal(
  action: AdminInvoiceAction,
  status: AdminInvoiceApplicationStatus,
): boolean {
  return INVOICE_ACTION_LEGAL_STATUSES[action].includes(status)
}

/** Why a control is not offered. Stated on the page rather than left to the operator to guess. */
export function invoiceActionUnavailableReason(
  action: AdminInvoiceAction,
  status: AdminInvoiceApplicationStatus,
): string {
  return `${INVOICE_ACTION_REQUIREMENT[action]}；当前状态为 ${status}，因此不提供「${INVOICE_ACTION_LABEL[action]}」。`
}

// ---------------------------------------------------------------- refusal

export interface AdminDeliveryRefusalView {
  /** The server's message, verbatim. */
  message: string
  traceId?: string
  code?: string
  /**
   * True when the refusal is the deployment's missing delivery capability rather than an applicant
   * or data problem. The refusal is then presented as a capability gap.
   */
  capability: boolean
}

/**
 * The server refuses a delivery it cannot honestly perform. A 503, or a code naming the missing
 * capability, is that refusal; anything else is reported as an ordinary failed request. Neither
 * branch is ever rendered as a success.
 */
export function isDeliveryCapabilityRefusal(error: { status?: number; code?: string }): boolean {
  if (error.status === 503) return true
  return (
    typeof error.code === 'string' &&
    /NOT_CONFIGURED|NOT_IMPLEMENTED|UNAVAILABLE/.test(error.code)
  )
}

export interface AdminInvoiceActionOutcome {
  action: AdminInvoiceAction
  /** The application exactly as the server returned it. Never a locally-assumed state. */
  application: AdminInvoiceApplicationView
}

export function toAdminInvoiceApplicationView(
  application: InvoiceApplicationDto,
  scopedUserId: string | null,
): AdminInvoiceApplicationView {
  return {
    id: application.id,
    applicationNo: application.applicationNo,
    status: application.status,
    invoiceType: application.invoiceType,
    titleType: application.titleType,
    title: application.title,
    taxNumber: application.taxNumber,
    amountCents: application.amountCents,
    deliveryEmail: application.deliveryEmail,
    remark: application.remark,
    rejectReason: application.rejectReason,
    deliveryProvider: application.deliveryProvider,
    invoiceNumber: application.invoiceNumber,
    manualSentBy: application.manualSentBy,
    webhookStatus: application.webhookStatus,
    webhookErrorCode: application.webhookErrorCode,
    submittedAt: application.submittedAt,
    reviewedAt: application.reviewedAt,
    approvedAt: application.approvedAt,
    deliveredAt: application.deliveredAt,
    rejectedAt: application.rejectedAt,
    cancelledAt: application.cancelledAt,
    createdAt: application.createdAt,
    updatedAt: application.updatedAt,
    scopedUserId,
  }
}

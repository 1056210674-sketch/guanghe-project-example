import type {
  FeedbackDto,
  InvoiceApplicationDto,
  InvoiceCapabilityDto,
  RateCardDto,
  RechargeCapabilityDto,
  RechargeOrderDto,
  RechargeTierDto,
} from '../../api/billingApi'
import { APP_PATHS, appLinks } from '../../routes/paths'
import { formatCents, formatDateTime, formatPoints } from '../../shared/format'

/**
 * View models for the user-facing commercial surfaces.
 *
 * Every capability answer is carried through as two things at once: the server's own status token,
 * printed verbatim, and a Chinese label. Showing the token is the difference between "the payment
 * channel is off and here is exactly what the server called it" and a button that quietly does
 * nothing. Nothing here invents a payment result, an invoice issue date or a QR code: every value
 * either came from a response or is a dash.
 */

export interface CapabilityView {
  /** Whether the server says the action can be attempted at all right now. */
  available: boolean
  statusToken: string
  statusLabel: string
  detail: string
  missingRequirements: string[]
}

export type RechargeCapabilityView = CapabilityView & {
  provider: string
  callbackVerifiable: boolean
  minAmountCents: number
  maxAmountCents: number
  pointsPerYuan: number
  ttlSeconds: number
  currency: string
}

const RECHARGE_STATUS_LABELS: Record<RechargeCapabilityDto['status'], string> = {
  PAYMENT_PROVIDER_READY: '支付通道已就绪',
  PAYMENT_PROVIDER_NOT_CONFIGURED: '暂不可充值：支付通道未配置',
  PAYMENT_PROVIDER_NOT_IMPLEMENTED: '暂不可充值：支付通道未接入',
}

export function toRechargeCapabilityView(dto: RechargeCapabilityDto): RechargeCapabilityView {
  return {
    available: dto.payable,
    statusToken: dto.status,
    statusLabel: RECHARGE_STATUS_LABELS[dto.status] ?? dto.status,
    detail: dto.payable
      ? '支付通道已就绪，可创建充值订单。'
      : '当前环境未启用真实微信支付，无法创建可支付的充值订单；此状态下不会生成支付二维码，也不会产生任何支付结果。',
    missingRequirements: dto.missingRequirements,
    provider: dto.provider,
    callbackVerifiable: dto.callbackVerifiable,
    minAmountCents: dto.minRechargeAmountCents,
    maxAmountCents: dto.maxRechargeAmountCents,
    pointsPerYuan: dto.pointsPerYuan,
    ttlSeconds: dto.rechargeOrderTtlSeconds,
    currency: dto.currency,
  }
}

/**
 * The active rate card.
 *
 * `productionRate: false` means the deployment priced this card from a placeholder rate, so the card
 * is labelled as such and the missing `versionId` is never papered over with a version number the
 * server did not send.
 */
export interface RateCardView {
  productionRate: boolean
  versionNo?: number
  pointsPerYuan: number
  minAmountCents: number
  maxAmountCents: number
  note?: string
  publishedAt?: string
  tiers: RechargeTierView[]
  currency: string
}

export interface RechargeTierView {
  tierId: string
  amountCents: number
  basePoints: number
  giftPoints: number
  points: number
  label: string
}

export function toRateCardView(dto: RateCardDto): RateCardView {
  return {
    productionRate: dto.productionRate,
    versionNo: dto.versionNo ?? undefined,
    pointsPerYuan: dto.pointsPerYuan,
    minAmountCents: dto.minRechargeAmountCents,
    maxAmountCents: dto.maxRechargeAmountCents,
    note: dto.note ?? undefined,
    publishedAt: dto.publishedAt ?? undefined,
    tiers: dto.tiers.map(toRechargeTierView),
    currency: dto.currency,
  }
}

function toRechargeTierView(tier: RechargeTierDto): RechargeTierView {
  return {
    tierId: tier.tierId,
    amountCents: tier.amountCents,
    basePoints: tier.basePoints,
    giftPoints: tier.giftPoints,
    points: tier.points,
    label: tier.label,
  }
}

export type RechargeOrderStatus = RechargeOrderDto['status']

export interface RechargeOrderView {
  id: string
  orderNo: string
  status: RechargeOrderStatus
  statusLabel: string
  provider: string
  amountText: string
  amountCents: number
  points: number
  pointsText: string
  providerOrderNo?: string
  codeUrl?: string
  returnContext?: ParsedReturnContext
  expiresAt: string
  expiresAtText: string
  paidAtText: string
  closedAtText: string
  failedAtText: string
  failureCode?: string
  createdAtText: string
  /** Whether the server still accepts a provider query for this order. */
  canQuery: boolean
  /** Whether the server still accepts a close request for this order. */
  canClose: boolean
  /** Whether a payment code exists. It only ever exists if a real provider returned one. */
  hasPaymentCode: boolean
}

const RECHARGE_ORDER_STATUS_META: Record<
  RechargeOrderStatus,
  { label: string; color: string; terminal: boolean }
> = {
  CREATED: { label: '待支付', color: 'processing', terminal: false },
  PAYING: { label: '支付中', color: 'processing', terminal: false },
  SUCCEEDED: { label: '已到账', color: 'success', terminal: true },
  CLOSED: { label: '已关闭', color: 'default', terminal: true },
  // 已过期 and 已关闭 are separate outcomes: one ran out of time, one was closed on request.
  EXPIRED: { label: '已过期', color: 'warning', terminal: true },
  FAILED: { label: '支付失败', color: 'error', terminal: true },
}

/** Only the two non-terminal states are actionable, mirroring the server's own active set. */
const ACTIVE_ORDER_STATUSES: readonly RechargeOrderStatus[] = ['CREATED', 'PAYING']

export function toRechargeOrderView(dto: RechargeOrderDto): RechargeOrderView {
  const meta = RECHARGE_ORDER_STATUS_META[dto.status]
  const active = ACTIVE_ORDER_STATUSES.includes(dto.status)
  return {
    id: dto.id,
    orderNo: dto.orderNo,
    status: dto.status,
    statusLabel: meta.label,
    provider: dto.provider,
    amountText: formatCents(dto.amountCents),
    amountCents: dto.amountCents,
    points: dto.points,
    pointsText: formatPoints(dto.points),
    providerOrderNo: dto.providerOrderNo ?? undefined,
    codeUrl: dto.codeUrl ?? undefined,
    returnContext: parseReturnContext(dto.returnContext),
    expiresAt: dto.expiresAt,
    expiresAtText: formatDateTime(dto.expiresAt),
    paidAtText: formatDateTime(dto.paidAt),
    closedAtText: formatDateTime(dto.closedAt),
    failedAtText: formatDateTime(dto.failedAt),
    failureCode: dto.failureCode ?? undefined,
    createdAtText: formatDateTime(dto.createdAt),
    canQuery: active,
    canClose: active,
    hasPaymentCode: Boolean(dto.codeUrl),
  }
}

export function rechargeOrderIsTerminal(status: RechargeOrderStatus): boolean {
  return RECHARGE_ORDER_STATUS_META[status].terminal
}

export type InvoiceStatus = InvoiceApplicationDto['status']

export interface InvoiceCapabilityView extends CapabilityView {
  deliveryProvider: string
  deliveryConfigured: boolean
  deliverable: boolean
  deliveryStatusToken: string
  deliveryStatusLabel: string
  deliveryMissing: string[]
  webhookConfigured: boolean
  webhookStatusLabel: string
  currency: string
}

const INVOICE_APPLICATION_STATUS_LABELS: Record<InvoiceCapabilityDto['applicationsStatus'], string> =
  {
    INVOICE_APPLICATIONS_OPEN: '发票申请已开放',
    INVOICE_MODULE_DISABLED: '发票申请已关闭',
  }

const INVOICE_DELIVERY_STATUS_LABELS: Record<InvoiceCapabilityDto['deliveryStatus'], string> = {
  INVOICE_MANUAL_EMAIL: '管理员线下开票并人工邮件发送',
}

export function toInvoiceCapabilityView(dto: InvoiceCapabilityDto): InvoiceCapabilityView {
  return {
    available: dto.applicationsOpen,
    statusToken: dto.applicationsStatus,
    statusLabel:
      INVOICE_APPLICATION_STATUS_LABELS[dto.applicationsStatus] ?? dto.applicationsStatus,
    detail: dto.applicationsOpen
      ? '可提交发票申请，审核结果以服务端状态为准。'
      : '当前环境未开放发票申请，提交入口不可用。',
    missingRequirements: [],
    deliveryProvider: dto.deliveryProvider,
    deliveryConfigured: dto.deliveryConfigured,
    deliverable: dto.deliverable,
    deliveryStatusToken: dto.deliveryStatus,
    deliveryStatusLabel: INVOICE_DELIVERY_STATUS_LABELS[dto.deliveryStatus] ?? dto.deliveryStatus,
    deliveryMissing: dto.deliveryMissing,
    webhookConfigured: dto.webhookConfigured,
    webhookStatusLabel: dto.webhookConfigured ? '飞书通知通道已配置' : '飞书通知通道未配置',
    currency: dto.currency,
  }
}

export interface InvoiceApplicationView {
  id: string
  applicationNo: string
  status: InvoiceStatus
  statusLabel: string
  statusHint: string
  invoiceTypeLabel: string
  titleTypeLabel: string
  title: string
  taxNumber?: string
  amountText: string
  amountCents: number
  deliveryEmail: string
  remark?: string
  rejectReason?: string
  deliveryProvider?: string
  invoiceNumber?: string
  webhookStatus: InvoiceApplicationDto['webhookStatus']
  webhookStatusLabel: string
  submittedAtText: string
  reviewedAtText: string
  approvedAtText: string
  deliveredAtText: string
  rejectedAtText: string
  cancelledAtText: string
  /** Only SUBMITTED and UNDER_REVIEW can still be withdrawn, matching the server's rule. */
  canCancel: boolean
}

const INVOICE_STATUS_META: Record<
  InvoiceStatus,
  { label: string; hint: string; cancelable: boolean }
> = {
  SUBMITTED: { label: '已提交', hint: '等待运营审核，此时可以撤销申请。', cancelable: true },
  UNDER_REVIEW: { label: '审核中', hint: '运营正在审核，此时可以撤销申请。', cancelable: true },
  APPROVED: {
    label: '审核通过（未开具）',
    hint: '审核已通过，但还没有开具或送达；此状态不代表发票已经开出。',
    cancelable: false,
  },
  DELIVERED: { label: '发件记录已完成', hint: '请核实具体发送记录；系统状态不代表邮件已被收取。', cancelable: false },
  REJECTED: { label: '已驳回', hint: '申请被驳回，可参考驳回原因后重新提交。', cancelable: false },
  CANCELLED: { label: '已撤销', hint: '申请已由申请人撤销。', cancelable: false },
}

export function toInvoiceApplicationView(dto: InvoiceApplicationDto): InvoiceApplicationView {
  const meta = INVOICE_STATUS_META[dto.status]
  return {
    id: dto.id,
    applicationNo: dto.applicationNo,
    status: dto.status,
    statusLabel: dto.status === 'DELIVERED'
      ? dto.deliveryProvider === 'MANUAL_EMAIL' ? '管理员已确认发出' : '历史完成记录（请核实）'
      : meta.label,
    statusHint: dto.status === 'DELIVERED'
      ? dto.deliveryProvider === 'MANUAL_EMAIL'
        ? '管理员已记录发票人工发送至申请邮箱；不代表邮件已被收取。'
        : '这是历史完成记录，发送方式需核实；不代表客户实际收件。'
      : meta.hint,
    invoiceTypeLabel: dto.invoiceType === 'VAT_SPECIAL' ? '增值税专用发票' : '增值税普通发票',
    titleTypeLabel: dto.titleType === 'COMPANY' ? '单位' : '个人',
    title: dto.title,
    taxNumber: dto.taxNumber ?? undefined,
    amountText: formatCents(dto.amountCents),
    amountCents: dto.amountCents,
    deliveryEmail: dto.deliveryEmail,
    remark: dto.remark ?? undefined,
    rejectReason: dto.rejectReason ?? undefined,
    deliveryProvider: dto.deliveryProvider ?? undefined,
    invoiceNumber: dto.invoiceNumber ?? undefined,
    webhookStatus: dto.webhookStatus,
    webhookStatusLabel: invoiceWebhookStatusLabel(dto.webhookStatus),
    submittedAtText: formatDateTime(dto.submittedAt),
    reviewedAtText: formatDateTime(dto.reviewedAt),
    approvedAtText: formatDateTime(dto.approvedAt),
    deliveredAtText: formatDateTime(dto.deliveredAt),
    rejectedAtText: formatDateTime(dto.rejectedAt),
    cancelledAtText: formatDateTime(dto.cancelledAt),
    canCancel: meta.cancelable,
  }
}

export function invoiceWebhookStatusLabel(status: InvoiceApplicationDto['webhookStatus']): string {
  switch (status) {
    case 'PENDING': return '待推送飞书'
    case 'WAITING_CONFIG': return '飞书未配置，待推送'
    case 'WAITING_MANUAL': return '历史申请，待管理员手动补推'
    case 'SENT': return '开票信息已推送飞书（非发票发出）'
    case 'SKIPPED': return '飞书推送已跳过'
  }
}

export type FeedbackStatus = FeedbackDto['status']
export type FeedbackCategory = FeedbackDto['category']

export interface FeedbackView {
  id: string
  categoryLabel: string
  content: string
  contact?: string
  status: FeedbackStatus
  statusLabel: string
  reply?: string
  handledAtText: string
  createdAtText: string
}

export const FEEDBACK_CATEGORY_LABELS: Record<FeedbackCategory, string> = {
  PRODUCT: '产品建议',
  BILLING: '充值与发票',
  BUG: '功能异常',
  CONTENT: '内容问题',
  OTHER: '其他',
}

const FEEDBACK_STATUS_LABELS: Record<FeedbackStatus, string> = {
  SUBMITTED: '已提交',
  ACKNOWLEDGED: '已受理',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
}

export function toFeedbackView(dto: FeedbackDto): FeedbackView {
  return {
    id: dto.id,
    categoryLabel: FEEDBACK_CATEGORY_LABELS[dto.category] ?? dto.category,
    content: dto.content,
    contact: dto.contact ?? undefined,
    status: dto.status,
    statusLabel: FEEDBACK_STATUS_LABELS[dto.status] ?? dto.status,
    reply: dto.reply ?? undefined,
    handledAtText: formatDateTime(dto.handledAt),
    createdAtText: formatDateTime(dto.createdAt),
  }
}

// ---------------------------------------------------------------- return context

/**
 * Where the payer was when the balance ran out, e.g. `WORKFLOW_PLAN:123`.
 *
 * The server accepts exactly these four kinds and stores the value as opaque text; it is not a URL
 * and must never be rendered as one. The client is the side that knows its own routes, so the route
 * is resolved here, and an unrecognised value is dropped rather than turned into a link.
 */
export type ReturnContextKind = 'TASK_DRAFT' | 'WORKFLOW_PLAN' | 'ARTICLE' | 'GEO_PLAN'

const RETURN_CONTEXT_PATTERN = /^(TASK_DRAFT|WORKFLOW_PLAN|ARTICLE|GEO_PLAN):([1-9][0-9]{0,18})$/

const RETURN_CONTEXT_LABELS: Record<ReturnContextKind, string> = {
  TASK_DRAFT: '任务草稿',
  WORKFLOW_PLAN: '组合计划',
  ARTICLE: '文章',
  GEO_PLAN: 'GEO 计划',
}

export interface ParsedReturnContext {
  raw: string
  kind: ReturnContextKind
  kindLabel: string
  targetId: string
  /** Where the payer can pick the work back up. Verified routes only. */
  path: string
  pathLabel: string
}

export function parseReturnContext(
  raw: string | null | undefined,
): ParsedReturnContext | undefined {
  if (!raw) return undefined
  const match = RETURN_CONTEXT_PATTERN.exec(raw)
  if (!match) return undefined
  const kind = match[1] as ReturnContextKind
  const targetId = match[2]
  return {
    raw,
    kind,
    kindLabel: RETURN_CONTEXT_LABELS[kind],
    targetId,
    path: returnContextPath(kind, targetId),
    pathLabel: returnContextPathLabel(kind),
  }
}

function returnContextPath(kind: ReturnContextKind, targetId: string): string {
  if (kind === 'WORKFLOW_PLAN') return appLinks.workflowPlanDetail(targetId)
  if (kind === 'ARTICLE') return appLinks.articleDetail(targetId)
  if (kind === 'GEO_PLAN') return APP_PATHS.geoPlans
  // A task draft is confirmed inside the conversation it was raised in; this app has no standalone
  // draft route, so the conversation surface is the honest destination.
  return APP_PATHS.chat
}

function returnContextPathLabel(kind: ReturnContextKind): string {
  if (kind === 'GEO_PLAN') return '返回 GEO 计划列表'
  if (kind === 'TASK_DRAFT') return '返回对话继续确认草稿'
  return `返回${RETURN_CONTEXT_LABELS[kind]}`
}

import type { RechargeOrderDto } from '../../../api/billingApi'

/**
 * Presentation model for the recharge-order console.
 *
 * Every field type is taken from the generated contract instead of being restated, so a server-side
 * rename fails the build rather than rendering an undefined cell. Nothing here invents a value the
 * server did not send: a missing field stays `null` and is rendered as a placeholder.
 *
 * The status is rendered verbatim. `EXPIRED` (the order ran out of its window) and `CLOSED` (the
 * order was closed) are different states with different money consequences and are never collapsed
 * into a single "已关闭" label.
 */

export type AdminRechargeOrderStatus = RechargeOrderDto['status']

export interface AdminRechargeOrderView {
  id: RechargeOrderDto['id']
  orderNo: RechargeOrderDto['orderNo']
  status: AdminRechargeOrderStatus
  provider: RechargeOrderDto['provider']
  amountCents: RechargeOrderDto['amountCents']
  points: RechargeOrderDto['points']
  providerOrderNo: RechargeOrderDto['providerOrderNo']
  codeUrl: RechargeOrderDto['codeUrl']
  returnContext: RechargeOrderDto['returnContext']
  expiresAt: RechargeOrderDto['expiresAt']
  paidAt: RechargeOrderDto['paidAt']
  closedAt: RechargeOrderDto['closedAt']
  failedAt: RechargeOrderDto['failedAt']
  failureCode: RechargeOrderDto['failureCode']
  createdAt: RechargeOrderDto['createdAt']
  updatedAt: RechargeOrderDto['updatedAt']
  /**
   * The account this row is scoped to, as far as the console can honestly say.
   *
   * The contract's `RechargeOrder` carries no user id, so the only truthful source for this column
   * is the `userId` the page was fetched with: the server then guarantees every returned row
   * belongs to that account. When the page is fetched without a user filter this is `null` and the
   * column renders a placeholder rather than guessing whose order it is.
   */
  scopedUserId: string | null
}

export interface AdminRechargeOrderStatusMeta {
  /** Chinese gloss. Shown next to the raw status, never instead of it. */
  label: string
  color: string
}

/**
 * The status list is the contract's union: `Record<AdminRechargeOrderStatus, …>` cannot be written
 * without every member, so this object is the single source of truth for the filter options too.
 */
export const RECHARGE_ORDER_STATUS_META: Record<
  AdminRechargeOrderStatus,
  AdminRechargeOrderStatusMeta
> = {
  CREATED: { label: '已创建，等待支付', color: 'default' },
  PAYING: { label: '支付中，等待渠道结果', color: 'processing' },
  SUCCEEDED: { label: '支付成功', color: 'success' },
  CLOSED: { label: '已关闭（订单被关闭，未支付）', color: 'default' },
  EXPIRED: { label: '已过期（超过有效期仍未支付）', color: 'warning' },
  FAILED: { label: '支付失败', color: 'error' },
}

export const RECHARGE_ORDER_STATUSES = Object.keys(
  RECHARGE_ORDER_STATUS_META,
) as readonly AdminRechargeOrderStatus[]

/** The provider's payment-code value. Stated plainly so nobody reads it as a payable QR code. */
export const CODE_URL_NOTICE =
  'codeUrl 是支付渠道返回的付款码原文。当前环境不提供收款能力，它不是可支付的二维码：本页只把它作为文本展示，不生成图片、不转成链接、也不提供点击动作。'

/** `returnContext` is constrained by the database to be a `kind:id` marker, not a URL. */
export const RETURN_CONTEXT_NOTICE =
  'returnContext 是「类型:标识」形式的来源标记（例如 TASK_DRAFT:123，数据库约束保证它不是 URL），因此只按文本展示，不提供链接。'

export const POINTS_SNAPSHOT_NOTICE =
  '积分为下单时冻结的快照值，不代表已经入账；实际入账以服务端账务记录为准。'

export const RECONCILE_NOTICE =
  '对账只记录支付渠道当前答复的订单状态。它本身不会改变余额：只有经验证的回调或渠道确认的答复才会入账。'

export interface AdminReconcileOutcome {
  /** The order exactly as the server answered the reconcile call. */
  order: AdminRechargeOrderView
  /** The status the console had read before asking, or `null` when it had not read one. */
  previousStatus: AdminRechargeOrderStatus | null
  /** `null` when there was no earlier status to compare with, so no claim is made either way. */
  statusChanged: boolean | null
}

export function toAdminRechargeOrderView(
  order: RechargeOrderDto,
  scopedUserId: string | null,
): AdminRechargeOrderView {
  return {
    id: order.id,
    orderNo: order.orderNo,
    status: order.status,
    provider: order.provider,
    amountCents: order.amountCents,
    points: order.points,
    providerOrderNo: order.providerOrderNo,
    codeUrl: order.codeUrl,
    returnContext: order.returnContext,
    expiresAt: order.expiresAt,
    paidAt: order.paidAt,
    closedAt: order.closedAt,
    failedAt: order.failedAt,
    failureCode: order.failureCode,
    createdAt: order.createdAt,
    updatedAt: order.updatedAt,
    scopedUserId,
  }
}

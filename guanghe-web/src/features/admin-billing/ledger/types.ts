/**
 * View models and DTO adapters for the 积分账务 console.
 *
 * The console renders whatever the server returned and nothing else: an adapter may only rename,
 * flatten or fill a missing optional with `undefined`. It never invents a number, a status or a
 * timestamp, and it never derives a total from a page — a total comes from the summary endpoint.
 *
 * Every shape on the wire is taken from the generated contract through the aliases in
 * `src/api/adminBillingApi.ts`, so a server-side rename breaks this file at compile time rather
 * than silently dropping a column.
 */
import type {
  AdminPointLedgerEntryDto,
  AdminPointLedgerListQuery,
  AdminPointLedgerPageDto,
  AdminPointLedgerSummaryQuery,
  BookkeepingSummaryDto,
  LedgerSummaryDto,
  PointAdjustmentDto,
  PointAdjustmentPageDto,
} from '../../../api/adminBillingApi'
import { isApiError } from '../../../api/apiError'
import type { AdminErrorView } from '../format'

export type { AdminErrorView } from '../format'

/** An error view every admin surface can render: the message plus the traceId to quote in a ticket. */
export function toAdminErrorView(error: unknown): AdminErrorView | null {
  if (!error) return null
  if (isApiError(error)) {
    return { message: error.message, traceId: error.traceId, code: error.code }
  }
  return { message: '请求失败，请稍后重试。' }
}

// ---------------------------------------------------------------- 积分流水 (point ledger)

/** The filter set shared by the list request and the totals request. Never carries a page. */
export interface AdminLedgerFiltersView {
  userId?: string
  entryType?: string
  businessType?: string
  from?: string
  to?: string
}

export interface AdminPointLedgerEntryView {
  id: string
  userId: string
  entryType: string
  businessType: string
  businessId: string
  availableDelta: number
  frozenDelta: number
  availableAfter: number
  frozenAfter: number
  operatorId?: string
  reason: string
  createdAt: string
}

export interface AdminPointLedgerPageView {
  items: readonly AdminPointLedgerEntryView[]
  page: number
  size: number
  total: number
  hasNext: boolean
}

export interface LedgerSummaryView {
  entries: number
  availableDelta: number
  frozenDelta: number
}

/** One row of a server-side GROUP BY over a status column. The counts are the server's, verbatim. */
export type StatusCountView = BookkeepingSummaryDto['recharge']['ordersByStatus'][number]

export interface BookkeepingView {
  generatedAt: string
  platformAvailablePoints: number
  platformFrozenPoints: number
  ledgerEntryTypes: readonly string[]
  ledgerBusinessTypes: readonly string[]
  recharge: {
    ordersByStatus: readonly StatusCountView[]
    succeededOrders: number
    succeededAmountCents: number
    basePoints: number
    giftPoints: number
  }
  invoice: {
    applicationsByStatus: readonly StatusCountView[]
    deliveredAmountCents: number
  }
  feedback: {
    itemsByStatus: readonly StatusCountView[]
  }
  ledger: LedgerSummaryView
}

export function toAdminPointLedgerEntryView(
  dto: AdminPointLedgerEntryDto,
): AdminPointLedgerEntryView {
  return {
    id: dto.id,
    userId: dto.userId,
    entryType: dto.entryType,
    businessType: dto.businessType,
    businessId: dto.businessId,
    availableDelta: dto.availableDelta,
    frozenDelta: dto.frozenDelta,
    availableAfter: dto.availableAfter,
    frozenAfter: dto.frozenAfter,
    operatorId: dto.operatorId ?? undefined,
    reason: dto.reason,
    createdAt: dto.createdAt,
  }
}

export function toAdminPointLedgerPageView(
  dto: AdminPointLedgerPageDto,
): AdminPointLedgerPageView {
  return {
    items: dto.items.map(toAdminPointLedgerEntryView),
    page: dto.page,
    size: dto.size,
    total: dto.total,
    hasNext: dto.hasNext,
  }
}

export function toLedgerSummaryView(dto: LedgerSummaryDto): LedgerSummaryView {
  return {
    entries: dto.entries,
    availableDelta: dto.availableDelta,
    frozenDelta: dto.frozenDelta,
  }
}

export function toBookkeepingView(dto: BookkeepingSummaryDto): BookkeepingView {
  return {
    generatedAt: dto.generatedAt,
    platformAvailablePoints: dto.platformAvailablePoints,
    platformFrozenPoints: dto.platformFrozenPoints,
    ledgerEntryTypes: [...dto.ledgerEntryTypes],
    ledgerBusinessTypes: [...dto.ledgerBusinessTypes],
    recharge: {
      ordersByStatus: dto.recharge.ordersByStatus,
      succeededOrders: dto.recharge.succeededOrders,
      succeededAmountCents: dto.recharge.succeededAmountCents,
      basePoints: dto.recharge.basePoints,
      giftPoints: dto.recharge.giftPoints,
    },
    invoice: {
      applicationsByStatus: dto.invoice.applicationsByStatus,
      deliveredAmountCents: dto.invoice.deliveredAmountCents,
    },
    feedback: { itemsByStatus: dto.feedback.itemsByStatus },
    ledger: toLedgerSummaryView(dto.ledger),
  }
}

/**
 * The list request for a filter set. `page`/`size` are added here and nowhere else.
 *
 * Empty filter values collapse to `undefined` so that the query key, the request URL and the
 * summary request all agree on what "no filter" means.
 */
export function toAdminPointLedgerListQuery(
  filters: AdminLedgerFiltersView,
  page: number,
  size: number,
): AdminPointLedgerListQuery {
  return { ...toAdminPointLedgerSummaryQuery(filters), page, size }
}

/**
 * The totals request for the same filter set — the summary endpoint takes no page, which is what
 * keeps 合计 independent of which page the operator has open.
 */
export function toAdminPointLedgerSummaryQuery(
  filters: AdminLedgerFiltersView,
): AdminPointLedgerSummaryQuery {
  return {
    userId: filters.userId || undefined,
    entryType: filters.entryType || undefined,
    businessType: filters.businessType || undefined,
    from: filters.from || undefined,
    to: filters.to || undefined,
  }
}

export function adminLedgerFiltersAreEmpty(filters: AdminLedgerFiltersView): boolean {
  return Object.values(toAdminPointLedgerSummaryQuery(filters)).every(
    (value) => value === undefined,
  )
}

// ---------------------------------------------------------------- 人工补录 (manual adjustment)

export type PointAdjustmentStatus = PointAdjustmentDto['status']

/** `direction` is CREDIT only in the contract, so the console never offers a debit. */
export type PointAdjustmentDirection = PointAdjustmentDto['direction']

export type ConfirmationExpiryState = 'VALID' | 'EXPIRED' | 'UNKNOWN'

export interface PointAdjustmentView {
  id: string
  userId: string
  points: number
  direction: PointAdjustmentDirection
  reason: string
  status: PointAdjustmentStatus
  operatorId?: string
  ledgerId?: string
  appliedAt?: string
  createdAt: string
  confirmationExpiresAt: string
  confirmationExpiryState: ConfirmationExpiryState
  /**
   * The one-time confirmation token. Only the response that created the adjustment carries it, and
   * it is rendered in exactly one place: the confirmation dialog. It is never logged, never put in
   * a query key and never written to a URL or storage.
   */
  confirmationToken?: string
}

export interface PointAdjustmentPageView {
  items: readonly PointAdjustmentView[]
  page: number
  size: number
  total: number
  hasNext: boolean
}

/** The draft the operator submits to step one. */
export interface PointAdjustmentDraft {
  userId: string
  points: number
  reason: string
}

/**
 * The contract's confirmation-token pattern. Validated before 确认入账 can be clicked so that an
 * obviously wrong value never costs a round trip.
 */
export const CONFIRMATION_TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/

/** The contract's minimum for a manual adjustment: points are a positive credit. */
export const POINT_ADJUSTMENT_MIN_POINTS = 1

/** The exact wording shown when the one-time token can no longer be used. */
export const CONFIRMATION_EXPIRED_MESSAGE = '确认令牌已过期，请重新发起'

export function isValidConfirmationToken(value: string): boolean {
  return CONFIRMATION_TOKEN_PATTERN.test(value)
}

/**
 * Fail-closed: an unparseable expiry is UNKNOWN, never "still valid". The server would refuse such
 * a confirmation anyway, so the UI must not offer it as a working one.
 */
export function confirmationExpiryState(
  confirmationExpiresAt: string | undefined | null,
  now: number = Date.now(),
): ConfirmationExpiryState {
  if (!confirmationExpiresAt) return 'UNKNOWN'
  const expiresAt = Date.parse(confirmationExpiresAt)
  if (!Number.isFinite(expiresAt)) return 'UNKNOWN'
  return expiresAt <= now ? 'EXPIRED' : 'VALID'
}

export function toPointAdjustmentView(
  dto: PointAdjustmentDto,
  now: number = Date.now(),
): PointAdjustmentView {
  return {
    id: dto.id,
    userId: dto.userId,
    points: dto.points,
    direction: dto.direction,
    reason: dto.reason,
    status: dto.status,
    operatorId: dto.operatorId || undefined,
    ledgerId: dto.ledgerId ?? undefined,
    appliedAt: dto.appliedAt ?? undefined,
    createdAt: dto.createdAt,
    confirmationExpiresAt: dto.confirmationExpiresAt,
    confirmationExpiryState: confirmationExpiryState(dto.confirmationExpiresAt, now),
    confirmationToken: dto.confirmationToken ?? undefined,
  }
}

export function toPointAdjustmentPageView(
  dto: PointAdjustmentPageDto,
  now: number = Date.now(),
): PointAdjustmentPageView {
  return {
    items: dto.items.map((item) => toPointAdjustmentView(item, now)),
    page: dto.page,
    size: dto.size,
    total: dto.total,
    hasNext: dto.hasNext,
  }
}

export const POINT_ADJUSTMENT_STATUS_LABELS: Record<PointAdjustmentStatus, string> = {
  PENDING_CONFIRMATION: '待确认',
  APPLIED: '已入账',
  CANCELLED: '已取消',
}

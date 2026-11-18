import type {
  CreatePricingVersionRequest,
  PricingTierRequest,
  PricingVersionDto,
  PricingVersionPageDto,
  RateLimitRuleDto,
  UpdateRateLimitRequest,
} from '../../../api/adminBillingApi'
import { formatCents } from '../format'

/**
 * View models and adapters for the 定价配置 console.
 *
 * Every shape below is derived from the generated contract through the aliases in
 * `src/api/adminBillingApi.ts`, so a server rename breaks the build instead of producing a blank
 * column. Nothing here invents a value: a field the server did not send stays `null` and the
 * workspace renders a placeholder or the server's own statement about it.
 */

export type PricingVersionStatus = PricingVersionDto['status']
export type PricingTierDto = PricingVersionDto['tiers'][number]
export type RateLimitAction = RateLimitRuleDto['action']

/** Whole-yuan bounds the server enforces. Repeated on screen, never widened here. */
export const RATE_LIMIT_WINDOW_SECONDS_MIN = 1
export const RATE_LIMIT_WINDOW_SECONDS_MAX = 86400
export const RATE_LIMIT_MAX_ATTEMPTS_MIN = 1
export const RATE_LIMIT_MAX_ATTEMPTS_MAX = 100000

export const PRICING_VERSION_PAGE_SIZE = 20
export const GIFT_VERSION_LIST_SIZE = 20

/** Said out loud instead of showing a made-up value for a rule nobody has configured. */
export const RATE_LIMIT_NOT_CONFIGURED_COPY = '尚未配置，服务端使用默认值'

export const PRICING_VERSION_STATUSES: readonly PricingVersionStatus[] = [
  'DRAFT',
  'ACTIVE',
  'RETIRED',
]

const PRICING_STATUS_LABELS: Record<PricingVersionStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '生效中',
  RETIRED: '已停用',
}

const PRICING_STATUS_TONES: Record<PricingVersionStatus, string> = {
  DRAFT: 'warning',
  ACTIVE: 'success',
  RETIRED: 'default',
}

const RATE_LIMIT_ACTION_LABELS: Record<RateLimitAction, string> = {
  RECHARGE_ORDER_CREATE: '创建充值订单',
  INVOICE_APPLICATION_SUBMIT: '提交发票申请',
  FEEDBACK_SUBMIT: '提交反馈',
}

export function pricingStatusLabel(status: PricingVersionStatus): string {
  return PRICING_STATUS_LABELS[status]
}

export function pricingStatusTone(status: PricingVersionStatus): string {
  return PRICING_STATUS_TONES[status]
}

export function rateLimitActionLabel(action: RateLimitAction): string {
  return RATE_LIMIT_ACTION_LABELS[action]
}

/**
 * Why an action is not offered, stated on the control that is disabled. A disabled button that
 * silently does nothing reads as "already done", so every disabled action carries its reason.
 */
export const PRICING_ACTION_REASONS = {
  edit: '编辑不可用：仅草稿版本可编辑，已发布版本的价格已被订单引用',
  publish: '发布不可用：仅草稿版本可发布，发布后会自动停用被它替换的版本',
  retire: '停用不可用：该版本已停用，不能重复停用',
  giftEdit: '赠送积分不可用：仅草稿版本可修改，生效中或已停用的版本需要新建版本替换',
} as const

export const PUBLISH_CONFIRM_NOTE =
  '发布后该版本立即生效，平台会自动停用被它替换的版本，因此不会出现无汇率的时刻。若服务端已存在生效版本且拒绝本次发布，页面会原样显示服务端的拒绝原因。'

export const RATE_LIMIT_SERVER_ENFORCED_NOTE =
  '时间窗与最大次数均由服务端强制校验：越界或使用过期版本号的保存请求会被服务端拒绝，页面不会预先伪造成功。'

// ---------------------------------------------------------------- view models

export interface PricingTierView {
  tierId: string
  amountCents: number
  basePoints: number
  giftPoints: number
  points: number
  label: string
  sortOrder: number
}

export interface PricingVersionView {
  id: string
  versionNo: number
  status: PricingVersionStatus
  pointsPerYuan: number
  minRechargeAmountCents: number
  maxRechargeAmountCents: number
  note: string | null
  createdBy: string
  publishedBy: string | null
  publishedAt: string | null
  retiredAt: string | null
  createdAt: string
  updatedAt: string
  tiers: readonly PricingTierView[]
  tierCount: number
}

export interface PricingVersionPageView {
  items: readonly PricingVersionView[]
  page: number
  size: number
  total: number
  hasNext: boolean
}

export interface RateLimitRuleView {
  id: string | null
  action: RateLimitAction
  windowSeconds: number
  maxAttempts: number
  enabled: boolean
  configured: boolean
  version: number
  updatedBy: string | null
  updatedAt: string | null
}

// ---------------------------------------------------------------- DTO -> view

export function toPricingTierView(tier: PricingTierDto): PricingTierView {
  return {
    tierId: tier.tierId,
    amountCents: tier.amountCents,
    basePoints: tier.basePoints,
    giftPoints: tier.giftPoints,
    points: tier.points,
    label: tier.label,
    sortOrder: tier.sortOrder,
  }
}

export function toPricingVersionView(version: PricingVersionDto): PricingVersionView {
  return {
    id: version.id,
    versionNo: version.versionNo,
    status: version.status,
    pointsPerYuan: version.pointsPerYuan,
    minRechargeAmountCents: version.minRechargeAmountCents,
    maxRechargeAmountCents: version.maxRechargeAmountCents,
    note: version.note,
    createdBy: version.createdBy,
    publishedBy: version.publishedBy,
    publishedAt: version.publishedAt,
    retiredAt: version.retiredAt,
    createdAt: version.createdAt,
    updatedAt: version.updatedAt,
    tiers: (version.tiers ?? []).map(toPricingTierView),
    tierCount: (version.tiers ?? []).length,
  }
}

export function toPricingVersionPageView(page: PricingVersionPageDto): PricingVersionPageView {
  return {
    items: (page.items ?? []).map(toPricingVersionView),
    page: page.page,
    size: page.size,
    total: page.total,
    hasNext: page.hasNext,
  }
}

export function toRateLimitRuleView(rule: RateLimitRuleDto): RateLimitRuleView {
  return {
    id: rule.id,
    action: rule.action,
    windowSeconds: rule.windowSeconds,
    maxAttempts: rule.maxAttempts,
    enabled: rule.enabled,
    configured: rule.configured,
    version: rule.version,
    updatedBy: rule.updatedBy,
    updatedAt: rule.updatedAt,
  }
}

// ---------------------------------------------------------------- display helpers

export function formatRechargeRange(minCents: number, maxCents: number): string {
  return `${formatCents(minCents)} – ${formatCents(maxCents)}`
}

export function isWholeYuan(amountCents: number | null | undefined): amountCents is number {
  return (
    typeof amountCents === 'number' &&
    Number.isSafeInteger(amountCents) &&
    amountCents >= 0 &&
    amountCents % 100 === 0
  )
}

export function yuanToCents(yuan: number | null | undefined): number | undefined {
  if (typeof yuan !== 'number' || !Number.isFinite(yuan) || yuan < 0) return undefined
  const cents = Math.round(yuan * 100)
  return Number.isSafeInteger(cents) ? cents : undefined
}

/**
 * The base points a tier would earn, derived from the entered amount and the version's rate.
 * Shown as 预计 only: the tier request does not carry base points, so the server's answer is the
 * authority and this hint is recomputed from what the operator typed.
 */
export function deriveBasePoints(
  amountCents: number | null | undefined,
  pointsPerYuan: number | null | undefined,
): number | undefined {
  if (!isWholeYuan(amountCents)) return undefined
  if (!isPositiveInteger(pointsPerYuan)) return undefined
  const base = (amountCents / 100) * pointsPerYuan
  return Number.isSafeInteger(base) ? base : undefined
}

export function deriveTotalPoints(
  basePoints: number | undefined,
  giftPoints: number | null | undefined,
): number | undefined {
  if (basePoints === undefined) return undefined
  const gift = giftPoints ?? 0
  if (!Number.isSafeInteger(gift) || gift < 0) return undefined
  const total = basePoints + gift
  return Number.isSafeInteger(total) ? total : undefined
}

export function isPositiveInteger(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

export function isNonNegativeInteger(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

// ---------------------------------------------------------------- form drafts

export interface PricingTierDraftRow {
  rowId: string
  amountYuan: number | null
  giftPoints: number | null
  label: string
  sortOrder: number | null
}

export interface PricingVersionFormDraft {
  pointsPerYuan: number | null
  minYuan: number | null
  maxYuan: number | null
  note: string
  tiers: readonly PricingTierDraftRow[]
}

/** What the rate-limit form holds while it is being edited: a bound is missing until it is typed. */
export interface RateLimitRuleFormState {
  windowSeconds: number | null
  maxAttempts: number | null
  enabled: boolean
}

export type RateLimitRuleDraft = Omit<UpdateRateLimitRequest, 'expectedVersion'>

export function createPricingTierDraftRow(rowId: string): PricingTierDraftRow {
  return { rowId, amountYuan: null, giftPoints: null, label: '', sortOrder: null }
}

export function toPricingTierDraftRow(tier: PricingTierView): PricingTierDraftRow {
  return {
    rowId: tier.tierId,
    amountYuan: tier.amountCents / 100,
    giftPoints: tier.giftPoints,
    label: tier.label,
    sortOrder: tier.sortOrder,
  }
}

/**
 * The first reason the draft cannot be sent, or `undefined` when it can. Every message names the
 * field and the bound it broke, so a blocked save never looks like a finished one.
 */
export function validatePricingVersionForm(draft: PricingVersionFormDraft): string | undefined {
  if (!isPositiveInteger(draft.pointsPerYuan)) {
    return '积分/元必须为正整数，且表示 1 元可以兑换多少积分。'
  }
  if (draft.minYuan === null || draft.minYuan === undefined) {
    return '请填写最低充值金额（元）。'
  }
  if (draft.maxYuan === null || draft.maxYuan === undefined) {
    return '请填写最高充值金额（元）。'
  }

  const minCents = yuanToCents(draft.minYuan)
  const maxCents = yuanToCents(draft.maxYuan)
  if (minCents === undefined || !isWholeYuan(minCents)) {
    return '最低充值金额必须是不小于 0 的整元金额（服务端按整元定价）。'
  }
  if (maxCents === undefined || !isWholeYuan(maxCents)) {
    return '最高充值金额必须是不小于 0 的整元金额（服务端按整元定价）。'
  }
  if (minCents > maxCents) {
    return `最低充值金额不能大于最高充值金额：当前最低 ${formatCents(minCents)}，最高 ${formatCents(maxCents)}。`
  }
  if (!draft.tiers.length) {
    return '至少需要一个充值档位，否则付费页无法渲染。'
  }

  const seenAmounts = new Set<number>()
  for (const [index, row] of draft.tiers.entries()) {
    const position = `第 ${index + 1} 个档位`
    const amountCents = yuanToCents(row.amountYuan)
    if (row.amountYuan === null || amountCents === undefined || !isWholeYuan(amountCents)) {
      return `${position}：充值金额必须是不小于 0 的整元金额。`
    }
    if (amountCents < minCents || amountCents > maxCents) {
      return `${position}：充值金额 ${formatCents(amountCents)} 不在 ${formatCents(minCents)}–${formatCents(maxCents)} 区间内。`
    }
    if (seenAmounts.has(amountCents)) {
      return `${position}：充值金额 ${formatCents(amountCents)} 与其它档位重复，服务端按金额精确匹配赠送积分。`
    }
    seenAmounts.add(amountCents)
    if (!row.label.trim()) {
      return `${position}：标签不能为空，付费页需要展示它。`
    }
    if (row.giftPoints !== null && !isNonNegativeInteger(row.giftPoints)) {
      return `${position}：赠送积分必须是不小于 0 的整数。`
    }
    if (row.sortOrder !== null && !Number.isSafeInteger(row.sortOrder)) {
      return `${position}：排序必须为整数。`
    }
  }

  return undefined
}

/** Builds the request body. Base points are deliberately absent: the server derives them. */
export function toPricingVersionRequest(
  draft: PricingVersionFormDraft,
): CreatePricingVersionRequest {
  const minCents = yuanToCents(draft.minYuan) as number
  const maxCents = yuanToCents(draft.maxYuan) as number
  return {
    pointsPerYuan: draft.pointsPerYuan as number,
    minRechargeAmountCents: minCents,
    maxRechargeAmountCents: maxCents,
    note: draft.note.trim() ? draft.note.trim() : null,
    tiers: draft.tiers.map<PricingTierRequest>((row, index) => ({
      amountCents: yuanToCents(row.amountYuan) as number,
      giftPoints: row.giftPoints ?? undefined,
      label: row.label.trim(),
      sortOrder: row.sortOrder ?? index,
    })),
  }
}

/** The first reason the throttles cannot be sent, naming the bound that was broken. */
export function validateRateLimitForm(state: RateLimitRuleFormState): string | undefined {
  if (
    state.windowSeconds === null ||
    !Number.isSafeInteger(state.windowSeconds) ||
    state.windowSeconds < RATE_LIMIT_WINDOW_SECONDS_MIN ||
    state.windowSeconds > RATE_LIMIT_WINDOW_SECONDS_MAX
  ) {
    return `时间窗必须是 ${RATE_LIMIT_WINDOW_SECONDS_MIN}–${RATE_LIMIT_WINDOW_SECONDS_MAX} 秒之间的整数。`
  }
  if (
    state.maxAttempts === null ||
    !Number.isSafeInteger(state.maxAttempts) ||
    state.maxAttempts < RATE_LIMIT_MAX_ATTEMPTS_MIN ||
    state.maxAttempts > RATE_LIMIT_MAX_ATTEMPTS_MAX
  ) {
    return `最大次数必须是 ${RATE_LIMIT_MAX_ATTEMPTS_MIN}–${RATE_LIMIT_MAX_ATTEMPTS_MAX} 次之间的整数。`
  }
  return undefined
}

/** Only valid after {@link validateRateLimitForm} returned `undefined`. */
export function toRateLimitRuleDraft(state: RateLimitRuleFormState): RateLimitRuleDraft {
  return {
    windowSeconds: state.windowSeconds as number,
    maxAttempts: state.maxAttempts as number,
    enabled: state.enabled,
  }
}

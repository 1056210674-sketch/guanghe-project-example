/**
 * The media console's presentation layer: labels, filter state and formatting.
 *
 * There is deliberately no hand-written mirror of a media DTO here. Rows are the generated
 * Browser API types; a second definition of the same business shape is a second thing that can
 * disagree with the Java contract, and the console's whole job is to report what the server
 * actually holds. Only what the server does not send — Chinese labels, tag colours, the shape of
 * the filter controls — is defined in this file.
 */
import type {
  AdminMediaCallback,
  AdminMediaCallbackListQuery,
  AdminMediaResource,
  AdminMediaResourceListQuery,
  AdminMediaSubmissionListQuery,
  AdminMediaSubmissionStatus,
  AdminMediaSubmissionSummary,
} from '../../api/adminMediaApi'

export type AdminMediaResourceStatus = AdminMediaResource['status']
export type AdminMediaSubmissionState = AdminMediaSubmissionSummary['submissionState']
export type AdminMediaCallbackStatus = AdminMediaCallback['status']

interface TagMeta {
  label: string
  color: string
}

/**
 * All eight local statuses, one label each.
 *
 * Eight, not five: the state machine distinguishes "the upstream accepted it" from "it is
 * published" and "we never learned what the upstream did" from "it failed", and an operator
 * reconciling a submission has to be able to tell those apart. Compressing them into a coarser
 * vocabulary would hide exactly the cases this console exists to surface.
 */
export const MEDIA_SUBMISSION_STATUS_META: Record<AdminMediaSubmissionStatus, TagMeta> = {
  QUEUED: { label: '已入队', color: 'default' },
  SUBMITTING: { label: '提交中', color: 'processing' },
  SUBMIT_UNKNOWN: { label: '受理结果未知', color: 'warning' },
  ACCEPTED: { label: '上游已受理', color: 'processing' },
  PUBLISHED: { label: '已发布', color: 'success' },
  REJECTED: { label: '上游已拒绝', color: 'error' },
  RECONCILING: { label: '核对中', color: 'warning' },
  FAILED: { label: '提交失败', color: 'error' },
}

export const MEDIA_SUBMISSION_STATE_META: Record<AdminMediaSubmissionState, TagMeta> = {
  NOT_SUBMITTED: { label: '未提交', color: 'default' },
  CONFIRMED: { label: '已确认受理', color: 'success' },
  UNKNOWN: { label: '受理状态未知', color: 'warning' },
}

export const MEDIA_CALLBACK_STATUS_META: Record<AdminMediaCallbackStatus, TagMeta> = {
  RECEIVED: { label: '待处理', color: 'warning' },
  PROCESSED: { label: '已处理', color: 'success' },
  FAILED: { label: '处理失败', color: 'error' },
}

export const MEDIA_RESOURCE_STATUS_META: Record<AdminMediaResourceStatus, TagMeta> = {
  ACTIVE: { label: '启用', color: 'success' },
  INACTIVE: { label: '停用', color: 'default' },
}

/**
 * The sync table's four allowed values, taken from the column's check constraint rather than
 * guessed. An unrecognised value is shown as itself instead of being mapped onto one of them.
 */
const MEDIA_SYNC_STATUS_META: Record<string, TagMeta> = {
  IDLE: { label: '待运行', color: 'default' },
  RUNNING: { label: '同步中', color: 'processing' },
  SUCCEEDED: { label: '已完成', color: 'success' },
  FAILED: { label: '失败', color: 'error' },
}

export function syncStatusMeta(status: string): TagMeta {
  return MEDIA_SYNC_STATUS_META[status] ?? { label: status, color: 'default' }
}

export type AdminMediaSubmissionGroup = 'PENDING' | 'EXCEPTION'

export const MEDIA_SUBMISSION_GROUP_META: Record<AdminMediaSubmissionGroup, TagMeta> = {
  PENDING: { label: '进行中', color: 'processing' },
  EXCEPTION: { label: '待人工处理', color: 'warning' },
}

/**
 * One control, not two.
 *
 * The contract rejects a request carrying both `status` and `group`, because the two can disagree
 * and the server refuses to guess. A single select whose value is one of these strings cannot
 * produce that request in the first place.
 */
export const MEDIA_SUBMISSION_FILTER_ALL = 'ALL'

export function mediaSubmissionGroupFilterValue(group: AdminMediaSubmissionGroup): string {
  return `GROUP:${group}`
}

export function mediaSubmissionStatusFilterValue(
  status: AdminMediaSubmissionStatus,
): string {
  return `STATUS:${status}`
}

export interface AdminMediaResourceFiltersView {
  search?: string
  modelId?: string
  type?: string
  status?: AdminMediaResourceStatus
}

/** The select's own value; it encodes either a status, a group or no filter at all. */
export type AdminMediaSubmissionFilterView = string

function trimmed(value: string | undefined): string | undefined {
  const result = value?.trim()
  return result ? result : undefined
}

export function toAdminMediaResourceQuery(
  filters: AdminMediaResourceFiltersView,
  page: number,
  size: number,
): AdminMediaResourceListQuery {
  return {
    search: trimmed(filters.search),
    modelId: trimmed(filters.modelId),
    type: trimmed(filters.type),
    status: filters.status,
    page,
    size,
  }
}

export function toAdminMediaSubmissionQuery(
  filter: AdminMediaSubmissionFilterView,
  page: number,
  size: number,
): AdminMediaSubmissionListQuery {
  if (filter.startsWith('GROUP:')) {
    return { group: filter.slice('GROUP:'.length) as AdminMediaSubmissionGroup, page, size }
  }
  if (filter.startsWith('STATUS:')) {
    return {
      status: filter.slice('STATUS:'.length) as AdminMediaSubmissionStatus,
      page,
      size,
    }
  }
  return { page, size }
}

export function toAdminMediaCallbackQuery(
  page: number,
  size: number,
): AdminMediaCallbackListQuery {
  return { page, size }
}

/**
 * An exact decimal, shown as the server wrote it.
 *
 * `numeric(12,4)` is a value the browser must not round: parsing it into a double to reformat it
 * would turn 368.0000 into something the operator cannot reconcile against an invoice. A value
 * that is not a plain decimal is not silently repaired either.
 */
export function exactDecimal(value: string | undefined | null): string {
  return value && /^\d+(?:\.\d+)?$/.test(value) ? value : '—'
}

export function formatUpstreamPrice(value: string | undefined | null): string {
  const decimal = exactDecimal(value)
  return decimal === '—' ? decimal : `¥${decimal}`
}

export interface CapabilityEntry {
  key: string
  value: string
}

/**
 * The upstream's own capability keys, printed verbatim.
 *
 * These keys belong to the provider and can change without our contract changing, so they are not
 * translated into prose we would then have to keep in step: the operator reads what the provider
 * said. Ordering is by key so the list does not reshuffle between loads.
 */
export function capabilityEntries(
  capabilities: Record<string, unknown> | undefined | null,
): CapabilityEntry[] {
  if (!capabilities || typeof capabilities !== 'object') return []
  return Object.keys(capabilities)
    .sort()
    .map((key) => ({ key, value: capabilityText(capabilities[key]) }))
}

function capabilityText(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'string') return value || '—'
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value)
}

export type InvoiceStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'REJECTED'
export interface InvoiceApplicationView {
  id: string
  account: string
  orderId: string
  amountCents: number
  title: string
  taxIdMasked: string
  emailMasked: string
  phoneMasked?: string
  createdAt: string
  status: InvoiceStatus
  note?: string
  invoiceNumber?: string
  processingNote?: string
  deliveryResult?: 'SENT' | 'FAILED' | 'NOT_SENT'
  events: readonly { title: string; time: string; detail?: string }[]
}

export type InvoiceSummaryView = Record<InvoiceStatus, number>

export function safePublishedUrl(value: string | undefined): string | undefined {
  if (!value?.trim()) return undefined
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && !url.username && !url.password ? url.href : undefined
  } catch {
    return undefined
  }
}

export function displayCount(value: number | undefined): string {
  return value !== undefined && Number.isSafeInteger(value) && value >= 0
    ? value.toLocaleString('en-US') : '—'
}

export function invoiceFingerprint(item: InvoiceApplicationView): string {
  return JSON.stringify([
    item.id, item.account, item.orderId, String(item.amountCents), item.title,
    item.taxIdMasked, item.emailMasked, item.phoneMasked, item.createdAt,
    item.status, item.note, item.invoiceNumber, item.processingNote,
    item.deliveryResult, item.events.map(event => [event.title, event.time, event.detail]),
  ])
}

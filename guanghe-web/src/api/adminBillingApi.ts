import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiRequest } from './client'
import type {
  FeedbackDto,
  FeedbackPageDto,
  InvoiceApplicationDto,
  InvoiceApplicationPageDto,
  RechargeOrderDto,
  RechargeOrderPageDto,
} from './billingApi'

/**
 * The operator half of the commercial API.
 *
 * Kept in its own module rather than mixed into `billingApi` because the two surfaces are
 * authorised differently: everything here requires an administrator, and everything in
 * `billingApi` is scoped to the signed-in account. A page importing from here is asking for an
 * operator-only view.
 */

export type PointAdjustmentDto = components['schemas']['PointAdjustment']
export type PointAdjustmentPageDto = components['schemas']['PointAdjustmentPage']
export type OperationalAuditEventDto = components['schemas']['OperationalAuditEvent']
export type OperationalAuditEventPageDto = components['schemas']['OperationalAuditEventPage']
export type PricingVersionDto = components['schemas']['PricingVersion']
export type PricingVersionPageDto = components['schemas']['PricingVersionPage']
export type RateLimitRuleDto = components['schemas']['RateLimitRule']
export type AdminPointLedgerEntryDto = components['schemas']['AdminPointLedgerEntry']
export type AdminPointLedgerPageDto = components['schemas']['AdminPointLedgerPage']
export type LedgerSummaryDto = components['schemas']['LedgerSummary']
export type BookkeepingSummaryDto = components['schemas']['BookkeepingSummary']

export type CreatePricingVersionRequest = components['schemas']['CreatePricingVersionRequest']
export type UpdatePricingVersionRequest = components['schemas']['UpdatePricingVersionRequest']
export type PricingTierRequest = components['schemas']['PricingTierRequest']
export type UpdateRateLimitRequest = components['schemas']['UpdateRateLimitRequest']
export type CreatePointAdjustmentRequest = components['schemas']['CreatePointAdjustmentRequest']
export type ConfirmPointAdjustmentRequest =
  components['schemas']['ConfirmPointAdjustmentRequest']
export type RejectInvoiceApplicationRequest =
  components['schemas']['RejectInvoiceApplicationRequest']
export type ConfirmManualInvoiceDeliveryRequest =
  components['schemas']['ConfirmManualInvoiceDeliveryRequest']
export type HandleFeedbackRequest = components['schemas']['HandleFeedbackRequest']

export type AdminRechargeOrderListQuery = NonNullable<
  operations['listAdminRechargeOrders']['parameters']['query']
>
export type PointAdjustmentListQuery = NonNullable<
  operations['listPointAdjustments']['parameters']['query']
>
export type OperationalAuditEventListQuery = NonNullable<
  operations['listOperationalAuditEvents']['parameters']['query']
>
export type PricingVersionListQuery = NonNullable<
  operations['listPricingVersions']['parameters']['query']
>
export type AdminInvoiceApplicationListQuery = NonNullable<
  operations['listAdminInvoiceApplications']['parameters']['query']
>
export type AdminFeedbackListQuery = NonNullable<
  operations['listAdminFeedback']['parameters']['query']
>
export type AdminPointLedgerListQuery = NonNullable<
  operations['listAdminPointLedger']['parameters']['query']
>
export type AdminPointLedgerSummaryQuery = NonNullable<
  operations['getAdminPointLedgerSummary']['parameters']['query']
>

type RechargeOrderPageApiResponse = components['schemas']['RechargeOrderPageApiResponse']
type RechargeOrderApiResponse = components['schemas']['RechargeOrderApiResponse']
type PointAdjustmentPageApiResponse = components['schemas']['PointAdjustmentPageApiResponse']
type PointAdjustmentApiResponse = components['schemas']['PointAdjustmentApiResponse']
type OperationalAuditEventPageApiResponse =
  components['schemas']['OperationalAuditEventPageApiResponse']
type PricingVersionPageApiResponse = components['schemas']['PricingVersionPageApiResponse']
type PricingVersionApiResponse = components['schemas']['PricingVersionApiResponse']
type RateLimitRuleApiResponse = components['schemas']['RateLimitRuleApiResponse']
type RateLimitRuleListApiResponse = components['schemas']['RateLimitRuleListApiResponse']
type InvoiceApplicationPageApiResponse =
  components['schemas']['InvoiceApplicationPageApiResponse']
type InvoiceApplicationApiResponse = components['schemas']['InvoiceApplicationApiResponse']
type FeedbackPageApiResponse = components['schemas']['FeedbackPageApiResponse']
type FeedbackApiResponse = components['schemas']['FeedbackApiResponse']
type AdminPointLedgerPageApiResponse = components['schemas']['AdminPointLedgerPageApiResponse']
type LedgerSummaryApiResponse = components['schemas']['LedgerSummaryApiResponse']
type BookkeepingSummaryApiResponse = components['schemas']['BookkeepingSummaryApiResponse']

function requireData<TData>(data: TData | undefined): TData {
  if (data === undefined || data === null) {
    throw new ApiError({
      status: 200,
      code: 'INVALID_RESPONSE',
      message: '服务返回了无法识别的数据。',
    })
  }
  return data
}

function withQuery(
  path: string,
  params: Readonly<Record<string, string | number | undefined>>,
): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue
    search.set(key, String(value))
  }
  const suffix = search.toString()
  return suffix ? `${path}?${suffix}` : path
}

// ---------------------------------------------------------------- recharge orders

export async function listAdminRechargeOrders(
  query: AdminRechargeOrderListQuery = {},
): Promise<RechargeOrderPageDto> {
  const response = await apiRequest<RechargeOrderPageApiResponse>(
    withQuery('/api/v1/admin/billing/recharge-orders', {
      userId: query.userId,
      status: query.status,
      page: query.page,
      size: query.size,
    }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function getAdminRechargeOrder(orderId: string): Promise<RechargeOrderDto> {
  const response = await apiRequest<RechargeOrderApiResponse>(
    `/api/v1/admin/billing/recharge-orders/${encodeURIComponent(orderId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

/**
 * Records what the provider says about this order and reports whether the credit moved.
 *
 * An operator asking the provider does not itself change the balance: only a verified callback or a
 * confirmed provider answer does, and the answer says which of the two happened.
 */
export async function reconcileAdminRechargeOrder(orderId: string): Promise<RechargeOrderDto> {
  const response = await apiRequest<RechargeOrderApiResponse>(
    `/api/v1/admin/billing/recharge-orders/${encodeURIComponent(orderId)}/reconcile`,
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- manual adjustments

export async function listPointAdjustments(
  query: PointAdjustmentListQuery = {},
): Promise<PointAdjustmentPageDto> {
  const response = await apiRequest<PointAdjustmentPageApiResponse>(
    withQuery('/api/v1/admin/billing/point-adjustments', {
      userId: query.userId,
      status: query.status,
      page: query.page,
      size: query.size,
    }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

/**
 * Step one of two. Returns a confirmation token that exists nowhere else: only its hash is stored,
 * so it can be neither replayed nor re-read. No points move until {@link confirmPointAdjustment}.
 */
export async function requestPointAdjustment(
  body: CreatePointAdjustmentRequest,
): Promise<PointAdjustmentDto> {
  const response = await apiRequest<PointAdjustmentApiResponse>(
    '/api/v1/admin/billing/point-adjustments',
    { method: 'POST', auth: 'required', body },
  )
  return requireData(response.data)
}

/** Step two. The ledger row and the APPLIED status are written in the same transaction. */
export async function confirmPointAdjustment(
  adjustmentId: string,
  body: ConfirmPointAdjustmentRequest,
): Promise<PointAdjustmentDto> {
  const response = await apiRequest<PointAdjustmentApiResponse>(
    `/api/v1/admin/billing/point-adjustments/${encodeURIComponent(adjustmentId)}/confirm`,
    { method: 'POST', auth: 'required', body },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- pricing versions

export async function listPricingVersions(
  query: PricingVersionListQuery = {},
): Promise<PricingVersionPageDto> {
  const response = await apiRequest<PricingVersionPageApiResponse>(
    withQuery('/api/v1/admin/billing/pricing-versions', {
      status: query.status,
      page: query.page,
      size: query.size,
    }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function createPricingVersion(
  body: CreatePricingVersionRequest,
): Promise<PricingVersionDto> {
  const response = await apiRequest<PricingVersionApiResponse>(
    '/api/v1/admin/billing/pricing-versions',
    { method: 'POST', auth: 'required', body },
  )
  return requireData(response.data)
}

export async function getPricingVersion(versionId: string): Promise<PricingVersionDto> {
  const response = await apiRequest<PricingVersionApiResponse>(
    `/api/v1/admin/billing/pricing-versions/${encodeURIComponent(versionId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function updatePricingVersion(
  versionId: string,
  body: UpdatePricingVersionRequest,
): Promise<PricingVersionDto> {
  const response = await apiRequest<PricingVersionApiResponse>(
    `/api/v1/admin/billing/pricing-versions/${encodeURIComponent(versionId)}`,
    { method: 'PUT', auth: 'required', body },
  )
  return requireData(response.data)
}

/** Publishing retires the version it replaces, so the deployment is never without a rate. */
export async function publishPricingVersion(versionId: string): Promise<PricingVersionDto> {
  const response = await apiRequest<PricingVersionApiResponse>(
    `/api/v1/admin/billing/pricing-versions/${encodeURIComponent(versionId)}/publish`,
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data)
}

export async function retirePricingVersion(versionId: string): Promise<PricingVersionDto> {
  const response = await apiRequest<PricingVersionApiResponse>(
    `/api/v1/admin/billing/pricing-versions/${encodeURIComponent(versionId)}/retire`,
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- rate limits

export async function listRateLimits(): Promise<readonly RateLimitRuleDto[]> {
  const response = await apiRequest<RateLimitRuleListApiResponse>(
    '/api/v1/admin/billing/rate-limits',
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function updateRateLimit(
  action: string,
  body: UpdateRateLimitRequest,
): Promise<RateLimitRuleDto> {
  const response = await apiRequest<RateLimitRuleApiResponse>(
    `/api/v1/admin/billing/rate-limits/${encodeURIComponent(action)}`,
    { method: 'PUT', auth: 'required', body },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- invoices

export async function listAdminInvoiceApplications(
  query: AdminInvoiceApplicationListQuery = {},
): Promise<InvoiceApplicationPageDto> {
  const response = await apiRequest<InvoiceApplicationPageApiResponse>(
    withQuery('/api/v1/admin/billing/invoices', {
      userId: query.userId,
      status: query.status,
      page: query.page,
      size: query.size,
    }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function getAdminInvoiceApplication(
  applicationId: string,
): Promise<InvoiceApplicationDto> {
  const response = await apiRequest<InvoiceApplicationApiResponse>(
    `/api/v1/admin/billing/invoices/${encodeURIComponent(applicationId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

/** Takes the application under review. Still no invoice exists at this point. */
export async function acceptInvoiceApplication(
  applicationId: string,
): Promise<InvoiceApplicationDto> {
  const response = await apiRequest<InvoiceApplicationApiResponse>(
    `/api/v1/admin/billing/invoices/${encodeURIComponent(applicationId)}/accept`,
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data)
}

/**
 * Records that internal review passed. It is not an issue date and the page must not present it as
 * one: review approval is independent of issuing and manual email sending.
 */
export async function approveInvoiceApplication(
  applicationId: string,
): Promise<InvoiceApplicationDto> {
  const response = await apiRequest<InvoiceApplicationApiResponse>(
    `/api/v1/admin/billing/invoices/${encodeURIComponent(applicationId)}/approve`,
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data)
}

export async function rejectInvoiceApplication(
  applicationId: string,
  body: RejectInvoiceApplicationRequest,
): Promise<InvoiceApplicationDto> {
  const response = await apiRequest<InvoiceApplicationApiResponse>(
    `/api/v1/admin/billing/invoices/${encodeURIComponent(applicationId)}/reject`,
    { method: 'POST', auth: 'required', body },
  )
  return requireData(response.data)
}

/**
 * Records the administrator's confirmation after they actually emailed an issued invoice.
 * This is not evidence that the applicant received the email.
 */
export async function deliverInvoiceApplication(
  applicationId: string,
  body: ConfirmManualInvoiceDeliveryRequest,
): Promise<InvoiceApplicationDto> {
  const response = await apiRequest<InvoiceApplicationApiResponse>(
    `/api/v1/admin/billing/invoices/${encodeURIComponent(applicationId)}/deliver`,
    { method: 'POST', auth: 'required', body },
  )
  return requireData(response.data)
}

/** Queues a failed/unconfigured or historic invoice notification for another Feishu attempt. */
export async function retryInvoiceWebhook(applicationId: string): Promise<InvoiceApplicationDto> {
  const response = await apiRequest<InvoiceApplicationApiResponse>(
    `/api/v1/admin/billing/invoices/${encodeURIComponent(applicationId)}/webhook/retry`,
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- feedback

export async function listAdminFeedback(
  query: AdminFeedbackListQuery = {},
): Promise<FeedbackPageDto> {
  const response = await apiRequest<FeedbackPageApiResponse>(
    withQuery('/api/v1/admin/billing/feedback', {
      userId: query.userId,
      status: query.status,
      page: query.page,
      size: query.size,
    }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

/** Status only moves forward, and the reply is what the author reads, so it is mandatory. */
export async function handleFeedback(
  feedbackId: string,
  body: HandleFeedbackRequest,
): Promise<FeedbackDto> {
  const response = await apiRequest<FeedbackApiResponse>(
    `/api/v1/admin/billing/feedback/${encodeURIComponent(feedbackId)}/handle`,
    { method: 'POST', auth: 'required', body },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- ledger and bookkeeping

export async function listAdminPointLedger(
  query: AdminPointLedgerListQuery = {},
): Promise<AdminPointLedgerPageDto> {
  const response = await apiRequest<AdminPointLedgerPageApiResponse>(
    withQuery('/api/v1/admin/billing/point-ledger', {
      userId: query.userId,
      entryType: query.entryType,
      businessType: query.businessType,
      from: query.from,
      to: query.to,
      page: query.page,
      size: query.size,
    }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

/** Totals for a filter, fetched separately so a total never depends on which page is open. */
export async function getAdminPointLedgerSummary(
  query: AdminPointLedgerSummaryQuery = {},
): Promise<LedgerSummaryDto> {
  const response = await apiRequest<LedgerSummaryApiResponse>(
    withQuery('/api/v1/admin/billing/point-ledger/summary', {
      userId: query.userId,
      entryType: query.entryType,
      businessType: query.businessType,
      from: query.from,
      to: query.to,
    }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function getBookkeepingSummary(): Promise<BookkeepingSummaryDto> {
  const response = await apiRequest<BookkeepingSummaryApiResponse>(
    '/api/v1/admin/billing/bookkeeping/summary',
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- operational audit

export async function listOperationalAuditEvents(
  query: OperationalAuditEventListQuery = {},
): Promise<OperationalAuditEventPageDto> {
  const response = await apiRequest<OperationalAuditEventPageApiResponse>(
    withQuery('/api/v1/admin/billing/audit-events', {
      actorType: query.actorType,
      targetType: query.targetType,
      targetId: query.targetId,
      eventType: query.eventType,
      page: query.page,
      size: query.size,
    }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

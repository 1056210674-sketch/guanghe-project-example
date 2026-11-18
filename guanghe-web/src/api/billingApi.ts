import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiRequest } from './client'

/**
 * The user-facing half of the commercial API.
 *
 * Every call is typed from the generated contract rather than from a hand-written shape, so a
 * server-side rename surfaces here as a compile error instead of as an undefined field at runtime.
 *
 * Two conventions are shared with the rest of the app:
 *   * a write that creates something takes an `idempotencyKey` from the caller, so a retry of the
 *     same logical action reuses it and the server can recognise the replay;
 *   * a response whose `data` is missing is an error, never silently an empty page.
 */

export type RechargeCapabilityDto = components['schemas']['RechargeCapability']
export type RateCardDto = components['schemas']['RateCard']
export type RechargeTierDto = components['schemas']['RechargeTier']
export type RechargeOrderDto = components['schemas']['RechargeOrder']
export type RechargeOrderPageDto = components['schemas']['RechargeOrderPage']
export type InvoiceCapabilityDto = components['schemas']['InvoiceCapability']
export type InvoiceApplicationDto = components['schemas']['InvoiceApplication']
export type InvoiceApplicationPageDto = components['schemas']['InvoiceApplicationPage']
export type NotificationDto = components['schemas']['Notification']
export type NotificationPageDto = components['schemas']['NotificationPage']
export type NotificationCapabilityDto = components['schemas']['NotificationCapability']
export type UnreadNotificationCountDto = components['schemas']['UnreadNotificationCount']
export type FeedbackDto = components['schemas']['Feedback']
export type FeedbackPageDto = components['schemas']['FeedbackPage']

export type CreateRechargeOrderRequest = components['schemas']['CreateRechargeOrderRequest']
export type CreateInvoiceApplicationRequest =
  components['schemas']['CreateInvoiceApplicationRequest']
export type CreateFeedbackRequest = components['schemas']['CreateFeedbackRequest']

export type RechargeOrderListQuery = NonNullable<
  operations['listRechargeOrders']['parameters']['query']
>
export type InvoiceApplicationListQuery = NonNullable<
  operations['listInvoiceApplications']['parameters']['query']
>
export type NotificationListQuery = NonNullable<
  operations['listNotifications']['parameters']['query']
>
export type FeedbackListQuery = NonNullable<operations['listMyFeedback']['parameters']['query']>

type RechargeCapabilityApiResponse = components['schemas']['RechargeCapabilityApiResponse']
type RateCardApiResponse = components['schemas']['RateCardApiResponse']
type RechargeOrderApiResponse = components['schemas']['RechargeOrderApiResponse']
type RechargeOrderPageApiResponse = components['schemas']['RechargeOrderPageApiResponse']
type InvoiceCapabilityApiResponse = components['schemas']['InvoiceCapabilityApiResponse']
type InvoiceApplicationApiResponse = components['schemas']['InvoiceApplicationApiResponse']
type InvoiceApplicationPageApiResponse =
  components['schemas']['InvoiceApplicationPageApiResponse']
type NotificationApiResponse = components['schemas']['NotificationApiResponse']
type NotificationPageApiResponse = components['schemas']['NotificationPageApiResponse']
type NotificationCapabilityApiResponse = components['schemas']['NotificationCapabilityApiResponse']
type UnreadNotificationCountApiResponse =
  components['schemas']['UnreadNotificationCountApiResponse']
type FeedbackApiResponse = components['schemas']['FeedbackApiResponse']
type FeedbackPageApiResponse = components['schemas']['FeedbackPageApiResponse']

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
  params: Readonly<Record<string, string | number | boolean | undefined>>,
): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue
    search.set(key, String(value))
  }
  const suffix = search.toString()
  return suffix ? `${path}?${suffix}` : path
}

// ---------------------------------------------------------------- capability probes

/**
 * Whether recharge can be started right now.
 *
 * Deliberately ungated by any module switch: a client has to be able to ask, and the answer is
 * reported verbatim. A `payable: false` here is what the page renders as 暂不可充值.
 */
export async function getRechargeCapability(): Promise<RechargeCapabilityDto> {
  const response = await apiRequest<RechargeCapabilityApiResponse>(
    '/api/v1/billing/recharge-capability',
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function getInvoiceCapability(): Promise<InvoiceCapabilityDto> {
  const response = await apiRequest<InvoiceCapabilityApiResponse>(
    '/api/v1/billing/invoice-capability',
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function getNotificationCapability(): Promise<NotificationCapabilityDto> {
  const response = await apiRequest<NotificationCapabilityApiResponse>(
    '/api/v1/billing/notification-capability',
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- rate card and recharge

export async function getActiveRateCard(): Promise<RateCardDto> {
  const response = await apiRequest<RateCardApiResponse>('/api/v1/billing/pricing/active', {
    method: 'GET',
    auth: 'required',
  })
  return requireData(response.data)
}

export async function listRechargeOrders(
  query: RechargeOrderListQuery = {},
): Promise<RechargeOrderPageDto> {
  const response = await apiRequest<RechargeOrderPageApiResponse>(
    withQuery('/api/v1/billing/recharge-orders', {
      status: query.status,
      page: query.page,
      size: query.size,
    }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function createRechargeOrder(
  body: CreateRechargeOrderRequest,
  idempotencyKey: string,
): Promise<RechargeOrderDto> {
  const response = await apiRequest<RechargeOrderApiResponse>(
    '/api/v1/billing/recharge-orders',
    {
      method: 'POST',
      auth: 'required',
      body,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
  )
  return requireData(response.data)
}

export async function getRechargeOrder(orderId: string): Promise<RechargeOrderDto> {
  const response = await apiRequest<RechargeOrderApiResponse>(
    `/api/v1/billing/recharge-orders/${encodeURIComponent(orderId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

/** Asks the provider for the order's state. Returns the order either way, including "unchanged". */
export async function queryRechargeOrder(orderId: string): Promise<RechargeOrderDto> {
  const response = await apiRequest<RechargeOrderApiResponse>(
    `/api/v1/billing/recharge-orders/${encodeURIComponent(orderId)}/query`,
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data)
}

export async function closeRechargeOrder(orderId: string): Promise<RechargeOrderDto> {
  const response = await apiRequest<RechargeOrderApiResponse>(
    `/api/v1/billing/recharge-orders/${encodeURIComponent(orderId)}/close`,
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- invoices

export async function listInvoiceApplications(
  query: InvoiceApplicationListQuery = {},
): Promise<InvoiceApplicationPageDto> {
  const response = await apiRequest<InvoiceApplicationPageApiResponse>(
    withQuery('/api/v1/billing/invoices', { page: query.page, size: query.size }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function createInvoiceApplication(
  body: CreateInvoiceApplicationRequest,
  idempotencyKey: string,
): Promise<InvoiceApplicationDto> {
  const response = await apiRequest<InvoiceApplicationApiResponse>('/api/v1/billing/invoices', {
    method: 'POST',
    auth: 'required',
    body,
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return requireData(response.data)
}

export async function getInvoiceApplication(applicationId: string): Promise<InvoiceApplicationDto> {
  const response = await apiRequest<InvoiceApplicationApiResponse>(
    `/api/v1/billing/invoices/${encodeURIComponent(applicationId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function cancelInvoiceApplication(
  applicationId: string,
): Promise<InvoiceApplicationDto> {
  const response = await apiRequest<InvoiceApplicationApiResponse>(
    `/api/v1/billing/invoices/${encodeURIComponent(applicationId)}/cancel`,
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- notifications

export async function listNotifications(
  query: NotificationListQuery = {},
): Promise<NotificationPageDto> {
  const response = await apiRequest<NotificationPageApiResponse>(
    withQuery('/api/v1/billing/notifications', {
      page: query.page, size: query.size, category: query.category, unreadOnly: query.unreadOnly,
    }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function getUnreadNotificationCount(): Promise<UnreadNotificationCountDto> {
  const response = await apiRequest<UnreadNotificationCountApiResponse>(
    '/api/v1/billing/notifications/unread-count',
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

/** Marking an already-read notification read again is a no-op; the server answers the same row. */
export async function markNotificationRead(notificationId: string): Promise<NotificationDto> {
  const response = await apiRequest<NotificationApiResponse>(
    `/api/v1/billing/notifications/${encodeURIComponent(notificationId)}/read`,
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data)
}

export async function markAllNotificationsRead(): Promise<UnreadNotificationCountDto> {
  const response = await apiRequest<UnreadNotificationCountApiResponse>(
    '/api/v1/billing/notifications/read-all',
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- feedback

export async function listMyFeedback(query: FeedbackListQuery = {}): Promise<FeedbackPageDto> {
  const response = await apiRequest<FeedbackPageApiResponse>(
    withQuery('/api/v1/billing/feedback', { page: query.page, size: query.size }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function createFeedback(
  body: CreateFeedbackRequest,
  idempotencyKey: string,
): Promise<FeedbackDto> {
  const response = await apiRequest<FeedbackApiResponse>('/api/v1/billing/feedback', {
    method: 'POST',
    auth: 'required',
    body,
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return requireData(response.data)
}

export async function getMyFeedback(feedbackId: string): Promise<FeedbackDto> {
  const response = await apiRequest<FeedbackApiResponse>(
    `/api/v1/billing/feedback/${encodeURIComponent(feedbackId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

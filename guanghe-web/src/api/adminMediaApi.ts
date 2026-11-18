import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiRequest } from './client'

/**
 * The operator half of the media API.
 *
 * Kept out of `mediaApi` because the two surfaces are authorised differently and show different
 * things: everything here requires an administrator, and it is the only place the purchase price,
 * the markup and the provider identifiers are visible. A page importing from here is asking for an
 * operator-only view. Nothing here submits, replays a callback or starts a sync — the operator
 * console is read-only, and the module exposes no write so a presentation component cannot invent
 * one by accident.
 */

export type AdminMediaOverview = components['schemas']['AdminMediaOverview']
export type AdminMediaResource = components['schemas']['AdminMediaResource']
export type AdminMediaResourcePage = components['schemas']['AdminMediaResourcePage']
export type AdminMediaSubmissionSummary =
  components['schemas']['AdminMediaSubmissionSummary']
export type AdminMediaSubmission = components['schemas']['AdminMediaSubmission']
export type AdminMediaSubmissionPage =
  components['schemas']['AdminMediaSubmissionPage']
export type AdminMediaCallback = components['schemas']['AdminMediaCallback']
export type AdminMediaCallbackPage = components['schemas']['AdminMediaCallbackPage']
export type AdminMediaSyncState = components['schemas']['AdminMediaSyncState']
export type AdminMediaSubmissionStatus =
  components['schemas']['MediaSubmissionStatus']
export type AdminMediaSubmissionEvent =
  components['schemas']['MediaSubmissionEvent']

export type AdminMediaResourceListQuery = NonNullable<
  operations['listAdminMediaResources']['parameters']['query']
>
export type AdminMediaSubmissionListQuery = NonNullable<
  operations['listAdminMediaSubmissions']['parameters']['query']
>
export type AdminMediaCallbackListQuery = NonNullable<
  operations['listAdminMediaCallbacks']['parameters']['query']
>

type AdminMediaOverviewApiResponse =
  components['schemas']['AdminMediaOverviewApiResponse']
type AdminMediaResourceApiResponse =
  components['schemas']['AdminMediaResourceApiResponse']
type AdminMediaResourcePageApiResponse =
  components['schemas']['AdminMediaResourcePageApiResponse']
type AdminMediaSubmissionApiResponse =
  components['schemas']['AdminMediaSubmissionApiResponse']
type AdminMediaSubmissionPageApiResponse =
  components['schemas']['AdminMediaSubmissionPageApiResponse']
type AdminMediaCallbackApiResponse =
  components['schemas']['AdminMediaCallbackApiResponse']
type AdminMediaCallbackPageApiResponse =
  components['schemas']['AdminMediaCallbackPageApiResponse']
type AdminMediaSyncStateListApiResponse =
  components['schemas']['AdminMediaSyncStateListApiResponse']

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

// ---------------------------------------------------------------- overview

/**
 * Counts plus the provider wallet balance, which this version always reports as null: reading the
 * real balance is a live provider call, and a page load must not make one.
 */
export async function getAdminMediaOverview(): Promise<AdminMediaOverview> {
  const response = await apiRequest<AdminMediaOverviewApiResponse>(
    '/api/v1/admin/media/overview',
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- resources

export async function listAdminMediaResources(
  query: AdminMediaResourceListQuery = {},
): Promise<AdminMediaResourcePage> {
  const response = await apiRequest<AdminMediaResourcePageApiResponse>(
    withQuery('/api/v1/admin/media/resources', {
      search: query.search,
      modelId: query.modelId,
      type: query.type,
      status: query.status,
      page: query.page,
      size: query.size,
    }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function getAdminMediaResource(
  resourceId: string,
): Promise<AdminMediaResource> {
  const response = await apiRequest<AdminMediaResourceApiResponse>(
    `/api/v1/admin/media/resources/${encodeURIComponent(resourceId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- submissions

/**
 * `status` is one exact local status and `group` is one of the two buckets the vocabulary defines.
 * They are mutually exclusive at the contract level, so the caller must send one or the other; the
 * server rejects a request that carries both instead of letting one win silently.
 */
export async function listAdminMediaSubmissions(
  query: AdminMediaSubmissionListQuery = {},
): Promise<AdminMediaSubmissionPage> {
  const response = await apiRequest<AdminMediaSubmissionPageApiResponse>(
    withQuery('/api/v1/admin/media/submissions', {
      status: query.status,
      group: query.group,
      page: query.page,
      size: query.size,
    }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

/** Carries the event trail: the status transitions this submission actually went through. */
export async function getAdminMediaSubmission(
  submissionId: string,
): Promise<AdminMediaSubmission> {
  const response = await apiRequest<AdminMediaSubmissionApiResponse>(
    `/api/v1/admin/media/submissions/${encodeURIComponent(submissionId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- callbacks

/**
 * Receipts that still want a human — RECEIVED and FAILED. There is no status filter in this version,
 * so a processed receipt is reachable only by id, which is what an operator arrives with.
 */
export async function listAdminMediaCallbacks(
  query: AdminMediaCallbackListQuery = {},
): Promise<AdminMediaCallbackPage> {
  const response = await apiRequest<AdminMediaCallbackPageApiResponse>(
    withQuery('/api/v1/admin/media/callbacks', {
      page: query.page,
      size: query.size,
    }),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function getAdminMediaCallback(
  callbackId: string,
): Promise<AdminMediaCallback> {
  const response = await apiRequest<AdminMediaCallbackApiResponse>(
    `/api/v1/admin/media/callbacks/${encodeURIComponent(callbackId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

// ---------------------------------------------------------------- sync state

/**
 * The last known outcome per upstream model, not a log of sync runs: nothing records a batch, an
 * operator or per-run counters, so the console shows the current state rather than inventing runs.
 */
export async function listAdminMediaSyncStates(): Promise<
  readonly AdminMediaSyncState[]
> {
  const response = await apiRequest<AdminMediaSyncStateListApiResponse>(
    '/api/v1/admin/media/sync-states',
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

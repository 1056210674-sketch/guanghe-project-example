import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiRequest } from './client'

/**
 * Read-only administrator evidence for the Agent bundle running behind Java.
 *
 * The browser never calls Python directly. Current evidence is whatever Java obtained from its
 * signed internal capability call; history is only what Java observed on task/workflow executions.
 */
export type AdminAgentRuntimeSnapshot = components['schemas']['AdminAgentRuntimeSnapshot']
export type AdminAgentVersionSummary = components['schemas']['AdminAgentVersionSummary']
export type AdminAgentVersionPage = components['schemas']['AdminAgentVersionPage']
export type AdminAgentVersionDetail = components['schemas']['AdminAgentVersion']
export type AdminAgentVersionRun = components['schemas']['AdminAgentVersionRun']
export type AdminAgentVersionRunPage = components['schemas']['AdminAgentVersionRunPage']
export type AdminAgentEvaluationPage = components['schemas']['AdminAgentEvaluationPage']

export type AdminAgentVersionListQuery = NonNullable<
  operations['listAdminAgentVersions']['parameters']['query']
>
export type AdminAgentVersionRunListQuery = NonNullable<
  operations['listAdminAgentVersionRuns']['parameters']['query']
>
export type AdminAgentEvaluationListQuery = NonNullable<
  operations['listAdminAgentVersionEvaluations']['parameters']['query']
>

type AdminAgentVersionPageApiResponse =
  components['schemas']['AdminAgentVersionPageApiResponse']
type AdminAgentVersionApiResponse =
  components['schemas']['AdminAgentVersionApiResponse']
type AdminAgentVersionRunPageApiResponse =
  components['schemas']['AdminAgentVersionRunPageApiResponse']
type AdminAgentEvaluationPageApiResponse =
  components['schemas']['AdminAgentEvaluationPageApiResponse']

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

function pageQuery(
  path: string,
  query: { page?: number; size?: number },
): string {
  const search = new URLSearchParams()
  if (query.page !== undefined) search.set('page', String(query.page))
  if (query.size !== undefined) search.set('size', String(query.size))
  const suffix = search.toString()
  return suffix ? `${path}?${suffix}` : path
}

export async function listAdminAgentVersions(
  query: AdminAgentVersionListQuery = {},
): Promise<AdminAgentVersionPage> {
  const response = await apiRequest<AdminAgentVersionPageApiResponse>(
    pageQuery('/api/v1/admin/agent-versions', query),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function getAdminAgentVersion(
  bundleVersion: string,
): Promise<AdminAgentVersionDetail> {
  const response = await apiRequest<AdminAgentVersionApiResponse>(
    `/api/v1/admin/agent-versions/${encodeURIComponent(bundleVersion)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function listAdminAgentVersionRuns(
  bundleVersion: string,
  query: AdminAgentVersionRunListQuery = {},
): Promise<AdminAgentVersionRunPage> {
  const response = await apiRequest<AdminAgentVersionRunPageApiResponse>(
    pageQuery(
      `/api/v1/admin/agent-versions/${encodeURIComponent(bundleVersion)}/runs`,
      query,
    ),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function listAdminAgentVersionEvaluations(
  bundleVersion: string,
  query: AdminAgentEvaluationListQuery = {},
): Promise<AdminAgentEvaluationPage> {
  const response = await apiRequest<AdminAgentEvaluationPageApiResponse>(
    pageQuery(
      `/api/v1/admin/agent-versions/${encodeURIComponent(bundleVersion)}/evaluations`,
      query,
    ),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

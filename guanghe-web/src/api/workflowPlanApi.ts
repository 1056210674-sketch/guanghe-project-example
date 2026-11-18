import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiRequest } from './client'

export type WorkflowPlanId = components['schemas']['BusinessId']
export type WorkflowPlanStatus = components['schemas']['WorkflowPlanStatus']
export type WorkflowPlanSummary = components['schemas']['WorkflowPlanSummary']
export type WorkflowPlan = components['schemas']['WorkflowPlan']
export type WorkflowPlanPage = components['schemas']['WorkflowPlanPage']
export type WorkflowPlanAbandonRequest = components['schemas']['WorkflowPlanAbandonRequest']
export type WorkflowConfirmationPreviewRequest =
  components['schemas']['WorkflowConfirmationPreviewRequest']
export type WorkflowConfirmationAuthorization =
  components['schemas']['WorkflowConfirmationAuthorization']
export type WorkflowPlanConfirmRequest = components['schemas']['WorkflowPlanConfirmRequest']
export type WorkflowPlanConfirmResponse = components['schemas']['WorkflowPlanConfirmResponse']
export type WorkflowStartRequest = components['schemas']['WorkflowStartRequest']
export type WorkflowStartResponse = components['schemas']['WorkflowStartResponse']
export type WorkflowPlanListQuery = NonNullable<operations['listWorkflowPlans']['parameters']['query']>

type WorkflowPlanPageApiResponse = components['schemas']['WorkflowPlanPageApiResponse']
type WorkflowPlanApiResponse = components['schemas']['WorkflowPlanApiResponse']
type WorkflowConfirmationAuthorizationApiResponse =
  components['schemas']['WorkflowConfirmationAuthorizationApiResponse']
type WorkflowPlanConfirmApiResponse = components['schemas']['WorkflowPlanConfirmApiResponse']
type WorkflowStartApiResponse = components['schemas']['WorkflowStartApiResponse']

function requireData<T>(data: T | undefined): T {
  if (data === undefined) {
    throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '服务返回了无法识别的响应。' })
  }
  return data
}

function encodedId(id: WorkflowPlanId): string {
  return encodeURIComponent(id)
}

function withListQuery(query: WorkflowPlanListQuery): string {
  const search = new URLSearchParams()
  if (query.status) search.set('status', query.status)
  if (query.page !== undefined) search.set('page', String(query.page))
  if (query.size !== undefined) search.set('size', String(query.size))
  const suffix = search.toString()
  return suffix ? `/api/v1/workflow-plans?${suffix}` : '/api/v1/workflow-plans'
}

export async function listWorkflowPlans(query: WorkflowPlanListQuery = {}): Promise<WorkflowPlanPage> {
  const response = await apiRequest<WorkflowPlanPageApiResponse>(withListQuery(query), {
    method: 'GET',
    auth: 'required',
  })
  return requireData(response.data)
}

export async function getWorkflowPlan(planId: WorkflowPlanId): Promise<WorkflowPlan> {
  const response = await apiRequest<WorkflowPlanApiResponse>(
    `/api/v1/workflow-plans/${encodedId(planId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data)
}

export async function abandonWorkflowPlan(
  planId: WorkflowPlanId,
  request: WorkflowPlanAbandonRequest,
): Promise<WorkflowPlan> {
  const response = await apiRequest<WorkflowPlanApiResponse>(
    `/api/v1/workflow-plans/${encodedId(planId)}/abandon`,
    { method: 'POST', auth: 'required', body: request },
  )
  return requireData(response.data)
}

export async function previewWorkflowPlanConfirmation(
  planId: WorkflowPlanId,
  request: WorkflowConfirmationPreviewRequest,
): Promise<WorkflowConfirmationAuthorization> {
  const response = await apiRequest<WorkflowConfirmationAuthorizationApiResponse>(
    `/api/v1/workflow-plans/${encodedId(planId)}/confirmation-preview`,
    { method: 'POST', auth: 'required', body: request },
  )
  return requireData(response.data)
}

export async function confirmWorkflowPlan(
  planId: WorkflowPlanId,
  request: WorkflowPlanConfirmRequest,
  idempotencyKey: string,
): Promise<WorkflowPlanConfirmResponse> {
  const response = await apiRequest<WorkflowPlanConfirmApiResponse>(
    `/api/v1/workflow-plans/${encodedId(planId)}/confirm`,
    {
      method: 'POST',
      auth: 'required',
      body: request,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
  )
  return requireData(response.data)
}

export async function startWorkflowPlan(
  planId: WorkflowPlanId,
  request: WorkflowStartRequest,
  idempotencyKey: string,
): Promise<WorkflowStartResponse> {
  const response = await apiRequest<WorkflowStartApiResponse>(
    `/api/v1/workflow-plans/${encodedId(planId)}/start`,
    {
      method: 'POST',
      auth: 'required',
      body: request,
      headers: { 'Idempotency-Key': idempotencyKey },
    },
  )
  return requireData(response.data)
}

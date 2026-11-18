import type { components } from './generated/browser-api'
import { apiRequest } from './client'
import { ApiError } from './apiError'
import type { AuthSessionSnapshot } from '../auth/tokenStorage'

export type MonitorExecutionIntent = components['schemas']['MonitorExecutionIntent']
export type MonitorRangeInput = components['schemas']['MonitorRangeInput']
export type FrozenMonitorRange = components['schemas']['FrozenMonitorRange']
export type MonitorActionPreview = components['schemas']['MonitorActionPreview']
export type MonitorAuthorization = components['schemas']['MonitorAuthorizationView']
export type MonitorAdmission = components['schemas']['MonitorAdmissionView']
export type BudgetPolicy = components['schemas']['BudgetPolicyView']
export type MonitorWaitReason = components['schemas']['WaitReason']

const conversationPath = (id: string) => `/api/v1/conversations/${encodeURIComponent(id)}`
async function request<T>(path: string, method: 'GET' | 'POST' | 'PUT', session: AuthSessionSnapshot, body?: unknown): Promise<T> {
  const result = await apiRequest<{ data: T }>(path, { method, auth: 'required', session, body })
  if (result.data === undefined || result.data === null) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '监测设置返回了无法识别的数据。' })
  return result.data
}

export const getExecutionBudget = (session: AuthSessionSnapshot) => request<BudgetPolicy>('/api/v1/execution-budget-policy', 'GET', session)
export const putExecutionBudget = (body: components['schemas']['BudgetPolicyPut'], session: AuthSessionSnapshot) => request<BudgetPolicy>('/api/v1/execution-budget-policy', 'PUT', session, body)
export const previewMonitorAction = (id: string, body: MonitorRangeInput, session: AuthSessionSnapshot) => request<MonitorActionPreview>(`${conversationPath(id)}/geo-monitor-action-preview`, 'POST', session, body)
export async function listMonitorAuthorizations(id: string, session: AuthSessionSnapshot): Promise<MonitorAuthorization[]> {
  const result = await request<MonitorAuthorization[]>(`${conversationPath(id)}/geo-monitor-authorizations`, 'GET', session)
  if (!Array.isArray(result) || result.length > 1) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '监测授权返回了无法识别的数据。' })
  return result
}
export const createMonitorAuthorization = (id: string, body: components['schemas']['MonitorAuthorizationCreate'], session: AuthSessionSnapshot) => request<MonitorAuthorization>(`${conversationPath(id)}/geo-monitor-authorizations`, 'POST', session, body)
export const updateMonitorAuthorization = (id: string, grantId: string, body: components['schemas']['MonitorAuthorizationPut'], session: AuthSessionSnapshot) => request<MonitorAuthorization>(`${conversationPath(id)}/geo-monitor-authorizations/${encodeURIComponent(grantId)}`, 'PUT', session, body)
export const revokeMonitorAuthorization = (id: string, grantId: string, body: components['schemas']['VersionRequest'], session: AuthSessionSnapshot) => request<MonitorAuthorization>(`${conversationPath(id)}/geo-monitor-authorizations/${encodeURIComponent(grantId)}/revoke`, 'POST', session, body)
export const getMonitorAdmission = (id: string, runId: string, session: AuthSessionSnapshot) => request<MonitorAdmission>(`${conversationPath(id)}/runs/${encodeURIComponent(runId)}/geo-monitor-admission`, 'GET', session)

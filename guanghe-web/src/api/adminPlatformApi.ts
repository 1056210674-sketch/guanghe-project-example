import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiRequest } from './client'
import type { AuthSessionSnapshot } from '../auth/tokenStorage'

export type AdminAgentDefinition = components['schemas']['AdminAgentDefinitionView']
export type AdminGeoPlan = components['schemas']['AdminGeoPlanView']
export type AdminGeoPlanSlot = components['schemas']['AdminGeoPlanSlotView']
export type AdminKnowledgeMetadata = components['schemas']['AdminKnowledgeDocumentMetadata']
export type AdminPreferenceMetadata = components['schemas']['AdminMemoryPreferenceMetadata']
export type AdminPlatformSummary = components['schemas']['AdminPlatformMetadataSummary']
export type AdminDefinitionUpdate = components['schemas']['AdminUpdateAgentDefinitionRequest']
type PlanQuery = NonNullable<operations['adminListGeoPlans']['parameters']['query']>

async function request<T>(path: string, method: 'GET' | 'POST' | 'PATCH' = 'GET', body?: unknown, session?: AuthSessionSnapshot): Promise<T> {
  const response = await apiRequest<{ data: T }>(path, { method, auth: 'required', body, session })
  if (response.data === undefined || response.data === null) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '平台管理返回了无法识别的数据。' })
  return response.data
}
const queryString = (query: Record<string, string | number | undefined>) => {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) if (value !== undefined && value !== '') search.set(key, String(value))
  return `?${search}`
}
export const listAdminDefinitions = () => request<AdminAgentDefinition[]>('/api/v1/admin/agent-definitions')
export const updateAdminDefinition = (definition: AdminAgentDefinition, body: AdminDefinitionUpdate, session: AuthSessionSnapshot) => request<AdminAgentDefinition>(`/api/v1/admin/agent-definitions/${encodeURIComponent(definition.agentKey)}/versions/${encodeURIComponent(definition.version)}`, 'PATCH', body, session)
export const listAdminGeoPlans = (query: PlanQuery) => request<components['schemas']['AdminGeoPlanPage']>('/api/v1/admin/geo-plans' + queryString(query))
export const listAdminGeoPlanSlots = (id: string, page = 1) => request<components['schemas']['AdminGeoPlanSlotPage']>(`/api/v1/admin/geo-plans/${encodeURIComponent(id)}/slots?page=${page}&size=20`)
export const pauseAdminGeoPlan = (plan: AdminGeoPlan, reason: string, session: AuthSessionSnapshot) => request<AdminGeoPlan>(`/api/v1/admin/geo-plans/${encodeURIComponent(plan.id)}/pause`, 'POST', { version: plan.version, reason } satisfies components['schemas']['AdminPauseGeoPlanRequest'], session)
export const resumeAdminGeoPlan = (plan: AdminGeoPlan, session: AuthSessionSnapshot) => request<AdminGeoPlan>(`/api/v1/admin/geo-plans/${encodeURIComponent(plan.id)}/resume`, 'POST', { version: plan.version } satisfies components['schemas']['AdminResumeGeoPlanRequest'], session)
export const listAdminKnowledgeMetadata = (page = 1) => request<components['schemas']['AdminKnowledgeDocumentMetadataPage']>(`/api/v1/admin/knowledge-documents?page=${page}&size=20`)
export const listAdminPreferenceMetadata = (page = 1) => request<components['schemas']['AdminMemoryPreferenceMetadataPage']>(`/api/v1/admin/memory-preferences?page=${page}&size=20`)
export const getAdminPlatformSummary = () => request<AdminPlatformSummary>('/api/v1/admin/platform-metadata')

import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiRequest } from './client'
import type { AuthSessionSnapshot } from '../auth/tokenStorage'

export type AgentDefinition = components['schemas']['AgentDefinitionView']
export type AgentInstallation = components['schemas']['AgentInstallationView']
export type Project = components['schemas']['ProjectView']
export type AddAgentInstallationRequest = operations['addAgentInstallation']['requestBody']['content']['application/json']
export type UpdateAgentInstallationRequest = operations['updateAgentInstallation']['requestBody']['content']['application/json']
export type CreateProjectRequest = operations['createProject']['requestBody']['content']['application/json']
export type UpdateProjectRequest = components['schemas']['UpdateProjectRequest']
export type ProjectBusinessHistory = components['schemas']['ProjectBusinessHistory']
export type ProjectGeoTarget = components['schemas']['ProjectGeoTarget']
export type UpdateProjectGeoTargetRequest = components['schemas']['UpdateProjectGeoTargetRequest']

function dataOrThrow<T>(data: T | undefined, label: string): T {
  if (data === undefined || data === null) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: `${label}返回了无法识别的数据。` })
  return data
}

async function readList<T extends { data: unknown[] }>(path: string, label: string): Promise<T['data']> {
  const response = await apiRequest<T>(path, { method: 'GET', auth: 'required' })
  const data = dataOrThrow(response.data, label)
  if (!Array.isArray(data)) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: `${label}返回了无法识别的数据。` })
  return data
}

export const listAgents = () => readList<components['schemas']['AgentCatalogueApiResponse']>('/api/v1/agents', '智能体库')
export const listAgentInstallations = () => readList<components['schemas']['AgentInstallationListApiResponse']>('/api/v1/agent-installations', '我的智能体')
export const listProjects = () => readList<components['schemas']['ProjectListApiResponse']>('/api/v1/projects', '项目')

export async function addAgentInstallation(request: AddAgentInstallationRequest): Promise<AgentInstallation> {
  const response = await apiRequest<components['schemas']['AgentInstallationApiResponse']>('/api/v1/agent-installations', { method: 'POST', auth: 'required', body: request })
  return dataOrThrow(response.data, '添加智能体')
}

export async function updateAgentInstallation(id: string, request: UpdateAgentInstallationRequest): Promise<AgentInstallation> {
  const response = await apiRequest<components['schemas']['AgentInstallationApiResponse']>(`/api/v1/agent-installations/${encodeURIComponent(id)}`, { method: 'PATCH', auth: 'required', body: request })
  return dataOrThrow(response.data, '更新智能体')
}

export async function removeAgentInstallation(id: string): Promise<void> {
  await apiRequest(`/api/v1/agent-installations/${encodeURIComponent(id)}`, { method: 'DELETE', auth: 'required' })
}

export async function resolveCreativeStorage(id: string, session?: AuthSessionSnapshot): Promise<components['schemas']['CreativeStorageView']> {
  const response = await apiRequest<components['schemas']['CreativeStorageApiResponse']>(`/api/v1/agent-installations/${encodeURIComponent(id)}/creative-storage`, { method: 'POST', auth: 'required', session })
  return dataOrThrow(response.data, '个人创作素材')
}

export async function createProject(request: CreateProjectRequest): Promise<Project> {
  const response = await apiRequest<components['schemas']['ProjectApiResponse']>('/api/v1/projects', { method: 'POST', auth: 'required', body: request })
  return dataOrThrow(response.data, '创建项目')
}

export async function updateProject(id: string, request: UpdateProjectRequest): Promise<Project> {
  const response = await apiRequest<components['schemas']['ProjectApiResponse']>(`/api/v1/projects/${encodeURIComponent(id)}`, { method: 'PATCH', auth: 'required', body: request })
  return dataOrThrow(response.data, '更新项目')
}

export async function getProjectBusinessHistory(id: string): Promise<ProjectBusinessHistory> {
  const response = await apiRequest<{ data: ProjectBusinessHistory }>(`/api/v1/projects/${encodeURIComponent(id)}/business-history`, { method: 'GET', auth: 'required' })
  return dataOrThrow(response.data, '项目创作历史')
}

function ownedGeoTarget(data: ProjectGeoTarget | undefined, projectId: string): ProjectGeoTarget {
  const target = dataOrThrow(data, '项目监测对象')
  if (target.projectId !== projectId) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '监测对象的项目归属无法核对，请重新读取。' })
  return target
}

export async function getProjectGeoTarget(id: string, session?: AuthSessionSnapshot): Promise<ProjectGeoTarget> {
  const response = await apiRequest<components['schemas']['ProjectGeoTargetApiResponse']>(`/api/v1/projects/${encodeURIComponent(id)}/geo-target`, { method: 'GET', auth: 'required', session })
  return ownedGeoTarget(response.data, id)
}

export async function updateProjectGeoTarget(id: string, request: UpdateProjectGeoTargetRequest, session?: AuthSessionSnapshot): Promise<ProjectGeoTarget> {
  const response = await apiRequest<components['schemas']['ProjectGeoTargetApiResponse']>(`/api/v1/projects/${encodeURIComponent(id)}/geo-target`, { method: 'PATCH', auth: 'required', body: request, session })
  return ownedGeoTarget(response.data, id)
}

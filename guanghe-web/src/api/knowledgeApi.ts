import type { components } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiMultipartUpload, apiRequest } from './client'

export type KnowledgeDocument = components['schemas']['KnowledgeDocumentSummary']
export type KnowledgeDocumentDetail = components['schemas']['KnowledgeDocumentDetail']
export type KnowledgeDocumentPage = components['schemas']['KnowledgeDocumentPage']
export type MemoryPreference = components['schemas']['MemoryPreferenceView']
export type CreateKnowledgeDocumentRequest = components['schemas']['CreateKnowledgeDocumentRequest']
export type UpdateKnowledgeDocumentRequest = components['schemas']['UpdateKnowledgeDocumentRequest']
export type CreateMemoryPreferenceRequest = components['schemas']['CreateMemoryPreferenceRequest']
export type UpdateMemoryPreferenceRequest = components['schemas']['UpdateMemoryPreferenceRequest']
export type MemoryEntry = components['schemas']['MemoryEntry']
export type CreateMemoryEntryRequest = components['schemas']['CreateMemoryEntryRequest']
export type UpdateMemoryEntryRequest = components['schemas']['UpdateMemoryEntryRequest']

const knowledgePath = (projectId: string) => `/api/v1/projects/${encodeURIComponent(projectId)}/knowledge-documents`
const memoryPath = (id?: string) => `/api/v1/memory/preferences${id ? `/${encodeURIComponent(id)}` : ''}`
function requireData<T>(data: T | undefined): T {
  if (data === undefined || data === null) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '资料服务返回了无法识别的数据。' })
  return data
}
async function request<T>(path: string, method: 'GET' | 'POST' | 'PUT' | 'DELETE', body?: unknown): Promise<T> {
  const result = await apiRequest<{ data: T }>(path, { method, auth: 'required', body })
  return requireData(result.data)
}
export const listKnowledgeDocuments = (projectId: string, page = 1) => request<KnowledgeDocumentPage>(`${knowledgePath(projectId)}?page=${page}&size=20`, 'GET')
export const getKnowledgeDocument = (projectId: string, id: string) => request<KnowledgeDocumentDetail>(`${knowledgePath(projectId)}/${encodeURIComponent(id)}`, 'GET')
export const createKnowledgeDocument = (projectId: string, body: CreateKnowledgeDocumentRequest) => request<KnowledgeDocumentDetail>(knowledgePath(projectId), 'POST', body)
export const updateKnowledgeDocument = (projectId: string, id: string, body: UpdateKnowledgeDocumentRequest) => request<KnowledgeDocumentDetail>(`${knowledgePath(projectId)}/${encodeURIComponent(id)}`, 'PUT', body)
export const deleteKnowledgeDocument = async (projectId: string, id: string, version: number) => {
  await apiRequest(`${knowledgePath(projectId)}/${encodeURIComponent(id)}?version=${version}`, { method: 'DELETE', auth: 'required' })
}
export async function uploadKnowledgeDocument(projectId: string, file: File, name?: string, onProgress?: (percent: number) => void): Promise<KnowledgeDocumentDetail> {
  const formData = new FormData()
  formData.append('file', file)
  if (name?.trim()) formData.append('name', name.trim())
  const result = await apiMultipartUpload<{ data: KnowledgeDocumentDetail }>(`${knowledgePath(projectId)}/upload`, { formData, onUploadProgress: onProgress })
  return requireData(result.data)
}
export async function listMemoryPreferences(projectId?: string): Promise<MemoryPreference[]> {
  const result = await request<MemoryPreference[]>(memoryPath() + (projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''), 'GET')
  if (!Array.isArray(result)) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '记忆列表返回了无法识别的数据。' })
  return result
}
export const createMemoryPreference = (body: CreateMemoryPreferenceRequest) => request<MemoryPreference>(memoryPath(), 'POST', body)
export const updateMemoryPreference = (id: string, body: UpdateMemoryPreferenceRequest) => request<MemoryPreference>(memoryPath(id), 'PUT', body)
export const deleteMemoryPreference = async (id: string, version: number) => {
  await apiRequest(`${memoryPath(id)}?version=${version}`, { method: 'DELETE', auth: 'required' })
}

const entryPath = (id?: string) => `/api/v1/memory/entries${id ? `/${encodeURIComponent(id)}` : ''}`
function requireEntryScope(entry: MemoryEntry, scope: MemoryEntry['scope'], projectId?: string): MemoryEntry {
  if (!entry || entry.scope !== scope || entry.projectId !== (scope === 'PROJECT' ? projectId : null) || (scope === 'ACCOUNT' && entry.kind !== 'PREFERENCE')) {
    throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '记忆归属与当前选择不一致，请重新加载。' })
  }
  return entry
}
export async function listMemoryEntries(scope: MemoryEntry['scope'], projectId?: string, includeDeleted = false): Promise<MemoryEntry[]> {
  if (scope === 'PROJECT' && !projectId) throw new ApiError({ status: 400, code: 'PROJECT_REQUIRED', message: '请先选择项目。' })
  const query = new URLSearchParams({ scope, includeDeleted: String(includeDeleted), ...(scope === 'PROJECT' ? { projectId: projectId! } : {}) })
  const result = await request<MemoryEntry[]>(`${entryPath()}?${query}`, 'GET')
  if (!Array.isArray(result)) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '记忆列表返回了无法识别的数据。' })
  return result.map((entry) => requireEntryScope(entry, scope, projectId)).filter((entry) => includeDeleted || !entry.deleted)
}
export async function createMemoryEntry(body: CreateMemoryEntryRequest): Promise<MemoryEntry> {
  const result = requireEntryScope(await request<MemoryEntry>(entryPath(), 'POST', body), body.scope, body.projectId ?? undefined)
  if (result.deleted || result.key !== body.key || result.kind !== body.kind) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '记忆保存结果与当前内容不一致，请重新加载。' })
  return result
}
export async function updateMemoryEntry(entry: MemoryEntry, body: UpdateMemoryEntryRequest): Promise<MemoryEntry> {
  const result = requireEntryScope(await request<MemoryEntry>(entryPath(entry.id), 'PUT', body), entry.scope, entry.projectId ?? undefined)
  if (result.deleted || result.id !== entry.id || result.key !== entry.key || result.kind !== entry.kind) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '记忆更新结果与当前记录不一致，请重新加载。' })
  return result
}
export const deleteMemoryEntry = async (id: string, version: number) => {
  await apiRequest(`${entryPath(id)}?version=${version}`, { method: 'DELETE', auth: 'required' })
}

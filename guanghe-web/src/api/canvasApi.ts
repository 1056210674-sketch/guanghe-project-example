import type { components } from './generated/browser-api'
import { apiDownloadRequest, apiRequest } from './client'
import { ApiError } from './apiError'

export type CanvasDocument = components['schemas']['CanvasDocument']
export type CanvasNode = components['schemas']['CanvasNode']
export type CanvasView = components['schemas']['CanvasView']
export type CanvasSummary = components['schemas']['CanvasSummary']
export type CanvasGeneration = components['schemas']['CanvasGeneration']
export type CanvasReference = components['schemas']['CanvasReference']
export type CanvasBatch = components['schemas']['CanvasBatch']
export type CanvasOptimized = components['schemas']['CanvasOptimized']
export type CanvasCapabilities = components['schemas']['CanvasCapabilities']
const base = '/api/v1/creative/canvases'
const path = (id: string) => `${base}/${encodeURIComponent(id)}`
async function request<T>(url: string, method = 'GET', body?: unknown, key?: string): Promise<T> {
  const response = await apiRequest<{ data: T }>(url, { method, body, auth: 'required', headers: key ? { 'Idempotency-Key': key } : undefined })
  if (response.data == null) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '画布返回了无效的数据。' })
  return response.data
}
export const listCanvases = (page = 1) => request<CanvasSummary[]>(`${base}?page=${page}&size=24`)
export const getCanvasCapabilities = () => request<CanvasCapabilities>(`${base}/capabilities`)
export const downloadCanvasAssets = (id: string, assetIds: string[]) => {
  const params = new URLSearchParams(); assetIds.forEach(assetId => params.append('assetIds', assetId))
  return apiDownloadRequest(`${path(id)}/download?${params}`, 'application/zip')
}
export const getCanvas = (id: string) => request<CanvasView>(path(id))
export const createCanvas = (body: components['schemas']['CanvasCreate']) => request<CanvasView>(base, 'POST', body)
export const saveCanvas = (id: string, body: components['schemas']['CanvasSave']) => request<CanvasView>(path(id), 'PUT', body)
export const duplicateCanvas = (id: string) => request<CanvasView>(`${path(id)}/duplicate`, 'POST')
export const deleteCanvas = (id: string) => apiRequest(`${path(id)}`, { method: 'DELETE', auth: 'required' })
export const listCanvasBatches = (id: string, page = 1) => request<CanvasBatch[]>(`${path(id)}/batches?page=${page}&size=20`)
export const quoteCanvasBatch = (id: string, body: components['schemas']['CanvasQuote'], key: string) => request<CanvasBatch>(`${path(id)}/batches`, 'POST', body, key)
export const confirmCanvasBatch = (id: string, batch: string, quotedPoints: number) => request<CanvasBatch>(`${path(id)}/batches/${encodeURIComponent(batch)}/confirm`, 'POST', { quotedPoints })
export const optimizeCanvasPrompt = (id: string, body: components['schemas']['CanvasOptimize']) => request<CanvasOptimized>(`${path(id)}/optimize`, 'POST', body)

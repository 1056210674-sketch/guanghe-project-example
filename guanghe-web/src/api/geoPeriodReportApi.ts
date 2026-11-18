import type { components } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiDownloadRequest, apiRequest } from './client'
import type { AuthSessionSnapshot } from '../auth/tokenStorage'

export type GeoPeriodReport = components['schemas']['GeoPeriodReportDetail']
export type GeoPeriodReportSummary = components['schemas']['GeoPeriodReportSummary']
export type GeoPeriodReportCreate = components['schemas']['GeoPeriodReportCreate']
export type GeoPeriodReportPage = components['schemas']['GeoPeriodReportPage']
export type GeoPeriodReportPlatform = components['schemas']['GeoPeriodReportPlatform']
const base = '/api/v1/geo/period-reports'
const encoded = (id: string) => {
  if (!/^[1-9]\d{0,18}$/.test(id)) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', message: '报告标识无效。' })
  return encodeURIComponent(id)
}
async function request<T>(url: string, method: 'GET' | 'POST', session: AuthSessionSnapshot, body?: unknown): Promise<T> {
  const result = await apiRequest<{ data: T }>(url, { method, auth: 'required', session, body })
  if (result.data === undefined || result.data === null) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '报告服务返回了无法识别的数据。' })
  return result.data
}
export const createGeoPeriodReport = (body: GeoPeriodReportCreate, session: AuthSessionSnapshot) => request<GeoPeriodReport>(base, 'POST', session, body)
export const getGeoPeriodReport = (id: string, session: AuthSessionSnapshot) => request<GeoPeriodReport>(`${base}/${encoded(id)}`, 'GET', session)
export const listGeoPeriodReports = (projectId: string, page: number, session: AuthSessionSnapshot) => request<GeoPeriodReportPage>(`${base}?projectId=${encoded(projectId)}&page=${page}&size=10`, 'GET', session)
export const retryGeoPeriodReportSummary = (id: string, session: AuthSessionSnapshot) => request<GeoPeriodReport>(`${base}/${encoded(id)}/retry-summary`, 'POST', session)
export const exportGeoPeriodReportPdf = (id: string, version: number, session: AuthSessionSnapshot) => {
  if (!Number.isInteger(version) || version < 1) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', message: '报告版本无效，请重新读取报告。' })
  return apiDownloadRequest(`${base}/${encoded(id)}/export?version=${version}`, 'application/pdf', { session })
}

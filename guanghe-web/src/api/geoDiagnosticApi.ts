import type { components } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiRequest } from './client'
import type { AuthSessionSnapshot } from '../auth/tokenStorage'

export type GeoDiagnostic = components['schemas']['GeoDiagnosticDetail']
export type GeoDiagnosticProfile = components['schemas']['GeoDiagnosticProfile']
export type GeoDiagnosticCompetitor = components['schemas']['GeoDiagnosticCompetitor']
export type GeoDiagnosticQuestion = components['schemas']['GeoDiagnosticQuestion']
export type GeoDiagnosticCard = components['schemas']['GeoDiagnosticCard']
export type GeoDiagnosticSnapshot = components['schemas']['GeoDiagnosticSnapshot']
export type GeoDiagnosticQuote = components['schemas']['GeoDiagnosticQuote']
export type GeoDiagnosticCreate = components['schemas']['GeoDiagnosticCreateRequest']
export type GeoDiagnosticPatch = components['schemas']['GeoDiagnosticPatchRequest']
export type GeoDiagnosticGenerate = components['schemas']['GeoDiagnosticGenerateRequest']
export type GeoDiagnosticQuoteRequest = components['schemas']['GeoDiagnosticQuoteRequest']
export type GeoDiagnosticPage = NonNullable<components['schemas']['GeoDiagnosticListApiResponse']['data']>
export type GeoDiagnosticSubmit = components['schemas']['GeoDiagnosticSubmitRequest']
export type GeoDiagnosticReport = components['schemas']['GeoDiagnosticReportDetail']
export type GeoDiagnosticReportRetry = components['schemas']['GeoDiagnosticReportRetryRequest']
export type GeoDiagnosticObservationsPage = NonNullable<components['schemas']['GeoDiagnosticObservationsApiResponse']['data']>
export type GeoDiagnosticDownload = NonNullable<components['schemas']['GeoDiagnosticDownloadApiResponse']['data']>

const base = '/api/v1/geo/diagnostics'
const encoded = (id: string) => {
  if (!/^[1-9]\d{0,18}$/.test(id)) throw new ApiError({ status: 400, code: 'VALIDATION_FAILED', message: '诊断记录无效，请重新打开。' })
  return encodeURIComponent(id)
}

async function request<T>(url: string, method: 'GET' | 'POST' | 'PATCH', session: AuthSessionSnapshot, body?: unknown): Promise<T> {
  const result = await apiRequest<{ data: T }>(url, { method, auth: 'required', session, body })
  if (result.data === undefined || result.data === null) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '诊断服务返回了无法识别的数据。' })
  return result.data
}

export const createGeoDiagnostic = (body: GeoDiagnosticCreate, session: AuthSessionSnapshot) => request<GeoDiagnostic>(base, 'POST', session, body)
export const getGeoDiagnostic = (id: string, session: AuthSessionSnapshot) => request<GeoDiagnostic>(`${base}/${encoded(id)}`, 'GET', session)
export const listGeoDiagnostics = (conversationId: string, session: AuthSessionSnapshot) => request<GeoDiagnosticPage>(`${base}?conversationId=${encoded(conversationId)}&page=1&size=20`, 'GET', session)
export const patchGeoDiagnostic = (id: string, body: GeoDiagnosticPatch, session: AuthSessionSnapshot) => request<GeoDiagnostic>(`${base}/${encoded(id)}`, 'PATCH', session, body)
export const generateGeoDiagnostic = (id: string, body: GeoDiagnosticGenerate, session: AuthSessionSnapshot) => request<GeoDiagnostic>(`${base}/${encoded(id)}/generate`, 'POST', session, body)
export const quoteGeoDiagnostic = (id: string, body: GeoDiagnosticQuoteRequest, session: AuthSessionSnapshot) => request<GeoDiagnostic>(`${base}/${encoded(id)}/quote`, 'POST', session, body)
export const submitGeoDiagnostic = (id: string, body: GeoDiagnosticSubmit, session: AuthSessionSnapshot) => request<GeoDiagnostic>(`${base}/${encoded(id)}/submit`, 'POST', session, body)
export const getGeoDiagnosticReport = (id: string, session: AuthSessionSnapshot) => request<GeoDiagnosticReport>(`${base}/${encoded(id)}/report`, 'GET', session)
export const retryGeoDiagnosticReport = (id: string, body: GeoDiagnosticReportRetry, session: AuthSessionSnapshot) => request<GeoDiagnosticReport>(`${base}/${encoded(id)}/report/retry`, 'POST', session, body)
export const listGeoDiagnosticReports = (projectId: string, page: number, session: AuthSessionSnapshot) => request<GeoDiagnosticPage>(`${base}?projectId=${encoded(projectId)}&submittedOnly=true&page=${page}&size=20`, 'GET', session)
export const getGeoDiagnosticObservations = (id: string, page: number, session: AuthSessionSnapshot) => request<GeoDiagnosticObservationsPage>(`${base}/${encoded(id)}/report/observations?page=${page}&size=10`, 'GET', session)
export const getGeoDiagnosticDownload = (id: string, version: number, session: AuthSessionSnapshot) => request<GeoDiagnosticDownload>(`${base}/${encoded(id)}/report/download-url?version=${version}`, 'GET', session)

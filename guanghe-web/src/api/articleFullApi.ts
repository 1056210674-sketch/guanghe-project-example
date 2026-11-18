import type { components } from './generated/browser-api'
import { apiRequest } from './client'
import { ApiError } from './apiError'
import type { AuthSessionSnapshot } from '../auth/tokenStorage'
import type { MediaRecommendationSet } from './mediaApi'

export type ArticleAuthorization = components['schemas']['ArticleAuthorizationView']
export type ArticleAuthorizationCreate = components['schemas']['ArticleAuthorizationCreate']
export type ArticleFullStartRequest = components['schemas']['ArticleFullStartRequest']
export type ArticleFullPreparation = components['schemas']['ArticleFullPreparationView']
export type ArticleFullPreview = components['schemas']['ArticleFullPreview']
const path = (id: string) => `/api/v1/conversations/${encodeURIComponent(id)}`
async function request<T>(url: string, method: 'GET' | 'POST' | 'DELETE', session: AuthSessionSnapshot, body?: unknown, headers?: Record<string, string>): Promise<T> {
  const response = await apiRequest<{ data: T }>(url, { method, auth: 'required', session, body, headers })
  if (response.data === undefined || response.data === null) throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '文章服务返回了无法识别的数据。' })
  return response.data
}
export const listArticleAuthorizations = (id: string, session: AuthSessionSnapshot) => request<ArticleAuthorization[]>(`${path(id)}/article-authorizations`, 'GET', session)
export const createArticleAuthorization = (id: string, body: ArticleAuthorizationCreate, session: AuthSessionSnapshot) => request<ArticleAuthorization>(`${path(id)}/article-authorizations`, 'POST', session, body)
export const startArticleFullPreparation = (id: string, body: ArticleFullStartRequest, key: string, session: AuthSessionSnapshot) => request<ArticleFullPreparation>(`${path(id)}/article-full-preparations`, 'POST', session, body, { 'Idempotency-Key': key })
export const getArticleFullPreparation = (id: string, session: AuthSessionSnapshot) => request<ArticleFullPreparation>(`/api/v1/article-full-preparations/${encodeURIComponent(id)}`, 'GET', session)
export const getArticleFullPreview = (id: string, session: AuthSessionSnapshot) => request<ArticleFullPreview>(`/api/v1/article-full-preparations/${encodeURIComponent(id)}/preview`, 'GET', session)
export const listPreparationMediaRecommendations = (id: string, session: AuthSessionSnapshot) => request<MediaRecommendationSet>(`/api/v1/articles/${encodeURIComponent(id)}/preparation-media-recommendations`, 'GET', session)
export async function revokeArticleAuthorization(id: string, grant: ArticleAuthorization, session: AuthSessionSnapshot) {
  await apiRequest(`${path(id)}/article-authorizations/${encodeURIComponent(grant.id)}?version=${grant.version}`, { method: 'DELETE', auth: 'required', session })
}

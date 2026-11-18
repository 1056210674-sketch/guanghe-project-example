import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiRequest } from './client'

export type MediaResource = components['schemas']['MediaResource']
export type MediaArticlePricing = components['schemas']['MediaArticlePricing']
export type MediaResourcePage = components['schemas']['MediaResourcePage']
export type MediaRecommendation = components['schemas']['MediaRecommendation']
export type MediaRecommendationSet =
  components['schemas']['MediaRecommendationSet']
export type MediaSubmissionStatus =
  components['schemas']['MediaSubmissionStatus']
export type MediaSubmissionSummary =
  components['schemas']['MediaSubmissionSummary']
export type MediaSubmissionDetail =
  components['schemas']['MediaSubmissionDetail']
export type MediaSubmissionPage = components['schemas']['MediaSubmissionPage']
export type CreateMediaQuoteRequest =
  components['schemas']['CreateMediaQuoteRequest']
export type MediaQuoteDraft = components['schemas']['TaskDraft']
export type MediaResourceListQuery = NonNullable<
  operations['listMediaResources']['parameters']['query']
>
export type MediaSubmissionListQuery = NonNullable<
  operations['listMediaSubmissions']['parameters']['query']
>

type MediaResourceApiResponse =
  components['schemas']['MediaResourceApiResponse']
type MediaResourcePageApiResponse =
  components['schemas']['MediaResourcePageApiResponse']
type MediaRecommendationSetApiResponse =
  components['schemas']['MediaRecommendationSetApiResponse']
type MediaSubmissionApiResponse =
  components['schemas']['MediaSubmissionApiResponse']
type MediaSubmissionPageApiResponse =
  components['schemas']['MediaSubmissionPageApiResponse']
type TaskDraftApiResponse = components['schemas']['TaskDraftApiResponse']

function requireData<T>(data: T | undefined, resource: string): T {
  if (data === undefined) {
    throw new ApiError({
      status: 200,
      code: 'INVALID_RESPONSE',
      message: `${resource}返回了无法识别的数据。`,
    })
  }
  return data
}

function encodedId(value: string): string {
  return encodeURIComponent(value)
}

function mediaResourceListPath(query: MediaResourceListQuery): string {
  const search = new URLSearchParams()
  if (query.search !== undefined) search.set('search', query.search)
  if (query.modelId !== undefined) search.set('modelId', query.modelId)
  if (query.type !== undefined) search.set('type', query.type)
  if (query.status !== undefined) search.set('status', query.status)
  if (query.minSaleAmountCents !== undefined) {
    search.set('minSaleAmountCents', String(query.minSaleAmountCents))
  }
  if (query.maxSaleAmountCents !== undefined) {
    search.set('maxSaleAmountCents', String(query.maxSaleAmountCents))
  }
  if (query.page !== undefined) search.set('page', String(query.page))
  if (query.size !== undefined) search.set('size', String(query.size))
  const value = search.toString()
  return value
    ? `/api/v1/media/resources?${value}`
    : '/api/v1/media/resources'
}

function mediaSubmissionListPath(query: MediaSubmissionListQuery): string {
  const search = new URLSearchParams()
  if (query.status !== undefined) search.set('status', query.status)
  if (query.taskId !== undefined) search.set('taskId', query.taskId)
  if (query.page !== undefined) search.set('page', String(query.page))
  if (query.size !== undefined) search.set('size', String(query.size))
  const value = search.toString()
  return value
    ? `/api/v1/media/submissions?${value}`
    : '/api/v1/media/submissions'
}

export async function listMediaResources(
  query: MediaResourceListQuery = {},
): Promise<MediaResourcePage> {
  const response = await apiRequest<MediaResourcePageApiResponse>(
    mediaResourceListPath(query),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '媒体资源列表')
}

export async function getMediaResource(
  resourceId: string,
): Promise<MediaResource> {
  const response = await apiRequest<MediaResourceApiResponse>(
    `/api/v1/media/resources/${encodedId(resourceId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '媒体资源')
}

export async function listArticleMediaRecommendations(
  articleId: string,
  limit = 5,
): Promise<MediaRecommendationSet> {
  const search = new URLSearchParams({ articleId, limit: String(limit) })
  const response = await apiRequest<MediaRecommendationSetApiResponse>(
    `/api/v1/media/recommendations?${search.toString()}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '文章投稿媒体推荐')
}

export async function createMediaQuote(
  request: CreateMediaQuoteRequest,
  idempotencyKey: string,
): Promise<MediaQuoteDraft> {
  const response = await apiRequest<TaskDraftApiResponse>(
    '/api/v1/media/quotes',
    {
      method: 'POST',
      auth: 'required',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: request,
    },
  )
  return requireData(response.data, '媒体投稿报价')
}

export async function listMediaSubmissions(
  query: MediaSubmissionListQuery = {},
): Promise<MediaSubmissionPage> {
  const response = await apiRequest<MediaSubmissionPageApiResponse>(
    mediaSubmissionListPath(query),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '媒体投稿列表')
}

export async function getMediaSubmission(
  submissionId: string,
): Promise<MediaSubmissionDetail> {
  const response = await apiRequest<MediaSubmissionApiResponse>(
    `/api/v1/media/submissions/${encodedId(submissionId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '媒体投稿详情')
}

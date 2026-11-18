import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiDownloadRequest, apiRequest } from './client'

export type GeoTopicId = components['schemas']['BusinessId']
export type GeoCompetitiveInsights = components['schemas']['GeoCompetitiveInsights']
export type GeoCompetitorCandidate = components['schemas']['GeoCompetitorCandidate']
export type GeoCompetitorCandidatePage = components['schemas']['GeoCompetitorCandidatePage']
export type ConfirmGeoCompetitorCandidateRequest = components['schemas']['GeoCompetitorConfirmRequest']
export type IgnoreGeoCompetitorCandidateRequest = components['schemas']['GeoCompetitorIgnoreRequest']
export type GeoCompetitiveInsightsQuery = NonNullable<operations['getGeoCompetitiveInsights']['parameters']['query']>
export type GeoCompetitorCandidatesQuery = NonNullable<operations['listGeoCompetitorCandidates']['parameters']['query']>
export type GeoVisibilityTrend = components['schemas']['GeoVisibilityTrend']
export type GeoVisibilityTrendPoint = components['schemas']['GeoVisibilityTrendPoint']
export type GeoVisibilityTrendQuery = NonNullable<operations['getGeoVisibilityTrend']['parameters']['query']>
export type GeoTopic = components['schemas']['GeoTopic']
export type GeoTopicSummary = components['schemas']['GeoTopicSummary']
export type GeoTopicPage = components['schemas']['GeoTopicPage']
export type GeoKeyword = components['schemas']['GeoKeyword']
export type GeoKeywordInput = components['schemas']['GeoKeywordInput']
export type GeoQuestion = components['schemas']['GeoQuestion']
export type GeoQuestionInput = components['schemas']['GeoQuestionInput']
export type CreateGeoTopicRequest = components['schemas']['CreateGeoTopicRequest']
export type UpdateGeoTopicRequest = components['schemas']['UpdateGeoTopicRequest']
export type ReplaceGeoKeywordsRequest =
  components['schemas']['ReplaceGeoKeywordsRequest']
export type ReplaceGeoQuestionsRequest =
  components['schemas']['ReplaceGeoQuestionsRequest']
export type ReplaceGeoCompetitorsRequest =
  components['schemas']['ReplaceGeoCompetitorsRequest']
export type GeoTopicListQuery = NonNullable<
  operations['listGeoTopics']['parameters']['query']
>
export type GeoPlatform = components['schemas']['GeoPlatform']
export type GeoPlanId = components['schemas']['BusinessId']
export type GeoPlanSchedule = components['schemas']['GeoPlanSchedule']
export type CreateGeoPlanDraftRequest =
  components['schemas']['CreateGeoPlanDraftRequest']
export type GeoPlanDraft = components['schemas']['GeoPlanDraft']
export type ConfirmGeoPlanRequest = components['schemas']['ConfirmGeoPlanRequest']
export type GeoPlanVersionRequest = components['schemas']['GeoPlanVersionRequest']
export type UpdateGeoPlanRequest = components['schemas']['UpdateGeoPlanRequest']
export type GeoPlan = components['schemas']['GeoPlan']
export type GeoPlanPage = Omit<components['schemas']['GeoPlanPage'], 'items'> & {
  items: GeoPlan[]
}
export type GeoPlanListQuery = NonNullable<
  operations['listGeoPlans']['parameters']['query']
>
export type GeoRunId = components['schemas']['BusinessId']
export type GeoRunSummary = components['schemas']['GeoRunSummary']
export type GeoRun = components['schemas']['GeoRun']
export type GeoRunProgress = components['schemas']['GeoRunProgress']
export type GeoRunPage = Omit<components['schemas']['GeoRunPage'], 'items'> & {
  items: GeoRunSummary[]
}
export type GeoReportId = components['schemas']['BusinessId']
export type GeoReportSummary = components['schemas']['GeoReportSummary']
export type GeoReport = components['schemas']['GeoReport']
export type GeoReportInterpretation = components['schemas']['GeoReportInterpretation']
export type GeoCreativePlanRequest = components['schemas']['GeoCreativePlanRequest']
export type GeoCreativePlanResponse = components['schemas']['GeoCreativePlanResponse']
export type GeoCreativePlan = components['schemas']['GeoCreativePlan']
export type CreateGeoArticleWorkflowRequest =
  components['schemas']['CreateGeoArticleWorkflowRequest']
export type GeoArticleWorkflow = components['schemas']['GeoArticleWorkflow']
export type GeoReportPage = Omit<components['schemas']['GeoReportPage'], 'items'> & {
  items: GeoReportSummary[]
}
export type GeoRunListQuery = NonNullable<
  operations['listGeoRuns']['parameters']['query']
>
export type GeoReportListQuery = NonNullable<
  operations['listGeoReports']['parameters']['query']
>

type GeoTopicApiResponse = components['schemas']['GeoTopicApiResponse']
type GeoTopicPageApiResponse =
  components['schemas']['GeoTopicPageApiResponse']
type EmptySuccessApiResponse = components['schemas']['EmptySuccessApiResponse']
type GeoPlatformListApiResponse =
  components['schemas']['GeoPlatformListApiResponse']
type GeoPlanDraftApiResponse = components['schemas']['GeoPlanDraftApiResponse']
type GeoPlanApiResponse = components['schemas']['GeoPlanApiResponse']
type GeoPlanPageApiResponse = components['schemas']['GeoPlanPageApiResponse']
type GeoRunPageApiResponse = components['schemas']['GeoRunPageApiResponse']
type GeoRunApiResponse = components['schemas']['GeoRunApiResponse']
type GeoRunProgressApiResponse =
  components['schemas']['GeoRunProgressApiResponse']
type GeoReportPageApiResponse =
  components['schemas']['GeoReportPageApiResponse']
type GeoReportApiResponse = components['schemas']['GeoReportApiResponse']
type GeoCreativePlanApiResponse = components['schemas']['GeoCreativePlanApiResponse']
type GeoReportSummaryApiResponse = components['schemas']['GeoReportSummaryApiResponse']
type GeoArticleWorkflowApiResponse =
  components['schemas']['GeoArticleWorkflowApiResponse']

function encodedId(id: GeoTopicId): string {
  return encodeURIComponent(id)
}

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

function withPageQuery(
  path: string,
  query: object,
): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined) search.set(key, String(value))
  }
  const queryString = search.toString()
  return queryString ? `${path}?${queryString}` : path
}

function withTopicQuery(query: GeoTopicListQuery): string {
  const search = new URLSearchParams()

  if (query.spaceId) search.set('spaceId', query.spaceId)
  if (query.productId) search.set('productId', query.productId)
  if (query.keyword) search.set('keyword', query.keyword)
  if (query.page !== undefined) search.set('page', String(query.page))
  if (query.size !== undefined) search.set('size', String(query.size))

  const queryString = search.toString()
  return queryString
    ? `/api/v1/geo/topics?${queryString}`
    : '/api/v1/geo/topics'
}

export async function listGeoTopics(
  query: GeoTopicListQuery = {},
): Promise<GeoTopicPage> {
  const response = await apiRequest<GeoTopicPageApiResponse>(
    withTopicQuery(query),
    { method: 'GET', auth: 'required' },
  )
  return response.data
}

export async function getGeoTopic(topicId: GeoTopicId): Promise<GeoTopic> {
  const response = await apiRequest<GeoTopicApiResponse>(
    `/api/v1/geo/topics/${encodedId(topicId)}`,
    { method: 'GET', auth: 'required' },
  )
  return response.data
}

export async function getGeoCompetitiveInsights(reportId: GeoReportId, query: GeoCompetitiveInsightsQuery = {}) {
  const response = await apiRequest<components['schemas']['GeoCompetitiveInsightsApiResponse']>(
    withPageQuery(`/api/v1/geo/reports/${encodedId(reportId)}/competitive-insights`, query),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '竞品洞察')
}

export async function analyzeGeoCompetitiveInsights(reportId: GeoReportId) {
  const response = await apiRequest<components['schemas']['GeoCompetitiveInsightsApiResponse']>(
    `/api/v1/geo/reports/${encodedId(reportId)}/competitive-insights/analyze`,
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data, '竞品分析状态')
}

export async function listGeoCompetitorCandidates(topicId: GeoTopicId, query: GeoCompetitorCandidatesQuery = {}) {
  const response = await apiRequest<components['schemas']['GeoCompetitorCandidatePageApiResponse']>(
    withPageQuery(`/api/v1/geo/topics/${encodedId(topicId)}/competitor-candidates`, query),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '竞品候选池')
}

export async function confirmGeoCompetitorCandidate(candidateId: string, request: ConfirmGeoCompetitorCandidateRequest) {
  const response = await apiRequest<components['schemas']['GeoCompetitorConfirmationApiResponse']>(
    `/api/v1/geo/competitor-candidates/${encodedId(candidateId)}/confirm`,
    { method: 'POST', auth: 'required', body: request },
  )
  return requireData(response.data, '竞品确认结果')
}

export async function ignoreGeoCompetitorCandidate(candidateId: string, request: IgnoreGeoCompetitorCandidateRequest) {
  const response = await apiRequest<components['schemas']['GeoCompetitorCandidateApiResponse']>(
    `/api/v1/geo/competitor-candidates/${encodedId(candidateId)}/ignore`,
    { method: 'POST', auth: 'required', body: request },
  )
  return requireData(response.data, '竞品忽略结果')
}

export async function getGeoVisibilityTrend(topicId: GeoTopicId, query: GeoVisibilityTrendQuery = {}): Promise<GeoVisibilityTrend> {
  const response = await apiRequest<components['schemas']['GeoVisibilityTrendApiResponse']>(
    withPageQuery(`/api/v1/geo/topics/${encodedId(topicId)}/visibility-trend`, query),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, 'GEO 持续监测')
}

export async function createGeoTopic(
  request: CreateGeoTopicRequest,
): Promise<GeoTopic> {
  const response = await apiRequest<GeoTopicApiResponse>(
    '/api/v1/geo/topics',
    { method: 'POST', auth: 'required', body: request },
  )
  return response.data
}

export async function updateGeoTopic(
  topicId: GeoTopicId,
  request: UpdateGeoTopicRequest,
): Promise<GeoTopic> {
  const response = await apiRequest<GeoTopicApiResponse>(
    `/api/v1/geo/topics/${encodedId(topicId)}`,
    { method: 'PATCH', auth: 'required', body: request },
  )
  return response.data
}

export async function deleteGeoTopic(topicId: GeoTopicId): Promise<void> {
  await apiRequest<EmptySuccessApiResponse>(
    `/api/v1/geo/topics/${encodedId(topicId)}`,
    { method: 'DELETE', auth: 'required' },
  )
}

export async function replaceGeoTopicKeywords(
  topicId: GeoTopicId,
  request: ReplaceGeoKeywordsRequest,
): Promise<GeoTopic> {
  const response = await apiRequest<GeoTopicApiResponse>(
    `/api/v1/geo/topics/${encodedId(topicId)}/keywords`,
    { method: 'PUT', auth: 'required', body: request },
  )
  return response.data
}

export async function replaceGeoTopicQuestions(
  topicId: GeoTopicId,
  request: ReplaceGeoQuestionsRequest,
): Promise<GeoTopic> {
  const response = await apiRequest<GeoTopicApiResponse>(
    `/api/v1/geo/topics/${encodedId(topicId)}/questions`,
    { method: 'PUT', auth: 'required', body: request },
  )
  return response.data
}

export async function replaceGeoTopicCompetitors(
  topicId: GeoTopicId,
  request: ReplaceGeoCompetitorsRequest,
): Promise<GeoTopic> {
  const response = await apiRequest<GeoTopicApiResponse>(
    `/api/v1/geo/topics/${encodedId(topicId)}/competitors`,
    { method: 'PUT', auth: 'required', body: request },
  )
  return response.data
}

export async function listGeoPlatforms(): Promise<GeoPlatform[]> {
  const response = await apiRequest<GeoPlatformListApiResponse>(
    '/api/v1/geo/platforms',
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, 'GEO 平台列表')
}

export async function createGeoPlanDraft(
  request: CreateGeoPlanDraftRequest,
  idempotencyKey: string,
): Promise<GeoPlanDraft> {
  const response = await apiRequest<GeoPlanDraftApiResponse>(
    '/api/v1/geo/plan-drafts',
    {
      method: 'POST',
      auth: 'required',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: request,
    },
  )
  return requireData(response.data, 'GEO 计划授权草稿')
}

export async function confirmGeoPlan(
  request: ConfirmGeoPlanRequest,
  idempotencyKey: string,
): Promise<GeoPlan> {
  const response = await apiRequest<GeoPlanApiResponse>(
    '/api/v1/geo/plans',
    {
      method: 'POST',
      auth: 'required',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: request,
    },
  )
  return requireData(response.data, 'GEO 自动监测计划')
}

export async function listGeoPlans(
  query: GeoPlanListQuery = {},
): Promise<GeoPlanPage> {
  const response = await apiRequest<GeoPlanPageApiResponse>(
    withPageQuery('/api/v1/geo/plans', query),
    { method: 'GET', auth: 'required' },
  )
  const data = requireData(response.data, 'GEO 自动监测计划列表')
  return {
    ...data,
    items: Array.isArray(data.items) ? data.items as GeoPlan[] : [],
  }
}

export async function updateGeoPlan(
  planId: GeoPlanId,
  request: UpdateGeoPlanRequest,
): Promise<GeoPlan> {
  const response = await apiRequest<GeoPlanApiResponse>(
    `/api/v1/geo/plans/${encodedId(planId)}`,
    { method: 'PATCH', auth: 'required', body: request },
  )
  return requireData(response.data, 'GEO 自动监测计划')
}

export async function pauseGeoPlan(
  planId: GeoPlanId,
  request: GeoPlanVersionRequest,
): Promise<GeoPlan> {
  const response = await apiRequest<GeoPlanApiResponse>(
    `/api/v1/geo/plans/${encodedId(planId)}/pause`,
    { method: 'POST', auth: 'required', body: request },
  )
  return requireData(response.data, 'GEO 自动监测计划')
}

export async function resumeGeoPlan(
  planId: GeoPlanId,
  request: GeoPlanVersionRequest,
): Promise<GeoPlan> {
  const response = await apiRequest<GeoPlanApiResponse>(
    `/api/v1/geo/plans/${encodedId(planId)}/resume`,
    { method: 'POST', auth: 'required', body: request },
  )
  return requireData(response.data, 'GEO 自动监测计划')
}

export async function listGeoRuns(
  query: GeoRunListQuery = {},
): Promise<GeoRunPage> {
  const response = await apiRequest<GeoRunPageApiResponse>(
    withPageQuery('/api/v1/geo/runs', query),
    { method: 'GET', auth: 'required' },
  )
  const data = requireData(response.data, 'GEO 运行记录')
  return {
    ...data,
    items: Array.isArray(data.items) ? data.items as GeoRunSummary[] : [],
  }
}

export async function getGeoRun(runId: GeoRunId): Promise<GeoRun> {
  const response = await apiRequest<GeoRunApiResponse>(
    `/api/v1/geo/runs/${encodedId(runId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, 'GEO 运行详情')
}

export async function getGeoRunProgress(
  runId: GeoRunId,
): Promise<GeoRunProgress> {
  const response = await apiRequest<GeoRunProgressApiResponse>(
    `/api/v1/geo/runs/${encodedId(runId)}/progress`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, 'GEO 运行进度')
}

export async function listGeoReports(
  query: GeoReportListQuery = {},
): Promise<GeoReportPage> {
  const response = await apiRequest<GeoReportPageApiResponse>(
    withPageQuery('/api/v1/geo/reports', query),
    { method: 'GET', auth: 'required' },
  )
  const data = requireData(response.data, 'GEO 报告列表')
  return {
    ...data,
    items: Array.isArray(data.items) ? data.items as GeoReportSummary[] : [],
  }
}

export async function getGeoReport(
  reportId: GeoReportId,
): Promise<GeoReport> {
  const response = await apiRequest<GeoReportApiResponse>(
    `/api/v1/geo/reports/${encodedId(reportId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, 'GEO 报告详情')
}

export function exportGeoReport(reportId: GeoReportId, format: 'md' | 'pdf'): Promise<Blob> {
  return apiDownloadRequest(`/api/v1/geo/reports/${encodedId(reportId)}/export?format=${format}`, format === 'pdf' ? 'application/pdf' : 'text/markdown')
}

export async function reinterpretGeoReport(
  reportId: GeoReportId,
): Promise<GeoReportInterpretation> {
  const response = await apiRequest<components['schemas']['GeoReportInterpretationApiResponse']>(
    `/api/v1/geo/reports/${encodedId(reportId)}/reinterpretation`,
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data, 'GEO 报告解读状态')
}

export async function createGeoCreativePlan(
  reportId: GeoReportId,
  request: GeoCreativePlanRequest,
): Promise<GeoCreativePlanResponse> {
  const response = await apiRequest<GeoCreativePlanApiResponse>(
    `/api/v1/geo/reports/${encodedId(reportId)}/creative-plans`,
    { method: 'POST', auth: 'required', body: request },
  )
  return requireData(response.data, 'GEO 创意方案')
}

async function changeGeoReportArchive(
  reportId: GeoReportId,
  action: 'archive' | 'restore',
): Promise<GeoReportSummary> {
  const response = await apiRequest<GeoReportSummaryApiResponse>(
    `/api/v1/geo/reports/${encodedId(reportId)}/${action}`,
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data, 'GEO 报告归档状态')
}

export const archiveGeoReport = (reportId: GeoReportId) =>
  changeGeoReportArchive(reportId, 'archive')

export const restoreGeoReport = (reportId: GeoReportId) =>
  changeGeoReportArchive(reportId, 'restore')

export async function createGeoArticleWorkflow(
  reportId: GeoReportId,
  request: CreateGeoArticleWorkflowRequest,
  idempotencyKey: string,
): Promise<GeoArticleWorkflow> {
  const response = await apiRequest<GeoArticleWorkflowApiResponse>(
    `/api/v1/geo/reports/${encodedId(reportId)}/article-workflows`,
    {
      method: 'POST',
      auth: 'required',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: request,
    },
  )
  return requireData(response.data, 'GEO 文章工作流')
}

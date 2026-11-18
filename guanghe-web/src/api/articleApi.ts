import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiRequest } from './client'

export type ArticleId = components['schemas']['BusinessId']
export type ArticleStatus = components['schemas']['ArticleStatus']
export type Article = components['schemas']['Article']
export type ArticleSummary = components['schemas']['ArticleSummary']
export type ArticlePage = components['schemas']['ArticlePage']
export type ArticleOutlineItem = components['schemas']['ArticleOutlineItem']
export type ArticleOutlineInput = components['schemas']['ArticleOutlineInput']
export type ArticleAssetInput = components['schemas']['ArticleAssetInput']
export type ArticleSubmissionSnapshot =
  components['schemas']['ArticleSubmissionSnapshot']
export type CreateArticleRequest = components['schemas']['CreateArticleRequest']
export type UpdateArticleRequest = components['schemas']['UpdateArticleRequest']
export type SaveArticleToAssetsRequest = components['schemas']['SaveArticleToAssetsRequest']
export type ReplaceArticleOutlineRequest =
  components['schemas']['ReplaceArticleOutlineRequest']
export type ReplaceArticleAssetsRequest =
  components['schemas']['ReplaceArticleAssetsRequest']
export type ArticleWorkflowStage = components['schemas']['ArticleWorkflowStage']
export type ArticleTitleSource = components['schemas']['ArticleTitleSource']
export type ArticleTitleCandidateBatch =
  components['schemas']['ArticleTitleCandidateBatch']
export type ArticleConfirmedTitle =
  components['schemas']['ArticleConfirmedTitle']
export type ArticleCreativePlan = components['schemas']['ArticleCreativePlan']
export type ArticleCreativePlanRevision =
  components['schemas']['ArticleCreativePlanRevision']
export type ArticleCreativePlanInput =
  components['schemas']['ArticleCreativePlanInput']
export type ConfirmArticleTitleRequest =
  components['schemas']['ConfirmArticleTitleRequest']
export type SaveArticleCreativePlanRequest =
  components['schemas']['SaveArticleCreativePlanRequest']
export type ConfirmArticleCreativePlanRequest =
  components['schemas']['ConfirmArticleCreativePlanRequest']
export type ConfirmArticleBodyRequest =
  components['schemas']['ConfirmArticleBodyRequest']
export type CreateArticleSnapshotRequest =
  components['schemas']['CreateArticleSnapshotRequest']
export type ArticleListQuery = NonNullable<
  operations['listArticles']['parameters']['query']
>

type ArticleApiResponse = components['schemas']['ArticleApiResponse']
type ArticlePageApiResponse = components['schemas']['ArticlePageApiResponse']
type ArticleSnapshotApiResponse =
  components['schemas']['ArticleSnapshotApiResponse']
type EmptySuccessApiResponse = components['schemas']['EmptySuccessApiResponse']

function encodedId(value: string): string {
  return encodeURIComponent(value)
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

function articleListPath(query: ArticleListQuery): string {
  const search = new URLSearchParams()
  if (query.spaceId !== undefined) search.set('spaceId', query.spaceId)
  if (query.productId !== undefined) search.set('productId', query.productId)
  if (query.status !== undefined) search.set('status', query.status)
  if (query.savedToAssets !== undefined) search.set('savedToAssets', String(query.savedToAssets))
  if (query.keyword !== undefined) search.set('keyword', query.keyword)
  if (query.page !== undefined) search.set('page', String(query.page))
  if (query.size !== undefined) search.set('size', String(query.size))
  const value = search.toString()
  return value ? `/api/v1/articles?${value}` : '/api/v1/articles'
}

export async function listArticles(
  query: ArticleListQuery = {},
): Promise<ArticlePage> {
  const response = await apiRequest<ArticlePageApiResponse>(
    articleListPath(query),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '文章列表')
}

export async function createArticle(
  request: CreateArticleRequest,
): Promise<Article> {
  const response = await apiRequest<ArticleApiResponse>('/api/v1/articles', {
    method: 'POST',
    auth: 'required',
    body: request,
  })
  return requireData(response.data, '文章')
}

export async function getArticle(articleId: ArticleId): Promise<Article> {
  const response = await apiRequest<ArticleApiResponse>(
    `/api/v1/articles/${encodedId(articleId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '文章')
}

export async function updateArticle(
  articleId: ArticleId,
  request: UpdateArticleRequest,
): Promise<Article> {
  const response = await apiRequest<ArticleApiResponse>(
    `/api/v1/articles/${encodedId(articleId)}`,
    {
      method: 'PATCH',
      auth: 'required',
      headers: { 'If-Match': `"${request.version}"` },
      body: request,
    },
  )
  return requireData(response.data, '文章')
}

export async function deleteArticle(articleId: ArticleId): Promise<void> {
  await apiRequest<EmptySuccessApiResponse>(
    `/api/v1/articles/${encodedId(articleId)}`,
    { method: 'DELETE', auth: 'required' },
  )
}

export async function saveArticleToAssetCollection(articleId: ArticleId, request: SaveArticleToAssetsRequest): Promise<Article> {
  const response = await apiRequest<ArticleApiResponse>(`/api/v1/articles/${encodedId(articleId)}/asset-collection`, { method: 'PUT', auth: 'required', body: request })
  return requireData(response.data, '保存文章')
}

export async function replaceArticleOutline(
  articleId: ArticleId,
  request: ReplaceArticleOutlineRequest,
): Promise<Article> {
  const response = await apiRequest<ArticleApiResponse>(
    `/api/v1/articles/${encodedId(articleId)}/outline`,
    { method: 'PUT', auth: 'required', body: request },
  )
  return requireData(response.data, '文章创作方案')
}

export async function replaceArticleAssets(
  articleId: ArticleId,
  request: ReplaceArticleAssetsRequest,
): Promise<Article> {
  const response = await apiRequest<ArticleApiResponse>(
    `/api/v1/articles/${encodedId(articleId)}/assets`,
    { method: 'PUT', auth: 'required', body: request },
  )
  return requireData(response.data, '文章配图')
}

export async function confirmArticleTitle(
  articleId: ArticleId,
  request: ConfirmArticleTitleRequest,
): Promise<Article> {
  const response = await apiRequest<ArticleApiResponse>(
    `/api/v1/articles/${encodedId(articleId)}/workflow/title`,
    { method: 'PUT', auth: 'required', body: request },
  )
  return requireData(response.data, '文章标题确认结果')
}

export async function saveArticleCreativePlan(
  articleId: ArticleId,
  request: SaveArticleCreativePlanRequest,
): Promise<Article> {
  const response = await apiRequest<ArticleApiResponse>(
    `/api/v1/articles/${encodedId(articleId)}/workflow/creative-plan`,
    { method: 'PUT', auth: 'required', body: request },
  )
  return requireData(response.data, '文章创作方案')
}

export async function confirmArticleCreativePlan(
  articleId: ArticleId,
  request: ConfirmArticleCreativePlanRequest,
): Promise<Article> {
  const response = await apiRequest<ArticleApiResponse>(
    `/api/v1/articles/${encodedId(articleId)}/workflow/creative-plan/confirm`,
    { method: 'POST', auth: 'required', body: request },
  )
  return requireData(response.data, '文章创作方案确认结果')
}

export async function confirmArticleBody(
  articleId: ArticleId,
  request: ConfirmArticleBodyRequest,
): Promise<Article> {
  const response = await apiRequest<ArticleApiResponse>(
    `/api/v1/articles/${encodedId(articleId)}/workflow/body/confirm`,
    { method: 'POST', auth: 'required', body: request },
  )
  return requireData(response.data, '文章正文确认结果')
}

export async function createArticleSubmissionSnapshot(
  articleId: ArticleId,
  request: CreateArticleSnapshotRequest,
): Promise<ArticleSubmissionSnapshot> {
  const response = await apiRequest<ArticleSnapshotApiResponse>(
    `/api/v1/articles/${encodedId(articleId)}/submission-snapshots`,
    { method: 'POST', auth: 'required', body: request },
  )
  return requireData(response.data, '文章投稿快照')
}

export async function getArticleSubmissionSnapshot(
  articleId: ArticleId,
  snapshotId: ArticleId,
): Promise<ArticleSubmissionSnapshot> {
  const response = await apiRequest<ArticleSnapshotApiResponse>(
    `/api/v1/articles/${encodedId(articleId)}/submission-snapshots/${encodedId(snapshotId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '文章投稿快照')
}

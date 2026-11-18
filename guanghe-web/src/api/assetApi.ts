import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiMultipartUpload, apiRequest } from './client'

export type AssetId = components['schemas']['BusinessId']
export type AssetSummary = components['schemas']['AssetSummary']
export type Asset = components['schemas']['Asset']
export type AssetPage = components['schemas']['AssetPage']
export type AssetDownloadUrl = components['schemas']['AssetDownloadUrl']
export type UploadedImageAsset = components['schemas']['UploadedImageAsset']
export type AssetListQuery = NonNullable<
  operations['listAssets']['parameters']['query']
>

type AssetPageApiResponse = components['schemas']['AssetPageApiResponse']
type AssetApiResponse = components['schemas']['AssetApiResponse']
type AssetDownloadUrlApiResponse =
  components['schemas']['AssetDownloadUrlApiResponse']
type UploadedImageAssetApiResponse =
  components['schemas']['UploadedImageAssetApiResponse']

export interface UploadImageAssetInput {
  spaceId: string
  file: File
  name?: string
  idempotencyKey: string
  onUploadProgress?: (percent: number) => void
}

function encodedId(assetId: AssetId): string {
  return encodeURIComponent(assetId)
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

function assetListPath(query: AssetListQuery): string {
  const search = new URLSearchParams()
  if (query.page !== undefined) search.set('page', String(query.page))
  if (query.size !== undefined) search.set('size', String(query.size))
  if (query.type !== undefined) search.set('type', query.type)
  if (query.spaceId !== undefined) search.set('spaceId', query.spaceId)
  if (query.productId !== undefined) search.set('productId', query.productId)
  if (query.sourceType !== undefined) search.set('sourceType', query.sourceType)
  const queryString = search.toString()
  return queryString ? `/api/v1/assets?${queryString}` : '/api/v1/assets'
}

export async function listAssets(
  query: AssetListQuery = {},
): Promise<AssetPage> {
  const response = await apiRequest<AssetPageApiResponse>(assetListPath(query), {
    method: 'GET',
    auth: 'required',
  })
  return requireData(response.data, '资产列表')
}

export async function getAsset(assetId: AssetId): Promise<Asset> {
  const response = await apiRequest<AssetApiResponse>(
    `/api/v1/assets/${encodedId(assetId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '资产详情')
}

function isUsableHttpsUrl(value: string): boolean {
  try {
    return new URL(value).protocol === 'https:'
  } catch {
    return false
  }
}

export async function createAssetDownloadUrl(
  assetId: AssetId,
): Promise<AssetDownloadUrl> {
  const response = await apiRequest<AssetDownloadUrlApiResponse>(
    `/api/v1/assets/${encodedId(assetId)}/download-url`,
    { method: 'GET', auth: 'required' },
  )
  const result = requireData(response.data, '资产临时链接')
  if (!isUsableHttpsUrl(result.url) || !Number.isFinite(Date.parse(result.expiresAt))) {
    throw new ApiError({
      status: 200,
      code: 'INVALID_RESPONSE',
      message: '资产临时链接格式无效。',
    })
  }
  return result
}

export async function uploadImageAsset({
  spaceId,
  file,
  name,
  idempotencyKey,
  onUploadProgress,
}: UploadImageAssetInput): Promise<UploadedImageAsset> {
  if (!['image/png', 'image/jpeg'].includes(file.type)) {
    throw new ApiError({
      status: 422,
      code: 'VALIDATION_FAILED',
      message: '仅支持 PNG 或 JPEG 图片。',
    })
  }
  if (file.size > 20 * 1024 * 1024) {
    throw new ApiError({
      status: 422,
      code: 'VALIDATION_FAILED',
      message: '单张图片不能超过 20 MiB。',
    })
  }
  const formData = new FormData()
  formData.set('spaceId', spaceId)
  const normalizedName = name?.trim()
  if (normalizedName) formData.set('name', normalizedName)
  formData.set('file', file)
  const response = await apiMultipartUpload<UploadedImageAssetApiResponse>(
    '/api/v1/assets/images',
    {
      formData,
      headers: { 'Idempotency-Key': idempotencyKey },
      onUploadProgress,
    },
  )
  return requireData(response.data, '图片上传')
}

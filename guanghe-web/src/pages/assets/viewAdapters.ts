import type { Asset, AssetSummary } from '../../api/assetApi'
import { isApiError } from '../../api/apiError'
import type { AssetItem, AssetViewError } from '../../features/assets'

export function toAssetViewError(error: unknown): AssetViewError | null {
  if (!error) return null
  if (isApiError(error)) {
    return { message: error.message, traceId: error.traceId }
  }
  return { message: '资产数据加载失败，请稍后重试。' }
}

export function toAssetItem(
  asset: AssetSummary | Asset,
  context: { businessSpaceName?: string; productName?: string } = {},
): AssetItem {
  const productId = 'primaryProductId' in asset
    ? asset.primaryProductId ?? undefined
    : undefined
  return {
    id: asset.id,
    name: asset.name,
    kind: asset.assetType,
    status: asset.status,
    safetyStatus: asset.safetyStatus,
    source: asset.sourceType,
    createdAt: asset.createdAt,
    updatedAt: asset.updatedAt,
    contentType: asset.contentType,
    sizeBytes: asset.sizeBytes,
    sha256: asset.sha256,
    width: asset.width ?? undefined,
    height: asset.height ?? undefined,
    durationSeconds:
      'durationSeconds' in asset ? asset.durationSeconds ?? undefined : undefined,
    businessSpaceId: asset.spaceId,
    businessSpaceName: context.businessSpaceName,
    productId,
    productName: context.productName,
    sourceTaskId: asset.sourceTaskId ?? undefined,
  }
}

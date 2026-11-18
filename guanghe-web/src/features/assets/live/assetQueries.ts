import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createAssetDownloadUrl,
  getAsset,
  listAssets,
  uploadImageAsset,
} from '../../../api/assetApi'
import { ApiError } from '../../../api/apiError'
import type {
  Asset,
  AssetDownloadUrl,
  AssetId,
  AssetListQuery,
  AssetSummary,
  UploadImageAssetInput,
} from '../../../api/assetApi'
import { useAuthStore } from '../../../stores/authStore'

const ASSET_URL_EXPIRY_MARGIN_MS = 30_000
const ASSET_URL_MAX_CACHE_MS = 5 * 60_000
const ALL_ASSET_PAGE_SIZE = 100

export type AllAssetListQuery = Omit<AssetListQuery, 'page' | 'size'>

function createAssetKeys(ownerId?: string | null) {
  const owner = ['assets', 'owner', ownerId ?? null] as const
  return {
    owner,
    list: (query: AssetListQuery) => [...owner, 'list', query] as const,
    allList: (query: AllAssetListQuery) =>
      [...owner, 'all-list', query] as const,
    detail: (assetId?: AssetId) => [...owner, 'detail', assetId ?? null] as const,
    downloadUrl: (assetId: AssetId) => [...owner, 'download-url', assetId] as const,
  }
}

export const assetKeys = {
  all: ['assets'] as const,
  forOwner: createAssetKeys,
}

function useAssetKeys() {
  const ownerId = useAuthStore((state) => state.user?.id)
  return createAssetKeys(ownerId)
}

function downloadUrlStaleTime(data: AssetDownloadUrl | undefined): number {
  if (!data) return 0
  const remaining = Date.parse(data.expiresAt) - Date.now() - ASSET_URL_EXPIRY_MARGIN_MS
  return Math.max(0, Math.min(remaining, ASSET_URL_MAX_CACHE_MS))
}

export function useAssets(query: AssetListQuery) {
  const keys = useAssetKeys()
  return useQuery({
    queryKey: keys.list(query),
    queryFn: () => listAssets(query),
  })
}

async function loadAllAssets(query: AllAssetListQuery): Promise<AssetSummary[]> {
  const assets: AssetSummary[] = []
  let page = 1

  while (true) {
    const result = await listAssets({
      ...query,
      page,
      size: ALL_ASSET_PAGE_SIZE,
    })
    assets.push(...result.items)

    if (!result.hasNext) return assets

    const advances =
      result.page === page &&
      result.size > 0 &&
      result.items.length > 0 &&
      result.page * result.size < result.total
    if (!advances) {
      throw new ApiError({
        status: 200,
        code: 'INVALID_RESPONSE',
        message: '资产分页返回异常，请刷新后重试。',
      })
    }

    page += 1
  }
}

export function useAllAssets(query: AllAssetListQuery, enabled = true) {
  const keys = useAssetKeys()
  return useQuery({
    queryKey: keys.allList(query),
    queryFn: () => loadAllAssets(query),
    enabled,
  })
}

export function useUploadImageAsset() {
  const keys = useAssetKeys()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: UploadImageAssetInput) => uploadImageAsset(input),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.owner })
    },
  })
}

export function useAsset(assetId?: AssetId) {
  const keys = useAssetKeys()
  return useQuery({
    queryKey: keys.detail(assetId),
    queryFn: () => getAsset(assetId as AssetId),
    enabled: Boolean(assetId),
  })
}

export function useAssetDetails(assetIds: readonly AssetId[]) {
  return useAssetDetailsState(assetIds).data
}

export function useAssetDetailsState(assetIds: readonly AssetId[]) {
  const keys = useAssetKeys()
  const uniqueIds = [...new Set(assetIds)]
  const results = useQueries({
    queries: uniqueIds.map((assetId) => ({
      queryKey: keys.detail(assetId),
      queryFn: () => getAsset(assetId),
      enabled: Boolean(assetId),
    })),
  })

  const data = uniqueIds.reduce<Record<string, Asset | undefined>>((map, assetId, index) => {
    map[assetId] = results[index]?.data
    return map
  }, {})
  return {
    data,
    isLoading: results.some((result) => result.isLoading),
    error: results.find((result) => result.error)?.error,
    refetch: () => Promise.all(results.map((result) => result.refetch())),
  }
}

export function useAssetDownloadUrls(assetIds: readonly AssetId[]) {
  const keys = useAssetKeys()
  const uniqueIds = [...new Set(assetIds)]
  const results = useQueries({
    queries: uniqueIds.map((assetId) => ({
      queryKey: keys.downloadUrl(assetId),
      queryFn: () => createAssetDownloadUrl(assetId),
      staleTime: (query: { state: { data?: AssetDownloadUrl } }) =>
        downloadUrlStaleTime(query.state.data),
      gcTime: ASSET_URL_MAX_CACHE_MS,
      refetchOnMount: 'always' as const,
      enabled: Boolean(assetId),
    })),
  })

  return uniqueIds.reduce<Record<string, AssetDownloadUrl | undefined>>(
    (map, assetId, index) => {
      const value = results[index]?.data
      map[assetId] = value && downloadUrlStaleTime(value) > 0 ? value : undefined
      return map
    },
    {},
  )
}

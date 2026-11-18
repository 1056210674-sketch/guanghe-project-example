import { useEffect, useMemo, useRef, useState } from 'react'
import { App, Tabs } from 'antd'
import { useSearchParams } from 'react-router-dom'
import { createAssetDownloadUrl } from '../../api/assetApi'
import { resolveCreativeStorage } from '../../api/agentPlatformApi'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import {
  AssetCenterWorkspace,
  useAsset,
  useAssetDownloadUrls,
  useAssets,
  useUploadImageAsset,
} from '../../features/assets'
import type { AssetFilterView } from '../../features/assets'
import { useAgentCatalog, useAgentInstallations, useProjects } from '../../features/agent-platform/queries'
import { supportsPersonalCreativeAssets } from '../../features/agent-platform/capabilities'
import { findInstalledAgentDefinition, isAgentInstallationAvailable } from '../../features/agent-platform/installedAgent'
import { openExternalHttps } from '../../features/shared/openExternalHttps'
import { UserShell } from '../../layouts/UserShell'
import {
  EMPTY_ASSET_STORAGE,
} from './pageDefaults'
import { toAssetItem, toAssetViewError } from './viewAdapters'
import { SavedArticlesContent } from './SavedArticlesContent'
import { useAuthStore } from '../../stores/authStore'

const DEFAULT_PAGE_SIZE = 20
const PERSONAL_SCOPE = 'personal-materials'

function safeAssetId(value: string | null): string | undefined {
  return value && /^[1-9]\d*$/.test(value) ? value : undefined
}

export function AssetCenterPage() {
  const [search, setSearch] = useSearchParams()
  const ownerId = useAuthStore((state) => state.user?.id)
  const { modal } = App.useApp()
  const changeTab = (tab: string) => {
    const change = () => setSearch((previous) => { const next = new URLSearchParams(previous); next.set('tab', tab); next.delete('article'); next.delete('asset'); return next }, { replace: true })
    if (document.querySelector('[data-article-editor-dirty="true"]')) modal.confirm({ title: '离开文章编辑？', content: '未保存的修改会暂存在当前浏览器标签页，再次编辑时可以恢复。', okText: '暂存并离开', cancelText: '继续编辑', onOk: change })
    else change()
  }
  return <UserShell><Tabs activeKey={search.get('tab') === 'articles' ? 'articles' : 'media'} onChange={changeTab} items={[{ key: 'media', label: '图片与视频' }, { key: 'articles', label: '文章' }]} />{search.get('tab') === 'articles' ? <SavedArticlesContent key={ownerId} /> : <MediaAssetsContent key={ownerId} />}</UserShell>
}

function MediaAssetsContent() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)
  const [filters, setFilters] = useState<AssetFilterView>({})
  const [openingAssetId, setOpeningAssetId] = useState<string>()
  const [actionError, setActionError] = useState<ReturnType<typeof toAssetViewError>>(null)
  const [personalSpaceId, setPersonalSpaceId] = useState<string>()
  const [pageSession] = useState(captureAuthSession)
  const active = useRef(true)
  const filterRequest = useRef(0)
  const personalRequest = useRef<Promise<string> | null>(null)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  const current = () => active.current && isCurrentAuthSession(pageSession)
  const uploadImage = useUploadImageAsset()
  const selectedAssetId = safeAssetId(searchParams.get('asset'))
  const listQuery = useAssets({ page, size: pageSize, ...filters })
  const detailQuery = useAsset(selectedAssetId)
  const spacesQuery = useProjects()
  const installations = useAgentInstallations()
  const catalog = useAgentCatalog()
  const creativeInstallation = !installations.isError && !catalog.isError ? installations.data?.find((item) =>
    isAgentInstallationAvailable(item, catalog.data)
    && supportsPersonalCreativeAssets(findInstalledAgentDefinition(item, catalog.data)?.capabilities),
  ) : undefined
  const projectOptions = (spacesQuery.isError ? [] : spacesQuery.data ?? []).map((project) => ({ value: project.id, label: project.name + (project.status === 'ARCHIVED' ? '（已归档）' : '') }))
  const personalOptions = creativeInstallation ? [{ value: PERSONAL_SCOPE, label: '个人素材' }] : []
  const ensurePersonalStorage = async () => {
    if (!creativeInstallation || !current()) throw new Error('请先添加具有生图或生视频能力的智能体，再使用个人素材上传。')
    if (personalSpaceId) return personalSpaceId
    if (!personalRequest.current) personalRequest.current = resolveCreativeStorage(creativeInstallation.id, pageSession).then((result) => {
      if (!current()) throw new Error('登录状态已变化，已停止操作。')
      setPersonalSpaceId(result.spaceId)
      return result.spaceId
    }).catch((error: unknown) => { personalRequest.current = null; throw error })
    return personalRequest.current
  }

  const spaceNames = useMemo(
    () => new Map<string, string>([
      ...(spacesQuery.isError ? [] : spacesQuery.data ?? []).map((space): [string, string] => [space.id, space.name]),
      ...(personalSpaceId ? [[personalSpaceId, '个人素材'] as [string, string]] : []),
    ]),
    [spacesQuery.data, spacesQuery.isError, personalSpaceId],
  )
  const assetItems = useMemo(
    () => (listQuery.data?.items ?? []).map((asset) => toAssetItem(asset, {
      businessSpaceName: spaceNames.get(asset.spaceId),
    })),
    [listQuery.data?.items, spaceNames],
  )
  const detailAsset = detailQuery.data
    ? toAssetItem(detailQuery.data, {
        businessSpaceName: spaceNames.get(detailQuery.data.spaceId),
      })
    : null
  const previewAssetIds = useMemo(
    () => [...new Set([
      ...assetItems
        .filter(
          (asset) =>
            (asset.kind === 'IMAGE' || asset.kind === 'VIDEO') &&
            asset.status === 'READY',
        )
        .map((asset) => asset.id),
      ...((detailAsset?.kind === 'IMAGE' || detailAsset?.kind === 'VIDEO') &&
      detailAsset.status === 'READY'
        ? [detailAsset.id]
        : []),
    ])],
    [assetItems, detailAsset],
  )
  const previewDownloads = useAssetDownloadUrls(previewAssetIds)
  const previewUrls = Object.fromEntries(
    Object.entries(previewDownloads).map(([id, value]) => [id, value?.url]),
  )
  const previewExpiresAt = Object.fromEntries(
    Object.entries(previewDownloads).map(([id, value]) => [id, value?.expiresAt]),
  )

  const setSelectedAsset = (assetId?: string) => {
    const next = new URLSearchParams(searchParams)
    if (assetId) next.set('asset', assetId)
    else next.delete('asset')
    setSearchParams(next, { replace: true })
  }

  const handleOpenOriginal = async (assetId: string) => {
    setOpeningAssetId(assetId)
    setActionError(null)
    try {
      const download = await createAssetDownloadUrl(assetId)
      if (!openExternalHttps(download.url)) {
        setActionError({ message: '资产临时链接无法安全打开。' })
      }
    } catch (error) {
      setActionError(toAssetViewError(error))
    } finally {
      setOpeningAssetId(undefined)
    }
  }

  return (
      <AssetCenterWorkspace
        projectMode
        assets={assetItems}
        businessSpaces={[...personalOptions, ...projectOptions]}
        uploadScopes={[...personalOptions, ...projectOptions.filter((option) => spacesQuery.data?.find((project) => project.id === option.value)?.status === 'ACTIVE')]}
        products={[]}
        storage={EMPTY_ASSET_STORAGE}
        filters={{ ...filters, spaceId: personalSpaceId && filters.spaceId === personalSpaceId ? PERSONAL_SCOPE : filters.spaceId }}
        pagination={listQuery.data ?? {
          page,
          size: pageSize,
          total: 0,
          hasNext: false,
          items: [],
        }}
        loading={listQuery.isLoading || spacesQuery.isLoading}
        error={toAssetViewError(listQuery.error ?? spacesQuery.error)}
        detailOpen={Boolean(selectedAssetId)}
        detailAsset={detailAsset}
        detailLoading={detailQuery.isLoading}
        detailError={toAssetViewError(detailQuery.error)}
        previewUrlsByAssetId={previewUrls}
        previewExpiresAtByAssetId={previewExpiresAt}
        openingAssetId={openingAssetId}
        actionError={actionError}
        onFiltersChange={(nextFilters) => {
          const request = ++filterRequest.current
          const apply = (spaceId?: string) => {
            if (!current() || request !== filterRequest.current) return
            setFilters({ ...nextFilters, spaceId, productId: undefined })
            setPage(1)
            setSelectedAsset(undefined)
            setActionError(null)
          }
          if (nextFilters.spaceId === PERSONAL_SCOPE) void ensurePersonalStorage().then(apply).catch((error: unknown) => { if (current() && request === filterRequest.current) setActionError(toAssetViewError(error)) })
          else apply(nextFilters.spaceId)
        }}
        onPageChange={(nextPage, nextPageSize) => {
          setPage(nextPageSize === pageSize ? nextPage : 1)
          setPageSize(nextPageSize)
        }}
        onOpenDetail={setSelectedAsset}
        onCloseDetail={() => setSelectedAsset(undefined)}
        onOpenOriginal={(assetId) => void handleOpenOriginal(assetId)}
        onRetry={() => void Promise.all([
          listQuery.refetch(),
          spacesQuery.refetch(),
          installations.refetch(),
          catalog.refetch(),
        ])}
        onRetryDetail={() => void detailQuery.refetch()}
        onUploadImage={async ({ spaceId, file, name, onProgress }) => {
          const resolvedSpaceId = spaceId === PERSONAL_SCOPE ? await ensurePersonalStorage() : spaceId
          if (!current()) throw new Error('登录状态已变化，已停止上传。')
          if (resolvedSpaceId !== personalSpaceId && spaceId !== PERSONAL_SCOPE && !spacesQuery.data?.some((project) => project.id === resolvedSpaceId && project.status === 'ACTIVE')) throw new Error('请选择可用客户项目或个人素材；已归档项目不能添加图片。')
          await uploadImage.mutateAsync({
            spaceId: resolvedSpaceId,
            file,
            name,
            idempotencyKey: crypto.randomUUID(),
            onUploadProgress: onProgress,
          })
          await listQuery.refetch()
        }}
      />
  )
}

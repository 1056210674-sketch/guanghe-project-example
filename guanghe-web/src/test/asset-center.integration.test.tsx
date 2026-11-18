import { QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createAssetDownloadUrl,
  getAsset,
  listAssets,
} from '../api/assetApi'
import { listAgentInstallations, listAgents, listProjects } from '../api/agentPlatformApi'
import type { AssetItem } from '../features/assets'
import { createQueryClient } from '../app/queryClient'
import { AssetCenterWorkspace, ImageAssetPickerDialog, assetKeys } from '../features/assets'
import { APP_PATHS } from '../routes/paths'
import { DEV_PREVIEW_PATHS } from '../routes/devPreviewPaths'
import { useAuthStore } from '../stores/authStore'
import { userFixture } from './fixtures/auth'
import { renderApp } from './renderApp'

vi.mock('../api/assetApi', async () => {
  const actual = await vi.importActual<typeof import('../api/assetApi')>('../api/assetApi')
  return {
    ...actual,
    createAssetDownloadUrl: vi.fn(),
    getAsset: vi.fn(),
    listAssets: vi.fn(),
  }
})

vi.mock('../api/agentPlatformApi', async () => {
  const actual = await vi.importActual<typeof import('../api/agentPlatformApi')>('../api/agentPlatformApi')
  return { ...actual, listAgentInstallations: vi.fn(), listAgents: vi.fn(), listProjects: vi.fn() }
})

const readyAsset: AssetItem = {
  id: '90378333853843456',
  name: '永久生成图片.png',
  kind: 'IMAGE',
  status: 'READY',
  safetyStatus: 'TEST_BYPASS',
  source: 'GENERATED',
  createdAt: '2026-09-07T04:22:00Z',
  updatedAt: '2026-09-07T04:23:00Z',
  contentType: 'image/png',
  sizeBytes: 468446,
  sha256: '26bf7e36d039844cebe07b50b9ede918f9e0540aa429cbea952a28f8bf3947f4',
  width: 1024,
  height: 1024,
  businessSpaceId: '90299993499369472',
  businessSpaceName: '真实经营空间',
  productId: '90300589849706496',
  productName: '真实商品',
  sourceTaskId: '90300727817142272',
}

const readyVideoAsset: AssetItem = {
  ...readyAsset,
  id: '90436867673030656',
  name: '永久生成视频.mp4',
  kind: 'VIDEO',
  safetyStatus: 'APPROVED',
  contentType: 'video/mp4',
  width: 1280,
  height: 720,
  durationSeconds: '5.000',
}

const callbacks = {
  onFiltersChange: vi.fn(),
  onPageChange: vi.fn(),
  onOpenDetail: vi.fn(),
  onCloseDetail: vi.fn(),
  onOpenOriginal: vi.fn(),
  onRetry: vi.fn(),
  onRetryDetail: vi.fn(),
}

const emptyProps = {
  assets: [],
  businessSpaces: [],
  products: [],
  storage: {},
  filters: {},
  pagination: { page: 1, size: 20, total: 0, hasNext: false },
  loading: false,
  error: null,
  detailOpen: false,
  detailAsset: null,
  detailLoading: false,
  detailError: null,
  previewUrlsByAssetId: {},
  previewExpiresAtByAssetId: {},
  ...callbacks,
} as const

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listAgentInstallations).mockResolvedValue([])
  vi.mocked(listAgents).mockResolvedValue([])
  vi.mocked(listProjects).mockResolvedValue([])
  useAuthStore.setState({ status: 'anonymous', user: null, error: null })
})

describe('资产中心真实数据展示', () => {
  it('图片选择器跨页保留选择，普通重渲染不跳页，重开或换空间后重置', async () => {
    const user = userEvent.setup()
    const assetSummary = (id: string, name: string, spaceId: string) => ({
      id,
      assetType: 'IMAGE' as const,
      sourceType: 'UPLOADED' as const,
      status: 'READY' as const,
      safetyStatus: 'APPROVED' as const,
      name,
      spaceId,
      contentType: 'image/png',
      sizeBytes: 100,
      sha256: id.padEnd(64, '0').slice(0, 64),
      width: 800,
      height: 800,
      createdAt: '2026-09-17T10:00:00Z',
      updatedAt: '2026-09-17T10:00:00Z',
    })
    vi.mocked(listAssets).mockImplementation(async ({ page = 1, size = 20, spaceId } = {}) => ({
      items: [assetSummary(
        page === 1 ? `${spaceId}-page-1` : `${spaceId}-page-2`,
        page === 1 ? '第一页图片' : '第二页图片',
        String(spaceId),
      )],
      page,
      size,
      total: 21,
      hasNext: page === 1,
    }))
    vi.mocked(createAssetDownloadUrl).mockImplementation(async (assetId) => ({
      url: `https://asset.example/${assetId}`,
      expiresAt: '2099-01-01T00:00:00Z',
    }))
    const queryClient = createQueryClient()
    const view = (open: boolean, businessSpaceId: string, selectedAssetIds: readonly string[]) => (
      <QueryClientProvider client={queryClient}>
        <ImageAssetPickerDialog
          open={open}
          title="选择图片"
          businessSpaceId={businessSpaceId}
          selectedAssetIds={selectedAssetIds}
          onCancel={vi.fn()}
          onConfirm={vi.fn()}
        />
      </QueryClientProvider>
    )
    const { rerender } = render(view(true, 'space-1', ['space-1-page-1']))

    expect(await screen.findByRole('button', { name: /第一页图片/ })).toHaveAttribute('aria-pressed', 'true')
    await user.click(screen.getByTitle('2'))
    expect(await screen.findByRole('button', { name: /第二页图片/ })).toBeInTheDocument()
    expect(screen.getByTitle('2').closest('li')).toHaveClass('ant-pagination-item-active')

    rerender(view(true, 'space-1', ['space-1-page-1']))
    await waitFor(() => expect(screen.getByTitle('2').closest('li')).toHaveClass('ant-pagination-item-active'))

    rerender(view(false, 'space-1', ['space-1-page-1']))
    rerender(view(true, 'space-2', ['space-2-page-1']))
    expect(await screen.findByRole('button', { name: /第一页图片/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTitle('1').closest('li')).toHaveClass('ant-pagination-item-active')
  })

  it('保留分类筛选和真实空态，不再提供本地上传或 LOCAL_ONLY', () => {
    render(<AssetCenterWorkspace {...emptyProps} />)
    expect(screen.getByRole('heading', { name: '资产中心' })).toBeInTheDocument()
    expect(screen.getByText('暂无符合当前条件的永久资产')).toBeInTheDocument()
    expect(screen.queryByText('存储空间')).not.toBeInTheDocument()
    expect(screen.queryByText(/暂未获取/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '上传素材' })).not.toBeInTheDocument()
    expect(screen.queryByText(/本地待上传|接口尚未开放|LOCAL_ONLY/)).not.toBeInTheDocument()
  })

  it('文章和报告进入真实业务页面，不冒充不存在的素材筛选或数量', () => {
    render(<AssetCenterWorkspace {...emptyProps} />)
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['全部', '图片', '视频'])
    expect(screen.getByRole('link', { name: '文章库' })).toHaveAttribute('href', APP_PATHS.articles)
    expect(screen.getByRole('link', { name: 'GEO 历史报告' })).toHaveAttribute('href', APP_PATHS.geoReports)
    expect(screen.queryByText('本页文章与报告')).not.toBeInTheDocument()
    expect(callbacks.onFiltersChange).not.toHaveBeenCalled()
  })

  it('类型、经营空间、商品和来源筛选仅回传受控条件', async () => {
    const user = userEvent.setup()
    render(
      <AssetCenterWorkspace
        {...emptyProps}
        businessSpaces={[{ label: '空间一', value: 'space-1' }]}
        products={[{ label: '商品一', value: 'product-1' }]}
        filters={{ spaceId: 'space-1' }}
      />,
    )
    await user.click(screen.getByRole('tab', { name: '图片' }))
    expect(callbacks.onFiltersChange).toHaveBeenCalledWith({
      spaceId: 'space-1',
      type: 'IMAGE',
    })
    fireEvent.mouseDown(screen.getByLabelText('按商品筛选'))
    await user.click(await screen.findByText('商品一'))
    expect(callbacks.onFiltersChange).toHaveBeenCalledWith({
      spaceId: 'space-1',
      productId: 'product-1',
    })
    fireEvent.mouseDown(screen.getByLabelText('按来源筛选'))
    await user.click(await screen.findByText('任务生成'))
    expect(callbacks.onFiltersChange).toHaveBeenCalledWith({
      spaceId: 'space-1',
      sourceType: 'GENERATED',
    })
  })

  it('上传图片需要经营空间和文件，并把已选文件交给容器', async () => {
    const user = userEvent.setup()
    const onUploadImage = vi.fn().mockResolvedValue(undefined)
    render(
      <AssetCenterWorkspace
        {...emptyProps}
        businessSpaces={[{ label: '空间一', value: 'space-1' }]}
        onUploadImage={onUploadImage}
      />,
    )
    await user.click(screen.getByRole('button', { name: /上传图片/ }))
    expect(screen.getByText(/内容审核状态以资产详情为准/)).toBeInTheDocument()
    const file = new File(['image'], 'coffee.png', { type: 'image/png' })
    await user.upload(screen.getByLabelText('图片文件'), file)
    await user.click(screen.getByRole('button', { name: '开始上传' }))
    await waitFor(() => expect(onUploadImage).toHaveBeenCalledWith({
      spaceId: 'space-1',
      file,
      name: undefined,
      onProgress: expect.any(Function),
    }))
  })

  it('选择不支持的图片时在本地阻止上传并说明限制', async () => {
    const user = userEvent.setup()
    const onUploadImage = vi.fn().mockResolvedValue(undefined)
    render(
      <AssetCenterWorkspace
        {...emptyProps}
        businessSpaces={[{ label: '空间一', value: 'space-1' }]}
        onUploadImage={onUploadImage}
      />,
    )
    await user.click(screen.getByRole('button', { name: /上传图片/ }))
    const file = new File(
      [new Uint8Array(20 * 1024 * 1024 + 1)],
      'too-large.png',
      { type: 'image/png' },
    )
    await user.upload(screen.getByLabelText('图片文件'), file)

    expect(screen.getByText('单张图片不能超过 20 MiB。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '开始上传' })).toBeDisabled()
    expect(onUploadImage).not.toHaveBeenCalled()
  })

  it('详情展示真实元数据、安全状态和临时链接提示', async () => {
    const user = userEvent.setup()
    render(
      <AssetCenterWorkspace
        {...emptyProps}
        assets={[readyAsset]}
        pagination={{ page: 1, size: 20, total: 1, hasNext: false }}
        detailOpen
        detailAsset={readyAsset}
        previewUrlsByAssetId={{ [readyAsset.id]: 'https://asset.example/preview' }}
        previewExpiresAtByAssetId={{ [readyAsset.id]: '2026-09-07T05:00:00Z' }}
      />,
    )
    expect(await screen.findByText('1024 × 1024')).toBeInTheDocument()
    expect(screen.getByText('测试环境已跳过内容安全审核')).toBeInTheDocument()
    expect(screen.getByText(/临时链接有效期至/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '打开资产原图' }))
    expect(callbacks.onOpenOriginal).toHaveBeenCalledWith(readyAsset.id)
    expect(screen.queryByRole('button', { name: /删除资产|继续创作/ })).not.toBeInTheDocument()
  })

  it('未审核资产明确展示未审核状态', () => {
    const unreviewedAsset = { ...readyAsset, safetyStatus: 'NOT_REVIEWED' as const }
    render(
      <AssetCenterWorkspace
        {...emptyProps}
        assets={[unreviewedAsset]}
        pagination={{ page: 1, size: 20, total: 1, hasNext: false }}
        detailOpen
        detailAsset={unreviewedAsset}
      />,
    )
    expect(screen.getByText('未进行内容审核')).toBeInTheDocument()
    expect(screen.queryByText('内容安全审核通过')).not.toBeInTheDocument()
  })

  it('VIDEO 资产使用原生播放器并展示时长、类型和来源任务', async () => {
    const user = userEvent.setup()
    render(
      <AssetCenterWorkspace
        {...emptyProps}
        assets={[readyVideoAsset]}
        pagination={{ page: 1, size: 20, total: 1, hasNext: false }}
        detailOpen
        detailAsset={readyVideoAsset}
        previewUrlsByAssetId={{
          [readyVideoAsset.id]: 'https://asset.example/video.mp4?signature=test',
        }}
        previewExpiresAtByAssetId={{
          [readyVideoAsset.id]: '2099-01-01T00:00:00Z',
        }}
      />,
    )

    expect(screen.getAllByLabelText(`播放视频 ${readyVideoAsset.name}`)).toHaveLength(2)
    expect(screen.getByText('5.000 秒')).toBeInTheDocument()
    expect(screen.getAllByText('video/mp4').length).toBeGreaterThan(0)
    expect(screen.getByText(readyVideoAsset.sourceTaskId as string)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '打开资产原视频' }))
    expect(callbacks.onOpenOriginal).toHaveBeenCalledWith(readyVideoAsset.id)
  })

  it('生产路由从用户隔离 Query 获取真实列表，不写入生产 Fixture', async () => {
    useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
    vi.mocked(listProjects).mockResolvedValue([{
      id: readyAsset.businessSpaceId!,
      name: '资产所属客户项目',
      status: 'ACTIVE',
      version: 1,
      createdAt: readyAsset.createdAt,
      updatedAt: readyAsset.updatedAt,
    }])
    vi.mocked(listAssets).mockResolvedValue({
      items: [{
        id: readyAsset.id,
        assetType: 'IMAGE',
        sourceType: 'GENERATED',
        status: 'READY',
        safetyStatus: 'TEST_BYPASS',
        name: readyAsset.name,
        spaceId: readyAsset.businessSpaceId,
        primaryProductId: readyAsset.productId,
        contentType: readyAsset.contentType,
        sizeBytes: readyAsset.sizeBytes,
        sha256: readyAsset.sha256,
        width: readyAsset.width,
        height: readyAsset.height,
        sourceTaskId: readyAsset.sourceTaskId,
        createdAt: readyAsset.createdAt,
        updatedAt: readyAsset.updatedAt,
      }],
      page: 1,
      size: 20,
      total: 1,
      hasNext: false,
    })
    vi.mocked(createAssetDownloadUrl).mockResolvedValue({
      url: 'https://asset.example/preview',
      expiresAt: '2099-01-01T00:00:00Z',
    })
    vi.mocked(getAsset).mockResolvedValue({
      id: readyAsset.id,
      assetType: 'IMAGE',
      sourceType: 'GENERATED',
      status: 'READY',
      safetyStatus: 'TEST_BYPASS',
      name: readyAsset.name,
      spaceId: readyAsset.businessSpaceId,
      primaryProductId: readyAsset.productId,
      productIds: readyAsset.productId ? [readyAsset.productId] : [],
      contentType: readyAsset.contentType,
      sizeBytes: readyAsset.sizeBytes,
      sha256: readyAsset.sha256,
      width: readyAsset.width,
      height: readyAsset.height,
      sourceTaskId: readyAsset.sourceTaskId,
      createdAt: readyAsset.createdAt,
      updatedAt: readyAsset.updatedAt,
    })

    const { queryClient } = renderApp([APP_PATHS.assets])
    expect(await screen.findByText(readyAsset.name)).toBeInTheDocument()
    expect(listProjects).toHaveBeenCalled()
    expect(listAssets).toHaveBeenCalledWith({ page: 1, size: 20 })
    expect(
      queryClient.getQueryData(
        assetKeys.forOwner(userFixture.id).list({ page: 1, size: 20 }),
      ),
    ).toMatchObject({ total: 1 })
  })

  it('预览路由保留为空态且不请求真实 API', async () => {
    renderApp([DEV_PREVIEW_PATHS.assets])
    expect(await screen.findByRole('heading', { name: '资产中心' })).toBeInTheDocument()
    expect(listAssets).not.toHaveBeenCalled()
  })
})

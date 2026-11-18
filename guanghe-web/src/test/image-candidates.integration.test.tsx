import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PaidTask, PaidTaskAssetReference, TaskDraft } from '../api/conversationApi'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { storeAuthToken } from '../auth/tokenStorage'
import {
  generatedImageAssetReferences,
  toGeneratedImageResultGroup,
  toTaskExecutionOutcome,
} from '../features/chat'
import { storeImageCreationRecovery } from '../features/image-creation'
import { openExternalHttps } from '../features/shared/openExternalHttps'
import { toTaskCenterTaskDetail } from '../features/task-center'
import { storeVideoCreationRecovery } from '../features/video-creation/live/videoCreationRecovery'
import { AssetCenterPage } from '../pages/assets/AssetCenterPage'
import { ImageCreationPage } from '../pages/image-creation/ImageCreationPage'
import { TaskCenterPage } from '../pages/task-center/TaskCenterPage'
import { VideoCreationPage } from '../pages/video-creation/VideoCreationPage'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

vi.mock('../features/shared/openExternalHttps', () => ({ openExternalHttps: vi.fn(() => true) }))

const now = '2026-09-22T01:00:00Z'
const asset = (assetId: string): PaidTaskAssetReference => ({
  assetId, assetType: 'IMAGE', status: 'READY', contentType: 'image/png',
  sizeBytes: 1000, sha256: 'a'.repeat(64), width: 1024, height: 768,
  safetyStatus: 'APPROVED',
})
const candidates = [{ ...asset('101'), index: 1 }, { ...asset('102'), index: 3 }]
const partialTask: PaidTask = {
  id: '61', taskType: 'IMAGE', status: 'PARTIAL', stageCode: 'IMAGE_PARTIAL_COMPLETED',
  stageLabel: '图片已保存到资产中心', spaceId: '21', productId: '31',
  quotedPoints: 28, settledPoints: 14, frozenPoints: 0, errorCode: null, progress: null,
  createdAt: now, updatedAt: now,
  result: {
    provider: 'QWEN_IMAGE', model: 'qwen-image-3.0', providerCostReported: false,
    assets: candidates, requestedCount: 4, succeededCount: 2, failedCount: 2,
  },
}
const draft: TaskDraft = {
  id: '41', draftSource: 'FORM', taskType: 'IMAGE', parameterSchemaVersion: 1,
  status: 'WAITING_CONFIRMATION', spaceId: '21', productId: '31',
  sourceMode: 'CATALOG_PRODUCT', referenceAssetIds: ['81'],
  parameters: {
    requestText: '晨光厨房', mode: 'AGENT_ASSISTED', agentSummary: '晨光厨房',
    sourceMode: 'CATALOG_PRODUCT', referenceAssetIds: ['81'], scene: '晨光厨房',
    style: '写实', lighting: '柔光', aspectRatio: '4:3', count: 4, negativePrompt: '文字水印',
  },
  warnings: [], inputHash: 'a'.repeat(64), pricingVersionId: '51', quotedPoints: 28,
  confirmationToken: 'opaque-token', expiresAt: '2099-01-01T00:00:00Z', createdAt: now,
}

function ok(data: unknown) {
  return new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), {
    headers: { 'Content-Type': 'application/json' },
  })
}

function assetRecord(id: string) {
  return {
    ...asset(id), id, name: id === '81' ? '商品主图' : `候选图片 ${id}`,
    sourceType: id === '81' ? 'UPLOADED' : 'GENERATED', spaceId: '21',
    primaryProductId: '31', productIds: ['31'], sourceTaskId: id === '81' ? null : '61',
    createdAt: now, updatedAt: now,
  }
}

function installApi(task: PaidTask = partialTask, currentDraft: TaskDraft = draft) {
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    if (url === '/api/v1/billing/notifications/unread-count') return ok({ unreadCount: 0 })
    if (url === '/api/v1/spaces?page=1&size=100') {
      return ok({ items: [{ id: '21', name: '经营空间' }], page: 1, size: 100, total: 1, hasNext: false })
    }
    if (url === '/api/v1/spaces/21/products?page=1&size=100') {
      return ok({ items: [{ id: '31', spaceId: '21', name: '咖啡机' }], page: 1, size: 100, total: 1, hasNext: false })
    }
    if (url === '/api/v1/spaces/21') return ok({ id: '21', name: '经营空间' })
    if (url === '/api/v1/products/31') return ok({ id: '31', spaceId: '21', name: '咖啡机' })
    if (url === '/api/v1/products/31/identity-assets') {
      return ok({ productId: '31', version: 1, creativeReady: true,
        items: [{ assetId: '81', role: 'PRIMARY', sequence: 1, status: 'READY' }] })
    }
    if (url.startsWith('/api/v1/tasks?')) return ok({ items: [], page: 1, size: 20, total: 0, hasNext: false })
    if (url === '/api/v1/tasks/61') return ok(task)
    if (url === '/api/v1/tasks/61/events') return ok([])
    if (url === '/api/v1/task-drafts/41') return ok(currentDraft)
    if (url === '/api/v1/conversations/11') return ok({ id: '11', title: '图片创作' })
    if (url === '/api/v1/task-drafts/41/revisions' && init?.method === 'POST') {
      return ok({ ...currentDraft, id: '42', parameters: JSON.parse(String(init.body)).parameters })
    }
    if (url === '/api/v1/billing/point-account') {
      return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    }
    if (url.startsWith('/api/v1/assets?')) {
      return ok({ items: candidates.map(({ assetId }) => assetRecord(assetId)), page: 1, size: 20, total: 2, hasNext: false })
    }
    const download = url.match(/^\/api\/v1\/assets\/(81|101|102)\/download-url$/)
    if (download) return ok({ url: `https://assets.example/${download[1]}.png`, expiresAt: '2099-01-01T00:00:00Z' })
    const detail = url.match(/^\/api\/v1\/assets\/(81|101|102)$/)
    if (detail) return ok(assetRecord(detail[1]))
    throw new Error(`Unexpected Browser API request: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function renderPage(element: ReactElement, path = '/app/image-creation') {
  const queryClient = createQueryClient()
  return {
    queryClient,
    ...render(
      <ConfigProvider theme={appTheme}>
        <AntdApp><QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={[path]}>{element}</MemoryRouter>
        </QueryClientProvider></AntdApp>
      </ConfigProvider>,
    ),
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  window.localStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('多张图片候选', () => {
  it('保留全部永久资产及部分失败数量，兼容旧单图且不以旧字段覆盖空数组', () => {
    expect(generatedImageAssetReferences(partialTask).map(({ assetId }) => assetId)).toEqual(['101', '102'])
    expect(toTaskExecutionOutcome(partialTask).summary).toBe('共请求 4 张，已保存 2 张，未完成 2 张。')
    expect(toTaskCenterTaskDetail(partialTask).generatedImageResultGroup?.images).toHaveLength(2)
    expect(toGeneratedImageResultGroup(partialTask)?.images.map(({ candidateLabel }) => candidateLabel)).toEqual(['候选 1', '候选 3'])
    const legacy: PaidTask = { ...partialTask, status: 'SUCCEEDED', result: {
      provider: 'QWEN_IMAGE', model: 'qwen-image-3.0', providerCostReported: false, asset: candidates[0],
    } }
    expect(toGeneratedImageResultGroup(legacy)?.images.map(({ assetId }) => assetId)).toEqual(['101'])
    const completed: PaidTask = { ...partialTask, status: 'SUCCEEDED', result: {
      provider: 'QWEN_IMAGE', model: 'qwen-image-3.0', providerCostReported: false,
      assets: [asset('101'), asset('102'), asset('103'), asset('104')],
      requestedCount: 4, succeededCount: 4, failedCount: 0,
    } }
    expect(toGeneratedImageResultGroup(completed)?.images).toHaveLength(4)
    expect(toTaskExecutionOutcome(completed).summary).toBe('共请求 4 张，已保存 4 张，未完成 0 张。')
    const failed: PaidTask = { ...partialTask, status: 'FAILED', result: {
      provider: 'QWEN_IMAGE', model: 'qwen-image-3.0', providerCostReported: false,
      assets: [], asset: candidates[0], requestedCount: 4, succeededCount: 0, failedCount: 4,
    } }
    expect(toGeneratedImageResultGroup(failed)).toBeUndefined()
    expect(toTaskCenterTaskDetail(failed).resultSummary?.summary).toBe('共请求 4 张，已保存 0 张，未完成 4 张。')
  })

  it('图片创作页展示每张候选独立的短效图片和下载入口', async () => {
    const user = userEvent.setup()
    installApi()
    storeImageCreationRecovery(userFixture.id, { confirmationAttempt: {
      draftId: '41', inputHash: 'a'.repeat(64), pricingVersionId: '51', idempotencyKey: 'confirm-key', taskId: '61',
    } })
    renderPage(<ImageCreationPage />)
    // Recovery reads the task first, then resolves each asset and its short-lived URL.
    // Await those stages explicitly rather than imposing a 1s mount-to-image budget.
    const gallery = await screen.findByRole('region', { name: '图片生成结果：图片生成结果' }, { timeout: 5_000 })
    for (const id of ['101', '102']) {
      expect(await within(gallery).findByRole('img', { name: `候选图片 ${id}生成图片` }, { timeout: 5_000 })).toHaveAttribute('src', `https://assets.example/${id}.png`)
    }
    expect(screen.getByText('共请求 4 张，已保存 2 张，未完成 2 张。')).toBeInTheDocument()
    const cards = within(gallery).getAllByRole('listitem')
    await user.click(within(cards[1]).getByRole('button', { name: '打开原图' }))
    expect(openExternalHttps).toHaveBeenCalledWith('https://assets.example/102.png')
    await user.click(within(cards[0]).getByRole('button', { name: '预览' }))
    expect(openExternalHttps).toHaveBeenCalledWith('https://assets.example/101.png')
  }, 15_000)

  it('任务详情也加载全部候选的短效链接，第二张可单独下载', async () => {
    const user = userEvent.setup()
    installApi()
    renderPage(<Routes><Route path="/app/tasks/:taskId" element={<TaskCenterPage />} /></Routes>, '/app/tasks/61')
    expect(await screen.findByRole('img', { name: '候选图片 102生成图片' })).toHaveAttribute('src', 'https://assets.example/102.png')
    const gallery = screen.getByRole('region', { name: '图片生成结果：图片生成结果' })
    expect(within(gallery).getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getByText('共请求 4 张，已保存 2 张，未完成 2 张。')).toBeInTheDocument()
    await user.click(within(within(gallery).getAllByRole('listitem')[1]).getByRole('button', { name: '打开原图' }))
    expect(openExternalHttps).toHaveBeenCalledWith('https://assets.example/102.png')
  })

  it('资产中心不会把同任务的多张图片折叠，第二张可以查看和下载', async () => {
    const user = userEvent.setup()
    installApi()
    renderPage(<AssetCenterPage />, '/app/assets')
    expect(await screen.findByRole('button', { name: '查看资产 候选图片 101' })).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: '查看资产 候选图片 102' }))
    await user.click(await screen.findByRole('button', { name: '打开资产原图' }))
    expect(openExternalHttps).toHaveBeenCalledWith('https://assets.example/102.png')
  })

  it('刷新后恢复全部参数，重新编辑和报价保留未修改字段，后台刷新不覆盖修改', async () => {
    const user = userEvent.setup()
    const fetchMock = installApi()
    storeImageCreationRecovery(userFixture.id, {
      conversationId: '11', draftAttempt: { fingerprint: 'saved', idempotencyKey: 'draft-key', draftId: '41' },
    })
    const { queryClient } = renderPage(<ImageCreationPage />)
    await waitFor(() => expect(screen.getByLabelText('画面场景')).toHaveValue('晨光厨房'))
    expect(screen.getByLabelText('风格')).toHaveValue('写实')
    expect(screen.getByLabelText('光线')).toHaveValue('柔光')
    expect(screen.getByLabelText('不希望出现')).toHaveValue('文字水印')
    expect(screen.getByText('4 张')).toBeInTheDocument()
    expect(screen.getAllByText('4:3').length).toBeGreaterThan(0)
    await user.click(await screen.findByRole('button', { name: '返回修改' }))
    await user.clear(screen.getByLabelText('画面场景'))
    await user.type(screen.getByLabelText('画面场景'), '午后厨房')
    await queryClient.invalidateQueries({ queryKey: ['image-creation', userFixture.id, 'draft', '41'] })
    expect(screen.getByLabelText('画面场景')).toHaveValue('午后厨房')
    await user.click(screen.getByLabelText('数量'))
    await user.click(screen.getByText('3 张'))
    await user.click(screen.getByRole('button', { name: /获取报价并确认/ }))
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/41/revisions'))).toBe(true))
    const call = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/41/revisions'))
    expect(JSON.parse(String(call?.[1]?.body)).parameters).toMatchObject({
      scene: '午后厨房', style: '写实', lighting: '柔光', aspectRatio: '4:3', count: 3, negativePrompt: '文字水印',
    })
    expect(await screen.findByRole('button', { name: '确认开始 · 28 积分' })).toBeEnabled()
    expect(screen.getAllByText(/28 积分/).length).toBeGreaterThan(0)
    await user.click(screen.getByLabelText('数量'))
    await user.click(screen.getByText('2 张'))
    expect(screen.queryByRole('button', { name: /^确认开始 · \d+ 积分$/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /获取报价并确认/ })).toBeEnabled()
  })

  it('视频刷新后恢复全部生成参数，恢复草稿的后台刷新保留用户修改', async () => {
    const user = userEvent.setup()
    const videoDraft: TaskDraft = { ...draft, taskType: 'VIDEO', parameters: {
      requestText: '咖啡机旋转', mode: 'AGENT_ASSISTED', agentSummary: '咖啡机旋转',
      sourceMode: 'CATALOG_PRODUCT', referenceAssetIds: ['81'], scene: '咖啡机旋转',
      style: '电影感', durationSeconds: 10, aspectRatio: '16:9', cameraMotion: '推进',
      resolution: '1080P', audio: true, seed: 42, promptExtend: false, watermark: true,
    } }
    installApi(partialTask, videoDraft)
    storeVideoCreationRecovery(userFixture.id, {
      conversationId: '11', draftAttempt: { fingerprint: 'saved', idempotencyKey: 'draft-key', draftId: '41' },
    })
    const { queryClient } = renderPage(<VideoCreationPage />, '/app/video-creation')
    await waitFor(() => expect(screen.getByLabelText('场景')).toHaveValue('咖啡机旋转'))
    expect(screen.getByLabelText('风格')).toHaveValue('电影感')
    expect(screen.getByLabelText('时长')).toHaveValue('10')
    expect(screen.getByLabelText('镜头运动')).toHaveValue('推进')
    expect(screen.getByLabelText('随机种子')).toHaveValue('42')
    expect(screen.getAllByText('16:9').length).toBeGreaterThan(0)
    expect(screen.getAllByText('1080P').length).toBeGreaterThan(0)
    expect(screen.getByRole('switch', { name: '生成音频' })).toBeChecked()
    expect(screen.getByRole('switch', { name: '提示词扩展' })).not.toBeChecked()
    expect(screen.getByRole('switch', { name: '添加水印' })).toBeChecked()
    await user.click(await screen.findByRole('button', { name: '返回修改' }))
    await user.clear(screen.getByLabelText('风格'))
    await user.type(screen.getByLabelText('风格'), '写实')
    await queryClient.invalidateQueries({ queryKey: ['video-creation', userFixture.id, 'draft', '41'] })
    expect(screen.getByLabelText('风格')).toHaveValue('写实')
  })
})

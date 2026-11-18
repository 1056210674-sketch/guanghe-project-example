import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { storeAuthToken } from '../auth/tokenStorage'
import { ImageCreationWorkspace } from '../features/image-creation'
import type { TaskCenterListItemView } from '../features/task-center'
import { getImageCreationRecoveryStorageKey } from '../features/image-creation/live/imageCreationRecovery'
import { ImageCreationPage } from '../pages/image-creation/ImageCreationPage'
import { useAuthStore } from '../stores/authStore'
import {
  testAccessToken,
  testExpiresAt,
  testTraceId,
  userFixture,
} from './fixtures/auth'

const catalogProps = {
  businessSpaces: [{ label: '测试经营空间', value: 'space-1' }],
  products: [{ label: '测试商品', value: 'product-1' }],
  selectedBusinessSpaceId: 'space-1',
  selectedProductId: 'product-1',
} as const

function ok(data: unknown, status = 200) {
  return new Response(
    JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({
    status: 'authenticated',
    user: userFixture,
    error: null,
  })
})

describe('图片创作工作台', () => {
  it('最近任务只显示真实资产预览，缺图和加载失败时显示明确状态', () => {
    const tasks: TaskCenterListItemView[] = [
      { id: 'ready', name: '图片生成', taskType: 'IMAGE', status: 'SUCCEEDED', createdAt: '2026-09-24', previewAssetId: 'asset-ready' },
      { id: 'failed', name: '图片生成', taskType: 'IMAGE', status: 'FAILED', createdAt: '2026-09-24' },
    ]
    render(<ImageCreationWorkspace {...catalogProps} recentTasks={tasks} recentTaskPreviewUrls={{ ready: 'https://example.com/real-image.png' }} />)

    const preview = screen.getByRole('img', { name: '图片生成预览' })
    expect(preview).toHaveAttribute('src', 'https://example.com/real-image.png')
    expect(screen.getByText('暂无预览')).toBeInTheDocument()

    fireEvent.error(preview)
    expect(screen.queryByRole('img', { name: '图片生成预览' })).toBeNull()
    expect(screen.getByText('图片暂不可预览')).toBeInTheDocument()
  })

  it('展示已接入的四种创作方式，不保留假生图入口', () => {
    const { container } = render(<ImageCreationWorkspace {...catalogProps} />)

    expect(screen.getByRole('button', { name: '商品资料生图' })).toBeEnabled()
    expect(screen.getByRole('button', { name: /纯文本生图/ })).toBeEnabled()
    expect(screen.getByRole('button', { name: /本次附件生图/ })).toBeEnabled()
    expect(screen.getByRole('button', { name: /局部重绘/ })).toBeEnabled()
    expect(screen.queryByText('后续接入')).not.toBeInTheDocument()
    expect(container.querySelector('input[type="file"]')).not.toBeInTheDocument()
    expect(container.innerHTML).not.toContain('data:')
    expect(container.innerHTML).not.toContain('blob:')
    expect(container.textContent).not.toContain('生成接口尚未接入')
  })

  it('已有 GEO 报告草稿时，切换其他模式后仍可切回报告生图', () => {
    const { rerender } = render(<ImageCreationWorkspace {...catalogProps} mode="GEO_REPORT" geoModeAvailable />)
    expect(screen.getByRole('button', { name: /GEO 报告生图/ })).toBeEnabled()

    rerender(<ImageCreationWorkspace {...catalogProps} mode="TEXT_ONLY" geoModeAvailable />)
    expect(screen.getByRole('button', { name: /GEO 报告生图/ })).toBeEnabled()
  })

  it('按 IMAGE Schema 把真实商品生图参数交给页面容器', async () => {
    const user = userEvent.setup()
    const onPrepareDraft = vi.fn()
    render(
      <ImageCreationWorkspace
        {...catalogProps}
        draftCreationEnabled
        onPrepareDraft={onPrepareDraft}
      />,
    )

    await user.type(screen.getByLabelText('画面场景'), '咖啡机放在晨光厨房台面上')
    await user.type(screen.getByLabelText('风格'), '清新写实')
    await user.type(screen.getByLabelText('光线'), '清晨自然光')
    await user.type(screen.getByLabelText('不希望出现'), '文字、水印')
    await user.click(screen.getByRole('button', { name: /获取报价并确认/ }))

    expect(onPrepareDraft).toHaveBeenCalledWith({
      mode: 'PRODUCT_GROUNDED',
      businessSpaceId: 'space-1',
      productId: 'product-1',
      scene: '咖啡机放在晨光厨房台面上',
      style: '清新写实',
      lighting: '清晨自然光',
      aspectRatio: '1:1',
      count: 1,
      negativePrompt: '文字、水印',
    })
    expect(screen.getByText('1 张')).toBeInTheDocument()
  })

  it('商品身份图片不完整时不能进入报价确认', async () => {
    const user = userEvent.setup()
    const onPrepareDraft = vi.fn()
    render(
      <ImageCreationWorkspace
        {...catalogProps}
        draftCreationDisabledReason="该商品还缺少主图或白底图"
        onPrepareDraft={onPrepareDraft}
      />,
    )

    await user.type(screen.getByLabelText('画面场景'), '有效画面描述')
    expect(screen.getByRole('button', { name: /获取报价并确认/ })).toBeDisabled()
    expect(screen.getByText('该商品还缺少主图或白底图')).toBeInTheDocument()
    expect(onPrepareDraft).not.toHaveBeenCalled()
  })
})

describe('图片创作真实任务容器', () => {
  it('使用最多三张商品身份图创建一个 IMAGE Draft，并幂等确认', async () => {
    const user = userEvent.setup()
    const now = '2026-09-14T01:00:00Z'
    const conversation = {
      id: '11', title: '图片创作', selectedSpaceId: null,
      selectedProductId: null, version: 1, lastMessageAt: null,
      createdAt: now, updatedAt: now,
    }
    const draft = {
      id: '41', draftSource: 'FORM', taskType: 'IMAGE',
      parameterSchemaVersion: 1, status: 'WAITING_CONFIRMATION',
      spaceId: '21', productId: '31',
      sourceMode: 'CATALOG_PRODUCT', referenceAssetIds: ['71', '72', '73'],
      parameters: {
        requestText: '晨光厨房中的咖啡机', mode: 'AGENT_ASSISTED',
        agentSummary: '晨光厨房中的咖啡机', sourceMode: 'CATALOG_PRODUCT',
        referenceAssetIds: ['71', '72', '73'], scene: '晨光厨房中的咖啡机',
        aspectRatio: '1:1', count: 1,
      },
      warnings: [], inputHash: 'a'.repeat(64), pricingVersionId: '51',
      quotedPoints: 5, confirmationToken: 'opaque-token',
      expiresAt: '2099-01-01T00:00:00Z', createdAt: now,
    }
    const task = {
      id: '61', taskType: 'IMAGE', status: 'RUNNING',
      stageCode: 'PROVIDER_GENERATING', stageLabel: '图片生成中',
      spaceId: '21', productId: '31', quotedPoints: 5,
      settledPoints: 0, frozenPoints: 5, result: {}, errorCode: null,
      createdAt: now, updatedAt: now,
    }
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url === '/api/v1/spaces?page=1&size=100') {
        return ok({
          items: [{ id: '21', name: '真实经营空间' }],
          page: 1, size: 100, total: 1, hasNext: false,
        })
      }
      if (url === '/api/v1/tasks?taskType=IMAGE&page=1&size=5') {
        return ok({ items: [], page: 1, size: 5, total: 0, hasNext: false })
      }
      if (url === '/api/v1/spaces/21/products?page=1&size=100') {
        return ok({
          items: [{
            id: '31', spaceId: '21', name: '真实商品', category: null,
            modelSpec: null, aliasCount: 0, version: 1,
            createdAt: now, updatedAt: now,
          }],
          page: 1, size: 100, total: 1, hasNext: false,
        })
      }
      if (url === '/api/v1/products/31/identity-assets') {
        return ok({
          productId: '31', version: 2, creativeReady: true,
          items: [
            { assetId: '73', role: 'DETAIL', sequence: 3, status: 'READY', contentType: 'image/png', width: 1200, height: 1200 },
            { assetId: '71', role: 'PRIMARY', sequence: 1, status: 'READY', contentType: 'image/png', width: 1200, height: 1200 },
            { assetId: '72', role: 'WHITE_BACKGROUND', sequence: 2, status: 'READY', contentType: 'image/png', width: 1200, height: 1200 },
            { assetId: '74', role: 'PACKAGE', sequence: 4, status: 'READY', contentType: 'image/png', width: 1200, height: 1200 },
          ],
        })
      }
      if (/^\/api\/v1\/assets\/(71|72|73)$/.test(url)) {
        const assetId = url.split('/').at(-1) as string
        return ok({
          id: assetId, assetType: 'IMAGE', sourceType: 'UPLOADED', status: 'READY',
          safetyStatus: 'APPROVED', name: `参考图 ${assetId}`, spaceId: '21',
          primaryProductId: '31', productIds: ['31'], contentType: 'image/png',
          sizeBytes: 1000, sha256: 'a'.repeat(64), width: 1200, height: 1200,
          sourceTaskId: null, createdAt: now, updatedAt: now,
        })
      }
      if (/^\/api\/v1\/assets\/(71|72|73)\/download-url$/.test(url)) {
        const assetId = url.split('/').at(-2) as string
        return ok({
          url: `https://example.com/${assetId}.png`,
          expiresAt: '2099-01-01T00:00:00Z',
        })
      }
      if (url === '/api/v1/conversations?page=1&size=100') {
        return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
      }
      if (url === '/api/v1/conversations' && init?.method === 'POST') {
        return ok(conversation, 201)
      }
      if (url === '/api/v1/task-drafts' && init?.method === 'POST') {
        return ok(draft, 201)
      }
      if (url === '/api/v1/billing/point-account') {
        return ok({
          userId: userFixture.id, availablePoints: 100,
          frozenPoints: 0, version: 1,
        })
      }
      if (url === '/api/v1/task-drafts/41/confirm' && init?.method === 'POST') {
        return ok({
          taskId: '61', status: 'QUEUED', quotedPoints: 5,
          frozenPoints: 5, createdAt: now,
        }, 202)
      }
      if (url === '/api/v1/tasks/61') return ok(task)
      throw new Error(`Unexpected Browser API request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <ConfigProvider theme={appTheme}>
        <AntdApp>
          <QueryClientProvider client={createQueryClient()}>
            <MemoryRouter initialEntries={['/app/image-creation']}>
              <ImageCreationPage />
            </MemoryRouter>
          </QueryClientProvider>
        </AntdApp>
      </ConfigProvider>,
    )

    await user.click(await screen.findByLabelText('经营空间'))
    await user.click(await screen.findByText('真实经营空间'))
    await user.click(await screen.findByLabelText('商品'))
    await user.click(await screen.findByText('真实商品'))
    await user.type(screen.getByLabelText('画面场景'), '晨光厨房中的咖啡机')
    await user.dblClick(screen.getByRole('button', { name: /获取报价并确认/ }))

    expect(await screen.findByText('图片创作任务确认')).toBeInTheDocument()
    const draftCalls = fetchMock.mock.calls.filter(
      ([url, init]) => String(url) === '/api/v1/task-drafts' && init?.method === 'POST',
    )
    expect(draftCalls).toHaveLength(1)
    const draftBody = JSON.parse(String(draftCalls[0]?.[1]?.body))
    expect(draftBody).toMatchObject({
      taskType: 'IMAGE', spaceId: '21', productId: '31',
      parameterSchemaVersion: 1,
      parameters: {
        sourceMode: 'CATALOG_PRODUCT',
        referenceAssetIds: ['71', '72', '73'],
        scene: '晨光厨房中的咖啡机', aspectRatio: '1:1', count: 1,
      },
    })
    expect((draftCalls[0]?.[1]?.headers as Headers).get('Idempotency-Key')).toMatch(
      /^[0-9a-f-]{36}$/,
    )
    expect(screen.getByRole('region', { name: '创作依据' })).toHaveTextContent(
      '创作依据：商品资料',
    )
    expect(screen.getAllByRole('img', { name: /参考图 \d/ })).toHaveLength(3)

    await user.dblClick(screen.getByRole('button', { name: '确认开始 · 5 积分' }))
    expect(await screen.findByText('图片生成中')).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.filter(
        ([url, init]) =>
          String(url) === '/api/v1/task-drafts/41/confirm' && init?.method === 'POST',
      ),
    ).toHaveLength(1)
    expect(window.localStorage.getItem(getImageCreationRecoveryStorageKey(userFixture.id)))
      .not.toContain('https://')
  })
})

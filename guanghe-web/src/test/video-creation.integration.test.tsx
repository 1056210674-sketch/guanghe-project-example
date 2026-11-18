import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { storeAuthToken } from '../auth/tokenStorage'
import { VideoCreationWorkspace } from '../features/video-creation'
import { getVideoCreationRecoveryStorageKey } from '../features/video-creation/live/videoCreationRecovery'
import { VideoCreationPage } from '../pages/video-creation/VideoCreationPage'
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

describe('视频创作工作台', () => {
  it('展示四种真实视频创作方式，不展示本地模拟结果', () => {
    const { container } = render(<VideoCreationWorkspace {...catalogProps} />)

    expect(screen.getByRole('button', { name: '商品图片生成视频' })).toBeEnabled()
    expect(screen.getByRole('button', { name: /纯文本生成视频/ })).toBeEnabled()
    expect(screen.getByRole('button', { name: /商品素材生成/ })).toBeEnabled()
    expect(screen.getByRole('button', { name: /GEO 洞察/ })).toBeEnabled()
    expect(screen.queryByText('后续接入')).not.toBeInTheDocument()
    expect(container.querySelector('input[type="file"]')).not.toBeInTheDocument()
    expect(container.innerHTML).not.toContain('data:')
    expect(container.innerHTML).not.toContain('blob:')
  })

  it('按 VIDEO Schema 将表单值交给页面容器', async () => {
    const user = userEvent.setup()
    const onPrepareDraft = vi.fn()
    render(
      <VideoCreationWorkspace
        {...catalogProps}
        draftCreationEnabled
        onPrepareDraft={onPrepareDraft}
      />,
    )

    await user.type(screen.getByLabelText('场景'), '咖啡机在晨光厨房中缓慢旋转')
    await user.type(screen.getByLabelText('风格'), '写实电影感')
    await user.type(screen.getByLabelText('镜头运动'), '缓慢推进')
    await user.click(screen.getByLabelText('生成音频'))
    await user.click(screen.getByLabelText('添加水印'))
    await user.click(screen.getByRole('button', { name: /获取报价并确认/ }))

    expect(onPrepareDraft).toHaveBeenCalledTimes(1)
    expect(onPrepareDraft).toHaveBeenCalledWith({
      mode: 'IMAGE_TO_VIDEO',
      businessSpaceId: 'space-1',
      productId: 'product-1',
      scene: '咖啡机在晨光厨房中缓慢旋转',
      style: '写实电影感',
      durationSeconds: 5,
      aspectRatio: 'adaptive',
      cameraMotion: '缓慢推进',
      resolution: '720P',
      audio: true,
      seed: -1,
      promptExtend: true,
      watermark: true,
    })
  })

  it('未满足真实创建条件或能力未开放时不能提交', async () => {
    const user = userEvent.setup()
    const onPrepareDraft = vi.fn()
    const { rerender } = render(
      <VideoCreationWorkspace
        {...catalogProps}
        draftCreationDisabledReason="该商品还缺少主图或白底图"
        onPrepareDraft={onPrepareDraft}
      />,
    )

    await user.type(screen.getByLabelText('场景'), '有效场景描述')
    expect(screen.getByRole('button', { name: /获取报价并确认/ })).toBeDisabled()
    expect(screen.getByText('该商品还缺少主图或白底图')).toBeInTheDocument()

    rerender(
      <VideoCreationWorkspace
        businessSpaces={catalogProps.businessSpaces}
        products={catalogProps.products}
        draftCreationEnabled
        onPrepareDraft={onPrepareDraft}
      />,
    )
    expect(screen.getByRole('button', { name: /获取报价并确认/ })).toBeDisabled()
    expect(onPrepareDraft).not.toHaveBeenCalled()
  })

  it('不展示本地模拟报价、进度或已完成结果', () => {
    const { container } = render(<VideoCreationWorkspace {...catalogProps} />)

    expect(screen.getByText('创建任务草稿后将显示最新报价')).toBeInTheDocument()
    expect(screen.getByText('暂无视频任务')).toBeInTheDocument()
    expect(screen.getByText('填写参数后即可获取最新报价')).toBeInTheDocument()
    expect(container.textContent).not.toMatch(/\d+\s*积分/)
    expect(container.textContent).not.toContain('negativePrompt')
    expect(container.querySelector('[role="progressbar"]')).not.toBeInTheDocument()
  })
})

describe('视频创作真实任务容器', () => {
  it('只创建一个 VIDEO Draft 并幂等确认，RUNNING 阶段不伪装完成', async () => {
    const user = userEvent.setup()
    const now = '2026-09-08T01:00:00Z'
    const conversation = {
      id: '11', title: '视频创作', selectedSpaceId: null,
      selectedProductId: null, version: 1, lastMessageAt: null,
      createdAt: now, updatedAt: now,
    }
    const draft = {
      id: '41', draftSource: 'FORM', taskType: 'VIDEO',
      parameterSchemaVersion: 1, status: 'WAITING_CONFIRMATION',
      spaceId: '21', productId: '31',
      sourceMode: 'CATALOG_PRODUCT', referenceAssetIds: ['71'],
      parameters: {
        requestText: '晨光中的咖啡机', mode: 'AGENT_ASSISTED',
        agentSummary: '晨光中的咖啡机', sourceMode: 'CATALOG_PRODUCT',
        referenceAssetIds: ['71'], scene: '晨光中的咖啡机',
        durationSeconds: 5, aspectRatio: 'adaptive', resolution: '720P',
        audio: false, seed: -1, promptExtend: true, watermark: false,
      },
      warnings: [], inputHash: 'a'.repeat(64), pricingVersionId: '51',
      quotedPoints: 10, confirmationToken: 'opaque-token',
      expiresAt: '2099-01-01T00:00:00Z', createdAt: now,
    }
    const task = {
      id: '61', taskType: 'VIDEO', status: 'RUNNING',
      stageCode: 'PROVIDER_GENERATING', stageLabel: '视频生成中',
      spaceId: '21', productId: '31', quotedPoints: 10,
      settledPoints: 0, frozenPoints: 10, result: {}, errorCode: null,
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
      if (url === '/api/v1/tasks?taskType=VIDEO&page=1&size=5') {
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
            { assetId: '71', role: 'PRIMARY', sequence: 1, status: 'READY', contentType: 'image/png', width: 1200, height: 1200 },
            { assetId: '72', role: 'WHITE_BACKGROUND', sequence: 2, status: 'READY', contentType: 'image/png', width: 1200, height: 1200 },
          ],
        })
      }
      if (url === '/api/v1/assets/71' || url === '/api/v1/assets/72') {
        const assetId = url.endsWith('/71') ? '71' : '72'
        return ok({
          id: assetId, assetType: 'IMAGE', sourceType: 'UPLOADED', status: 'READY',
          safetyStatus: 'APPROVED', name: `参考图 ${assetId}`, spaceId: '21',
          primaryProductId: '31', productIds: ['31'], contentType: 'image/png',
          sizeBytes: 1000, sha256: 'a'.repeat(64), width: 1200, height: 1200,
          sourceTaskId: null, createdAt: now, updatedAt: now,
        })
      }
      if (url === '/api/v1/assets/71/download-url' || url === '/api/v1/assets/72/download-url') {
        const assetId = url.includes('/71/') ? '71' : '72'
        return ok({ url: `https://example.com/${assetId}.png`, expiresAt: '2099-01-01T00:00:00Z' })
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
          taskId: '61', status: 'QUEUED', quotedPoints: 10,
          frozenPoints: 10, createdAt: now,
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
            <MemoryRouter initialEntries={['/app/video-creation']}>
              <VideoCreationPage />
            </MemoryRouter>
          </QueryClientProvider>
        </AntdApp>
      </ConfigProvider>,
    )

    await user.click(await screen.findByLabelText('经营空间'))
    await user.click(await screen.findByText('真实经营空间'))
    await user.click(await screen.findByLabelText('商品'))
    await user.click(await screen.findByText('真实商品'))
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /获取报价并确认/ })).toBeDisabled(),
    )
    await user.type(screen.getByLabelText('场景'), '晨光中的咖啡机')
    await user.dblClick(screen.getByRole('button', { name: /获取报价并确认/ }))

    expect(await screen.findByText('视频创作任务确认')).toBeInTheDocument()
    expect(fetchMock.mock.calls.filter(([url]) => String(url) === '/api/v1/conversations')).toHaveLength(1)
    const draftCalls = fetchMock.mock.calls.filter(
      ([url, init]) => String(url) === '/api/v1/task-drafts' && init?.method === 'POST',
    )
    expect(draftCalls).toHaveLength(1)
    expect((draftCalls[0]?.[1]?.headers as Headers).get('Idempotency-Key')).toMatch(
      /^[0-9a-f-]{36}$/,
    )
    const draftBody = JSON.parse(String(draftCalls[0]?.[1]?.body))
    expect(draftBody).toMatchObject({
      taskType: 'VIDEO', spaceId: '21', parameterSchemaVersion: 1,
      productId: '31',
      parameters: {
        sourceMode: 'CATALOG_PRODUCT', referenceAssetIds: ['71'],
        scene: '晨光中的咖啡机', durationSeconds: 5,
        aspectRatio: 'adaptive', resolution: '720P',
      },
    })
    expect(draftBody.parameters).not.toHaveProperty('negativePrompt')

    await user.dblClick(screen.getByRole('button', { name: '确认开始 · 10 积分' }))
    expect(await screen.findByText('视频生成中')).toBeInTheDocument()
    expect(screen.queryByText('已完成')).not.toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
    expect(
      fetchMock.mock.calls.filter(
        ([url, init]) =>
          String(url) === '/api/v1/task-drafts/41/confirm' && init?.method === 'POST',
      ),
    ).toHaveLength(1)
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/tasks/61',
      expect.objectContaining({ method: 'GET' }),
    ))
    expect(window.localStorage.getItem(getVideoCreationRecoveryStorageKey(userFixture.id)))
      .not.toContain('https://')
  })
})

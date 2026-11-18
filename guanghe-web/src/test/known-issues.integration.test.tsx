import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import { fireEvent, render, screen } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { storeAuthToken } from '../auth/tokenStorage'
import { MarkdownMessageContent } from '../features/chat/components/MarkdownMessageContent'
import { storeChatRecoveryState } from '../features/chat/live/chatRecovery'
import { openExternalHttps } from '../features/shared/openExternalHttps'
import { ChatPage } from '../pages/chat/ChatPage'
import { TaskCenterPage } from '../pages/task-center/TaskCenterPage'
import { appLinks } from '../routes/paths'
import { useAuthStore } from '../stores/authStore'
import {
  testAccessToken,
  testExpiresAt,
  testTraceId,
  userFixture,
} from './fixtures/auth'

vi.mock('../features/assets', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../features/assets')>()
  return {
    ...actual,
    useAssetDetails: (assetIds: readonly string[]) => Object.fromEntries(
      assetIds.map((assetId) => [assetId, {
        id: assetId,
        name: '永久生成图片.png',
      }]),
    ),
    useAssetDownloadUrls: (assetIds: readonly string[]) => Object.fromEntries(
      assetIds.map((assetId) => [assetId, {
        url: 'https://provider.example/generated.png',
        expiresAt: '2099-09-08T04:23:00Z',
      }]),
    ),
  }
})

vi.mock('../features/chat', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../features/chat')>()
  return {
    ...actual,
    ChatWorkspace: actual.ChatMessageStream,
  }
})

const now = '2026-09-07T04:23:00Z'

function renderPage(
  element: ReactElement,
  initialEntry: string,
  routePath: string,
) {
  return render(
    <ConfigProvider theme={appTheme}>
      <AntdApp>
        <QueryClientProvider client={createQueryClient()}>
          <MemoryRouter initialEntries={[initialEntry]}>
            <Routes>
              <Route path={routePath} element={element} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </AntdApp>
    </ConfigProvider>,
  )
}

function ok(data: unknown) {
  return new Response(
    JSON.stringify({
      code: 'OK',
      message: 'success',
      data,
      traceId: testTraceId,
    }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
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

describe('已知验收问题回归', () => {
  it('安全渲染 GFM Markdown，不执行原始 HTML 或加载正文图片', () => {
    render(
      <MarkdownMessageContent
        content={`# 分析结果\n\n- **卖点一**\n- 卖点二\n\n| 场景 | 建议 |\n| --- | --- |\n| 办公室 | 简洁 |\n\n<script>window.bad = true</script>\n\n![外部图片](https://tracker.invalid/pixel.png)`}
      />,
    )

    expect(screen.getByRole('heading', { name: '分析结果' })).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByText('卖点一').tagName).toBe('STRONG')
    expect(document.querySelector('script')).toBeNull()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('任务详情路由读取真实任务并展示 Qwen 图片结果', async () => {
    const taskId = '90300727817142272'
    const imageUrl = 'https://provider.example/generated.png'
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url === '/api/v1/tasks?page=1&size=20') {
        return ok({ items: [], page: 1, size: 20, total: 0, hasNext: false })
      }
      if (url === '/api/v1/spaces?page=1&size=100') {
        return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
      }
      if (url === `/api/v1/tasks/${taskId}/events`) {
        return ok([])
      }
      if (url === `/api/v1/tasks/${taskId}`) {
        return ok({
          id: taskId,
          taskType: 'IMAGE',
          status: 'SUCCEEDED',
          stageCode: 'COMPLETED',
          stageLabel: '已完成',
          spaceId: '21',
          productId: '31',
          quotedPoints: 7,
          settledPoints: 7,
          frozenPoints: 0,
          result: {
            provider: 'QWEN_IMAGE',
            model: 'qwen-image-3.0',
            providerCostReported: false,
            asset: {
              assetId: '90378333853843456',
              assetType: 'IMAGE',
              status: 'READY',
              contentType: 'image/png',
              sizeBytes: 468446,
              sha256: '26bf7e36d039844cebe07b50b9ede918f9e0540aa429cbea952a28f8bf3947f4',
              width: 1024,
              height: 1024,
              safetyStatus: 'TEST_BYPASS',
            },
          },
          errorCode: null,
          createdAt: now,
          updatedAt: now,
        })
      }
      if (url === '/api/v1/spaces/21') {
        return ok({ id: '21', name: '验收经营空间' })
      }
      if (url === '/api/v1/products/31') {
        return ok({ id: '31', name: '便携咖啡机' })
      }
      if (url === '/api/v1/assets/90378333853843456') {
        return ok({
          id: '90378333853843456', assetType: 'IMAGE', sourceType: 'GENERATED',
          status: 'READY', safetyStatus: 'TEST_BYPASS', name: '永久生成图片.png',
          spaceId: '21', primaryProductId: '31', productIds: ['31'],
          contentType: 'image/png', sizeBytes: 468446,
          sha256: '26bf7e36d039844cebe07b50b9ede918f9e0540aa429cbea952a28f8bf3947f4',
          width: 1024, height: 1024, sourceTaskId: taskId,
          createdAt: now, updatedAt: now,
        })
      }
      if (url === '/api/v1/assets/90378333853843456/download-url') {
        return ok({ url: imageUrl, expiresAt: '2099-09-08T04:23:00Z' })
      }
      throw new Error(`Unexpected Browser API request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    renderPage(<TaskCenterPage />, appLinks.taskDetail(taskId), '/app/tasks/:taskId')

    expect(await screen.findByText('验收经营空间')).toBeInTheDocument()
    expect(screen.getAllByText('便携咖啡机')).toHaveLength(2)
    expect(screen.getByLabelText('图片生成结果：图片生成结果')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: '永久生成图片.png生成图片' })).toHaveAttribute(
      'src',
      imageUrl,
    )
    expect(
      await screen.findByText('临时链接有效期至 2099-09-08T04:23:00Z'),
    ).toBeInTheDocument()
    expect(screen.getByText('测试环境已跳过内容安全审核')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '已保存到资产中心' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '打开原图' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/tasks/${taskId}`,
      expect.objectContaining({ method: 'GET' }),
    )
  })

  it('刷新对话后从服务端恢复已完成任务，独立完成通知先展示结算再展示图片', async () => {
    const taskId = '90300727817142272'
    const completedTask = {
      id: taskId,
      taskType: 'IMAGE',
      status: 'SUCCEEDED',
      stageCode: 'COMPLETED',
      stageLabel: '已完成',
      spaceId: '21',
      productId: '31',
      quotedPoints: 7,
      settledPoints: 7,
      frozenPoints: 0,
      result: {
        provider: 'QWEN_IMAGE',
        model: 'qwen-image-3.0',
        providerCostReported: false,
        asset: {
          assetId: '90378333853843456', assetType: 'IMAGE', status: 'READY',
          contentType: 'image/png', sizeBytes: 468446,
          sha256: '26bf7e36d039844cebe07b50b9ede918f9e0540aa429cbea952a28f8bf3947f4',
          width: 1024, height: 1024, safetyStatus: 'TEST_BYPASS',
        },
      },
      errorCode: null,
      createdAt: now,
      updatedAt: now,
    }
    storeChatRecoveryState('1', {
      drafts: [],
      tasks: [{ messageId: '2', resourceId: taskId }],
      confirmationAttempts: [],
    })
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) {
        return ok({
          items: [
            {
              id: '1',
              title: '图片验收会话',
              selectedSpaceId: '21',
              selectedProductId: '31',
              version: 1,
              lastMessageAt: now,
              createdAt: now,
              updatedAt: now,
            },
          ],
          page: 1,
          size: 100,
          total: 1,
          hasNext: false,
        })
      }
      if (url === '/api/v1/conversations/1') {
        return ok({
          id: '1',
          title: '图片验收会话',
          selectedSpaceId: '21',
          selectedProductId: '31',
          version: 1,
          lastMessageAt: now,
          createdAt: now,
          updatedAt: now,
        })
      }
      if (url.startsWith('/api/v1/conversations/1/messages?')) {
        return ok({
          items: [
            {
              id: '2',
              conversationId: '1',
              role: 'ASSISTANT',
              contentType: 'SYSTEM_EVENT',
              content: { text: '任务已经完成。' },
              replyToMessageId: null,
              runId: null,
              sequence: 2,
              status: 'COMPLETED',
              createdAt: now,
              updatedAt: now,
            },
          ],
          nextCursor: null,
        })
      }
      if (url === '/api/v1/tasks?conversationId=1&page=1&size=100') {
        return ok({ items: [completedTask], page: 1, size: 100, total: 1, hasNext: false })
      }
      if (url === '/api/v1/conversations/1/events') {
        return new Response('', {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        })
      }
      if (url === `/api/v1/tasks/${taskId}`) {
        return ok(completedTask)
      }
      if (url === '/api/v1/billing/point-account') {
        return ok({
          userId: userFixture.id,
          availablePoints: 9972,
          frozenPoints: 0,
          version: 1,
        })
      }
      if (url === '/api/v1/spaces?page=1&size=100') {
        return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
      }
      if (url === '/api/v1/spaces/21/products?page=1&size=100') {
        return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
      }
      if (url === '/api/v1/products/31') {
        return ok({ id: '31', name: '便携咖啡机' })
      }
      if (url === '/api/v1/assets/90378333853843456') {
        return ok({
          id: '90378333853843456', assetType: 'IMAGE', sourceType: 'GENERATED',
          status: 'READY', safetyStatus: 'TEST_BYPASS', name: '永久生成图片.png',
          spaceId: '21', primaryProductId: '31', productIds: ['31'],
          contentType: 'image/png', sizeBytes: 468446,
          sha256: '26bf7e36d039844cebe07b50b9ede918f9e0540aa429cbea952a28f8bf3947f4',
          width: 1024, height: 1024, sourceTaskId: taskId,
          createdAt: now, updatedAt: now,
        })
      }
      if (url === '/api/v1/assets/90378333853843456/download-url') {
        return ok({
          url: 'https://asset.example/generated.png',
          expiresAt: '2099-09-08T04:23:00Z',
        })
      }
      throw new Error(`Unexpected Browser API request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    renderPage(<ChatPage />, '/app/chat?conversation=1', '/app/chat')

    await vi.waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([url]) =>
          String(url).includes('/api/v1/products/31'),
        ),
      ).toBe(true),
    )
    const gallery = await screen.findByLabelText('图片生成结果：图片生成结果')
    const notification = document.querySelector<HTMLElement>('.chat-background-task-notification')
    const outcome = document.querySelector<HTMLElement>('.task-execution-outcome-group')
    expect(notification).not.toBeNull()
    expect(outcome).not.toBeNull()
    expect(notification).toContainElement(outcome)
    expect(notification).toContainElement(gallery)
    expect(document.querySelector('.task-created-receipt')).toBeNull()
    expect(document.querySelectorAll('.task-execution-outcome-group')).toHaveLength(1)
    expect(screen.queryByLabelText(/付费任务确认卡/)).not.toBeInTheDocument()
    expect(
      outcome!.compareDocumentPosition(gallery) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
    expect(screen.getByRole('button', { name: '打开原图' })).toBeInTheDocument()
    expect(
      await screen.findByText('临时链接有效期至 2099-09-08T04:23:00Z'),
    ).toBeInTheDocument()
  })

  it('临时原图只允许通过无来源信息的新窗口打开 HTTPS 地址', () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    const signedUrl = 'https://provider.example/result.png?signature=secret'

    expect(openExternalHttps(signedUrl)).toBe(true)
    expect(openSpy).toHaveBeenCalledWith(
      signedUrl,
      '_blank',
      'noopener,noreferrer',
    )

    openSpy.mockClear()
    expect(openExternalHttps('http://provider.example/result.png')).toBe(false)
    expect(openExternalHttps('javascript:alert(1)')).toBe(false)
    expect(openExternalHttps('not-a-url')).toBe(false)
    expect(openSpy).not.toHaveBeenCalled()

    render(
      <button type="button" onClick={() => openExternalHttps(signedUrl)}>
        打开原图
      </button>,
    )
    fireEvent.click(screen.getByRole('button', { name: '打开原图' }))
    expect(openSpy).toHaveBeenCalledOnce()
  })
})

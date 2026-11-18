import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { storeAuthToken } from '../auth/tokenStorage'
import { toLiveMediaSubmission } from '../features/articles'
import { notificationTargetLink } from '../features/notifications/live/notificationAdapters'
import { TaskCenterMediaSubmissions } from '../features/task-center/components/TaskCenterMediaSubmissions'
import { TaskCenterPage } from '../pages/task-center/TaskCenterPage'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

function ok(data: unknown) {
  return new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), {
    headers: { 'Content-Type': 'application/json' },
  })
}

function ArticleRoute() {
  const location = useLocation()
  return <div>文章入口：{location.pathname}{location.search}</div>
}

describe('媒体任务投稿记录', () => {
  it('任务记录用客户项目筛选，不再请求经营空间或商品选项，清空后恢复全部历史任务', async () => {
    window.localStorage.clear()
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = new URL(String(input), 'http://localhost')
      expect(init?.method).toBe('GET')
      if (url.pathname === '/api/v1/projects') return ok([
        { id: '21', name: '小行星咖啡店', status: 'ACTIVE', version: 1 },
        { id: '22', name: '艾尼亚科', status: 'ARCHIVED', version: 1 },
      ])
      if (url.pathname === '/api/v1/tasks' || url.pathname === '/api/v1/conversations') {
        return ok({ items: [], page: 1, size: 20, total: 0, hasNext: false })
      }
      if (url.pathname === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
      if (url.pathname === '/api/v1/notifications/unread-count') return ok({ unreadCount: 0 })
      throw new Error(`Unexpected Browser API request: ${url.pathname}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ConfigProvider theme={appTheme}><AntdApp><QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={['/app/tasks']}><TaskCenterPage /></MemoryRouter>
    </QueryClientProvider></AntdApp></ConfigProvider>)
    await screen.findByText('还没有正式任务')
    expect(screen.queryByLabelText('按经营空间筛选')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('按商品筛选')).not.toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(screen.getByLabelText('按客户项目筛选'))
    // Archived projects remain selectable for their historical tasks.
    await user.click(screen.getByText('艾尼亚科', { selector: '.ant-select-item-option-content' }))
    await waitFor(() => expect(fetchMock.mock.calls.some(([input]) => {
      const url = new URL(String(input), 'http://localhost')
      return url.pathname === '/api/v1/tasks' && url.searchParams.get('spaceId') === '22'
        && !url.searchParams.has('productId') && url.searchParams.get('page') === '1'
    })).toBe(true))
    await screen.findByText('没有匹配的任务')
    await user.click(screen.getByRole('button', { name: '清空筛选' }))
    await screen.findByText('还没有正式任务')
    expect(screen.getByLabelText('按客户项目筛选')).toHaveValue('')
    expect(fetchMock.mock.calls.some(([input]) => /\/api\/v1\/(spaces|products)(\?|$)/.test(String(input)))).toBe(false)
  })

  it('展示任务投稿、文章入口及全部安全回链', async () => {
    const onOpenArticle = vi.fn()
    const onRefresh = vi.fn()
    const submission = toLiveMediaSubmission({
      id: '1001',
      batchId: '1002',
      taskId: '1003',
      articleId: '1004',
      articleSnapshotId: '1005',
      mediaResourceId: '1006',
      mediaName: '媒体甲',
      status: 'PUBLISHED',
      saleAmountCents: 12000,
      chargedPoints: 120,
      publishedUrl: 'https://example.com/story',
      publishedUrls: [
        'https://example.com/story',
        'http://example.org/story?channel=media',
      ],
      stableErrorCode: null,
      createdAt: '2026-09-23T10:00:00Z',
      updatedAt: '2026-09-23T10:05:00Z',
    })

    render(
      <TaskCenterMediaSubmissions
        submissions={[submission]}
        loading={false}
        error={null}
        onRetry={vi.fn()}
        onRefresh={onRefresh}
        onOpenArticle={onOpenArticle}
      />,
    )

    expect(screen.getByText('媒体甲')).toBeInTheDocument()
    expect(screen.getByText('已发布')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: /打开发布页面/ })).toHaveLength(2)
    await userEvent.setup().click(screen.getByRole('button', { name: '刷新投稿状态' }))
    expect(onRefresh).toHaveBeenCalledTimes(1)
    await userEvent.setup().click(screen.getByRole('button', { name: '查看文章' }))
    expect(onOpenArticle).toHaveBeenCalledExactlyOnceWith('1004')
  })

  it('已发布但无回链时不误报为未完成', () => {
    const submission = toLiveMediaSubmission({
      id: '1001', batchId: '1002', taskId: '1003', articleId: '1004',
      articleSnapshotId: '1005', mediaResourceId: '1006', mediaName: '媒体甲',
      status: 'PUBLISHED', saleAmountCents: 12000, chargedPoints: 120,
      publishedUrl: null, publishedUrls: [], stableErrorCode: null,
      createdAt: '2026-09-23T10:00:00Z', updatedAt: '2026-09-23T10:05:00Z',
    })
    render(
      <TaskCenterMediaSubmissions
        submissions={[submission]}
        loading={false}
        error={null}
        onRetry={vi.fn()}
        onOpenArticle={vi.fn()}
      />,
    )
    expect(screen.getByText('已发布，上游暂未提供可访问链接')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /打开发布页面/ })).not.toBeInTheDocument()
  })

  it('从任务通知进入详情，只按 taskId 读取投稿并可打开文章入口', async () => {
    const taskId = '1003'
    const notificationLink = notificationTargetLink('TASK', taskId)
    expect(notificationLink?.path).toBe('/app/tasks/1003')
    window.localStorage.clear()
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })

    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      expect(init?.method).toBe('GET')
      if (url === '/api/v1/billing/notifications/unread-count') return ok({ unreadCount: 0 })
      if (url === '/api/v1/spaces?page=1&size=100') {
        return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
      }
      if (url === '/api/v1/projects') return ok([])
      if (url === '/api/v1/tasks?page=1&size=20') {
        return ok({ items: [], page: 1, size: 20, total: 0, hasNext: false })
      }
      if (url === `/api/v1/tasks/${taskId}/events`) return ok([])
      if (url === `/api/v1/tasks/${taskId}`) {
        return ok({
          id: taskId, taskType: 'MEDIA', status: 'SUCCEEDED', stageCode: 'COMPLETED',
          stageLabel: '已完成', spaceId: '21', productId: null, quotedPoints: 120,
          settledPoints: 120, frozenPoints: 0, progress: null,
          result: { submissionBatchId: '1002', total: 1, published: 1, rejected: 0 },
          errorCode: null, createdAt: '2026-09-23T10:00:00Z', updatedAt: '2026-09-23T10:05:00Z',
        })
      }
      if (url === '/api/v1/spaces/21') return ok({ id: '21', name: '经营空间' })
      if (url === `/api/v1/media/submissions?taskId=${taskId}&page=1&size=20`) {
        return ok({
          items: [{
            id: '1001', batchId: '1002', taskId, articleId: '1004', articleSnapshotId: '1005',
            mediaResourceId: '1006', mediaName: '媒体甲', status: 'PUBLISHED',
            saleAmountCents: 12000, chargedPoints: 120,
            publishedUrl: 'https://example.com/story',
            publishedUrls: ['https://example.com/story', 'http://example.org/story?ref=media'],
            stableErrorCode: null, createdAt: '2026-09-23T10:00:00Z', updatedAt: '2026-09-23T10:05:00Z',
          }],
          page: 1, size: 20, total: 1, hasNext: false,
        })
      }
      throw new Error(`Unexpected Browser API request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <ConfigProvider theme={appTheme}>
        <AntdApp>
          <QueryClientProvider client={createQueryClient()}>
            <MemoryRouter initialEntries={[notificationLink!.path]}>
              <Routes>
                <Route path="/app/tasks/:taskId" element={<TaskCenterPage />} />
                <Route path="/app/articles" element={<ArticleRoute />} />
              </Routes>
            </MemoryRouter>
          </QueryClientProvider>
        </AntdApp>
      </ConfigProvider>,
    )

    const records = await screen.findByLabelText('媒体投稿记录')
    expect(await within(records).findByText('媒体甲')).toBeInTheDocument()
    const links = within(records).getAllByRole('link', { name: /打开发布页面/ })
    expect(links).toHaveLength(2)
    expect(links[1]).toHaveAttribute('href', 'http://example.org/story?ref=media')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/media/submissions?taskId=${taskId}&page=1&size=20`,
      expect.objectContaining({ method: 'GET' }),
    ))
    await userEvent.setup().click(within(records).getByRole('button', { name: '查看文章' }))
    expect(await screen.findByText('文章入口：/app/articles?article=1004')).toBeInTheDocument()
  })
})

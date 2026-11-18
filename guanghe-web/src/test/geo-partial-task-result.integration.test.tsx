import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation, useParams } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PaidTask } from '../api/conversationApi'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { storeAuthToken } from '../auth/tokenStorage'
import { taskFailureMessage } from '../features/task-common/taskFailurePresentation'
import { toTaskCenterTaskDetail } from '../features/task-center/live/taskCenterAdapters'
import { TaskCenterPage } from '../pages/task-center/TaskCenterPage'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'
import { completedGeoTask, partialRateLimitedTask } from './fixtures/geoPartialRateLimited'

const rateLimitMessage = '上游接口触发限流，本条未取得回答。'
function ok(data: unknown) {
  return new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), {
    headers: { 'Content-Type': 'application/json' },
  })
}
const page = (items: unknown[]) => ok({ items, page: 1, size: 20, total: items.length, hasNext: false })
function RouteObserver() {
  const location = useLocation()
  return <output data-testid="route">{location.pathname}</output>
}
function ReportRouteProbe() {
  const { reportId } = useParams()
  return <output data-testid="opened-report">{reportId}</output>
}

function stub(task: PaidTask = partialRateLimitedTask) {
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const url = new URL(String(input), 'http://localhost')
    expect(init?.method).toBe('GET')
    if (url.pathname === '/api/v1/tasks/7001') return ok(task)
    if (url.pathname === '/api/v1/tasks/7001/events') return ok([])
    if (url.pathname === '/api/v1/tasks') return page([task])
    if (url.pathname === '/api/v1/spaces/4001') return ok({ id: '4001', name: '并发回归测试项目' })
    if (url.pathname === '/api/v1/projects') return ok([])
    if (url.pathname === '/api/v1/conversations') return page([])
    if (url.pathname === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    if (url.pathname === '/api/v1/notifications/unread-count') return ok({ unreadCount: 0 })
    throw new Error(`Unexpected Browser API request: ${url.pathname}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function mountTaskPage() {
  return render(<ConfigProvider theme={appTheme}><AntdApp><QueryClientProvider client={createQueryClient()}>
    <MemoryRouter initialEntries={['/app/tasks/7001']}><Routes>
      <Route path="/app/tasks/:taskId" element={<TaskCenterPage />} />
      <Route path="/app/geo/reports/:reportId" element={<ReportRouteProbe />} />
    </Routes><RouteObserver /></MemoryRouter>
  </QueryClientProvider></AntdApp></ConfigProvider>)
}

beforeEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('部分 GEO 监测结果的页面入口（仅 Browser API stub，不调用模型）', () => {
  it('十条中两条成功八条限流，真实任务详情仍提供精确报告入口，点击只导航不发起新采集', async () => {
    const fetchMock = stub()
    mountTaskPage()
    const summary = await screen.findByText('计划 10 个采样单元，成功 2 个，失败 8 个，缺失 0 个。')
    const drawer = summary.closest<HTMLElement>('.task-center-detail')!
    expect(drawer).not.toBeNull()
    expect(within(drawer).getByText('部分完成', { selector: '.task-center-status' })).toBeVisible()
    await userEvent.click(within(drawer).getByRole('button', { name: '查看已完成报告' }))
    await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent('/app/geo/reports/9001'))
    expect(screen.getByTestId('opened-report')).toHaveTextContent('9001')
    expect(fetchMock.mock.calls.every(([input, init]) => String(input).startsWith('/api/v1/') && init?.method === 'GET')).toBe(true)
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/geo/'))).toBe(false)
  })

  it('全部成功的 GEO 任务也能打开已有报告', async () => {
    stub(completedGeoTask)
    mountTaskPage()
    const summary = await screen.findByText('计划 10 个采样单元，成功 10 个，失败 0 个，缺失 0 个。')
    const drawer = summary.closest<HTMLElement>('.task-center-detail')!
    expect(drawer).not.toBeNull()
    await userEvent.click(within(drawer).getByRole('button', { name: '查看已完成报告' }))
    await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent('/app/geo/reports/9001'))
  })

  it.each(['RUNNING', 'FAILED', 'SYSTEM_TERMINATED'] as const)('状态为 %s 时不制造可查看的已完成报告入口', (status) => {
    expect(toTaskCenterTaskDetail({ ...partialRateLimitedTask, status }).actions?.some((action) => action.type === 'VIEW_COMPLETED_REPORT'))
      .not.toBe(true)
  })

  it('没有报告引用或不是 GEO 任务时不猜测报告 ID', () => {
    expect(toTaskCenterTaskDetail({ ...partialRateLimitedTask, result: {} }).actions?.some((action) => action.type === 'VIEW_COMPLETED_REPORT'))
      .not.toBe(true)
    expect(toTaskCenterTaskDetail({ ...partialRateLimitedTask, taskType: 'IMAGE' }).actions?.some((action) => action.type === 'VIEW_COMPLETED_REPORT'))
      .not.toBe(true)
  })

  it.each(['GEO_PROVIDER_RATE_LIMITED', 'GEO_YUANBAO_RATE_LIMITED'])('%s 明确展示上游限流原因', (code) => {
    expect(taskFailureMessage(code, { status: 'FAILED' })).toBe(rateLimitMessage)
  })
})

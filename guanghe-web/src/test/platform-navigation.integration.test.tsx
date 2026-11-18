import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App as AntdApp, ConfigProvider } from 'antd'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { storeAuthToken } from '../auth/tokenStorage'
import { UserShell } from '../layouts/UserShell'
import { UserHomePage } from '../pages/UserHomePage'
import { APP_PATHS } from '../routes/paths'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data }), { headers: { 'Content-Type': 'application/json' } })

function RouteProbe() {
  const location = useLocation()
  return <output data-testid="route">{location.pathname}{location.search}{location.hash}</output>
}

function renderNavigation(path = APP_PATHS.userHome as string) {
  const requests: Array<{ url: string; method: string }> = []
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    requests.push({ url, method: init?.method ?? 'GET' })
    if (url === '/api/v1/projects') return ok([])
    if (url.startsWith('/api/v1/conversations?')) return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
    if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    if (url === '/api/v1/notifications/unread-count') return ok({ unreadCount: 0 })
    throw new Error(`Unexpected navigation request: ${url}`)
  }))
  render(<ConfigProvider theme={appTheme}><AntdApp><QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={[path]}>
    <UserShell><RouteProbe /></UserShell>
  </MemoryRouter></QueryClientProvider></AntdApp></ConfigProvider>)
  return requests
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})
afterEach(() => vi.unstubAllGlobals())

describe('精简用户导航', () => {
  it('主入口按聊天、智能体、画布、知识库、资产、监测和投稿组织，点击仅导航不创建业务', async () => {
    const requests = renderNavigation()
    const user = userEvent.setup()
    const navigation = screen.getByRole('navigation', { name: '普通用户主导航' })
    const entries = [
      ['新聊天', APP_PATHS.userHome], ['智能体', APP_PATHS.agents],
      ['创作画布', APP_PATHS.canvases],
      ['品牌知识库', APP_PATHS.knowledge], ['资产中心', APP_PATHS.assets],
      ['自动监测', APP_PATHS.geoPlans], ['投稿', APP_PATHS.submissions],
    ] as const
    expect(within(navigation).getAllByRole('button')).toHaveLength(entries.length)
    expect(screen.getByRole('button', { name: '新建项目' })).toBeInTheDocument()
    expect(screen.getByText('最近聊天')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '更多功能' })).not.toBeInTheDocument()
    for (const [name, path] of entries) {
      await user.click(within(navigation).getByRole('button', { name }))
      await waitFor(() => expect(screen.getByTestId('route').textContent).toBe(path))
      expect(within(navigation).getByRole('button', { name })).toHaveAttribute('aria-current', 'page')
    }
    expect(requests.length).toBeGreaterThan(0)
    expect(requests.every((request) => request.method === 'GET')).toBe(true)
    expect(requests.some((request) => /\/spaces|\/products|\/competitors/.test(request.url))).toBe(false)
  })

  it('账户菜单保留任务追踪、积分账单和通知入口', async () => {
    renderNavigation()
    const user = userEvent.setup()
    for (const [label, path] of [['任务记录', APP_PATHS.tasks], ['积分与账单', APP_PATHS.pointsBilling], ['通知中心', APP_PATHS.notifications]] as const) {
      await user.click(screen.getByRole('button', { name: '账户菜单' }))
      await user.click(await screen.findByRole('menuitem', { name: new RegExp(label) }))
      await waitFor(() => expect(screen.getByTestId('route').textContent).toBe(path))
    }
    expect(screen.getByRole('button', { name: '打开通知中心' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '积分与账单' })).toBeInTheDocument()
  })

  it('旧工作台书签转入唯一聊天首页，保留智能体、项目与锚点且不请求旧工作台数据', async () => {
    const fetchMock = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', fetchMock)
    render(<MemoryRouter initialEntries={['/app/workbench?installation=101&project=201#saved']}>
      <Routes><Route path="/app/workbench" element={<UserHomePage />} /><Route path="/app" element={<RouteProbe />} /></Routes>
    </MemoryRouter>)
    await waitFor(() => expect(screen.getByTestId('route').textContent).toBe('/app?installation=101&project=201#saved'))
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

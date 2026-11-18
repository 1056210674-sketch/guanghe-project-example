import { QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App as AntdApp, ConfigProvider } from 'antd'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { GeoPlan } from '../api/geoApi'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { storeAuthToken } from '../auth/tokenStorage'
import { GeoPlansPage, GeoQuestionsPage } from '../pages/geo/GeoPage'
import { APP_PATHS } from '../routes/paths'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const now = '2026-10-09T01:00:00Z'
const plan: GeoPlan = {
  id: '701', topicId: '501', name: '验收定时监测计划', questionIds: ['1', '2', '3'],
  platformCodes: ['DOUBAO'], sampleMode: 'STANDARD',
  schedule: { type: 'DAILY', localTime: '09:00', timeZone: 'Asia/Shanghai' },
  spaceId: '401', productId: null, lastScheduledSlot: null, pauseReason: null,
  status: 'ACTIVE', nextRunAt: now, estimatedPointsPerRun: 3, maxPointsPerRun: 10,
  pricingVersionId: '601', version: 1, createdAt: now, updatedAt: now,
}
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data }), { headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[]) => ok({ items, page: 1, size: 20, total: items.length, hasNext: false })
function RouteProbe() { const location = useLocation(); return <output data-testid="route">{location.pathname}{location.search}</output> }

function renderPage(path = APP_PATHS.geoPlans as string) {
  const writes: string[] = []
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    if (init?.method && init.method !== 'GET') { writes.push(url); throw new Error('Navigation must not create a paid task or plan') }
    if (url.startsWith('/api/v1/geo/plans?')) return page([plan])
    if (url.startsWith('/api/v1/geo/topics?')) return page([])
    if (url === '/api/v1/geo/platforms') return ok([{ code: 'DOUBAO', name: '豆包', availability: 'AVAILABLE' }])
    if (url === '/api/v1/projects') return ok([])
    if (url.startsWith('/api/v1/conversations?')) return page([])
    if (url === '/api/v1/notifications/unread-count') return ok({ unreadCount: 0 })
    if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    throw new Error(`Unexpected navigation request: ${url}`)
  }))
  render(<ConfigProvider theme={appTheme}><AntdApp><QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={[path]}>
    <Routes>
      <Route path={APP_PATHS.geoPlans} element={<GeoPlansPage />} />
      <Route path={APP_PATHS.geoQuestions} element={<GeoQuestionsPage />} />
      <Route path={APP_PATHS.geoReports} element={<h1>历史报告入口</h1>} />
      <Route path={APP_PATHS.tasks} element={<h1>任务记录入口</h1>} />
    </Routes><RouteProbe />
  </MemoryRouter></QueryClientProvider></AntdApp></ConfigProvider>)
  return writes
}

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})
afterEach(() => vi.unstubAllGlobals())

describe('自动监测独立入口', () => {
  it('只呈现计划管理，保留新建计划入口且不自动启动监测', async () => {
    const writes = renderPage()
    await screen.findByText('验收定时监测计划')
    expect(screen.getByText('自动监测计划').tagName).toBe('H1')
    // Retired navigation must not exist, including hidden tabs. Use DOM presence
    // here: repeated full-table accessible-name calculation is slow in jsdom.
    expect(document.querySelector('[role="tablist"], [role="tab"]')).toBeNull()
    expect(screen.getByText('管理定时监测计划，查看执行任务和报告。创建计划不会立即执行监测。')).toBeInTheDocument()
    expect(screen.getByText('查看报告').closest('button')).toBeEnabled()
    expect(screen.getByText('查看任务').closest('button')).toBeEnabled()
    const createButton = screen.getByText('新建计划').closest('button')!
    expect(createButton).toBeEnabled()
    expect(screen.queryByText('新建自动监测计划')).not.toBeInTheDocument()
    fireEvent.click(createButton)
    await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent('planId=new'))
    expect((await screen.findByText('新建自动监测计划')).closest('[role="dialog"]')).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByText('创建计划不立即扣积分')).toBeInTheDocument()
    expect(screen.getByText('生成授权草稿').closest('button')).toBeDisabled()
    expect(writes).toEqual([])
  })

  it.each([['查看报告', APP_PATHS.geoReports], ['查看任务', APP_PATHS.tasks]] as const)('%s保留可达入口', async (label, path) => {
    const writes = renderPage()
    await userEvent.click(await screen.findByRole('button', { name: label }))
    await waitFor(() => expect(screen.getByTestId('route').textContent).toBe(path))
    expect(writes).toEqual([])
  })

  it('历史 GEO 分区仍保留原有工作台标签', async () => {
    renderPage(APP_PATHS.geoQuestions)
    for (const name of ['手动监测', '快速监测', '专题管理', '问题库']) expect(await screen.findByRole('tab', { name })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '问题库' })).toHaveAttribute('aria-selected', 'true')
  })
})

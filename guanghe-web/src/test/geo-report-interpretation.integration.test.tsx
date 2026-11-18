import type { ReactNode } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp } from 'antd'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { GeoReport, GeoReportInterpretation } from '../api/geoApi'
import { createQueryClient } from '../app/queryClient'
import { storeAuthToken } from '../auth/tokenStorage'
import { geoKeys, GeoReportDetail } from '../features/geo'
import { GeoReportDetailPage } from '../pages/geo/GeoReportDetailPage'
import { toGeoReportDetailView } from '../pages/geo/viewAdapters'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, userFixture } from './fixtures/auth'
import { geoReportDto, geoRunDto } from './fixtures/geo'

vi.mock('../layouts/UserShell', () => ({
  UserShell: ({ children }: { children: ReactNode }) => <>{children}</>,
}))

const traceId = '0123456789abcdef0123456789abcdef'
function ok(data: unknown) {
  return new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId }), {
    headers: { 'Content-Type': 'application/json' },
  })
}
function failedResponse() {
  return new Response(JSON.stringify({ code: 'INTERNAL_ERROR', message: 'private provider stack', traceId }), {
    status: 500, headers: { 'Content-Type': 'application/json' },
  })
}
function interpretation(patch: Partial<GeoReportInterpretation> = {}): GeoReportInterpretation {
  return {
    status: 'NOT_STARTED', canRetry: true, attempts: 0, maxAttempts: 2,
    requestedAt: null, finishedAt: null, failureCode: null, unavailableReason: null, retryAfter: null,
    ...patch,
  }
}
function deferredReport(state = interpretation()): GeoReport {
  return { ...geoReportDto('geo-deterministic-v1'), interpretation: state }
}
function mountReport() {
  const client = createQueryClient()
  const rendered = render(
    <AntdApp><QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/app/geo/reports/9001']}>
        <Routes><Route path="/app/geo/reports/:reportId" element={<GeoReportDetailPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider></AntdApp>,
  )
  return { ...rendered, client }
}
function mockServer(initial: GeoReport) {
  const state = { report: initial, getFailed: false, postFailed: false, gets: 0, posts: 0, runGets: 0 }
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input), 'http://localhost').pathname
    if (path === '/api/v1/geo/platforms') return ok([{ code: 'DOUBAO', name: '豆包', configured: true, selectable: true }])
    if (path === '/api/v1/geo/runs/8001') { state.runGets++; return ok(geoRunDto) }
    if (path === '/api/v1/geo/reports/9001' && init?.method === 'GET') {
      state.gets++
      return state.getFailed ? failedResponse() : ok(state.report)
    }
    if (path === '/api/v1/geo/reports/9001/reinterpretation' && init?.method === 'POST') {
      state.posts++
      if (state.postFailed) return failedResponse()
      state.report = { ...state.report, interpretation: interpretation({
        status: 'QUEUED', attempts: 1, canRetry: false, requestedAt: '2026-09-28T04:00:00Z',
      }) }
      return ok(state.report.interpretation)
    }
    throw new Error(`Unexpected test request: ${init?.method} ${path}`)
  }))
  return state
}

beforeEach(() => {
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('GEO 已存回答重新解读闭环', () => {
  it('只在显式点击后提交一次；活动状态轮询生成真实结论，终态停止且不重新采集', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const state = mockServer(deferredReport())
    mountReport()
    const button = await screen.findByRole('button', { name: '生成智能解读' })
    expect(screen.getByText('仅使用本报告已保存的回答，不重新采集，不扣新积分。')).toBeInTheDocument()
    expect(state.posts).toBe(0)
    fireEvent.click(button)
    fireEvent.click(button)
    await waitFor(() => expect(state.posts).toBe(1))
    expect(await screen.findByText('智能解读已排队')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('测试平台回答，包含品牌事实。')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '正在生成解读' })).toBeDisabled()
    state.report = { ...geoReportDto('geo-synthesis-v1'), interpretation: interpretation({
      status: 'SUCCEEDED', canRetry: false, attempts: 1, finishedAt: '2026-09-28T04:00:30Z',
    }) }
    await act(async () => { await vi.advanceTimersByTimeAsync(3_100) })
    expect(await screen.findByText('AI 综合摘要')).toBeInTheDocument()
    expect(screen.getByText('建议优先动作')).toBeInTheDocument()
    const getsAtCompletion = state.gets
    await act(async () => { await vi.advanceTimersByTimeAsync(6_000) })
    expect(state.gets).toBe(getsAtCompletion)
    expect(state.posts).toBe(1)
    expect(state.runGets).toBe(1)
    expect(screen.queryByRole('button', { name: '生成智能解读' })).not.toBeInTheDocument()
  })

  it('刷新后读取持久运行状态不隐式POST；查询失败保留证据，刷新只GET', async () => {
    const state = mockServer(deferredReport(interpretation({ status: 'RUNNING', canRetry: false, attempts: 1 })))
    const { client } = mountReport()
    expect(await screen.findByText('正在根据已保存的回答生成解读')).toBeInTheDocument()
    expect(state.posts).toBe(0)
    state.getFailed = true
    await act(async () => { await client.invalidateQueries({ queryKey: geoKeys.forOwner(userFixture.id).reportDetail('9001') }) })
    expect(await screen.findByText('暂时无法更新报告状态，已保留上次加载的结果')).toBeInTheDocument()
    expect(screen.getByText('平台问题回答与引用证据')).toBeInTheDocument()
    expect(screen.queryByText('private provider stack')).not.toBeInTheDocument()
    expect(screen.queryByText(traceId)).not.toBeInTheDocument()
    state.getFailed = false
    state.report = deferredReport(interpretation({ status: 'FAILED', attempts: 1, canRetry: true }))
    fireEvent.click(screen.getByRole('button', { name: '刷新状态' }))
    await waitFor(() => expect(state.gets).toBeGreaterThanOrEqual(3))
    await waitFor(() => expect(client.getQueryData<{ report: GeoReport }>(geoKeys.forOwner(userFixture.id).reportDetail('9001'))?.report.interpretation.status).toBe('FAILED'))
    expect(await screen.findByRole('button', { name: '重新解读' })).toBeEnabled()
    expect(state.posts).toBe(0)
    expect(screen.getByText('本报告还可尝试 1 次。')).toBeInTheDocument()
  })

  it('POST失败只查询实际状态、不自动重试，错误不暴露内部message或traceId', async () => {
    const state = mockServer(deferredReport(interpretation({ status: 'FAILED', attempts: 1 })))
    state.postFailed = true
    mountReport()
    fireEvent.click(await screen.findByRole('button', { name: '重新解读' }))
    expect(await screen.findByText('暂未确认解读请求的处理结果，已重新查询状态。请勿连续提交。')).toBeInTheDocument()
    expect(state.posts).toBe(1)
    expect(state.gets).toBeGreaterThanOrEqual(2)
    expect(screen.queryByText('private provider stack')).not.toBeInTheDocument()
    expect(screen.queryByText(traceId)).not.toBeInTheDocument()
    expect(screen.getByText('平台问题回答与引用证据')).toBeInTheDocument()
  })

  it.each([true, false])('容量冷却到期仅GET一次；服务端可重试=%s，不因attempt=2自行禁用或自动POST', async (canRetry) => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const retryAfter = new Date(Date.now() + 5_000).toISOString()
    const capacity = interpretation({
      status: 'FAILED', canRetry: false, attempts: 2,
      failureCode: 'GEO_INTERPRETATION_CAPACITY_FULL',
      unavailableReason: 'SERVICE_UNAVAILABLE', retryAfter,
    })
    const state = mockServer(deferredReport(capacity))
    mountReport()
    await vi.waitFor(() => expect(screen.getByText('稍后将自动刷新可重试状态，不会自动提交解读。')).toBeInTheDocument())
    expect(screen.getByText('解读服务繁忙，稍后可重试。本次未调用模型，不消耗解读尝试次数。')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新解读' })).not.toBeInTheDocument()
    expect(state.gets).toBe(1)
    if (canRetry) state.report = deferredReport({ ...capacity, canRetry: true, retryAfter: null, unavailableReason: null })
    await act(async () => { await vi.advanceTimersByTimeAsync(5_200) })
    expect(state.gets).toBe(2)
    if (canRetry) expect(screen.getByRole('button', { name: '重新解读' })).toBeEnabled()
    else expect(screen.queryByRole('button', { name: '重新解读' })).not.toBeInTheDocument()
    expect(screen.queryByText('本报告还可尝试 0 次。')).not.toBeInTheDocument()
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(state.gets).toBe(2)
    expect(state.posts).toBe(0)
  })

  it.each([
    ['NO_VALID_ANSWERS', 0, '本次尚未取得有效回答'],
    ['ATTEMPT_LIMIT_REACHED', 2, '本报告的解读重试次数已用完。'],
    ['ARCHIVED', 2, '这份报告已归档'],
  ] as const)('服务端资格%s不会被前端绕过', async (reason, succeeded, label) => {
    const report = deferredReport(interpretation({
      status: 'FAILED', canRetry: false, attempts: 2, unavailableReason: reason,
    }))
    report.succeededUnits = succeeded
    const state = mockServer(report)
    mountReport()
    expect(await screen.findByText(new RegExp(label))).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新解读' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '生成智能解读' })).not.toBeInTheDocument()
    expect(state.posts).toBe(0)
  })

  it('独立解读状态不清空已存在结论；更新失败仍保留旧建议且不提供重复生成', () => {
    const report = geoReportDto('geo-synthesis-v1')
    report.interpretation = interpretation({ status: 'FAILED', canRetry: false, attempts: 2 })
    const view = toGeoReportDetailView(report, geoRunDto, [])
    render(<GeoReportDetail loading={false} error={null} report={view} />)
    expect(screen.getByText('AI 综合摘要')).toBeInTheDocument()
    expect(screen.getByText('建议优先动作')).toBeInTheDocument()
    expect(screen.getByText('本次更新没有替换已保存的结论。')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新解读' })).not.toBeInTheDocument()
  })
})

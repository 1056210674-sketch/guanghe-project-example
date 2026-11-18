import type { ReactNode } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp } from 'antd'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { storeAuthToken } from '../auth/tokenStorage'
import { GeoPage } from '../pages/geo/GeoPage'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, userFixture } from './fixtures/auth'
import { geoReportDto, geoRunDto } from './fixtures/geo'

vi.mock('../layouts/UserShell', () => ({ UserShell: ({ children }: { children: ReactNode }) => <>{children}</> }))
const report = geoReportDto('geo-deterministic-v1')
function ok(data: unknown) { return new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: '0123456789abcdef0123456789abcdef' }), { headers: { 'Content-Type': 'application/json' } }) }
function page(items: unknown[], current = 1, hasNext = false) { return { items, page: current, size: 100, total: 2, hasNext } }
function Location() { const location = useLocation(); return <output aria-label="当前地址">{location.pathname}{location.search}</output> }

beforeEach(() => {
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('商家概览专题范围', () => {
  it('加载后续专题分页，切换无报告专题不会显示其他商品的旧报告，也不发无范围列表请求', async () => {
    const calls: URL[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), 'http://localhost')
      calls.push(url)
      if (url.pathname === '/api/v1/geo/topics') return ok(url.searchParams.get('page') === '2'
        ? page([{ id: 'new-topic', name: '新商品专题' }], 2)
        : page([{ id: report.topicId, name: '杯子专题' }], 1, true))
      if (url.pathname === '/api/v1/geo/platforms') return ok([{ code: 'DOUBAO', name: '豆包', configured: true, selectable: true, liveStatus: '可用' }])
      if (url.pathname.endsWith('/visibility-trend')) return ok({ topicId: url.pathname.split('/')[5], days: 30, questionScope: 'DISCOVERY', methodVersion: 'explicit-name-mention-v1', from: '2026-09-01T00:00:00Z', to: '2026-09-30T00:00:00Z', historyTruncated: false, pointLimit: 100, baselineScanLimit: 100, platforms: [], history: [] })
      if (url.pathname === '/api/v1/geo/runs') return ok(page(url.searchParams.get('topicId') === report.topicId ? [geoRunDto] : []))
      // A stale/wrong-scoped response must also be rejected by the page projection.
      if (url.pathname === '/api/v1/geo/reports') return ok(page([report]))
      if (url.pathname === `/api/v1/geo/reports/${report.id}`) return ok(report)
      if (url.pathname === `/api/v1/geo/runs/${geoRunDto.id}`) return ok(geoRunDto)
      throw new Error(`Unexpected test path: ${url.pathname}`)
    }))
    render(<AntdApp><QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={['/app/geo']}><GeoPage /><Location /></MemoryRouter></QueryClientProvider></AntdApp>)
    expect(await screen.findByRole('button', { name: '查看完整报告' })).toBeInTheDocument()
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '监测专题' }))
    fireEvent.click(await screen.findByText('新商品专题'))
    await waitFor(() => expect(screen.queryByRole('button', { name: '查看完整报告' })).not.toBeInTheDocument())
    expect(await screen.findByText('这个专题还没有监测记录。')).toBeInTheDocument()
    expect(screen.queryByText(/历史报告未保存可核验的监测名称 ·/)).not.toBeInTheDocument()
    expect(calls.some((url) => url.pathname === '/api/v1/geo/topics' && url.searchParams.get('page') === '2')).toBe(true)
    expect(calls.filter((url) => ['/api/v1/geo/reports', '/api/v1/geo/runs'].includes(url.pathname)).every((url) => Boolean(url.searchParams.get('topicId')))).toBe(true)
  })
})

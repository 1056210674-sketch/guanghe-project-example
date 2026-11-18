import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { MediaResource, MediaSubmissionDetail, MediaSubmissionSummary } from '../api/mediaApi'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { storeAuthToken } from '../auth/tokenStorage'
import { SubmissionCenterPage } from '../pages/submissions/SubmissionCenterPage'
import { toLiveMediaResource } from '../features/articles/live/mediaAdapters'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'
import { renderApp } from './renderApp'

const resource: MediaResource = {
  id: '5101', providerCode: 'TEST_MEDIA', upstreamModelId: '1', upstreamResourceId: '8001',
  name: '测试城市媒体', status: 'ACTIVE', resourceType: 'NEWS', channelCategory: '生活消费',
  region: '上海', estimatedDays: 2, capabilities: {}, saleAmountCents: 12300, chargedPoints: 12300,
  quoteExpiresAt: '2099-01-01T00:00:00Z', syncedAt: '2026-10-07T02:00:00Z',
}
const submission: MediaSubmissionSummary = {
  id: '6101', batchId: '7101', taskId: '8101', articleId: '9101', articleSnapshotId: '9102',
  mediaResourceId: resource.id, mediaName: resource.name, status: 'PUBLISHED',
  saleAmountCents: 9500, chargedPoints: 9500,
  publishedUrl: 'https://example.invalid/story?source=one',
  publishedUrls: ['https://example.invalid/story?source=one', 'http://example.invalid/story-two', 'javascript:alert(1)', 'https://person:password@example.invalid/private'],
  stableErrorCode: null, createdAt: '2026-10-06T02:00:00Z', updatedAt: '2026-10-07T02:00:00Z',
}
const detail: MediaSubmissionDetail = { ...submission, remark: '测试备注', events: [] }
function ok(data: unknown) {
  return new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), {
    headers: { 'Content-Type': 'application/json' },
  })
}
function page(items: unknown[], current = 1, total = items.length) {
  return { items, page: current, size: 20, total, hasNext: current * 20 < total }
}
function stubApi(options: { resourceError?: boolean; detailGate?: Promise<MediaSubmissionDetail>; detailErrorAfterFirst?: boolean; records?: MediaSubmissionSummary[] } = {}) {
  let detailReads = 0
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const url = new URL(String(input), 'http://localhost')
    if ((init?.method ?? 'GET') !== 'GET') throw new Error('Read-only submission center must not write')
    if (url.pathname === '/api/v1/media/resources') {
      if (options.resourceError) return new Response(JSON.stringify({ code: 'PROVIDER_NOT_CONFIGURED', message: '不可用', traceId: testTraceId }), { status: 503, headers: { 'Content-Type': 'application/json' } })
      const current = Number(url.searchParams.get('page') ?? 1)
      const active = url.searchParams.get('status') !== 'INACTIVE'
      return ok(page([{ ...resource, name: current === 1 ? resource.name : '测试第二页媒体', status: active ? 'ACTIVE' : 'INACTIVE' }], current, 41))
    }
    if (url.pathname === '/api/v1/media/submissions') {
      const current = Number(url.searchParams.get('page') ?? 1)
      const records = options.records ?? [submission,
        { ...submission, id: '6102', status: 'PUBLISHED', publishedUrl: null, publishedUrls: [] },
        { ...submission, id: '6103', status: 'SUBMIT_UNKNOWN', publishedUrl: null, publishedUrls: [] },
      ]
      const status = url.searchParams.get('status')
      return ok(page(status ? records.filter((row) => row.status === status) : records, current, 41))
    }
    if (url.pathname.startsWith('/api/v1/media/submissions/')) {
      detailReads += 1
      if (options.detailErrorAfterFirst && detailReads > 1) return new Response(JSON.stringify({ code: 'RESOURCE_NOT_FOUND', message: '不可用', traceId: testTraceId }), { status: 404, headers: { 'Content-Type': 'application/json' } })
      return ok(options.detailGate ? await options.detailGate : detail)
    }
    if (url.pathname === '/api/v1/projects') return ok([])
    if (url.pathname === '/api/v1/conversations') return ok(page([]))
    if (url.pathname === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    if (url.pathname === '/api/v1/notifications/unread-count') return ok({ unreadCount: 0 })
    throw new Error(`Unexpected test endpoint ${url.pathname}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
function ArticleRoute() {
  const location = useLocation()
  return <output>既有文章入口：{location.pathname}{location.search}</output>
}
function HistoryControls() {
  const navigate = useNavigate()
  const location = useLocation()
  return <><output aria-label="当前投稿入口">{location.pathname}{location.search}</output>
    <button onClick={() => navigate(-1)}>浏览器后退</button><button onClick={() => navigate(1)}>浏览器前进</button></>
}
function renderCenter(entry = '/app/submissions', historyControls = false) {
  const queryClient = createQueryClient()
  return render(<ConfigProvider theme={appTheme}><AntdApp><QueryClientProvider client={queryClient}>
    <MemoryRouter initialEntries={[entry]}><Routes>
      <Route path="/app/submissions" element={<SubmissionCenterPage />} />
      <Route path="/app/articles" element={<ArticleRoute />} />
      <Route path="/app/assets" element={<ArticleRoute />} />
    </Routes>{historyControls ? <HistoryControls /> : null}</MemoryRouter>
  </QueryClientProvider></AntdApp></ConfigProvider>)
}
function onlyReads(fetchMock: ReturnType<typeof stubApi>) {
  expect(fetchMock.mock.calls.every(([, init]) => (init?.method ?? 'GET') === 'GET')).toBe(true)
  expect(fetchMock.mock.calls.some(([input]) => /quotes|snapshots|confirm/.test(String(input)))).toBe(false)
}
beforeEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('独立只读投稿中心', () => {
  it('资源价格和积分必须为正整数，过期价格不能标记目录可选', () => {
    for (const value of [0, -1, Number.NaN, 1.5]) {
      for (const field of ['saleAmountCents', 'chargedPoints']) {
        const view = toLiveMediaResource({ ...resource, [field]: value })
        expect(view.orderable).toBe(false)
        expect(view.saleAmountLabel).toBe('暂无可用报价')
        expect(view.chargedPoints).toBeUndefined()
      }
    }
    expect(toLiveMediaResource({ ...resource, quoteExpiresAt: '2000-01-01T00:00:00Z' }).orderable).toBe(false)
  })
  it('正式路由有独立主导航，未选择文章也能看目录、元价、独立积分和快照时间', async () => {
    const api = stubApi()
    renderApp(['/app/submissions'])
    expect(await screen.findByRole('heading', { name: '投稿', level: 1 })).toBeInTheDocument()
    expect(within(screen.getByRole('navigation', { name: '普通用户主导航' })).getByRole('button', { name: /投稿$/ })).toHaveAttribute('aria-current', 'page')
    expect(await screen.findByText(resource.name)).toBeInTheDocument()
    expect(screen.getByText('¥123.00')).toBeInTheDocument()
    expect(screen.getByText('12300 积分')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '快照同步时间' })).toBeInTheDocument()
    expect(screen.getByText('价格来自最近同步目录，正式投稿前会再次核验价格与媒体状态。')).toBeInTheDocument()
    expect(screen.queryByText(/执行已开放|立即可投|实时可投/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /确认投稿|立即下单/ })).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '媒体目录' })).toHaveAttribute('aria-selected', 'true')
    expect(api.mock.calls.some(([input]) => String(input).startsWith('/api/v1/media/submissions?'))).toBe(false)
    onlyReads(api)
  })

  it.each(['records', 'submissions'])('正式 %s 深链直接打开投稿记录与回链，重新进入仍保持正确分栏', async (tab) => {
    const api = stubApi()
    const entry = `/app/submissions?tab=${tab}`
    const first = renderApp([entry])
    await screen.findByLabelText('查看投稿 6101')
    expect(screen.getByRole('tab', { name: '投稿记录' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('link', { name: '打开发布页面 1' })).toHaveAttribute('href', submission.publishedUrl)
    expect(api.mock.calls.some(([input]) => String(input).startsWith('/api/v1/media/submissions?'))).toBe(true)
    first.unmount()
    renderApp([entry])
    await screen.findByLabelText('查看投稿 6101')
    expect(screen.getByRole('tab', { name: '投稿记录' })).toHaveAttribute('aria-selected', 'true')
    onlyReads(api)
  })

  it.each(['invalid', 'javascript:alert(1)'])('非法 tab=%s 安全回到媒体目录，不查询投稿或发起任何写入', async (tab) => {
    const api = stubApi()
    renderCenter(`/app/submissions?tab=${encodeURIComponent(tab)}`)
    await screen.findByText(resource.name)
    expect(screen.getByRole('tab', { name: '媒体目录' })).toHaveAttribute('aria-selected', 'true')
    expect(api.mock.calls.some(([input]) => String(input).startsWith('/api/v1/media/submissions?'))).toBe(false)
    onlyReads(api)
  })

  it('分栏写入 URL 并保留其他查询参数，前进后退同步视图且不沿用旧详情', async () => {
    const api = stubApi()
    renderCenter('/app/submissions?source=article', true)
    await screen.findByText(resource.name)
    fireEvent.click(screen.getByText('投稿记录', { selector: '.ant-tabs-tab-btn' }))
    await screen.findByLabelText('查看投稿 6101')
    expect(screen.getByLabelText('当前投稿入口')).toHaveTextContent('/app/submissions?source=article&tab=records')
    fireEvent.click(screen.getByLabelText('查看投稿 6101'))
    await screen.findByText('测试备注')
    fireEvent.click(screen.getByText('媒体目录', { selector: '.ant-tabs-tab-btn' }))
    expect(screen.getByLabelText('当前投稿入口')).toHaveTextContent('/app/submissions?source=article')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    fireEvent.click(screen.getByText('浏览器后退'))
    await screen.findByLabelText('查看投稿 6101')
    expect(screen.getByRole('tab', { name: '投稿记录' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('浏览器前进'))
    expect(screen.getByRole('tab', { name: '媒体目录' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByLabelText('当前投稿入口')).toHaveTextContent('/app/submissions?source=article')
    onlyReads(api)
  })

  it('目录服务端翻页，查询价格从元换成分，筛选重置第一页且不触发报价', async () => {
    const api = stubApi()
    const user = userEvent.setup()
    renderCenter()
    await screen.findByText(resource.name)
    await user.click(screen.getByTitle('Next Page'))
    await screen.findByText('测试第二页媒体')
    await user.type(screen.getByRole('textbox', { name: '搜索媒体' }), '城市')
    await user.type(screen.getByRole('spinbutton', { name: '最低参考价（元）' }), '12.34')
    await user.type(screen.getByRole('spinbutton', { name: '最高参考价（元）' }), '125')
    await user.click(screen.getByRole('button', { name: /查\s*询/ }))
    await waitFor(() => expect(api.mock.calls.some(([input]) => {
      const url = new URL(String(input), 'http://localhost')
      return url.searchParams.get('search') === '城市' && url.searchParams.get('page') === '1'
        && url.searchParams.get('minSaleAmountCents') === '1234' && url.searchParams.get('maxSaleAmountCents') === '12500'
    })).toBe(true))
    await user.clear(screen.getByRole('spinbutton', { name: '最高参考价（元）' }))
    await user.type(screen.getByRole('spinbutton', { name: '最高参考价（元）' }), '1')
    await user.click(screen.getByRole('button', { name: /查\s*询/ }))
    expect(screen.getByText('最低参考价不能高于最高参考价。')).toBeInTheDocument()
    onlyReads(api)
  })

  it('逐媒体平铺历史金额与全部安全回链，已发布无链接和未知结果不变成重投入口', async () => {
    const api = stubApi({ detailErrorAfterFirst: true })
    const user = userEvent.setup()
    renderCenter()
    await screen.findByText(resource.name)
    await user.click(screen.getByRole('tab', { name: '投稿记录' }))
    const panel = within(screen.getByRole('tabpanel', { name: '投稿记录' }))
    // These controls have explicit labels. Direct label/text queries avoid
    // repeatedly computing visibility/styles for both retained AntD tab panes.
    await panel.findByLabelText('查看投稿 6101')
    expect(panel.getAllByText('¥95.00')).toHaveLength(3)
    expect(panel.getAllByText('下单积分')).toHaveLength(3)
    expect(panel.queryByText('消耗积分')).not.toBeInTheDocument()
    expect(panel.getByText('已发布，上游暂未提供可访问链接')).toBeInTheDocument()
    const links = panel.getAllByText(/打开发布页面/, { selector: 'a' })
    expect(links.map((link) => link.getAttribute('href'))).toEqual(['https://example.invalid/story?source=one', 'http://example.invalid/story-two'])
    expect(links.every((link) => link.getAttribute('rel') === 'noopener noreferrer')).toBe(true)
    expect(panel.getByText('正在核对')).toBeInTheDocument()
    expect(panel.queryByRole('button', { name: /重投|再次投稿|重试投稿/ })).not.toBeInTheDocument()
    await user.click(panel.getByLabelText('刷新投稿记录'))
    await user.click(panel.getByTitle('Next Page'))
    await waitFor(() => expect(api.mock.calls.some(([input]) => String(input).includes('/media/submissions?page=2'))).toBe(true))
    await user.click(panel.getByLabelText('投稿状态'))
    await user.click(screen.getByTitle('已发布'))
    await waitFor(() => expect(api.mock.calls.some(([input]) => String(input).includes('/media/submissions?status=PUBLISHED&page=1'))).toBe(true))
    await user.click(panel.getByLabelText('查看投稿 6101'))
    await screen.findByText('测试备注')
    // rc-component uses the same test-id for multiple aria-labelledby targets in test mode.
    const drawer = within(await screen.findByRole('dialog'))
    expect(drawer.getByText('投稿详情')).toBeInTheDocument()
    await user.click(drawer.getByLabelText('刷新当前记录'))
    await screen.findByText('投稿详情暂不可用，或该记录不属于当前账号。')
    expect(screen.queryByText('测试备注')).not.toBeInTheDocument()
    expect(api.mock.calls.some(([input]) => String(input) === '/api/v1/media/submissions/6101')).toBe(true)
    await user.keyboard('{Escape}')
    await user.click(await panel.findByLabelText('查看投稿 6101 的原文章'))
    expect(await screen.findByText('既有文章入口：/app/articles?article=9101')).toBeInTheDocument()
    onlyReads(api)
  })

  it('切账号清除选中详情，旧账号迟到响应不展示，新账号重新请求自己的记录', async () => {
    let resolveDetail!: (value: MediaSubmissionDetail) => void
    const detailGate = new Promise<MediaSubmissionDetail>((resolve) => { resolveDetail = resolve })
    const api = stubApi({ detailGate })
    const user = userEvent.setup()
    renderCenter()
    await screen.findByText(resource.name)
    await user.click(screen.getByRole('tab', { name: '投稿记录' }))
    await user.click(await within(screen.getByRole('tabpanel', { name: '投稿记录' })).findByRole('button', { name: '查看投稿 6101' }))
    await screen.findByText('正在加载投稿详情…')
    const before = api.mock.calls.filter(([input]) => String(input).startsWith('/api/v1/media/submissions?')).length
    act(() => useAuthStore.setState({ user: { ...userFixture, id: '1009' } }))
    await act(async () => { resolveDetail({ ...detail, remark: '旧账号迟到测试备注' }); await detailGate })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByText('旧账号迟到测试备注')).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '投稿记录' })).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(api.mock.calls.filter(([input]) => String(input).startsWith('/api/v1/media/submissions?')).length).toBeGreaterThan(before))
    onlyReads(api)
  })

  it('目录错误不伪装空数据、不阻断记录；选择文章投稿仅导航资产中心文章列表', async () => {
    const api = stubApi({ resourceError: true, records: [] })
    const user = userEvent.setup()
    renderCenter()
    expect(await screen.findByText('媒体目录暂不可用，请稍后重试。')).toBeInTheDocument()
    expect(screen.queryByText('暂无符合条件的媒体资源')).not.toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: '投稿记录' }))
    expect(await screen.findByText('暂无投稿记录')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '选择文章投稿' }))
    expect(await screen.findByText('既有文章入口：/app/assets?tab=articles')).toBeInTheDocument()
    onlyReads(api)
  })
})

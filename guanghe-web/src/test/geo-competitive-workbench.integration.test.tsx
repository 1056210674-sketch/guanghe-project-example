import type { ReactNode } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp } from 'antd'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GeoCompetitiveInsights, GeoCompetitorCandidate, GeoTopic } from '../api/geoApi'
import { createQueryClient } from '../app/queryClient'
import { storeAuthToken } from '../auth/tokenStorage'
import { GeoCandidateConfirmation, GeoCompetitiveWorkbench } from '../features/geo/components/GeoCompetitiveWorkbench'
import type { GeoCompetitiveWorkbenchProps } from '../features/geo/components/GeoCompetitiveWorkbench'
import { geoKeys } from '../features/geo/geoQueries'
import { GeoCompetitorsSection } from '../pages/geo/GeoCompetitorsSection'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, userFixture } from './fixtures/auth'
import { geoPlatformFixtures, geoReportDto } from './fixtures/geo'

const report = geoReportDto('geo-synthesis-v1')
const candidate: GeoCompetitorCandidate = {
  id: 'candidate-private-01', topicId: report.topicId, name: '远山保温杯 X-200', entityKind: 'PRODUCT', status: 'PENDING', version: 4,
  competitorId: null, existingCompetitorId: null, firstReportId: report.id, lastReportId: report.id,
  firstSeenAt: '2026-09-27T00:00:00Z', lastSeenAt: '2026-09-28T00:00:00Z',
  evidence: [{ reportId: report.id, unitKey: 'private-unit-1', quote: '远山保温杯 X-200 的杯盖易于清洁，售价 199 元。', signal: 'POSITIVE_ATTRIBUTE' }],
  learningSuggestions: ['可研究是否为自家商品补充真实的杯盖清洁说明，需先验证商品结构。'],
}
const topic: GeoTopic = { id: report.topicId, spaceId: 'space-1', productId: 'product-1', name: '保温杯专题', region: '中国大陆',
  keywords: [], questions: [], competitorIds: [], competitorSetVersion: 2, version: 7, createdAt: '2026-09-27T00:00:00Z', updatedAt: '2026-09-28T00:00:00Z' }
function insights(overrides: Partial<GeoCompetitiveInsights> = {}): GeoCompetitiveInsights {
  return { reportId: report.id, topicId: report.topicId, status: 'SUCCEEDED', retryAllowed: false, attempts: 1, maxAttempts: 2, reasonCode: null,
    questionScope: 'DISCOVERY', methodVersion: 'geo-competitive-insight-v2', evidenceTruncated: false,
    platforms: [{ platformCode: 'DOUBAO', expectedUnits: 4, validUnits: 2, failedUnits: 1, missingUnits: 1,
      entities: [
        { candidateId: null, competitorId: null, name: '自家保温杯', entityKind: 'PRODUCT', role: 'OWN', recognitionStatus: null, mentionedUnits: 0, mentionRate: 0, evidence: [] },
        { candidateId: candidate.id, competitorId: null, name: candidate.name, entityKind: 'PRODUCT', role: 'DISCOVERED', recognitionStatus: 'PENDING', mentionedUnits: 1, mentionRate: 50, evidence: candidate.evidence },
        { candidateId: null, competitorId: 'catalog-private-1', name: '层级不明品牌', entityKind: 'UNKNOWN', role: 'CONFIGURED', recognitionStatus: null, mentionedUnits: 2, mentionRate: 100, evidence: [] },
      ] }], candidates: [candidate], ...overrides }
}
function ok(data: unknown) { return new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: '0123456789abcdef0123456789abcdef' }), { headers: { 'Content-Type': 'application/json' } }) }
function page(items: unknown[]) { return { items, page: 1, size: 20, total: items.length, hasNext: false } }
function Location() { const location = useLocation(); return <output aria-label="当前地址">{location.pathname}{location.search}</output> }
function app(children: ReactNode, path = `/app/geo/competitors?topicId=${report.topicId}`) {
  return <AntdApp><QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={[path]}>{children}<Location /></MemoryRouter></QueryClientProvider></AntdApp>
}
function presentation(overrides: Partial<GeoCompetitiveWorkbenchProps> = {}) {
  const props: GeoCompetitiveWorkbenchProps = {
    topics: [{ id: topic.id, label: topic.name }], selectedTopicId: topic.id, reports: [{ id: report.id, label: '本次报告' }], selectedReportId: report.id,
    reportPage: 1, reportTotal: 1, platforms: geoPlatformFixtures, questionScope: 'DISCOVERY', insights: insights(),
    candidates: page([candidate]) as GeoCompetitiveWorkbenchProps['candidates'], candidateStatus: 'PENDING', candidatePage: 1, confirmationReady: true,
    formalManager: <span>正式集合编辑器</span>, onTopicChange: vi.fn(), onReportChange: vi.fn(), onReportPageChange: vi.fn(), onScopeChange: vi.fn(),
    onAnalyze: vi.fn(), onRefresh: vi.fn(), onCandidateStatusChange: vi.fn(), onCandidatePageChange: vi.fn(), onConfirmCandidate: vi.fn(),
    onIgnoreCandidate: vi.fn(), onViewEvidence: vi.fn(), onViewReport: vi.fn(), onManageCatalog: vi.fn(), ...overrides,
  }
  return { props, tree: app(<GeoCompetitiveWorkbench {...props} />) }
}

function server(initial = insights(), failPost = false, confirmConflict = false) {
  let state = initial
  let savedCandidate = { ...candidate }
  const calls: Array<{ path: string; method: string; query: URLSearchParams; body?: Record<string, unknown> }> = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost')
    const method = init?.method ?? 'GET'
    calls.push({ path: url.pathname, method, query: url.searchParams, body: init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : undefined })
    if (url.pathname === '/api/v1/geo/topics') return ok(page([topic, { ...topic, id: 'empty-topic', name: '暂无报告专题' }]))
    if (url.pathname === `/api/v1/geo/topics/${topic.id}`) return ok(topic)
    if (url.pathname === '/api/v1/geo/topics/empty-topic') return ok({ ...topic, id: 'empty-topic', name: '暂无报告专题' })
    if (url.pathname === '/api/v1/geo/reports') return ok(page(url.searchParams.get('topicId') === topic.id ? [report] : []))
    if (url.pathname === '/api/v1/geo/platforms') return ok([{ code: 'DOUBAO', name: '豆包', configured: true, selectable: true, liveStatus: '可用' }])
    if (url.pathname === '/api/v1/spaces/space-1/competitors') return ok(page([]))
    if (url.pathname.endsWith('/competitive-insights/analyze')) {
      state = insights({ status: failPost ? 'RUNNING' : 'SUCCEEDED', retryAllowed: false })
      if (failPost) throw new TypeError('Response lost after submission')
      return ok(state)
    }
    if (url.pathname.endsWith('/competitive-insights')) return ok({ ...state, questionScope: url.searchParams.get('questionScope') ?? 'DISCOVERY' })
    if (url.pathname.endsWith('/competitor-candidates')) return ok(page(url.pathname.includes('empty-topic') || url.searchParams.get('status') !== savedCandidate.status ? [] : [savedCandidate]))
    if (url.pathname.endsWith('/confirm')) {
      if (confirmConflict) {
        savedCandidate = { ...savedCandidate, status: 'IGNORED', version: 5 }
        return new Response(JSON.stringify({ code: 'VERSION_CONFLICT', message: 'candidate-private-01 version mismatch' }), { status: 409, headers: { 'Content-Type': 'application/json' } })
      }
      savedCandidate = { ...savedCandidate, status: 'CONFIRMED', version: 5, competitorId: 'catalog-added-1' }
      return ok({ candidate: savedCandidate, topicVersion: 8, competitorSetVersion: 3, existingPlansChanged: false })
    }
    if (url.pathname.endsWith('/ignore')) { savedCandidate = { ...savedCandidate, status: 'IGNORED', version: 5 }; return ok(savedCandidate) }
    throw new Error(`Unexpected test path: ${url.pathname}`)
  }))
  return calls
}

beforeEach(() => {
  vi.unstubAllGlobals()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('竞品洞察工作台', () => {
  it('展示同批有效分母、真实零提及和层级不可比，不给竞争排名；证据链接指向原回答', () => {
    const { props, tree } = presentation()
    render(tree)
    expect(screen.getByText('有效 2 / 计划 4 份回答 · 失败 1 · 缺失 1')).toBeInTheDocument()
    expect(screen.getByText('0%')).toBeInTheDocument()
    expect(screen.getByText('50%')).toBeInTheDocument()
    expect(screen.getByText('1 / 2 份有效回答')).toBeInTheDocument()
    expect(screen.getByText('不可直接比较')).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '排名' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '查看证据与借鉴' }))
    expect(screen.getByText(candidate.evidence[0].quote)).toBeInTheDocument()
    expect(screen.getByText(candidate.learningSuggestions[0])).toBeInTheDocument()
    expect(screen.queryByText(/不证明对方表现更好/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '查看原始回答 1' }))
    expect(props.onViewEvidence).toHaveBeenCalledWith(report.id, 'private-unit-1')
    expect(screen.queryByText(candidate.id)).not.toBeInTheDocument()
    expect(props.onAnalyze).not.toHaveBeenCalled()
  })

  it('无有效回答不是零可见度，服务端不允许首次分析时不提供触发按钮', () => {
    const data = insights({ status: 'NOT_ANALYZED', retryAllowed: false, reasonCode: 'NO_VALID_ANSWERS' })
    data.platforms[0].validUnits = 0
    data.platforms[0].entities = [{ ...data.platforms[0].entities[0], mentionedUnits: null, mentionRate: null }]
    render(presentation({ insights: data }).tree)
    expect(screen.getByText('暂不可评估')).toBeInTheDocument()
    expect(screen.getByText(/未取得有效回答/)).toBeInTheDocument()
    expect(screen.queryByText('0%')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '分析这份报告的竞品' })).not.toBeInTheDocument()
    expect(screen.queryByText('NO_VALID_ANSWERS')).not.toBeInTheDocument()
  })

  it('成功缓存只读；切换题型、候选状态与无报告专题不触发模型或混入其他专题报告', async () => {
    const calls = server()
    render(app(<GeoCompetitorsSection />))
    expect(await screen.findByText('50%')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('radio', { name: '品牌验证问题' }))
    expect(await screen.findByText(/这些问题已点名本批自家或竞品对象/)).toBeInTheDocument()
    await waitFor(() => expect(calls.some((call) => call.path.endsWith('/competitive-insights') && call.query.get('questionScope') === 'BRAND_VALIDATION')).toBe(true))
    fireEvent.click(screen.getByRole('radio', { name: '已忽略' }))
    expect(await screen.findByText(/目前没有已忽略的候选/)).toBeInTheDocument()
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '竞品洞察专题' }))
    fireEvent.click(await screen.findByText('暂无报告专题'))
    await waitFor(() => expect(screen.queryByText('50%')).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '查看本次完整报告' })).not.toBeInTheDocument()
    expect(calls.every((call) => call.method === 'GET')).toBe(true)
    expect(calls.filter((call) => call.path === '/api/v1/geo/reports').every((call) => Boolean(call.query.get('topicId')))).toBe(true)
  })

  it('历史分析仅由明确点击提交一次，丢失POST响应后读取持久状态，不自动重试', async () => {
    const calls = server(insights({ status: 'NOT_ANALYZED', retryAllowed: true, attempts: 0, platforms: [], candidates: [] }), true)
    render(app(<GeoCompetitorsSection />))
    const button = await screen.findByRole('button', { name: '分析这份报告的竞品' })
    expect(calls.every((call) => call.method === 'GET')).toBe(true)
    fireEvent.click(button)
    fireEvent.click(button)
    expect(await screen.findByText('正在分析回答中的竞品')).toBeInTheDocument()
    const posts = calls.filter((call) => call.method === 'POST')
    expect(posts).toHaveLength(1)
    expect(posts[0].path).toBe(`/api/v1/geo/reports/${report.id}/competitive-insights/analyze`)
    expect(posts[0].body).toBeUndefined()
    expect(screen.queryByRole('button', { name: '分析这份报告的竞品' })).not.toBeInTheDocument()
  })

  it('确认弹窗明确后续范围；确认前无写入，提交乐观锁且不创建付费任务/更改计划', async () => {
    const calls = server()
    render(app(<GeoCompetitorsSection />))
    const confirm = await screen.findByRole('button', { name: '确认或关联' })
    await waitFor(() => expect(confirm).toBeEnabled())
    fireEvent.click(confirm)
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/不修改已有自动计划的授权范围/)).toBeInTheDocument()
    expect(calls.every((call) => call.method === 'GET')).toBe(true)
    fireEvent.click(within(dialog).getByRole('button', { name: '确认保存' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const posts = calls.filter((call) => call.method === 'POST')
    expect(posts).toHaveLength(1)
    expect(posts[0].path).toBe(`/api/v1/geo/competitor-candidates/${candidate.id}/confirm`)
    expect(posts[0].body).toEqual({ version: 4, topicVersion: 7, existingCompetitorId: null, type: 'DIRECT', addToTopic: true })
    expect(await screen.findByText(/目前没有待确认的候选/)).toBeInTheDocument()
  })

  it('忽略候选需确认，持久忽略可查看且没有再次确认/自动复活入口', async () => {
    const calls = server()
    render(app(<GeoCompetitorsSection />))
    fireEvent.click(await screen.findByRole('button', { name: '忽略' }))
    expect(calls.every((call) => call.method === 'GET')).toBe(true)
    fireEvent.click(await screen.findByRole('button', { name: '确认忽略' }))
    expect(await screen.findByText(/目前没有待确认的候选/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('radio', { name: '已忽略' }))
    expect(await screen.findByRole('button', { name: '查看证据与借鉴' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '确认或关联' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '忽略' })).not.toBeInTheDocument()
    expect(calls.filter((call) => call.method === 'POST').map((call) => call.body)).toEqual([{ version: 4 }])
  })

  it('洞察缓存按账号/报告/题型隔离，候选缓存按状态分页隔离', () => {
    const key = geoKeys.forOwner('owner-a').competitiveInsight(report.id, { questionScope: 'DISCOVERY' })
    expect(key).not.toEqual(geoKeys.forOwner('owner-b').competitiveInsight(report.id, { questionScope: 'DISCOVERY' }))
    expect(key).not.toEqual(geoKeys.forOwner('owner-a').competitiveInsight(report.id, { questionScope: 'BRAND_VALIDATION' }))
    expect(geoKeys.forOwner('owner-a').competitorCandidateList(topic.id, { status: 'PENDING', page: 1 }))
      .not.toEqual(geoKeys.forOwner('owner-a').competitorCandidateList(topic.id, { status: 'IGNORED', page: 1 }))
  })

  it('失败重试资格只服从服务端；容量拒绝未执行时即使显示第二次也不擅自禁用', () => {
    const { props, tree } = presentation({ insights: insights({ status: 'FAILED', attempts: 2, retryAllowed: true, reasonCode: 'CAPACITY_COOLDOWN' }) })
    const view = render(tree)
    fireEvent.click(screen.getByRole('button', { name: '重试竞品分析' }))
    expect(props.onAnalyze).toHaveBeenCalledOnce()
    view.rerender(presentation({ insights: insights({ status: 'FAILED', attempts: 2, retryAllowed: false, reasonCode: 'ATTEMPT_LIMIT_REACHED' }) }).tree)
    expect(screen.queryByRole('button', { name: '重试竞品分析' })).not.toBeInTheDocument()
    expect(screen.getByText(/已达到分析尝试上限/)).toBeInTheDocument()
    expect(screen.queryByText('ATTEMPT_LIMIT_REACHED')).not.toBeInTheDocument()
  })

  it('已有匹配默认关联且不泄露资料ID；可只确认资料不纳入专题', async () => {
    const onSubmit = vi.fn()
    render(app(<GeoCandidateConfirmation candidate={{ ...candidate, existingCompetitorId: 'catalog-private-1' }}
      competitors={[{ id: 'catalog-private-1', name: candidate.name, aliases: [], competitorType: 'INDIRECT' }]}
      submitting={false} onCancel={vi.fn()} onSubmit={onSubmit} />))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('radio', { name: '新建正式竞品' })).toBeDisabled()
    expect(within(dialog).getByRole('radio', { name: '关联已有竞品' })).toBeChecked()
    expect(within(dialog).queryByText('catalog-private-1')).not.toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('checkbox', { name: '同时纳入当前专题的正式竞品集合' }))
    fireEvent.click(within(dialog).getByRole('button', { name: '确认保存' }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith({ existingCompetitorId: 'catalog-private-1', type: 'INDIRECT', addToTopic: false }))
  })

  it('确认冲突后刷新状态并保留弹窗草稿，不显示内部错误，也不重试已被忽略的候选', async () => {
    const calls = server(insights(), false, true)
    render(app(<GeoCompetitorsSection />))
    const confirm = await screen.findByRole('button', { name: '确认或关联' })
    await waitFor(() => expect(confirm).toBeEnabled())
    fireEvent.click(confirm)
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('checkbox', { name: '同时纳入当前专题的正式竞品集合' }))
    fireEvent.click(within(dialog).getByRole('button', { name: '确认保存' }))
    expect(await within(dialog).findByText(/资料或候选状态已变化/)).toBeInTheDocument()
    expect(within(dialog).getByRole('checkbox', { name: '同时纳入当前专题的正式竞品集合' })).not.toBeChecked()
    expect(screen.queryByText(/version mismatch/)).not.toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: '确认保存' }))
    expect(await within(dialog).findByText(/已不在待确认列表/)).toBeInTheDocument()
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(1)
  })

  it('接口返回其他专题的数据不展示可见度或分析按钮', async () => {
    server(insights({ topicId: 'other-topic', status: 'NOT_ANALYZED', retryAllowed: true }))
    render(app(<GeoCompetitorsSection />))
    expect(await screen.findByText(/返回的报告范围与当前选择不一致/)).toBeInTheDocument()
    expect(screen.queryByText('50%')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '分析这份报告的竞品' })).not.toBeInTheDocument()
  })
})

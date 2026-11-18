import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, useLocation, useSearchParams } from 'react-router-dom'
import { useState } from 'react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { GeoPeriodReport } from '../api/geoPeriodReportApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { ChatComposer } from '../features/chat/components/ChatComposer'
import { PeriodReportForm } from '../features/geo-period-reports/PeriodReportForm'
import { PeriodReportPreview } from '../features/geo-period-reports/PeriodReportPreview'
import { periodDateError, previousCompleteWeek } from '../features/geo-period-reports/dateRange'
import { usePeriodReportWorkspace } from '../pages/chat/usePeriodReportWorkspace'
import { renderApp } from './renderApp'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const date = '2026-10-09T03:00:00Z'
const project = { id: '201', name: '小行星咖啡店', status: 'ACTIVE' as const, version: 1, createdAt: date, updatedAt: date }
const report: GeoPeriodReport = {
  id: '901', version: 1, projectId: project.id, projectName: project.name, title: '小行星阶段成果', focus: '',
  startDate: '2026-09-28', endDate: '2026-10-04', timezone: 'Asia/Shanghai', snapshotAt: date,
  status: 'READY', summaryAttempts: 1, canRetrySummary: false, summaryMarkdown: '## 阶段总结\n已有数据已整理。', summaryError: null, empty: false,
  metrics: { monitorRuns: 3, completedMonitorRuns: 2, partialMonitorRuns: 1, failedMonitorRuns: 0, runningMonitorRuns: 0,
    expectedSamples: 9, validSamples: 7, failedSamples: 1, missingSamples: 1, createdArticles: 2, publishedPlacements: 1,
    publishedArticles: 1, publishedLinks: 1, pendingSubmissions: 0, failedSubmissions: 0 },
  topics: [{ topicId: '301', topicName: '广州咖啡', questionScope: 'DISCOVERY', baselineKind: 'PERIOD_FIRST', platforms: [{
    platformCode: 'KIMI', baselineReportId: '801', baselineObservedAt: date, endReportId: '803', endObservedAt: date,
    baselineMentionRate: null, endMentionRate: null, deltaPercentagePoints: null, commonSamples: 0, comparisonStatus: 'NOT_COMPARABLE', comparisonReason: 'QUESTION_CHANGED',
    points: [50, 60, 70].map((mentionRate, index) => ({ reportId: String(801 + index), runId: String(701 + index), observedAt: date,
      mentionRate, validSamples: 3, failedSamples: 0, missingSamples: 0, comparisonStatus: index === 2 ? 'COMPARABLE' : 'NOT_COMPARABLE',
      comparisonReason: index === 1 ? 'QUESTION_CHANGED' : null, connectToPrevious: index === 2 })),
  }] }], articles: [], publications: [], limitations: ['不证明发文导致可见度提升。'],
}
const ok = (data: unknown, status = 200) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { status, headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  vi.unstubAllGlobals(); sessionStorage.clear(); localStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

it('加号明确进入周期报告；默认上一完整上海周，未来/超过92天阻止提交，只有生成按钮触发', async () => {
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date(date))
  expect(previousCompleteWeek(new Date('2026-10-04T16:30:00Z'))).toEqual({ startDate: '2026-09-28', endDate: '2026-10-04' })
  expect(periodDateError('2026-07-01', '2026-09-30')).toBeUndefined()
  const send = vi.fn(), generate = vi.fn()
  function View() {
    const [open, setOpen] = useState(false)
    return <><ChatComposer value="原来的聊天输入" isGenerating={false} sendEnabled onChange={vi.fn()} onSend={send} onStop={vi.fn()} onCreatePeriodReport={() => setOpen(true)} />
      {open ? <PeriodReportForm projects={[project]} defaultProjectId="201" projectsLoading={false} submitting={false} onRetryProjects={vi.fn()} onGenerate={generate} onClose={() => setOpen(false)}
        history={[]} historyLoading={false} onProjectChange={vi.fn()} onOpenSaved={vi.fn()} onRetryHistory={vi.fn()} /> : null}</>
  }
  render(<View />)
  await userEvent.click(screen.getByRole('button', { name: '添加资料' }))
  await userEvent.click(await screen.findByText('导出周报 / 阶段报告'))
  expect(screen.getByLabelText('报告开始日期')).toHaveValue('2026-09-28')
  expect(screen.getByLabelText('报告结束日期')).toHaveValue('2026-10-04')
  expect(generate).not.toHaveBeenCalled(); expect(send).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('报告结束日期'), { target: { value: '2026-10-10' } })
  expect(screen.getByRole('button', { name: '生成报告' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('报告结束日期'), { target: { value: '2026-09-30' } })
  fireEvent.change(screen.getByLabelText('报告开始日期'), { target: { value: '2026-06-30' } })
  expect(screen.getByRole('button', { name: '生成报告' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('报告开始日期'), { target: { value: '2026-07-01' } })
  await userEvent.click(screen.getByRole('button', { name: '生成报告' }))
  expect(generate).toHaveBeenCalledWith({ projectId: '201', startDate: '2026-07-01', endDate: '2026-09-30', title: '', focus: '' })
  expect(send).not.toHaveBeenCalled(); expect(screen.getByLabelText('消息输入')).toHaveValue('原来的聊天输入')
})

it('总结失败保留数据预览、PDF与重试；不可比点断线且不显示假提升', async () => {
  const retry = vi.fn(), download = vi.fn()
  const { container } = render(<MemoryRouter><PeriodReportPreview report={{ ...report, status: 'SUMMARY_UNAVAILABLE', canRetrySummary: true, summaryMarkdown: null }}
    loading={false} retrying={false} downloading={false} onRefresh={vi.fn()} onRetrySummary={retry} onDownload={download} onNewReport={vi.fn()} /></MemoryRouter>)
  expect(screen.queryByText('报告已生成')).not.toBeInTheDocument()
  expect(screen.getByText('不作比较')).toBeInTheDocument()
  expect(container.querySelectorAll('svg line[stroke="#d6453d"]')).toHaveLength(1)
  expect(screen.getByText(/已返回回链 1 条/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '重试总结' })); expect(retry).toHaveBeenCalledOnce()
  await userEvent.click(screen.getByRole('button', { name: '导出 PDF' })); expect(download).toHaveBeenCalledOnce()
})

function Harness() {
  const [params, setParams] = useSearchParams()
  const projectId = params.get('project') ?? '201'
  const work = usePeriodReportWorkspace({ ownerId: userFixture.id, scopeKey: `${userFixture.id}:${projectId}`, reportId: params.get('periodReportId') ?? undefined,
    defaultProjectId: projectId, onSelectReport: (id) => setParams(id ? { project: projectId, periodReportId: id } : { project: projectId }) })
  return <><output data-testid="route">{params.toString()}</output><button onClick={work.openForm}>打开报告表单</button>
    <button disabled={work.projectsLoading || !work.projects.length} onClick={() => void work.generate({ projectId, startDate: '2026-09-28', endDate: '2026-10-04', title: '', focus: '' })}>生成当前项目报告</button>
    <button onClick={() => setParams({ project: '202' })}>切换客户项目</button>
    {work.reportOpen ? <PeriodReportPreview report={work.report} loading={work.loading} loadError={work.loadError} actionError={work.actionError}
      retrying={work.retrying} downloading={work.downloading} onRefresh={work.refreshReport} onRetrySummary={() => void work.retrySummary()}
      onDownload={() => void work.downloadPdf()} onNewReport={work.openForm} /> : null}</>
}
function mount(entry: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={[entry]}><Harness /></MemoryRouter></QueryClientProvider>)
}

it('刷新深链只读恢复已保存客户报告，PDF使用授权接口，不触发聊天/监测/付费动作', async () => {
  const calls: { path: string; method: string; authorization: string | null }[] = []
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const path = String(input); calls.push({ path, method: init?.method ?? 'GET', authorization: new Headers(init?.headers).get('Authorization') })
    if (path === '/api/v1/geo/period-reports/901') return ok(report)
    if (path.endsWith('/901/export?version=1')) return new Response('%PDF-test', { headers: { 'Content-Type': 'application/pdf' } })
    throw new Error(`Unexpected request ${path}`)
  }))
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:period-test'); vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
  mount('/app/chat?project=201&periodReportId=901')
  expect(await screen.findByText('小行星阶段成果')).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '导出 PDF' }))
  await waitFor(() => expect(click).toHaveBeenCalledOnce())
  expect(calls).toHaveLength(2)
  expect(calls.every((item) => item.method === 'GET' && item.authorization === `Bearer ${testAccessToken}`)).toBe(true)
})

it('创建请求仅使用显式项目接口；切换客户后迟到响应不得打开旧客户报告', async () => {
  let finish: ((value: Response) => void) | undefined
  const writes: { path: string; body: Record<string, unknown> }[] = []
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const path = String(input)
    if (path === '/api/v1/projects') return ok([project, { ...project, id: '202', name: '艾尼亚科' }])
    if (path.includes('/period-reports?')) return ok({ items: [], total: 0, page: 1, size: 10, hasNext: false })
    if (path === '/api/v1/geo/period-reports' && init?.method === 'POST') {
      writes.push({ path, body: JSON.parse(String(init.body)) }); return new Promise<Response>((resolve) => { finish = resolve })
    }
    throw new Error(`Unexpected request ${path}`)
  }))
  mount('/app/chat?project=201')
  await userEvent.click(screen.getByText('打开报告表单'))
  await waitFor(() => expect(screen.getByText('生成当前项目报告')).toBeEnabled())
  await userEvent.click(screen.getByText('生成当前项目报告'))
  await waitFor(() => expect(writes).toHaveLength(1))
  expect(writes[0].body).toMatchObject({ projectId: '201', startDate: '2026-09-28', endDate: '2026-10-04' })
  expect(writes[0].body.requestId).toMatch(/^[0-9a-f-]{36}$/)
  await userEvent.click(screen.getByText('切换客户项目'))
  await act(async () => { finish?.(ok({ ...report, status: 'QUEUED' }, 202)) })
  expect(screen.getByTestId('route')).toHaveTextContent('project=202')
  expect(screen.getByTestId('route')).not.toHaveTextContent('periodReportId')
  expect(screen.queryByText('小行星阶段成果')).not.toBeInTheDocument()
  expect(writes).toHaveLength(1)
})

it('真实新任务页切换客户项目时关闭上一客户周报，保留智能体且不创建任何任务', async () => {
  await import('../pages/chat/ChatPage')
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: query.includes('min-width'), media: query, onchange: null,
    addListener: () => undefined, removeListener: () => undefined, addEventListener: () => undefined, removeEventListener: () => undefined, dispatchEvent: () => false }))
  const writes: string[] = []
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const path = String(input), method = init?.method ?? 'GET'
    if (method !== 'GET') writes.push(path)
    if (path === '/api/v1/agents') return ok([{ agentKey: 'geo', version: '1.0.0', name: 'GEO 智能体', description: 'GEO 分析', capabilities: ['chat'], status: 'PUBLISHED' }])
    if (path === '/api/v1/agent-installations') return ok([{ id: '101', agentKey: 'geo', agentVersion: '1.0.0', name: 'GEO 智能体', enabled: true, version: 1, createdAt: date }])
    if (path === '/api/v1/projects') return ok([project, { ...project, id: '202', name: '艾尼亚科' }])
    if (path === '/api/v1/geo/period-reports/901') return ok(report)
    if (path.startsWith('/api/v1/conversations?') || path.startsWith('/api/v1/spaces?') || path.startsWith('/api/v1/tasks?')) return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
    if (path === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    if (path === '/api/v1/notifications/unread-count') return ok({ unreadCount: 0 })
    return new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'not found', traceId: testTraceId }), { status: 404, headers: { 'Content-Type': 'application/json' } })
  }))
  function RouteProbe() { const location = useLocation(); return <output data-testid="full-page-route">{location.pathname}{location.search}</output> }
  renderApp(['/app?installation=101&project=201&periodReportId=901'], <RouteProbe />)
  expect(await screen.findByRole('heading', { name: '小行星阶段成果' })).toBeInTheDocument()
  expect(screen.getByLabelText('客户报告画布')).toBeInTheDocument()
  const projectSelect = screen.getByRole('combobox', { name: '任务所属项目' })
  await waitFor(() => expect(projectSelect).toBeEnabled())
  await userEvent.click(projectSelect)
  await userEvent.click(await screen.findByText('艾尼亚科', { selector: '.ant-select-item-option-content' }))
  await waitFor(() => expect(screen.getByTestId('full-page-route')).toHaveTextContent('/app?installation=101&project=202'))
  expect(screen.getByTestId('full-page-route')).not.toHaveTextContent('periodReportId')
  expect(screen.queryByLabelText('客户报告画布')).not.toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: '小行星阶段成果' })).not.toBeInTheDocument()
  expect(writes).toEqual([])
})

import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import type { GeoDiagnostic, GeoDiagnosticReport, GeoDiagnosticSnapshot } from '../api/geoDiagnosticApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { GeoDiagnosticCardContainer } from '../pages/chat/GeoDiagnosticCardContainer'
import { useGeoDiagnosticReport, mergeDiagnosticReport } from '../pages/geo/useGeoDiagnosticReport'
import { GeoDiagnosticReportView } from '../features/geo-diagnostics/GeoDiagnosticReportView'
import { diagnosticKeys, mergeGeoDiagnostic } from '../features/geo-diagnostics/queries'
import { GeoDiagnosticReportLibrary } from '../pages/geo/GeoDiagnosticReportLibrary'
import { ChatComposer } from '../features/chat/components/ChatComposer'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { headers: { 'Content-Type': 'application/json' } })
const forbidden = () => new Response(JSON.stringify({ code: 'FORBIDDEN', message: 'Forbidden', details: {}, traceId: testTraceId }), { status: 403, headers: { 'Content-Type': 'application/json' } })
const profile = { brandName: '小行星咖啡', website: 'https://asteroid.example', introduction: '广州咖啡门店', brandTerms: ['小行星咖啡'], region: '广州' }
const snapshot = (record: GeoDiagnostic): GeoDiagnosticSnapshot => structuredClone({ version: record.version, profile: record.profile, competitors: record.competitors, discoveryQuestions: record.discoveryQuestions, reputationQuestions: record.reputationQuestions, platformCodes: record.platformCodes, sources: record.sources, warnings: record.warnings })
function prepared(): GeoDiagnostic {
  const record: GeoDiagnostic = { id: '901', projectId: '201', conversationId: '501', version: 10, step: 6, brandName: profile.brandName, profile, competitors: [],
    discoveryQuestions: Array.from({ length: 5 }, (_, i) => ({ id: `d-${i}`, text: `广州哪里有适合场景${i}的咖啡店？`, origin: 'AI', edited: false })),
    reputationQuestions: Array.from({ length: 3 }, (_, i) => ({ id: `r-${i}`, text: `小行星咖啡体验${i}如何？`, origin: 'AI', edited: false })),
    platformCodes: ['DOUBAO', 'KIMI'], sources: [], warnings: [], generation: null, cards: [], submissionEnabled: true, delivery: null,
    quote: { id: '11111111-1111-4111-8111-111111111111', version: 10, inputHash: 'f'.repeat(64), expectedSamples: 16, points: 16, expiresAt: '2099-01-01T00:00:00Z', blockedReason: null, availablePoints: 100, maxPointsPerAction: 50, maxPointsPerDay: 200 } }
  record.cards = [{ step: 2, messageId: '9900', snapshot: snapshot(record) }, { step: 6, messageId: '9901', snapshot: snapshot(record) }]
  return record
}
const delivery: NonNullable<GeoDiagnostic['delivery']> = { taskId: '701', runId: '702', status: 'SAMPLING', samplingStatus: 'RUNNING', hasNoData: false, errorCode: null, canRetry: false, progressPercent: 20, reportVersion: 1 }
const submitted = () => ({ ...prepared(), delivery: structuredClone(delivery) })
function reportFixture(): GeoDiagnosticReport {
  return { id: '901', version: 12, reportVersion: 1, taskId: '701', runId: '702', status: 'READY', samplingStatus: 'PARTIAL', hasNoData: false,
    analysisAttempts: 1, pdfAttempts: 1, canRetry: false, errorCode: null, quotedPoints: 16, settledPoints: 14, releasedPoints: 2, profile, platformCodes: ['DOUBAO', 'KIMI'],
    metrics: { expectedSamples: 16, validSamples: 14, failedSamples: 2, missingSamples: 0,
      discovery: [{ platformCode: 'DOUBAO', expectedSamples: 5, validSamples: 5, failedSamples: 0, missingSamples: 0, mentions: [{ brandId: 'OWN', name: '小行星咖啡', mentionedSamples: 2, mentionRate: 0.4 }] }, { platformCode: 'KIMI', expectedSamples: 5, validSamples: 0, failedSamples: 2, missingSamples: 3, mentions: [{ brandId: 'OWN', name: '小行星咖啡', mentionedSamples: 0, mentionRate: null }] }],
      reputation: [{ platformCode: 'DOUBAO', expectedSamples: 3, validSamples: 3, failedSamples: 0, missingSamples: 0, recognizedSamples: 3 }] },
    analysis: { title: '小行星咖啡品牌诊断', summary: '部分平台自然提及，口碑回答须单独核对。', findings: [{ section: 'DISCOVERY', heading: '已有自然提及证据', description: '豆包有效发现型回答提及品牌。', evidence: [{ unitKey: '101:DOUBAO:1', sourceId: null, quote: '小行星咖啡适合休息' }] }], recommendations: [{ priority: 'P1', title: '补充门店事实', action: '公开服务范围和顾客关心的信息。', basisEvidence: [] }], contentTopics: [{ title: '广州天河休息场景咖啡店', audienceQuestion: '天河哪里适合喝咖啡？', angle: '明确门店适合场景，不捏造排名。', coveredBrandTerms: ['小行星咖啡'], basisEvidence: [] }], limitations: ['这是单次采样，不能代表全部用户。'] },
    samples: [{ unitKey: '101:DOUBAO:1', questionId: '101', category: 'BRAND_DISCOVERY', questionText: '广州哪里适合休息喝咖啡？', platformCode: 'DOUBAO', status: 'SUCCEEDED', answerExcerpt: '小行星咖啡适合休息。', answerTruncated: true, stableErrorCode: null, sourceIds: ['s1'] }],
    sources: [{ id: 's1', title: '门店公开介绍', url: 'http://example.com/source', snippet: '门店信息摘录' }], limitations: [], snapshotAt: '2026-10-14T04:00:00Z', pdf: { filename: '小行星咖啡诊断.pdf', sizeBytes: 1024, sha256: 'a'.repeat(64) } }
}
beforeEach(() => { localStorage.clear(); sessionStorage.clear(); storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt }); useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null }) })
afterEach(() => vi.restoreAllMocks())
function mount(children: React.ReactNode, client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 60000 } } })) {
  return { ...render(<MemoryRouter><QueryClientProvider client={client}>{children}</QueryClientProvider></MemoryRouter>), client }
}
function server(record = prepared(), report = reportFixture()) {
  const calls: { path: string; method: string; body: Record<string, unknown>; authorization: string | null }[] = []
  let loseSubmit = false, denyReport = false
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, options) => {
    const path = String(input), method = options?.method ?? 'GET', body = options?.body ? JSON.parse(String(options.body)) as Record<string, unknown> : {}
    calls.push({ path, method, body, authorization: new Headers(options?.headers).get('Authorization') })
    if (path === '/api/v1/geo/diagnostics/901') return ok(record)
    if (path === '/api/v1/geo/platforms') return ok(record.platformCodes.map((code) => ({ code, name: code === 'DOUBAO' ? '豆包' : 'Kimi', configured: true, selectable: true, liveStatus: null })))
    if (path.endsWith('/submit')) {
      if (loseSubmit) throw new TypeError('network response lost')
      record.version += 1; record.delivery = structuredClone(delivery); return ok(record)
    }
    if (path.endsWith('/report')) return denyReport ? forbidden() : ok(report)
    if (path.endsWith('/report/retry')) { report.version += 1; report.status = 'PDF_RUNNING'; report.canRetry = false; return ok(report) }
    if (path.includes('/report/download-url')) return ok({ downloadUrl: 'https://private.example/pdf?v=1&signature=test-only', expiresAt: '2099-01-01T00:00:00Z', filename: '小行星咖啡诊断.pdf' })
    if (path.includes('/report/observations')) return ok({ page: Number(new URL(path, 'https://test.example').searchParams.get('page')), size: 10, total: 11, hasNext: false, items: [{ unitKey: '101:DOUBAO:1', questionId: '101', questionText: '广州哪里适合休息喝咖啡？', platformCode: 'DOUBAO', sampleNo: 1, status: 'SUCCEEDED', answerText: '完整原始回答，不限于分析摘录。\n第二段保留。', stableErrorCode: null, provider: 'TEST', model: 'test', startedAt: '2026-10-14T04:00:00Z', finishedAt: '2026-10-14T04:00:01Z', latencyMs: 1000, usage: { inputTokens: 1, outputTokens: 1, searchRequests: 0, monetaryCost: null, currency: null }, providerCallCount: 1, citations: [{ title: '回答原始来源', url: 'https://example.com/answer' }] }] })
    if (path.includes('/conversations/501/snapshot')) return ok({ conversationId: '501', messages: [], highWatermark: 0, nextBeforeSequence: null })
    throw new Error(`Unexpected test request ${method} ${path}`)
  }))
  return { calls, record, report, lose: () => { loseSubmit = true }, permitSubmit: () => { loseSubmit = false }, deny: () => { denyReport = true } }
}
function ReportHarness() {
  const state = useGeoDiagnosticReport('901')
  if (state.error) return <p>{state.error}</p>
  if (!state.report) return <p>读取中</p>
  return <GeoDiagnosticReportView report={state.report} downloading={state.downloading} retrying={state.retrying} actionError={state.actionError} observationsOpen={Boolean(state.observationsOpen)} observations={state.observations} observationsLoading={state.observationsLoading} observationsError={state.observationsError} observationsPage={state.observationsPage} onDownload={state.download} onRetry={state.retry} onRefresh={state.refresh} onChat={vi.fn()} onObservations={state.toggleObservations} onObservationsPage={state.changePage} onRefreshObservations={state.refreshObservations} />
}
it('一次确认提交真实后台诊断，冻结历史资料，但普通聊天输入与发送继续可用', async () => {
  const api = server(), send = vi.fn()
  const { client } = mount(<><GeoDiagnosticCardContainer reference={{ diagnosticId: '901', conversationId: '501', step: 2 }} messageId="9900" /><GeoDiagnosticCardContainer reference={{ diagnosticId: '901', conversationId: '501', step: 6 }} messageId="9901" /><ChatComposer value="继续讨论选题" onChange={vi.fn()} onSend={send} onStop={vi.fn()} isGenerating={false} sendEnabled /></>)
  const submit = await screen.findByRole('button', { name: '提交诊断并生成 PDF' })
  await userEvent.dblClick(submit)
  expect(await screen.findByText('正在跨平台采样')).toBeInTheDocument()
  expect(api.calls.filter((call) => call.path.endsWith('/submit'))).toHaveLength(1)
  expect(api.calls.find((call) => call.path.endsWith('/submit'))?.body).toMatchObject({ version: 10, quoteId: '11111111-1111-4111-8111-111111111111', inputHash: 'f'.repeat(64) })
  expect(screen.queryByRole('button', { name: '返回修改这一步' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '发送' })).toBeEnabled()
  await userEvent.click(screen.getByRole('button', { name: '发送' })); expect(send).toHaveBeenCalledWith('继续讨论选题')
  expect(api.calls.some((call) => call.path.includes('/paid-task') || call.path.includes('/internal/'))).toBe(false)
  const old = prepared()
  await act(async () => { client.setQueryData(diagnosticKeys.detail(userFixture.id, '901'), (previous) => mergeGeoDiagnostic(previous, old, { diagnosticId: '901', conversationId: '501', projectId: '201' })) })
  expect(screen.queryByRole('button', { name: '提交诊断并生成 PDF' })).not.toBeInTheDocument()
})
it('丢失提交回执后保存稳定身份，刷新仅GET核对，显式重试复用同一nonce和报价', async () => {
  const api = server(); api.lose()
  const first = mount(<GeoDiagnosticCardContainer reference={{ diagnosticId: '901', conversationId: '501', step: 6 }} messageId="9901" />)
  await userEvent.click(await screen.findByRole('button', { name: '提交诊断并生成 PDF' }))
  expect(await screen.findByText('提交结果尚未确认')).toBeInTheDocument()
  const firstPayload = api.calls.find((call) => call.path.endsWith('/submit'))!.body
  first.unmount()
  mount(<GeoDiagnosticCardContainer reference={{ diagnosticId: '901', conversationId: '501', step: 6 }} messageId="9901" />)
  expect(await screen.findByText('提交结果尚未确认')).toBeInTheDocument()
  expect(api.calls.filter((call) => call.path.endsWith('/submit'))).toHaveLength(1)
  await userEvent.click(screen.getByRole('button', { name: '核对提交结果' }))
  expect(api.calls.filter((call) => call.path.endsWith('/submit'))).toHaveLength(1)
  api.permitSubmit()
  await userEvent.click(screen.getByRole('button', { name: '重试同一次提交' }))
  expect(await screen.findByText('正在跨平台采样')).toBeInTheDocument()
  expect(api.calls.filter((call) => call.path.endsWith('/submit')).map((call) => call.body)).toEqual([firstPayload, firstPayload])
})
it('默认56样本超过真实单次50积分时明确阻止，不自行降低样本或提高限额', async () => {
  const record = prepared(); record.platformCodes = ['DOUBAO', 'DEEPSEEK', 'QWEN', 'WENXIN', 'KIMI', 'YUANBAO', 'ZHIPU']; record.quote = { ...record.quote!, points: 56, expectedSamples: 56, blockedReason: 'ACTION_BUDGET_EXCEEDED' }; record.cards[1].snapshot = snapshot(record)
  const api = server(record)
  mount(<GeoDiagnosticCardContainer reference={{ diagnosticId: '901', conversationId: '501', step: 6 }} messageId="9901" />)
  expect(await screen.findByText('预计 56 积分')).toBeInTheDocument()
  expect(screen.getByText(/单次上限 50 积分/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '提交诊断并生成 PDF' })).toBeDisabled()
  expect(api.calls.every((call) => call.method === 'GET')).toBe(true)
})
it('在线报告展示独立发现和口碑口径、缺失不作零、完整原文分页和选题蓝图，PDF取精确版', async () => {
  const api = server(submitted()); const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  mount(<ReportHarness />)
  expect(await screen.findByRole('heading', { name: '小行星咖啡品牌诊断' })).toBeInTheDocument()
  expect(screen.getByLabelText('核心判断')).toHaveTextContent(api.report.analysis!.summary)
  expect(screen.queryByRole('region', { name: '建议实施路线图' })).not.toBeInTheDocument()
  await userEvent.click(screen.getByText('公开资料来源'))
  expect(screen.getByRole('img', { name: '小行星咖啡 无有效样本，0 次提及' })).toBeInTheDocument()
  expect(screen.getByRole('img', { name: '小行星咖啡 40.0%，2 次提及' })).toBeInTheDocument()
  expect(screen.getByRole('region', { name: '内容选题蓝图' })).toHaveTextContent('广州天河休息场景咖啡店')
  await userEvent.click(screen.getByRole('button', { name: '展开完整采样回答' }))
  expect(await screen.findByText(/完整原始回答，不限于分析摘录/)).toBeInTheDocument()
  await userEvent.click(screen.getByTitle('2'))
  await waitFor(() => expect(api.calls.some((call) => call.path.endsWith('observations?page=2&size=10'))).toBe(true))
  await userEvent.click(screen.getByRole('button', { name: '下载本版 PDF' }))
  await waitFor(() => expect(click).toHaveBeenCalledOnce())
  expect(api.calls.some((call) => call.path.endsWith('/download-url?version=1'))).toBe(true)
  expect(api.calls.every((call) => call.path.startsWith('/api/v1/'))).toBe(true)
})
it('全部失败只交付失败说明，不输出优化成功/零可见度，交付恢复不重新监测或扣费', async () => {
  const report = reportFixture(); report.hasNoData = true; report.samplingStatus = 'FAILED'; report.status = 'PDF_FAILED'; report.canRetry = true; report.analysis = null; report.metrics.discovery = []; report.metrics.reputation = []; report.metrics.validSamples = 0; report.settledPoints = 0; report.releasedPoints = 16
  const api = server(submitted(), report)
  mount(<ReportHarness />)
  expect(await screen.findByText('监测未获取有效样本')).toBeInTheDocument()
  expect(screen.queryByRole('region', { name: '后续优化建议' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '下载本版 PDF' })).toBeDisabled()
  await userEvent.dblClick(screen.getByRole('button', { name: '恢复生成报告' }))
  await waitFor(() => expect(api.calls.filter((call) => call.path.endsWith('/report/retry'))).toHaveLength(1))
  expect(api.calls.filter((call) => call.method === 'POST').map((call) => call.path)).toEqual(['/api/v1/geo/diagnostics/901/report/retry'])
})
it('咨询报告用冻结证据呈现核心判断、命中矩阵、竞品和可执行路线，旧未知与失败不冒充未命中', async () => {
  const report = reportFixture(), sample = report.samples[0]
  report.analysis!.strategy = {
    version: 'consulting-v1',
    coreJudgment: { headline: '能被发现，但场景信息还不足', description: '本批豆包回答提及门店，尚不足以说明所有平台都能自然发现品牌。', evidence: [{ unitKey: sample.unitKey, sourceId: null, quote: '小行星咖啡适合休息。' }] },
    cognitionFindings: [{ title: '休息场景已经进入回答', observation: '回答明确描述休息场景。', businessImpact: '客户可以据此判断场景适配性。', evidence: [{ unitKey: sample.unitKey, sourceId: null, quote: '小行星咖啡适合休息。' }] }],
    keywordLayers: [{ layer: 'SCENARIO', questionIds: ['101'], assessment: '休息场景获得本次提及。', nextAction: '补齐门店场景页。', evidence: [{ unitKey: sample.unitKey, sourceId: null, quote: '小行星咖啡适合休息。' }] }],
    roadmap: [{ stage: '第一阶段：补齐事实', objective: '完成顾客可核对的门店信息页。', recommendationIndexes: [0] }],
  }
  report.analysis!.recommendations[0] = { ...report.analysis!.recommendations[0], priority: 'P0', currentState: '场景介绍需要更完整。', businessImpact: '帮助顾客判断门店是否适合。', deliverable: '门店场景与服务信息页', acceptance: '逐项对应已确认品牌资料，再按原问题复测。', ownerRole: '内容负责人', timeframe: '首个实施阶段' }
  report.analysis!.contentTopics[0].intentLayer = 'SCENARIO'
  report.competitors = [{ id: 'competitor-1', name: '邻里咖啡', brandTerms: ['邻里咖啡'], reason: '同一地域的咖啡店', sourceIds: ['s1'], origin: 'USER', edited: false }]
  report.metrics.discovery[0].mentions.push({ brandId: 'competitor-1', name: '邻里咖啡', mentionedSamples: 1, mentionRate: 0.2 })
  report.metrics.discovery[1].mentions.push({ brandId: 'competitor-1', name: '邻里咖啡', mentionedSamples: 0, mentionRate: null })
  report.samples = [
    { ...sample, matchedBrandIds: ['OWN'] },
    { ...sample, unitKey: '101:KIMI:1', platformCode: 'KIMI', status: 'FAILED', matchedBrandIds: [], answerExcerpt: '', stableErrorCode: 'PROVIDER_TIMEOUT' },
    { ...sample, unitKey: '102:DOUBAO:1', questionId: '102', questionText: '天河适合办公的咖啡店有哪些？', matchedBrandIds: [], answerExcerpt: '可以考虑附近其他门店。' },
    { ...sample, unitKey: '103:DOUBAO:1', questionId: '103', questionText: '附近哪家咖啡店适合约见客户？', matchedBrandIds: null },
    { ...sample, unitKey: '104:DOUBAO:1', questionId: '104', category: 'BRAND_REPUTATION', questionText: '小行星咖啡值得考虑吗？', matchedBrandIds: ['OWN'] },
  ]
  const api = server(submitted(), report)
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  mount(<ReportHarness />)
  const judgment = await screen.findByLabelText('核心判断')
  expect(within(judgment).getByRole('heading', { name: '能被发现，但场景信息还不足' })).toBeInTheDocument()
  expect(judgment).not.toHaveTextContent(report.analysis!.summary)
  expect(document.querySelectorAll('[data-report-section]')).toHaveLength(14)
  expect(screen.queryByRole('heading', { name: 'P1：形成可持续的信息承接' })).not.toBeInTheDocument()
  expect(screen.getByText(/本版未单列 P1、P2 行动/)).toBeInTheDocument()
  const matrix = screen.getByRole('region', { name: '逐题实际回答与命中' })
  expect(within(matrix).getAllByText('已提及')).toHaveLength(2)
  expect(within(matrix).getByText('未提及')).toBeInTheDocument()
  expect(within(matrix).getByText('未记录命中')).toBeInTheDocument()
  expect(within(matrix).getByText('采样失败')).toBeInTheDocument()
  expect(within(matrix).getAllByText('未采集')).toHaveLength(3)
  const reputationRow = within(matrix).getByRole('row', { name: /小行星咖啡值得考虑吗/ })
  expect(reputationRow).toHaveTextContent('点名口碑')
  expect(within(matrix).getByText(/可以考虑附近其他门店。/)).toBeInTheDocument()
  const comparison = screen.getByRole('region', { name: '同批竞品对比表' })
  expect(comparison).toHaveTextContent('邻里咖啡')
  expect(comparison).toHaveTextContent('20.0%')
  expect(within(comparison).queryByText('0.0%')).not.toBeInTheDocument()
  expect(within(comparison).getByRole('columnheader', { name: /小行星咖啡/ })).toHaveClass('diagnostic-reference__own')
  expect(within(comparison).queryByText('小行星咖啡值得考虑吗？')).not.toBeInTheDocument()
  const baselines = screen.getByRole('region', { name: '六项诊断基线' })
  expect(within(baselines).getByRole('row', { name: /自然提及率/ })).toHaveTextContent('40.0%')
  expect(within(baselines).getByRole('row', { name: /自然命中样本/ })).toHaveTextContent('2 条')
  expect(within(baselines).getByRole('row', { name: /有效覆盖率/ })).toHaveTextContent('87.5%')
  expect(within(baselines).getByRole('row', { name: /竞品合计提及次数/ })).toHaveTextContent('1 次')
  expect(within(baselines).getByRole('row', { name: /本批可追溯来源/ })).toHaveTextContent('1 项')
  expect(baselines).toHaveTextContent('不等于正确识别或正面评价')
  expect(screen.getByRole('region', { name: '后续优化建议' })).toHaveTextContent('门店场景与服务信息页')
  expect(screen.getByRole('region', { name: '后续优化建议' })).toHaveTextContent('建议负责：内容负责人')
  expect(screen.getByRole('region', { name: '建议实施路线图' })).toHaveTextContent('第一阶段：补齐事实')
  expect(screen.getByRole('link', { name: 'P0 · 补充门店事实' })).toHaveAttribute('href', '#diagnostic-action-0')
  await userEvent.click(within(screen.getByLabelText('核心结论证据')).getByText('查看 1 条判断依据'))
  expect(within(screen.getByLabelText('核心结论证据')).getByText('小行星咖啡适合休息。')).toBeVisible()
  await userEvent.click(screen.getByRole('button', { name: '展开完整采样回答' }))
  expect(await screen.findByText(/完整原始回答，不限于分析摘录/)).toBeInTheDocument()
  await userEvent.click(screen.getByTitle('2'))
  await waitFor(() => expect(api.calls.some((call) => call.path.endsWith('observations?page=2&size=10'))).toBe(true))
  await userEvent.click(screen.getByRole('button', { name: '下载本版 PDF' }))
  await waitFor(() => expect(click).toHaveBeenCalledOnce())
  expect(api.calls.some((call) => call.path.endsWith('/download-url?version=1'))).toBe(true)
  expect(api.calls.every((call) => call.method === 'GET')).toBe(true)
})
it('报告不展示泛泛资料提醒，但保留来源、费用、实际失败与缺失样本', async () => {
  const report = reportFixture()
  report.limitations = ['未审计网页全文，品牌成立时间和团队规模请用户核实。']
  server(submitted(), report)
  mount(<ReportHarness />)
  expect(await screen.findByRole('heading', { name: '小行星咖啡品牌诊断' })).toBeInTheDocument()
  expect(screen.queryByText('调研限制与资料缺口')).not.toBeInTheDocument()
  for (const warning of [...report.limitations, ...report.analysis!.limitations]) expect(screen.queryByText(warning)).not.toBeInTheDocument()
  await userEvent.click(screen.getByText('公开资料来源'))
  expect(screen.getByRole('link', { name: '门店公开介绍' })).toHaveAttribute('href', 'http://example.com/source')
  expect(screen.getByText('门店信息摘录')).toBeInTheDocument()
  expect(screen.getByText('报价 16 积分 · 已结算 14 积分 · 已释放 2 积分')).toBeInTheDocument()
  expect(screen.queryByText('部分平台样本失败或缺失，报告会保留失败范围与限制。')).not.toBeInTheDocument()
  expect(screen.getByRole('region', { name: '实际采样覆盖' })).toHaveTextContent('失败回答2')
  expect(screen.getByRole('region', { name: '实际采样覆盖' })).toHaveTextContent('缺失 / 尚未采集0')
  expect(screen.getByRole('img', { name: '小行星咖啡 无有效样本，0 次提及' })).toBeInTheDocument()
})
it('新版报告PDF失败保留分析且只恢复交付，零有效发现样本不显示零可见度，来源按URL去重', async () => {
  const report = reportFixture()
  report.status = 'PDF_FAILED'; report.canRetry = true; report.pdf = null
  report.analysis!.strategy = { version: 'consulting-v1', coreJudgment: { headline: '仅口碑组取得有效回答', description: '发现型问题未取得有效回答，不能据此判断自然可见度。', evidence: [] }, cognitionFindings: [], keywordLayers: [], roadmap: [] }
  report.metrics.discovery.forEach((row) => { row.validSamples = 0; row.mentions.forEach((brand) => { brand.mentionedSamples = 0; brand.mentionRate = null }) })
  report.sources.push({ ...report.sources[0], id: 'same-url-source' })
  const api = server(submitted(), report)
  mount(<ReportHarness />)
  expect(await screen.findByRole('region', { name: '核心判断' })).toHaveTextContent('仅口碑组取得有效回答')
  expect(screen.getByText(/^从 P1 行动开始/)).toBeInTheDocument()
  expect(screen.queryByText(/^从 P0 行动开始/)).not.toBeInTheDocument()
  const baseline = screen.getByRole('region', { name: '六项诊断基线' })
  const natural = within(baseline).getByRole('row', { name: /自然提及率/ })
  expect(natural).toHaveTextContent('无有效样本')
  expect(natural).not.toHaveTextContent('0.0%')
  expect(within(baseline).getByRole('row', { name: /本批可追溯来源/ })).toHaveTextContent('1 项')
  expect(screen.getByRole('button', { name: '下载本版 PDF' })).toBeDisabled()
  await userEvent.dblClick(screen.getByRole('button', { name: '恢复生成报告' }))
  await waitFor(() => expect(api.calls.filter((call) => call.path.endsWith('/report/retry'))).toHaveLength(1))
  expect(api.calls.filter((call) => call.method === 'POST').map((call) => call.path)).toEqual(['/api/v1/geo/diagnostics/901/report/retry'])
  expect(screen.getByRole('region', { name: '核心判断' })).toHaveTextContent('仅口碑组取得有效回答')
})
it('报告403移除旧缓存展示，单调revision不回退已确认报告状态', async () => {
  const api = server(submitted()), { client } = mount(<ReportHarness />)
  expect(await screen.findByText('小行星咖啡品牌诊断')).toBeInTheDocument()
  expect(mergeDiagnosticReport({ ...api.report, version: 13 }, { ...api.report, version: 12, status: 'PDF_RUNNING' }, '901').status).toBe('READY')
  expect(() => mergeDiagnosticReport(undefined, { ...api.report, id: '902' }, '901')).toThrow()
  api.deny()
  await act(async () => client.invalidateQueries({ queryKey: diagnosticKeys.report(userFixture.id, '901') }))
  expect(await screen.findByText('这份诊断报告不存在或当前账号无权查看。')).toBeInTheDocument()
  expect(screen.queryByText('小行星咖啡品牌诊断')).not.toBeInTheDocument()
})
it('诊断报告列表需显式项目且只读正式delivery，项目切换迟到列表不串客户', async () => {
  const requests: string[] = []
  let resolveOld: ((response: Response) => void) | undefined
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input) => {
    const path = String(input); requests.push(path)
    if (path === '/api/v1/projects') return ok([{ id: '201', name: '咖啡客户', status: 'ACTIVE' }, { id: '202', name: '其他客户', status: 'ACTIVE' }])
    if (path.includes('projectId=201')) return new Promise<Response>((resolve) => { resolveOld = resolve })
    if (path.includes('projectId=202')) return ok({ items: [], total: 0, page: 1, size: 20, hasNext: false })
    throw new Error('unexpected list request')
  }))
  mount(<GeoDiagnosticReportLibrary />)
  expect(await screen.findByText('先选择客户项目，查看其诊断报告。')).toBeInTheDocument()
  expect(requests.some((path) => path.includes('/diagnostics?'))).toBe(false)
  await userEvent.click(screen.getByLabelText('诊断报告客户项目')); await userEvent.click(await screen.findByText('咖啡客户'))
  await waitFor(() => expect(resolveOld).toBeDefined())
  await userEvent.click(screen.getByLabelText('诊断报告客户项目')); await userEvent.click(await screen.findByText('其他客户'))
  const old = prepared(); old.delivery = delivery
  await act(async () => resolveOld?.(ok({ items: [old], total: 1, page: 1, size: 20, hasNext: false })))
  expect(screen.queryByText('小行星咖啡')).not.toBeInTheDocument()
  expect(requests.find((path) => path.includes('projectId=201'))).toContain('submittedOnly=true')
})

it('过期报价不能提交，PDF返回非HTTPS不能打开下载', async () => {
  const record = prepared(); record.quote!.expiresAt = '2020-01-01T00:00:00Z'
  const api = server(record)
  const view = mount(<GeoDiagnosticCardContainer reference={{ diagnosticId: '901', conversationId: '501', step: 6 }} messageId="9901" />)
  expect(await screen.findByRole('button', { name: '提交诊断并生成 PDF' })).toBeDisabled()
  expect(screen.queryByText('预计 16 积分')).not.toBeInTheDocument()
  expect(api.calls.some((call) => call.path.endsWith('/submit'))).toBe(false)
  view.unmount()
  server(submitted()); const original = globalThis.fetch
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, options) => String(input).includes('/download-url')
    ? ok({ downloadUrl: 'javascript:alert(1)', expiresAt: '2099-01-01T00:00:00Z', filename: '小行星咖啡诊断.pdf' }) : original(input, options)))
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  mount(<ReportHarness />)
  await userEvent.click(await screen.findByRole('button', { name: '下载本版 PDF' }))
  expect(await screen.findByText('诊断报告暂时无法读取，请稍后重试。')).toBeInTheDocument()
  expect(click).not.toHaveBeenCalled()
})

it('账号会话更换后迟到提交回执不进入新登录的缓存或交付视图', async () => {
  const api = server(), original = globalThis.fetch
  let release: ((response: Response) => void) | undefined
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, options) => {
    if (String(input).endsWith('/submit')) return new Promise<Response>((resolve) => { release = resolve })
    return original(input, options)
  }))
  const { client } = mount(<GeoDiagnosticCardContainer reference={{ diagnosticId: '901', conversationId: '501', step: 6 }} messageId="9901" />)
  await userEvent.click(await screen.findByRole('button', { name: '提交诊断并生成 PDF' }))
  await waitFor(() => expect(release).toBeDefined())
  await act(async () => {
    storeAuthToken({ accessToken: 'different-test-session', expiresAt: testExpiresAt })
    useAuthStore.setState({ status: 'authenticated', user: { ...userFixture, id: '2022' }, error: null })
  })
  await act(async () => release?.(ok({ ...api.record, version: 11, delivery })))
  expect(client.getQueryData(diagnosticKeys.detail('2022', '901'))).not.toMatchObject({ delivery })
  expect(screen.queryByText('正在跨平台采样')).not.toBeInTheDocument()
})

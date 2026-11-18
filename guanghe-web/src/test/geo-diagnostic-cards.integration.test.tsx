import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import type { GeoDiagnostic, GeoDiagnosticSnapshot } from '../api/geoDiagnosticApi'
import { getConversationSnapshot } from '../api/conversationApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { chatKeysFor } from '../features/chat/live/chatQueries'
import { toChatMessageView } from '../features/chat/live/chatAdapters'
import { geoDiagnosticMessageKey, placeGeoDiagnosticMessages } from '../features/chat/live/diagnosticMessagePlacement'
import { ChatComposer } from '../features/chat/components/ChatComposer'
import { GeoDiagnosticCardContainer } from '../pages/chat/GeoDiagnosticCardContainer'
import { GeoDiagnosticPagerContainer } from '../pages/chat/GeoDiagnosticPagerContainer'
import { useGeoDiagnosticEntry } from '../pages/chat/useGeoDiagnosticEntry'
import { diagnosticKeys, mergeGeoDiagnostic } from '../features/geo-diagnostics/queries'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const date = '2026-10-13T06:00:00Z'
const profile = { brandName: '', website: null, introduction: '', brandTerms: [] as string[], region: '' }
const readyProfile = { brandName: '小行星咖啡', website: 'https://asteroid.example/', introduction: '广州天河的咖啡店，主营现磨咖啡和糕点。', brandTerms: ['小行星咖啡', '星咖'], region: '广州' }
const discovery = Array.from({ length: 5 }, (_, index) => ({ id: `discover-${index}`, text: `广州有哪些适合场景${index + 1}的咖啡店？`, origin: 'AI' as const, edited: false }))
const reputation = ['口味好不好', '是否值得考虑', '体验如何'].map((suffix, index) => ({ id: `reputation-${index}`, text: `小行星咖啡${suffix}？`, origin: 'AI' as const, edited: false }))
const ok = (data: unknown, status = 200) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { status, headers: { 'Content-Type': 'application/json' } })
const denied = () => new Response(JSON.stringify({ code: 'FORBIDDEN', message: '无权查看', details: {}, traceId: testTraceId }), { status: 403, headers: { 'Content-Type': 'application/json' } })
function snapshot(record: GeoDiagnostic): GeoDiagnosticSnapshot {
  return structuredClone({ version: record.version, profile: record.profile, competitors: record.competitors, discoveryQuestions: record.discoveryQuestions,
    reputationQuestions: record.reputationQuestions, platformCodes: record.platformCodes, sources: record.sources, warnings: record.warnings })
}
function createRecord(step = 1): GeoDiagnostic {
  const record: GeoDiagnostic = { id: '901', projectId: '201', conversationId: '501', version: 1, step, brandName: '', profile: structuredClone(profile),
    competitors: [], discoveryQuestions: [], reputationQuestions: [], platformCodes: [], sources: [], warnings: [], generation: null, cards: [], quote: null, submissionEnabled: false, delivery: null }
  record.cards.push({ step, messageId: '9901', snapshot: snapshot(record) })
  return record
}
function recordWithHistory(step: number): GeoDiagnostic {
  const record = createRecord(step)
  record.profile = structuredClone(readyProfile)
  record.competitors = [{ id: 'comp-ai', name: '另一家咖啡', brandTerms: ['另一家'], reason: '同类门店', sourceIds: [], origin: 'AI', edited: false }]
  record.discoveryQuestions = structuredClone(discovery)
  record.reputationQuestions = structuredClone(reputation)
  record.cards = Array.from({ length: step }, (_, index) => ({ step: index + 1, messageId: String(9901 + index), snapshot: snapshot(record) }))
  return record
}
function fakeServer(initial = createRecord()) {
  let record = initial
  const calls: { path: string; method: string; body: Record<string, unknown> }[] = []
  const append = () => record.cards.push({ step: record.step, messageId: String(9901 + record.cards.length), snapshot: snapshot(record) })
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const path = String(input), method = init?.method ?? 'GET', body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : {}
    calls.push({ path, method, body })
    if (path === '/api/v1/conversations/501/snapshot?limit=50') return ok({ conversationId: '501', highWatermark: record.cards.length, nextBeforeSequence: null,
      messages: record.cards.map((card, index) => ({ id: card.messageId, conversationId: '501', role: 'ASSISTANT', contentType: 'INTERACTION', content: { geoDiagnostic: { diagnosticId: record.id, step: card.step } },
        replyToMessageId: null, runId: null, attachmentAssetIds: [], sequence: index + 1, status: 'COMPLETED', createdAt: date, updatedAt: date })) })
    if (path.startsWith('/api/v1/geo/diagnostics?')) return ok({ items: calls.some((call) => call.path === '/api/v1/geo/diagnostics' && call.method === 'POST') ? [record] : [], page: 1, size: 20, total: 0, hasNext: false })
    if (path === '/api/v1/geo/diagnostics' && method === 'POST') return ok(record, 201)
    if (path === '/api/v1/geo/diagnostics/901' && method === 'GET') return ok(record)
    if (path === '/api/v1/geo/platforms') return ok([{ code: 'DOUBAO', name: '豆包', configured: true, selectable: true, liveStatus: null }, { code: 'KIMI', name: 'Kimi', configured: true, selectable: true, liveStatus: null }, { code: 'DEEPSEEK', name: 'DeepSeek', configured: false, selectable: false, liveStatus: null }])
    if (path === '/api/v1/geo/diagnostics/901' && method === 'PATCH') {
      record = { ...record, ...body, version: record.version + 1, step: Number(body.step), generation: null, quote: null } as GeoDiagnostic
      if (body.advance) {
        record.step += 1
        if (record.step === 3) record.competitors = [{ id: 'comp-ai', name: '另一家咖啡', brandTerms: ['另一家'], reason: '同为广州咖啡门店', sourceIds: [], origin: 'AI', edited: false }]
        if (record.step === 4) record.discoveryQuestions = structuredClone(discovery)
        if (record.step === 5) record.reputationQuestions = structuredClone(reputation)
      }
      append(); return ok(record)
    }
    if (path === '/api/v1/geo/diagnostics/901/generate') {
      record.version += 1
      if (body.phase === 'BRAND_RESEARCH') { record.profile = structuredClone(readyProfile); record.step = 2 }
      if (body.phase === 'COMPETITORS') record.competitors = [...record.competitors.filter((row) => row.origin === 'USER' || row.edited), { id: 'comp-fresh', name: '新候选咖啡', brandTerms: ['新候选'], reason: '同类品牌', sourceIds: [], origin: 'AI', edited: false }]
      append(); return ok(record)
    }
    if (path === '/api/v1/geo/diagnostics/901/quote') {
      record.version += 1; record.platformCodes = body.platformCodes as string[]
      record.quote = { id: '801', inputHash: 'f'.repeat(64), version: record.version, expectedSamples: 16, points: 23, expiresAt: '2099-10-13T07:00:00Z', blockedReason: null, availablePoints: 100, maxPointsPerAction: 50, maxPointsPerDay: 200 }
      return ok(record)
    }
    throw new Error(`Unexpected request ${method} ${path}`)
  }))
  return { calls, record: () => record }
}

beforeEach(() => {
  vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})
afterEach(() => vi.unstubAllGlobals())

function Harness({ send }: { send: (text: string) => void }) {
  const [draft, setDraft] = useState('我原来正在写的内容')
  const [notice, setNotice] = useState('')
  const entry = useGeoDiagnosticEntry({ ownerId: userFixture.id, scopeKey: `${userFixture.id}:501`, conversationId: '501', projectId: '201', enabled: true,
    onEnsureConversation: async () => '501', onNotice: setNotice })
  const messages = useQuery({ queryKey: chatKeysFor(userFixture.id).messages('501'), queryFn: () => getConversationSnapshot('501', 50) })
  return <><output>{notice}</output>{placeGeoDiagnosticMessages(messages.data?.messages.map((message) => toChatMessageView(message)) ?? []).map((view) => view.geoDiagnostic
    ? <GeoDiagnosticPagerContainer key={geoDiagnosticMessageKey(view) ?? view.id} reference={view.geoDiagnostic} messageId={view.id} /> : null)}
    <ChatComposer value={draft} onChange={setDraft} onSend={send} onStop={vi.fn()} isGenerating={false} sendEnabled onCreateDiagnosticReport={() => void entry.open()} /></>
}
function mount(children: ReactNode, client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })) {
  return { ...render(<QueryClientProvider client={client}>{children}</QueryClientProvider>), client }
}

it('加号进入单卡六步准备流程，保留普通聊天输入，报价仅使用服务端结果并明确禁止未接入的最终提交', async () => {
  const server = fakeServer(), send = vi.fn()
  mount(<Harness send={send} />)
  await userEvent.click(screen.getByRole('button', { name: '添加资料' }))
  await userEvent.click(await screen.findByRole('menuitem', { name: /诊断报告/ }))
  const brand = await screen.findByLabelText('诊断品牌名称')
  fireEvent.change(brand, { target: { value: '小行星咖啡' } })
  await userEvent.click(screen.getByRole('button', { name: '开始诊断' }))
  expect(await screen.findByLabelText('诊断品牌介绍')).toHaveValue(readyProfile.introduction)
  expect(screen.getByRole('navigation', { name: '诊断步骤翻页' })).toHaveTextContent('2/6')
  expect(screen.getAllByRole('region', { name: /^诊断报告 · / })).toHaveLength(1)
  expect(screen.queryByRole('button', { name: '重新生成品牌资料' })).not.toBeInTheDocument()
  expect(screen.getByLabelText('消息输入')).toHaveValue('我原来正在写的内容')
  expect(send).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: '确认并继续' }))
  expect(await screen.findByLabelText('同类品牌 1 名称')).toHaveValue('另一家咖啡')
  await userEvent.click(screen.getByRole('button', { name: '确认并继续' }))
  expect(await screen.findByLabelText('可见度问题 1')).toHaveValue(discovery[0].text)
  await userEvent.click(screen.getByRole('button', { name: '确认并继续' }))
  expect(await screen.findByLabelText('口碑问题 1')).toHaveValue(reputation[0].text)
  await userEvent.click(screen.getByRole('button', { name: '确认问题，选择平台' }))
  const platformGroup = await screen.findByLabelText('诊断监测平台')
  expect(screen.getByRole('navigation', { name: '诊断步骤翻页' })).toHaveTextContent('6/6')
  expect(screen.getAllByRole('region', { name: /^诊断报告 · / })).toHaveLength(1)
  await userEvent.click(within(platformGroup).getByText('豆包')); await userEvent.click(within(platformGroup).getByText('Kimi'))
  expect(within(platformGroup).getByLabelText('DeepSeek')).toBeDisabled()
  await userEvent.click(screen.getByRole('button', { name: '核对本次费用' }))
  expect(await screen.findByText('预计 23 积分')).toBeInTheDocument()
  expect(screen.getByText(/16 个独立采样/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '提交诊断并生成 PDF（尚未开放）' })).toBeDisabled()
  expect(screen.getByText(/后台监测及完整 PDF 交付尚未开放/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '发送' }))
  expect(send).toHaveBeenCalledWith('我原来正在写的内容')
  expect(server.calls.filter((call) => call.method !== 'GET').every((call) => call.path.startsWith('/api/v1/geo/diagnostics'))).toBe(true)
  expect(server.calls.some((call) => call.path.includes('/tasks') || call.path.includes('/internal/') || call.path.endsWith('/submit'))).toBe(false)
  await userEvent.click(screen.getByRole('button', { name: '添加资料' }))
  await userEvent.click(await screen.findByRole('menuitem', { name: /诊断报告/ }))
  await waitFor(() => expect(screen.getByText('已恢复这个聊天的诊断卡片，可继续填写。')).toBeInTheDocument())
  expect(server.calls.filter((call) => call.method === 'POST' && call.path === '/api/v1/geo/diagnostics')).toHaveLength(1)
})

it('重新生成先保存手动竞品和修改，服务端保留历史快照但不堆叠旧卡片，最多五个候选', async () => {
  const record = createRecord(3); record.profile = structuredClone(readyProfile)
  record.competitors = [{ id: 'comp-ai', name: '另一家咖啡', brandTerms: ['另一家'], reason: '', sourceIds: [], origin: 'AI', edited: false }]
  record.cards[0].snapshot = snapshot(record)
  const server = fakeServer(record)
  mount(<Harness send={vi.fn()} />)
  expect(await screen.findByLabelText('同类品牌 1 名称')).toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('同类品牌 1 名称'), { target: { value: '用户修正咖啡' } })
  await userEvent.click(screen.getByRole('button', { name: '添加同类品牌' }))
  fireEvent.change(screen.getByLabelText('同类品牌 2 名称'), { target: { value: '用户手选咖啡' } })
  await userEvent.click(screen.getByRole('button', { name: '重新生成同类品牌' }))
  await waitFor(() => expect(server.calls.some((call) => call.path.endsWith('/generate'))).toBe(true))
  const patch = server.calls.find((call) => call.method === 'PATCH')
  expect(patch?.body.competitors).toMatchObject([{ name: '用户修正咖啡', edited: true }, { name: '用户手选咖啡', origin: 'USER', edited: true }])
  expect(await screen.findByLabelText('同类品牌 3 名称')).toHaveValue('新候选咖啡')
  expect(screen.getByLabelText('同类品牌 1 名称')).toHaveValue('用户修正咖啡')
  expect(server.record().cards[0].snapshot.competitors[0].name).toBe('另一家咖啡')
  expect(screen.queryByText('另一家咖啡', { selector: 'strong' })).not.toBeInTheDocument()
  expect(screen.getAllByRole('region', { name: /^诊断报告 · / })).toHaveLength(1)
  for (let index = 0; index < 2; index += 1) await userEvent.click(screen.getByRole('button', { name: '添加同类品牌' }))
  expect(screen.getByRole('button', { name: '添加同类品牌' })).toBeDisabled()
})

it('刷新恢复当前步骤与失败信息；准备失败不会阻止普通聊天或伪造任务', async () => {
  const record = createRecord(4); record.profile = structuredClone(readyProfile); record.discoveryQuestions = structuredClone(discovery)
  record.generation = { phase: 'DISCOVERY_QUESTIONS', status: 'FAILED', errorCode: 'UPSTREAM_TIMEOUT' }
  record.cards[0].snapshot = snapshot(record)
  const server = fakeServer(record), send = vi.fn()
  mount(<Harness send={send} />)
  expect(await screen.findByLabelText('可见度问题 1')).toHaveValue(discovery[0].text)
  expect(screen.getByText('这一步暂时没有生成完成，已保留填写的资料。')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '发送' })).toBeEnabled()
  await userEvent.click(screen.getByRole('button', { name: '发送' }))
  expect(send).toHaveBeenCalledOnce()
  expect(server.calls.every((call) => call.method === 'GET')).toBe(true)
})

it('六步历史仅占一张卡，左右翻页停在边界且不写资料、不创建聊天或监测任务', async () => {
  const server = fakeServer(recordWithHistory(6))
  mount(<Harness send={vi.fn()} />)
  const navigation = await screen.findByRole('navigation', { name: '诊断步骤翻页' })
  const deck = navigation.parentElement!
  expect(deck).toHaveClass('geo-diagnostic-deck')
  expect(navigation).toHaveClass('geo-diagnostic-deck__navigation')
  expect(deck.querySelectorAll('.geo-diagnostic-deck__page:not([hidden])')).toHaveLength(1)
  expect(deck.querySelector('.geo-diagnostic-deck__page')?.contains(navigation)).toBe(false)
  const previous = within(navigation).getByRole('button', { name: '上一步' })
  const next = within(navigation).getByRole('button', { name: '下一步' })
  expect(navigation).toHaveTextContent('6/6')
  expect(next).toBeDisabled()
  for (let step = 5; step >= 1; step -= 1) {
    await userEvent.click(previous)
    expect(navigation).toHaveTextContent(`${step}/6`)
    expect(screen.getAllByRole('region', { name: /^诊断报告 · / })).toHaveLength(1)
    expect(deck.querySelectorAll('.geo-diagnostic-deck__page:not([hidden])')).toHaveLength(1)
  }
  expect(previous).toBeDisabled()
  expect(screen.getByRole('region', { name: '诊断报告 · 开始品牌诊断' })).toBeVisible()
  for (let step = 2; step <= 6; step += 1) {
    await userEvent.click(next)
    expect(navigation).toHaveTextContent(`${step}/6`)
    expect(screen.getAllByRole('region', { name: /^诊断报告 · / })).toHaveLength(1)
  }
  expect(next).toBeDisabled()
  expect(server.calls.every((call) => call.method === 'GET')).toBe(true)
  expect(screen.getByRole('button', { name: '发送' })).toBeEnabled()
})

it('当前未保存的输入在左右查看历史后保持原值，翻页不推进准备步骤也不锁普通聊天', async () => {
  const server = fakeServer(recordWithHistory(2)), send = vi.fn()
  mount(<Harness send={send} />)
  const intro = await screen.findByLabelText('诊断品牌介绍')
  const unsaved = '尚未保存的经营介绍：提供现磨咖啡与社区下午茶。'
  fireEvent.change(intro, { target: { value: unsaved } })
  const navigation = screen.getByRole('navigation', { name: '诊断步骤翻页' })
  expect(within(navigation).getByRole('button', { name: '下一步' })).toBeDisabled()
  await userEvent.click(within(navigation).getByRole('button', { name: '上一步' }))
  expect(navigation).toHaveTextContent('1/6')
  expect(intro).not.toBeVisible()
  expect(screen.getAllByRole('region', { name: /^诊断报告 · / })).toHaveLength(1)
  await userEvent.click(within(navigation).getByRole('button', { name: '下一步' }))
  expect(screen.getByLabelText('诊断品牌介绍')).toBe(intro)
  expect(intro).toBeVisible()
  expect(intro).toHaveValue(unsaved)
  expect(navigation).toHaveTextContent('2/6')
  expect(within(navigation).getByRole('button', { name: '下一步' })).toBeDisabled()
  expect(server.calls.every((call) => call.method === 'GET')).toBe(true)
  expect(server.record().profile.introduction).toBe(readyProfile.introduction)
  await userEvent.click(screen.getByRole('button', { name: '发送' }))
  expect(send).toHaveBeenCalledWith('我原来正在写的内容')
})

it('显式返回修改前置资料后回到该步，旧的后续卡片不能继续前翻且刷新仍恢复当前步骤', async () => {
  const server = fakeServer(recordWithHistory(6))
  const view = mount(<Harness send={vi.fn()} />)
  const navigation = await screen.findByRole('navigation', { name: '诊断步骤翻页' })
  for (let index = 0; index < 4; index += 1) await userEvent.click(within(navigation).getByRole('button', { name: '上一步' }))
  expect(navigation).toHaveTextContent('2/6')
  expect(server.calls.every((call) => call.method === 'GET')).toBe(true)
  await userEvent.click(screen.getByRole('button', { name: '返回修改这一步' }))
  expect(await screen.findByLabelText('诊断品牌介绍')).toHaveValue(readyProfile.introduction)
  expect(screen.getByRole('navigation', { name: '诊断步骤翻页' })).toHaveTextContent('2/6')
  expect(screen.getByRole('button', { name: '下一步' })).toBeDisabled()
  expect(server.calls.filter((call) => call.method !== 'GET')).toEqual([
    expect.objectContaining({ method: 'PATCH', path: '/api/v1/geo/diagnostics/901', body: { version: 1, step: 2, advance: false } }),
  ])
  view.unmount()
  mount(<Harness send={vi.fn()} />)
  expect(await screen.findByLabelText('诊断品牌介绍')).toHaveValue(readyProfile.introduction)
  expect(screen.getByRole('navigation', { name: '诊断步骤翻页' })).toHaveTextContent('2/6')
  expect(screen.getByRole('button', { name: '下一步' })).toBeDisabled()
  expect(screen.getAllByRole('region', { name: /^诊断报告 · / })).toHaveLength(1)
})

it('翻回历史时仍接收后台准备完成结果，保持浏览位置且切回后可见新版本', async () => {
  const record = recordWithHistory(3)
  record.generation = { phase: 'COMPETITORS', status: 'RUNNING', errorCode: null }
  const server = fakeServer(record)
  const { client } = mount(<Harness send={vi.fn()} />)
  expect(await screen.findByText(/正在寻找合适的同类品牌/)).toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '上一步' }))
  expect(screen.getByRole('navigation', { name: '诊断步骤翻页' })).toHaveTextContent('2/6')
  record.version += 1
  record.generation = null
  record.competitors = [{ id: 'fresh', name: '后台新查到的咖啡店', brandTerms: [], reason: '同类门店', sourceIds: [], origin: 'AI', edited: false }]
  record.cards.push({ step: 3, messageId: '9904', snapshot: snapshot(record) })
  await waitFor(() => expect(client.getQueryData(diagnosticKeys.detail(userFixture.id, '901'))).toMatchObject({ version: 2, generation: null }), { timeout: 4500 })
  expect(screen.getByRole('navigation', { name: '诊断步骤翻页' })).toHaveTextContent('2/6')
  expect(screen.getByRole('region', { name: '诊断报告 · 确认品牌资料' })).toBeVisible()
  await userEvent.click(screen.getByRole('button', { name: '下一步' }))
  expect(await screen.findByLabelText('同类品牌 1 名称')).toHaveValue('后台新查到的咖啡店')
  expect(screen.getAllByRole('region', { name: /^诊断报告 · / })).toHaveLength(1)
  expect(server.calls.every((call) => call.method === 'GET')).toBe(true)
})

it('已提交诊断左右浏览均为冻结快照，后台采样不锁普通聊天也不重复提交', async () => {
  const record = recordWithHistory(6), send = vi.fn()
  record.submissionEnabled = true
  record.delivery = { taskId: '701', runId: '702', status: 'SAMPLING', samplingStatus: 'RUNNING', hasNoData: false,
    errorCode: null, canRetry: false, progressPercent: 20, reportVersion: 1 }
  const server = fakeServer(record)
  mount(<Harness send={send} />)
  expect(await screen.findByText('正在跨平台采样')).toBeVisible()
  const navigation = screen.getByRole('navigation', { name: '诊断步骤翻页' })
  for (let step = 6; step >= 1; step -= 1) {
    expect(navigation).toHaveTextContent(`${step}/6`)
    expect(screen.getAllByRole('region', { name: /^诊断报告 · / })).toHaveLength(1)
    expect(screen.queryByRole('button', { name: '返回修改这一步' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '提交诊断并生成 PDF' })).not.toBeInTheDocument()
    if (step > 1) await userEvent.click(within(navigation).getByRole('button', { name: '上一步' }))
  }
  expect(within(navigation).getByRole('button', { name: '上一步' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '发送' })).toBeEnabled()
  await userEvent.click(screen.getByRole('button', { name: '发送' }))
  expect(send).toHaveBeenCalledWith('我原来正在写的内容')
  expect(server.calls.every((call) => call.method === 'GET')).toBe(true)
})

it('读取被拒绝后移除缓存中的客户资料，不把其他聊天的诊断显示为当前卡片', async () => {
  const record = createRecord(2); record.profile = structuredClone(readyProfile); record.cards[0].snapshot = snapshot(record)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  client.setQueryData(diagnosticKeys.detail(userFixture.id, record.id), record)
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => denied()))
  mount(<GeoDiagnosticCardContainer reference={{ diagnosticId: '901', conversationId: '501', step: 2 }} messageId="9901" />, client)
  expect(await screen.findByText('这个诊断记录不存在或当前账号无权查看。')).toBeInTheDocument()
  expect(screen.queryByLabelText('诊断品牌介绍')).not.toBeInTheDocument()
  expect(() => client.setQueryData(diagnosticKeys.detail(userFixture.id, record.id), { ...record, conversationId: '502' })).toThrow()
  await act(async () => client.invalidateQueries({ queryKey: diagnosticKeys.detail(userFixture.id, record.id) }))
  expect(screen.queryByText(readyProfile.introduction)).not.toBeInTheDocument()
})

it('可见度问题包含自身品牌词时不能继续，口碑问题必须包含自身品牌词', async () => {
  const record = createRecord(4); record.profile = structuredClone(readyProfile); record.discoveryQuestions = structuredClone(discovery); record.cards[0].snapshot = snapshot(record)
  record.profile.brandTerms.push('mi', 'New Balance')
  record.competitors = [{ id: 'same-kind', name: '银河咖啡', brandTerms: ['银河小馆'], reason: '', sourceIds: [], origin: 'AI', edited: false }]
  record.cards[0].snapshot = snapshot(record)
  fakeServer(record); const view = mount(<Harness send={vi.fn()} />)
  const question = await screen.findByLabelText('可见度问题 1')
  fireEvent.change(question, { target: { value: '小行星咖啡好不好？' } })
  expect(screen.getByRole('button', { name: '确认并继续' })).toBeDisabled()
  expect(screen.getByText(/不直接写出目标品牌或已选同类品牌/)).toBeInTheDocument()
  fireEvent.change(question, { target: { value: '银河小馆好不好？' } })
  expect(screen.getByRole('button', { name: '确认并继续' })).toBeDisabled()
  fireEvent.change(question, { target: { value: '广州有哪些 premium 咖啡店？' } })
  expect(screen.getByRole('button', { name: '确认并继续' })).toBeEnabled()
  fireEvent.change(question, { target: { value: '广州有哪些 ＭＩ 咖啡门店？' } })
  expect(screen.getByRole('button', { name: '确认并继续' })).toBeDisabled()
  fireEvent.change(question, { target: { value: '广州有哪些 newbalance 咖啡门店？' } })
  expect(screen.getByRole('button', { name: '确认并继续' })).toBeEnabled()
  fireEvent.change(question, { target: { value: '广州有哪些 New Balance 咖啡门店？' } })
  expect(screen.getByRole('button', { name: '确认并继续' })).toBeDisabled()
  view.unmount()
  const next = createRecord(5); next.profile = structuredClone(readyProfile); next.discoveryQuestions = structuredClone(discovery); next.reputationQuestions = structuredClone(reputation); next.cards[0].snapshot = snapshot(next)
  fakeServer(next); mount(<Harness send={vi.fn()} />)
  const brandQuestion = await screen.findByLabelText('口碑问题 1')
  fireEvent.change(brandQuestion, { target: { value: '广州有哪些咖啡店？' } })
  expect(screen.getByRole('button', { name: '确认问题，选择平台' })).toBeDisabled()
  expect(screen.getByText('口碑问题需要直接写出你的品牌或品牌词。')).toBeInTheDocument()
})

it('没有客户项目时不创建会话或诊断，客户项目切换后迟到的创建回执不进入新项目', async () => {
  const ensure = vi.fn(async () => '501')
  let finish: ((value: Response) => void) | undefined
  const calls: { method: string; path: string }[] = []
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const path = String(input), method = init?.method ?? 'GET'; calls.push({ method, path })
    if (method === 'GET') return ok({ items: [], page: 1, size: 20, total: 0, hasNext: false })
    return new Promise<Response>((resolve) => { finish = resolve })
  }))
  function ScopeHarness() {
    const [projectId, setProjectId] = useState<string>()
    const [notice, setNotice] = useState('')
    const entry = useGeoDiagnosticEntry({ ownerId: userFixture.id, scopeKey: `new:${projectId ?? ''}`, projectId, enabled: true,
      onEnsureConversation: ensure, onNotice: setNotice })
    return <><button onClick={() => void entry.open()}>诊断入口</button><button onClick={() => { setProjectId(projectId === '201' ? '202' : '201'); setNotice('') }}>切换项目</button><output>{notice}</output></>
  }
  const { client } = mount(<ScopeHarness />)
  await userEvent.click(screen.getByText('诊断入口'))
  expect(screen.getByText('请先选择客户项目，再准备这个品牌的诊断报告。')).toBeInTheDocument()
  expect(ensure).not.toHaveBeenCalled(); expect(calls).toEqual([])
  await userEvent.click(screen.getByText('切换项目')); await userEvent.click(screen.getByText('诊断入口'))
  await waitFor(() => expect(calls.some((call) => call.method === 'POST')).toBe(true))
  await userEvent.click(screen.getByText('切换项目'))
  await act(async () => finish?.(ok(createRecord(), 201)))
  expect(client.getQueryData(diagnosticKeys.detail(userFixture.id, '901'))).toBeUndefined()
  expect(screen.queryByText('诊断卡片已准备，请填写品牌名称。')).not.toBeInTheDocument()
})

it('只有显式新建另一份按钮创建新诊断；重新报价失败不继续展示上次价格', async () => {
  const record = createRecord(6); record.profile = structuredClone(readyProfile); record.discoveryQuestions = structuredClone(discovery); record.reputationQuestions = structuredClone(reputation)
  record.platformCodes = ['DOUBAO']; record.quote = { id: 'cd50e84f-b856-4d84-a34e-f60970f9c80b', inputHash: 'f'.repeat(64), version: 1, expectedSamples: 8, points: 19, expiresAt: '2099-10-13T07:00:00Z', blockedReason: null, availablePoints: 100, maxPointsPerAction: 50, maxPointsPerDay: 200 }; record.cards[0].snapshot = snapshot(record)
  const created = { ...createRecord(), id: '902', cards: [{ ...createRecord().cards[0], messageId: '9910' }] }
  const writes: { path: string; body: Record<string, unknown> }[] = []
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const path = String(input)
    if (init?.method === 'POST') writes.push({ path, body: JSON.parse(String(init.body)) })
    if (path === '/api/v1/geo/diagnostics/901') return ok(record)
    if (path === '/api/v1/geo/platforms') return ok([{ code: 'DOUBAO', name: '豆包', configured: true, selectable: true, liveStatus: null }])
    if (path.endsWith('/quote')) return new Response(JSON.stringify({ code: 'UPSTREAM_UNAVAILABLE', message: '报价暂不可用', details: {}, traceId: testTraceId }), { status: 503, headers: { 'Content-Type': 'application/json' } })
    if (path === '/api/v1/geo/diagnostics') return ok(created, 201)
    throw new Error(`Unexpected ${path}`)
  }))
  const { client } = mount(<GeoDiagnosticCardContainer reference={{ diagnosticId: '901', conversationId: '501', step: 6 }} messageId="9901" />,
    new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 60_000 } } }))
  expect(await screen.findByText('预计 19 积分')).toBeInTheDocument()
  await waitFor(() => expect(screen.getByRole('button', { name: '核对本次费用' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '核对本次费用' }))
  expect(await screen.findByText('这一步暂时无法完成，已保存的资料会保留，请刷新后重试。')).toBeInTheDocument()
  expect(screen.queryByText('预计 19 积分')).not.toBeInTheDocument()
  expect(writes).toHaveLength(1)
  await waitFor(() => expect(screen.getByRole('button', { name: '新建另一份诊断' })).toBeEnabled())
  await userEvent.click(screen.getByRole('button', { name: '新建另一份诊断' }))
  await waitFor(() => expect(client.getQueryData(diagnosticKeys.detail(userFixture.id, '902'))).toMatchObject({ id: '902', conversationId: '501' }))
  expect(writes[1]).toMatchObject({ path: '/api/v1/geo/diagnostics', body: { conversationId: '501' } })
  expect(writes[1].body.requestId).toMatch(/^[0-9a-f-]{36}$/)
})

it('生成期间可以回改前置资料，不展示冗余资料提醒，正常编辑不被只读刷新覆盖', async () => {
  const record = createRecord(2); record.profile = structuredClone(readyProfile); record.warnings = ['搜索结果存在同名咖啡品牌，请确认官网属于本次品牌。', '本次为 BRAND_RESEARCH 阶段，按规则不新增竞品与问题。']; record.cards[0].snapshot = snapshot(record)
  record.step = 3; record.version = 2; record.generation = { phase: 'COMPETITORS', status: 'RUNNING', errorCode: null }
  record.cards.push({ step: 3, messageId: '9902', snapshot: snapshot(record) })
  const server = fakeServer(record)
  const { client } = mount(<Harness send={vi.fn()} />)
  const previousStep = await screen.findByRole('button', { name: '上一步' })
  for (const warning of record.warnings) expect(screen.queryByText(warning)).not.toBeInTheDocument()
  await userEvent.click(previousStep)
  const historical = await screen.findByRole('region', { name: '诊断报告 · 确认品牌资料' })
  expect(within(historical).queryByText(record.warnings[0])).not.toBeInTheDocument()
  expect(screen.queryByText('请核对这些资料提示')).not.toBeInTheDocument()
  await userEvent.click(within(historical).getByRole('button', { name: '返回修改这一步' }))
  const intro = await screen.findByLabelText('诊断品牌介绍')
  expect(server.calls.find((call) => call.method === 'PATCH')?.body).toMatchObject({ step: 2, version: 2, advance: false })
  fireEvent.change(intro, { target: { value: '用户正在填写的真实资料，不应被后台刷新覆盖。' } })
  await act(async () => client.invalidateQueries({ queryKey: diagnosticKeys.detail(userFixture.id, '901') }))
  expect(screen.getByLabelText('诊断品牌介绍')).toHaveValue('用户正在填写的真实资料，不应被后台刷新覆盖。')
  for (const warning of record.warnings) expect(screen.queryByText(warning)).not.toBeInTheDocument()
})

it('后台准备完成的新卡自动进入聊天，不需要新的聊天Run或用户手动刷新', async () => {
  const record = createRecord(2); record.profile.brandName = '小行星咖啡'; record.generation = { phase: 'BRAND_RESEARCH', status: 'RUNNING', errorCode: null }
  record.cards[0].snapshot = snapshot(record)
  const server = fakeServer(record)
  mount(<Harness send={vi.fn()} />)
  expect(await screen.findByText(/正在查找品牌的公开资料/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '发送' })).toBeEnabled()
  record.version += 1; record.profile = structuredClone(readyProfile); record.generation = null
  record.cards.push({ step: 2, messageId: '9902', snapshot: snapshot(record) })
  await waitFor(() => expect(screen.getByLabelText('诊断品牌介绍')).toHaveValue(readyProfile.introduction), { timeout: 4500 })
  expect(screen.queryByText(/正在查找品牌的公开资料/)).not.toBeInTheDocument()
  expect(server.calls.filter((call) => call.path === '/api/v1/geo/diagnostics/901').length).toBeGreaterThan(1)
  expect(server.calls.every((call) => call.method === 'GET')).toBe(true)
})

it('重新生成回执后迟到的旧 GET 不回退版本、人工项或活动轮询，完成卡仍自动恢复', async () => {
  const record = createRecord(3); record.profile = structuredClone(readyProfile)
  record.competitors = [{ id: 'comp-ai', name: '另一家咖啡', brandTerms: ['另一家'], reason: '', sourceIds: [], origin: 'AI', edited: false }]
  record.cards[0].snapshot = snapshot(record)
  const server = fakeServer(record), originalFetch = globalThis.fetch
  let stale: GeoDiagnostic | undefined, releaseStale: ((response: Response) => void) | undefined
  let signalStarted: (() => void) | undefined
  const staleStarted = new Promise<void>((resolve) => { signalStarted = resolve })
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const path = String(input), method = init?.method ?? 'GET'
    if (path === '/api/v1/geo/diagnostics/901' && method === 'GET' && !stale
      && server.calls.some((call) => call.method === 'PATCH')) {
      stale = structuredClone(server.record())
      signalStarted?.()
      return new Promise<Response>((resolve) => { releaseStale = resolve })
    }
    if (path.endsWith('/generate')) {
      await staleStarted
      await originalFetch(input, init)
      server.record().generation = { phase: 'COMPETITORS', status: 'RUNNING', errorCode: null }
      return ok(server.record(), 202)
    }
    return originalFetch(input, init)
  }))
  const { client } = mount(<Harness send={vi.fn()} />,
    new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 60_000 } } }))
  const name = await screen.findByLabelText('同类品牌 1 名称')
  fireEvent.change(name, { target: { value: '用户修正咖啡' } })
  await userEvent.click(screen.getByRole('button', { name: '重新生成同类品牌' }))
  await waitFor(() => expect(server.calls.some((call) => call.method === 'PATCH')).toBe(true))
  // A stable pager no longer remounts every server message. Explicitly emulate a
  // concurrent refresh so the late response exercises the same version fence.
  await act(async () => {
    void client.refetchQueries({ queryKey: diagnosticKeys.detail(userFixture.id, '901') })
    await staleStarted
  })
  await waitFor(() => expect(client.getQueryData(diagnosticKeys.detail(userFixture.id, '901'))).toMatchObject({ version: 3, generation: { status: 'RUNNING' } }))
  expect(stale?.version).toBe(2)
  await act(async () => releaseStale?.(ok(stale)))
  await waitFor(() => expect(client.getQueryState(diagnosticKeys.detail(userFixture.id, '901'))?.fetchStatus).toBe('idle'))
  expect(client.getQueryData(diagnosticKeys.detail(userFixture.id, '901'))).toMatchObject({ version: 3, generation: { status: 'RUNNING' } })
  expect(await screen.findByText(/正在寻找合适的同类品牌/)).toBeInTheDocument()
  server.record().version += 1; server.record().generation = null
  server.record().cards.push({ step: 3, messageId: '9904', snapshot: snapshot(server.record()) })
  await waitFor(() => expect(client.getQueryData(diagnosticKeys.detail(userFixture.id, '901'))).toMatchObject({ version: 4, generation: null }), { timeout: 4500 })
  expect(await screen.findByLabelText('同类品牌 1 名称')).toHaveValue('用户修正咖啡')
  expect(screen.getByLabelText('同类品牌 2 名称')).toHaveValue('新候选咖啡')
})

it('加号恢复旧列表不能覆盖已确认的更高版本，异常 scope 回执不进入缓存', async () => {
  const older = createRecord(3); older.profile = structuredClone(readyProfile); older.cards[0].snapshot = snapshot(older)
  const newer = structuredClone(older); newer.version = 4; newer.generation = { phase: 'COMPETITORS', status: 'RUNNING', errorCode: null }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 60_000 } } })
  client.setQueryData(diagnosticKeys.detail(userFixture.id, '901'), newer)
  let wrongScope = false
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => ok({ items: [{ ...older, conversationId: wrongScope ? '502' : '501' }], page: 1, size: 20, total: 1, hasNext: false })))
  function EntryHarness() {
    const [notice, setNotice] = useState('')
    const entry = useGeoDiagnosticEntry({ ownerId: userFixture.id, scopeKey: `${userFixture.id}:501`, conversationId: '501', projectId: '201', enabled: true,
      onEnsureConversation: async () => '501', onNotice: setNotice })
    return <><button onClick={() => void entry.open()}>恢复诊断</button><output>{notice}</output></>
  }
  mount(<EntryHarness />, client)
  await userEvent.click(screen.getByText('恢复诊断'))
  expect(await screen.findByText('已恢复这个聊天的诊断卡片，可继续填写。')).toBeInTheDocument()
  expect(client.getQueryData(diagnosticKeys.detail(userFixture.id, '901'))).toMatchObject({ version: 4, generation: { status: 'RUNNING' } })
  wrongScope = true
  await userEvent.click(screen.getByText('恢复诊断'))
  expect(await screen.findByText('这个项目或聊天暂时不可用于诊断，请重新打开。')).toBeInTheDocument()
  expect(client.getQueryData(diagnosticKeys.detail(userFixture.id, '901'))).toMatchObject({ version: 4, conversationId: '501' })
})

it('版本围栏只保留相同诊断与聊天项目的缓存，拒绝不安全标识或项目错配', () => {
  const scope = { diagnosticId: '901', conversationId: '501', projectId: '201' }
  const incoming = createRecord(3), high = { ...incoming, version: 4 }
  expect(mergeGeoDiagnostic(high, incoming, scope)).toBe(high)
  expect(mergeGeoDiagnostic({ ...high, id: '902' }, incoming, scope)).toBe(incoming)
  expect(mergeGeoDiagnostic({ ...high, conversationId: '502' }, incoming, scope)).toBe(incoming)
  for (const mismatch of [{ id: '../901' }, { id: '902' }, { conversationId: '502' }, { projectId: '202' }, { version: 0 }]) {
    expect(() => mergeGeoDiagnostic(high, { ...incoming, ...mismatch }, scope)).toThrow()
  }
})

it('缓存中的其他诊断即使聊天相同也不显示，错误 GET 不用旧资料降级', async () => {
  const cached = createRecord(2); cached.id = '902'; cached.profile = structuredClone(readyProfile); cached.cards[0].snapshot = snapshot(cached)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 60_000 } } })
  client.setQueryData(diagnosticKeys.detail(userFixture.id, '901'), cached)
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => ok(cached)))
  mount(<GeoDiagnosticCardContainer reference={{ diagnosticId: '901', conversationId: '501', step: 2 }} messageId="9901" />, client)
  expect(screen.queryByLabelText('诊断品牌介绍')).not.toBeInTheDocument()
  expect(await screen.findByText('这个诊断记录不存在或当前账号无权查看。')).toBeInTheDocument()
  expect(screen.queryByText(readyProfile.introduction)).not.toBeInTheDocument()
})

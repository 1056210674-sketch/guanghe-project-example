import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { readCurrentAttachmentIds, storeCurrentAttachmentIds } from '../features/chat/live/attachmentRecovery'
import { renderApp } from './renderApp'
import { geoReportDto } from './fixtures/geo'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

vi.mock('../app/theme', async () => {
  const original = await vi.importActual<typeof import('../app/theme')>('../app/theme')
  return { ...original, appTheme: { ...original.appTheme, token: { ...original.appTheme.token, motion: false } } }
})

const now = '2026-10-09T03:00:00Z'
const projects = ['小行星咖啡店', '艾尼亚科'].map((name, index) => ({ id: String(4001 + index), name, status: 'ACTIVE', version: 1, createdAt: now, updatedAt: now }))
const report = { ...geoReportDto('geo-synthesis-v1'), topic: { ...geoReportDto('geo-synthesis-v1').topic, spaceId: '4001', productId: '31' } }
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data, message: 'success', traceId: testTraceId }), { headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[]) => ok({ items, total: items.length, size: 100, page: 1, hasNext: false })
const runId = '00000000-0000-4000-8000-000000000001'

function stubApi() {
  const writes: Array<{ path: string; body: Record<string, unknown> }> = []
  const reads: string[] = []
  let conversation = { id: '1', title: '品牌优化方案', selectedSpaceId: '4001', selectedProductId: '31', version: 1,
    lastMessageAt: now, createdAt: now, updatedAt: now, agentIdentity: { installationId: '101', agentKey: 'geo', agentVersion: '1.0.0', name: 'GEO 智能体', projectId: '4001', capabilities: ['chat'] } }
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const path = String(input)
    if (init?.method && init.method !== 'GET') {
      const body = JSON.parse(String(init.body)) as Record<string, unknown>
      writes.push({ path, body })
      if (path === '/api/v1/conversations') {
        conversation = { ...conversation, selectedSpaceId: String(body.projectId), agentIdentity: { ...conversation.agentIdentity, projectId: String(body.projectId) } }
        return ok(conversation)
      }
      if (path === '/api/v1/conversations/1/messages') return ok({ messageId: '20', runId, status: 'COMPLETED' })
      throw new Error(`Unexpected mutation ${path}`)
    }
    reads.push(path)
    if (path === '/api/v1/agents') return ok([{ agentKey: 'geo', version: '1.0.0', name: 'GEO 智能体', capabilities: ['chat'], description: 'GEO', status: 'PUBLISHED' }])
    if (path === '/api/v1/agent-installations') return ok([{ id: '101', agentKey: 'geo', agentVersion: '1.0.0', name: 'GEO 智能体', enabled: true, version: 1, createdAt: now }])
    if (path === '/api/v1/projects') return ok(projects)
    if (path.match(/\/projects\/\d+\/geo-target$/)) return ok({ projectId: path.split('/')[4], configured: false, name: null, aliases: [], version: 0 })
    if (path.startsWith('/api/v1/memory/entries?')) return ok([])
    if (path.includes('/knowledge-documents?')) return page([])
    if (path.startsWith('/api/v1/conversations?')) return page([conversation])
    if (path === '/api/v1/conversations/1') return ok(conversation)
    if (path.includes('/snapshot?')) return ok({ messages: writes.filter(({ path: p }) => p.endsWith('/messages')).map(({ body }, index) => ({
      id: '20', conversationId: '1', role: 'ASSISTANT', contentType: 'TEXT', content: { text: `已收到：${body.content}` },
      replyToMessageId: null, runId, sequence: index + 1, status: 'COMPLETED', createdAt: now, updatedAt: now,
    })), nextBeforeSequence: null, highWatermark: 0, activeRun: null })
    if (path.endsWith('/events')) return new Response('')
    if (path.startsWith('/api/v1/tasks?')) return page([])
    if (path === '/api/v1/notifications/unread-count') return ok({ unreadCount: 0 })
    if (path === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    if (path === '/api/v1/geo/reports/9001') return ok(report)
    if (path === '/api/v1/geo/topics/5001') return ok(report.topic)
    if (path === '/api/v1/assets/81') return ok({ id: '81', assetType: 'IMAGE', sourceType: 'UPLOADED', status: 'READY', safetyStatus: 'APPROVED', name: '小行星门店图片',
      spaceId: '4001', primaryProductId: null, productIds: [], contentType: 'image/png', sizeBytes: 1000, sha256: 'a'.repeat(64), width: 1024, height: 1024,
      sourceTaskId: null, createdAt: now, updatedAt: now })
    if (path === '/api/v1/assets/81/download-url') return ok({ url: 'https://example.com/image.png', expiresAt: '2099-01-01T00:00:00Z' })
    throw new Error(`Unexpected read ${path}`)
  }))
  return { reads, writes, messages: () => writes.filter(({ path }) => path.endsWith('/messages')) }
}

function RouteProbe() { const location = useLocation(); return <output data-testid="route">{location.pathname}{location.search}</output> }

beforeEach(async () => {
  await import('../pages/chat/ChatPage')
  await import('../pages/chat/GeoOnboarding')
  vi.unstubAllGlobals(); sessionStorage.clear(); localStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})
afterEach(() => vi.unstubAllGlobals())

it('GEO 空聊天主动引导；填写只预填并保留原输入，点击发送才创建会话', async () => {
  const api = stubApi()
  renderApp(['/app?installation=101&project=4001'])
  // Agent/project queries trigger a second lazy boundary; settle it after launch is ready.
  await screen.findByText('从了解你的品牌开始', { selector: 'h1' })
  await act(() => vi.dynamicImportSettled())
  await screen.findByRole('heading', { name: '你好，我是 GEO 智能体' })
  expect(api.writes).toEqual([])
  const input = screen.getByRole('textbox', { name: '消息输入' })
  fireEvent.change(input, { target: { value: '先帮我了解怎么做' } })
  await userEvent.click(screen.getByRole('button', { name: '填写公司信息' }))
  const dialog = screen.getByRole('dialog')
  fireEvent.change(within(dialog).getByLabelText('对外品牌名称'), { target: { value: '小行星咖啡' } })
  fireEvent.change(within(dialog).getByLabelText('主营产品／服务'), { target: { value: '咖啡和点心' } })
  await userEvent.click(within(dialog).getByRole('button', { name: '放入聊天输入框' }))
  expect(String((input as HTMLTextAreaElement).value)).toContain('先帮我了解怎么做')
  expect(String((input as HTMLTextAreaElement).value)).toContain('对外品牌名称：小行星咖啡')
  expect(api.writes).toEqual([])
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), { timeout: 3000 })
  await userEvent.click(screen.getByRole('button', { name: '发送' }))
  await waitFor(() => expect(api.messages()).toHaveLength(1))
  expect(api.writes[0].body).toEqual({ installationId: '101', projectId: '4001' })
  expect(api.messages()[0].body).not.toHaveProperty('executionIntent')
  expect(api.messages()[0].body.content).toContain('不启动监测或投稿')
})

it('通过引导填写的客户资料不会随项目切换带入另一客户', async () => {
  const api = stubApi()
  renderApp(['/app?installation=101&project=4001'])
  await screen.findByText('从了解你的品牌开始', { selector: 'h1' })
  await act(() => vi.dynamicImportSettled())
  await userEvent.click(await screen.findByRole('button', { name: '填写公司信息' }))
  fireEvent.change(screen.getByLabelText('对外品牌名称'), { target: { value: '小行星咖啡' } })
  await userEvent.click(screen.getByRole('button', { name: '放入聊天输入框' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), { timeout: 3000 })
  await userEvent.click(screen.getByRole('combobox', { name: '任务所属项目' }))
  await userEvent.click(await screen.findByText('艾尼亚科', { selector: '.ant-select-item-option-content' }))
  expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue('')
  await userEvent.click(screen.getByRole('button', { name: '填写公司信息' }))
  expect(screen.getByLabelText('对外品牌名称')).toHaveValue('')
  expect(api.writes).toEqual([])
})

it('只选一次项目即可首聊；没有经营空间或选品前置，普通消息继承项目', async () => {
  const api = stubApi()
  renderApp(['/app?installation=101&project=4001'])
  await waitFor(() => expect(screen.getByRole('combobox', { name: '任务所属项目' })).toBeEnabled())
  expect(screen.queryByRole('button', { name: /选择商品/ })).not.toBeInTheDocument()
  expect(screen.queryByText('经营空间')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '本次资料与任务' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '任务进度' })).not.toBeInTheDocument()
  fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '先给小行星一个品牌优化方案' } })
  await userEvent.click(screen.getByRole('button', { name: '发送' }))
  await waitFor(() => expect(api.messages()).toHaveLength(1))
  expect(api.writes[0]).toEqual({ path: '/api/v1/conversations', body: { installationId: '101', projectId: '4001' } })
  expect(api.messages()[0].body).toMatchObject({ content: '先给小行星一个品牌优化方案', contextSelectionMode: 'INHERIT', attachments: [] })
  expect(api.messages()[0].body).not.toHaveProperty('selectedSpaceId')
  expect(api.messages()[0].body).not.toHaveProperty('executionIntent')
  expect(api.reads.some((path) => path.startsWith('/api/v1/spaces'))).toBe(false)
  expect(screen.queryByText('项目：小行星咖啡店')).not.toBeInTheDocument()
  expect(screen.getByRole('combobox', { name: '任务所属项目' })).toBeEnabled()
})

it('切换客户项目清除上一客户报告、隐式商品和图片，保留输入，不自动发任务', async () => {
  const api = stubApi()
  storeCurrentAttachmentIds(userFixture.id, 'new', ['81'])
  renderApp(['/app?installation=101&project=4001&geoReportId=9001&articleGoal=true'], <RouteProbe />)
  await screen.findByLabelText('当前对话引用的监测报告')
  const input = screen.getByRole('textbox', { name: '消息输入' })
  fireEvent.change(input, { target: { value: '我先补充一下客户资料' } })
  await userEvent.click(screen.getByRole('combobox', { name: '任务所属项目' }))
  await userEvent.click(await screen.findByText('艾尼亚科', { selector: '.ant-select-item-option-content' }))
  await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent('/app?installation=101&project=4002'))
  expect(screen.getByTestId('route')).not.toHaveTextContent('geoReportId')
  expect(screen.getByTestId('route')).not.toHaveTextContent('articleGoal')
  expect(screen.queryByLabelText('当前对话引用的监测报告')).not.toBeInTheDocument()
  expect(readCurrentAttachmentIds(userFixture.id, 'new')).toEqual([])
  expect(input).toHaveValue('我先补充一下客户资料')
  expect(api.writes).toEqual([])
  await userEvent.click(screen.getByRole('button', { name: '发送' }))
  await waitFor(() => expect(api.messages()).toHaveLength(1))
  expect(api.writes[0].body.projectId).toBe('4002')
  expect(api.messages()[0].body).toMatchObject({ contextSelectionMode: 'INHERIT', attachments: [] })
  expect(api.messages()[0].body).not.toHaveProperty('selectedProductId')
  expect(api.messages()[0].body).not.toHaveProperty('selectedSpaceId')
  expect(api.messages()[0].body).not.toHaveProperty('geoReportId')
})

it('历史聊天仍能继承原商品归属，不需新建经营空间或迁移历史数据', async () => {
  const api = stubApi()
  renderApp(['/app/chat?conversation=1'])
  await screen.findByRole('textbox', { name: '消息输入' })
  expect(screen.queryByText('项目：小行星咖啡店')).not.toBeInTheDocument()
  fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '记住，咖啡和曲奇都是我们的主营品类' } })
  await userEvent.click(screen.getByRole('button', { name: '发送' }))
  await waitFor(() => expect(api.messages()).toHaveLength(1))
  expect(api.messages()[0].body.contextSelectionMode).toBe('INHERIT')
  expect(api.writes).toHaveLength(1)
  expect(api.reads.some((path) => path.startsWith('/api/v1/spaces'))).toBe(false)
})

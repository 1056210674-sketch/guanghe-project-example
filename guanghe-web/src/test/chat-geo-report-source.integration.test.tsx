import { act, configure, fireEvent, renderHook, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation, useNavigate } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { appLinks, APP_PATHS } from '../routes/paths'
import { GEO_REPORT_QUESTION, GEO_VISIBILITY_GOAL, useGeoReportChatSource } from '../pages/chat/useGeoReportChatSource'
import { storeCurrentAttachmentIds } from '../features/chat/live/attachmentRecovery'
import { renderApp } from './renderApp'
import { geoPlatformFixtures, geoReportDto, geoRunDto } from './fixtures/geo'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const report = { ...geoReportDto('geo-synthesis-v1'), topic: { ...geoReportDto('geo-synthesis-v1').topic, productId: '31' } }
const now = '2026-09-28T08:00:00Z'
const conversation = { id: '1', title: '已有对话', selectedSpaceId: null, selectedProductId: null, version: 1, lastMessageAt: now, createdAt: now, updatedAt: now }
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[]) => ok({ items, page: 1, size: 100, total: items.length, hasNext: false })
const product = (id: string) => ({ id, spaceId: '4001', name: `商品${id}`, category: '杯具', modelSpec: '500ml', aliasCount: 0, version: 1, createdAt: now, updatedAt: now })
configure({ asyncUtilTimeout: 5000 })

function RouteProbe() {
  const location = useLocation()
  const navigate = useNavigate()
  return <><output data-testid="route">{location.pathname}{location.search}</output>
    <button onClick={() => navigate(-1)}>浏览器返回</button>
    <button onClick={() => navigate('/app/chat')}>直接进入空白聊天</button>
    <button onClick={() => navigate('/app/chat?conversation=1')}>直接返回首轮会话</button></>
}

function stubPage(options: { reportResponse?: () => Response | Promise<Response>; topicMismatch?: boolean; lostAck?: boolean } = {}) {
  const writes: Array<{ path: string; body: Record<string, unknown> }> = []
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    if (url === '/api/v1/assets/81/download-url') return ok({ url: 'https://example.com/fixture.png', expiresAt: '2099-01-01T00:00:00Z' })
    if (init?.method === 'POST') {
      const body = JSON.parse(String(init.body)) as Record<string, unknown>
      writes.push({ path: url, body })
      if (url === '/api/v1/conversations') return ok(conversation)
      if (url === '/api/v1/conversations/1/messages') {
        if (options.lostAck) throw new TypeError('network response lost')
        return ok({ messageId: String(writes.length), runId: '00000000-0000-4000-8000-000000000001', status: 'COMPLETED' })
      }
      throw new Error(`Unexpected write: ${url}`)
    }
    if (url === '/api/v1/geo/reports/9001') return options.reportResponse ? options.reportResponse() : ok(report)
    if (url === '/api/v1/geo/runs/8001') return ok(geoRunDto)
    if (url === '/api/v1/geo/platforms') return ok(geoPlatformFixtures)
    if (url === '/api/v1/assets/81') return ok({ id: '81', assetType: 'IMAGE', sourceType: 'UPLOADED', status: 'READY', safetyStatus: 'APPROVED', name: '本次附件', spaceId: '4001', primaryProductId: '31', productIds: ['31'], contentType: 'image/png', sizeBytes: 1000, sha256: 'a'.repeat(64), width: 1024, height: 1024, sourceTaskId: null, createdAt: now, updatedAt: now })
    if (url === '/api/v1/geo/topics/5001') return ok({ ...report.topic, spaceId: options.topicMismatch ? '999' : '4001', productId: '32' })
    if (url.startsWith('/api/v1/conversations?page=')) return page([conversation])
    if (url === '/api/v1/conversations/1') return ok(conversation)
    if (url.includes('/message-operations/')) return ok({ messageId: '2', runId: '00000000-0000-4000-8000-000000000001', status: 'COMPLETED' })
    if (url.includes('/snapshot?')) return ok({ messages: writes.filter(({ path }) => path.endsWith('/messages')).map(({ body }, index) => ({ id: String(index + 20), conversationId: '1', role: 'ASSISTANT', contentType: 'TEXT', content: { text: `已回答：${body.content}` }, replyToMessageId: null, runId: '00000000-0000-4000-8000-000000000001', attachmentAssetIds: [], sequence: index + 1, status: 'COMPLETED', createdAt: now, updatedAt: now })), nextBeforeSequence: null, highWatermark: 0, activeRun: null })
    if (url.startsWith('/api/v1/tasks?conversationId=')) return page([])
    if (url.startsWith('/api/v1/spaces?')) return page(['4001', '4002'].map((id) => ({ id, name: `空间${id}`, brandName: '测试品牌', productCount: 2, competitorCount: 0, version: 1, createdAt: now, updatedAt: now })))
    if (/\/spaces\/\d+\/products\?/.test(url)) return page([product('31'), product('32')])
    if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    if (url.endsWith('/events')) return new Response('')
    throw new Error(`Unexpected request: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { writes, fetchMock, messages: () => writes.filter(({ path }) => path.endsWith('/messages')) }
}

beforeEach(async () => {
  await import('../pages/chat/ChatPage')
  vi.unstubAllGlobals()
  localStorage.clear()
  sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('报告来源聊天（仅 Browser API stub，无模型调用）', () => {
  it('一键分析只发送明确目标，不猜最新报告或直接创建监测任务', async () => {
    const api = stubPage()
    renderApp([APP_PATHS.chat])
    await userEvent.dblClick(await screen.findByRole('button', { name: /分析商品可见度/ }))
    await waitFor(() => expect(api.messages()).toHaveLength(1))
    expect(api.messages()[0].body.content).toBe(GEO_VISIBILITY_GOAL)
    expect(api.messages()[0].body).not.toHaveProperty('geoReportId')
    expect(api.writes.map(({ path }) => path)).toEqual(['/api/v1/conversations', '/api/v1/conversations/1/messages'])
    expect(api.fetchMock.mock.calls.some(([path]) => String(path).includes('/geo/'))).toBe(false)
  })

  it('一键分析保留已有输入，不发送或覆盖用户的另一话题', async () => {
    const api = stubPage()
    renderApp([APP_PATHS.chat])
    const input = await screen.findByRole('textbox', { name: '消息输入' })
    fireEvent.change(input, { target: { value: '今天聊聊我的新品命名' } })
    await userEvent.click(screen.getByRole('button', { name: /分析商品可见度/ }))
    expect(input).toHaveValue('今天聊聊我的新品命名')
    expect(screen.getByText(/已保留你的输入，未发送分析请求/)).toBeVisible()
    expect(api.writes).toEqual([])
  })

  it('一键分析不挪用尚未发送的图片附件', async () => {
    const api = stubPage()
    storeCurrentAttachmentIds(userFixture.id, 'new', ['81'])
    renderApp([APP_PATHS.chat])
    await userEvent.click(await screen.findByRole('button', { name: /分析商品可见度/ }))
    await screen.findByText(/已保留本次图片，未发送分析请求/)
    expect(api.writes).toEqual([])
  })

  it('从真实报告详情入口进入精确报告的新聊天，只导航、验权与预填', async () => {
    const api = stubPage()
    renderApp([appLinks.geoReportDetail(report.id)], <RouteProbe />)
    await userEvent.click(await screen.findByRole('button', { name: '带这份报告问 AI' }))
    await screen.findByText('本次报告来源：测试监测对象')
    expect(screen.getByTestId('route')).toHaveTextContent('/app/chat?geoReportId=9001')
    expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue(GEO_REPORT_QUESTION)
    expect(api.writes).toEqual([])
  }, 15000)

  it('读取报告与专题后只预填，并用冻结商品而非专题当前商品，不创建会话或消息', async () => {
    const api = stubPage()
    renderApp([appLinks.geoReportChat(report.id)])
    await screen.findByText('本次报告来源：测试监测对象')
    expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue(GEO_REPORT_QUESTION)
    const context = screen.getByRole('complementary', { name: '本次对话上下文' })
    await waitFor(() => expect(within(context).getByText('商品31', { selector: 'strong' })).toBeVisible())
    expect(api.writes).toEqual([])
    expect(api.fetchMock.mock.calls.map(([path]) => String(path))).toContain('/api/v1/geo/topics/5001')
    expect(GEO_REPORT_QUESTION).not.toContain(report.observations[0].answerText)
    expect(screen.getByRole('button', { name: '查看已有 GEO 趋势' })).toBeVisible()
    expect(screen.getByRole('button', { name: '查看已有 GEO 趋势' }).closest('.chat-conversation__panel-actions')).toBeNull()
    expect(screen.getByText('分析商品可见度')).toBeVisible()
  })

  it('报告加载期间不能发送，且完成后不会覆盖用户已输入的内容', async () => {
    let release: ((response: Response) => void) | undefined
    const api = stubPage({ reportResponse: () => new Promise<Response>((resolve) => { release = resolve }) })
    renderApp([appLinks.geoReportChat(report.id)])
    await screen.findByText('正在核对报告来源…')
    fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '保留我的具体问题' } })
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled()
    await act(async () => release?.(ok(report)))
    await screen.findByText('本次报告来源：测试监测对象')
    expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue('保留我的具体问题')
    expect(api.writes).toEqual([])
  })

  it('首次发送与追问都显式绑定同一报告，移除后消息不携带旧报告', async () => {
    const api = stubPage()
    renderApp([appLinks.geoReportChat(report.id)])
    await screen.findByText('本次报告来源：测试监测对象')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(api.messages()).toHaveLength(1))
    expect(api.messages()[0].body).toMatchObject({ geoReportId: '9001', selectedSpaceId: '4001', selectedProductId: '31', contextSelectionMode: 'REPLACE', content: GEO_REPORT_QUESTION, attachments: [] })
    await screen.findByText(`已回答：${GEO_REPORT_QUESTION}`)
    await waitFor(() => expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue(''))
    fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '这份报告有哪些限制？' } })
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled())
    expect(screen.getByText('本次报告来源：测试监测对象')).toBeVisible()
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(api.messages()).toHaveLength(2))
    expect(api.messages()[1].body.geoReportId).toBe('9001')
    await waitFor(() => expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue(''))
    await userEvent.click(screen.getByRole('button', { name: '移除报告来源' }))
    expect(screen.queryByText('本次报告来源：测试监测对象')).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '现在普通聊聊' } })
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(api.messages()).toHaveLength(3))
    expect(api.messages()[2].body).not.toHaveProperty('geoReportId')
    expect(api.writes.filter(({ path }) => path === '/api/v1/conversations')).toHaveLength(1)
  }, 15000)

  it.each(['forbidden', 'mismatch'] as const)('报告无权或专题范围不匹配时阻止半绑定发送：%s', async (failure) => {
    const api = stubPage(failure === 'mismatch' ? { topicMismatch: true } : { reportResponse: () => new Response(JSON.stringify({ code: 'FORBIDDEN', message: 'private backend detail', traceId: testTraceId }), { status: 403, headers: { 'Content-Type': 'application/json' } }) })
    renderApp([appLinks.geoReportChat(report.id)])
    await screen.findByText(/报告不存在、无权访问或暂时无法读取/)
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled()
    expect(screen.queryByText('private backend detail')).not.toBeInTheDocument()
    expect(api.writes).toEqual([])
    await userEvent.click(screen.getByRole('button', { name: '移除报告来源' }))
    fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '普通问题' } })
    expect(screen.getByRole('button', { name: '发送' })).toBeEnabled()
  })

  it('带来源的已有会话深链不会静默改写会话资料或发送', async () => {
    const api = stubPage()
    renderApp([`${APP_PATHS.chat}?conversation=1&geoReportId=9001`])
    await screen.findByText(/没有把报告加入已有会话/)
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled()
    expect(api.fetchMock.mock.calls.some(([path]) => String(path).includes('/geo/'))).toBe(false)
    expect(api.writes).toEqual([])
  })

  it('手动移除会清理 URL，刷新或重访不会重新附带来源', async () => {
    stubPage()
    renderApp([appLinks.geoReportChat(report.id)], <RouteProbe />)
    await screen.findByText('本次报告来源：测试监测对象')
    expect(screen.getByTestId('route')).toHaveTextContent('geoReportId=9001')
    await userEvent.click(screen.getByRole('button', { name: '移除报告来源' }))
    await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent(/^\/app\/chat$/))
    expect(screen.queryByText('本次报告来源：测试监测对象')).not.toBeInTheDocument()
  })

  it('报告与本次图片附件冲突时，不创建会话或发消息', async () => {
    const api = stubPage()
    storeCurrentAttachmentIds(userFixture.id, 'new', ['81'])
    renderApp([appLinks.geoReportChat(report.id)])
    await screen.findByText('本次报告来源：测试监测对象')
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled())
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await screen.findByText(/报告来源不能与本次图片附件一起发送/)
    expect(api.writes).toEqual([])
  })

  it('显式新聊天会清除来源，且不提前创建空白会话', async () => {
    const api = stubPage()
    renderApp([appLinks.geoReportChat(report.id)], <RouteProbe />)
    await screen.findByText('本次报告来源：测试监测对象')
    await userEvent.click(screen.getByRole('button', { name: '新聊天' }))
    await screen.findByRole('heading', { name: '今天想完成什么？' })
    expect(screen.getByTestId('route')).not.toHaveTextContent('conversation=')
    expect(screen.queryByText('本次报告来源：测试监测对象')).not.toBeInTheDocument()
    expect(api.messages()).toEqual([])
  })

  it('首轮回执丢失后仍保留报告和原幂等请求，只查询受理结果，不重复发送', async () => {
    const api = stubPage({ lostAck: true })
    renderApp([appLinks.geoReportChat(report.id)], <RouteProbe />)
    await screen.findByText('本次报告来源：测试监测对象')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(api.messages()).toHaveLength(1))
    await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent('conversation=1'))
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled())
    expect(screen.getByText('本次报告来源：测试监测对象')).toBeVisible()
    expect(api.messages()[0].body.geoReportId).toBe('9001')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(api.fetchMock.mock.calls.some(([path]) => String(path).includes(`/message-operations/${api.messages()[0].body.clientMessageId}`))).toBe(true))
    await waitFor(() => expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue(''))
    expect(api.messages()).toHaveLength(1)
    expect(sessionStorage.getItem(`zhixiaohe.chat.pending-message.v1.${userFixture.id}.1`)).toBeNull()
  }, 15000)

  it('真实路由返回空白聊天或切回首轮会话不会复活已经绑定的报告来源', async () => {
    const api = stubPage()
    renderApp(['/app/chat', appLinks.geoReportChat(report.id)], <RouteProbe />)
    await screen.findByText('本次报告来源：测试监测对象')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await screen.findByText(`已回答：${GEO_REPORT_QUESTION}`)
    await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent('conversation=1'))
    expect(screen.getByText('本次报告来源：测试监测对象')).toBeVisible()
    await userEvent.click(screen.getByRole('button', { name: '浏览器返回' }))
    await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent(/^\/app\/chat$/))
    expect(screen.queryByText('本次报告来源：测试监测对象')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '直接返回首轮会话' }))
    await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent('conversation=1'))
    expect(screen.queryByText('本次报告来源：测试监测对象')).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '普通后续消息' } })
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(api.messages()).toHaveLength(2))
    expect(api.messages()[1].body).not.toHaveProperty('geoReportId')
  }, 15000)

  it.each(['space', 'product'] as const)('切换资料后立即清除报告，不会随下一次发送：%s', async (field) => {
    const api = stubPage()
    renderApp([appLinks.geoReportChat(report.id)])
    await screen.findByText('本次报告来源：测试监测对象')
    if (field === 'space') {
      fireEvent.mouseDown(screen.getByRole('combobox', { name: '选择经营空间' }))
      await userEvent.click(await screen.findByText('空间4002', { selector: '.ant-select-item-option-content' }))
    } else {
      await userEvent.click(await screen.findByRole('button', { name: '更换商品' }))
      await userEvent.click(await screen.findByRole('radio', { name: /^商品32/ }))
      await userEvent.click(screen.getByRole('button', { name: '确认选择' }))
    }
    expect(screen.queryByText('本次报告来源：测试监测对象')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(api.messages()).toHaveLength(1))
    expect(api.messages()[0].body).not.toHaveProperty('geoReportId')
    expect(api.messages()[0].body.selectedSpaceId).toBe(field === 'space' ? '4002' : '4001')
  }, 15000)
})

describe('报告异步来源生命周期', () => {
  it('清除后迟到的报告响应不恢复来源、不写入资料或预填', async () => {
    let release: ((response: Response) => void) | undefined
    const api = stubPage({ reportResponse: () => new Promise<Response>((resolve) => { release = resolve }) })
    const onLoaded = vi.fn()
    const { result } = renderHook(() => useGeoReportChatSource({ ownerId: userFixture.id, requestedReportId: '9001', onLoaded }))
    await waitFor(() => expect(release).toBeDefined())
    act(() => result.current.clear())
    await act(async () => release?.(ok(report)))
    expect(result.current.source).toBeUndefined()
    expect(onLoaded).not.toHaveBeenCalled()
    expect(api.fetchMock.mock.calls.some(([path]) => String(path).includes('/topics/'))).toBe(false)
  })

  it.each(['conversation', 'owner'] as const)('切换上下文后来源不复活：%s', async (change) => {
    stubPage()
    const onLoaded = vi.fn()
    const initialProps = { ownerId: userFixture.id as string | undefined, conversationId: undefined as string | undefined, requestedReportId: '9001' as string | null, onLoaded }
    const { result, rerender } = renderHook((props) => useGeoReportChatSource(props), { initialProps })
    await waitFor(() => expect(result.current.source?.reportId).toBe('9001'))
    rerender({ ...initialProps, requestedReportId: null, ...(change === 'owner' ? { ownerId: undefined } : { conversationId: '2' }) })
    await waitFor(() => expect(result.current.source).toBeUndefined())
    rerender({ ...initialProps, requestedReportId: null })
    expect(result.current.source).toBeUndefined()
  })
})

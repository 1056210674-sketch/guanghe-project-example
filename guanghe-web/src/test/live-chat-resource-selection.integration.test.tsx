import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { storeAuthToken } from '../auth/tokenStorage'
import type { ConversationMessage, SendConversationMessageRequest } from '../api/conversationApi'
import { readCurrentAttachmentIds, storeCurrentAttachmentIds } from '../features/chat/live/attachmentRecovery'
import { readConversationEventCursor } from '../features/chat/live/conversationStream'
import { readMessageAttempt } from '../features/chat/live/messageAttempt'
import { APP_PATHS } from '../routes/paths'
import { useAuthStore } from '../stores/authStore'
import { renderApp } from './renderApp'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const now = '2026-10-05T08:00:00Z'
const runId = '00000000-0000-4000-8000-000000000001'
const conversation = { id: '1', title: '查询商品', selectedSpaceId: '21', selectedProductId: '31', version: 1, lastMessageAt: now, createdAt: now, updatedAt: now }
const interaction = { interactionType: 'RESOURCE_SELECTION', intent: 'GENERAL', question: '你想查看哪件咖啡？',
  candidateSetId: '901', available: true, missingFields: [], choices: ['咖啡', '咖啡'], candidates: [
    { choiceId: 'c1', kind: 'PRODUCT', resourceId: '31', name: '咖啡', spaceId: '21', spaceName: '抖店', hasReport: true, exact: true },
    { choiceId: 'c2', kind: 'PRODUCT', resourceId: '32', name: '咖啡', spaceId: '22', spaceName: '拼多多', hasReport: false, exact: true },
  ] } as const

function message(id: string, role: ConversationMessage['role'], content: Record<string, unknown>): ConversationMessage {
  return { id, conversationId: '1', role, contentType: 'TEXT', content, replyToMessageId: null,
    runId, attachmentAssetIds: [], sequence: Number(id), status: 'COMPLETED', createdAt: now, updatedAt: now }
}
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data }), { headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[]) => ok({ items, page: 1, size: 100, total: items.length, hasNext: false })
const failure = (status: number) => new Response(JSON.stringify({ code: 'CONFLICT', message: '选择已失效，请重新查询', traceId: testTraceId }), { status, headers: { 'Content-Type': 'application/json' } })

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function stubApi(options: { lostAck?: boolean; readFailure?: boolean; statusError?: number; conflict?: boolean; stream?: ReadableStream<Uint8Array>; delayedSend?: Promise<Response> } = {}) {
  const messages = [message('10', 'ASSISTANT', { interaction }), message('11', 'USER', { text: '先聊别的，稍后再选商品' }),
    message('12', 'ASSISTANT', { text: '可以继续聊', businessOutcome: { schemaVersion: 1, status: 'ANSWERED' } })]
  const writes: { url: string; body: SendConversationMessageRequest; idempotencyKey: string }[] = []
  const reads: string[] = []
  let highWatermark = 0
  let fail = Boolean(options.lostAck)
  const setAvailable = (available: boolean) => { messages[0] = message('10', 'ASSISTANT', { interaction: { ...interaction, available } }) }
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    if (init?.method === 'POST') {
      const body = JSON.parse(String(init.body)) as SendConversationMessageRequest
      writes.push({ url, body, idempotencyKey: (init.headers as Headers).get('Idempotency-Key') ?? '' })
      if (url !== '/api/v1/conversations/1/messages') throw new Error(`Unexpected write: ${url}`)
      if (options.delayedSend) return options.delayedSend
      if (options.conflict) { setAvailable(false); return failure(409) }
      if (options.statusError) return failure(options.statusError)
      if (fail) { fail = false; throw new TypeError('response lost before acceptance') }
      if (options.readFailure) {
        messages.push(message('13', 'USER', { text: body.content }), message('14', 'ASSISTANT', { text: '资料暂时无法读取，请稍后再试',
          businessOutcome: { schemaVersion: 1, status: 'FAILED', reasonCode: 'READONLY_EVIDENCE_UNAVAILABLE', failureStage: 'LOOKUP' } }))
        return ok({ messageId: '14', runId, status: 'COMPLETED' })
      }
      setAvailable(false)
      messages.push(message('13', 'USER', { text: body.content }), message('14', 'ASSISTANT', { text: '拼多多的咖啡资料已查到', businessOutcome: { schemaVersion: 1, status: 'ANSWERED' } }))
      return ok({ messageId: '14', runId, status: 'COMPLETED' })
    }
    reads.push(url)
    if ((init?.headers as Headers).get('Authorization')?.includes('other-test-owner')) {
      if (url.startsWith('/api/v1/conversations?page=')) return page([])
      if (url === '/api/v1/conversations/1') return failure(404)
      if (url.includes('/snapshot?')) return ok({ messages: [], nextBeforeSequence: null, highWatermark: 0, activeRun: null })
    }
    if (url.includes('/message-operations/')) return failure(404)
    if (url.startsWith('/api/v1/conversations?page=')) return page([conversation])
    if (url === '/api/v1/conversations/1') return ok(conversation)
    if (url.includes('/snapshot?')) return ok({ messages, nextBeforeSequence: null, highWatermark,
      activeRun: options.stream ? { messageId: '12', runId, status: 'RUNNING' } : null })
    if (url.startsWith('/api/v1/tasks?conversationId=')) return page([])
    if (url.startsWith('/api/v1/spaces?')) return page([{ id: '21', name: '抖店', brandName: '测试品牌', productCount: 1, competitorCount: 0, version: 1, createdAt: now, updatedAt: now }])
    if (url.startsWith('/api/v1/spaces/21/products?')) return page([{ id: '31', spaceId: '21', name: '当前商品', category: '咖啡', modelSpec: '500ml', aliasCount: 0, version: 1, createdAt: now, updatedAt: now }])
    if (url === '/api/v1/assets/81') return ok({ id: '81', assetType: 'IMAGE', sourceType: 'UPLOADED', status: 'READY', safetyStatus: 'APPROVED', name: '我的未发送附件', spaceId: '21', primaryProductId: '31', productIds: ['31'], contentType: 'image/png', sizeBytes: 1000, sha256: 'a'.repeat(64), width: 1024, height: 1024, sourceTaskId: null, createdAt: now, updatedAt: now })
    if (url === '/api/v1/assets/81/download-url') return ok({ url: 'https://example.com/test.png', expiresAt: testExpiresAt })
    if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    if (url.endsWith('/events')) return new Response(options.stream ?? '', { headers: { 'Content-Type': 'text/event-stream' } })
    throw new Error(`Unexpected read: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { writes, reads, setAvailable, setHighWatermark: (value: number) => { highWatermark = value }, fetchMock }
}

beforeEach(async () => {
  await import('../pages/chat/ChatPage')
  vi.unstubAllGlobals()
  localStorage.clear()
  sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('资源选择的真实页面行为（仅 Browser API，不调用模型）', () => {
  it('旁聊和技术完成不关闭卡片；选另一空间只读，双击不重发，保留未发送文本、附件和侧栏', async () => {
    storeCurrentAttachmentIds(userFixture.id, '1', ['81'])
    const api = stubApi()
    renderApp([`${APP_PATHS.chat}?conversation=1`])
    const choice = await screen.findByRole('button', { name: '咖啡 商品 · 拼多多' }, { timeout: 5000 })
    expect(choice).toBeEnabled()
    const input = screen.getByRole('textbox', { name: '消息输入' })
    fireEvent.change(input, { target: { value: '下一条我还想写篇文章' } })
    await screen.findByText('我的未发送附件', {}, { timeout: 5000 })
    await userEvent.dblClick(choice)
    await waitFor(() => expect(api.writes).toHaveLength(1), { timeout: 5000 })
    expect(api.writes[0].body).toEqual({ content: '咖啡', replyToMessageId: '10', resourceSelection: { candidateSetId: '901', choiceId: 'c2' },
      contextSelectionMode: 'INHERIT', attachments: [], clientMessageId: expect.stringMatching(/^[0-9a-f-]{36}$/) })
    await screen.findByText(/这次选择已失效/, {}, { timeout: 5000 })
    expect(choice).toBeDisabled()
    expect(input).toHaveValue('下一条我还想写篇文章')
    expect(readCurrentAttachmentIds(userFixture.id, '1')).toEqual(['81'])
    expect(screen.getByText('我的未发送附件')).toBeVisible()
    const sidebar = screen.getByRole('complementary', { name: '本次对话上下文' })
    expect(within(sidebar).getByText('抖店', { selector: 'strong' })).toBeVisible()
    expect(within(sidebar).getByText('当前商品', { selector: 'strong' })).toBeVisible()
    expect(api.writes.map(({ url }) => url)).toEqual(['/api/v1/conversations/1/messages'])
    expect(api.reads.some((url) => /task-drafts|\/geo\/|\/spaces\/22/.test(url))).toBe(false)
  })

  it('网络错误不消费卡片，同一选择重试沿用幂等；不把选择内容塞回未发送输入', async () => {
    const api = stubApi({ lostAck: true })
    renderApp([`${APP_PATHS.chat}?conversation=1`])
    const choice = await screen.findByRole('button', { name: '咖啡 商品 · 拼多多' }, { timeout: 5000 })
    const input = screen.getByRole('textbox', { name: '消息输入' })
    fireEvent.change(input, { target: { value: '这段草稿别动' } })
    await userEvent.click(choice)
    await waitFor(() => expect(api.writes).toHaveLength(1), { timeout: 5000 })
    await waitFor(() => expect(choice).toBeEnabled(), { timeout: 5000 })
    expect(readMessageAttempt(userFixture.id, '1')).toBeDefined()
    expect(input).toHaveValue('这段草稿别动')
    await userEvent.click(choice)
    await waitFor(() => expect(api.writes).toHaveLength(2), { timeout: 5000 })
    expect(api.writes[1].body).toEqual(api.writes[0].body)
    expect(api.writes[1].idempotencyKey).toBe(api.writes[0].idempotencyKey)
    await waitFor(() => expect(readMessageAttempt(userFixture.id, '1')).toBeUndefined(), { timeout: 5000 })
    expect(input).toHaveValue('这段草稿别动')
  })

  it('服务端拒绝过期选择后同步失效状态，不提交付费任务', async () => {
    const api = stubApi({ conflict: true })
    renderApp([`${APP_PATHS.chat}?conversation=1`])
    const choice = await screen.findByRole('button', { name: '咖啡 商品 · 拼多多' }, { timeout: 5000 })
    await userEvent.click(choice)
    await waitFor(() => expect(choice).toBeDisabled(), { timeout: 5000 })
    expect(screen.getByText(/这次选择已失效/)).toBeVisible()
    expect(api.writes).toHaveLength(1)
    expect(api.reads.filter((url) => url.includes('/snapshot?')).length).toBeGreaterThanOrEqual(2)
  })

  it('读取业务失败但 Run 技术完成时，服务端未消费的选择卡仍可继续使用', async () => {
    const api = stubApi({ readFailure: true })
    renderApp([`${APP_PATHS.chat}?conversation=1`])
    const choice = await screen.findByRole('button', { name: '咖啡 商品 · 拼多多' }, { timeout: 5000 })
    await userEvent.click(choice)
    await screen.findByText('资料暂时无法读取，请稍后再试', {}, { timeout: 5000 })
    await waitFor(() => expect(choice).toBeEnabled(), { timeout: 5000 })
    expect(api.writes).toHaveLength(1)
    expect(screen.queryByText(/这次选择已失效/)).not.toBeInTheDocument()
    expect(screen.getAllByLabelText('光禾AI智能体框架消息').at(-1)).toHaveTextContent('本次回复未完成')
  })

  it('受控 404 读取失败保持原输入、暂存附件，服务端 available=true 的卡片仍可选', async () => {
    storeCurrentAttachmentIds(userFixture.id, '1', ['81'])
    const api = stubApi({ statusError: 404 })
    renderApp([`${APP_PATHS.chat}?conversation=1`])
    const choice = await screen.findByRole('button', { name: '咖啡 商品 · 拼多多' }, { timeout: 5000 })
    const input = screen.getByRole('textbox', { name: '消息输入' })
    fireEvent.change(input, { target: { value: '我的下一条问题' } })
    await userEvent.click(choice)
    await waitFor(() => expect(api.reads.filter((url) => url.includes('/snapshot?')).length).toBeGreaterThanOrEqual(2), { timeout: 5000 })
    await waitFor(() => expect(choice).toBeEnabled(), { timeout: 5000 })
    expect(input).toHaveValue('我的下一条问题')
    expect(readCurrentAttachmentIds(userFixture.id, '1')).toEqual(['81'])
    expect(api.writes).toHaveLength(1)
    expect(readMessageAttempt(userFixture.id, '1')).toBeUndefined()
  })

  it('资源事件只刷新消息快照：未知元数据照样推进游标，不改侧栏或暴露内部资料', async () => {
    let controller!: ReadableStreamDefaultController<Uint8Array>
    const stream = new ReadableStream<Uint8Array>({ start(value) { controller = value } })
    const api = stubApi({ stream })
    const mounted = renderApp([`${APP_PATHS.chat}?conversation=1`])
    const choice = await screen.findByRole('button', { name: '咖啡 商品 · 拼多多' }, { timeout: 5000 })
    await waitFor(() => expect(api.reads).toContain('/api/v1/conversations/1/events'), { timeout: 5000 })
    const readsBefore = [...api.reads]
    api.setAvailable(false)
    api.setHighWatermark(1)
    act(() => controller.enqueue(new TextEncoder().encode(`id: 1\nevent: context.resource\ndata: ${JSON.stringify({
      eventId: '101', sequence: 1, conversationId: '1', occurredAt: now, messageId: '10', taskId: null,
      payload: { unknownFutureMetadata: true, inputHash: 'secret', result: { text: 'private result' } },
    })}\n\n`)))
    await screen.findByText(/这次选择已失效/, {}, { timeout: 5000 })
    expect(choice).toBeDisabled()
    await waitFor(() => expect(readConversationEventCursor('1')?.lastSequence).toBe(1), { timeout: 5000 })
    const laterReads = api.reads.slice(readsBefore.length)
    expect(laterReads.length).toBeGreaterThan(0)
    // The existing stream hook reconnects from the new snapshot watermark;
    // no sidebar, billing, provider or paid-draft reads are needed.
    expect(laterReads.every((url) => url.includes('/snapshot?') || url.endsWith('/events'))).toBe(true)
    expect(laterReads.filter((url) => url.includes('/snapshot?'))).toHaveLength(1)
    expect(api.writes).toEqual([])
    expect(screen.queryByText(/secret|private result/)).not.toBeInTheDocument()
    mounted.unmount()
  })

  it('换账号后迟到选择响应不会恢复旧用户卡片或旧输入', async () => {
    const reply = deferred<Response>()
    const api = stubApi({ delayedSend: reply.promise })
    renderApp([`${APP_PATHS.chat}?conversation=1`])
    await userEvent.click(await screen.findByRole('button', { name: '咖啡 商品 · 拼多多' }, { timeout: 5000 }))
    await waitFor(() => expect(api.writes).toHaveLength(1), { timeout: 5000 })
    act(() => {
      storeAuthToken({ accessToken: 'other-test-owner', expiresAt: testExpiresAt })
      useAuthStore.setState({ status: 'authenticated', user: { ...userFixture, id: '1009', username: 'new-owner' }, error: null })
    })
    await act(async () => { reply.resolve(ok({ messageId: '14', runId, status: 'COMPLETED' })); await reply.promise })
    await waitFor(() => expect(screen.queryByRole('region', { name: '选择查询对象' })).not.toBeInTheDocument(), { timeout: 5000 })
    expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue('')
    expect(readMessageAttempt('1009', '1')).toBeUndefined()
    expect(api.writes).toHaveLength(1)
    expect(api.fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })
})

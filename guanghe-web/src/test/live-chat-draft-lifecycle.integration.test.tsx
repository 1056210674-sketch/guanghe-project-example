import { act, fireEvent, screen, waitFor as waitForDOM, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConversationMessage, TaskDraft } from '../api/conversationApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { APP_PATHS } from '../routes/paths'
import { useAuthStore } from '../stores/authStore'
import { renderApp } from './renderApp'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const now = '2026-09-24T08:00:00Z'
const runId = '00000000-0000-4000-8000-000000000099'
const conversation = { id: '1', title: '多轮创作', selectedSpaceId: null, selectedProductId: null, version: 1, lastMessageAt: now, createdAt: now, updatedAt: now }
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { headers: { 'Content-Type': 'application/json' } })
// Full-route, multi-card flows include asynchronous draft hydration and Ant Design rendering.
// Keep each wait bounded independently of the integration project's whole-flow budget.
const waitFor: typeof waitForDOM = (callback, options) => waitForDOM(callback, { timeout: 5_000, ...options })

function draft(id: string, overrides: Partial<TaskDraft> = {}): TaskDraft {
  return {
    id, draftSource: 'CHAT', taskType: 'IMAGE', parameterSchemaVersion: 1,
    status: 'WAITING_CONFIRMATION', spaceId: '21', productId: null,
    sourceMode: 'TEXT_ONLY', referenceAssetIds: [],
    parameters: { requestText: `创作方案${id}`, mode: 'AGENT_ASSISTED', agentSummary: '场景图', sourceMode: 'TEXT_ONLY', referenceAssetIds: [], aspectRatio: '1:1', count: 1 },
    warnings: [], inputHash: 'a'.repeat(64), pricingVersionId: '51', quotedPoints: 20,
    confirmationToken: `token-${id}`, expiresAt: '2099-01-01T00:00:00Z', createdAt: now,
    ...overrides,
  }
}

function message(id: string, draftId: string): ConversationMessage {
  return { id, conversationId: '1', role: 'ASSISTANT', contentType: 'TEXT',
    content: { text: `方案${draftId}`, taskDraft: { id: draftId } }, replyToMessageId: null,
    runId: null, attachmentAssetIds: [], sequence: Number(id), status: 'COMPLETED', createdAt: now, updatedAt: now }
}

function setup(initialDrafts: TaskDraft[], streaming = false) {
  const waiting = initialDrafts.filter((item) => item.status === 'WAITING_CONFIRMATION')
  const state = {
    drafts: new Map(initialDrafts.map((item) => [item.id, item])),
    messages: initialDrafts.map((item, index) => message(String(index + 1), item.id)),
    writes: [] as string[], reads: [] as string[], failDraftReads: false,
    stream: undefined as ReadableStreamDefaultController<Uint8Array> | undefined,
    streaming, sequence: 0,
    activeConfirmationDraftId: (waiting.length === 1 ? waiting[0].id : null) as string | null,
  }
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    if (init?.method === 'POST') {
      state.writes.push(url)
      throw new Error('No business write should be sent by this scenario')
    }
    if (url.startsWith('/api/v1/task-drafts/')) {
      state.reads.push(url)
      if (state.failDraftReads) return new Response(JSON.stringify({ code: 'INTERNAL_ERROR', message: '暂时不可用', traceId: testTraceId }), { status: 503 })
      return ok(state.drafts.get(url.split('/').at(-1)!))
    }
    if (url.includes('/snapshot?')) return ok({ messages: state.messages, nextBeforeSequence: null, highWatermark: state.sequence, activeRun: state.streaming ? { messageId: '90', runId, status: 'RUNNING' } : null })
    if (url.endsWith('/events')) return new Response(new ReadableStream<Uint8Array>({ start(controller) { state.stream = controller } }), { headers: { 'Content-Type': 'text/event-stream' } })
    if (url.startsWith('/api/v1/conversations?page=')) return ok({ items: [conversation], page: 1, size: 100, total: 1, hasNext: false })
    if (url === '/api/v1/conversations/1') return ok({ ...conversation, activeConfirmationDraftId: state.activeConfirmationDraftId })
    if (url.startsWith('/api/v1/spaces?') || url.startsWith('/api/v1/tasks?conversationId=')) return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
    if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    throw new Error(`Unexpected request: ${url}`)
  }))
  const rendered = renderApp([`${APP_PATHS.chat}?conversation=1`])
  const card = (id: string) => {
    const element = rendered.container.querySelector<HTMLElement>(`[data-chat-draft-id="${id}"]`)
    expect(element).not.toBeNull()
    return within(element!)
  }
  const emit = (event: string, payload: Record<string, unknown>, messageId: string) => {
    state.sequence += 1
    state.stream!.enqueue(new TextEncoder().encode(`id: ${state.sequence}\nevent: ${event}\ndata: ${JSON.stringify({ eventId: `event-${state.sequence}`, sequence: state.sequence, occurredAt: now, conversationId: '1', messageId, taskId: null, payload })}\n\n`))
  }
  return { state, card, emit }
}

beforeEach(async () => {
  await import('../pages/chat/ChatPage')
  vi.unstubAllGlobals()
  localStorage.clear()
  sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('多轮草稿状态与显式确认（只使用本地接口替身，不调用模型）', () => {
  it('A/B独立待确认时不猜修改对象，点旧A不填输入也不修改B，原卡仍可确认', async () => {
    const { state, card } = setup([draft('41'), draft('42')])
    await waitFor(() => expect(card('41').getByRole('button', { name: /确认开始/ })).toBeEnabled())
    await waitFor(() => expect(card('42').getByRole('button', { name: /确认开始/ })).toBeEnabled())
    const input = screen.getByRole('textbox', { name: '消息输入' })
    fireEvent.change(input, { target: { value: '尚未发送的需求' } })
    expect(card('41').getByText(/当前无法确定唯一的待修改方案/)).toBeInTheDocument()
    expect(card('41').getByRole('button', { name: '返回修改' })).toBeDisabled()
    expect(card('42').getByRole('button', { name: '返回修改' })).toBeDisabled()
    await userEvent.click(card('41').getByRole('button', { name: '返回修改' }))
    expect(input).toHaveValue('尚未发送的需求')
    expect(state.drafts.get('42')?.status).toBe('WAITING_CONFIRMATION')
    expect(state.writes).toEqual([])
  })

  it('旧界面仍显示A可修改，但服务器已有独立B时二次核对拒绝填入，不发新Run', async () => {
    const { state, card } = setup([draft('41')])
    await waitFor(() => expect(card('41').getByRole('button', { name: '返回修改' })).toBeEnabled())
    const input = screen.getByRole('textbox', { name: '消息输入' })
    fireEvent.change(input, { target: { value: '保留我的输入' } })
    state.drafts.set('42', draft('42'))
    state.activeConfirmationDraftId = null
    await userEvent.click(card('41').getByRole('button', { name: '返回修改' }))
    await waitFor(() => expect(card('41').getByRole('button', { name: '返回修改' })).toBeDisabled())
    expect(input).toHaveValue('保留我的输入')
    expect(card('41').getByRole('button', { name: /确认开始/ })).toBeEnabled()
    expect(state.writes).toEqual([])
  })

  it('唯一有效草稿核对后仍可返回修改，只填入需求而不自动发送', async () => {
    const { state, card } = setup([draft('41')])
    await waitFor(() => expect(card('41').getByRole('button', { name: '返回修改' })).toBeEnabled())
    await userEvent.click(card('41').getByRole('button', { name: '返回修改' }))
    await waitFor(() => expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue('修改当前待确认方案：\n创作方案41'))
    state.drafts.set('41', draft('41', { parameters: {
      requestText: '修改当前待确认方案：\n创作方案41', mode: 'AGENT_ASSISTED', agentSummary: '场景图',
      sourceMode: 'TEXT_ONLY', referenceAssetIds: [], aspectRatio: '1:1', count: 1,
    } }))
    await userEvent.click(card('41').getByRole('button', { name: '返回修改' }))
    await waitFor(() => expect(state.reads.length).toBeGreaterThanOrEqual(3))
    expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue('修改当前待确认方案：\n创作方案41')
    expect(state.writes).toEqual([])
  })

  it('修订事件后保留旧卡并展示被替代原因，新建的独立方案仍可确认', async () => {
    const { state, card, emit } = setup([draft('41'), draft('43')], true)
    await waitFor(() => expect(card('41').getByRole('button', { name: /确认开始/ })).toBeEnabled())
    await waitFor(() => expect(state.stream).toBeDefined())
    state.drafts.set('41', draft('41', { status: 'ABANDONED', confirmationToken: null }))
    state.drafts.set('42', draft('42', { previousDraftId: '41' }))
    state.messages.push(message('3', '42'))
    await act(async () => { emit('draft.created', { draft: { id: '42' } }, '3') })
    await waitFor(() => expect(card('41').getByText(/此方案已被新方案替代/)).toBeInTheDocument())
    expect(card('41').getByRole('button', { name: /确认开始/ })).toBeDisabled()
    expect(card('41').getByRole('button', { name: '返回修改' })).toBeDisabled()
    expect(card('42').getByRole('button', { name: /确认开始/ })).toBeEnabled()
    expect(card('43').getByRole('button', { name: /确认开始/ })).toBeEnabled()
    state.streaming = false
    await act(async () => { emit('message.completed', { status: 'COMPLETED' }, '3') })
    await waitFor(() => expect(card('43').getByRole('button', { name: /确认开始/ })).toBeEnabled())
    expect(state.writes).toEqual([])
    expect(state.reads.length).toBeLessThan(15)
  })

  it('刷新恢复区分替代、主动取消、过期与已确认，不把历史卡隐藏或复活', async () => {
    const { state, card } = setup([
      draft('41', { status: 'ABANDONED' }), draft('42', { previousDraftId: '41' }),
      draft('43', { status: 'ABANDONED' }), draft('44', { status: 'EXPIRED' }),
      draft('45', { status: 'CONFIRMED' }),
    ])
    await waitFor(() => expect(card('41').getByText(/此方案已被新方案替代/)).toBeInTheDocument())
    expect(card('43').getByText(/此方案已取消/)).toBeInTheDocument()
    expect(card('44').getByText(/此方案报价已过期/)).toBeInTheDocument()
    expect(card('45').getByText(/此方案已确认/)).toBeInTheDocument()
    for (const id of ['41', '43', '44', '45']) expect(card(id).getByRole('button', { name: /确认开始/ })).toBeDisabled()
    expect(card('42').getByRole('button', { name: /确认开始/ })).toBeEnabled()
    expect(screen.queryByText(testTraceId, { exact: false })).not.toBeInTheDocument()
    expect(state.writes).toEqual([])
  })

  it.each(['ABANDONED', 'EXPIRED', 'CONFIRMED'] as const)('点击旧卡发现服务端已 %s 时只查原ID，不发确认POST', async (status) => {
    const { state, card } = setup([draft('41')])
    await waitFor(() => expect(card('41').getByRole('button', { name: /确认开始/ })).toBeEnabled())
    state.drafts.set('41', draft('41', { status }))
    await userEvent.click(card('41').getByRole('button', { name: /确认开始/ }))
    await screen.findByText('此方案已不能确认，请查看卡片中的当前状态。未提交新的付费任务。')
    expect(card('41').getByRole('button', { name: /确认开始/ })).toBeDisabled()
    expect(state.reads.every((path) => path === '/api/v1/task-drafts/41')).toBe(true)
    expect(state.writes).toEqual([])
  })

  it('确认前报价或proof已变化时仅刷新卡片，不能替用户接受新的价格', async () => {
    const { state, card } = setup([draft('41')])
    await waitFor(() => expect(card('41').getByRole('button', { name: /确认开始/ })).toBeEnabled())
    state.drafts.set('41', draft('41', { quotedPoints: 30, pricingVersionId: '52', inputHash: 'b'.repeat(64) }))
    await userEvent.click(card('41').getByRole('button', { name: /确认开始/ }))
    await screen.findByText('方案或报价已更新，请重新核对卡片后再确认。本次未提交付费任务。')
    expect(card('41').getByRole('button', { name: '确认开始 · 30 积分' })).toBeEnabled()
    expect(state.writes).toEqual([])
  })

  it('重新聚焦时核对旧卡；核对失败明确禁用，恢复后再显示真实状态', async () => {
    const { state, card } = setup([draft('41')])
    await waitFor(() => expect(card('41').getByRole('button', { name: /确认开始/ })).toBeEnabled())
    state.failDraftReads = true
    fireEvent.focus(window)
    await waitFor(() => expect(card('41').getByText(/暂时无法核对方案状态/)).toBeInTheDocument())
    expect(card('41').getByRole('button', { name: /确认开始/ })).toBeDisabled()
    expect(screen.queryByText(testTraceId, { exact: false })).not.toBeInTheDocument()
    state.failDraftReads = false
    state.drafts.set('41', draft('41', { status: 'ABANDONED' }))
    fireEvent.focus(window)
    await waitFor(() => expect(card('41').getByText(/此方案已取消/)).toBeInTheDocument())
    expect(state.writes).toEqual([])
  })
})

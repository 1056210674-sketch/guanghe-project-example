import { act, fireEvent, screen, waitFor as waitForDOM, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConversationMessage } from '../api/conversationApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { APP_PATHS } from '../routes/paths'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'
import { renderApp } from './renderApp'

const now = '2026-10-13T06:23:00Z'
const runId = '00000000-0000-4000-8000-000000000123'
const waitFor: typeof waitForDOM = (callback, options) => waitForDOM(callback, { timeout: 5_000, ...options })
const ok = (data: unknown, status = 200) => new Response(
  JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }),
  { status, headers: { 'Content-Type': 'application/json' } },
)

function replyMessage(id: string, role: 'USER' | 'ASSISTANT', text: string): ConversationMessage {
  return {
    id, conversationId: '1', role, contentType: 'TEXT', content: { text },
    replyToMessageId: role === 'ASSISTANT' ? '1' : null, runId,
    attachmentAssetIds: [], sequence: Number(id), status: 'COMPLETED', createdAt: now, updatedAt: now,
  }
}

function setupDelayedRefresh() {
  let releaseSidebar!: () => void
  const sidebarReady = new Promise<void>((resolve) => { releaseSidebar = resolve })
  const current = {
    id: '1', title: '品牌资料对话', selectedSpaceId: null, selectedProductId: null,
    version: 1, lastMessageAt: null, createdAt: now, updatedAt: now,
  }
  const state = {
    messages: [] as ConversationMessage[], posted: false, delayedSidebarReads: 0,
    activeRun: null as { messageId: string; runId: string; status: 'RUNNING' } | null,
    sequence: 0, sends: 0, stream: undefined as ReadableStreamDefaultController<Uint8Array> | undefined,
  }
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    if (url.startsWith('/api/v1/conversations?page=')) {
      if (state.posted) {
        state.delayedSidebarReads += 1
        await sidebarReady
      }
      return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
    }
    if (url === '/api/v1/conversations/1' && init?.method === 'GET') return ok(current)
    if (url.includes('/snapshot?')) return ok({
      messages: state.messages, nextBeforeSequence: null, highWatermark: state.sequence, activeRun: state.activeRun,
    })
    if (url.endsWith('/events')) return new Response(new ReadableStream<Uint8Array>({
      start(controller) { state.stream = controller },
    }), { headers: { 'Content-Type': 'text/event-stream' } })
    if (url === '/api/v1/conversations/1/messages' && init?.method === 'POST') {
      state.sends += 1
      state.posted = true
      const { content } = JSON.parse(String(init.body)) as { content: string }
      state.messages.push(replyMessage('1', 'USER', content))
      state.activeRun = { messageId: '2', runId, status: 'RUNNING' }
      // This is the accepted USER ID, not the ID of the future ASSISTANT reply.
      return ok({ messageId: '1', runId, status: 'QUEUED' }, 202)
    }
    if (url.includes('/message-operations/')) return new Response(
      JSON.stringify({ code: 'NOT_FOUND', message: '尚未受理', traceId: testTraceId }), { status: 404 },
    )
    if (url.startsWith('/api/v1/spaces?') || url.startsWith('/api/v1/tasks?conversationId=')) {
      return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
    }
    if (url === '/api/v1/billing/point-account') return ok({
      userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1,
    })
    throw new Error(`Unexpected Browser API request: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  const rendered = renderApp([`${APP_PATHS.chat}?conversation=1`])
  const emit = async (event: string, payload: Record<string, unknown>) => {
    state.sequence += 1
    const frame = `id: ${state.sequence}\nevent: ${event}\ndata: ${JSON.stringify({
      eventId: `event-${state.sequence}`, sequence: state.sequence, occurredAt: now,
      conversationId: '1', messageId: '2', taskId: null, payload: { messageId: '2', runId, ...payload },
    })}\n\n`
    await act(async () => { state.stream!.enqueue(new TextEncoder().encode(frame)) })
  }
  const complete = async () => {
    state.messages.push(replyMessage('2', 'ASSISTANT', '已保存品牌资料，下面是优化建议。'))
    state.activeRun = null
    await emit('message.completed', { status: 'COMPLETED', businessOutcome: { schemaVersion: 1, status: 'ANSWERED' } })
  }
  return { ...rendered, state, emit, complete, releaseSidebar }
}

beforeEach(async () => {
  await import('../pages/chat/ChatPage')
  vi.unstubAllGlobals()
  localStorage.clear()
  sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('真实 ChatPage 后台侧栏刷新与实时回复', () => {
  it.each([false, true])('侧栏仍在后台刷新时，实时回复保留进度并正常结束（侧栏刷新前已完成：%s）', async (completeBeforeSidebarRefresh) => {
    const { state, emit, complete, releaseSidebar } = setupDelayedRefresh()
    const input = await screen.findByRole('textbox', { name: '消息输入' })
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeDisabled())
    fireEvent.change(input, { target: { value: '请保存我的品牌资料，给出优化方案。' } })
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => {
      expect(state.sends).toBe(1)
      expect(state.delayedSidebarReads).toBeGreaterThan(0)
      expect(state.stream).toBeDefined()
    })
    await emit('message.start', { status: 'RUNNING' })
    await emit('agent.progress', {
      stageCode: 'PLANNING_WORKFLOW', status: 'RUNNING', ordinal: 1,
      elapsedMs: 6_000, current: null, total: null,
    })
    await waitFor(() => expect(screen.getAllByRole('button', { name: /^展开执行动态：/ })).toHaveLength(1))
    expect(screen.getByRole('button', { name: /^展开执行动态：/ })).toHaveAccessibleName(/正在思考与分析/)

    if (completeBeforeSidebarRefresh) await complete()
    await act(async () => { releaseSidebar() })
    // The accepted message clears the composer; sidebar metadata does not own
    // the live reply's progress or terminal state.
    await waitFor(() => expect(input).toHaveValue(''))
    expect(screen.getAllByRole('button', { name: /^展开执行动态：/ })).toHaveLength(1)
    if (!completeBeforeSidebarRefresh) {
      expect(screen.getByRole('button', { name: /^展开执行动态：/ })).toHaveAccessibleName(/正在思考与分析/)
      await complete()
    }

    await waitFor(() => expect(screen.queryByRole('button', { name: '停止当前回复' })).not.toBeInTheDocument())
    expect(await screen.findAllByText('已保存品牌资料，下面是优化建议。')).toHaveLength(1)
    const timelineToggle = screen.getByRole('button', { name: /^展开执行动态：/ })
    expect(timelineToggle).toHaveAccessibleName(/本次回复已完成/)
    fireEvent.click(timelineToggle)
    const timeline = timelineToggle.closest('section')!
    expect(within(timeline).getByText('思考与分析')).toBeInTheDocument()
    expect(within(timeline).queryByText('暂无详细过程记录')).not.toBeInTheDocument()
    expect(screen.queryByText('正在处理你的消息')).not.toBeInTheDocument()
    expect(state.sends).toBe(1)
  })
})

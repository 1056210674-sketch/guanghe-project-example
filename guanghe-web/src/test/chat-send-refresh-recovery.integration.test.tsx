import { act, fireEvent, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConversationMessage } from '../api/conversationApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { APP_PATHS } from '../routes/paths'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'
import { renderApp } from './renderApp'

const now = '2026-10-23T06:00:00Z'
const pendingMessageKey = `zhixiaohe.chat.pending-message.v1.${userFixture.id}.1`
const current = {
  id: '1', title: '发送恢复验证', selectedSpaceId: null, selectedProductId: null,
  version: 1, lastMessageAt: null, createdAt: now, updatedAt: now,
}
const page = (items: unknown[]) => ({ items, page: 1, size: 100, total: items.length, hasNext: false })
const ok = (data: unknown, status = 200) => new Response(
  JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }),
  { status, headers: { 'Content-Type': 'application/json' } },
)
const failed = (status: number, message: string) => new Response(
  JSON.stringify({ code: status === 429 ? 'RATE_LIMITED' : 'INTERNAL_ERROR', message, traceId: testTraceId }),
  { status, headers: { 'Content-Type': 'application/json' } },
)

type RefreshTarget = 'sidebar' | 'detail'
type RefreshOutcome = 'pending' | 429 | 500
type PostedMessage = { content: string; clientMessageId: string; idempotencyKey: string | null }

let cleanupFixture: (() => Promise<void>) | undefined
let unexpectedRequests: string[] = []

function setupRefresh(target: RefreshTarget, outcome: RefreshOutcome, rejectFirstPost = false) {
  let releaseRefresh!: () => void
  const refreshReady = new Promise<void>((resolve) => {
    releaseRefresh = () => { state.refreshReleased = true; resolve() }
  })
  const state = {
    posts: [] as PostedMessage[], messages: [] as ConversationMessage[],
    accepted: 0, auxiliaryRefreshReads: 0, auxiliaryFailures: 0,
    completedSnapshotReads: 0, refreshReleased: false,
  }
  const auxiliaryResponse = async (refreshTarget: RefreshTarget, data: unknown) => {
    if (state.accepted && target === refreshTarget) {
      state.auxiliaryRefreshReads += 1
      if (outcome === 'pending') await refreshReady
      else {
        state.auxiliaryFailures += 1
        return failed(outcome, '辅助会话信息暂时无法刷新')
      }
    }
    return ok(data)
  }

  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (url === '/api/v1/conversations/1/messages' && method === 'POST') {
      const body = JSON.parse(String(init?.body)) as { content: string; clientMessageId: string }
      state.posts.push({ ...body, idempotencyKey: new Headers(init?.headers).get('Idempotency-Key') })
      if (rejectFirstPost && state.posts.length === 1) return failed(429, '本次请求触发限流，请稍后重试')

      state.accepted += 1
      const userId = String(state.messages.length + 1)
      const assistantId = String(state.messages.length + 2)
      const runId = `00000000-0000-4000-8000-${String(state.accepted).padStart(12, '0')}`
      const common = {
        conversationId: '1', contentType: 'TEXT' as const, runId,
        attachmentAssetIds: [], status: 'COMPLETED' as const, createdAt: now, updatedAt: now,
      }
      state.messages.push(
        { ...common, id: userId, role: 'USER', content: { text: body.content }, replyToMessageId: null, sequence: Number(userId) },
        { ...common, id: assistantId, role: 'ASSISTANT', content: { text: `第${state.accepted}条成功回答` }, replyToMessageId: userId, sequence: Number(assistantId) },
      )
      // The run completes before the mutation refreshes its snapshot, while its
      // original acceptance receipt still says QUEUED. No model is called.
      return ok({ messageId: userId, runId, status: 'QUEUED' }, 202)
    }
    if (method === 'GET') {
      if (url.startsWith('/api/v1/conversations?page=')) return auxiliaryResponse('sidebar', page([current]))
      if (url === '/api/v1/conversations/1') return auxiliaryResponse('detail', current)
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
        if (state.accepted) state.completedSnapshotReads += 1
        return ok({ messages: state.messages, nextBeforeSequence: null, highWatermark: state.messages.length, activeRun: null })
      }
      if (url === '/api/v1/conversations/1/events') return new Response('', { headers: { 'Content-Type': 'text/event-stream' } })
      if (url === '/api/v1/projects') return ok([])
      if (url === '/api/v1/billing/notifications/unread-count') return ok({ unread: 0 })
      if (url.startsWith('/api/v1/spaces?') || url.startsWith('/api/v1/tasks?conversationId=')) return ok(page([]))
      if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    }
    // React Query can catch fetch exceptions. Also assert this list in cleanup
    // so an unexpected Browser API call cannot silently pass the regression.
    unexpectedRequests.push(`${method} ${url}`)
    throw new Error(`Unexpected Browser API request: ${method} ${url}`)
  }))

  const rendered = renderApp([`${APP_PATHS.chat}?conversation=1`])
  cleanupFixture = async () => {
    rendered.unmount()
    await act(async () => {
      releaseRefresh()
      await rendered.queryClient.cancelQueries()
    })
    rendered.queryClient.clear()
  }
  const composerControls = async () => {
    const input = await within(rendered.container).findByLabelText('消息输入')
    const form = input.closest('form')!
    const composer = within(form)
    const messages = within(rendered.container.querySelector<HTMLElement>('.chat-conversation__content')!)
    const sendButton = () => composer.getByRole('button', { name: /^发送$/ })
    const send = async (text: string) => {
      fireEvent.change(input, { target: { value: text } })
      await waitFor(() => expect(sendButton()).toBeEnabled(), { container: form })
      fireEvent.click(sendButton())
    }
    return { input, form, composer, messages, sendButton, send }
  }
  return { state, composerControls }
}

beforeEach(async () => {
  await import('../pages/chat/ChatPage')
  vi.unstubAllGlobals()
  localStorage.clear()
  sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
  cleanupFixture = undefined
  unexpectedRequests = []
})

afterEach(async () => {
  await cleanupFixture?.()
  vi.unstubAllGlobals()
  expect(unexpectedRequests).toEqual([])
})

describe('真实 ChatPage 发送恢复与辅助刷新隔离（仅 Browser API stub）', () => {
  it.each<RefreshTarget>(['sidebar', 'detail'])('回答已完成时，%s GET 持续 pending 不应阻止发送下一条', async (target) => {
    const { state, composerControls } = setupRefresh(target, 'pending')
    const ui = await composerControls()
    await ui.send('第一个不同的问题')
    await ui.messages.findByText('第1条成功回答')
    await waitFor(() => expect(state.auxiliaryRefreshReads).toBeGreaterThan(0))
    expect(state.completedSnapshotReads).toBeGreaterThan(0)
    expect(state.posts).toHaveLength(1)
    expect(state.refreshReleased).toBe(false)

    // Do not release the auxiliary GET before asserting recovery. Waiting for
    // it here would hide the bug: the answer is visible but sending is locked.
    await ui.send('第二个不同的问题')
    await ui.messages.findByText('第2条成功回答')
    await waitFor(() => expect(ui.input).toHaveValue(''), { container: ui.form })
    expect(state.refreshReleased).toBe(false)
    expect(state.posts.map((post) => post.content)).toEqual(['第一个不同的问题', '第二个不同的问题'])
    expect(new Set(state.posts.map((post) => post.clientMessageId)).size).toBe(2)
    expect(new Set(state.posts.map((post) => post.idempotencyKey)).size).toBe(2)
    expect(state.posts.every((post) => Boolean(post.clientMessageId && post.idempotencyKey))).toBe(true)
    expect(ui.composer.queryByRole('button', { name: '停止当前回复' })).not.toBeInTheDocument()
    expect(sessionStorage.getItem(pendingMessageKey)).toBeNull()
  })

  it.each([
    ['sidebar', 429], ['sidebar', 500], ['detail', 429], ['detail', 500],
  ] as const)('消息 POST 429 后可继续成功发送；%s GET %s 不会重发旧请求', async (target, outcome) => {
    const { state, composerControls } = setupRefresh(target, outcome, true)
    const ui = await composerControls()
    await ui.send('这条问题被明确限流拒绝')
    await ui.composer.findByText(/本次请求触发限流/)
    await waitFor(() => expect(ui.sendButton()).toBeEnabled(), { container: ui.form })
    expect(state.posts).toHaveLength(1)
    expect(state.accepted).toBe(0)
    expect(ui.input).toHaveValue('这条问题被明确限流拒绝')
    expect(sessionStorage.getItem(pendingMessageKey)).toBeNull()

    await ui.send('限流后提交的不同问题')
    await ui.messages.findByText('第1条成功回答')
    await waitFor(() => expect(ui.input).toHaveValue(''), { container: ui.form })
    await waitFor(() => expect(state.auxiliaryFailures).toBeGreaterThan(0))
    await ui.send('成功回答后继续提问')
    await ui.messages.findByText('第2条成功回答')
    await waitFor(() => expect(ui.input).toHaveValue(''), { container: ui.form })

    expect(state.posts.map((post) => post.content)).toEqual([
      '这条问题被明确限流拒绝', '限流后提交的不同问题', '成功回答后继续提问',
    ])
    expect(new Set(state.posts.map((post) => post.clientMessageId)).size).toBe(3)
    expect(new Set(state.posts.map((post) => post.idempotencyKey)).size).toBe(3)
    expect(state.posts.every((post) => Boolean(post.clientMessageId && post.idempotencyKey))).toBe(true)
    expect(state.accepted).toBe(2)
    expect(ui.composer.queryByText(/本次请求触发限流/)).not.toBeInTheDocument()
    expect(ui.composer.queryByRole('button', { name: '停止当前回复' })).not.toBeInTheDocument()
    expect(sessionStorage.getItem(pendingMessageKey)).toBeNull()
  })
})

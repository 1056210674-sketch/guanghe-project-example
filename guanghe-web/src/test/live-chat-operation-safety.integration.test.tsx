import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { APP_PATHS } from '../routes/paths'
import { renderApp } from './renderApp'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const now = '2026-09-24T08:00:00Z'
const conversation = { id: '1', title: '我的对话', selectedSpaceId: null, selectedProductId: null, version: 1, lastMessageAt: now, createdAt: now, updatedAt: now }
const message = (id: string, content: string) => ({ id, conversationId: '1', role: 'ASSISTANT', contentType: 'TEXT', content: { text: content }, replyToMessageId: null, runId: null, attachmentAssetIds: [], sequence: Number(id), status: 'COMPLETED', createdAt: now, updatedAt: now })
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { headers: { 'Content-Type': 'application/json' } })

function base(url: string, conv = conversation): Response {
  if (url.startsWith('/api/v1/conversations?page=')) return ok({ items: [conv], page: 1, size: 100, total: 1, hasNext: false })
  if (url === '/api/v1/conversations/1') return ok(conv)
  if (url.startsWith('/api/v1/spaces?') || url.startsWith('/api/v1/tasks?conversationId=')) return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
  if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
  throw new Error(`Unexpected request: ${url}`)
}

beforeEach(async () => {
  await import('../pages/chat/ChatPage')
  vi.unstubAllGlobals()
  localStorage.clear()
  sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('真实页面受理与恢复边界（不调用模型）', () => {
  it('快速完成后的权威快照不会被旧 QUEUED 回执覆盖为持续生成', async () => {
    let posted = 0
    let releaseConversationRefresh: (() => void) | undefined
    const runId = '00000000-0000-4000-8000-000000000009'
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.includes('/snapshot?')) return ok({ messages: posted ? [{ ...message('2', '已快速完成的回答'), runId }] : [], nextBeforeSequence: null, highWatermark: posted ? 3 : 0, activeRun: null })
      if (url === '/api/v1/conversations/1/messages' && init?.method === 'POST') {
        posted += 1
        return ok({ messageId: '1', runId, status: 'QUEUED' })
      }
      if (url.endsWith('/events')) return new Response('')
      if (url === '/api/v1/conversations/1' && posted) return new Promise<Response>((resolve) => {
        releaseConversationRefresh = () => resolve(ok(conversation))
      })
      return base(url)
    }))
    renderApp([`${APP_PATHS.chat}?conversation=1`])
    await screen.findByText('你好，我是光禾AI智能体框架。')
    fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '你好' } })
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await screen.findByText('已快速完成的回答')
    fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '下一个问题' } })
    await waitFor(() => expect(releaseConversationRefresh).toBeDefined())
    releaseConversationRefresh?.()
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled())
    expect(screen.queryByRole('button', { name: '停止当前回复' })).not.toBeInTheDocument()
    expect(posted).toBe(1)
  })

  it('刷新只读取最近50条，点击更早历史才分页，不产生消息POST', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      calls.push(`${init?.method ?? 'GET'} ${url}`)
      if (url.includes('/snapshot?')) return ok({ messages: [message('51', '最近的回复')], nextBeforeSequence: '51', highWatermark: 100, activeRun: null })
      if (url.includes('/messages?')) return ok({ items: [message('1', '更早的回复')], nextCursor: null })
      return base(url)
    }))
    renderApp([`${APP_PATHS.chat}?conversation=1`])
    await screen.findByText('最近的回复')
    expect(calls.some((call) => call.includes('/messages?'))).toBe(false)
    await userEvent.click(screen.getByRole('button', { name: '加载更早消息' }))
    await screen.findByText('更早的回复')
    expect(calls.filter((call) => call.startsWith('POST'))).toHaveLength(0)
    expect(calls.some((call) => call.includes('beforeSequence=51'))).toBe(true)
  })

  it('发送成功但回执丢失后重试只查询受理结果，不重复POST', async () => {
    let posted = 0
    let operationReads = 0
    let acceptedClientId: string | undefined
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.includes('/snapshot?')) return ok({ messages: posted ? [message('2', '这里是已经保存的回答')] : [], nextBeforeSequence: null, highWatermark: 0, activeRun: null })
      if (url === '/api/v1/conversations/1/messages' && init?.method === 'POST') {
        posted += 1
        acceptedClientId = JSON.parse(String(init.body)).clientMessageId
        throw new TypeError('network response lost')
      }
      if (url.includes('/message-operations/')) {
        operationReads += 1
        expect(url).toContain(acceptedClientId)
        return ok({ messageId: '1', runId: '00000000-0000-4000-8000-000000000001', status: 'COMPLETED' })
      }
      return base(url)
    }))
    renderApp([`${APP_PATHS.chat}?conversation=1`])
    await screen.findByText('你好，我是光禾AI智能体框架。')
    fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '随便聊聊好吗' } })
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(posted).toBe(1))
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeEnabled())
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(operationReads).toBe(1))
    await screen.findByText('这里是已经保存的回答')
    expect(posted).toBe(1)
    expect(sessionStorage.getItem(`zhixiaohe.chat.pending-message.v1.${userFixture.id}.1`)).toBeNull()
  })

  it('旧澄清卡在后续用户消息出现后不可再次触发新请求', async () => {
    const writes: string[] = []
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (init?.method === 'POST') writes.push(url)
      if (url.includes('/snapshot?')) return ok({ messages: [
        { ...message('1', '请选一种'), content: { interaction: { question: '想做哪一种？', choices: ['图片', '文章'] } } },
        { ...message('2', '我改主意了，只聊聊'), role: 'USER' },
      ], nextBeforeSequence: null, highWatermark: 0, activeRun: null })
      return base(url)
    }))
    renderApp([`${APP_PATHS.chat}?conversation=1`])
    const oldChoice = await screen.findByRole('button', { name: /图片.*这条补充问题已处理/ })
    expect(oldChoice).toBeDisabled()
    await userEvent.click(oldChoice)
    expect(writes).toHaveLength(0)
  })
})

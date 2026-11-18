import { fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ComponentProps } from 'react'
import type { GeoMonitorAction } from '../pages/chat/GeoMonitorAction'
import type { MonitorExecutionIntent } from '../api/monitorExecutionApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { renderApp } from './renderApp'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const intent: MonitorExecutionIntent = { action: 'MONITOR_RUN', grantId: '501', grantVersion: 1, pricingVersionId: '701', quotedPoints: 3,
  range: { topicId: '101', topicVersion: 1, questionIds: ['301', '302', '303'], platformCodes: ['DOUBAO'], sampleMode: 'STANDARD', region: '广州', inputHash: 'a'.repeat(64), inputVersions: { spaceVersion: 1, productVersion: null, competitorSetVersion: null, competitorVersions: [] } } }
// Exercise the real ChatPage/client/message UUID pipeline. The panel/grant protocol
// has separate tests; no fake model completion is treated as business acceptance here.
// This test-only trigger represents recovery of an already-confirmed intent, not
// a new monitoring entry. Production resultOnly recovery is tested separately.
vi.mock('../pages/chat/GeoMonitorAction', () => ({ GeoMonitorAction: (p: ComponentProps<typeof GeoMonitorAction>) => {
  return <div>
    {p.resultOnly && p.conversationId ? <button disabled={p.disabled} onClick={() => void p.onSend(intent, p.conversationId!).catch(() => undefined)}>模拟恢复已确认监测请求</button> : null}
    <output data-testid="monitor-recovery-run">{p.restoredRunId}</output>
  </div>
} }))

const now = '2026-10-07T01:00:00Z'
const runId = 'b0db759f-ee24-49e9-9fbd-46fdd8c54949'
const conversation = (agentKey = 'geo') => ({ id: '801', title: '监测验证', selectedSpaceId: '201', selectedProductId: '202', version: 1, lastMessageAt: now, createdAt: now, updatedAt: now,
  agentIdentity: { installationId: '101', agentKey, agentVersion: '1.0.0', name: agentKey, projectId: null, capabilities: agentKey === 'geo' ? ['chat', 'geo.monitor'] : ['chat', 'text.summarize'] } })
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { headers: { 'Content-Type': 'application/json' } })
function fixture(options: { lost?: boolean; unacceptedFirst?: boolean; summary?: boolean; newConversation?: boolean } = {}) {
  const writes: { url: string; body: Record<string, unknown>; key: string | null }[] = []
  const reads: string[] = []
  const conv = conversation(options.summary ? 'summary' : 'geo')
  let posted = false
  let postCount = 0
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input); const method = init?.method ?? 'GET'
    if (method === 'GET') reads.push(url)
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : {}
    if (method !== 'GET') writes.push({ url, body, key: new Headers(init?.headers).get('Idempotency-Key') })
    if (url === '/api/v1/agents') return ok([{ agentKey: 'geo', version: '1.0.0', name: 'GEO', description: '', capabilities: ['chat', 'geo.monitor'], status: 'PUBLISHED' }])
    if (url === '/api/v1/agent-installations') return ok([{ id: '101', agentKey: 'geo', agentVersion: '1.0.0', name: 'GEO', enabled: true, version: 1, createdAt: now }])
    if (url === '/api/v1/projects') return ok([])
    if (url.startsWith('/api/v1/conversations?')) return ok({ items: options.newConversation && !posted ? [] : [conv], page: 1, size: 100, total: 1, hasNext: false })
    if (url === '/api/v1/conversations' && method === 'POST') return ok(conv)
    if (url === '/api/v1/conversations/801') return ok(conv)
    if (url.includes('/snapshot?')) return ok({ messages: posted ? [
      { id: '901', conversationId: '801', role: 'USER', contentType: 'TEXT', content: { text: '执行监测', executionIntent: intent }, runId, replyToMessageId: null, attachmentAssetIds: [], sequence: 1, status: 'COMPLETED', createdAt: now, updatedAt: now },
      { id: '902', conversationId: '801', role: 'ASSISTANT', contentType: 'TEXT', content: { text: '正在准备方案' }, runId, replyToMessageId: '901', attachmentAssetIds: [], sequence: 2, status: 'COMPLETED', createdAt: now, updatedAt: now },
    ] : [], nextBeforeSequence: null, highWatermark: posted ? 2 : 0, activeRun: null })
    if (url === '/api/v1/conversations/801/messages' && method === 'POST') {
      postCount += 1
      if (options.unacceptedFirst && postCount === 1) throw new TypeError('request not received')
      posted = true; if (options.lost) throw new TypeError('network response lost'); return ok({ messageId: '901', runId, status: 'COMPLETED' })
    }
    if (url.includes('/message-operations/')) return options.unacceptedFirst && !posted
      ? new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'not found', traceId: testTraceId }), { status: 404, headers: { 'Content-Type': 'application/json' } })
      : ok({ messageId: '901', runId, status: 'COMPLETED' })
    if (url.includes('/events')) return new Response('', { headers: { 'Content-Type': 'text/event-stream' } })
    if (url.startsWith('/api/v1/spaces?') || url.startsWith('/api/v1/tasks?') || url.includes('/products?')) return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
    if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    if (url === '/api/v1/notifications/unread-count') return ok({ unreadCount: 0 })
    return new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'not found', traceId: testTraceId }), { status: 404, headers: { 'Content-Type': 'application/json' } })
  }))
  return { writes, reads }
}

beforeEach(async () => {
  await import('../pages/chat/ChatPage'); vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

async function openTaskProgress() {
  fireEvent.click(await screen.findByRole('button', { name: '添加资料' }))
  fireEvent.click(await screen.findByRole('menuitem', { name: /任务进度/ }))
  await screen.findByRole('dialog', { name: '任务进度' })
}

describe('ChatPage 已确认监测意图的恢复接缝', () => {
  it('沿同一个消息客户端传 frozen intent，已有输入不被换成假聊天，不混入侧栏商品', async () => {
    const api = fixture(); renderApp(['/app/chat?conversation=801'])
    const input = await screen.findByRole('textbox', { name: '消息输入' })
    fireEvent.change(input, { target: { value: '这是还没发送的普通问题' } })
    expect(screen.queryByRole('button', { name: '模拟恢复已确认监测请求' })).not.toBeInTheDocument()
    await openTaskProgress()
    fireEvent.click(await screen.findByRole('button', { name: '模拟恢复已确认监测请求' }))
    await waitFor(() => expect(api.writes.filter((item) => item.url.endsWith('/messages'))).toHaveLength(1))
    const sent = api.writes.find((item) => item.url.endsWith('/messages'))!
    expect(sent.body).toEqual({ content: '执行本次已选专题的单次监测。', executionIntent: intent, contextSelectionMode: 'INHERIT', attachments: [], clientMessageId: expect.any(String) })
    expect(sent.key).toMatch(/^[0-9a-f-]{36}$/)
    expect(input).toHaveValue('这是还没发送的普通问题')
    await waitFor(() => expect(screen.getByTestId('monitor-recovery-run')).toHaveTextContent(runId))
  })
  it('回执丢失后复用原 USER operation，只读核对结果不重复消息 POST', async () => {
    const api = fixture({ lost: true }); renderApp(['/app/chat?conversation=801'])
    await openTaskProgress()
    fireEvent.click(await screen.findByRole('button', { name: '模拟恢复已确认监测请求' }))
    await waitFor(() => expect(api.writes.filter((item) => item.url.endsWith('/messages'))).toHaveLength(1))
    await waitFor(() => expect(screen.getByRole('button', { name: '模拟恢复已确认监测请求' })).toBeEnabled())
    const clientId = api.writes[0].body.clientMessageId
    fireEvent.click(screen.getByRole('button', { name: '模拟恢复已确认监测请求' }))
    await waitFor(() => expect(api.reads.some((url) => url.endsWith(`/message-operations/${String(clientId)}`))).toBe(true))
    expect(api.writes.filter((item) => item.url.endsWith('/messages'))).toHaveLength(1)
  })
  it('没有项目的新用户不显示直接监测或创建会话按钮，也不自动发起写入', async () => {
    const api = fixture({ newConversation: true }); renderApp(['/app?installation=101'])
    await screen.findByRole('textbox', { name: '消息输入' })
    await waitFor(() => expect(api.reads).toEqual(expect.arrayContaining(['/api/v1/agent-installations', '/api/v1/projects'])))
    expect(screen.queryByRole('button', { name: '监测一次' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '预览创建会话', hidden: true })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '模拟恢复已确认监测请求', hidden: true })).not.toBeInTheDocument()
    expect(api.writes).toHaveLength(0)
  })
  it('未知请求经 operation 404 后仅用同一个 clientMessageId/idempotencyKey 重放同一 payload', async () => {
    const api = fixture({ unacceptedFirst: true }); renderApp(['/app/chat?conversation=801'])
    await openTaskProgress()
    fireEvent.click(await screen.findByRole('button', { name: '模拟恢复已确认监测请求' }))
    await waitFor(() => expect(api.writes.filter((item) => item.url.endsWith('/messages'))).toHaveLength(1))
    await waitFor(() => expect(screen.getByRole('button', { name: '模拟恢复已确认监测请求' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '模拟恢复已确认监测请求' }))
    await waitFor(() => expect(api.writes.filter((item) => item.url.endsWith('/messages'))).toHaveLength(2))
    const messages = api.writes.filter((item) => item.url.endsWith('/messages'))
    expect(messages[1]).toEqual(messages[0])
    expect(api.reads.some((url) => url.includes('/message-operations/'))).toBe(true)
  })
  it('摘要身份没有监测动作入口，普通消息不携带执行意图', async () => {
    const api = fixture({ summary: true }); renderApp(['/app/chat?conversation=801'])
    await screen.findByRole('textbox', { name: '消息输入' })
    await waitFor(() => expect(api.reads).toContain('/api/v1/conversations/801'))
    expect(screen.queryByRole('button', { name: '模拟恢复已确认监测请求', hidden: true })).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '帮我总结一下' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(api.writes.some((item) => item.url.endsWith('/messages'))).toBe(true))
    expect(api.writes.find((item) => item.url.endsWith('/messages'))!.body).not.toHaveProperty('executionIntent')
  })
})

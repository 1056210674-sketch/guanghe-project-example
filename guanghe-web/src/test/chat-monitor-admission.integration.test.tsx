import { act, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { renderApp } from './renderApp'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const now = '2026-10-07T01:00:00Z'
const runId = 'b0db759f-ee24-49e9-9fbd-46fdd8c54949'
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { headers: { 'Content-Type': 'application/json' } })

beforeEach(async () => {
  await import('../pages/chat/ChatPage')
  vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

it('真实 ChatPage/Workspace 恢复受理事实后隐藏迟到的待确认卡，只加载真实排队任务而不再次付费确认', async () => {
  const writes: string[] = []; const reads: string[] = []
  let completeAdmission!: (value: Response) => void
  const admission = new Promise<Response>((resolve) => { completeAdmission = resolve })
  const conversation = { id: '801', title: '监测受理恢复', version: 1, selectedSpaceId: null, selectedProductId: null, activeConfirmationDraftId: '601', lastMessageAt: now, createdAt: now, updatedAt: now,
    agentIdentity: { installationId: '101', agentKey: 'geo', agentVersion: '1.0.0', name: 'GEO', projectId: null, capabilities: ['chat', 'geo.monitor'] } }
  const message = { conversationId: '801', runId, attachmentAssetIds: [], status: 'COMPLETED', createdAt: now, updatedAt: now }
  const taskDraft = { id: '601', draftSource: 'CHAT', taskType: 'GEO', parameterSchemaVersion: 1, status: 'WAITING_CONFIRMATION', spaceId: '201', productId: null,
    parameters: { mode: 'MONITOR_RUN', requestText: '监测一次', agentSummary: '本次监测', topicId: '301', topic: '品牌发现', audience: '顾客', region: '广州', questionIds: ['301', '302', '303'], platformCodes: ['DOUBAO'], sampleMode: 'STANDARD' },
    warnings: [], inputHash: 'a'.repeat(64), pricingVersionId: '701', quotedPoints: 3, confirmationToken: 'test-opaque-proof', expiresAt: '2099-01-01T00:00:00Z', createdAt: now }
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input); const method = init?.method ?? 'GET'
    if (method === 'GET') reads.push(url); else writes.push(url)
    if (url === '/api/v1/agents' || url === '/api/v1/agent-installations' || url === '/api/v1/projects') return ok([])
    if (url.startsWith('/api/v1/conversations?')) return ok({ items: [conversation], page: 1, size: 100, total: 1, hasNext: false })
    if (url === '/api/v1/conversations/801') return ok(conversation)
    if (url.includes('/snapshot?')) return ok({ messages: [
      { ...message, id: '901', role: 'USER', contentType: 'TEXT', content: { text: '执行本次监测', executionIntent: { action: 'MONITOR_RUN' } }, replyToMessageId: null, sequence: 1 },
      { ...message, id: '902', role: 'ASSISTANT', contentType: 'INTERACTION', content: { text: '已准备本次方案', taskDraft: { id: '601' } }, replyToMessageId: '901', sequence: 2 },
    ], highWatermark: 2, nextBeforeSequence: null, activeRun: null })
    if (url.endsWith(`/runs/${runId}/geo-monitor-admission`)) return admission
    if (url === '/api/v1/task-drafts/601') return ok(taskDraft)
    if (url === '/api/v1/tasks/701') return ok({ id: '701', taskType: 'GEO', status: 'QUEUED', stageCode: 'QUEUED', stageLabel: '任务已排队', spaceId: '201', productId: null, quotedPoints: 3, settledPoints: 0, frozenPoints: 3, progress: null, result: {}, errorCode: null, createdAt: now, updatedAt: now })
    if (url.includes('/events')) return new Response('', { headers: { 'Content-Type': 'text/event-stream' } })
    if (url.startsWith('/api/v1/spaces?') || url.startsWith('/api/v1/tasks?')) return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
    if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 97, frozenPoints: 3, version: 1 })
    if (url === '/api/v1/notifications/unread-count') return ok({ unreadCount: 0 })
    return new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'not found', traceId: testTraceId }), { status: 404, headers: { 'Content-Type': 'application/json' } })
  }))
  renderApp(['/app/chat?conversation=801'])
  await screen.findByRole('button', { name: '确认开始 · 3 积分' }, { timeout: 4000 })
  await act(async () => { completeAdmission(ok({ runId, status: 'ADMITTED', reasonCode: null, draftId: '601', taskId: '701', executionSource: 'EXECUTION_PREAUTH', quotedPoints: 3, admittedAt: now })) })
  await screen.findByText('监测已受理')
  await waitFor(() => expect(screen.queryByRole('button', { name: '确认开始 · 3 积分' })).not.toBeInTheDocument())
  await waitFor(() => expect(reads).toContain('/api/v1/tasks/701'))
  expect(await screen.findAllByText('任务已排队')).not.toHaveLength(0)
  expect(screen.queryByText('监测已完成')).not.toBeInTheDocument()
  expect(screen.queryByText('监测已成功')).not.toBeInTheDocument()
  expect(writes).toEqual([])
})

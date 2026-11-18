import { fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Conversation, ConversationMessage } from '../api/conversationApi'
import type { AgentInstallation, Project, AgentDefinition } from '../api/agentPlatformApi'
import type { ArticleAuthorization, ArticleFullPreparation } from '../api/articleFullApi'
import type { GeoReport } from '../api/geoApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { renderApp } from './renderApp'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'
import { geoReportDto } from './fixtures/geo'

const now = '2026-10-07T01:00:00Z'
const runId = 'b0db759f-ee24-49e9-9fbd-46fdd8c54949'
const text = '给我的小行星咖啡店写个介绍，别编优惠'
const project: Project = { id: '201', name: '小行星咖啡店', status: 'ACTIVE', version: 1, createdAt: now, updatedAt: now }
const installation: AgentInstallation = { id: '101', agentKey: 'geo', agentVersion: '1.0.0', name: 'GEO', enabled: true, version: 1, createdAt: now }
const agent: AgentDefinition = { agentKey: 'geo', version: '1.0.0', name: 'GEO', description: '分析与内容准备', capabilities: ['chat', 'article.generate'], status: 'PUBLISHED' }
const conversation: Conversation = { id: '801', title: '小行星咖啡', selectedSpaceId: '201', selectedProductId: null, activeConfirmationDraftId: null, version: 1, lastMessageAt: now, createdAt: now, updatedAt: now, agentIdentity: { installationId: '101', agentKey: 'geo', agentVersion: '1.0.0', name: 'GEO', projectId: '201', capabilities: agent.capabilities } }
const message = (role: ConversationMessage['role'], needsInput: boolean): ConversationMessage => ({ id: role === 'USER' ? '901' : '902', conversationId: '801', role, contentType: 'TEXT', content: { text: role === 'USER' ? text : '本次需求已核对', businessOutcome: { schemaVersion: 1, status: needsInput ? 'NEEDS_INPUT' : 'ANSWERED' } }, runId, replyToMessageId: null, attachmentAssetIds: [], sequence: role === 'USER' ? 1 : 2, status: 'COMPLETED', createdAt: now, updatedAt: now })
const preparation: ArticleFullPreparation = { id: '601', templateCode: 'ARTICLE_FULL_V1', status: 'RUNNING', conversationId: '801', articleId: null, articleVersion: null, quotedPoints: 3, settledPoints: 0, releasedPoints: 0, deadlineAt: '2099-01-01T00:00:00Z', errorCode: null, stages: [], publicationRequiresConfirmation: true, knowledgeSourcesCurrent: true }
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { headers: { 'Content-Type': 'application/json' } })
function fixture(needsInput = false, options: { projectOnly?: boolean; report?: GeoReport } = {}) {
  const writes: { url: string; body: Record<string, unknown>; key: string | null }[] = []
  let posted = false
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input); const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : {}
    if (method !== 'GET') writes.push({ url, body, key: new Headers(init?.headers).get('Idempotency-Key') })
    if (url === '/api/v1/agents') return ok([agent])
    if (url === '/api/v1/agent-installations') return ok([installation])
    if (url === '/api/v1/projects') return ok([project])
    if (url.startsWith('/api/v1/conversations?')) return ok({ items: [conversation], page: 1, size: 100, total: 1, hasNext: false })
    if (url === '/api/v1/conversations' && method === 'POST') return ok(conversation)
    if (url === '/api/v1/conversations/801') return ok(conversation)
    if (options.report && url === `/api/v1/geo/reports/${options.report.id}`) return ok(options.report)
    if (options.report && url === `/api/v1/geo/topics/${options.report.topicId}`) return ok(options.report.topic)
    if (url.includes('/snapshot?')) return ok({ messages: posted ? [message('USER', needsInput), message('ASSISTANT', needsInput)] : [], nextBeforeSequence: null, highWatermark: posted ? 2 : 0, activeRun: null })
    if (url === '/api/v1/conversations/801/messages' && method === 'POST') { posted = true; return ok({ messageId: '901', runId, status: 'COMPLETED' }) }
    if (url.includes('/message-operations/')) return ok({ messageId: '901', runId, status: 'COMPLETED' })
    if (url.includes('/events')) return new Response('', { headers: { 'Content-Type': 'text/event-stream' } })
    if (url.startsWith('/api/v1/spaces?')) return ok({ items: options.projectOnly ? [] : [{ id: '201', name: project.name, version: 1, status: 'ACTIVE' }], page: 1, size: 100, total: options.projectOnly ? 0 : 1, hasNext: false })
    if (url.startsWith('/api/v1/tasks?') || url.includes('/products?')) return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
    if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    if (url === '/api/v1/notifications/unread-count') return ok({ unreadCount: 0 })
    if (url.endsWith('/article-authorizations')) return ok(method === 'GET' ? [] : { id: '501', action: 'ARTICLE_FULL_V1', version: 1, status: 'ACTIVE', spaceId: '201', productId: null, maxPoints: 20, expiresAt: body.expiresAt as string, createdAt: now } satisfies ArticleAuthorization)
    if (url.endsWith('/article-full-preparations') || url === '/api/v1/article-full-preparations/601') return ok(preparation)
    return new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'not found', traceId: testTraceId }), { status: 404, headers: { 'Content-Type': 'application/json' } })
  }))
  return writes
}
beforeEach(async () => {
  await import('../pages/chat/ChatPage'); vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})
async function sendFull() {
  fireEvent.click(await screen.findByRole('button', { name: /^file-text 完整文章$/ }))
  fireEvent.change(await screen.findByRole('spinbutton', { name: '全文创作积分上限' }), { target: { value: '20' } })
  fireEvent.click(screen.getByRole('button', { name: '使用全文模式' }))
  await waitFor(() => expect(screen.getByRole('button', { name: /完整文章 · ≤20积分/ })).toBeInTheDocument())
  fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: text } })
  fireEvent.click(screen.getByRole('button', { name: '发送' }))
}
describe('用户发送一次全文需求的完整页面链路', () => {
  it('无需第二次准备点击，原 USER 完成后自动授权并启动全文，未产生旧标题草稿或投稿', async () => {
    const writes = fixture(); renderApp(['/app?conversation=801']); await sendFull()
    await screen.findByText('正在准备全文')
    expect(writes.map((item) => item.url)).toEqual(['/api/v1/conversations/801/messages', '/api/v1/conversations/801/article-authorizations', '/api/v1/conversations/801/article-full-preparations'])
    expect(writes[0].body).toMatchObject({ content: text, articlePreparationMode: 'ARTICLE_FULL_V1', contextSelectionMode: 'REPLACE', selectedSpaceId: '201', attachments: [] })
    expect(writes[0].body).not.toHaveProperty('executionIntent')
    expect(writes[2].body).toMatchObject({ sourceRunId: runId, grantId: '501', grantVersion: 1, spaceId: '201', requestText: text })
    expect(writes[2].key).toMatch(/^[0-9a-f-]{36}$/)
    expect(writes.some((item) => item.url.includes('task-drafts') || item.url.includes('media/quotes'))).toBe(false)
  })
  it('技术完成但业务要求补充资料，只返回聊天，不支出或开始全文', async () => {
    const writes = fixture(true); renderApp(['/app?conversation=801']); await sendFull()
    await screen.findByText(/智能体需要补充资料/)
    expect(writes.map((item) => item.url)).toEqual(['/api/v1/conversations/801/messages'])
    expect(screen.getByRole('textbox', { name: '消息输入' })).toBeEnabled()
  })
  it('只有平台项目而没有旧经营空间或商品时，报告全文入口仍冻结原 USER 的项目及精确报告，不经过旧分阶段确认', async () => {
    const savedReport: GeoReport = {
      ...geoReportDto('geo-synthesis-v1'),
      id: '101250201615859712', topicId: '101250142945935360',
      topic: { id: '101250142945935360', spaceId: project.id, productId: null, name: '咖啡店品牌发现', region: '广州', version: 2 },
    }
    const writes = fixture(false, { projectOnly: true, report: savedReport })
    renderApp([`/app?installation=${installation.id}&project=${project.id}&geoReportId=${savedReport.id}`])
    await screen.findByText('本次报告来源：咖啡店品牌发现')
    expect(writes).toEqual([])
    await sendFull()
    await screen.findByText('正在准备全文')
    expect(writes.map((item) => item.url)).toEqual([
      '/api/v1/conversations',
      '/api/v1/conversations/801/messages',
      '/api/v1/conversations/801/article-authorizations',
      '/api/v1/conversations/801/article-full-preparations',
    ])
    expect(writes[0].body).toEqual({ installationId: installation.id, projectId: project.id })
    expect(writes[1].body).toMatchObject({
      content: text, articlePreparationMode: 'ARTICLE_FULL_V1', geoReportId: savedReport.id,
      contextSelectionMode: 'REPLACE', selectedSpaceId: project.id, attachments: [],
    })
    expect(writes[1].body.selectedProductId ?? null).toBeNull()
    expect(writes[1].body).not.toHaveProperty('executionIntent')
    expect(writes[3].body).toMatchObject({
      sourceRunId: runId, grantId: '501', grantVersion: 1, spaceId: project.id,
      requestText: text, geoReportId: savedReport.id,
    })
    expect(writes[3].body.productId ?? null).toBeNull()
    expect(writes[3].key).toMatch(/^[0-9a-f-]{36}$/)
    expect(writes.some((item) => /task-drafts|confirm|adopt|submit|media\/quotes/.test(item.url))).toBe(false)
    expect(screen.queryByRole('button', { name: /确认标题|确认大纲|确认正文|确认投稿/ })).not.toBeInTheDocument()
  }, 15000)
})

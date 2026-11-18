import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Conversation, ConversationMessage, ConversationSnapshot } from '../api/conversationApi'
import type { AgentInstallation, Project } from '../api/agentPlatformApi'
import type { ArticleAuthorization, ArticleFullPreparation } from '../api/articleFullApi'
import { captureAuthSession, storeAuthToken } from '../auth/tokenStorage'
import { invokeFullArticle } from '../pages/chat/fullArticleInvocation'
import { readFullPreparationRecovery, storeFullPreparationRecovery, type FullPreparationAttempt } from '../features/articles/live/fullPreparationRecovery'
import { testAccessToken, testExpiresAt, testTraceId } from './fixtures/auth'

const run = '00000000-0000-4000-8000-000000000301'
const otherRun = '00000000-0000-4000-8000-000000000302'
const key = '00000000-0000-4000-8000-000000000999'
const now = '2026-10-07T01:00:00Z'
const project: Project = { id: '201', name: '小行星咖啡店', status: 'ACTIVE', version: 1, createdAt: now, updatedAt: now }
const installation: AgentInstallation = { id: '101', agentKey: 'geo', agentVersion: '1.0.0', name: 'GEO', enabled: true, version: 1, createdAt: now }
const conversation: Conversation = { id: '301', title: '小行星咖啡', selectedSpaceId: null, selectedProductId: null, activeConfirmationDraftId: null, version: 1, lastMessageAt: now, createdAt: now, updatedAt: now, agentIdentity: { installationId: '101', agentKey: 'geo', agentVersion: '1.0.0', name: 'GEO', projectId: '201', capabilities: ['chat', 'article.generate'] } }
const message = (role: ConversationMessage['role'], status: ConversationMessage['status'] = 'COMPLETED', sourceRun = run): ConversationMessage => ({ id: role === 'USER' ? '401' : '402', conversationId: '301', role, contentType: 'TEXT', content: { text: role === 'USER' ? '给我的小行星咖啡店写一篇介绍，别编优惠' : '资料已经核对，正文稍后准备', businessOutcome: { schemaVersion: 1, status: 'ANSWERED' } }, runId: sourceRun, replyToMessageId: null, attachmentAssetIds: [], sequence: role === 'USER' ? 1 : 2, status, createdAt: now, updatedAt: now })
const snapshot = (status: ConversationMessage['status'] = 'COMPLETED'): ConversationSnapshot => ({ messages: [message('USER'), message('ASSISTANT', status), { ...message('USER', 'COMPLETED', otherRun), id: '403', sequence: 3, content: { text: '现在聊聊天气吧' } }], activeRun: null, nextBeforeSequence: null, highWatermark: 3 })
const grant: ArticleAuthorization = { id: '501', action: 'ARTICLE_FULL_V1', version: 1, status: 'ACTIVE', spaceId: '201', productId: null, maxPoints: 20, expiresAt: '2099-01-01T00:00:00Z', createdAt: now }
const attempt = (): FullPreparationAttempt => ({ key, sourceRunId: run, authorization: { spaceId: '201', productId: null, maxPoints: 20, expiresAt: grant.expiresAt } })
const preparation: ArticleFullPreparation = { id: '601', templateCode: 'ARTICLE_FULL_V1', status: 'RUNNING', conversationId: '301', articleId: null, articleVersion: null, quotedPoints: 3, settledPoints: 0, releasedPoints: 0, deadlineAt: grant.expiresAt, errorCode: null, stages: [], publicationRequiresConfirmation: true, knowledgeSourcesCurrent: true }
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data }), { status: 200, headers: { 'Content-Type': 'application/json' } })
function stub(options: { snapshots?: ConversationSnapshot[]; disabled?: boolean; archived?: boolean; wrongProject?: boolean; unknownStart?: boolean; grants?: ArticleAuthorization[]; older?: ConversationMessage[] } = {}) {
  const snapshots = [...(options.snapshots ?? [snapshot()])]
  const writes: { url: string; method: string; body: Record<string, unknown>; key: string | null }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'
    if (method !== 'GET') writes.push({ url, method, body: JSON.parse(String(init?.body)) as Record<string, unknown>, key: new Headers(init?.headers).get('Idempotency-Key') })
    if (url.includes('/snapshot?')) return ok(snapshots.length > 1 ? snapshots.shift() : snapshots[0])
    if (url.includes('/messages?')) return ok({ items: options.older ?? [], nextCursor: null })
    if (url === '/api/v1/conversations/301') return ok({ ...conversation, agentIdentity: { ...conversation.agentIdentity, projectId: options.wrongProject ? '202' : '201' } })
    if (url === '/api/v1/agent-installations') return ok([{ ...installation, enabled: !options.disabled }])
    if (url === '/api/v1/projects') return ok([{ ...project, status: options.archived ? 'ARCHIVED' : 'ACTIVE' }])
    if (url.endsWith('/article-authorizations')) return ok(method === 'GET' ? options.grants ?? [] : grant)
    if (url.endsWith('/article-full-preparations')) return options.unknownStart ? new Response(JSON.stringify({ code: 'SERVICE_UNAVAILABLE', message: '暂不可用', traceId: testTraceId }), { status: 503, headers: { 'Content-Type': 'application/json' } }) : ok(preparation)
    throw new Error(`Unexpected request ${url}`)
  }))
  return writes
}
const invoke = (value = attempt(), extra: { current?: () => boolean; wait?: () => Promise<void> } = {}) => invokeFullArticle({ ownerId: '1', conversationId: '301', session: captureAuthSession(), attempt: value, current: extra.current ?? (() => true), wait: extra.wait ?? (() => Promise.resolve()), onPending: () => undefined })
beforeEach(() => { vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear(); storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt }) })
describe('完整文章从原 USER 请求自动继续的真实 HTTP 链路', () => {
  it('先等原 Run 完成，再受理同一篇文章；不采用后来的聊天或模型文字，不投稿', async () => {
    const writes = stub({ snapshots: [snapshot('STREAMING'), snapshot()] })
    const wait = vi.fn(async () => { expect(writes).toHaveLength(0) })
    expect((await invoke(attempt(), { wait })).id).toBe('601')
    expect(wait).toHaveBeenCalledOnce()
    expect(writes.map((item) => item.url)).toEqual(['/api/v1/conversations/301/article-authorizations', '/api/v1/conversations/301/article-full-preparations'])
    expect(writes[1].body).toEqual({ sourceRunId: run, grantId: '501', grantVersion: 1, spaceId: '201', productId: null, requestText: '给我的小行星咖啡店写一篇介绍，别编优惠' })
    expect(writes[1].key).toBe(key)
  })
  it.each(['FAILED', 'STOPPED'] as const)('原 Run %s 不创建授权或文章', async (status) => { const writes = stub({ snapshots: [snapshot(status)] }); await expect(invoke()).rejects.toThrow('未完成或已停止'); expect(writes).toHaveLength(0) })
  it('业务仍需补充资料时不拿技术 COMPLETED 当创作许可', async () => {
    const source = snapshot(); source.messages[1].content.businessOutcome = { schemaVersion: 1, status: 'NEEDS_INPUT' }
    const writes = stub({ snapshots: [source] }); await expect(invoke()).rejects.toThrow('需要补充资料'); expect(writes).toHaveLength(0)
  })
  it.each([{ disabled: true }, { archived: true }, { wrongProject: true }])('停用/归档/项目错配不启动文章：%j', async (options) => { const writes = stub(options); await expect(invoke()).rejects.toThrow('所属项目已不可执行'); expect(writes).toHaveLength(0) })
  it('用户切换任务时停止后续写入', async () => {
    const writes = stub({ snapshots: [snapshot('STREAMING')] }); let current = true
    await expect(invoke(attempt(), { current: () => current, wait: async () => { current = false } })).rejects.toThrow('状态已改变')
    expect(writes).toHaveLength(0)
  })
  it('原始请求在历史页时只找指定 Run，且保留业务补充资料状态', async () => {
    const source = snapshot(); source.messages = [source.messages[2]]; source.nextBeforeSequence = '3'
    const original = [message('USER'), { ...message('ASSISTANT'), content: { text: '请告诉我所在地', businessOutcome: { schemaVersion: 1 as const, status: 'NEEDS_INPUT' as const } } }]
    const writes = stub({ snapshots: [source], older: original }); await expect(invoke()).rejects.toThrow('需要补充资料'); expect(writes).toHaveLength(0)
  })
  it('受理结果未知保留同一授权和幂等请求；恢复不缓存或替换用户正文', async () => {
    const writes = stub({ unknownStart: true }); await expect(invoke()).rejects.toMatchObject({ status: 503 })
    const saved = readFullPreparationRecovery('1', '301', captureAuthSession().revision)!
    expect(saved.attempt?.request?.grantId).toBe('501')
    expect(sessionStorage.getItem('zhixiaohe.article-full.v1.1.301')).not.toContain('小行星咖啡')
    const retryWrites = stub({ grants: [grant] }); await invoke(saved.attempt!)
    expect(retryWrites).toHaveLength(1); expect(retryWrites[0].key).toBe(writes[1].key)
    expect(readFullPreparationRecovery('2', '301', captureAuthSession().revision)).toBeUndefined()
  })
  it('报告创作及回执未知恢复沿用本次冻结报告，不因当前聊天改变丢失来源', async () => {
    const request = { ...attempt(), geoReportId: '701' }
    const writes = stub({ unknownStart: true }); await expect(invoke(request)).rejects.toMatchObject({ status: 503 })
    expect(writes[1].body.geoReportId).toBe('701')
    const saved = readFullPreparationRecovery('1', '301', captureAuthSession().revision)!
    expect(saved.attempt?.geoReportId).toBe('701'); expect(saved.attempt?.request?.geoReportId).toBe('701')
    const retry = stub(); await invoke(saved.attempt!)
    expect(retry[0].body.geoReportId).toBe('701'); expect(retry[0].key).toBe(key)
  })
  it('未找到原请求或一直未完成时不建立新文章，保留可恢复的等待错误', async () => {
    const writes = stub({ snapshots: [snapshot('STREAMING')] }); await expect(invoke()).rejects.toMatchObject({ code: 'ARTICLE_SOURCE_RUN_PENDING' }); expect(writes).toHaveLength(0)
  })
  it('恢复信息不能注入正文、跨项目 grant 或替换冻结报告', () => {
    storeFullPreparationRecovery('1', '301', { revision: captureAuthSession().revision, attempt: { ...attempt(), request: { sourceRunId: run, grantId: '501', grantVersion: 1, spaceId: '202' } } })
    expect(readFullPreparationRecovery('1', '301', captureAuthSession().revision)?.attempt?.request).toBeUndefined()
    storeFullPreparationRecovery('1', '301', { revision: captureAuthSession().revision, attempt: { ...attempt(), geoReportId: '701', request: { sourceRunId: run, grantId: '501', grantVersion: 1, spaceId: '201', productId: null, geoReportId: '702' } } })
    const frozen = readFullPreparationRecovery('1', '301', captureAuthSession().revision)?.attempt
    expect(frozen?.geoReportId).toBe('701'); expect(frozen?.request).toBeUndefined()
    storeFullPreparationRecovery('1', '301', { revision: captureAuthSession().revision, attempt: { ...attempt(), geoReportId: 'invalid' } })
    expect(readFullPreparationRecovery('1', '301', captureAuthSession().revision)).toBeUndefined()
  })
})

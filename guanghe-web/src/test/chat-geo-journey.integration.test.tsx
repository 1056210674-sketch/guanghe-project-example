import { act, configure, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation, useNavigate } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConversationMessageGeoContext, TaskDraft } from '../api/conversationApi'
import type { Article } from '../api/articleApi'
import type { components } from '../api/generated/browser-api'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { getArticleTaskRecoveryStorageKey } from '../features/articles/live/articleRecovery'
import { renderApp } from './renderApp'
import { geoPlatformFixtures, geoReportDto, geoRunDto } from './fixtures/geo'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const now = '2026-09-29T08:00:00Z'
configure({ asyncUtilTimeout: 5000 })
const report = { ...geoReportDto('geo-synthesis-v1', { recommendations: [] }), topic: { ...geoReportDto('geo-synthesis-v1').topic, productId: '31' } }
const context: ConversationMessageGeoContext = {
  status: 'READY', selectionSource: 'AUTO_SINGLE_TOPIC', topicsTruncated: false, topicChoices: [],
  report: { reportId: report.id, topicId: report.topicId, spaceId: '4001', productId: '31', title: '本轮实际使用的报告', generatedAt: now, canCreateArticle: true, articleUnavailableReason: null },
}
const articleParameters: components['schemas']['ArticleTaskParameters'] = { mode: 'TITLE', articleId: '9101', articleVersion: 1, brief: '依据已保存的真实报告准备标题', selectedTitle: null,
  confirmedOutline: [], confirmedPlan: null, rewriteInstruction: null, rewriteBlockIds: [], targetLength: 'MEDIUM', tone: '客观', targetPlatform: null, sourceAssetIds: [], geoReportId: report.id }
const draft: TaskDraft = {
  id: '9201', draftSource: 'FORM', taskType: 'ARTICLE', parameterSchemaVersion: 2,
  status: 'WAITING_CONFIRMATION', spaceId: '4001', productId: '31', sourceMode: null, referenceAssetIds: [],
  parameters: articleParameters,
  warnings: [], inputHash: 'a'.repeat(64), pricingVersionId: '51', quotedPoints: 12, confirmationToken: 'test-only-token', expiresAt: '2099-01-01T00:00:00Z', createdAt: now,
}
const article: Article = { id: '9101', spaceId: '4001', productId: '31', title: '', status: 'DRAFT', version: 1, savedToAssets: false, savedToAssetsAt: null, savedArticleVersion: null,
  createdAt: now, updatedAt: now, bodyHtml: '', bodyText: '', outline: [], assets: [], workflowStage: 'TITLE_INPUT', titleCandidateBatch: null, confirmedTitle: null, creativePlan: null }
const workflow = { workflowId: '9201', reportId: report.id, articleId: article.id, draftId: draft.id, conversationId: '1', reused: false, suggestionId: null, draft }
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[]) => ok({ items, page: 1, size: 100, total: items.length, hasNext: false })

function Probe() {
  const location = useLocation()
  const navigate = useNavigate()
  return <><output data-testid="route">{location.pathname}{location.search}</output>
    <button onClick={() => navigate('/app/chat')}>切到新聊天</button>
    <button onClick={() => navigate('/app/chat?conversation=1')}>回到原聊天</button></>
}

function stub(options: {
  context?: ConversationMessageGeoContext
  role?: string
  status?: string
  selectedProductId?: string
  reportResponse?: () => Response | Promise<Response>
  workflowResponse?: () => Response | Promise<Response>
  articleResponse?: () => Response | Promise<Response>
  discoveryDraft?: TaskDraft
  historicalDrafts?: TaskDraft[]
  priorDraftResponse?: () => Response | Promise<Response>
  abandonResponse?: () => Response | Promise<Response>
} = {}) {
  const writes: Array<{ path: string; body: Record<string, unknown>; key: string | null }> = []
  const conversation = { id: '1', title: '可见度分析', selectedSpaceId: '4001', selectedProductId: options.selectedProductId ?? '31', activeConfirmationDraftId: options.discoveryDraft?.id ?? null, version: 1, createdAt: now, updatedAt: now, lastMessageAt: now }
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const path = String(input)
    if (init?.method && !['GET', 'HEAD'].includes(init.method)) {
      writes.push({ path, body: init.body ? JSON.parse(String(init.body)) : {}, key: new Headers(init.headers).get('Idempotency-Key') })
      if (path === `/api/v1/geo/reports/${report.id}/article-workflows`) return options.workflowResponse?.() ?? ok(workflow)
      if (path === `/api/v1/task-drafts/${options.discoveryDraft?.id}/abandon`) return options.abandonResponse?.() ?? ok({ ...options.discoveryDraft, status: 'ABANDONED' })
      if (path === '/api/v1/conversations/1/messages') return ok({ messageId: '21', runId: '00000000-0000-4000-8000-000000000001', status: 'COMPLETED' })
      throw new Error(`Unexpected mutation: ${path}`)
    }
    if (path.startsWith('/api/v1/conversations?page=')) return page([conversation])
    if (path === '/api/v1/conversations/1') return ok(conversation)
    if (path.includes('/snapshot?')) return ok({ messages: [{ id: '20', conversationId: '1', role: options.role ?? 'ASSISTANT', contentType: 'TEXT', content: { text: '只读分析已完成，可以选择下一步，也可以聊别的。', geoContext: options.context ?? context, ...(options.discoveryDraft ? { taskDraft: { id: options.discoveryDraft.id } } : {}) }, status: options.status ?? 'COMPLETED', attachmentAssetIds: [], sequence: 1, runId: null, replyToMessageId: null, createdAt: now, updatedAt: now }, ...(options.historicalDrafts ?? []).map((item, index) => ({ id: String(21 + index), conversationId: '1', role: 'ASSISTANT', contentType: 'TEXT', content: { text: '另一篇历史文章的报价', taskDraft: { id: item.id } }, status: 'COMPLETED', attachmentAssetIds: [], sequence: 2 + index, runId: null, replyToMessageId: null, createdAt: now, updatedAt: now }))], activeRun: null, highWatermark: 0, nextBeforeSequence: null })
    if (path === `/api/v1/geo/reports/${report.id}`) return options.reportResponse?.() ?? ok(report)
    if (path === '/api/v1/geo/runs/8001') return ok(geoRunDto)
    if (path === '/api/v1/geo/platforms') return ok(geoPlatformFixtures)
    if (path === '/api/v1/articles/9101') return options.articleResponse?.() ?? ok(article)
    if (path === '/api/v1/task-drafts/9201') return ok(draft)
    if (options.discoveryDraft && path === `/api/v1/task-drafts/${options.discoveryDraft.id}`) return options.priorDraftResponse?.() ?? ok(options.discoveryDraft)
    const historical = options.historicalDrafts?.find((item) => path === `/api/v1/task-drafts/${item.id}`)
    if (historical) return ok(historical)
    if (path.startsWith('/api/v1/tasks?conversationId=')) return page([])
    if (path.startsWith('/api/v1/spaces?')) return page([{ id: '4001', name: '商家空间', brandName: '品牌', productCount: 2, competitorCount: 0, version: 1, createdAt: now, updatedAt: now }])
    if (/\/spaces\/4001\/products\?/.test(path)) return page(['31', '32'].map((id) => ({ id, spaceId: '4001', name: `商品${id}`, category: '杯具', modelSpec: null, aliasCount: 0, version: 1, createdAt: now, updatedAt: now })))
    if (path === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    if (path.endsWith('/events')) return new Response('')
    throw new Error(`Unexpected request: ${path}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, writes }
}

beforeEach(async () => {
  await import('../pages/chat/ChatPage')
  vi.unstubAllGlobals()
  localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
  HTMLElement.prototype.scrollIntoView = vi.fn()
})

describe('可见度报告 → 可选文章方案（仅本地 Browser API stub）', () => {
  it('已读取的报告可以来自另一商品，编辑方向后只准备标题报价，不修改侧栏或自动收费', async () => {
    const api = stub({ selectedProductId: '32', context: { ...context, report: { ...context.report!, observationId: '9901', available: true } } })
    renderApp(['/app/chat?conversation=1'])
    const sourceCard = await screen.findByLabelText('本条回复的 GEO 依据与后续操作')
    expect(api.writes).toEqual([])
    fireEvent.change(within(sourceCard).getByRole('textbox', { name: '文章方向' }), { target: { value: '给通勤者介绍选购思路，只写有依据的商品特点' } })
    await userEvent.click(within(sourceCard).getByRole('button', { name: '准备标题方案' }))
    await screen.findByRole('region', { name: /付费任务确认卡/ })
    expect(api.writes).toHaveLength(1)
    expect(api.writes[0].body).toEqual({ conversationId: '1', suggestionId: null, observationId: '9901', direction: '给通勤者介绍选购思路，只写有依据的商品特点' })
    expect(within(screen.getByRole('complementary', { name: '本次对话上下文' })).getByText('商品32', { selector: 'strong' })).toBeVisible()
  })

  it.each([false, undefined])('资源报告 available=%s 时仅能查看报告，不能用历史资格准备文章', async (available) => {
    const api = stub({ context: { ...context, report: { ...context.report!, observationId: '9901', available } } })
    renderApp(['/app/chat?conversation=1'])
    const sourceCard = await screen.findByLabelText('本条回复的 GEO 依据与后续操作')
    expect(within(sourceCard).getByRole('button', { name: '准备标题方案' })).toBeDisabled()
    expect(within(sourceCard).getByRole('button', { name: '查看报告' })).toBeEnabled()
    expect(within(sourceCard).getByRole('textbox', { name: '文章方向' })).toBeDisabled()
    expect(api.writes).toEqual([])
  })

  it('同报告补读证据和改方向不会先放弃旧稿，服务器修订结果刷新原确认卡', async () => {
    const previous: TaskDraft = { ...draft, id: '9199' }
    let revised = false
    const api = stub({ discoveryDraft: previous, context: { ...context, report: { ...context.report!, observationId: '9902', available: true } },
      priorDraftResponse: () => ok({ ...previous, status: revised ? 'ABANDONED' : previous.status }),
      workflowResponse: () => { revised = true; return ok({ ...workflow, draft: { ...draft, previousDraftId: previous.id } }) },
    })
    renderApp(['/app/chat?conversation=1'])
    await screen.findByRole('region', { name: /付费任务确认卡/ })
    const sourceCard = screen.getByLabelText('本条回复的 GEO 依据与后续操作')
    fireEvent.change(within(sourceCard).getByRole('textbox', { name: '文章方向' }), { target: { value: '换个角度介绍办公室使用场景' } })
    await userEvent.click(within(sourceCard).getByRole('button', { name: '准备标题方案' }))
    await waitFor(() => expect(screen.getAllByRole('region', { name: /付费任务确认卡/ })).toHaveLength(2))
    expect(api.writes.map(({ path }) => path)).toEqual(['/api/v1/geo/reports/9001/article-workflows'])
    expect(api.writes[0].body).toMatchObject({ observationId: '9902', direction: '换个角度介绍办公室使用场景' })
    const reason = await screen.findByText('此方案已被新方案替代，不能继续确认。请核对新的方案卡片。')
    const old = screen.getAllByRole('region', { name: /付费任务确认卡/ }).find((card) => card.contains(reason))!
    expect(within(old).getByRole('button', { name: /确认开始/ })).toBeDisabled()
  })

  it('只在明确准备新来源时放弃未确认旧稿，新稿创建失败也不恢复旧确认', async () => {
    const previous: TaskDraft = { ...draft, id: '9199', parameters: { ...articleParameters, geoReportId: '9002' } }
    let abandoned = false
    const api = stub({ discoveryDraft: previous,
      priorDraftResponse: () => ok({ ...previous, status: abandoned ? 'ABANDONED' : previous.status }),
      abandonResponse: () => { abandoned = true; return ok({ ...previous, status: 'ABANDONED' }) },
      workflowResponse: () => new Response(JSON.stringify({ code: 'SERVICE_UNAVAILABLE', message: '准备方案暂时失败', traceId: testTraceId }), { status: 503, headers: { 'Content-Type': 'application/json' } }),
    })
    renderApp(['/app/chat?conversation=1'])
    const oldCard = await screen.findByRole('region', { name: /付费任务确认卡/ })
    expect(api.writes).toEqual([])
    await userEvent.click(screen.getByRole('button', { name: '准备标题方案' }))
    await screen.findByText(/准备方案暂时失败/)
    expect(api.writes.map(({ path }) => path)).toEqual(['/api/v1/task-drafts/9199/abandon', '/api/v1/geo/reports/9001/article-workflows'])
    expect(within(oldCard).getByRole('button', { name: /确认开始/ })).toBeDisabled()
  })

  it('放弃响应丢失但 GET 已查证旧稿失效，可以继续准备；不重复确认或发布', async () => {
    const previous: TaskDraft = { ...draft, id: '9199', parameters: { ...articleParameters, geoReportId: '9002' } }
    let abandoned = false
    const api = stub({ discoveryDraft: previous,
      priorDraftResponse: () => ok({ ...previous, status: abandoned ? 'ABANDONED' : previous.status }),
      abandonResponse: () => { abandoned = true; throw new TypeError('abandon acknowledgement lost') },
    })
    renderApp(['/app/chat?conversation=1'])
    await screen.findByRole('region', { name: /付费任务确认卡/ })
    await userEvent.click(screen.getByRole('button', { name: '准备标题方案' }))
    await waitFor(() => expect(api.writes).toHaveLength(2))
    expect(api.writes.map(({ path }) => path)).toEqual(['/api/v1/task-drafts/9199/abandon', '/api/v1/geo/reports/9001/article-workflows'])
    expect(api.fetchMock.mock.calls.filter(([path]) => path === '/api/v1/task-drafts/9199').length).toBeGreaterThanOrEqual(3)
  })

  it('存在多份历史报价时只替换当前报价，不放弃另一篇未确认文章', async () => {
    const previous: TaskDraft = { ...draft, id: '9199', parameters: { ...articleParameters, geoReportId: '9002' } }
    const historical: TaskDraft = { ...draft, id: '9198', parameters: { ...articleParameters, geoReportId: '9003' } }
    const api = stub({ discoveryDraft: previous, historicalDrafts: [historical] })
    renderApp(['/app/chat?conversation=1'])
    await waitFor(() => expect(screen.getAllByRole('region', { name: /付费任务确认卡/ })).toHaveLength(2))
    await userEvent.click(screen.getByRole('button', { name: '准备标题方案' }))
    await waitFor(() => expect(screen.getAllByRole('region', { name: /付费任务确认卡/ })).toHaveLength(3))
    expect(api.writes.map(({ path }) => path)).toEqual(['/api/v1/task-drafts/9199/abandon', '/api/v1/geo/reports/9001/article-workflows'])
    expect(api.fetchMock.mock.calls.some(([path]) => path === '/api/v1/task-drafts/9198')).toBe(true)
    expect(screen.getAllByText('此方案已取消，不能继续确认。请查看最新方案，或重新说明需求。')).toHaveLength(1)
  })

  it('放弃与查证都失败时保留不可确认状态，不创建新标题方案', async () => {
    const previous: TaskDraft = { ...draft, id: '9199', parameters: { ...articleParameters, geoReportId: '9002' } }
    let unknown = false
    const api = stub({ discoveryDraft: previous,
      priorDraftResponse: () => { if (unknown) throw new TypeError('verification unavailable'); return ok(previous) },
      abandonResponse: () => { unknown = true; throw new TypeError('unknown abandon result') },
    })
    renderApp(['/app/chat?conversation=1'])
    const oldCard = await screen.findByRole('region', { name: /付费任务确认卡/ })
    await userEvent.click(screen.getByRole('button', { name: '准备标题方案' }))
    await screen.findByText(/暂时无法核对原方案状态/)
    expect(api.writes.map(({ path }) => path)).toEqual(['/api/v1/task-drafts/9199/abandon'])
    expect(within(oldCard).getByRole('button', { name: /确认开始/ })).toBeDisabled()
  })

  it.each(['CONFIRMED', 'BODY', 'REWRITE'] as const)('已有 %s 的文章只继续原工作台，不把新方向变成新标题任务', async (stage) => {
    const existing = { ...draft, status: stage === 'CONFIRMED' ? 'CONFIRMED' : draft.status,
      parameters: { ...draft.parameters, mode: stage === 'CONFIRMED' ? 'TITLE' : stage } }
    const api = stub({ workflowResponse: () => ok({ ...workflow, reused: true, draft: existing }) })
    renderApp(['/app/chat?conversation=1'], <Probe />)
    fireEvent.change(await screen.findByRole('textbox', { name: '文章方向' }), { target: { value: '新的方向不要覆盖已推进的文章' } })
    await userEvent.click(screen.getByRole('button', { name: '准备标题方案' }))
    await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent('/app/articles?article=9101'))
    expect(api.writes.map(({ path }) => path)).toEqual(['/api/v1/geo/reports/9001/article-workflows'])
  })

  it('完成消息使用冻结报告引用，刷新不猜 latest，查看报告跳转精确 ID', async () => {
    const api = stub()
    const mounted = renderApp(['/app/chat?conversation=1'], <Probe />)
    await screen.findByText('本条回答依据：本轮实际使用的报告')
    expect(api.writes).toEqual([])
    expect(api.fetchMock.mock.calls.some(([path]) => String(path).includes('/geo/'))).toBe(false)
    mounted.unmount()
    renderApp(['/app/chat?conversation=1'], <Probe />)
    await userEvent.click(await screen.findByRole('button', { name: '查看报告' }))
    expect(screen.getByTestId('route')).toHaveTextContent('/app/geo/reports/9001')
    expect(api.writes).toEqual([])
  }, 15000)

  it('空 recommendations 仍可一键准备标题报价，双击只请求一次，不确认、不投稿', async () => {
    const api = stub()
    renderApp(['/app/chat?conversation=1'])
    await userEvent.dblClick(await screen.findByRole('button', { name: '准备标题方案' }))
    const card = await screen.findByRole('region', { name: /付费任务确认卡/ })
    expect(within(card).getByText('12 积分')).toBeVisible()
    expect(within(card).getByRole('button', { name: /确认开始/ })).toBeEnabled()
    expect(api.writes).toHaveLength(1)
    expect(api.writes[0]).toMatchObject({ path: '/api/v1/geo/reports/9001/article-workflows', body: { conversationId: '1', suggestionId: null } })
    expect(api.writes[0].key).toBeTruthy()
  }, 15000)

  it('工作流受理响应丢失后沿用原幂等键，不生成另一付费任务', async () => {
    let calls = 0
    const api = stub({ workflowResponse: () => { calls += 1; if (calls === 1) throw new TypeError('lost ack'); return ok(workflow) } })
    renderApp(['/app/chat?conversation=1'])
    // Scope accessibility queries to the small reference card. Whole-page getByRole
    // repeatedly computes styles for the Ant Design workspace, not API progress.
    const sourceCard = await screen.findByLabelText('本条回复的 GEO 依据与后续操作')
    const prepareButton = within(sourceCard).getByRole('button', { name: '准备标题方案' })
    await userEvent.click(prepareButton)
    await waitFor(() => expect(api.writes).toHaveLength(1))
    await screen.findByText(/网络连接失败/)
    await waitFor(() => expect(prepareButton).toBeEnabled())
    await userEvent.click(prepareButton)
    await screen.findByLabelText('付费任务确认卡：文章创作任务确认')
    expect(api.writes).toHaveLength(2)
    expect(api.writes[1].key).toBe(api.writes[0].key)
  }, 15000)

  it.each(['REPORT_ARCHIVED', 'REPORT_ANALYSIS_UNAVAILABLE'] as const)('冻结资格明确不可创作时显示原因且不发送：%s', async (reason) => {
    const api = stub({ context: { ...context, report: { ...context.report!, canCreateArticle: false, articleUnavailableReason: reason } } })
    renderApp(['/app/chat?conversation=1'])
    expect(await screen.findByRole('button', { name: '准备标题方案' })).toBeDisabled()
    expect(screen.getByText(reason === 'REPORT_ARCHIVED' ? /报告已归档，不能创建/ : /尚无可用于创作的有效解读/)).toBeVisible()
    expect(api.writes).toEqual([])
  })

  it('点击时报告已归档会重新拦截，不因历史资格允许就准备任务', async () => {
    const api = stub({ reportResponse: () => ok({ ...report, archivedAt: now }) })
    renderApp(['/app/chat?conversation=1'])
    await userEvent.click(await screen.findByRole('button', { name: '准备标题方案' }))
    await screen.findByText('这份报告已归档，不能创建文章草稿。')
    expect(api.writes).toEqual([])
  })

  it('当前商品不匹配历史来源时不静默改资料', async () => {
    const api = stub({ selectedProductId: '32' })
    renderApp(['/app/chat?conversation=1'])
    await userEvent.click(await screen.findByRole('button', { name: '准备标题方案' }))
    await screen.findByText(/本次资料与这份报告不一致/)
    expect(api.writes).toEqual([])
  })

  it('报告加载中切新会话再返回，迟到读取不创建文章方案', async () => {
    let release!: (response: Response) => void
    const api = stub({ reportResponse: () => new Promise<Response>((resolve) => { release = resolve }) })
    renderApp(['/app/chat?conversation=1'], <Probe />)
    await userEvent.click(await screen.findByRole('button', { name: '准备标题方案' }))
    await waitFor(() => expect(release).toBeTypeOf('function'))
    await userEvent.click(screen.getByRole('button', { name: '切到新聊天' }))
    await userEvent.click(screen.getByRole('button', { name: '回到原聊天' }))
    await act(async () => release(ok(report)))
    expect(api.writes).toEqual([])
    expect(screen.queryByRole('region', { name: /付费任务确认卡/ })).not.toBeInTheDocument()
  }, 15000)

  it('准备文章前换商品，迟到报告读取不覆盖新资料或创建方案', async () => {
    let release!: (response: Response) => void
    const api = stub({ reportResponse: () => new Promise<Response>((resolve) => { release = resolve }) })
    renderApp(['/app/chat?conversation=1'])
    await userEvent.click(await screen.findByRole('button', { name: '准备标题方案' }))
    await waitFor(() => expect(release).toBeTypeOf('function'))
    await userEvent.click(await screen.findByRole('button', { name: '更换商品' }))
    await userEvent.click(await screen.findByRole('radio', { name: /^商品32/ }))
    await userEvent.click(screen.getByRole('button', { name: '确认选择' }))
    await act(async () => release(ok(report)))
    expect(api.writes).toEqual([])
    expect(within(screen.getByRole('complementary', { name: '本次对话上下文' })).getByText('商品32', { selector: 'strong' })).toBeVisible()
  }, 15000)

  it.each(['success', 'conflict', 'error'] as const)('创建中离开，迟到 %s 不串入新会话或导航', async (outcome) => {
    let release!: (response: Response) => void
    const api = stub({ workflowResponse: () => new Promise<Response>((resolve) => { release = resolve }) })
    renderApp(['/app/chat?conversation=1'], <Probe />)
    await userEvent.click(await screen.findByRole('button', { name: '准备标题方案' }))
    await waitFor(() => expect(api.writes).toHaveLength(1))
    await userEvent.click(screen.getByRole('button', { name: '切到新聊天' }))
    fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '保留新的聊天草稿' } })
    const recoveryBefore = localStorage.getItem(getArticleTaskRecoveryStorageKey(userFixture.id))
    const response = outcome === 'success' ? ok(workflow) : new Response(JSON.stringify({ code: 'CONFLICT', message: '迟到的错误', traceId: testTraceId, details: outcome === 'conflict' ? { workflowId: '9201', articleId: '9101', draftId: '9201', conversationId: '1', reportId: '9001' } : {} }), { status: outcome === 'conflict' ? 409 : 500, headers: { 'Content-Type': 'application/json' } })
    await act(async () => release(response))
    expect(screen.getByTestId('route')).toHaveTextContent(/^\/app\/chat$/)
    expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue('保留新的聊天草稿')
    expect(screen.queryByText('迟到的错误')).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: /付费任务确认卡/ })).not.toBeInTheDocument()
    expect(api.fetchMock.mock.calls.some(([path]) => path === '/api/v1/articles/9101')).toBe(false)
    expect(localStorage.getItem(getArticleTaskRecoveryStorageKey(userFixture.id))).toBe(recoveryBefore)
  }, 15000)

  it('文章读取期间账号变化，结果不写入当前会话恢复状态', async () => {
    let release!: (response: Response) => void
    stub({ articleResponse: () => new Promise<Response>((resolve) => { release = resolve }) })
    renderApp(['/app/chat?conversation=1'])
    await userEvent.click(await screen.findByRole('button', { name: '准备标题方案' }))
    await waitFor(() => expect(release).toBeTypeOf('function'))
    await act(async () => { useAuthStore.setState({ status: 'anonymous', user: null, error: null }) })
    const before = { ...localStorage, ...sessionStorage }
    await act(async () => release(ok(article)))
    expect({ ...localStorage, ...sessionStorage }).toEqual(before)
    expect(screen.queryByRole('region', { name: /付费任务确认卡/ })).not.toBeInTheDocument()
  }, 15000)

  it('无报告保留可修改的问题/平台/报价卡，文字确认不执行付费任务', async () => {
    const discoveryDraft: TaskDraft = { ...draft, id: '9301', taskType: 'GEO', parameterSchemaVersion: 1, draftSource: 'CHAT', quotedPoints: 24,
      parameters: { mode: 'QUESTION_DISCOVERY', requestText: '分析当前商品的可见度', agentSummary: '候选监测方案', topic: '保温杯发现', audience: '通勤者', region: '中国', questions: ['有哪些适合通勤的保温杯？', '哪些保温杯适合送礼？'], platformCodes: ['DOUBAO', 'DEEPSEEK'], sampleMode: 'STANDARD' } }
    const api = stub({ context: { ...context, status: 'NO_REPORT', report: null }, discoveryDraft })
    renderApp(['/app/chat?conversation=1'])
    const card = await screen.findByRole('region', { name: /付费任务确认卡/ })
    expect(within(card).getByText('有哪些适合通勤的保温杯？')).toBeVisible()
    expect(within(card).getByText('豆包')).toBeVisible()
    expect(within(card).getByText('24 积分')).toBeVisible()
    await userEvent.click(within(card).getByRole('button', { name: /修改/ }))
    await waitFor(() => expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue('修改当前待确认方案：\n分析当前商品的可见度'))
    fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '确认' } })
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    await screen.findByText(/文字确认不会开始付费任务/)
    expect(api.writes).toEqual([])
  }, 15000)
})

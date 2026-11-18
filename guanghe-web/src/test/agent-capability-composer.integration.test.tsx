import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AgentDefinition, AgentInstallation, Project } from '../api/agentPlatformApi'
import type { Conversation, ConversationMessage } from '../api/conversationApi'
import type { Article } from '../api/articleApi'
import type { ArticleFullPreparation } from '../api/articleFullApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { renderApp } from './renderApp'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'
import '../pages/chat/ChatPage'

const now = '2026-10-11T01:00:00Z'
const runId = 'b0db759f-ee24-49e9-9fbd-46fdd8c54949'
const definitions: AgentDefinition[] = [
  { agentKey: 'geo', name: 'GEO 智能体', capabilities: ['chat', 'resources.read', 'geo.read', 'geo.monitor', 'article.generate', 'media.recommend'] },
  { agentKey: 'creative', name: '创作智能体', capabilities: ['chat', 'resources.read', 'image.generate', 'video.generate'] },
  { agentKey: 'summary', name: '只读摘要助手', capabilities: ['chat', 'resources.read', 'text.summarize'] },
  // Test-only fourth identity. Production has no corresponding demo definition or installation.
  { agentKey: 'brand-editor', name: '品牌文案助手', capabilities: ['chat', 'resources.read', 'article.generate'] },
  { agentKey: 'poster', name: '海报助手', capabilities: ['chat', 'resources.read', 'image.generate'] },
].map((item) => ({ ...item, version: '1.0.0', description: item.name, status: 'PUBLISHED' }))
const installations: AgentInstallation[] = definitions.map((item, index) => ({ id: String(101 + index), agentKey: item.agentKey,
  agentVersion: item.version, name: item.name, enabled: true, version: 1, createdAt: now }))
const projects: Project[] = ['小行星咖啡店', '艾尼亚科'].map((name, index) => ({ id: String(201 + index), name, status: 'ACTIVE', version: 1, createdAt: now, updatedAt: now }))
const article: Article = { id: '701', spaceId: '201', productId: null, title: '小行星咖啡店的一杯日常', status: 'COMPLETED', version: 5,
  savedToAssets: false, savedToAssetsAt: null, savedArticleVersion: null, createdAt: now, updatedAt: now,
  bodyHtml: '<h2>认识小行星咖啡店</h2><p>以已确认的品牌资料介绍门店。</p>', bodyText: '以已确认的品牌资料介绍门店。',
  outline: [], assets: [], workflowStage: 'BODY_READY', titleCandidateBatch: null, confirmedTitle: null, creativePlan: null }
const preparation: ArticleFullPreparation = { id: '601', templateCode: 'ARTICLE_FULL_V1', status: 'SUCCEEDED', conversationId: '801',
  articleId: '701', articleVersion: 5, quotedPoints: 3, settledPoints: 3, releasedPoints: 0, deadlineAt: '2099-01-01T00:00:00Z', errorCode: null,
  stages: [], publicationRequiresConfirmation: true, knowledgeSourcesCurrent: true }
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data }), { headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[]) => ok({ items, page: 1, size: 100, total: items.length, hasNext: false })

function stubApi(options: { articleResult?: boolean; storageGate?: Promise<void>; catalog?: AgentDefinition[];
  installed?: AgentInstallation[]; existingConversation?: Conversation; existingMessages?: ConversationMessage[] } = {}) {
  const writes: { path: string; body: Record<string, unknown> }[] = []
  const catalog = options.catalog ?? definitions
  let installed = options.installed ?? installations
  let conversation = options.existingConversation
  let messages = options.existingMessages ?? []
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const path = String(input)
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : {}
    if (method !== 'GET') writes.push({ path, body })
    if (path === '/api/v1/agents') return ok(catalog)
    if (path === '/api/v1/agent-installations') return ok(installed)
    const installationUpdate = path.match(/^\/api\/v1\/agent-installations\/(\d+)$/)
    if (installationUpdate && method === 'PATCH') {
      installed = installed.map((item) => item.id === installationUpdate[1]
        ? { ...item, enabled: body.enabled === true, version: item.version + 1 } : item)
      return ok(installed.find((item) => item.id === installationUpdate[1]))
    }
    if (path === '/api/v1/projects') return ok(projects)
    if (path.startsWith('/api/v1/conversations?')) return page(conversation ? [conversation] : [])
    if (path === '/api/v1/conversations' && method === 'POST') {
      const installation = installed.find((item) => item.id === body.installationId)!
      const definition = catalog.find((item) => item.agentKey === installation.agentKey && item.version === installation.agentVersion)!
      conversation = { id: '801', title: '新智能体功能验收', selectedSpaceId: null, selectedProductId: null, activeConfirmationDraftId: null,
        version: 1, lastMessageAt: now, createdAt: now, updatedAt: now, agentIdentity: { installationId: installation.id,
          agentKey: installation.agentKey, agentVersion: installation.agentVersion, name: installation.name,
          projectId: body.projectId ? String(body.projectId) : null, capabilities: definition.capabilities } }
      return ok(conversation)
    }
    if (path === '/api/v1/conversations/801') return ok(conversation)
    if (path === '/api/v1/conversations/801/messages' && method === 'POST') {
      messages = [
        { id: '901', role: 'USER', content: { text: String(body.content) }, sequence: 1 },
        { id: '902', role: 'ASSISTANT', content: { text: '已完成当前请求。', ...(options.articleResult ? { articlePreparation: { preparationId: '601', articleId: '701' } } : {}) }, sequence: 2 },
      ].map((item) => ({ ...item, conversationId: '801', contentType: 'TEXT', runId, replyToMessageId: null, attachmentAssetIds: [], status: 'COMPLETED', createdAt: now, updatedAt: now })) as ConversationMessage[]
      return ok({ messageId: '901', runId, status: 'COMPLETED' })
    }
    if (path.includes('/snapshot?')) return ok({ messages, nextBeforeSequence: null, highWatermark: messages.length, activeRun: null })
    if (path.includes('/message-operations/')) return ok({ messageId: '901', runId, status: 'COMPLETED' })
    if (path.includes('/events')) return new Response('', { headers: { 'Content-Type': 'text/event-stream' } })
    if (path.endsWith('/creative-storage')) { await options.storageGate; return ok({ spaceId: '301' }) }
    if (path === '/api/v1/article-full-preparations/601') return ok(preparation)
    if (path === '/api/v1/articles/701') return ok(article)
    if (path.startsWith('/api/v1/tasks?') || path.startsWith('/api/v1/assets?') || path.startsWith('/api/v1/spaces?')) return page([])
    if (path === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    if (path === '/api/v1/notifications/unread-count') return ok({ unreadCount: 0 })
    throw new Error(`Unexpected request ${method} ${path}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { writes, fetchMock }
}
function RouteProbe() { const location = useLocation(); return <output data-testid="route">{location.pathname}{location.search}</output> }

beforeEach(() => {
  vi.unstubAllGlobals(); sessionStorage.clear(); localStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})
afterEach(() => vi.unstubAllGlobals())

describe('按能力复用的真实聊天页面交互（Java 接口桩，不调用上游模型）', () => {
  it.each(['missing', 'different-version', 'disabled'] as const)('目录 %s 时旧安装不可选，不借其他版本能力发送新任务', async (state) => {
    const editor = definitions[3]
    const catalog: AgentDefinition[] = state === 'missing' ? [] : [{ ...editor,
      version: state === 'different-version' ? '2.0.0' : editor.version,
      status: state === 'disabled' ? 'DISABLED' : 'PUBLISHED' }]
    if (state === 'disabled') catalog.unshift({ ...editor, version: '2.0.0' })
    const api = stubApi({ catalog, installed: [installations[3]] })
    renderApp(['/app?installation=104'])
    await screen.findByText('这个智能体暂不可用于新任务，历史会话仍可查看。')
    expect(screen.getByText('已添加的智能体目前不可用于新任务。请前往智能体库查看状态，或启用可用的智能体。')).toBeVisible()
    fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '继续当前品牌需求' } })
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: '写文章' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /监测一次/ })).not.toBeInTheDocument()
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '使用智能体' }))
    const option = await screen.findByText(editor.name, { selector: '.ant-select-item-option-content' })
    expect(option.closest('.ant-select-item-option')).toHaveClass('ant-select-item-option-disabled')
    expect(api.writes).toEqual([])
  })

  it.each(['PUBLISHED', 'WITHDRAWN'] as const)('新版与已安装 %s 旧版同时返回时只显示一张目录卡，仍以旧身份和旧能力发起聊天', async (status) => {
    const installed = installations[3]
    const api = stubApi({ installed: [installed], catalog: [
      { ...definitions[3], version: '2.0.0', description: '新版支持文章生成' },
      { ...definitions[3], status, capabilities: ['chat', 'resources.read'], description: '已安装版本仅支持对话', installedInstallationId: installed.id },
    ] })
    const user = userEvent.setup()
    renderApp(['/app/agents'], <RouteProbe />)
    expect(await screen.findByRole('button', { name: '使用 品牌文案助手' })).toBeEnabled()
    expect(screen.getAllByRole('heading', { name: '品牌文案助手' })).toHaveLength(1)
    expect(screen.getByText('新版支持文章生成')).toBeVisible()
    expect(screen.queryByText('已安装版本仅支持对话')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '添加 品牌文案助手' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: '我的智能体' }))
    expect(screen.getByText('已安装版本仅支持对话')).toBeVisible()
    expect(screen.getByText('智能体库已提供版本 2.0.0。已有会话保留原版本。')).toBeVisible()
    await user.click(screen.getByRole('button', { name: '使用 品牌文案助手' }))
    await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent('installation=104'))
    const input = await screen.findByRole('textbox', { name: '消息输入' })
    expect(screen.queryByRole('button', { name: '写文章' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /监测一次/ })).not.toBeInTheDocument()
    fireEvent.change(input, { target: { value: '总结当前已知的信息' } })
    await user.click(screen.getByRole('button', { name: '发送' }))
    expect(await screen.findByText('已完成当前请求。')).toBeVisible()
    expect(api.writes.map(({ path }) => path)).toEqual(['/api/v1/conversations', '/api/v1/conversations/801/messages'])
    expect(api.writes[0].body).toEqual({ installationId: installed.id })
    expect(screen.queryByRole('button', { name: '写文章' })).not.toBeInTheDocument()
  })

  it('未安装的下架版本不能添加，已安装但停用的下架版本可以重新启用', async () => {
    const installed = { ...installations[3], enabled: false }
    const api = stubApi({ installed: [installed], catalog: [
      { ...definitions[3], status: 'WITHDRAWN', installedInstallationId: installed.id },
      { ...definitions[4], status: 'WITHDRAWN' },
    ] })
    const user = userEvent.setup()
    renderApp(['/app/agents'])
    expect(await screen.findByRole('button', { name: '使用 品牌文案助手' })).toBeDisabled()
    expect(screen.getByRole('button', { name: /添加 海报助手$/ })).toBeDisabled()
    await user.click(screen.getByRole('tab', { name: '我的智能体' }))
    await user.click(screen.getByRole('button', { name: /启\s*用/ }))
    await waitFor(() => expect(screen.getByRole('button', { name: '使用 品牌文案助手' })).toBeEnabled())
    expect(screen.getByRole('button', { name: /停\s*用/ })).toBeEnabled()
    expect(api.writes).toEqual([{ path: '/api/v1/agent-installations/104', body: { version: 1, enabled: true } }])
  })

  it('智能体库不把新版当作已安装版本；新版提示保留，旧会话仍能打开阅读', async () => {
    const editor = installations[3]
    const history: Conversation = { id: '801', title: '旧版本的客户分析', selectedSpaceId: null, selectedProductId: null,
      activeConfirmationDraftId: null, version: 1, lastMessageAt: now, createdAt: now, updatedAt: now,
      agentIdentity: { installationId: editor.id, agentKey: editor.agentKey, agentVersion: editor.agentVersion,
        name: editor.name, projectId: '201', capabilities: ['chat', 'resources.read'] } }
    const historicalMessage: ConversationMessage = { id: '902', conversationId: history.id, role: 'ASSISTANT', contentType: 'TEXT',
      content: { text: '这条历史回答属于旧版本。' }, sequence: 1, runId, replyToMessageId: null,
      attachmentAssetIds: [], status: 'COMPLETED', createdAt: now, updatedAt: now }
    const api = stubApi({ catalog: [{ ...definitions[3], version: '2.0.0', description: '仅属于新版本的能力介绍' }],
      installed: [editor], existingConversation: history, existingMessages: [historicalMessage] })
    const user = userEvent.setup()
    renderApp(['/app/agents?tab=installed'])
    expect(await screen.findByRole('button', { name: '使用 品牌文案助手' })).toBeDisabled()
    expect(screen.getByText('智能体库已提供版本 2.0.0。已有会话保留原版本。')).toBeVisible()
    expect(screen.queryByText('仅属于新版本的能力介绍')).not.toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: '智能体库' }))
    expect(screen.getByRole('button', { name: '使用 品牌文案助手' })).toBeDisabled()
    expect(screen.getByText('仅属于新版本的能力介绍')).toBeVisible()
    await user.click(screen.getByTitle(history.title))
    expect(await screen.findByText('这条历史回答属于旧版本。')).toBeVisible()
    expect(screen.getByRole('heading', { name: history.title })).toBeVisible()
    expect(screen.queryByRole('button', { name: '写文章' })).not.toBeInTheDocument()
    expect(api.writes).toEqual([])
  })

  it('资产中心不使用同名新版本的生图能力开放个人素材，客户项目仍可选择', async () => {
    const api = stubApi({ catalog: [{ ...definitions[4], version: '2.0.0' }], installed: [installations[4]] })
    renderApp(['/app/assets'])
    const filter = await screen.findByRole('combobox', { name: '按项目筛选' })
    await waitFor(() => expect(filter).toBeEnabled())
    fireEvent.mouseDown(filter)
    const projectOption = await screen.findByText('小行星咖啡店', { selector: '.ant-select-item-option-content' })
    expect(screen.queryByText('个人素材', { selector: '.ant-select-item-option-content' })).not.toBeInTheDocument()
    // Exercise the scoped selection; JSDOM cannot validate the dropdown's entry animation.
    fireEvent.click(projectOption)
    await waitFor(() => expect(api.fetchMock.mock.calls.some(([url]) => String(url).startsWith('/api/v1/assets?')
      && new URL(String(url), 'https://example.test').searchParams.get('spaceId') === '201')).toBe(true))
    expect(api.writes).toEqual([])
  })

  it('第四个身份复用写作入口、普通发送和正式文章画布，不产生旧确认或投稿调用', async () => {
    const api = stubApi({ articleResult: true }); const user = userEvent.setup()
    renderApp(['/app?installation=104&project=201'])
    await user.click(await screen.findByRole('button', { name: '写文章' }))
    const input = screen.getByRole('textbox', { name: '消息输入' })
    expect(input).toHaveValue('结合项目资料，帮我写一篇完整的品牌文章。缺少关键事实时先问我，投稿前由我确认。')
    expect(api.writes).toEqual([])
    await user.click(screen.getByRole('button', { name: '发送' }))
    await screen.findByText('完整文章已准备')
    expect(api.writes.map(({ path }) => path)).toEqual(['/api/v1/conversations', '/api/v1/conversations/801/messages'])
    expect(api.writes[0].body).toEqual({ installationId: '104', projectId: '201' })
    expect(api.writes[1].body).not.toHaveProperty('articlePreparationMode')
    await user.click(screen.getByRole('button', { name: /查看文章$/ }))
    expect(await screen.findByRole('heading', { name: article.title })).toBeVisible()
    expect(screen.getByRole('heading', { name: '认识小行星咖啡店' })).toBeVisible()
    const document = within(screen.getByRole('heading', { name: article.title }).closest('section')!)
    expect(document.getByRole('button', { name: /保存到资产中心$/ })).toBeEnabled()
    expect(document.getByRole('button', { name: /投稿$/ })).toBeEnabled()
    expect(api.writes).toHaveLength(2)
  })

  it('GEO 仍有监测和写文；点击快捷入口只预填，已有文字不会被覆盖', async () => {
    const api = stubApi(); const user = userEvent.setup()
    renderApp(['/app?installation=101&project=201'])
    await user.click(await screen.findByRole('button', { name: '写文章' }))
    expect(screen.getByRole('button', { name: /监测一次/ })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '生成图片' })).not.toBeInTheDocument()
    const input = screen.getByRole('textbox', { name: '消息输入' })
    expect((input as HTMLTextAreaElement).value).toContain('推荐适合的媒体')
    fireEvent.change(input, { target: { value: '不要覆盖这个客户的具体需求' } })
    await user.click(screen.getByRole('button', { name: '写文章' }))
    expect(input).toHaveValue('不要覆盖这个客户的具体需求')
    expect(await screen.findByText('输入内容已保留。直接说明要写的文章，发送后即可继续。')).toBeVisible()
    expect(api.writes).toEqual([])
  })

  it('创作能力分别显示图像和视频；只读 Agent 不展示任何生成或监测快捷入口', async () => {
    const api = stubApi(); const user = userEvent.setup()
    renderApp(['/app?installation=102'])
    expect(await screen.findByRole('heading', { name: '你好，我是创作智能体。' }, { timeout: 5_000 })).toBeVisible()
    expect(screen.getByText(/上传参考图片后告诉我保留什么、修改什么/)).toBeVisible()
    expect(screen.getByLabelText('推荐输入入口')).toHaveTextContent('生成图片')
    await user.click(await screen.findByRole('button', { name: '生成图片' }))
    const input = screen.getByRole('textbox', { name: '消息输入' })
    expect(input).toHaveValue('帮我生成一张图片：')
    fireEvent.change(input, { target: { value: '' } })
    await user.click(screen.getByRole('button', { name: '生成视频' }))
    expect(input).toHaveValue('帮我生成一段视频：')
    await user.click(screen.getByRole('combobox', { name: '使用智能体' }))
    await user.click(await screen.findByText('只读摘要助手', { selector: '.ant-select-item-option-content' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: '生成视频' })).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '生成图片' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '写文章' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /监测一次/ })).not.toBeInTheDocument()
    expect(input).toHaveValue('帮我生成一段视频：')
    expect(api.writes).toEqual([])
  })

  it('非 creative 身份有生图能力也可读取个人素材入口，不会获得视频能力', async () => {
    const api = stubApi(); const user = userEvent.setup()
    renderApp(['/app?installation=105'])
    expect(await screen.findByRole('button', { name: '生成图片' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '生成视频' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '添加资料' }))
    await user.click(await screen.findByRole('menuitem', { name: /从资产中心选择图片/ }))
    // Ant Design's entry animation has no layout engine in JSDOM; assert the
    // mounted picker and scoped request, not browser animation visibility.
    expect(await screen.findByText('选择本次要参考的图片')).toBeInTheDocument()
    expect(screen.getByText('资产中心暂无可使用的图片')).toBeInTheDocument()
    expect(api.fetchMock.mock.calls.some(([url]) => String(url).includes('spaceId=301'))).toBe(true)
    expect(api.writes.map(({ path }) => path)).toEqual(['/api/v1/agent-installations/105/creative-storage'])
  })

  it('个人素材初始化迟到时不能把选择器或错误带到另一个客户项目', async () => {
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    const api = stubApi({ storageGate: gate }); const user = userEvent.setup()
    renderApp(['/app?installation=105'], <RouteProbe />)
    await screen.findByRole('button', { name: '生成图片' })
    await user.click(screen.getByRole('button', { name: '添加资料' }))
    await user.click(await screen.findByRole('menuitem', { name: /从资产中心选择图片/ }))
    await waitFor(() => expect(api.writes).toHaveLength(1))
    await user.click(screen.getByRole('combobox', { name: '任务所属项目' }))
    await user.click(await screen.findByText('艾尼亚科', { selector: '.ant-select-item-option-content' }))
    await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent('project=202'))
    await act(async () => { release(); await gate })
    expect(screen.queryByText('选择本次要参考的图片')).not.toBeInTheDocument()
    expect(screen.queryByText('对话已切换，请在当前对话重新选择图片。')).not.toBeInTheDocument()
    expect(api.writes).toHaveLength(1)
  })

  it('换账号后个人素材初始化的迟到响应不打开旧账号素材选择器', async () => {
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    const api = stubApi({ storageGate: gate }); const user = userEvent.setup()
    renderApp(['/app?installation=105'])
    await screen.findByRole('button', { name: '生成图片' })
    await user.click(screen.getByRole('button', { name: '添加资料' }))
    await user.click(await screen.findByRole('menuitem', { name: /从资产中心选择图片/ }))
    await waitFor(() => expect(api.writes).toHaveLength(1))
    act(() => {
      storeAuthToken({ accessToken: 'other-test-owner', expiresAt: testExpiresAt })
      useAuthStore.setState({ status: 'authenticated', user: { ...userFixture, id: '2020', displayName: '另一账号' }, error: null })
    })
    await act(async () => { release(); await gate })
    expect(screen.queryByText('选择本次要参考的图片')).not.toBeInTheDocument()
    expect(api.fetchMock.mock.calls.some(([url]) => String(url).includes('spaceId=301'))).toBe(false)
    expect(api.writes).toHaveLength(1)
  })
})

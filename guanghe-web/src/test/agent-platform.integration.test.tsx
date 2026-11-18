import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AgentDefinition, AgentInstallation, Project } from '../api/agentPlatformApi'
import type { Conversation } from '../api/conversationApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { renderApp } from './renderApp'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const now = '2026-10-06T01:00:00Z'
const geo: AgentDefinition = { agentKey: 'geo', version: '1.0.0', name: 'GEO 智能体', description: '帮助提高品牌可见度', capabilities: ['chat', 'resources.read', 'geo.read', 'geo.monitor'], status: 'PUBLISHED' }
const summary: AgentDefinition = { agentKey: 'summary', version: '1.0.0', name: '摘要助手', description: '内部验收夹具', capabilities: ['chat', 'resources.read', 'text.summarize'], status: 'PUBLISHED' }
const installation = (id = '101', definition = geo): AgentInstallation => ({ id, agentKey: definition.agentKey, agentVersion: definition.version, name: definition.name, enabled: true, version: 1, createdAt: now })
const project: Project = { id: '201', name: '品牌研究', status: 'ACTIVE', version: 1, createdAt: now, updatedAt: now }
const conversation = (item = installation(), projectId: string | null = project.id, id = '301'): Conversation => ({ id, title: '保留的历史任务', selectedSpaceId: null, selectedProductId: null, activeConfirmationDraftId: null, version: 1, lastMessageAt: null, createdAt: now, updatedAt: now, agentIdentity: { installationId: item.id, agentKey: item.agentKey, agentVersion: item.agentVersion, name: item.name, projectId, capabilities: item.agentKey === 'geo' ? geo.capabilities : summary.capabilities } })
const ok = (data: unknown, status = 200) => new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data }), { status, headers: { 'Content-Type': 'application/json' } })
const fail = () => new Response(JSON.stringify({ code: 'SERVICE_UNAVAILABLE', message: '暂不可用', traceId: testTraceId }), { status: 503, headers: { 'Content-Type': 'application/json' } })
function RouteProbe() { const location = useLocation(); return <output data-testid="route">{location.pathname}{location.search}</output> }

function stubPlatform(options: { installations?: AgentInstallation[]; projects?: Project[]; conversations?: Conversation[]; internalSummary?: boolean; catalogError?: boolean; addError?: boolean; status?: AgentDefinition['status']; createGate?: Promise<void> } = {}) {
  let installed = options.installations ?? []
  const projects = options.projects ?? []
  const conversations = options.conversations ?? []
  const definitions = options.internalSummary ? [geo, summary] : [{ ...geo, status: options.status ?? geo.status }]
  const writes: Array<{ url: string; method: string; body: Record<string, unknown> }> = []
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : {}
    if (method !== 'GET') writes.push({ url, method, body })
    if (url === '/api/v1/agents') return options.catalogError ? fail() : ok(definitions.map((item) => ({ ...item, installedInstallationId: installed.find((entry) => entry.agentKey === item.agentKey)?.id })))
    if (url === '/api/v1/agent-installations' && method === 'GET') return ok(installed)
    if (url === '/api/v1/agent-installations' && method === 'POST') {
      if (options.addError) return fail()
      const definition = definitions.find((item) => item.agentKey === body.agentKey)!
      const item = installation(String(101 + installed.length), definition)
      installed = [...installed, item]
      return ok(item)
    }
    if (url.startsWith('/api/v1/agent-installations/') && method === 'DELETE') { installed = installed.filter((item) => !url.endsWith('/' + item.id)); return ok(null) }
    if (url.startsWith('/api/v1/agent-installations/') && method === 'PATCH') {
      const item = installed.find((entry) => url.endsWith('/' + entry.id))!
      item.enabled = body.enabled === true; item.version += 1
      return ok(item)
    }
    if (url === '/api/v1/projects' && method === 'GET') return ok(projects)
    if (url === '/api/v1/projects' && method === 'POST') { const item = { ...project, name: String(body.name) }; projects.push(item); return ok(item, 201) }
    if (url.startsWith('/api/v1/conversations?')) return ok({ items: conversations, page: 1, size: 100, total: conversations.length, hasNext: false })
    if (url === '/api/v1/conversations' && method === 'POST') {
      await options.createGate
      const item = conversation(installed.find((entry) => entry.id === body.installationId)!, body.projectId ? String(body.projectId) : null, String(302 + conversations.length))
      conversations.push(item); return ok(item, 201)
    }
    if (/\/conversations\/\d+$/.test(url)) return ok(conversations.find((item) => url.endsWith('/' + item.id)))
    if (url.includes('/snapshot')) return ok({ messages: [], nextBeforeSequence: null, highWatermark: 0, activeRun: null })
    if (url.endsWith('/messages') && method === 'POST') return ok({ messageId: '401', runId: '9a3935c1-141d-450b-9056-3b37457c48c9', status: 'QUEUED' }, 202)
    if (url.includes('/events')) return new Response('', { headers: { 'Content-Type': 'text/event-stream' } })
    if (url.startsWith('/api/v1/spaces?') || url.startsWith('/api/v1/tasks?')) return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
    if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    if (url === '/api/v1/notifications/unread-count') return ok({ unreadCount: 0 })
    return fail()
  })
  vi.stubGlobal('fetch', fetchMock)
  return { writes, fetchMock, conversations, projects }
}

beforeEach(async () => {
  await Promise.all([import('../pages/chat/ChatPage'), import('../pages/agents/AgentsPage')])
  vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('智能体平台普通用户旅程', () => {
  it('从库添加到我的智能体，再使用，无项目也可发送并创建绑定身份的新会话', async () => {
    const api = stubPlatform()
    const user = userEvent.setup()
    renderApp(['/app/agents'], <RouteProbe />)
    await user.click(await screen.findByRole('button', { name: /添加 GEO 智能体/ }))
    expect(await screen.findByText('已添加，可开始使用。')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '使用 GEO 智能体' }))
    await screen.findByRole('heading', { name: '今天想完成什么？' })
    expect(api.writes.filter((item) => item.url === '/api/v1/conversations')).toHaveLength(0)
    await user.type(screen.getByRole('textbox', { name: '消息输入' }), '提高品牌可见度')
    await user.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(api.writes.find((item) => item.url.endsWith('/messages'))).toBeDefined())
    expect(api.writes.find((item) => item.url === '/api/v1/conversations')?.body).toEqual({ installationId: '101' })
    expect(await screen.findByText('GEO 智能体 · v1.0.0')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('route')).toHaveTextContent('conversation=302'))
    expect(screen.queryByRole('combobox', { name: '使用智能体' })).not.toBeInTheDocument()
    expect(api.projects).toHaveLength(0)
    expect(screen.queryByText('摘要助手')).not.toBeInTheDocument()
  })

  it('从旧会话切换到另一个智能体只开启新任务，保留旧身份及所属项目', async () => {
    const existing = conversation()
    const api = stubPlatform({ installations: [installation(), installation('102', summary)], projects: [project], conversations: [existing], internalSummary: true })
    const user = userEvent.setup()
    renderApp(['/app/chat?conversation=301&installation=102'], <RouteProbe />)
    await screen.findByText('GEO 智能体 · v1.0.0')
    await user.click(screen.getByRole('button', { name: '选择智能体开始新任务' }))
    await user.click(await screen.findByRole('button', { name: '使用 摘要助手' }))
    expect(screen.getByTestId('route')).toHaveTextContent('/app?installation=102&project=201')
    await user.type(await screen.findByRole('textbox', { name: '消息输入' }), '总结这段文字')
    await user.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(api.writes.find((item) => item.url === '/api/v1/conversations')?.body).toEqual({ installationId: '102', projectId: '201' }))
    await screen.findByText('摘要助手 · v1.0.0')
    expect(existing.agentIdentity?.installationId).toBe('101')
    expect(existing.agentIdentity?.projectId).toBe('201')
    expect(api.writes.some((item) => item.url === '/api/v1/conversations/301')).toBe(false)
    expect(screen.queryByRole('button', { name: '查看已有 GEO 趋势' })).not.toBeInTheDocument()
  })

  it('停用、启用、移除调用真实接口；移除后仍可打开项目中的历史会话', async () => {
    const api = stubPlatform({ installations: [installation()], projects: [project], conversations: [conversation()] })
    const user = userEvent.setup()
    renderApp(['/app/agents?tab=installed'], <RouteProbe />)
    await user.click(await screen.findByRole('button', { name: /^停\s*用$/ }))
    await screen.findByText('已停用，历史任务与成果仍可查看。')
    expect(screen.getByRole('button', { name: '使用 GEO 智能体' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: /^启\s*用$/ }))
    await screen.findByText('已启用。')
    await user.click(screen.getByRole('button', { name: /^移\s*除$/ }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: /^移\s*除$/ }))
    await screen.findByText('已移除智能体，项目资料、历史会话与成果均已保留。')
    await user.click(screen.getByRole('button', { name: /保留的历史任务/ }))
    await screen.findByRole('heading', { name: '保留的历史任务' })
    expect(screen.getByText('GEO 智能体 · v1.0.0')).toBeInTheDocument()
    expect(api.writes.filter((item) => item.method === 'PATCH').map((item) => item.body)).toEqual([{ version: 1, enabled: false }, { version: 2, enabled: true }])
    expect(api.writes.filter((item) => item.method === 'DELETE').map((item) => item.url)).toEqual(['/api/v1/agent-installations/101'])
    expect(api.conversations).toHaveLength(1)
  })

  it('项目只需名称；项目任务与最近任务不重复，窄屏导航可开关', async () => {
    const api = stubPlatform({ conversations: [conversation(installation(), null)] })
    const user = userEvent.setup()
    const { container } = renderApp(['/app/agents'])
    await user.click(await screen.findByRole('button', { name: '新建项目' }))
    await user.type(screen.getByRole('textbox', { name: '项目名称' }), '没有品牌的研究')
    await user.click(screen.getByRole('button', { name: '创建项目' }))
    await screen.findByTitle('没有品牌的研究')
    expect(api.writes.find((item) => item.url === '/api/v1/projects')?.body).toEqual({ name: '没有品牌的研究' })
    expect(screen.getAllByRole('button', { name: /保留的历史任务/ })).toHaveLength(1)
    // JSDOM does not apply narrow-screen media queries; exercise the same toggle control directly.
    fireEvent.click(screen.getByLabelText('展开导航'))
    expect(container.querySelector('.platform-shell--sidebar-open')).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('关闭导航'))
    expect(container.querySelector('.platform-shell--sidebar-open')).not.toBeInTheDocument()
    expect(container.querySelector('.platform-sidebar')).toHaveStyle({ width: '232px' })
  })

  it.each(['removed', 'disabled', 'archived'] as const)('不可用的 %s 选择不回退成旧默认身份', async (state) => {
    const api = stubPlatform({ installations: state === 'removed' ? [] : [{ ...installation(), enabled: state !== 'disabled' }], projects: [{ ...project, status: state === 'archived' ? 'ARCHIVED' : 'ACTIVE' }] })
    renderApp(['/app?installation=101&project=201'])
    await screen.findByText(state === 'removed' ? /这个智能体已被移除/ : state === 'disabled' ? /这个智能体已停用/ : /所属项目不可用或已归档/)
    fireEvent.change(screen.getByRole('textbox', { name: '消息输入' }), { target: { value: '请开始' } })
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled()
    expect(api.writes).toHaveLength(0)
  })

  it('目录或添加失败呈现可恢复错误，不显示虚构的成功安装', async () => {
    const api = stubPlatform({ addError: true })
    const user = userEvent.setup()
    renderApp(['/app/agents'])
    await user.click(await screen.findByRole('button', { name: /添加 GEO 智能体/ }))
    await screen.findByText('操作结果未确认，请重新加载当前状态后重试。')
    expect(screen.queryByText('已添加，可开始使用。')).not.toBeInTheDocument()
    expect(api.writes).toHaveLength(1)
    expect(screen.getByRole('button', { name: /添加 GEO 智能体/ })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '重新加载' }))
    await waitFor(() => expect(screen.getByRole('button', { name: /添加 GEO 智能体/ })).toBeEnabled())
  })

  it('下架只禁止新添加，已安装且未停用的智能体仍可用于新任务', async () => {
    const api = stubPlatform({ installations: [installation()], status: 'WITHDRAWN' })
    const user = userEvent.setup()
    renderApp(['/app/agents'])
    await user.click(await screen.findByRole('button', { name: '使用 GEO 智能体' }))
    await user.type(await screen.findByRole('textbox', { name: '消息输入' }), '继续研究')
    await user.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(api.writes.find((item) => item.url === '/api/v1/conversations')?.body).toEqual({ installationId: '101' }))
  })

  it('下架目录不允许未安装用户添加', async () => {
    const api = stubPlatform({ status: 'WITHDRAWN' })
    renderApp(['/app/agents'])
    expect(await screen.findByRole('button', { name: /添加 GEO 智能体/ })).toBeDisabled()
    expect(api.writes).toHaveLength(0)
  })

  it('目录不可用时保留错误与重试，不伪造空目录', async () => {
    stubPlatform({ catalogError: true })
    renderApp(['/app/agents'])
    await screen.findByText('智能体数据加载失败，请重试。', {}, { timeout: 5000 })
    expect(screen.getByRole('button', { name: '重新加载' })).toBeInTheDocument()
    expect(screen.queryByText('暂无已发布的智能体')).not.toBeInTheDocument()
  })

  it('创建回执迟到不会切走用户已经打开的历史会话，消息仍绑定原智能体', async () => {
    let release: () => void = () => undefined
    const createGate = new Promise<void>((resolve) => { release = resolve })
    const existing = conversation()
    const api = stubPlatform({ installations: [installation()], conversations: [existing], projects: [project], createGate })
    const user = userEvent.setup()
    renderApp(['/app?installation=101'], <RouteProbe />)
    await user.type(await screen.findByRole('textbox', { name: '消息输入' }), '提高品牌可见度')
    await user.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(api.writes.some((item) => item.url === '/api/v1/conversations')).toBe(true))
    await user.click(screen.getByTitle('保留的历史任务'))
    await screen.findByRole('heading', { name: '保留的历史任务' })
    release()
    await waitFor(() => expect(api.writes.some((item) => item.url === '/api/v1/conversations/303/messages')).toBe(true))
    expect(screen.getByTestId('route')).toHaveTextContent('conversation=301')
    expect(api.conversations.find((item) => item.id === '303')?.agentIdentity?.installationId).toBe('101')
    expect(existing.agentIdentity?.installationId).toBe('101')
  })
})

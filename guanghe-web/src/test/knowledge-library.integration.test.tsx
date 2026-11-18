import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App, ConfigProvider } from 'antd'
import { QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { KnowledgeDocumentDetail, MemoryPreference } from '../api/knowledgeApi'
import type { Project } from '../api/agentPlatformApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { createQueryClient } from '../app/queryClient'
import { KnowledgeLibraryPanel } from '../pages/knowledge/KnowledgeLibraryPage'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const now = '2026-10-07T01:00:00Z'
const project: Project = { id: '201', name: '小行星咖啡', status: 'ACTIVE', version: 1, createdAt: now, updatedAt: now }
const document = (id = '501'): KnowledgeDocumentDetail => ({ id, projectId: '201', name: '门店事实', sourceType: 'PASTED_TEXT', status: 'READY', version: 1, sha256: 'a'.repeat(64), contentType: 'text/plain', sizeBytes: 100, paragraphCount: 1, pageCount: null, sourceUrl: null, failureCode: null, createdAt: now, updatedAt: now, paragraphs: [{ index: 0, page: null, text: '小行星咖啡位于广州，销售咖啡和曲奇。' }], readLimitApplied: false })
const ok = (data: unknown, status = 200) => new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data }), { status, headers: { 'Content-Type': 'application/json' } })
const fail = (status = 503) => new Response(JSON.stringify({ code: 'UNAVAILABLE', message: '暂不可用', traceId: testTraceId }), { status, headers: { 'Content-Type': 'application/json' } })
const renderLibrary = (add?: 'document' | 'excerpt') => render(<ConfigProvider theme={{ token: { motion: false } }}><App><QueryClientProvider client={createQueryClient()}><KnowledgeLibraryPanel initialProjectId="201" add={add} /></QueryClientProvider></App></ConfigProvider>)
const button = (name: string, root: HTMLElement = window.document.body) => within(root).getByText(name, { selector: 'button span' }).closest('button')!
const activeDialog = () => window.document.querySelector<HTMLElement>('.ant-modal-wrap:not([style*="display: none"]) [role="dialog"]')!
const closedDialog = () => waitFor(() => expect(activeDialog()).toBeNull())
function stub(options: { unavailableProject?: boolean; staleEdit?: boolean; documents?: KnowledgeDocumentDetail[] } = {}) {
  let docs = options.documents ?? []
  let memories: MemoryPreference[] = []
  const writes: { url: string; method: string; body: Record<string, unknown> }[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'; const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : {}
    if (method !== 'GET') writes.push({ url, method, body })
    if (url === '/api/v1/projects') return options.unavailableProject ? fail() : ok([project])
    if (url === '/api/v1/projects/201/business-history') return ok({ projectId: '201', writtenArticleCount: 3, publishedArticleCount: 2, publishedPlacementCount: 4, asOf: now })
    if (url.includes('/knowledge-documents')) {
      if (method === 'GET' && url.includes('?page=')) return ok({ items: docs, page: 1, size: 20, total: docs.length, hasNext: false })
      if (method === 'GET') return ok(docs.find((item) => url.endsWith('/' + item.id)))
      if (method === 'POST') { const item = { ...document(), name: String(body.name), sourceType: body.sourceUrl ? 'LINK_PASTED_TEXT' as const : 'PASTED_TEXT' as const, sourceUrl: body.sourceUrl ? String(body.sourceUrl) : null, paragraphs: [{ index: 0, page: null, text: String(body.text) }] }; docs = [item]; return ok(item, 201) }
      if (method === 'PUT') { if (options.staleEdit) return fail(409); docs = docs.map((item) => ({ ...item, version: item.version + 1, name: String(body.name), paragraphs: [{ index: 0, page: null, text: String(body.text) }] })); return ok(docs[0]) }
      if (method === 'DELETE') { docs = []; return ok(null) }
    }
    if (url.startsWith('/api/v1/memory/preferences')) {
      if (method === 'GET') return ok(memories)
      if (method === 'POST') { const item: MemoryPreference = { id: String(601 + memories.length), projectId: body.projectId ? String(body.projectId) : null, scope: body.projectId ? 'PROJECT' : 'PERSONAL', content: String(body.content), sourceType: 'USER_INPUT', sourceMessageId: null, version: 1, createdAt: now, updatedAt: now }; memories.push(item); return ok(item, 201) }
      if (method === 'DELETE') { memories = memories.filter((item) => !url.includes('/' + item.id)); return ok(null) }
    }
    if (url.startsWith('/api/v1/conversations?')) return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
    if (url === '/api/v1/agent-installations') return ok([])
    if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 500, frozenPoints: 0, version: 1 })
    if (url === '/api/v1/notifications/unread-count') return ok({ unreadCount: 0 })
    return fail()
  })
  vi.stubGlobal('fetch', fetchMock)
  return { writes, fetchMock }
}
beforeEach(async () => {
  await import('../pages/knowledge/KnowledgeLibraryPage')
  vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt }); useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})
describe('项目资料与记忆真实接口旅程', () => {
  it('保存网页摘录，不联网假读；显示来源、版本，修订与移除带真实版本', async () => {
    const api = stub()
    renderLibrary('excerpt')
    await screen.findByText('添加项目资料', {}, { timeout: 5000 })
    const dialog = activeDialog()
    fireEvent.change(within(dialog).getByLabelText('资料名称'), { target: { value: '官网摘录' } })
    fireEvent.change(within(dialog).getByLabelText('来源网页'), { target: { value: 'https://example.com/about' } })
    fireEvent.change(within(dialog).getByLabelText('资料正文'), { target: { value: '我们提供咖啡和曲奇，营业时间以门店为准。' } })
    expect(api.writes).toHaveLength(0)
    fireEvent.click(button('保存到此项目', dialog))
    await closedDialog()
    expect(await screen.findByText('官网摘录', { selector: 'h4' })).toBeInTheDocument()
    expect(screen.getByText('摘录来源：https://example.com/about')).toBeInTheDocument()
    expect(api.writes[0]).toEqual({ url: '/api/v1/projects/201/knowledge-documents', method: 'POST', body: { name: '官网摘录', text: '我们提供咖啡和曲奇，营业时间以门店为准。', sourceUrl: 'https://example.com/about' } })
    expect(api.fetchMock.mock.calls.some(([url]) => String(url).startsWith('https://example.com'))).toBe(false)
    fireEvent.click(button('修订文本'))
    const edit = activeDialog()
    fireEvent.change(within(edit).getByLabelText('资料正文'), { target: { value: '我们提供咖啡和曲奇，营业时间以门店为准。 新增蛋糕。' } })
    fireEvent.click(button('保存到此项目', edit))
    await closedDialog()
    await screen.findByText('版本 2 · 1 段')
    expect(api.writes.find((item) => item.method === 'PUT')?.body.version).toBe(1)
    fireEvent.click(screen.getByLabelText('移除资料 官网摘录'))
    fireEvent.click(button('移除资料', activeDialog()))
    await screen.findByText('这个项目还没有资料')
    expect(api.writes.find((item) => item.method === 'DELETE')?.url).toBe('/api/v1/projects/201/knowledge-documents/501?version=2')
  })
  it('个人与项目偏好分别保存；不把偏好当发布或收费授权', async () => {
    const api = stub(); renderLibrary()
    await screen.findByText('这个项目还没有资料')
    fireEvent.click(screen.getByText('记忆与偏好', { selector: '.ant-tabs-tab-btn' }))
    const add = screen.getAllByText('添加偏好', { selector: 'button span' })
    fireEvent.click(add[0].closest('button')!); fireEvent.change(screen.getByLabelText('表达偏好'), { target: { value: '说得短一点' } })
    fireEvent.click(button('保存偏好')); await closedDialog(); await screen.findByText('说得短一点')
    fireEvent.click(screen.getAllByText('添加偏好', { selector: 'button span' })[1].closest('button')!); fireEvent.change(screen.getByLabelText('表达偏好'), { target: { value: '品牌内容不要编造优惠' } })
    fireEvent.click(button('保存偏好')); await closedDialog(); await screen.findByText('品牌内容不要编造优惠')
    expect(api.writes.map((item) => item.body)).toEqual([{ content: '说得短一点' }, { content: '品牌内容不要编造优惠', projectId: '201' }])
    expect(api.writes.every((item) => item.url === '/api/v1/memory/preferences')).toBe(true)
  })
  it('版本冲突保留用户编辑，不静默覆盖新版本', async () => {
    const api = stub({ documents: [document()], staleEdit: true }); renderLibrary()
    fireEvent.click((await screen.findByText('门店事实', { selector: 'button span' })).closest('button')!)
    fireEvent.click((await screen.findByText('修订文本', { selector: 'button span' })).closest('button')!)
    const input = screen.getByLabelText('资料正文'); fireEvent.change(input, { target: { value: '小行星咖啡位于广州，销售咖啡和曲奇。 我的修改不要丢。' } })
    fireEvent.click(button('保存到此项目'))
    await waitFor(() => expect(screen.getAllByText(/你填写的内容仍保留/).length).toBeGreaterThan(0))
    expect(input).toHaveValue('小行星咖啡位于广州，销售咖啡和曲奇。 我的修改不要丢。')
    expect(api.writes).toHaveLength(1)
  })
  it('不支持文件在上传前拦截，不发送任何写请求', async () => {
    const api = stub(); renderLibrary('document')
    await screen.findByText('添加项目资料', {}, { timeout: 5000 }); const dialog = activeDialog()
    fireEvent.click(within(dialog).getByLabelText('上传文件'))
    fireEvent.change(within(dialog).getByLabelText('选择资料文件'), { target: { files: [new File(['x'], 'orders.xlsx')] } })
    expect(within(dialog).getByText(/仅支持非空的 TXT/)).toBeInTheDocument()
    expect(button('保存到此项目', dialog)).toBeDisabled()
    expect(api.writes).toHaveLength(0)
  })
  it('项目读取失败时，不能猜测资料归属或发起任何上传', async () => {
    const api = stub({ unavailableProject: true }); renderLibrary()
    await screen.findByText('项目加载失败，暂不能选择资料归属')
    expect(button('添加资料')).toBeDisabled()
    expect(api.fetchMock.mock.calls.some(([url]) => String(url).includes('/201/knowledge-documents'))).toBe(false)
    expect(api.writes).toHaveLength(0)
  })
  it('解析失败的 PDF 不展示成已读，用户能改用文本修订', async () => {
    const failed: KnowledgeDocumentDetail = { ...document(), sourceType: 'PDF', status: 'FAILED', paragraphCount: 0, failureCode: 'KNOWLEDGE_PDF_NO_EXTRACTABLE_TEXT', paragraphs: [] }
    const api = stub({ documents: [failed] }); renderLibrary()
    fireEvent.click((await screen.findByText('门店事实', { selector: 'button span' })).closest('button')!)
    await screen.findByText('资料解析失败')
    expect(screen.getByText(/扫描 PDF 暂不支持/)).toBeInTheDocument()
    expect(api.writes).toHaveLength(0)
    expect(button('修订文本')).toBeEnabled()
  })
})

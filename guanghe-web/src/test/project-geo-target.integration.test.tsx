import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App, ConfigProvider } from 'antd'
import { QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import type { Project, ProjectGeoTarget } from '../api/agentPlatformApi'
import { ProjectGeoTargetCard } from '../pages/knowledge/ProjectGeoTargetCard'
import { KnowledgeLibraryPanel } from '../pages/knowledge/KnowledgeLibraryPage'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const now = '2026-10-07T01:00:00Z'
const project: Project = { id: '201', name: '内部咖啡测试项目', status: 'ACTIVE', version: 1, createdAt: now, updatedAt: now }
const emptyTarget: ProjectGeoTarget = { projectId: '201', version: 1, name: null, aliases: [], configured: false, updatedAt: now }
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { headers: { 'Content-Type': 'application/json' } })
const fail = (status: number) => new Response(JSON.stringify({ code: status === 409 ? 'VERSION_CONFLICT' : 'UNAVAILABLE', message: '未完成本次请求', traceId: testTraceId }), { status, headers: { 'Content-Type': 'application/json' } })
const renderTarget = (value = project) => render(<ConfigProvider theme={{ token: { motion: false } }}><App><QueryClientProvider client={createQueryClient()}><ProjectGeoTargetCard key={value.id} project={value} /></QueryClientProvider></App></ConfigProvider>)
const renderLibrary = () => render(<ConfigProvider theme={{ token: { motion: false } }}><App><QueryClientProvider client={createQueryClient()}><KnowledgeLibraryPanel initialProjectId="201" /></QueryClientProvider></App></ConfigProvider>)
function stub(options: { conflict?: boolean; conflictReadFails?: boolean; unreadable?: boolean; target?: ProjectGeoTarget } = {}) {
  let target = options.target ?? emptyTarget
  let patchCount = 0
  let unreadable = options.unreadable
  const writes: { url: string; method: string; body: Record<string, unknown> }[] = []
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input); const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : {}
    if (method !== 'GET') writes.push({ url, method, body })
    if (url === '/api/v1/projects') return ok([project, { ...project, id: '202', name: '另一个项目' }])
    if (url === '/api/v1/projects/201/business-history') return ok({ projectId: '201', writtenArticleCount: 0, publishedArticleCount: 0, publishedPlacementCount: 0, asOf: now })
    if (url.startsWith('/api/v1/projects/201/knowledge-documents?')) return ok({ items: [], page: 1, size: 20, total: 0, hasNext: false })
    if (url.startsWith('/api/v1/memory/preferences')) return ok([])
    if (url === '/api/v1/projects/201/geo-target' && method === 'GET') return unreadable ? fail(503) : ok(target)
    if (url === '/api/v1/projects/201/geo-target' && method === 'PATCH') {
      patchCount += 1
      if (options.conflict && patchCount === 1) { target = { ...target, name: '另一窗口的公开名', version: 2, configured: true }; unreadable = options.conflictReadFails; return fail(409) }
      target = { ...target, name: String(body.name), aliases: body.aliases as string[], version: Number(body.version) + 1, configured: true }
      return ok(target)
    }
    return fail(404)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { writes, fetchMock, readable: () => { unreadable = false } }
}
beforeEach(() => {
  vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('项目公开 GEO 监测对象', () => {
  it('内部项目名不自动变成品牌，明确保存公开名及别名只修改本项目目标', async () => {
    const api = stub(); renderTarget()
    fireEvent.click(await screen.findByRole('button', { name: '设置监测对象' }))
    expect(screen.getByRole('textbox', { name: '公开监测名称' })).toHaveValue('')
    expect(api.writes).toEqual([])
    fireEvent.change(screen.getByRole('textbox', { name: '公开监测名称' }), { target: { value: '小行星咖啡店' } })
    fireEvent.change(screen.getByRole('textbox', { name: '监测对象别名' }), { target: { value: '小行星咖啡\n Asteroid Coffee ' } })
    fireEvent.click(screen.getByRole('button', { name: '保存监测对象' }))
    await screen.findByText('小行星咖啡店', { selector: 'strong' })
    expect(screen.getByText('常用别名：小行星咖啡、Asteroid Coffee')).toBeInTheDocument()
    expect(api.writes).toEqual([{ url: '/api/v1/projects/201/geo-target', method: 'PATCH', body: { version: 1, name: '小行星咖啡店', aliases: ['小行星咖啡', 'Asteroid Coffee'] } }])
  })
  it('版本冲突保留输入，不自动覆盖；核对服务器最新值后才按新版本提交', async () => {
    const api = stub({ conflict: true }); renderTarget()
    fireEvent.click(await screen.findByRole('button', { name: '设置监测对象' }))
    fireEvent.change(screen.getByRole('textbox', { name: '公开监测名称' }), { target: { value: '我的公开名称' } })
    fireEvent.change(screen.getByRole('textbox', { name: '监测对象别名' }), { target: { value: '我的真实别名' } })
    fireEvent.click(screen.getByRole('button', { name: '保存监测对象' }))
    await screen.findByText(/监测对象或项目版本已在别处更新/)
    expect(screen.getByRole('textbox', { name: '公开监测名称' })).toHaveValue('我的公开名称')
    expect(screen.getByRole('textbox', { name: '监测对象别名' })).toHaveValue('我的真实别名')
    expect(screen.getByRole('button', { name: '保存监测对象' })).toBeDisabled()
    expect(screen.getByText('另一窗口的公开名')).toBeInTheDocument()
    expect(api.writes).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: '已核对当前设置，保留我的修改' }))
    fireEvent.click(screen.getByRole('button', { name: '保存监测对象' }))
    await screen.findByText('我的公开名称', { selector: 'strong' })
    expect(api.writes[1].body).toEqual({ version: 2, name: '我的公开名称', aliases: ['我的真实别名'] })
  })
  it('目标读取失败不显示成尚未设置，也不猜项目名；显式重试后才能配置', async () => {
    const api = stub({ unreadable: true }); renderTarget()
    await screen.findByText('监测对象读取失败，不能猜测公开名称')
    expect(screen.queryByText('尚未设置公开监测名称')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '设置监测对象' })).not.toBeInTheDocument()
    expect(api.writes).toEqual([])
    api.readable(); fireEvent.click(screen.getByRole('button', { name: '重新读取监测对象' }))
    await screen.findByRole('button', { name: '设置监测对象' })
  })
  it('版本冲突后读取失败仍保留编辑与真实切项目离开提醒，禁止用未知版本保存', async () => {
    const api = stub({ conflict: true, conflictReadFails: true }); renderLibrary()
    fireEvent.click(await screen.findByRole('button', { name: '补充品牌名称' }))
    fireEvent.change(screen.getByRole('textbox', { name: '对外品牌名称' }), { target: { value: '不要丢失的公开名' } })
    fireEvent.change(screen.getByRole('textbox', { name: '监测对象别名' }), { target: { value: '不要丢失的别名' } })
    fireEvent.click(screen.getByRole('button', { name: '保存品牌名称' }))
    await screen.findByText('监测对象读取失败，不能猜测公开名称')
    expect(screen.getByRole('textbox', { name: '对外品牌名称' })).toHaveValue('不要丢失的公开名')
    expect(screen.getByRole('textbox', { name: '监测对象别名' })).toHaveValue('不要丢失的别名')
    expect(document.querySelector('[data-knowledge-dirty="true"]')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存品牌名称' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: '已核对当前设置，保留我的修改' })).not.toBeInTheDocument()
    expect(screen.queryByText('服务器当前设置')).not.toBeInTheDocument()
    expect(api.writes).toHaveLength(1)
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '资料所属项目' }))
    fireEvent.click(await screen.findByText('另一个项目', { selector: '.ant-select-item-option-content' }))
    await screen.findByText('离开未保存的编辑？', { selector: '.ant-modal-confirm-title' })
    fireEvent.click(screen.getByRole('button', { name: '继续编辑' }))
    await waitFor(() => expect(screen.queryByText('离开未保存的编辑？', { selector: '.ant-modal-confirm-title' })).not.toBeInTheDocument())
    expect(screen.getByRole('textbox', { name: '对外品牌名称' })).toHaveValue('不要丢失的公开名')
    expect(api.writes).toHaveLength(1)
    api.readable(); fireEvent.click(screen.getByRole('button', { name: '重新读取监测对象' }))
    await screen.findByText('另一窗口的公开名')
    expect(screen.getByRole('button', { name: '保存品牌名称' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '已核对当前设置，保留我的修改' }))
    expect(screen.getByRole('button', { name: '保存品牌名称' })).toBeEnabled()
    expect(api.writes).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: '保存品牌名称' }))
    await screen.findByText('不要丢失的公开名', { selector: 'strong' })
    expect(api.writes[1].body).toEqual({ version: 2, name: '不要丢失的公开名', aliases: ['不要丢失的别名'] })
  })
  it('公开名与别名长度不合规时禁用保存，不发送无效请求', async () => {
    const api = stub(); renderTarget()
    fireEvent.click(await screen.findByRole('button', { name: '设置监测对象' }))
    fireEvent.change(screen.getByRole('textbox', { name: '公开监测名称' }), { target: { value: '小' } })
    expect(screen.getByRole('button', { name: '保存监测对象' })).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: '公开监测名称' }), { target: { value: '小行星咖啡店' } })
    fireEvent.change(screen.getByRole('textbox', { name: '监测对象别名' }), { target: { value: '短' } })
    expect(screen.getByRole('button', { name: '保存监测对象' })).toBeDisabled()
    expect(api.writes).toEqual([])
  })
  it('归档项目只读，不允许修改公开目标', async () => {
    const api = stub({ target: { ...emptyTarget, name: '历史公开名称', configured: true } }); renderTarget({ ...project, status: 'ARCHIVED' })
    await screen.findByText('历史公开名称', { selector: 'strong' })
    expect(screen.getByRole('button', { name: '修改监测对象' })).toBeDisabled()
    expect(api.writes).toEqual([])
  })
  it('返回了另一项目目标时拒绝显示或编辑，不能把跨项目数据当有效配置', async () => {
    const api = stub({ target: { ...emptyTarget, projectId: '999', name: '不应出现的其他项目名称', configured: true } }); renderTarget()
    await screen.findByText('监测对象读取失败，不能猜测公开名称')
    expect(screen.queryByText('不应出现的其他项目名称')).not.toBeInTheDocument()
    expect(api.writes).toEqual([])
  })
  it('离开项目后的迟到保存响应不显示成果，也不恢复已离开的编辑', async () => {
    let finish!: (response: Response) => void
    const fetchMock = vi.fn<typeof fetch>(async (_input, init) => init?.method === 'PATCH' ? new Promise<Response>((resolve) => { finish = resolve }) : ok(emptyTarget))
    vi.stubGlobal('fetch', fetchMock)
    const view = renderTarget()
    fireEvent.click(await screen.findByRole('button', { name: '设置监测对象' }))
    fireEvent.change(screen.getByRole('textbox', { name: '公开监测名称' }), { target: { value: '迟到的公开名' } })
    fireEvent.click(screen.getByRole('button', { name: '保存监测对象' }))
    await waitFor(() => expect(finish).toBeDefined())
    view.unmount()
    await act(async () => finish(ok({ ...emptyTarget, name: '迟到的公开名', configured: true, version: 2 })))
    expect(screen.queryByText('迟到的公开名')).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PATCH')).toHaveLength(1)
  })
})

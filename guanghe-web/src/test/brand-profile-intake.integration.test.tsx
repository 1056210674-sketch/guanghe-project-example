import { QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App, ConfigProvider } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Project, ProjectGeoTarget } from '../api/agentPlatformApi'
import type { MemoryEntry } from '../api/knowledgeApi'
import { createQueryClient } from '../app/queryClient'
import { storeAuthToken } from '../auth/tokenStorage'
import { KnowledgeLibraryPanel } from '../pages/knowledge/KnowledgeLibraryPage'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const now = '2026-10-09T01:00:00Z'
const projects: Project[] = [
  { id: '201', name: '客户订单 A（不是公开品牌）', status: 'ACTIVE', version: 1, createdAt: now, updatedAt: now },
  { id: '202', name: '艾尼亚科项目', status: 'ACTIVE', version: 1, createdAt: now, updatedAt: now },
]
const fact = (key: string, value: string, id = '501'): MemoryEntry => ({ id, projectId: '201', scope: 'PROJECT', kind: 'FACT', key, label: key, value, version: 2, deleted: false, sourceType: 'USER_MESSAGE', sourceMessageId: '601', sourceConversationId: '701', updatedAt: now })
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data }), { headers: { 'Content-Type': 'application/json' } })
const button = (name: string, root: HTMLElement = document.body) => within(root).getByText(name, { selector: 'button span' }).closest('button')!

function installApi(initial: MemoryEntry[] = [], options: { brand?: string; conflict?: boolean; memoryFails?: boolean } = {}) {
  let memories = initial.map((item) => ({ ...item }))
  let conflict = options.conflict
  const targets = new Map<string, ProjectGeoTarget>(projects.map((project) => [project.id, { projectId: project.id, version: 1, name: project.id === '201' ? options.brand ?? null : '艾尼亚科', aliases: [], configured: project.id !== '201' || Boolean(options.brand), updatedAt: now }]))
  const requests: { url: string; method: string; body?: Record<string, unknown> }[] = []
  const unexpected: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input), method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : undefined
    requests.push({ url, method, body })
    if (url === '/api/v1/projects') return ok(projects)
    if (/^\/api\/v1\/projects\/\d+\/geo-target$/.test(url)) {
      const projectId = url.split('/')[4]
      const target = targets.get(projectId)!
      if (method === 'PATCH' && body) targets.set(projectId, { ...target, name: String(body.name), aliases: body.aliases as string[], version: target.version + 1, configured: true })
      return ok(targets.get(projectId))
    }
    if (url.includes('/business-history')) return ok({ projectId: url.split('/')[4], writtenArticleCount: 0, publishedArticleCount: 0, publishedPlacementCount: 0, asOf: now })
    if (url.includes('/knowledge-documents?')) return ok({ items: [], page: 1, size: 20, total: 0, hasNext: false })
    if (url.startsWith('/api/v1/memory/preferences')) return ok([])
    if (url.startsWith('/api/v1/memory/entries?')) {
      if (options.memoryFails && url.includes('scope=PROJECT')) return new Response(JSON.stringify({ code: 'UNAVAILABLE', message: '读取失败' }), { status: 503 })
      const search = new URL(url, 'https://example.test').searchParams
      return ok(memories.filter((item) => item.scope === search.get('scope') && item.projectId === search.get('projectId') && (search.get('includeDeleted') === 'true' || !item.deleted)))
    }
    if (url === '/api/v1/memory/entries' && method === 'POST' && body) {
      const created = { ...fact(String(body.key), String(body.value), String(800 + memories.length)), scope: body.scope, projectId: body.projectId, kind: body.kind, label: body.label, version: 1, sourceType: 'USER_INPUT', sourceMessageId: null, sourceConversationId: null } as MemoryEntry
      memories.push(created); return ok(created)
    }
    if (/^\/api\/v1\/memory\/entries\/\d+/.test(url)) {
      const id = url.split('/')[5].split('?')[0]
      const entry = memories.find((item) => item.id === id)!
      if (method === 'PUT' && body) {
        if (conflict) {
          conflict = false; memories = memories.map((item) => item.id === id ? { ...item, value: '聊天中刚补充：咖啡和曲奇', version: item.version + 1 } : item)
          return new Response(JSON.stringify({ code: 'RESOURCE_VERSION_CONFLICT', message: '版本变化' }), { status: 409 })
        }
        const updated = { ...entry, deleted: false, label: String(body.label), value: String(body.value), version: entry.version + 1, sourceType: 'USER_INPUT' as const }
        memories = memories.map((item) => item.id === id ? updated : item); return ok(updated)
      }
      if (method === 'DELETE') { memories = memories.map((item) => item.id === id ? { ...item, deleted: true, value: null, version: item.version + 1 } : item); return ok(null) }
    }
    unexpected.push(`${method} ${url}`)
    return new Response('{}', { status: 500 })
  }))
  return { requests, unexpected, writes: () => requests.filter((item) => item.method !== 'GET') }
}

const show = () => render(<ConfigProvider theme={{ token: { motion: false } }}><App><QueryClientProvider client={createQueryClient()}><MemoryRouter><KnowledgeLibraryPanel initialProjectId="201" /></MemoryRouter></QueryClientProvider></App></ConfigProvider>)
beforeEach(() => {
  vi.clearAllMocks(); vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('逐步补齐项目品牌档案', () => {
  it('四项最低资料明确展示，关键词选填；不从项目名猜品牌、不阻止管理资料', async () => {
    const api = installApi(); show()
    await screen.findByText('基本资料 0 / 4')
    for (const name of ['对外品牌名称', '主营业务', '实际经营／服务范围', '目标曝光地域']) expect(within(screen.getByRole('region', { name })).getByText('待补充')).toBeInTheDocument()
    expect(screen.getByText('可逐步补充的偏好（选填）')).toBeInTheDocument()
    expect(button('添加资料')).toBeEnabled()
    fireEvent.click(button('补充品牌名称'))
    expect(screen.getByRole('textbox', { name: '对外品牌名称' })).toHaveValue('')
    expect(api.writes()).toEqual([])
    expect(api.unexpected).toEqual([])
  })

  it('单项保存复用既有项目记忆，区分实际服务和曝光地域；品牌名仍保存原公开名称接口', async () => {
    const api = installApi(); show()
    await screen.findByText('基本资料 0 / 4')
    fireEvent.click(button('补充品牌名称'))
    fireEvent.change(screen.getByRole('textbox', { name: '对外品牌名称' }), { target: { value: '小行星咖啡店' } })
    fireEvent.click(button('保存品牌名称'))
    await screen.findByText('基本资料 1 / 4')
    for (const [name, value] of [['主营业务', '咖啡、曲奇和蛋糕'], ['实际经营／服务范围', '广州天河门店，蛋糕仅周边配送'], ['目标曝光地域', '广东和福建，面向来广州的游客'], ['关键词／搜索需求', '广州下午茶']]) {
      fireEvent.click(screen.getByLabelText(`补充${name}`))
      fireEvent.change(screen.getByLabelText('有效记忆内容'), { target: { value } })
      fireEvent.click(button('保存到品牌档案'))
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      expect(within(screen.getByRole('region', { name })).getByText(value)).toBeInTheDocument()
    }
    expect(screen.getByText('基本资料 4 / 4')).toBeInTheDocument()
    const writes = api.writes()
    expect(writes[0]).toMatchObject({ url: '/api/v1/projects/201/geo-target', method: 'PATCH', body: { version: 1, name: '小行星咖啡店', aliases: [] } })
    expect(writes.slice(1).map((item) => item.body)).toEqual([
      { scope: 'PROJECT', projectId: '201', kind: 'FACT', key: 'brand.categories', label: '主营业务', value: '咖啡、曲奇和蛋糕' },
      { scope: 'PROJECT', projectId: '201', kind: 'FACT', key: 'brand.service_area', label: '实际经营／服务范围', value: '广州天河门店，蛋糕仅周边配送' },
      { scope: 'PROJECT', projectId: '201', kind: 'FACT', key: 'geo.target_regions', label: '目标曝光地域', value: '广东和福建，面向来广州的游客' },
      { scope: 'PROJECT', projectId: '201', kind: 'PREFERENCE', key: 'geo.target_queries', label: '关键词／搜索需求', value: '广州下午茶' },
    ])
    expect(api.unexpected).toEqual([])
  })

  it('读取对话沉淀字段、来源和版本，通用列表不重复；版本冲突保留用户输入', async () => {
    const api = installApi([fact('brand.categories', '咖啡和蛋糕')], { brand: '小行星咖啡店', conflict: true }); show()
    await screen.findByText('基本资料 2 / 4')
    expect(screen.getByRole('link', { name: '查看来源对话' })).toHaveAttribute('href', '/app/chat?conversation=701')
    fireEvent.click(screen.getByRole('tab', { name: '记忆与偏好' }))
    expect(screen.getAllByText('咖啡和蛋糕')).toHaveLength(1)
    fireEvent.click(screen.getByLabelText('修改主营业务'))
    fireEvent.change(screen.getByLabelText('有效记忆内容'), { target: { value: '咖啡、蛋糕、曲奇' } })
    fireEvent.click(button('保存到品牌档案'))
    await screen.findByText('当前记忆（版本 3）')
    expect(screen.getByLabelText('有效记忆内容')).toHaveValue('咖啡、蛋糕、曲奇')
    expect(button('保存到品牌档案')).toBeDisabled()
    fireEvent.click(button('已核对当前记忆，保留我的修改'))
    fireEvent.click(button('保存到品牌档案'))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(api.writes().map((item) => item.body?.version)).toEqual([2, 3])
    expect(within(screen.getByRole('region', { name: '主营业务' })).getByText('咖啡、蛋糕、曲奇')).toBeInTheDocument()
  })

  it('切换客户前提示未保存编辑，放弃后不会向新项目提交旧草稿', async () => {
    const api = installApi([], { brand: '小行星咖啡店' }); show()
    await screen.findByText('基本资料 1 / 4')
    fireEvent.click(screen.getByLabelText('补充主营业务'))
    fireEvent.change(screen.getByLabelText('有效记忆内容'), { target: { value: '只属于小行星的草稿' } })
    expect(document.querySelector('[data-knowledge-dirty="true"]')).toBeInTheDocument()
    fireEvent.mouseDown(screen.getByLabelText('资料所属项目'))
    fireEvent.click(await screen.findByText('艾尼亚科项目', { selector: '.ant-select-item-option-content' }))
    await screen.findByText('离开未保存的编辑？', { selector: '.ant-modal-confirm-title' })
    fireEvent.click(button('放弃编辑'))
    await screen.findByText('艾尼亚科', { selector: 'strong' })
    await waitFor(() => expect(screen.queryByLabelText('有效记忆内容')).not.toBeInTheDocument())
    expect(api.writes()).toEqual([])
    fireEvent.click(screen.getByLabelText('补充主营业务'))
    expect(screen.getByLabelText('有效记忆内容')).toHaveValue('')
    fireEvent.change(screen.getByLabelText('有效记忆内容'), { target: { value: '工业设备' } })
    fireEvent.click(button('保存到品牌档案'))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(api.writes()).toHaveLength(1)
    expect(api.writes()[0].body).toMatchObject({ projectId: '202', value: '工业设备' })
  })

  it('删除固定档案不自动恢复旧资料；重新填写从空白开始，按删除版本显式恢复', async () => {
    const api = installApi([fact('brand.categories', '删除前的内容')], { brand: '小行星咖啡店' }); show()
    await screen.findByText('基本资料 2 / 4')
    fireEvent.click(screen.getByLabelText('删除档案 主营业务'))
    fireEvent.click(button('删除记忆'))
    await screen.findByText('已移除。可以重新填写新内容，不会恢复已删除的旧值。')
    expect(screen.getByText('基本资料 1 / 4')).toBeInTheDocument()
    expect(screen.queryByText('删除前的内容')).not.toBeInTheDocument()
    expect(screen.getByLabelText('重新填写主营业务')).toBeEnabled()
    expect(api.writes()).toEqual([{ url: '/api/v1/memory/entries/501?version=2', method: 'DELETE', body: undefined }])
    fireEvent.click(screen.getByLabelText('重新填写主营业务'))
    expect(screen.getByLabelText('有效记忆内容')).toHaveValue('')
    expect(button('保存到品牌档案')).toBeDisabled()
    fireEvent.change(screen.getByLabelText('有效记忆内容'), { target: { value: '现在只经营咖啡和曲奇' } })
    fireEvent.click(button('保存到品牌档案'))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(api.writes()[1]).toEqual({ url: '/api/v1/memory/entries/501', method: 'PUT', body: { version: 3, label: '主营业务', value: '现在只经营咖啡和曲奇', restore: true } })
    expect(screen.getByText('基本资料 2 / 4')).toBeInTheDocument()
  })

  it('读取失败不宣称资料为空，也不开放覆盖写入', async () => {
    const api = installApi([], { brand: '小行星咖啡店', memoryFails: true }); show()
    await screen.findByText('品牌档案暂时无法读取，不能视为资料缺失', {}, { timeout: 10000 })
    expect(screen.queryByText('基本资料 1 / 4')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('补充主营业务')).not.toBeInTheDocument()
    expect(api.writes()).toEqual([])
  })
})

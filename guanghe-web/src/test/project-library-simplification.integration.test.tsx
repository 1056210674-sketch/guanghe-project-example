import { QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App, ConfigProvider } from 'antd'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Project } from '../api/agentPlatformApi'
import { uploadImageAsset, type AssetSummary } from '../api/assetApi'
import type { MemoryEntry, MemoryPreference } from '../api/knowledgeApi'
import { createQueryClient } from '../app/queryClient'
import { storeAuthToken } from '../auth/tokenStorage'
import { AssetCenterPage } from '../pages/assets/AssetCenterPage'
import { KnowledgeLibraryPanel } from '../pages/knowledge/KnowledgeLibraryPage'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

// Keep the actual pages, query hooks and Browser API adapters; isolate only the
// navigation shell and binary upload transport from these UI journeys.
vi.mock('../layouts/UserShell', () => ({ UserShell: ({ children }: { children: ReactNode }) => <main>{children}</main> }))
vi.mock('../api/assetApi', async () => ({ ...await vi.importActual('../api/assetApi'), uploadImageAsset: vi.fn() }))

const now = '2026-10-09T01:00:00Z'
const projects: Project[] = [
  { id: '201', name: '小行星客户项目', status: 'ACTIVE', version: 1, createdAt: now, updatedAt: now },
  { id: '202', name: '艾尼亚科客户项目', status: 'ACTIVE', version: 1, createdAt: now, updatedAt: now },
  { id: '203', name: '归档客户项目', status: 'ARCHIVED', version: 1, createdAt: now, updatedAt: now },
]
const asset = (id: string, spaceId: string, name: string): AssetSummary => ({
  id, spaceId, name, assetType: 'IMAGE', sourceType: 'GENERATED', status: 'READY',
  safetyStatus: 'NOT_REVIEWED', contentType: 'image/png', sizeBytes: 123,
  sha256: 'a'.repeat(64), width: 32, height: 32, createdAt: now, updatedAt: now,
})
const assets = [asset('301', '900', '个人创作图片'), asset('302', '201', '小行星门店图片')]
const history: MemoryPreference = { id: '401', projectId: '201', scope: 'PROJECT', content: '历史写作习惯', sourceType: 'USER_INPUT', sourceMessageId: null, version: 2, createdAt: now, updatedAt: now }
const fact: MemoryEntry = { id: '501', projectId: '201', scope: 'PROJECT', kind: 'FACT', key: 'brand.city', label: '品牌城市', value: '广州', version: 1, deleted: false, sourceType: 'USER_MESSAGE', sourceMessageId: '601', sourceConversationId: '701', updatedAt: now }
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data }), { headers: { 'Content-Type': 'application/json' } })

function installApi(projectList = projects, creativeAgentKey = 'creative') {
  let currentFact = { ...fact }
  const requests: { url: string; method: string; body?: Record<string, unknown> }[] = []
  const unexpected: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : undefined
    requests.push({ url, method, body })
    if (url === '/api/v1/projects') return ok(projectList)
    if (url === '/api/v1/agents') return ok([{ agentKey: creativeAgentKey, version: '1.0.0', name: '创作智能体', description: '', status: 'PUBLISHED', capabilities: ['chat', 'image.generate'] }])
    if (url === '/api/v1/agent-installations') return ok([{ id: '801', agentKey: creativeAgentKey, agentVersion: '1.0.0', name: '创作智能体', enabled: true, version: 1, createdAt: now }])
    if (url === '/api/v1/agent-installations/801/creative-storage' && method === 'POST') return ok({ spaceId: '900' })
    if (url.startsWith('/api/v1/assets?')) {
      const spaceId = new URL(url, 'https://example.test').searchParams.get('spaceId')
      const items = assets.filter((item) => !spaceId || item.spaceId === spaceId)
      return ok({ items, page: 1, size: 20, total: items.length, hasNext: false })
    }
    if (/^\/api\/v1\/assets\/\d+\/download-url$/.test(url)) return ok({ url: 'https://assets.example.test/image.png', expiresAt: '2099-01-01T00:00:00Z' })
    if (url.startsWith('/api/v1/articles?')) return ok({ items: [], page: 1, size: 20, total: 0, hasNext: false })
    if (/^\/api\/v1\/projects\/\d+\/geo-target$/.test(url)) {
      const projectId = url.split('/')[4]
      return ok({ projectId, version: 1, name: projectId === '201' ? '小行星咖啡店' : '艾尼亚科', aliases: [], configured: true, updatedAt: now })
    }
    if (url.includes('/business-history')) return ok({ projectId: url.split('/')[4], writtenArticleCount: 0, publishedArticleCount: 0, publishedPlacementCount: 0, asOf: now })
    if (url.includes('/knowledge-documents?')) return ok({ items: [], page: 1, size: 20, total: 0, hasNext: false })
    if (url.startsWith('/api/v1/memory/preferences')) return ok(url.endsWith('projectId=201') ? [history] : [])
    if (url.startsWith('/api/v1/memory/entries?')) return ok(url.endsWith('projectId=201') ? [currentFact] : [])
    if (url === '/api/v1/memory/entries/501' && method === 'PUT' && body) {
      currentFact = { ...currentFact, label: String(body.label), value: String(body.value), version: currentFact.version + 1 }
      return ok(currentFact)
    }
    unexpected.push(`${method} ${url}`)
    return new Response(JSON.stringify({ code: 'UNEXPECTED', message: 'Unexpected test request' }), { status: 500 })
  }))
  return { requests, unexpected }
}

function show(content: ReactNode, path = '/app/assets') {
  return render(<ConfigProvider theme={{ token: { motion: false } }}><App><QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={[path]}>{content}</MemoryRouter></QueryClientProvider></App></ConfigProvider>)
}
const button = (name: string, root: HTMLElement = document.body) => within(root).getByText(name, { selector: 'button span' }).closest('button')!
const selectOption = async (label: string, option: string) => {
  fireEvent.mouseDown(screen.getByLabelText(label))
  fireEvent.click(await screen.findByText(option, { selector: '.ant-select-item-option-content' }))
}

beforeEach(() => {
  vi.clearAllMocks(); vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('项目化品牌知识和资产交互', () => {
  it('统一品牌资料和有效记忆，历史只读；换客户后不带入旧品牌', async () => {
    const api = installApi()
    show(<KnowledgeLibraryPanel initialProjectId="201" />, '/app/knowledge')
    expect(await screen.findByRole('heading', { name: '品牌知识库' })).toBeInTheDocument()
    expect(await screen.findByText('小行星咖啡店')).toBeInTheDocument()
    expect(button('重命名项目')).toBeEnabled()
    expect(button('添加资料')).toBeEnabled()
    fireEvent.click(screen.getByRole('tab', { name: '记忆与偏好' }))
    expect(await screen.findByText('广州')).toBeInTheDocument()
    expect(screen.queryByText('历史写作习惯')).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('历史偏好（只读存档）'))
    const legacy = screen.getByText('历史写作习惯').closest('.knowledge-legacy-memories')! as HTMLElement
    expect(legacy.querySelector('button')).not.toBeInTheDocument()
    expect(within(legacy).queryByText(/重新保存/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('修改记忆 品牌城市'))
    fireEvent.change(screen.getByLabelText('有效记忆内容'), { target: { value: '广州天河' } })
    fireEvent.click(button('保存为有效记忆'))
    await screen.findByText('广州天河')
    expect(api.requests.find((item) => item.method === 'PUT')?.body).toEqual({ version: 1, label: '品牌城市', value: '广州天河', restore: false })
    expect(api.requests.filter((item) => item.method !== 'GET').every((item) => item.url.startsWith('/api/v1/memory/entries/'))).toBe(true)
    await selectOption('资料所属项目', '艾尼亚科客户项目')
    expect(await screen.findByText('艾尼亚科')).toBeInTheDocument()
    expect(screen.queryByText('小行星咖啡店')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: '记忆与偏好' }))
    expect(await screen.findByText('这个项目还没有有效记忆')).toBeInTheDocument()
    expect(screen.queryByText('广州天河')).not.toBeInTheDocument()
    expect(screen.queryByText('历史偏好（只读存档）')).not.toBeInTheDocument()
    expect(api.unexpected).toEqual([])
  })

  it('个人图片默认可见；主动筛选个人时才解析私有位置，项目筛选不用商品', async () => {
    const api = installApi()
    show(<AssetCenterPage />)
    expect(await screen.findByRole('button', { name: '查看资产 个人创作图片' })).toBeInTheDocument()
    expect(screen.queryByLabelText('按商品筛选')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('按经营空间筛选')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '文章库' })).not.toBeInTheDocument()
    expect(api.requests.some((item) => item.url.includes('creative-storage'))).toBe(false)
    await selectOption('按项目筛选', '个人素材')
    await waitFor(() => expect(api.requests.some((item) => item.url.includes('assets?') && item.url.includes('spaceId=900'))).toBe(true))
    expect(await screen.findByText('归属：个人素材')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '查看资产 小行星门店图片' })).not.toBeInTheDocument()
    await selectOption('按项目筛选', '小行星客户项目')
    expect(await screen.findByRole('button', { name: '查看资产 小行星门店图片' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '查看资产 个人创作图片' })).not.toBeInTheDocument()
    expect(api.requests.filter((item) => item.url.includes('creative-storage'))).toHaveLength(1)
    expect(api.requests.some((item) => item.url.includes('/spaces') || item.url.includes('/products') || item.url.includes('personal-materials'))).toBe(false)
    expect(api.unexpected).toEqual([])
  })

  it('非 creative 身份有生图能力且没有客户项目也能上传个人图片，不创建假项目；文件交给上传适配器', async () => {
    const user = userEvent.setup()
    const api = installApi([], 'poster')
    vi.mocked(uploadImageAsset).mockResolvedValue({ id: '303', spaceId: '900', name: '新图片', assetType: 'IMAGE', sourceType: 'UPLOADED', status: 'READY', safetyStatus: 'NOT_REVIEWED', contentType: 'image/png', sizeBytes: 4, sha256: 'b'.repeat(64), width: 1, height: 1, createdAt: now })
    show(<AssetCenterPage />)
    await screen.findByRole('button', { name: '查看资产 个人创作图片' })
    const upload = button('上传图片')
    await waitFor(() => expect(upload).toBeEnabled())
    await user.click(upload)
    expect(screen.getByText('个人素材')).toBeInTheDocument()
    const file = new File(['image'], 'personal.png', { type: 'image/png' })
    await user.upload(screen.getByLabelText('图片文件'), file)
    await user.click(button('开始上传'))
    await waitFor(() => expect(uploadImageAsset).toHaveBeenCalledWith(expect.objectContaining({ spaceId: '900', file, idempotencyKey: expect.any(String) })))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(api.requests.filter((item) => item.method !== 'GET').map((item) => item.url)).toEqual(['/api/v1/agent-installations/801/creative-storage'])
    expect(api.unexpected).toEqual([])
  })

  it('文章资产也按客户项目筛选，仍只查用户主动保存的文章', async () => {
    const api = installApi()
    show(<AssetCenterPage />, '/app/assets?tab=articles')
    await screen.findByText(/还没有保存的文章/)
    await selectOption('筛选文章项目', '小行星客户项目')
    await waitFor(() => expect(api.requests.some((item) => item.url.includes('/articles?') && item.url.includes('spaceId=201') && item.url.includes('savedToAssets=true'))).toBe(true))
    expect(api.requests.some((item) => item.url.includes('/spaces') || item.url.includes('/products'))).toBe(false)
    expect(api.unexpected).toEqual([])
  })
})

import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { CanvasWorkspace } from '../pages/canvas/CanvasWorkspacePage'
import type { CanvasView } from '../api/canvasApi'
import { activeCanvasReferences, expandGenerations, newNode, removeNodes } from '../features/canvas/canvasModel'
import { useCanvasDocument } from '../features/canvas/useCanvasDocument'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

// React Flow coordinates are exercised by its upstream kernel. Here test our HTTP/edit boundaries.
vi.mock('../features/canvas/CanvasBoard', () => ({ CanvasBoard: () => <div data-testid="canvas-board" /> }))
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { headers: { 'Content-Type': 'application/json' } })
const initial = (): CanvasView => ({ id: '81', name: '新品商拍', projectId: null, spaceId: '31', conversationId: '51', version: 1, updatedAt: '2026-10-26T08:00:00Z', document: { nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 }, prompt: '' } })
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})
afterEach(() => vi.unstubAllGlobals())
function mount(view: CanvasView) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } })
  return render(<App><QueryClientProvider client={client}><MemoryRouter><CanvasWorkspace initial={view} ownerId={userFixture.id} /></MemoryRouter></QueryClientProvider></App>)
}

it('late prompt enrichment never overwrites newer user typing', async () => {
  let server = initial()
  let release: (response: Response) => void = () => {}
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://localhost').pathname
    if (path.endsWith('/capabilities')) return ok({ image: true, video: true, referenceImages: true, imageToVideo: true, regionEdit: true, maxReferences: 3, maxImageCount: 4, maxBatchItems: 20 })
    if (path.endsWith('/batches')) return ok([])
    if (path.endsWith('/optimize')) return new Promise<Response>(resolve => { release = resolve })
    if (init?.method === 'PUT') { const saved = JSON.parse(init.body as string); server = { ...server, ...saved, version: server.version + 1 }; return ok(server) }
    throw new Error(`Unexpected ${init?.method} ${path}`)
  }))
  mount(server)
  const input = screen.getByRole('textbox', { name: '创作描述' })
  fireEvent.change(input, { target: { value: '让模特戴上帽子' } })
  fireEvent.click(screen.getByRole('button', { name: '优化描述' }))
  await waitFor(() => expect(server.document.prompt).toBe('让模特戴上帽子'))
  fireEvent.change(input, { target: { value: '保留帽子颜色，只换背景' } })
  await act(async () => release(ok({ version: 'canvas-prompt-v1', originalPrompt: '让模特戴上帽子', prompt: '让模特戴上帽子，棚拍' })))
  expect(input).toHaveValue('保留帽子颜色，只换背景')
})

it('one aggregate quote and one confirmation leave the canvas editable while tasks run', async () => {
  let server = initial(); let quotes = 0; let confirms = 0; let batchList: unknown[] = []
  const posted: Record<string, unknown>[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const path = new URL(url, 'http://localhost').pathname
    if (path.endsWith('/capabilities')) return ok({ image: true, video: true, referenceImages: true, imageToVideo: true, regionEdit: true, maxReferences: 3, maxImageCount: 4, maxBatchItems: 20 })
    const body = init?.body ? JSON.parse(init.body as string) : undefined
    if (path.endsWith('/batches') && (!init?.method || init.method === 'GET')) return ok(batchList)
    if (path.endsWith('/optimize')) return ok({ version: 'canvas-prompt-v1', originalPrompt: body.originalPrompt, prompt: body.originalPrompt + '\n简洁棚拍' })
    if (init?.method === 'PUT') { server = { ...server, ...body, version: server.version + 1 }; return ok(server) }
    if (path.endsWith('/batches') && init?.method === 'POST') {
      quotes++; posted.push(body)
      const batch = { id: '91', status: 'QUOTED', quotedPoints: 4, createdAt: server.updatedAt, items: body.items.map((input: unknown) => ({ input, task: null, draft: { id: '101', quotedPoints: 4, expiresAt: '2099-10-26T09:00:00Z' } })) }
      batchList = [batch]; return ok(batch)
    }
    if (path.endsWith('/confirm')) {
      confirms++
      const original = batchList[0] as { items: { input: unknown }[] }
      const batch = { id: '91', status: 'SUBMITTED', quotedPoints: 4, createdAt: server.updatedAt, items: original.items.map(i => ({ input: i.input, draft: null, task: { id: '111', taskType: 'IMAGE', status: 'QUEUED', stageLabel: '排队中', progress: { percent: 0 }, result: {} } })) }
      batchList = [batch]; return ok(batch)
    }
    throw new Error(`Unexpected ${init?.method} ${path}`)
  }))
  mount(server)
  const input = screen.getByRole('textbox', { name: '创作描述' })
  fireEvent.change(input, { target: { value: '帽子品牌 A，保留 Logo' } })
  await waitFor(() => expect(screen.getByRole('button', { name: '预览制作' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '预览制作' }))
  await screen.findByRole('button', { name: '开始制作 · 4 积分' })
  const confirm = screen.getByRole('button', { name: '开始制作 · 4 积分' })
  fireEvent.click(confirm); fireEvent.click(confirm)
  await waitFor(() => expect(confirms).toBe(1))
  await waitFor(() => expect(screen.queryByText('确认制作')).not.toBeInTheDocument())
  fireEvent.change(input, { target: { value: '下一款商品，换成户外风格' } })
  expect(input).toBeEnabled(); expect(input).toHaveValue('下一款商品，换成户外风格')
  expect(quotes).toBe(1)
  expect((posted[0].items as { originalPrompt: string }[])[0].originalPrompt).toBe('帽子品牌 A，保留 Logo')
})

it('serialized autosave keeps edits made while a prior save is in flight and restores after refresh', async () => {
  let server = initial(); let release: (response: Response) => void = () => {}; let writes = 0
  vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
    writes++; const body = JSON.parse(init!.body as string)
    if (writes === 1) return new Promise<Response>(resolve => { release = resolve; server = { ...server, ...body, version: 2 } })
    expect(body.version).toBe(2); server = { ...server, ...body, version: 3 }; return ok(server)
  }))
  let controls!: ReturnType<typeof useCanvasDocument>
  function Harness({ data }: { data: CanvasView }) { controls = useCanvasDocument(data, userFixture.id); return <output>{controls.document.prompt}</output> }
  const mounted = render(<Harness data={server} />)
  act(() => controls.update({ name: server.name, document: { ...server.document, prompt: '第一版' } }))
  let saving!: Promise<CanvasView | undefined>
  act(() => { saving = controls.flush() })
  await waitFor(() => expect(writes).toBe(1))
  act(() => controls.update({ name: server.name, document: { ...server.document, prompt: '第二版' } }))
  await act(async () => { release(ok(server)); await saving })
  expect(server.document.prompt).toBe('第二版'); expect(writes).toBe(2)
  mounted.unmount(); render(<Harness data={server} />)
  expect(screen.getByText('第二版')).toBeInTheDocument()
})

it('pairing expands products separately and deleting a group only removes placement', () => {
  const common = { taskType: 'IMAGE' as const, originalPrompt: '商拍', prompt: '商拍', aspectRatio: '1:1' as const, count: 2, durationSeconds: 5 }
  const items = expandGenerations(common, [{ nodeId: 'p1', assetId: '1', role: 'PRODUCT' }, { nodeId: 'p2', assetId: '2', role: 'PRODUCT' }, { nodeId: 'm', assetId: '3', role: 'PERSON' }], 'EACH_PRODUCT', ['棚拍', '户外'])
  expect(items).toHaveLength(4); expect(items.map(i => i.references.map(r => r.assetId))).toEqual([['1', '3'], ['1', '3'], ['2', '3'], ['2', '3']])
  const group = { ...newNode('GROUP', 0), id: 'g', position: { x: 100, y: 200 } }
  const child = { ...newNode('MEDIA', 0, '1'), id: 'p', parentId: 'g', position: { x: 20, y: 30 } }
  const next = removeNodes({ nodes: [group, child], edges: [], viewport: { x: 0, y: 0, zoom: 1 }, prompt: '' }, ['g'])
  expect(next.nodes[0]).toMatchObject({ id: 'p', parentId: null, position: { x: 120, y: 230 }, assetIds: ['1'] })
  const linked = { ...next, nodes: [...next.nodes, { ...newNode('PLAN', 1), id: 'plan' }], referenceNodeIds: ['plan'], edges: [{ id: 'e1', source: 'p', target: 'plan' }, { id: 'e2', source: 'plan', target: 'p' }] }
  expect(activeCanvasReferences(JSON.parse(JSON.stringify(linked)))).toEqual([{ nodeId: 'p', assetId: '1', role: 'REFERENCE' }])
})

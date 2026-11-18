import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App, ConfigProvider } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { apiMultipartUpload } from '../api/client'
import type { KnowledgeDocumentDetail } from '../api/knowledgeApi'
import { createQueryClient } from '../app/queryClient'
import { storeAuthToken } from '../auth/tokenStorage'
import { KnowledgeLibraryPanel } from '../pages/knowledge/KnowledgeLibraryPage'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

// Retain the real page, query hooks and Knowledge API adapters. Only the binary
// transport is isolated; all JSON requests still pass through the real client.
vi.mock('../api/client', async () => ({ ...await vi.importActual('../api/client'), apiMultipartUpload: vi.fn() }))

const now = '2026-10-13T01:00:00Z'
const projects = [
  { id: '201', name: '小行星客户项目', status: 'ACTIVE', version: 1, createdAt: now, updatedAt: now },
  { id: '202', name: '艾尼亚科客户项目', status: 'ACTIVE', version: 1, createdAt: now, updatedAt: now },
]
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data }), { headers: { 'Content-Type': 'application/json' } })

function installApi(status: 'READY' | 'FAILED') {
  const document: KnowledgeDocumentDetail = {
    id: '301', projectId: '201', name: '公司介绍.txt', sourceType: 'TXT', status, version: 1,
    sha256: 'a'.repeat(64), contentType: 'text/plain', sizeBytes: 80,
    paragraphCount: status === 'READY' ? 1 : 0, pageCount: null, sourceUrl: null,
    failureCode: status === 'FAILED' ? 'KNOWLEDGE_TEXT_EXTRACTION_FAILED' : null,
    createdAt: now, updatedAt: now, readLimitApplied: false,
    paragraphs: status === 'READY' ? [{ index: 0, page: null, text: '小行星咖啡，主营咖啡与烘焙，服务广州天河。' }] : [],
  }
  let uploaded = false
  const requests: { path: string; method: string }[] = []
  const unexpected: string[] = []
  vi.mocked(apiMultipartUpload).mockImplementation(async () => {
    uploaded = true
    return { data: document }
  })
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input)
    const method = init?.method ?? 'GET'
    requests.push({ path, method })
    if (method === 'GET') {
      if (path === '/api/v1/projects') return ok(projects)
      if (path === '/api/v1/projects/201/geo-target') return ok({ projectId: '201', configured: false, name: null, aliases: [], version: 0 })
      if (path === '/api/v1/projects/201/business-history') return ok({ projectId: '201', writtenArticleCount: 0, publishedArticleCount: 0, publishedPlacementCount: 0, asOf: now })
      if (path.startsWith('/api/v1/projects/201/knowledge-documents?')) return ok({ items: uploaded ? [document] : [], page: 1, size: 20, total: uploaded ? 1 : 0, hasNext: false })
      if (path === '/api/v1/projects/201/knowledge-documents/301') return ok(document)
      if (path.startsWith('/api/v1/memory/preferences') || path.startsWith('/api/v1/memory/entries?')) return ok([])
    }
    unexpected.push(`${method} ${path}`)
    return new Response(JSON.stringify({ code: 'UNEXPECTED', message: 'Unexpected test request' }), { status: 500 })
  }))
  return { document, requests, unexpected }
}

function show(onUseDocument: (document: KnowledgeDocumentDetail) => void) {
  return render(<ConfigProvider theme={{ token: { motion: false } }}><App>
    <QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={['/app?installation=101&project=201']}>
      <KnowledgeLibraryPanel initialProjectId="201" add="file" lockProject onUseDocument={onUseDocument} />
    </MemoryRouter></QueryClientProvider>
  </App></ConfigProvider>)
}

beforeEach(() => {
  vi.clearAllMocks(); vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})
afterEach(() => vi.unstubAllGlobals())

it('引导入口锁定客户项目，上传 READY 后只有显式返回聊天才回填，不自动发任务', async () => {
  const user = userEvent.setup()
  const api = installApi('READY')
  const onUseDocument = vi.fn()
  show(onUseDocument)
  const fileInput = await screen.findByLabelText('选择资料文件')
  expect(screen.getByRole('combobox', { name: '资料所属项目' })).toBeDisabled()
  expect(apiMultipartUpload).not.toHaveBeenCalled()
  expect(onUseDocument).not.toHaveBeenCalled()
  const file = new File(['小行星咖啡，主营咖啡与烘焙，服务广州天河。'], '公司介绍.txt', { type: 'text/plain' })
  await user.upload(fileInput, file)
  expect(screen.getByRole('textbox', { name: '资料名称' })).toHaveValue('公司介绍.txt')
  expect(apiMultipartUpload).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: '保存到此项目' }))
  await waitFor(() => expect(apiMultipartUpload).toHaveBeenCalledTimes(1))
  const [path, options] = vi.mocked(apiMultipartUpload).mock.calls[0]
  expect(path).toBe('/api/v1/projects/201/knowledge-documents/upload')
  expect(options.formData.get('file')).toBe(file)
  expect(options.formData.get('name')).toBe('公司介绍.txt')
  const returnButton = await screen.findByRole('button', { name: '回到聊天，整理资料' })
  await waitFor(() => expect(returnButton).toBeEnabled())
  expect(onUseDocument).not.toHaveBeenCalled()
  await user.click(returnButton)
  expect(onUseDocument).toHaveBeenCalledExactlyOnceWith(api.document)
  expect(api.requests.every(({ method }) => method === 'GET')).toBe(true)
  expect(api.requests.some(({ path: requestPath }) => /conversations|tasks|articles|monitor/.test(requestPath))).toBe(false)
  expect(api.unexpected).toEqual([])
})

it('上传受理但解析 FAILED 不提供返回聊天回填入口，也不会触发对话或任务', async () => {
  const user = userEvent.setup()
  const api = installApi('FAILED')
  const onUseDocument = vi.fn()
  show(onUseDocument)
  await user.upload(await screen.findByLabelText('选择资料文件'), new File(['无法提取的资料'], '公司介绍.txt', { type: 'text/plain' }))
  await user.click(screen.getByRole('button', { name: '保存到此项目' }))
  await waitFor(() => expect(apiMultipartUpload).toHaveBeenCalledTimes(1))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  await waitFor(() => expect(api.requests.some(({ path }) => path === '/api/v1/projects/201/knowledge-documents/301')).toBe(true))
  expect(screen.queryByRole('button', { name: '回到聊天，整理资料' })).not.toBeInTheDocument()
  expect(onUseDocument).not.toHaveBeenCalled()
  expect(api.requests.every(({ method }) => method === 'GET')).toBe(true)
  expect(api.unexpected).toEqual([])
})

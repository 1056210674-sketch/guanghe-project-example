import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App, ConfigProvider } from 'antd'
import { QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AdminPlatformContent } from '../pages/admin/AdminPlatformPage'
import type { AdminAgentDefinition, AdminGeoPlan, AdminGeoPlanSlot, AdminKnowledgeMetadata, AdminPreferenceMetadata, AdminPlatformSummary } from '../api/adminPlatformApi'
import { createQueryClient } from '../app/queryClient'
import { storeAuthToken } from '../auth/tokenStorage'
import { testAccessToken, testExpiresAt, testTraceId } from './fixtures/auth'

const now = '2026-10-07T01:00:00Z'
const definition: AdminAgentDefinition = { agentKey: 'geo', version: '1.0.0', name: 'GEO', adapterKey: 'GEO_V1', visibility: 'PUBLIC', status: 'PUBLISHED', definitionHash: 'a'.repeat(64), publishedAt: now, installationCount: 3 }
const plan: AdminGeoPlan = { id: '701', userId: '101', projectId: '201', topicId: '301', status: 'ACTIVE', scheduleType: 'HOURLY', maxPointsPerRun: 20, estimatedPointsPerRun: 3, pricingVersionId: '501', nextRunAt: now, lastScheduledSlot: null, pauseReason: null, version: 2, createdAt: now, updatedAt: now }
const slot: AdminGeoPlanSlot = { id: '801', planId: '701', userId: '101', scheduledSlot: now, status: 'SKIPPED', taskId: null, quotedPoints: 3, errorCode: 'INSUFFICIENT_POINTS', createdAt: now, updatedAt: now }
const doc: AdminKnowledgeMetadata = { id: '401', userId: '101', projectId: '201', sourceType: 'PDF', status: 'FAILED', version: 1, sha256: '', contentType: 'application/pdf', sizeBytes: 100, paragraphCount: 0, pageCount: 0, failureCode: 'PDF_NO_TEXT', createdAt: now, updatedAt: now, deletedAt: null }
const preference: AdminPreferenceMetadata = { id: '601', userId: '101', projectId: null, scope: 'PERSONAL', status: 'ACTIVE', sourceType: 'USER_INPUT', contentHash: 'a'.repeat(64), version: 1, createdAt: now, updatedAt: now, deletedAt: null }
const summary: AdminPlatformSummary = { knowledgeDocumentCount: 1, readyKnowledgeDocumentCount: 0, failedKnowledgeDocumentCount: 1, deletedKnowledgeDocumentCount: 0, activePersonalPreferenceCount: 1, activeProjectPreferenceCount: 0, deletedPreferenceCount: 0 }
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data }), { headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[]) => ({ items, page: 1, size: 20, total: items.length, hasNext: false })
function stub() {
  const calls: { url: string; method: string; body: Record<string, unknown> }[] = []
  let currentPlan = plan; let currentDefinition = definition
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input); const method = init?.method ?? 'GET'; const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : {}
    calls.push({ url, method, body })
    if (url === '/api/v1/admin/agent-definitions') return ok([currentDefinition])
    if (url.includes('/agent-definitions/geo/versions/1.0.0')) { currentDefinition = { ...currentDefinition, status: body.status as AdminAgentDefinition['status'] }; return ok(currentDefinition) }
    if (url.startsWith('/api/v1/admin/geo-plans?')) return ok(page([currentPlan]))
    if (url.includes('/701/slots?')) return ok(page([slot]))
    if (url.endsWith('/701/pause')) { currentPlan = { ...plan, status: 'PAUSED', version: 3, pauseReason: String(body.reason) }; return ok(currentPlan) }
    if (url.endsWith('/701/resume')) { currentPlan = { ...plan, version: 4 }; return ok(currentPlan) }
    if (url === '/api/v1/admin/platform-metadata') return ok(summary)
    if (url.startsWith('/api/v1/admin/knowledge-documents?')) return ok(page([doc]))
    if (url.startsWith('/api/v1/admin/memory-preferences?')) return ok(page([preference]))
    throw new Error(`Unexpected request ${url}`)
  }))
  return calls
}
const show = () => render(<ConfigProvider theme={{ token: { motion: false } }} button={{ autoInsertSpace: false }}><App><QueryClientProvider client={createQueryClient()}><AdminPlatformContent owner="1001" /></QueryClientProvider></App></ConfigProvider>)
// Direct text targets avoid jsdom repeatedly evaluating the complete Ant Design table CSS tree.
// Still exercise actual buttons, dialogs, generated requests and service responses.
const button = (name: string) => screen.getByText(name, { selector: 'button span' }).closest('button')!
const tab = (name: string) => screen.getByText(name, { selector: '.ant-tabs-tab-btn' })
beforeEach(() => { vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear(); storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt }) })
describe('管理员平台的真实元数据操作链路', () => {
  it('停用前显式确认，带原状态、范围和定义 hash，不靠前端改个标签', async () => {
    const calls = stub(); show(); fireEvent.click((await screen.findByText('停用', { selector: 'button span' })).closest('button')!)
    expect(calls.filter((item) => item.method !== 'GET')).toHaveLength(0)
    fireEvent.click(button('确认更改'))
    await screen.findByText('已停用')
    expect(calls.find((item) => item.method === 'PATCH')?.body).toEqual({ expectedStatus: 'PUBLISHED', expectedVisibility: 'PUBLIC', definitionHash: definition.definitionHash, status: 'DISABLED' })
  }, 60000)
  it('小时计划暂停和恢复带最新版本，并能查看跳过原因', async () => {
    const calls = stub(); show(); fireEvent.click(tab('自动监测'))
    await screen.findByText('每小时'); fireEvent.click(button('暂停'))
    fireEvent.change(screen.getByLabelText('暂停原因'), { target: { value: '处理账号反馈期间暂停' } })
    fireEvent.click(button('暂停监测'))
    await screen.findByText('处理账号反馈期间暂停')
    // Controlled Modal retains its contents for reopening; closure means hidden, not unmounted.
    await waitFor(() => expect(screen.getByText('暂停自动监测', { selector: '.ant-modal-title' }).closest('.ant-modal-wrap')).not.toBeVisible())
    await waitFor(() => expect(button('恢复')).toBeEnabled())
    expect(calls.find((item) => item.url.endsWith('/701/pause'))?.body).toEqual({ version: 2, reason: '处理账号反馈期间暂停' })
    fireEvent.click(button('恢复')); fireEvent.click(button('恢复监测'))
    await waitFor(() => expect(calls.find((item) => item.url.endsWith('/701/resume'))?.body).toEqual({ version: 3 }))
    await waitFor(() => expect(screen.queryByText('恢复自动监测？', { selector: '.ant-modal-confirm-title' })).not.toBeInTheDocument())
    fireEvent.click(button('执行记录')); await screen.findByText('INSUFFICIENT_POINTS')
  }, 60000)
  it('资料和记忆只有处理元数据，没有读取用户私有全文的接口', async () => {
    const calls = stub(); show(); fireEvent.click(tab('资料与记忆状态'))
    await screen.findByText('PDF_NO_TEXT'); await screen.findByText('个人范围')
    expect(calls.every((item) => item.url.startsWith('/api/v1/admin/'))).toBe(true)
    expect(calls.some((item) => /knowledge-documents\/\d|memory-preferences\/\d/.test(item.url))).toBe(false)
    expect(screen.queryByText('资料全文')).not.toBeInTheDocument()
  }, 60000)
})

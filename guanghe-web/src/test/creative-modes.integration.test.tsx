import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { storeAuthToken } from '../auth/tokenStorage'
import * as assetApi from '../api/assetApi'
import { ImageCreationPage } from '../pages/image-creation/ImageCreationPage'
import { VideoCreationPage } from '../pages/video-creation/VideoCreationPage'
import { RegionMaskPainter } from '../features/creative-modes/RegionMaskPainter'
import { storeImageCreationRecovery } from '../features/image-creation/live/imageCreationRecovery'
import { generatedImageAssetReferences } from '../features/chat/live/chatAdapters'
import type { PaidTask } from '../api/conversationApi'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const now = '2026-09-22T00:00:00Z'
const page = (items: unknown[], size = 100) => ({ items, page: 1, size, total: items.length, hasNext: false })
const ok = (data: unknown, status = 200) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { status, headers: { 'Content-Type': 'application/json' } })
const asset = (id: string) => ({ id, assetType: 'IMAGE', sourceType: 'UPLOADED', status: 'READY', safetyStatus: 'APPROVED', name: `图片 ${id}`, spaceId: '21', primaryProductId: '31', productIds: ['31'], contentType: 'image/png', sizeBytes: 1000, sha256: 'a'.repeat(64), width: 1024, height: 1024, sourceTaskId: null, createdAt: now, updatedAt: now })
const report = {
  id: '91', topicId: '81', runId: '92', taskId: '93', status: 'PARTIAL', summary: '缺少通勤场景介绍', sampleSize: 2, generatedAt: now,
  topic: { id: '81', spaceId: '21', productId: '31', name: '咖啡监测', region: 'CN' },
  methodVersion: 'geo-synthesis-v1', synthesis: { summary: '缺少通勤场景介绍', recommendations: [{ id: 'r1', title: '通勤场景', rationale: '已采集样本覆盖不足', priority: 'P1' }] },
  observations: [{ unitKey: 'q1:DOUBAO:1', questionId: 'q1', questionText: '通勤时如何选咖啡？', platformCode: 'DOUBAO', sampleNo: 1, status: 'SUCCEEDED', answerText: '通勤时关注便携与冲泡速度。' }],
}
const systemLimitations = [
  '本报告计划 2 条，成功 1 条，失败 1 条，缺失 0 条。失败和缺失不按“未提及”计算。',
  '商品已有 2 张可用身份参考图。本次方案未进行图片内容识别。',
  '不宣称监测结果代表市场份额；不得新增未经证实的功效。',
]
const plans = {
  IMAGE: { version: 'geo-visual-plan-v1', taskType: 'IMAGE', reportId: '91', recommendationId: 'r1', opportunity: '通勤便携诉求', audience: '通勤人群', message: '用真实商品展示便携', evidenceKeys: ['q1:DOUBAO:1'], scene: '地铁入口前，真实咖啡商品置于手提包旁。', visualFocus: '突出便携外观', shotBeats: [], limitations: systemLimitations, proof: `v1.${'a'.repeat(43)}` },
  VIDEO: { version: 'geo-visual-plan-v1', taskType: 'VIDEO', reportId: '91', recommendationId: null, opportunity: '通勤便携诉求', audience: '通勤人群', message: '用真实商品展示便携', evidenceKeys: ['q1:DOUBAO:1'], scene: '地铁入口前，手持真实咖啡商品展示便携。', visualFocus: '', shotBeats: ['展示通勤场景', '展示商品使用', '回到商品主体'], limitations: systemLimitations, proof: `v1.${'b'.repeat(43)}` },
}

function installApi(restoredDraft?: Record<string, unknown>, options?: { planFailure?: boolean; methodVersion?: string }) {
  const requests: Record<string, unknown>[] = []
  const planRequests: Record<string, unknown>[] = []
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const path = String(input)
    const url = new URL(path, 'https://example.com')
    if (url.pathname === '/api/v1/spaces') return ok(page([{ id: '21', name: '真实空间' }]))
    if (url.pathname === '/api/v1/spaces/21/products') return ok(page([{ id: '31', spaceId: '21', name: '真实商品', version: 1 }]))
    if (url.pathname === '/api/v1/tasks') return ok(page([], 5))
    if (path === '/api/v1/products/31/identity-assets') return ok({ productId: '31', version: 1, creativeReady: true, items: ['71', '72'].map((id, index) => ({ assetId: id, role: index ? 'DETAIL' : 'PRIMARY', sequence: index + 1, status: 'READY', width: 1024, height: 1024, contentType: 'image/png' })) })
    if (url.pathname === '/api/v1/assets') return ok(page([asset('71'), asset('72')], 20))
    if (/^\/api\/v1\/assets\/\d+\/download-url$/.test(path)) return ok({ url: `https://example.com/${path.split('/').at(-2)}.png`, expiresAt: '2099-01-01T00:00:00Z' })
    if (/^\/api\/v1\/assets\/\d+$/.test(path)) return ok(asset(path.split('/').at(-1)!))
    if (url.pathname === '/api/v1/conversations' && init?.method !== 'POST') return ok(page([]))
    if (path === '/api/v1/conversations' && init?.method === 'POST') return ok({ id: '11', title: '创作', selectedSpaceId: null, selectedProductId: null, version: 1, createdAt: now, updatedAt: now }, 201)
    if (path === '/api/v1/conversations/11') return ok({ id: '11', title: '创作', selectedSpaceId: null, selectedProductId: null, version: 1, createdAt: now, updatedAt: now })
    if (path === '/api/v1/task-drafts' || path === '/api/v1/task-drafts/41/revise') {
      const body = JSON.parse(String(init?.body))
      requests.push(body)
      return ok({ ...body, id: '41', draftSource: 'FORM', status: 'WAITING_CONFIRMATION', sourceMode: body.parameters.sourceMode, referenceAssetIds: body.parameters.referenceAssetIds,
        warnings: [], inputHash: 'a'.repeat(64), pricingVersionId: '51', quotedPoints: 5, confirmationToken: 'token', expiresAt: '2099-01-01T00:00:00Z', createdAt: now }, 201)
    }
    if (path === '/api/v1/task-drafts/41' && restoredDraft) return ok(restoredDraft)
    if (path === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    if (url.pathname === '/api/v1/geo/topics') {
      expect(url.searchParams.get('spaceId')).toBe('21')
      expect(url.searchParams.get('productId')).toBe('31')
      return ok(page([{ id: '81', name: '咖啡监测', spaceId: '21', productId: '31' }], 20))
    }
    if (url.pathname === '/api/v1/geo/reports') {
      expect(url.searchParams.get('topicId')).toBe('81')
      return ok(page([report], 20))
    }
    if (path === '/api/v1/geo/reports/91') return ok({ ...report, methodVersion: options?.methodVersion ?? report.methodVersion })
    if (path === '/api/v1/geo/reports/91/creative-plans' && init?.method === 'POST') {
      const body = JSON.parse(String(init.body)) as { taskType: 'IMAGE' | 'VIDEO' }
      planRequests.push(body)
      if (options?.planFailure) return new Response(JSON.stringify({ code: 'GEO_PLAN_FAILED', message: '规划服务暂不可用', traceId: testTraceId }), { status: 503, headers: { 'Content-Type': 'application/json' } })
      return ok({ plan: plans[body.taskType], evidence: [
        { key: 'q1:DOUBAO:1', questionText: '通勤时如何选咖啡？', platformCode: 'DOUBAO', answerExcerpt: '通勤时关注便携与冲泡速度。' },
        { key: 'q2:KIMI:1', questionText: '无关问题', platformCode: 'KIMI', answerExcerpt: '未被方案引用的回答' },
      ] })
    }
    throw new Error(`Unexpected API ${path}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { requests, planRequests, fetchMock }
}

function renderPage(kind: 'IMAGE' | 'VIDEO', initialPath = '/') {
  return render(<ConfigProvider theme={appTheme}><AntdApp><QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={[initialPath]}>
    {kind === 'IMAGE' ? <ImageCreationPage /> : <VideoCreationPage />}
  </MemoryRouter></QueryClientProvider></AntdApp></ConfigProvider>)
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})
afterEach(() => vi.restoreAllMocks())

describe('六种新创作入口', () => {
  it.each(['IMAGE', 'VIDEO'] as const)('%s 纯文本不要求商品或图片，切换模式废弃旧报价', async (kind) => {
    const user = userEvent.setup()
    const { requests, fetchMock } = installApi()
    renderPage(kind)
    await user.click(screen.getByRole('button', { name: kind === 'IMAGE' ? '纯文本生图' : '纯文本生成视频' }))
    await user.click(screen.getByLabelText('经营空间'))
    await user.click(await screen.findByText('真实空间'))
    expect(screen.queryByLabelText('商品')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText(kind === 'IMAGE' ? '画面场景' : '场景'), '窗边的一杯咖啡')
    await user.click(screen.getByRole('button', { name: /获取报价并确认/ }))
    await screen.findByText(kind === 'IMAGE' ? '图片创作任务确认' : '视频创作任务确认')
    expect(requests[0]).toMatchObject({ taskType: kind, productId: null, parameters: { sourceMode: 'TEXT_ONLY', referenceAssetIds: [], requestText: '窗边的一杯咖啡' } })
    expect(screen.getByRole('region', { name: '创作依据' })).toHaveTextContent('纯文本')
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('identity-assets'))).toBe(false)
    await user.click(screen.getByRole('button', { name: kind === 'IMAGE' ? '本次附件生图' : '商品素材生成' }))
    expect(screen.queryByRole('button', { name: '确认并执行' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /获取报价并确认/ })).toBeDisabled()
  })

  it('本次附件选取真实资产，不伪造消息且不夹带商品', async () => {
    const user = userEvent.setup()
    const { requests } = installApi()
    renderPage('IMAGE')
    await user.click(screen.getByRole('button', { name: '本次附件生图' }))
    await user.click(screen.getByLabelText('经营空间'))
    await user.click(await screen.findByText('真实空间'))
    await user.click(screen.getByRole('button', { name: '从资产中心选择' }))
    const picker = await screen.findByRole('dialog')
    await user.click(await within(picker).findByRole('button', { name: /图片 71/ }))
    await user.click(within(picker).getByRole('button', { name: /确认选择/ }))
    await user.type(screen.getByLabelText('画面场景'), '换成暖色背景')
    await user.click(screen.getByRole('button', { name: /获取报价并确认/ }))
    await screen.findByText('图片创作任务确认')
    expect(requests[0]).toMatchObject({ productId: null, parameters: { sourceMode: 'SELECTED_ASSETS', referenceAssetIds: ['71'] } })
    expect(requests[0]).not.toHaveProperty('sourceMessageId')
  })

  it('商品素材按明确选择创建参考视频，不静默选主图', async () => {
    const user = userEvent.setup()
    const { requests } = installApi()
    renderPage('VIDEO')
    await user.click(screen.getByRole('button', { name: '商品素材生成' }))
    await user.click(screen.getByLabelText('经营空间'))
    await user.click(await screen.findByText('真实空间'))
    await user.click(screen.getByLabelText('商品'))
    await user.click(await screen.findByText('真实商品'))
    await user.click(await screen.findByRole('button', { name: '选择商品素材 2' }))
    await user.type(screen.getByLabelText('场景'), '展示商品细节')
    await user.click(screen.getByRole('button', { name: /获取报价并确认/ }))
    await screen.findByText('视频创作任务确认')
    expect(requests[0]).toMatchObject({ productId: '31', parameters: { sourceMode: 'PRODUCT_MATERIALS', referenceAssetIds: ['72'] } })
  })

  it('GEO 视频必须先显式生成有证据的方案，随后才可报价', async () => {
    const user = userEvent.setup()
    const { requests, planRequests } = installApi()
    renderPage('VIDEO')
    await user.click(screen.getByRole('button', { name: '根据 GEO 洞察生成' }))
    await user.click(screen.getByLabelText('经营空间'))
    await user.click(await screen.findByText('真实空间'))
    await user.click(screen.getByLabelText('商品'))
    await user.click(await screen.findByText('真实商品'))
    await user.click(screen.getByLabelText('GEO 专题'))
    await user.click(await screen.findByText('咖啡监测'))
    await user.click(screen.getByLabelText('GEO 报告'))
    await user.click(await screen.findByText(/2026-09-22.*部分完成.*缺少通勤/))
    await screen.findByText('报告部分样本未完成，请结合覆盖范围参考。')
    expect(planRequests).toHaveLength(0)
    expect(screen.getByRole('button', { name: /获取报价并确认/ })).toBeDisabled()
    await user.type(screen.getByLabelText('创作补充要求'), '突出商品主体')
    await waitFor(() => expect(screen.getByRole('button', { name: /生成创意方案/ })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: /生成创意方案/ }))
    await screen.findByRole('region', { name: '已生成的 GEO 创意方案' })
    expect(planRequests).toEqual([{ taskType: 'VIDEO', productId: '31', userDirection: '突出商品主体' }])
    expect(screen.getByLabelText('场景')).toHaveValue(plans.VIDEO.scene)
    expect(screen.queryByLabelText('风格')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('镜头运动')).not.toBeInTheDocument()
    expect(screen.getByText('通勤时关注便携与冲泡速度。')).toBeInTheDocument()
    expect(screen.queryByText('未被方案引用的回答')).not.toBeInTheDocument()
    const plan = screen.getByRole('region', { name: '已生成的 GEO 创意方案' })
    expect(plan).not.toHaveTextContent('证据范围与创作边界')
    for (const limitation of systemLimitations) expect(plan).not.toHaveTextContent(limitation)
    expect(plan).not.toHaveTextContent('不等于已识别参考图片内容')
    expect(plan).not.toHaveTextContent('缺少实拍参考图')
    expect(requests).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: /获取报价并确认/ }))
    await screen.findByText('视频创作任务确认')
    expect(requests[0]).toMatchObject({ parameters: { sourceMode: 'GEO_REPORT', geoReportId: '91', referenceAssetIds: ['71'], creativePlan: plans.VIDEO, scene: plans.VIDEO.scene } })
    expect(requests[0].parameters).not.toHaveProperty('geoInsight')
  })

  it('从报告建议进入图片创作只导航；改场景使方案失效并禁止报价', async () => {
    const user = userEvent.setup()
    const { requests, planRequests } = installApi()
    renderPage('IMAGE', '/app/image-creation?geoReportId=91&geoRecommendationId=r1')
    expect(planRequests).toHaveLength(0)
    expect(requests).toHaveLength(0)
    await waitFor(() => expect(screen.getByRole('button', { name: '生成创意方案' })).toBeEnabled())
    expect(screen.getByRole('button', { name: /获取报价并确认/ })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '生成创意方案' }))
    await screen.findByRole('region', { name: '已生成的 GEO 创意方案' })
    expect(planRequests).toEqual([{ taskType: 'IMAGE', productId: '31', recommendationId: 'r1' }])
    for (const limitation of systemLimitations) {
      expect(screen.getByRole('region', { name: '已生成的 GEO 创意方案' })).not.toHaveTextContent(limitation)
    }
    expect(screen.getByLabelText('画面场景')).toHaveValue(plans.IMAGE.scene)
    expect(screen.queryByLabelText('风格')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('光线')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('画面场景'), '修改')
    expect(screen.queryByRole('region', { name: '已生成的 GEO 创意方案' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /获取报价并确认/ })).toBeDisabled()
    expect(requests).toHaveLength(0)
    await waitFor(() => expect(screen.getByRole('button', { name: /生成创意方案/ })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: /生成创意方案/ }))
    await screen.findByRole('region', { name: '已生成的 GEO 创意方案' })
    await user.click(screen.getByRole('button', { name: /获取报价并确认/ }))
    await screen.findByText('图片创作任务确认')
    expect(requests[0]).toMatchObject({ parameters: { sourceMode: 'GEO_REPORT', geoReportId: '91', geoRecommendationId: 'r1', creativePlan: plans.IMAGE, scene: plans.IMAGE.scene } })
  })

  it('规划失败保留输入并可重试，且不能获取报价', async () => {
    const user = userEvent.setup()
    const { requests, planRequests } = installApi(undefined, { planFailure: true })
    renderPage('IMAGE', '/app/image-creation?geoReportId=91')
    await waitFor(() => expect(screen.getByRole('button', { name: '生成创意方案' })).toBeEnabled())
    await user.type(screen.getByLabelText('创作补充要求'), '只显示真实商品')
    await user.click(screen.getByRole('button', { name: '生成创意方案' }))
    await screen.findByText('规划服务暂不可用')
    expect(screen.getByLabelText('创作补充要求')).toHaveValue('只显示真实商品')
    expect(screen.getByRole('button', { name: /获取报价并确认/ })).toBeDisabled()
    await waitFor(() => expect(screen.getByRole('button', { name: /生成创意方案/ })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: /生成创意方案/ }))
    await waitFor(() => expect(planRequests).toHaveLength(2))
    expect(requests).toHaveLength(0)
  })

  it('规则汇总报告不提供付费规划入口', async () => {
    const { planRequests } = installApi(undefined, { methodVersion: 'geo-deterministic-v1' })
    renderPage('IMAGE', '/app/image-creation?geoReportId=91')
    await screen.findByText('这份报告尚未形成智能解读，可在完整报告中基于已保存回答生成解读。')
    expect(screen.getByRole('button', { name: '生成创意方案' })).toBeDisabled()
    expect(planRequests).toHaveLength(0)
  })

  it('刷新后从已报价草稿恢复方案与引用回答，不重新调用付费规划', async () => {
    const quotedDraft = { id: '41', conversationId: '11', draftSource: 'FORM', taskType: 'IMAGE', parameterSchemaVersion: 1,
      status: 'WAITING_CONFIRMATION', spaceId: '21', productId: '31', sourceMode: 'GEO_REPORT', referenceAssetIds: ['71'],
      parameters: { requestText: plans.IMAGE.scene, mode: 'AGENT_ASSISTED', agentSummary: plans.IMAGE.scene, sourceMode: 'GEO_REPORT',
        referenceAssetIds: ['71'], geoReportId: '91', geoRecommendationId: 'r1', creativePlan: plans.IMAGE,
        scene: plans.IMAGE.scene, aspectRatio: '1:1', count: 1 },
      warnings: [], inputHash: 'a'.repeat(64), pricingVersionId: '51', quotedPoints: 5,
      confirmationToken: 'token', expiresAt: '2099-01-01T00:00:00Z', createdAt: now }
    const { planRequests } = installApi(quotedDraft)
    storeImageCreationRecovery(userFixture.id, { conversationId: '11', draftAttempt: { draftId: '41', fingerprint: 'geo-quoted', idempotencyKey: 'idem' } })
    renderPage('IMAGE')
    await screen.findByText('图片创作任务确认')
    await screen.findByText('通勤时关注便携与冲泡速度。')
    expect(screen.getByRole('region', { name: '已生成的 GEO 创意方案' })).toHaveTextContent('通勤便携诉求')
    expect(planRequests).toHaveLength(0)
  })

  it('旧 GEO 报价草稿缺少方案时隐藏确认，必须重新规划', async () => {
    const legacyDraft = { id: '41', conversationId: '11', draftSource: 'FORM', taskType: 'IMAGE', parameterSchemaVersion: 1,
      status: 'WAITING_CONFIRMATION', spaceId: '21', productId: '31', sourceMode: 'GEO_REPORT', referenceAssetIds: ['71'],
      parameters: { requestText: '旧方案', mode: 'AGENT_ASSISTED', agentSummary: '旧方案', sourceMode: 'GEO_REPORT',
        referenceAssetIds: ['71'], geoReportId: '91', scene: '旧方案', aspectRatio: '1:1', count: 1 },
      warnings: [], inputHash: 'a'.repeat(64), pricingVersionId: '51', quotedPoints: 5,
      confirmationToken: 'token', expiresAt: '2099-01-01T00:00:00Z', createdAt: now }
    const { planRequests } = installApi(legacyDraft)
    storeImageCreationRecovery(userFixture.id, { conversationId: '11', draftAttempt: { draftId: '41', fingerprint: 'geo-legacy', idempotencyKey: 'idem' } })
    renderPage('IMAGE')
    await screen.findByText('旧 GEO 草稿缺少创意方案，不能直接确认；请生成方案后重新获取报价。')
    expect(screen.queryByRole('button', { name: '确认并执行' })).not.toBeInTheDocument()
    expect(planRequests).toHaveLength(0)
  })

  it('刷新恢复局部重绘模式、底图和蒙版；移除底图后不能复用旧报价', async () => {
    const user = userEvent.setup()
    const draft = { id: '41', conversationId: '11', draftSource: 'FORM', taskType: 'IMAGE', parameterSchemaVersion: 1, status: 'WAITING_CONFIRMATION', spaceId: '21', productId: null, sourceMode: 'REGION_EDIT', referenceAssetIds: ['71'],
      parameters: { requestText: '只更换杯子的颜色', mode: 'AGENT_ASSISTED', agentSummary: '只更换杯子的颜色', sourceMode: 'REGION_EDIT', referenceAssetIds: ['71'], maskAssetId: '79', scene: '只更换杯子的颜色', aspectRatio: '1:1', count: 2 },
      warnings: [], inputHash: 'a'.repeat(64), pricingVersionId: '51', quotedPoints: 10, confirmationToken: 'token', expiresAt: '2099-01-01T00:00:00Z', createdAt: now }
    installApi(draft)
    storeImageCreationRecovery(userFixture.id, { conversationId: '11', draftAttempt: { draftId: '41', fingerprint: 'image-mask', idempotencyKey: 'idem' } })
    renderPage('IMAGE')
    await screen.findByText('图片创作任务确认')
    expect(screen.getByRole('button', { name: '局部重绘' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByLabelText('画幅')).toBeDisabled()
    const confirmation = screen.getByRole('region', { name: '付费任务确认卡：图片创作任务确认' })
    expect(within(confirmation).getByText('保持原图尺寸')).toBeInTheDocument()
    expect(within(confirmation).queryByText('1:1')).not.toBeInTheDocument()
    expect(await screen.findByRole('img', { name: '已保存的重绘区域蒙版' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '移除参考图 图片 71' }))
    expect(screen.queryByRole('button', { name: '确认并执行' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /获取报价并确认/ })).toBeDisabled()
  })

  it('上传失败不创建任务草稿', async () => {
    const user = userEvent.setup()
    const { requests } = installApi()
    vi.spyOn(assetApi, 'uploadImageAsset').mockRejectedValue(new Error('upload failed'))
    renderPage('IMAGE')
    await user.click(screen.getByRole('button', { name: '本次附件生图' }))
    await user.click(screen.getByLabelText('经营空间'))
    await user.click(await screen.findByText('真实空间'))
    await user.upload(screen.getByLabelText('上传参考图片'), new File(['png'], 'source.png', { type: 'image/png' }))
    await screen.findByText('参考图片上传失败，请重试。')
    expect(requests).toHaveLength(0)
    expect(screen.getByRole('button', { name: /获取报价并确认/ })).toBeDisabled()
  })

  it('真实涂抹确认后先上传蒙版，再以底图和蒙版标识创建重绘草稿', async () => {
    const user = userEvent.setup()
    const { requests } = installApi()
    const pixels = { data: new Uint8ClampedArray([255, 255, 255, 255, 0, 0, 0, 0]) }
    const context = { clearRect: vi.fn(), beginPath: vi.fn(), arc: vi.fn(), fill: vi.fn(), moveTo: vi.fn(), lineTo: vi.fn(), stroke: vi.fn(), getImageData: vi.fn(() => pixels), putImageData: vi.fn() }
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context as unknown as CanvasRenderingContext2D)
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((callback) => callback(new Blob(['mask'], { type: 'image/png' })))
    vi.spyOn(HTMLCanvasElement.prototype, 'getBoundingClientRect').mockReturnValue({ x: 0, y: 0, left: 0, top: 0, right: 512, bottom: 512, width: 512, height: 512, toJSON: () => ({}) })
    const upload = vi.spyOn(assetApi, 'uploadImageAsset').mockImplementation(async (input) => {
      expect(requests).toHaveLength(0)
      expect(input.file.type).toBe('image/png')
      return { id: '79', assetType: 'IMAGE', sourceType: 'UPLOADED', status: 'READY', spaceId: '21', safetyStatus: 'APPROVED', name: '蒙版', contentType: 'image/png', sizeBytes: 4, width: 512, height: 512, sha256: 'a'.repeat(64), createdAt: now }
    })
    renderPage('IMAGE')
    await user.click(screen.getByRole('button', { name: '局部重绘' }))
    await user.click(screen.getByLabelText('经营空间'))
    await user.click(await screen.findByText('真实空间'))
    await user.click(screen.getByRole('button', { name: '从资产中心选择' }))
    const picker = await screen.findByRole('dialog')
    await user.click(await within(picker).findByRole('button', { name: /图片 71/ }))
    await user.click(within(picker).getByRole('button', { name: /确认选择/ }))
    const base = await screen.findByRole('img', { name: '局部重绘底图' })
    Object.defineProperties(base, { naturalWidth: { value: 512 }, naturalHeight: { value: 512 } })
    fireEvent.load(base)
    await user.type(screen.getByLabelText('画面场景'), '只修改涂抹区域的颜色')
    expect(screen.getByRole('button', { name: /获取报价并确认/ })).toBeDisabled()
    const canvas = screen.getByRole('img', { name: '涂抹重绘区域' })
    fireEvent.pointerDown(canvas, { clientX: 100, clientY: 100, pointerId: 1 })
    fireEvent.pointerUp(canvas, { pointerId: 1 })
    await user.click(screen.getByRole('button', { name: '确认涂抹区域' }))
    await waitFor(() => expect(screen.getByRole('button', { name: /获取报价并确认/ })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: /获取报价并确认/ }))
    await screen.findByText('图片创作任务确认')
    expect(upload).toHaveBeenCalledOnce()
    expect(requests[0]).toMatchObject({ productId: null, parameters: { sourceMode: 'REGION_EDIT', referenceAssetIds: ['71'], maskAssetId: '79' } })
  })

  it('重绘永久资产不因 Provider 名称变化而从结果画廊消失', () => {
    const task = { taskType: 'IMAGE', result: { provider: 'WAN_IMAGE_EDIT', assets: [{ assetId: '71', assetType: 'IMAGE', status: 'READY' }] } } as PaidTask
    expect(generatedImageAssetReferences(task).map(({ assetId }) => assetId)).toEqual(['71'])
  })
})

describe('真实涂抹蒙版', () => {
  it('禁止空蒙版提交，导出时将抗锯齿转换为不透明二值 PNG', async () => {
    const user = userEvent.setup()
    const pixels = { data: new Uint8ClampedArray([255, 255, 255, 255, 255, 255, 255, 120, 255, 255, 255, 180]) }
    const context = { clearRect: vi.fn(), beginPath: vi.fn(), arc: vi.fn(), fill: vi.fn(), moveTo: vi.fn(), lineTo: vi.fn(), stroke: vi.fn(), drawImage: vi.fn(), getImageData: vi.fn(() => pixels), putImageData: vi.fn() }
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context as unknown as CanvasRenderingContext2D)
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((callback) => callback(new Blob(['mask'], { type: 'image/png' })))
    vi.spyOn(HTMLCanvasElement.prototype, 'getBoundingClientRect').mockReturnValue({ x: 0, y: 0, left: 0, top: 0, right: 512, bottom: 512, width: 512, height: 512, toJSON: () => ({}) })
    const onChange = vi.fn()
    render(<RegionMaskPainter imageUrl="https://example.com/base.png" onChange={onChange} />)
    const base = screen.getByRole('img', { name: '局部重绘底图' })
    Object.defineProperties(base, { naturalWidth: { value: 512 }, naturalHeight: { value: 512 } })
    fireEvent.load(base)
    expect(screen.getByRole('button', { name: '确认涂抹区域' })).toBeDisabled()
    const canvas = screen.getByRole('img', { name: '涂抹重绘区域' })
    fireEvent.pointerDown(canvas, { clientX: 100, clientY: 100, pointerId: 1 })
    fireEvent.pointerUp(canvas, { pointerId: 1 })
    await user.click(screen.getByRole('button', { name: '确认涂抹区域' }))
    await waitFor(() => expect(onChange).toHaveBeenCalledWith(expect.any(File)))
    expect(Array.from(pixels.data)).toEqual([255, 255, 255, 255, 0, 0, 0, 255, 255, 255, 255, 255])
    const output = onChange.mock.calls.at(-1)?.[0] as File
    expect(output.type).toBe('image/png')
    await user.click(screen.getByRole('button', { name: '清空涂抹' }))
    expect(onChange).toHaveBeenLastCalledWith(undefined)
    expect(screen.getByRole('button', { name: '确认涂抹区域' })).toBeDisabled()
  })
})

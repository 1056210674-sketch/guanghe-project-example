import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { storeAuthToken } from '../auth/tokenStorage'
import { GeoManualMonitoring } from '../features/geo'
import {
  getGeoManualRecoveryStorageKey,
  getGeoQuickRecoveryStorageKey,
  readGeoManualRecovery,
  readGeoQuickRecovery,
  storeGeoManualRecovery,
  storeGeoQuickRecovery,
} from '../features/geo/geoManualRecovery'
import { toFormTaskDraftRevisionRequest } from '../features/task-draft'
import { GeoManualMonitoringSection } from '../pages/geo/GeoManualMonitoringSection'
import { toGeoTaskDraftRequest } from '../pages/geo/geoManualTaskFlow'
import { useAuthStore } from '../stores/authStore'
import {
  testAccessToken,
  testExpiresAt,
  testTraceId,
  userFixture,
} from './fixtures/auth'

const now = '2026-09-08T01:00:00Z'
const topic = {
  id: '31',
  spaceId: '21',
  productId: '22',
  name: '真实监测专题',
  region: '中国大陆',
  keywords: [],
  questions: [
    {
      id: 'q1', sequence: 1, category: 'BRAND_DISCOVERY', source: 'MANUAL',
      text: '用户如何评价该品牌？', enabled: true, createdAt: now, updatedAt: now,
    },
    {
      id: 'q2', sequence: 2, category: 'BRAND_DISCOVERY', source: 'MANUAL',
      text: '该品牌适合哪些场景？', enabled: true, createdAt: now, updatedAt: now,
    },
    {
      id: 'q3', sequence: 3, category: 'BRAND_DISCOVERY', source: 'MANUAL',
      text: '该品牌有哪些核心优势？', enabled: true, createdAt: now, updatedAt: now,
    },
  ],
  competitorIds: [],
  competitorSetVersion: 1,
  version: 3,
  createdAt: now,
  updatedAt: now,
} as const

const platforms = [
  { code: 'DOUBAO', name: '豆包', configured: true, selectable: true, liveStatus: '可创建任务' },
  { code: 'QWEN', name: '通义千问', configured: true, selectable: false, liveStatus: '当前配额不可用' },
] as const

function ok(data: unknown, status = 200) {
  return new Response(
    JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function failure(status: number, message: string) {
  return new Response(
    JSON.stringify({ code: `HTTP_${status}`, message, details: {}, traceId: testTraceId }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function renderPage(
  entry = '/app/geo/manual-monitoring?topicId=31&sampleMode=DEEP',
  experience: 'MANUAL' | 'QUICK' = 'MANUAL',
) {
  return render(
    <ConfigProvider theme={appTheme}>
      <AntdApp>
        <QueryClientProvider client={createQueryClient()}>
          <MemoryRouter initialEntries={[entry]}>
            <GeoManualMonitoringSection experience={experience} />
            <RouteLocation />
          </MemoryRouter>
        </QueryClientProvider>
      </AntdApp>
    </ConfigProvider>,
  )
}

function RouteLocation() {
  const location = useLocation()
  return <output data-testid="geo-monitor-location">{location.pathname}{location.search}</output>
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('GEO 手动监测契约', () => {
  it('只提交冻结的 GEO MONITOR_RUN Schema，并原样生成修订请求', () => {
    const request = toGeoTaskDraftRequest('11', {
      topicId: '31',
      questionIds: ['q2', 'q1', 'q3'],
      platformCodes: ['DOUBAO'],
      mode: 'DEEP',
    }, {
      spaceId: '21', productId: '22', region: '中国大陆', platformCodes: ['DOUBAO'],
    })

    expect(request).toEqual({
      conversationId: '11', taskType: 'GEO', spaceId: '21', productId: '22',
      parameterSchemaVersion: 1,
      parameters: {
        mode: 'MONITOR_RUN', topicId: '31', questionIds: ['q2', 'q1', 'q3'],
        platformCodes: ['DOUBAO'], sampleMode: 'DEEP', region: '中国大陆',
      },
    })
    expect(toFormTaskDraftRevisionRequest(request)).toEqual({
      spaceId: '21', productId: '22', parameterSchemaVersion: 1,
      parameters: request.parameters,
    })
    expect(JSON.stringify(request)).not.toMatch(/provider|model|price|confirmationToken/i)
  })

  it('刷新恢复仅保存会话、草稿和幂等信息，不持久化确认令牌', () => {
    storeGeoManualRecovery(userFixture.id, {
      conversationId: '11',
      draftAttempt: { fingerprint: 'fingerprint', idempotencyKey: 'draft-key', draftId: '41' },
      confirmationAttempt: {
        draftId: '41', inputHash: 'input-hash', pricingVersionId: '51',
        idempotencyKey: 'confirm-key', taskId: '61',
      },
    })
    expect(readGeoManualRecovery(userFixture.id)).toMatchObject({
      conversationId: '11',
      draftAttempt: { draftId: '41', idempotencyKey: 'draft-key' },
      confirmationAttempt: { taskId: '61', idempotencyKey: 'confirm-key' },
    })
    expect(window.localStorage.getItem(getGeoManualRecoveryStorageKey(userFixture.id)))
      .not.toContain('confirmationToken')
    expect(readGeoManualRecovery('another-user')).toEqual({})

    storeGeoQuickRecovery(userFixture.id, { conversationId: 'quick-conversation' })
    expect(getGeoQuickRecoveryStorageKey(userFixture.id)).not.toBe(
      getGeoManualRecoveryStorageKey(userFixture.id),
    )
    expect(readGeoQuickRecovery(userFixture.id)).toEqual({
      conversationId: 'quick-conversation',
    })
    expect(readGeoManualRecovery(userFixture.id).conversationId).toBe('11')
  })

  it('按服务端顺序展示正式问题和平台，禁用不可选择的平台', async () => {
    const user = userEvent.setup()
    const onPrepare = vi.fn()
    render(
      <GeoManualMonitoring
        loading={false}
        error={null}
        topics={[{ id: '31', name: '真实专题', keywords: [], platformCodes: [] }]}
        questionGroups={[]}
        questions={topic.questions.map((question) => ({
          id: question.id, text: question.text, typeLabel: '品牌发现',
          source: 'MANUAL', relatedKeywords: [], status: 'ENABLED',
        }))}
        platforms={[
          { code: 'DOUBAO', name: '豆包', availability: 'AVAILABLE' },
          { code: 'QWEN', name: '通义千问', availability: 'MAINTENANCE', availabilityLabel: '当前配额不可用' },
        ]}
        selectedTopicId="31"
        selectedQuestionIds={['q2', 'q1', 'q3']}
        selectedPlatformCodes={['DOUBAO', 'QWEN']}
        mode="STANDARD"
        onPrepareConfirmation={onPrepare}
      />,
    )

    const firstQuestion = screen.getByText('用户如何评价该品牌？')
    const secondQuestion = screen.getByText('该品牌适合哪些场景？')
    const firstPlatform = screen.getAllByText('豆包').at(-1) as HTMLElement
    const secondPlatform = screen.getAllByText('通义千问').at(-1) as HTMLElement
    expect(firstQuestion.compareDocumentPosition(secondQuestion) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(firstPlatform.compareDocumentPosition(secondPlatform) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByText('当前配额不可用')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /通义千问/ })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: /准备付费确认/ }))
    expect(onPrepare).toHaveBeenCalledWith({
      topicId: '31', questionGroupId: undefined,
      questionIds: ['q2', 'q1', 'q3'], platformCodes: ['DOUBAO'], mode: 'STANDARD',
    })
  })

  it('快速监测自动带入最小有效范围，但仍需用户确认报价', async () => {
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url === '/api/v1/geo/topics?page=1&size=100') return ok({
        items: [{ id: '31', spaceId: '21', productId: '22', name: '真实监测专题', region: '中国大陆', version: 3, createdAt: now, updatedAt: now }],
        page: 1, size: 100, total: 1, hasNext: false,
      })
      if (url === '/api/v1/geo/topics/31') return ok(topic)
      if (url === '/api/v1/geo/platforms') return ok(platforms)
      if (url === '/api/v1/spaces?page=1&size=100') return ok({
        items: [{ id: '21', name: '真实经营空间' }], page: 1, size: 100, total: 1, hasNext: false,
      })
      if (url === '/api/v1/spaces/21/products?page=1&size=100') return ok({
        items: [{ id: '22', spaceId: '21', name: '真实商品', aliases: [], version: 1 }],
        page: 1, size: 100, total: 1, hasNext: false,
      })
      throw new Error(`Unexpected Browser API request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    renderPage('/app/geo/diagnostics?topicId=31', 'QUICK')

    expect(await screen.findByRole('heading', { name: '快速 GEO 监测' }))
      .toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /用户如何评价/ })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /适合哪些场景/ })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /核心优势/ })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /豆包/ })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /通义千问/ })).toBeDisabled()
    expect(screen.getByRole('radio', { name: /标准模式/ })).toBeChecked()
    expect(screen.getByRole('button', { name: /准备付费确认/ })).toBeEnabled()
  })
})

describe('GEO 手动监测页面真实旅程', () => {
  it.each(['MANUAL', 'QUICK'] as const)('无商品的项目缺公开目标时，%s 在创建空会话或报价之前带项目进入现有资料库', async (experience) => {
    const user = userEvent.setup()
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url === '/api/v1/geo/topics?page=1&size=100') return ok({ items: [{ ...topic, productId: null }], page: 1, size: 100, total: 1, hasNext: false })
      if (url === '/api/v1/geo/topics/31') return ok({ ...topic, productId: null })
      if (url === '/api/v1/geo/platforms') return ok(platforms)
      if (url === '/api/v1/spaces?page=1&size=100' || url === '/api/v1/spaces/21/products?page=1&size=100') return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
      if (url === '/api/v1/projects/21/geo-target') return ok({ projectId: '21', version: 1, name: null, aliases: [], configured: false, updatedAt: now })
      throw new Error(`Unexpected Browser API request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage('/app/geo/manual-monitoring?topicId=31', experience)
    await screen.findByRole('checkbox', { name: /用户如何评价/ })
    if (experience === 'MANUAL') {
      for (const name of [/用户如何评价/, /适合哪些场景/, /核心优势/, /豆包/]) await user.click(screen.getByRole('checkbox', { name }))
    }
    await waitFor(() => expect(screen.getByRole('button', { name: /准备付费确认/ })).toBeEnabled())
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/geo-target'))).toBe(false)
    await user.click(screen.getByRole('button', { name: /准备付费确认/ }))
    await waitFor(() => expect(screen.getByTestId('geo-monitor-location')).toHaveTextContent('/app/knowledge?project=21'))
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method && init.method !== 'GET')).toHaveLength(0)
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/conversations') || String(url).includes('/task-drafts'))).toBe(false)
  })

  it('无商品项目的目标读取失败时保留问题选择，不创建会话或付费草稿', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url === '/api/v1/geo/topics?page=1&size=100') return ok({ items: [{ ...topic, productId: null }], page: 1, size: 100, total: 1, hasNext: false })
      if (url === '/api/v1/geo/topics/31') return ok({ ...topic, productId: null })
      if (url === '/api/v1/geo/platforms') return ok(platforms)
      if (url === '/api/v1/spaces?page=1&size=100' || url === '/api/v1/spaces/21/products?page=1&size=100') return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
      if (url === '/api/v1/projects/21/geo-target') return failure(503, '暂时无法读取监测对象')
      throw new Error(`Unexpected Browser API request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage('/app/geo/manual-monitoring?topicId=31', 'QUICK')
    await waitFor(() => expect(screen.getByRole('button', { name: /准备付费确认/ })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: /准备付费确认/ }))
    await screen.findByText('暂时无法读取监测对象')
    expect(screen.getByTestId('geo-monitor-location')).toHaveTextContent('/app/geo/manual-monitoring?topicId=31')
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method && init.method !== 'GET')).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: /重新加载/ }))
    expect(await screen.findByRole('checkbox', { name: /用户如何评价/ })).toBeChecked()
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/geo-target'))).toHaveLength(1)
  })

  it('创建一个 FORM Draft、修订后幂等确认，并展示真实报告入口', async () => {
    const user = userEvent.setup()
    const conversation = {
      id: '11', title: 'GEO 手动监测', selectedSpaceId: null,
      selectedProductId: null, version: 1, lastMessageAt: null,
      createdAt: now, updatedAt: now,
    }
    const draft = {
      id: '41', draftSource: 'FORM', taskType: 'GEO', parameterSchemaVersion: 1,
      status: 'WAITING_CONFIRMATION', spaceId: '21', productId: '22',
      parameters: {
        mode: 'MONITOR_RUN', topicId: '31', questionIds: ['q1', 'q2', 'q3'],
        platformCodes: ['DOUBAO'], sampleMode: 'DEEP', region: '中国大陆',
      },
      warnings: ['最终按实际成功结果结算'], inputHash: 'a'.repeat(64),
      pricingVersionId: '51', quotedPoints: 20, confirmationToken: 'opaque-token',
      expiresAt: '2099-01-01T00:00:00Z', createdAt: now,
    } as const
    const revised = {
      ...draft, id: '42', previousDraftId: '41',
      parameters: { ...draft.parameters, sampleMode: 'STANDARD' as const },
      inputHash: 'b'.repeat(64), confirmationToken: 'revised-token',
    }
    const taskResult = {
      id: '61', taskType: 'GEO', status: 'PARTIAL', stageCode: 'COMPLETED',
      stageLabel: '监测部分完成', spaceId: '21', productId: '22',
      quotedPoints: 20, settledPoints: 15, frozenPoints: 0, errorCode: null,
      result: {
        runId: '71', reportId: '81', status: 'PARTIAL', expectedUnits: 1,
        succeededUnits: 1, failedUnits: 0, missingUnits: 0,
        settledPoints: 15, releasedPoints: 5,
      },
      createdAt: now, updatedAt: now,
    } as const
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url === '/api/v1/geo/topics?page=1&size=100') return ok({
        items: [{ id: '31', spaceId: '21', productId: '22', name: '真实监测专题', region: '中国大陆', version: 3, createdAt: now, updatedAt: now }],
        page: 1, size: 100, total: 1, hasNext: false,
      })
      if (url === '/api/v1/geo/topics/31') return ok(topic)
      if (url === '/api/v1/geo/platforms') return ok(platforms)
      if (url === '/api/v1/spaces?page=1&size=100') return ok({
        items: [{ id: '21', name: '真实经营空间' }], page: 1, size: 100, total: 1, hasNext: false,
      })
      if (url === '/api/v1/spaces/21/products?page=1&size=100') return ok({
        items: [{ id: '22', spaceId: '21', name: '真实商品', aliases: [], version: 1 }],
        page: 1, size: 100, total: 1, hasNext: false,
      })
      if (url === '/api/v1/conversations?page=1&size=100') return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
      if (url === '/api/v1/conversations' && init?.method === 'POST') return ok(conversation, 201)
      if (url === '/api/v1/conversations/11' && init?.method === 'GET') return ok(conversation)
      if (url === '/api/v1/task-drafts' && init?.method === 'POST') return ok(draft, 201)
      if (url === '/api/v1/task-drafts/41' && init?.method === 'GET') return ok(draft)
      if (url === '/api/v1/task-drafts/41/revisions' && init?.method === 'POST') return ok(revised, 201)
      if (url === '/api/v1/task-drafts/42' && init?.method === 'GET') return ok(revised)
      if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
      if (url === '/api/v1/task-drafts/42/confirm' && init?.method === 'POST') return ok({ taskId: '61', status: 'QUEUED', quotedPoints: 20, frozenPoints: 20, createdAt: now }, 202)
      if (url === '/api/v1/tasks/61') return ok(taskResult)
      if (url === '/api/v1/tasks/61/events') return ok([])
      throw new Error(`Unexpected Browser API request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    await user.click(await screen.findByRole('checkbox', { name: /用户如何评价/ }))
    await user.click(screen.getByRole('checkbox', { name: /适合哪些场景/ }))
    await user.click(screen.getByRole('checkbox', { name: /核心优势/ }))
    await user.click(screen.getByRole('checkbox', { name: /豆包/ }))
    await user.dblClick(screen.getByRole('button', { name: /准备付费确认/ }))
    expect(await screen.findByText('GEO 监测任务确认')).toBeInTheDocument()

    const createCalls = fetchMock.mock.calls.filter(
      ([url, init]) => String(url) === '/api/v1/task-drafts' && init?.method === 'POST',
    )
    expect(createCalls).toHaveLength(1)
    expect(JSON.parse(String(createCalls[0]?.[1]?.body))).toMatchObject({
      taskType: 'GEO', spaceId: '21', productId: '22', parameterSchemaVersion: 1,
      parameters: {
        mode: 'MONITOR_RUN', topicId: '31', questionIds: ['q1', 'q2', 'q3'],
        platformCodes: ['DOUBAO'], sampleMode: 'DEEP', region: '中国大陆',
      },
    })

    await user.click(screen.getByRole('button', { name: '返回修改' }))
    await user.click(screen.getByRole('radio', { name: /标准模式/ }))
    await user.click(screen.getByRole('button', { name: /准备付费确认/ }))
    expect((await screen.findAllByText('标准模式')).length).toBeGreaterThanOrEqual(2)
    expect(fetchMock.mock.calls.filter(([url]) => String(url) === '/api/v1/task-drafts/41/revisions')).toHaveLength(1)

    await user.dblClick(screen.getByRole('button', { name: '确认开始 · 20 积分' }))
    expect(await screen.findByText('任务已创建')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: '查看 GEO 报告' })).toBeInTheDocument()
    expect(screen.getByText('已退回积分')).toBeInTheDocument()
    expect(fetchMock.mock.calls.filter(([url]) => String(url) === '/api/v1/task-drafts/42/confirm')).toHaveLength(1)
    expect(fetchMock.mock.calls.filter(([url]) => String(url) === '/api/v1/conversations')).toHaveLength(1)
  })

  it('Java 422 错误展示真实 message 和 traceId，并保留用户选择', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url === '/api/v1/geo/topics?page=1&size=100') return ok({ items: [{ id: '31', spaceId: '21', productId: '22', name: '真实监测专题', region: '中国大陆', version: 3, createdAt: now, updatedAt: now }], page: 1, size: 100, total: 1, hasNext: false })
      if (url === '/api/v1/geo/topics/31') return ok(topic)
      if (url === '/api/v1/geo/platforms') return ok(platforms)
      if (url === '/api/v1/spaces?page=1&size=100') return ok({ items: [{ id: '21', name: '真实经营空间' }], page: 1, size: 100, total: 1, hasNext: false })
      if (url === '/api/v1/spaces/21/products?page=1&size=100') return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
      if (url === '/api/v1/conversations?page=1&size=100') return ok({ items: [{ id: '11', title: 'GEO 手动监测' }], page: 1, size: 100, total: 1, hasNext: false })
      if (url === '/api/v1/task-drafts' && init?.method === 'POST') return failure(422, '监测参数不符合当前规则')
      throw new Error(`Unexpected Browser API request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage('/app/geo/manual-monitoring?topicId=31')

    const question = await screen.findByRole('checkbox', { name: /用户如何评价/ })
    await user.click(question)
    await user.click(screen.getByRole('checkbox', { name: /适合哪些场景/ }))
    await user.click(screen.getByRole('checkbox', { name: /核心优势/ }))
    await user.click(screen.getByRole('checkbox', { name: /豆包/ }))
    await user.click(screen.getByRole('button', { name: /准备付费确认/ }))
    expect(await screen.findByText('监测参数不符合当前规则')).toBeInTheDocument()
    expect(screen.queryByText(`traceId：${testTraceId}`)).not.toBeInTheDocument()
    expect(question).toBeChecked()
  })
})

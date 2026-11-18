import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  archiveGeoReport,
  confirmGeoPlan,
  createGeoArticleWorkflow,
  createGeoPlanDraft,
  createGeoTopic,
  deleteGeoTopic,
  getGeoReport,
  getGeoRun,
  getGeoRunProgress,
  getGeoTopic,
  getGeoVisibilityTrend,
  listGeoPlatforms,
  listGeoPlans,
  listGeoReports,
  listGeoRuns,
  listGeoTopics,
  pauseGeoPlan,
  replaceGeoTopicCompetitors,
  replaceGeoTopicKeywords,
  replaceGeoTopicQuestions,
  resumeGeoPlan,
  restoreGeoReport,
  updateGeoPlan,
  updateGeoTopic,
} from '../api/geoApi'
import type { GeoTopic } from '../api/geoApi'
import { ApiError } from '../api/apiError'
import { storeAuthToken } from '../auth/tokenStorage'
import { geoKeys } from '../features/geo'
import { toGeoDeleteError } from '../pages/geo/viewAdapters'

const traceId = '0123456789abcdef0123456789abcdef'
const topicId = '91000000000000001'
const spaceId = '91000000000000002'
const productId = '91000000000000003'
const competitorIds = ['91000000000000004', '91000000000000005']

const topic: GeoTopic = {
  id: topicId,
  spaceId,
  productId,
  name: '测试专题',
  region: '中国大陆',
  keywords: [
    { id: 'keyword-1', sequence: 1, type: 'CORE', text: '核心词' },
    { id: 'keyword-2', sequence: 2, type: 'SCENE', text: '场景词' },
  ],
  questions: [
    {
      id: 'question-1',
      sequence: 1,
      category: 'BRAND_DISCOVERY',
      source: 'MANUAL',
      text: '测试问题？',
      enabled: true,
      createdAt: '2026-09-08T10:00:00Z',
      updatedAt: '2026-09-08T10:00:00Z',
    },
  ],
  competitorIds,
  competitorSetVersion: 2,
  version: 7,
  createdAt: '2026-09-08T10:00:00Z',
  updatedAt: '2026-09-08T10:00:00Z',
}

const page = {
  items: [
    {
      id: topic.id,
      spaceId: topic.spaceId,
      productId: topic.productId,
      name: topic.name,
      region: topic.region,
      version: topic.version,
      createdAt: topic.createdAt,
      updatedAt: topic.updatedAt,
    },
  ],
  page: 2,
  size: 50,
  total: 1,
  hasNext: false,
}

type FetchMock = ReturnType<typeof vi.fn<typeof fetch>>

function successResponse(data: unknown, status = 200) {
  return new Response(
    JSON.stringify({ code: 'OK', message: 'success', data, traceId }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function errorResponse(status: number, code: string) {
  return new Response(
    JSON.stringify({
      code,
      message: `GEO error ${status}`,
      details: { field: 'version' },
      traceId,
    }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function stubFetch(...responses: Response[]): FetchMock {
  const mock = vi.fn<typeof fetch>()
  responses.forEach((response) => mock.mockResolvedValueOnce(response))
  vi.stubGlobal('fetch', mock)
  return mock
}

function bodyAt(mock: FetchMock, index: number) {
  return JSON.parse(String(mock.mock.calls[index]?.[1]?.body)) as unknown
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  storeAuthToken({ accessToken: 'geo-browser-token', expiresAt: '2099-01-01T00:00:00Z' })
})

describe('GEO Foundation Browser API', () => {
  it('趋势只读Java Browser接口，时间与题型隔离缓存，不创建付费任务', async () => {
    const data = { topicId, days: 90, questionScope: 'BRAND_VALIDATION', platforms: [], history: [] }
    const fetchMock = stubFetch(successResponse(data))
    expect(await getGeoVisibilityTrend(topicId, { days: 90, questionScope: 'BRAND_VALIDATION' })).toEqual(data)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0][0])).toContain(`/api/v1/geo/topics/${topicId}/visibility-trend?days=90&questionScope=BRAND_VALIDATION`)
    expect(fetchMock.mock.calls[0][1]?.method).toBe('GET')
    const key = geoKeys.forOwner('owner-a').visibilityTrend(topicId, { days: 90, questionScope: 'BRAND_VALIDATION' })
    expect(key).not.toEqual(geoKeys.forOwner('owner-b').visibilityTrend(topicId, { days: 90, questionScope: 'BRAND_VALIDATION' }))
    expect(key).not.toEqual(geoKeys.forOwner('owner-a').visibilityTrend(topicId, { days: 30, questionScope: 'DISCOVERY' }))
  })

  it('生成类型包含 GEO Foundation 与只读运行报告操作', () => {
    const modules = import.meta.glob('../api/generated/browser-api.ts', {
      eager: true,
      import: 'default',
      query: '?raw',
    }) as Record<string, string>
    const source = Object.values(modules).join('\n')

    for (const operation of [
      'listGeoTopics',
      'createGeoTopic',
      'getGeoTopic',
      'updateGeoTopic',
      'deleteGeoTopic',
      'replaceGeoTopicKeywords',
      'replaceGeoTopicQuestions',
      'replaceGeoTopicCompetitors',
      'listGeoPlatforms',
      'listGeoRuns',
      'getGeoRun',
      'getGeoRunProgress',
      'listGeoReports',
      'getGeoReport',
      'createGeoArticleWorkflow',
      'createGeoPlanDraft',
      'listGeoPlans',
      'confirmGeoPlan',
      'updateGeoPlan',
      'pauseGeoPlan',
      'resumeGeoPlan',
    ]) {
      expect(source).toContain(operation)
    }
    expect(source).toContain('"CORE" | "DISTILLED" | "BRAND" | "SCENE"')
    expect(source).toContain('category: "BRAND_DISCOVERY"')
    expect(source).toContain('source: "MANUAL"')
  })

  it('自动计划授权、确认、列表、编辑、暂停和恢复使用冻结Browser路径', async () => {
    const draft = {
      draftId: 'plan-draft-1', inputHash: 'input-hash', pricingVersionId: 'pricing-1',
      estimatedPointsPerRun: 21, confirmationToken: 'memory-only-token', expiresAt: '2026-09-10T00:00:00Z',
    }
    const plan = {
      id: 'plan-1', topicId, spaceId, productId, name: '每日监测', status: 'ACTIVE' as const,
      schedule: { type: 'DAILY' as const, timeZone: 'Asia/Shanghai', localTime: '09:00' },
      questionIds: ['q1', 'q2', 'q3'], platformCodes: ['DOUBAO' as const], sampleMode: 'STANDARD' as const,
      estimatedPointsPerRun: 21, maxPointsPerRun: 30, pricingVersionId: 'pricing-1',
      nextRunAt: '2026-09-10T01:00:00Z', lastScheduledSlot: null, pauseReason: null,
      version: 1, createdAt: '2026-09-09T00:00:00Z', updatedAt: '2026-09-09T00:00:00Z',
    }
    const mock = stubFetch(
      successResponse(draft, 201), successResponse(plan, 201),
      successResponse({ items: [plan], page: 2, size: 20, total: 1, hasNext: false }),
      successResponse({ ...plan, name: '改名后', version: 2 }),
      successResponse({ ...plan, version: 2 }),
      successResponse({ ...plan, status: 'PAUSED', version: 2 }),
      successResponse({ ...plan, version: 3 }),
    )
    const createRequest = {
      topicId, name: '每日监测', schedule: plan.schedule,
      questionIds: plan.questionIds, platformCodes: plan.platformCodes,
      sampleMode: plan.sampleMode, maxPointsPerRun: 30,
    }
    await createGeoPlanDraft(createRequest, 'draft-idempotency')
    await confirmGeoPlan({
      draftId: draft.draftId, inputHash: draft.inputHash,
      pricingVersionId: draft.pricingVersionId, confirmationToken: draft.confirmationToken,
    }, 'confirm-idempotency')
    await listGeoPlans({ status: 'ACTIVE', topicId, page: 2, size: 20 })
    await updateGeoPlan(plan.id, { version: 1, name: '改名后' })
    await updateGeoPlan(plan.id, {
      version: 1, draftId: draft.draftId, inputHash: draft.inputHash,
      pricingVersionId: draft.pricingVersionId, confirmationToken: draft.confirmationToken,
    })
    await pauseGeoPlan(plan.id, { version: 1 })
    await resumeGeoPlan(plan.id, { version: 2 })

    expect(mock.mock.calls.map((call) => call[0])).toEqual([
      '/api/v1/geo/plan-drafts', '/api/v1/geo/plans',
      `/api/v1/geo/plans?status=ACTIVE&topicId=${topicId}&page=2&size=20`,
      `/api/v1/geo/plans/${plan.id}`, `/api/v1/geo/plans/${plan.id}`,
      `/api/v1/geo/plans/${plan.id}/pause`,
      `/api/v1/geo/plans/${plan.id}/resume`,
    ])
    expect(new Headers(mock.mock.calls[0]?.[1]?.headers).get('Idempotency-Key')).toBe('draft-idempotency')
    expect(new Headers(mock.mock.calls[1]?.[1]?.headers).get('Idempotency-Key')).toBe('confirm-idempotency')
    expect(bodyAt(mock, 0)).toEqual(createRequest)
    expect(bodyAt(mock, 3)).toEqual({ version: 1, name: '改名后' })
    expect(bodyAt(mock, 4)).toEqual({
      version: 1, draftId: draft.draftId, inputHash: draft.inputHash,
      pricingVersionId: draft.pricingVersionId, confirmationToken: draft.confirmationToken,
    })
  })

  it('过期授权返回410后保留后端message和traceId', async () => {
    stubFetch(errorResponse(410, 'CONFIRMATION_EXPIRED'))
    await expect(confirmGeoPlan({
      draftId: 'draft-expired', inputHash: 'input-hash',
      pricingVersionId: 'pricing-1', confirmationToken: 'expired-token',
    }, 'confirm-expired')).rejects.toMatchObject({
      status: 410, code: 'CONFIRMATION_EXPIRED', message: 'GEO error 410', traceId,
    } satisfies Partial<ApiError>)
  })

  it('平台、运行和报告使用正式路径、筛选与分页', async () => {
    const runId = '91000000000000101'
    const reportId = '91000000000000102'
    const mock = stubFetch(
      successResponse([]),
      successResponse({ items: [], page: 2, size: 20, total: 0, hasNext: false }),
      successResponse({ items: [], page: 3, size: 50, total: 0, hasNext: false }),
      successResponse({ id: runId }),
      successResponse({
        runId,
        taskId: '91000000000000105',
        status: 'RUNNING',
        stageCode: 'GEO_SAMPLING',
        percent: 50,
        completedUnits: 2,
        totalUnits: 4,
        succeededUnits: 2,
        failedUnits: 0,
        missingUnits: 0,
        reportId: null,
        terminal: false,
        retryable: false,
        updatedAt: '2026-09-09T00:00:00Z',
        finishedAt: null,
      }),
      successResponse({ id: reportId, runId }),
    )

    await listGeoPlatforms()
    await listGeoRuns({ topicId, status: 'PARTIAL', page: 2, size: 20 })
    await listGeoReports({ topicId, page: 3, size: 50 })
    await getGeoRun(runId)
    await getGeoRunProgress(runId)
    await getGeoReport(reportId)

    expect(mock.mock.calls.map((call) => call[0])).toEqual([
      '/api/v1/geo/platforms',
      `/api/v1/geo/runs?topicId=${topicId}&status=PARTIAL&page=2&size=20`,
      `/api/v1/geo/reports?topicId=${topicId}&page=3&size=50`,
      `/api/v1/geo/runs/${runId}`,
      `/api/v1/geo/runs/${runId}/progress`,
      `/api/v1/geo/reports/${reportId}`,
    ])
  })

  it('GEO报告到文章工作流由Java单接口原子创建并携带幂等键', async () => {
    const reportId = '91000000000000102'
    const conversationId = '91000000000000103'
    const suggestionId = `${reportId}:recommendation:1`
    const workflow = {
      workflowId: '91000000000000104',
      reportId,
      suggestionId,
      conversationId,
      articleId: '91000000000000105',
      draftId: '91000000000000104',
      reused: false,
      draft: { id: '91000000000000104' },
    }
    const mock = stubFetch(successResponse(workflow, 201))

    const result = await createGeoArticleWorkflow(
      reportId,
      { suggestionId, conversationId },
      '12345678-1234-4234-8234-123456789abc',
    )

    expect(mock.mock.calls[0]?.[0]).toBe(
      `/api/v1/geo/reports/${reportId}/article-workflows`,
    )
    expect(mock.mock.calls[0]?.[1]?.method).toBe('POST')
    expect(
      new Headers(mock.mock.calls[0]?.[1]?.headers).get('Idempotency-Key'),
    ).toBe('12345678-1234-4234-8234-123456789abc')
    expect(bodyAt(mock, 0)).toEqual({ suggestionId, conversationId })
    expect(result).toEqual(workflow)
  })

  it('报告归档和恢复调用归属受控接口，列表可按服务端归档状态筛选', async () => {
    const reportId = '91000000000000102'
    const archived = { id: reportId, archivedAt: '2026-09-23T02:00:00Z' }
    const restored = { id: reportId, archivedAt: null }
    const mock = stubFetch(
      successResponse({ items: [archived], page: 1, size: 20, total: 1, hasNext: false }),
      successResponse(archived),
      successResponse(restored),
    )

    const page = await listGeoReports({ archived: true, page: 1, size: 20 })
    expect(page.items[0]?.archivedAt).toBe(archived.archivedAt)
    expect((await archiveGeoReport(reportId)).archivedAt).toBe(archived.archivedAt)
    expect((await restoreGeoReport(reportId)).archivedAt).toBeNull()
    expect(mock.mock.calls.map((call) => [call[0], call[1]?.method])).toEqual([
      ['/api/v1/geo/reports?archived=true&page=1&size=20', 'GET'],
      [`/api/v1/geo/reports/${reportId}/archive`, 'POST'],
      [`/api/v1/geo/reports/${reportId}/restore`, 'POST'],
    ])
  })

  it('列表筛选、分页和详情保留字符串ID并解包真实data', async () => {
    const mock = stubFetch(successResponse(page), successResponse(topic))
    const result = await listGeoTopics({
      spaceId,
      productId,
      keyword: '核心 词',
      page: 2,
      size: 50,
    })
    const detail = await getGeoTopic(topicId)

    const listUrl = new URL(String(mock.mock.calls[0]?.[0]), 'https://example.test')
    expect(listUrl.pathname).toBe('/api/v1/geo/topics')
    expect(Object.fromEntries(listUrl.searchParams)).toEqual({
      spaceId,
      productId,
      keyword: '核心 词',
      page: '2',
      size: '50',
    })
    expect(mock.mock.calls[1]?.[0]).toBe(`/api/v1/geo/topics/${topicId}`)
    expect(result).toEqual(page)
    expect(detail).toEqual(topic)
    expect(typeof detail.id).toBe('string')
  })

  it('专题创建、version编辑和删除使用真实方法与路径', async () => {
    const mock = stubFetch(
      successResponse(topic, 201),
      successResponse({ ...topic, version: 8 }),
      successResponse(null),
    )

    await createGeoTopic({ spaceId, productId, name: '测试专题', region: '中国大陆' })
    await updateGeoTopic(topicId, { version: 7, name: '更新专题', productId: null })
    await deleteGeoTopic(topicId)

    expect(mock.mock.calls.map((call) => call[0])).toEqual([
      '/api/v1/geo/topics',
      `/api/v1/geo/topics/${topicId}`,
      `/api/v1/geo/topics/${topicId}`,
    ])
    expect(mock.mock.calls.map((call) => call[1]?.method)).toEqual(['POST', 'PATCH', 'DELETE'])
    expect(bodyAt(mock, 1)).toEqual({ version: 7, name: '更新专题', productId: null })
  })

  it('关键词、问题和竞品集合按传入顺序整体PUT并携带version', async () => {
    const mock = stubFetch(
      successResponse(topic),
      successResponse(topic),
      successResponse(topic),
    )
    const keywords = [
      { type: 'SCENE' as const, text: '先保存场景词' },
      { type: 'CORE' as const, text: '再保存核心词' },
    ]
    const questions = [
      { category: 'BRAND_DISCOVERY' as const, source: 'MANUAL' as const, text: '问题乙', enabled: false },
      { category: 'BRAND_DISCOVERY' as const, source: 'MANUAL' as const, text: '问题甲', enabled: true },
    ]

    await replaceGeoTopicKeywords(topicId, { version: 7, keywords })
    await replaceGeoTopicQuestions(topicId, { version: 8, questions })
    await replaceGeoTopicCompetitors(topicId, { version: 9, competitorIds })

    expect(mock.mock.calls.map((call) => call[0])).toEqual([
      `/api/v1/geo/topics/${topicId}/keywords`,
      `/api/v1/geo/topics/${topicId}/questions`,
      `/api/v1/geo/topics/${topicId}/competitors`,
    ])
    expect(bodyAt(mock, 0)).toEqual({ version: 7, keywords })
    expect(bodyAt(mock, 1)).toEqual({ version: 8, questions })
    expect(bodyAt(mock, 2)).toEqual({ version: 9, competitorIds })
  })

  it.each([
    [401, 'UNAUTHENTICATED'],
    [403, 'FORBIDDEN'],
    [409, 'RESOURCE_VERSION_CONFLICT'],
    [422, 'VALIDATION_FAILED'],
  ] as const)('HTTP %i保留后端message和traceId', async (status, code) => {
    stubFetch(errorResponse(status, code))
    await expect(updateGeoTopic(topicId, { version: 1, name: '冲突专题' })).rejects.toMatchObject({
      status,
      code,
      message: `GEO error ${status}`,
      traceId,
    } satisfies Partial<ApiError>)
  })

  it('Query Key按userId隔离且筛选条件进入列表Key', () => {
    const first = geoKeys.forOwner('user-1').topicList({ spaceId, page: 2, size: 20 })
    const second = geoKeys.forOwner('user-2').topicList({ spaceId, page: 2, size: 20 })
    expect(first).not.toEqual(second)
    expect(first).toContain('user-1')
    expect(first).toContain(spaceId)
    expect(geoKeys.forOwner('user-1').reportList({ topicId })).not.toEqual(
      geoKeys.forOwner('user-2').reportList({ topicId }),
    )
    expect(geoKeys.forOwner('user-1').planList({ topicId, status: 'ACTIVE' })).not.toEqual(
      geoKeys.forOwner('user-2').planList({ topicId, status: 'ACTIVE' }),
    )
  })

  it('RESOURCE_IN_USE删除冲突解释正式配置或审计引用', () => {
    const error = new ApiError({
      status: 409,
      code: 'RESOURCE_IN_USE',
      message: '专题正在使用中。',
      traceId,
    })
    expect(toGeoDeleteError(error)).toEqual({
      message: '专题正在使用中。 该专题存在正式配置或审计引用，不能删除。',
      traceId,
    })
  })
})

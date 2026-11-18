import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/apiError'
import {
  abandonWorkflowPlan,
  confirmWorkflowPlan,
  getWorkflowPlan,
  listWorkflowPlans,
  previewWorkflowPlanConfirmation,
  startWorkflowPlan,
} from '../api/workflowPlanApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { workflowPlanKeys } from '../features/workflow-plans'

const traceId = '0123456789abcdef0123456789abcdef'
const planId = '90071992547409931'

const plan = {
  id: planId,
  conversationId: '90071992547409930',
  goalSummary: '完成跨工具新品推广计划',
  status: 'WAITING_CONFIRMATION' as const,
  version: 3,
  stepCount: 2,
  totalQuotedPoints: 18,
  maxPointCost: 30,
  confirmationExpiresAt: '2026-09-10T10:00:00Z',
  createdAt: '2026-09-10T08:00:00Z',
  updatedAt: '2026-09-10T08:10:00Z',
  spaceId: '90071992547409920',
  productId: '90071992547409921',
  budget: {
    maxSteps: 6,
    maxReplans: 1,
    maxModelCalls: 8,
    maxToolCalls: 12,
    maxProviderCalls: 4,
    maxPointCost: 30,
    deadlineAt: '2026-09-11T08:00:00Z',
  },
  steps: [],
  confirmation: {
    planVersion: 3,
    quotedPoints: 18,
    maxPointCost: 30,
    expiresAt: '2026-09-10T10:00:00Z',
    consumptionEnabled: false as const,
  },
  events: [],
  abandonedAt: null,
  executionEnabled: false,
  executionStarted: false,
  lastStableError: null,
}

function response(data: unknown, status = 200) {
  return new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
  storeAuthToken({ accessToken: 'workflow-test-token', expiresAt: '2099-01-01T00:00:00Z' })
})

describe('Workflow Plan Browser API', () => {
  it('列表、详情和放弃使用冻结契约路径并保持 Snowflake 字符串', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(response({ items: [plan], page: 2, size: 20, total: 21, hasNext: true }))
      .mockResolvedValueOnce(response(plan))
      .mockResolvedValueOnce(response({ ...plan, status: 'ABANDONED', version: 4 }))
    vi.stubGlobal('fetch', fetchMock)

    const page = await listWorkflowPlans({ status: 'WAITING_CONFIRMATION', page: 2, size: 20 })
    const detail = await getWorkflowPlan(planId)
    const abandoned = await abandonWorkflowPlan(planId, { version: 3 })

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/workflow-plans?status=WAITING_CONFIRMATION&page=2&size=20',
      `/api/v1/workflow-plans/${planId}`,
      `/api/v1/workflow-plans/${planId}/abandon`,
    ])
    expect(fetchMock.mock.calls.map(([, init]) => init?.method)).toEqual(['GET', 'GET', 'POST'])
    expect(JSON.parse(String(fetchMock.mock.calls[2][1]?.body))).toEqual({ version: 3 })
    expect(page.items?.[0] && 'id' in page.items[0] ? page.items[0].id : undefined).toBe(planId)
    expect(detail.id).toBe(planId)
    expect(abandoned.status).toBe('ABANDONED')
  })

  it('确认预览与确认使用冻结路径、固定报价授权及稳定幂等键', async () => {
    const authorization = {
      planId,
      planVersion: 3,
      totalQuotedPoints: 18,
      maxPointCost: 30,
      expiresAt: '2099-09-10T10:00:00Z',
      executionEnabled: false as const,
      confirmationToken: 'one-time-confirmation-secret',
    }
    const confirmed = {
      planId,
      planVersion: 3,
      status: 'CONFIRMED' as const,
      totalQuotedPoints: 18,
      authorizedMaxPoints: 18,
      confirmedAt: '2099-09-10T09:00:00Z',
      executionEnabled: false as const,
      executionStarted: false as const,
    }
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(response(authorization))
      .mockResolvedValueOnce(response(confirmed))
      .mockResolvedValueOnce(response(confirmed))
    vi.stubGlobal('fetch', fetchMock)

    const preview = await previewWorkflowPlanConfirmation(planId, { planVersion: 3 })
    const request = {
      planVersion: preview.planVersion,
      authorizedMaxPoints: preview.totalQuotedPoints,
      confirmationToken: preview.confirmationToken,
    }
    const idempotencyKey = crypto.randomUUID()
    await confirmWorkflowPlan(planId, request, idempotencyKey)
    await confirmWorkflowPlan(planId, request, idempotencyKey)

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      `/api/v1/workflow-plans/${planId}/confirmation-preview`,
      `/api/v1/workflow-plans/${planId}/confirm`,
      `/api/v1/workflow-plans/${planId}/confirm`,
    ])
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({ planVersion: 3 })
    expect(JSON.parse(String(fetchMock.mock.calls[1][1]?.body))).toEqual(request)
    expect(JSON.parse(String(fetchMock.mock.calls[2][1]?.body))).toEqual(request)
    expect(new Headers(fetchMock.mock.calls[1][1]?.headers).get('Idempotency-Key')).toBe(idempotencyKey)
    expect(new Headers(fetchMock.mock.calls[2][1]?.headers).get('Idempotency-Key')).toBe(idempotencyKey)
    expect(Object.values(localStorage).join(' ')).not.toContain(authorization.confirmationToken)
  })

  it('开始执行使用版本请求与调用方提供的稳定幂等键', async () => {
    const started = {
      planId, planVersion: 3, status: 'QUEUED' as const,
      executionEnabled: true, executionStarted: true, executionNo: 1,
    }
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async () => response(started))
    vi.stubGlobal('fetch', fetchMock)
    const idempotencyKey = crypto.randomUUID()

    await startWorkflowPlan(planId, { planVersion: 3 }, idempotencyKey)
    await startWorkflowPlan(planId, { planVersion: 3 }, idempotencyKey)

    expect(fetchMock).toHaveBeenCalledTimes(2)
    for (const [url, init] of fetchMock.mock.calls) {
      expect(url).toBe(`/api/v1/workflow-plans/${planId}/start`)
      expect(init?.method).toBe('POST')
      expect(JSON.parse(String(init?.body))).toEqual({ planVersion: 3 })
      expect(new Headers(init?.headers).get('Idempotency-Key')).toBe(idempotencyKey)
    }
  })

  it.each([
    [409, 'WORKFLOW_STATE_CONFLICT'],
    [422, 'VALIDATION_ERROR'],
    [503, 'WORKFLOW_EXECUTION_DISABLED'],
  ])('开始执行 %s 保留 Java message 和 traceId', async (status, code) => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(JSON.stringify({
      code,
      message: '执行请求未被接受。',
      details: {},
      traceId,
    }), { status, headers: { 'Content-Type': 'application/json' } })))

    await expect(startWorkflowPlan(planId, { planVersion: 3 }, crypto.randomUUID())).rejects.toMatchObject({
      status,
      message: '执行请求未被接受。',
      traceId,
    } satisfies Partial<ApiError>)
  })

  it('409 冲突保留 Java message 和 traceId', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(JSON.stringify({
      code: 'VERSION_CONFLICT',
      message: '计划版本已变化，请刷新后重试。',
      details: {},
      traceId,
    }), { status: 409, headers: { 'Content-Type': 'application/json' } })))

    await expect(abandonWorkflowPlan(planId, { version: 2 })).rejects.toMatchObject({
      status: 409,
      message: '计划版本已变化，请刷新后重试。',
      traceId,
    } satisfies Partial<ApiError>)
  })

  it.each([
    [410, 'CONFIRMATION_EXPIRED', '确认授权已过期，请重新获取。'],
    [422, 'VALIDATION_ERROR', '授权积分参数不合法。'],
  ])('%s 错误保留 Java message 和 traceId', async (status, code, message) => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(JSON.stringify({
      code,
      message,
      details: {},
      traceId,
    }), { status, headers: { 'Content-Type': 'application/json' } })))

    await expect(confirmWorkflowPlan(planId, {
      planVersion: 3,
      authorizedMaxPoints: 18,
      confirmationToken: 'expired-secret',
    }, crypto.randomUUID())).rejects.toMatchObject({
      status,
      message,
      traceId,
    } satisfies Partial<ApiError>)
  })

  it('Query Key 按用户隔离且列表条件参与缓存键', () => {
    expect(workflowPlanKeys('user-a').list({ page: 1 })).not.toEqual(
      workflowPlanKeys('user-b').list({ page: 1 }),
    )
    expect(workflowPlanKeys('user-a').list({ page: 1 })).not.toEqual(
      workflowPlanKeys('user-a').list({ page: 2 }),
    )
  })
})

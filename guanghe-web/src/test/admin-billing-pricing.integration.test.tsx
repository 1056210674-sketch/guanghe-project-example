import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  CreatePricingVersionRequest,
  PricingVersionDto,
  RateLimitRuleDto,
  UpdateRateLimitRequest,
} from '../api/adminBillingApi'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { storeAuthToken } from '../auth/tokenStorage'
import { AdminPricingConsole } from '../features/admin-billing/pricing'
import { useAuthStore } from '../stores/authStore'
import {
  adminFixture,
  apiErrorResponse,
  jsonResponse,
  testAccessToken,
  testExpiresAt,
  testTraceId,
} from './fixtures/auth'

/**
 * The console under test is driven entirely through `fetch`. Every response body below is what a
 * stub server returns over that transport — never data the application module reads from anywhere
 * else — and every assertion about a write is made against the request that actually left the
 * browser, so a bundled fixture could not pass this file.
 */

const DRAFT_ID = 'draft-2001'
const ACTIVE_ID = 'active-1001'
const REFUSED_TRACE_ID = 'fedcba9876543210fedcba9876543210'
const OPERATOR_ID = 'admin-1002'

/**
 * Controls are located by their rendered label rather than by `getByRole(..., { name })`.
 *
 * antd turns a two-character CJK label into "编 辑" (it inserts a space) and puts an icon before it
 * whose `aria-label` joins the accessible name, so a role query would have to guess at text the
 * operator never sees. Collapsing whitespace before comparing keeps the assertion about what is on
 * screen and is also the only affordable option here: computing accessible names for every control
 * of an antd table under jsdom costs seconds per row, which is what used to make these tests
 * time out rather than fail.
 */
function collectByLabel<T extends Element>(scope: ParentNode, selector: string, label: string): T {
  const collapsedLabel = collapse(label)
  const candidates = Array.from(scope.querySelectorAll<T>(selector))
  const match = candidates.find(
    (candidate) => collapse(candidate.textContent ?? '') === collapsedLabel,
  )
  if (!match) {
    const available = candidates
      .map((candidate) => `「${collapse(candidate.textContent ?? '')}」`)
      .join('、')
    throw new Error(`未找到 ${selector}「${label}」；当前可选：${available || '（无）'}`)
  }
  return match
}

function collapse(value: string): string {
  return value.replace(/\s+/g, '')
}

/** The button that carries this visible label inside `scope` (a row, the modal, the document). */
function findButton(scope: ParentNode, label: string): HTMLButtonElement {
  return collectByLabel<HTMLButtonElement>(scope, 'button', label)
}

function pricedTier(amountCents: number, giftPoints: number, label: string, sortOrder: number) {
  return {
    tierId: `tier-${amountCents}`,
    amountCents,
    basePoints: amountCents,
    giftPoints,
    points: amountCents + giftPoints,
    label,
    sortOrder,
  }
}

function pricingVersion(
  overrides: Partial<PricingVersionDto> & Pick<PricingVersionDto, 'id' | 'versionNo' | 'status'>,
): PricingVersionDto {
  return {
    pointsPerYuan: 100,
    minRechargeAmountCents: 1000,
    maxRechargeAmountCents: 100000,
    note: null,
    createdBy: OPERATOR_ID,
    publishedBy: null,
    publishedAt: null,
    retiredAt: null,
    tiers: [],
    createdAt: '2026-09-10T02:00:00Z',
    updatedAt: '2026-09-11T03:00:00Z',
    ...overrides,
  }
}

function rateLimitRule(
  overrides: Partial<RateLimitRuleDto> & Pick<RateLimitRuleDto, 'action'>,
): RateLimitRuleDto {
  return {
    id: null,
    windowSeconds: 60,
    maxAttempts: 5,
    enabled: true,
    configured: false,
    version: 0,
    updatedBy: null,
    updatedAt: null,
    ...overrides,
  }
}

const DRAFT_VERSION = pricingVersion({
  id: DRAFT_ID,
  versionNo: 1,
  status: 'DRAFT',
  note: '国庆档位调整',
  tiers: [pricedTier(5000, 500, '标准档', 1)],
})

const ACTIVE_VERSION = pricingVersion({
  id: ACTIVE_ID,
  versionNo: 2,
  status: 'ACTIVE',
  pointsPerYuan: 100,
  minRechargeAmountCents: 1000,
  maxRechargeAmountCents: 100000,
  publishedBy: OPERATOR_ID,
  publishedAt: '2026-09-11T03:00:00Z',
  tiers: [pricedTier(5000, 300, '基础档', 1), pricedTier(20000, 2000, '大额档', 2)],
})

const CONFIGURED_RULES: RateLimitRuleDto[] = [
  rateLimitRule({
    id: 'rule-1',
    action: 'RECHARGE_ORDER_CREATE',
    windowSeconds: 60,
    maxAttempts: 5,
    enabled: true,
    configured: true,
    version: 3,
    updatedBy: OPERATOR_ID,
    updatedAt: '2026-09-11T03:00:00Z',
  }),
  rateLimitRule({
    action: 'INVOICE_APPLICATION_SUBMIT',
    windowSeconds: 3600,
    maxAttempts: 10,
    enabled: true,
    configured: false,
    version: 0,
  }),
  rateLimitRule({
    id: 'rule-3',
    action: 'FEEDBACK_SUBMIT',
    windowSeconds: 300,
    maxAttempts: 20,
    enabled: false,
    configured: true,
    version: 7,
    updatedBy: OPERATOR_ID,
    updatedAt: '2026-09-09T03:00:00Z',
  }),
]

interface RecordedRequest {
  url: string
  path: string
  method: string
  body: unknown
}

let versions: PricingVersionDto[] = []
let rules: RateLimitRuleDto[] = []
let publishRefusal: string | null = null
const requests: RecordedRequest[] = []

function ok(data: unknown, status = 200): Response {
  return jsonResponse({ code: 'OK', message: 'success', traceId: testTraceId, data }, status)
}

function findVersion(versionId: string): PricingVersionDto | undefined {
  return versions.find((version) => version.id === versionId)
}

/** Replaces a version as the server would, keeping the list the console reads afterwards honest. */
function storeVersion(next: PricingVersionDto): void {
  versions = versions.map((version) => (version.id === next.id ? next : version))
}

function handleCreate(request: CreatePricingVersionRequest): Response {
  const created = pricingVersion({
    id: 'draft-created',
    versionNo: 9,
    status: 'DRAFT',
    pointsPerYuan: request.pointsPerYuan,
    minRechargeAmountCents: request.minRechargeAmountCents,
    maxRechargeAmountCents: request.maxRechargeAmountCents,
    note: request.note ?? null,
    tiers: request.tiers.map((tier, index) => ({
      tierId: `tier-new-${index}`,
      amountCents: tier.amountCents,
      basePoints: (tier.amountCents / 100) * request.pointsPerYuan,
      giftPoints: tier.giftPoints ?? 0,
      points: (tier.amountCents / 100) * request.pointsPerYuan + (tier.giftPoints ?? 0),
      label: tier.label,
      sortOrder: tier.sortOrder ?? index,
    })),
  })
  versions = [created, ...versions]
  return ok(created, 201)
}

function handlePublish(versionId: string): Response {
  if (publishRefusal) {
    return apiErrorResponse(409, publishRefusal, REFUSED_TRACE_ID)
  }
  const target = findVersion(versionId)
  if (!target) return apiErrorResponse(404, '价格版本不存在。')
  if (target.status !== 'DRAFT') {
    return apiErrorResponse(409, '只有草稿版本可以发布。')
  }
  const published = { ...target, status: 'ACTIVE' as const, publishedBy: OPERATOR_ID, publishedAt: '2026-09-12T03:00:00Z' }
  versions = versions.map((version) =>
    version.id === versionId
      ? published
      : version.status === 'ACTIVE'
        ? { ...version, status: 'RETIRED' as const, retiredAt: '2026-09-12T03:00:00Z' }
        : version,
  )
  return ok(published)
}

function handleRetire(versionId: string): Response {
  const target = findVersion(versionId)
  if (!target) return apiErrorResponse(404, '价格版本不存在。')
  if (target.status === 'RETIRED') return apiErrorResponse(409, '该版本已停用。')
  const retired = { ...target, status: 'RETIRED' as const, retiredAt: '2026-09-13T03:00:00Z' }
  storeVersion(retired)
  return ok(retired)
}

function handleUpdateRateLimit(action: string, request: UpdateRateLimitRequest): Response {
  const current = rules.find((rule) => rule.action === action)
  if (!current) return apiErrorResponse(404, '频率限制规则不存在。')
  if (request.expectedVersion !== current.version) {
    return apiErrorResponse(409, '该规则已被其他人修改，请刷新后重试。', REFUSED_TRACE_ID)
  }
  const updated: RateLimitRuleDto = {
    id: current.id ?? `rule-${action}`,
    action: current.action,
    windowSeconds: request.windowSeconds,
    maxAttempts: request.maxAttempts,
    enabled: request.enabled,
    configured: true,
    version: current.version + 1,
    updatedBy: OPERATOR_ID,
    updatedAt: '2026-09-14T03:00:00Z',
  }
  rules = rules.map((rule) => (rule.action === action ? updated : rule))
  return ok(updated)
}

function handleRequest(request: RecordedRequest): Response {
  const url = new URL(request.url, 'http://localhost')
  const { path, method, body } = request

  if (path === '/api/v1/admin/billing/pricing-versions' && method === 'GET') {
    const status = url.searchParams.get('status')
    const page = Number(url.searchParams.get('page') ?? '1')
    const size = Number(url.searchParams.get('size') ?? '20')
    const filtered = status ? versions.filter((version) => version.status === status) : versions
    const items = filtered.slice((page - 1) * size, page * size)
    return ok({ items, page, size, total: filtered.length, hasNext: page * size < filtered.length })
  }
  if (path === '/api/v1/admin/billing/pricing-versions' && method === 'POST') {
    return handleCreate(body as CreatePricingVersionRequest)
  }

  const versionMatch = /^\/api\/v1\/admin\/billing\/pricing-versions\/([^/]+)(\/publish|\/retire)?$/.exec(path)
  if (versionMatch) {
    const versionId = decodeURIComponent(versionMatch[1])
    const action = versionMatch[2]
    if (method === 'PUT' && !action) {
      const update = body as CreatePricingVersionRequest & { expectedVersion: number }
      const target = findVersion(versionId)
      if (!target) return apiErrorResponse(404, '价格版本不存在。')
      if (update.expectedVersion !== target.versionNo) {
        return apiErrorResponse(409, '该草稿已被其他人修改，请刷新后重试。', REFUSED_TRACE_ID)
      }
      const updated = pricingVersion({
        ...target,
        versionNo: target.versionNo + 1,
        pointsPerYuan: update.pointsPerYuan,
        minRechargeAmountCents: update.minRechargeAmountCents,
        maxRechargeAmountCents: update.maxRechargeAmountCents,
        note: update.note ?? null,
        tiers: target.tiers.map((tier, index) => {
          const submitted = update.tiers[index]
          return submitted
            ? { ...tier, amountCents: submitted.amountCents, giftPoints: submitted.giftPoints ?? 0, label: submitted.label }
            : tier
        }),
      })
      storeVersion(updated)
      return ok(updated)
    }
    if (method === 'POST' && action === '/publish') return handlePublish(versionId)
    if (method === 'POST' && action === '/retire') return handleRetire(versionId)
  }

  if (path === '/api/v1/admin/billing/rate-limits' && method === 'GET') {
    return ok(rules)
  }
  const rateLimitMatch = /^\/api\/v1\/admin\/billing\/rate-limits\/([^/]+)$/.exec(path)
  if (rateLimitMatch && method === 'PUT') {
    return handleUpdateRateLimit(decodeURIComponent(rateLimitMatch[1]), body as UpdateRateLimitRequest)
  }

  return apiErrorResponse(404, `未预期的请求：${method} ${path}`)
}

function renderConsole() {
  return render(
    <ConfigProvider theme={appTheme}>
      <AntdApp>
        <QueryClientProvider client={createQueryClient()}>
          <AdminPricingConsole />
        </QueryClientProvider>
      </AntdApp>
    </ConfigProvider>,
  )
}

function requestsTo(path: string, method?: string): RecordedRequest[] {
  return requests.filter(
    (request) => request.path === path && (method === undefined || request.method === method),
  )
}

function versionListRequests(): RecordedRequest[] {
  return requests.filter(
    (request) =>
      request.method === 'GET' && request.path === '/api/v1/admin/billing/pricing-versions',
  )
}

async function openTab(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(collectByLabel<HTMLElement>(document, '[role="tab"]', name))
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  requests.length = 0
  versions = [ACTIVE_VERSION, DRAFT_VERSION]
  rules = CONFIGURED_RULES
  publishRefusal = null
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const url =
        typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
      const parsed = new URL(url, 'http://localhost')
      const record: RecordedRequest = {
        url,
        path: parsed.pathname,
        method: (init?.method ?? 'GET').toUpperCase(),
        body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      }
      requests.push(record)
      return handleRequest(record)
    }),
  )
})

describe('定价配置：价格版本', () => {
  it('渲染服务端返回的真实字段，并且只按契约参数请求', async () => {
    renderConsole()

    const table = await screen.findByRole('table', { name: '价格版本列表' })
    const activeRow = within(table).getByText('v2').closest('tr')
    const draftRow = within(table).getByText('v1').closest('tr')
    expect(activeRow).not.toBeNull()
    expect(draftRow).not.toBeNull()

    expect(within(activeRow as HTMLElement).getByText('生效中')).toBeInTheDocument()
    expect(within(activeRow as HTMLElement).getByText('100')).toBeInTheDocument()
    expect(within(activeRow as HTMLElement).getByText('¥10.00 – ¥1000.00')).toBeInTheDocument()
    expect(within(activeRow as HTMLElement).getByText('2')).toBeInTheDocument()
    expect(within(activeRow as HTMLElement).getAllByText('••1002')).toHaveLength(2)

    expect(within(draftRow as HTMLElement).getByText('草稿')).toBeInTheDocument()
    expect(within(draftRow as HTMLElement).getByText('国庆档位调整')).toBeInTheDocument()
    expect(within(draftRow as HTMLElement).getAllByText('—').length).toBeGreaterThan(0)

    expect(requestsTo('/api/v1/admin/billing/pricing-versions', 'GET')).not.toHaveLength(0)
    expect(requestsTo('/api/v1/admin/billing/rate-limits', 'GET')).not.toHaveLength(0)
    for (const request of requestsTo('/api/v1/admin/billing/pricing-versions', 'GET')) {
      expect(request.url).toMatch(
        /^\/api\/v1\/admin\/billing\/pricing-versions\?(status=[A-Z]+&)?page=\d+&size=\d+$/,
      )
    }
  })

  it('空响应渲染真实空态而不是样例行', async () => {
    versions = []
    renderConsole()

    expect(await screen.findByText('暂无价格版本')).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: '价格版本列表' })).not.toBeInTheDocument()
    expect(versionListRequests()).not.toHaveLength(0)
  })

  it('只有草稿可以编辑和发布，其它状态在禁用控件上说明原因', async () => {
    renderConsole()

    const table = await screen.findByRole('table', { name: '价格版本列表' })
    const activeRow = within(table).getByText('v2').closest('tr') as HTMLElement
    const draftRow = within(table).getByText('v1').closest('tr') as HTMLElement

    expect(findButton(activeRow, '编辑')).toBeDisabled()
    expect(findButton(activeRow, '发布')).toBeDisabled()
    expect(
      within(activeRow).getByText(/编辑不可用：仅草稿版本可编辑/),
    ).toBeInTheDocument()
    expect(within(activeRow).getByText(/发布不可用：仅草稿版本可发布/)).toBeInTheDocument()

    expect(findButton(draftRow, '编辑')).toBeEnabled()
    expect(findButton(draftRow, '发布')).toBeEnabled()
    expect(findButton(draftRow, '停用')).toBeEnabled()
  })

  it('新建草稿只提交契约字段：档位不含基础积分', async () => {
    const user = userEvent.setup()
    renderConsole()
    await screen.findByRole('table', { name: '价格版本列表' })

    await user.click(findButton(document.body, '新建版本'))
    await user.type(screen.getByLabelText('积分/元'), '200')
    await user.type(screen.getByLabelText('最低充值金额（元）'), '10')
    await user.type(screen.getByLabelText('最高充值金额（元）'), '1000')
    await user.type(screen.getByLabelText('备注'), '  新增定价  ')
    await user.type(screen.getByLabelText('档位 1 充值金额（元）'), '50')
    await user.type(screen.getByLabelText('档位 1 赠送积分'), '500')
    await user.type(screen.getByLabelText('档位 1 标签'), '标准档')

    // 50 元 × 200 积分/元 = 10000 基础积分，再加 500 赠送积分。
    expect(screen.getByText(/预计基础积分：10000/)).toBeInTheDocument()
    expect(screen.getByText(/预计到账合计：/)).toHaveTextContent('预计到账合计：10500')

    await user.click(findButton(document.body, '创建草稿'))

    await waitFor(() => {
      expect(requestsTo('/api/v1/admin/billing/pricing-versions', 'POST')).toHaveLength(1)
    })
    const [created] = requestsTo('/api/v1/admin/billing/pricing-versions', 'POST')
    expect(created.body).toEqual({
      pointsPerYuan: 200,
      minRechargeAmountCents: 1000,
      maxRechargeAmountCents: 100000,
      note: '新增定价',
      tiers: [{ amountCents: 5000, giftPoints: 500, label: '标准档', sortOrder: 0 }],
    })
    expect(JSON.stringify(created.body)).not.toMatch(/basePoints|points"/)
    expect(await screen.findByText(/已创建草稿 v9/)).toBeInTheDocument()
  })

  it('最低金额大于最高金额被客户端拦下并说明原因', async () => {
    const user = userEvent.setup()
    renderConsole()
    await screen.findByRole('table', { name: '价格版本列表' })

    await user.click(findButton(document.body, '新建版本'))
    await user.type(screen.getByLabelText('积分/元'), '100')
    await user.type(screen.getByLabelText('最低充值金额（元）'), '500')
    await user.type(screen.getByLabelText('最高充值金额（元）'), '100')

    expect(
      screen.getByText('最低充值金额不能大于最高充值金额：当前最低 ¥500.00，最高 ¥100.00。'),
    ).toBeInTheDocument()
    const submit = findButton(document.body, '创建草稿')
    expect(submit).toBeDisabled()
    await user.click(submit)
    expect(requestsTo('/api/v1/admin/billing/pricing-versions', 'POST')).toHaveLength(0)
  })

  it('编辑草稿携带读取到的版本号作为 expectedVersion', async () => {
    const user = userEvent.setup()
    renderConsole()
    const table = await screen.findByRole('table', { name: '价格版本列表' })
    const draftRow = within(table).getByText('v1').closest('tr') as HTMLElement

    await user.click(findButton(draftRow, '编辑'))
    expect(findButton(document.body, '保存草稿')).toBeEnabled()
    const pointsPerYuan = screen.getByLabelText('积分/元')
    await user.clear(pointsPerYuan)
    await user.type(pointsPerYuan, '150')
    await user.click(findButton(document.body, '保存草稿'))

    await waitFor(() => {
      expect(requestsTo(`/api/v1/admin/billing/pricing-versions/${DRAFT_ID}`, 'PUT')).toHaveLength(1)
    })
    const [update] = requestsTo(`/api/v1/admin/billing/pricing-versions/${DRAFT_ID}`, 'PUT')
    expect(update.body).toMatchObject({
      pointsPerYuan: 150,
      minRechargeAmountCents: 1000,
      maxRechargeAmountCents: 100000,
      expectedVersion: 1,
    })
    expect(await screen.findByText('草稿 v2 已保存。')).toBeInTheDocument()
  })

  it('发布调用发布端点并失效列表缓存，页面随后显示服务端的新状态', async () => {
    const user = userEvent.setup()
    renderConsole()
    const table = await screen.findByRole('table', { name: '价格版本列表' })
    const draftRow = within(table).getByText('v1').closest('tr') as HTMLElement

    const listCallsBefore = versionListRequests().length
    await user.click(findButton(draftRow, '发布'))
    expect(screen.getByText(/发布后该版本立即生效/)).toBeInTheDocument()
    await user.click(findButton(document.body, '确认发布'))

    await waitFor(() => {
      expect(requestsTo(`/api/v1/admin/billing/pricing-versions/${DRAFT_ID}/publish`, 'POST')).toHaveLength(1)
    })
    const [publish] = requestsTo(`/api/v1/admin/billing/pricing-versions/${DRAFT_ID}/publish`, 'POST')
    expect(publish.body).toBeUndefined()
    expect(await screen.findByText('v1 已发布并生效。')).toBeInTheDocument()
    await waitFor(() => {
      expect(versionListRequests().length).toBeGreaterThan(listCallsBefore)
    })
    // 服务端把被替换的 v2 变成了已停用：页面照抄服务端状态，而不是自己推断。
    expect(await screen.findByText('已停用')).toBeInTheDocument()
  })

  it('被拒绝的发布显示服务端原因与 traceId，且不显示成功提示', async () => {
    const user = userEvent.setup()
    publishRefusal = '当前已有生效版本，发布被拒绝。'
    renderConsole()
    const table = await screen.findByRole('table', { name: '价格版本列表' })
    const draftRow = within(table).getByText('v1').closest('tr') as HTMLElement

    await user.click(findButton(draftRow, '发布'))
    await user.click(findButton(document.body, '确认发布'))

    expect(await screen.findByText('当前已有生效版本，发布被拒绝。')).toBeInTheDocument()
    expect(screen.getByText(`traceId：${REFUSED_TRACE_ID}`)).toBeInTheDocument()
    expect(screen.queryByText(/已发布并生效/)).not.toBeInTheDocument()
    // 服务端没有改变任何状态，因此列表里不会凭空出现“已停用”。
    expect(screen.queryByText('已停用')).not.toBeInTheDocument()
    expect(within(table).getByText('草稿')).toBeInTheDocument()
  })

  it('停用需要填写原因并调用停用端点', async () => {
    const user = userEvent.setup()
    renderConsole()
    const table = await screen.findByRole('table', { name: '价格版本列表' })
    const activeRow = within(table).getByText('v2').closest('tr') as HTMLElement

    await user.click(findButton(activeRow, '停用'))
    expect(findButton(document.body, '确认停用')).toBeDisabled()
    await user.type(screen.getByLabelText('停用原因'), '  替换为新汇率  ')
    await user.click(findButton(document.body, '确认停用'))

    await waitFor(() => {
      expect(requestsTo(`/api/v1/admin/billing/pricing-versions/${ACTIVE_ID}/retire`, 'POST')).toHaveLength(1)
    })
    expect(await screen.findByText('v2 已停用。')).toBeInTheDocument()
  })
})

describe('定价配置：赠送积分', () => {
  it('默认展示生效版本的档位，生效版本不能就地修改', async () => {
    const user = userEvent.setup()
    renderConsole()
    await screen.findByRole('table', { name: '价格版本列表' })
    await openTab(user, '赠送积分')

    const tierTable = await screen.findByRole('table', { name: '充值档位列表' })
    expect(within(tierTable).getByText('¥50.00')).toBeInTheDocument()
    expect(within(tierTable).getByText('基础档')).toBeInTheDocument()
    expect(within(tierTable).getByText('大额档')).toBeInTheDocument()
    expect(screen.getByText(/赠送积分不可用：仅草稿版本可修改/)).toBeInTheDocument()
    expect(findButton(document.body, '编辑该版本档位')).toBeDisabled()
  })

  it('切到草稿版本后可以复用同一表单修改赠送积分', async () => {
    const user = userEvent.setup()
    renderConsole()
    await screen.findByRole('table', { name: '价格版本列表' })
    await openTab(user, '赠送积分')
    await screen.findByRole('table', { name: '充值档位列表' })

    await user.click(screen.getByLabelText('选择价格版本'))
    const draftOption = await screen.findByText('v1 · 草稿')
    await user.click(draftOption)

    const edit = findButton(document.body, '编辑该版本档位')
    expect(edit).toBeEnabled()
    await user.click(edit)

    const giftPoints = screen.getByLabelText('档位 1 赠送积分')
    expect(giftPoints).toHaveValue('500')
    await user.clear(giftPoints)
    await user.type(giftPoints, '800')
    await user.click(findButton(document.body, '保存草稿'))

    await waitFor(() => {
      expect(requestsTo(`/api/v1/admin/billing/pricing-versions/${DRAFT_ID}`, 'PUT')).toHaveLength(1)
    })
    const [update] = requestsTo(`/api/v1/admin/billing/pricing-versions/${DRAFT_ID}`, 'PUT')
    expect(update.body).toMatchObject({
      expectedVersion: 1,
      tiers: [{ amountCents: 5000, giftPoints: 800, label: '标准档', sortOrder: 1 }],
    })
  })

  it('没有任何版本时直接说明充值功能尚未配置', async () => {
    const user = userEvent.setup()
    versions = []
    renderConsole()
    await screen.findByText('暂无价格版本')
    await openTab(user, '赠送积分')

    expect(
      await screen.findByText('暂无价格版本，充值功能尚未配置'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: '充值档位列表' })).not.toBeInTheDocument()
  })
})

describe('定价配置：频率限制', () => {
  it('未配置的规则按服务端状态渲染，不假装已经设置过', async () => {
    const user = userEvent.setup()
    renderConsole()
    await openTab(user, '频率限制')

    const table = await screen.findByRole('table', { name: '频率限制规则列表' })
    const configuredRow = within(table).getByText('创建充值订单').closest('tr') as HTMLElement
    expect(within(configuredRow).getByText('已配置')).toBeInTheDocument()
    expect(within(configuredRow).getByText('3')).toBeInTheDocument()
    expect(within(configuredRow).getByText('启用')).toBeInTheDocument()

    const unconfiguredRow = within(table).getByText('提交发票申请').closest('tr') as HTMLElement
    expect(
      within(unconfiguredRow).getByText('尚未配置，服务端使用默认值'),
    ).toBeInTheDocument()
    expect(within(unconfiguredRow).getByText('0（内置默认）')).toBeInTheDocument()
    expect(within(unconfiguredRow).getByText('提交发票申请')).toBeInTheDocument()
  })

  it('越界的时间窗与最大次数被拦下并指出边界，合法值携带 expectedVersion 提交', async () => {
    const user = userEvent.setup()
    renderConsole()
    await openTab(user, '频率限制')
    const table = await screen.findByRole('table', { name: '频率限制规则列表' })
    const row = within(table).getByText('创建充值订单').closest('tr') as HTMLElement

    await user.click(findButton(row, '编辑'))
    const windowSeconds = screen.getByLabelText('时间窗（秒）')
    await user.clear(windowSeconds)
    await user.type(windowSeconds, '90000')

    expect(screen.getByText('时间窗必须是 1–86400 秒之间的整数。')).toBeInTheDocument()
    const save = findButton(document.body, '保存')
    expect(save).toBeDisabled()
    await user.click(save)
    expect(
      requestsTo('/api/v1/admin/billing/rate-limits/RECHARGE_ORDER_CREATE', 'PUT'),
    ).toHaveLength(0)

    await user.clear(windowSeconds)
    await user.type(windowSeconds, '120')
    const maxAttempts = screen.getByLabelText('最大次数')
    await user.clear(maxAttempts)
    await user.type(maxAttempts, '200000')
    expect(screen.getByText('最大次数必须是 1–100000 次之间的整数。')).toBeInTheDocument()
    expect(findButton(document.body, '保存')).toBeDisabled()

    await user.clear(maxAttempts)
    await user.type(maxAttempts, '50')
    expect(screen.queryByText(/必须是 1–/)).not.toBeInTheDocument()
    await user.click(findButton(document.body, '保存'))

    await waitFor(() => {
      expect(
        requestsTo('/api/v1/admin/billing/rate-limits/RECHARGE_ORDER_CREATE', 'PUT'),
      ).toHaveLength(1)
    })
    const [update] = requestsTo('/api/v1/admin/billing/rate-limits/RECHARGE_ORDER_CREATE', 'PUT')
    expect(update.body).toEqual({
      windowSeconds: 120,
      maxAttempts: 50,
      enabled: true,
      expectedVersion: 3,
    })
    expect(await screen.findByText(/创建充值订单 的频率限制已保存。/)).toBeInTheDocument()
  })

  it('并发编辑被服务端拒绝时重新拉取列表并显示服务端原因与 traceId', async () => {
    const user = userEvent.setup()
    renderConsole()
    await openTab(user, '频率限制')
    const table = await screen.findByRole('table', { name: '频率限制规则列表' })
    const row = within(table).getByText('创建充值订单').closest('tr') as HTMLElement

    await user.click(findButton(row, '编辑'))
    const listCallsBefore = requestsTo('/api/v1/admin/billing/rate-limits', 'GET').length
    // The other operator's save bumps the stored version while this form is open.
    rules = rules.map((rule) =>
      rule.action === 'RECHARGE_ORDER_CREATE' ? { ...rule, version: 4, windowSeconds: 30 } : rule,
    )
    await user.click(findButton(document.body, '保存'))

    expect(await screen.findByText('该规则已被其他人修改，请刷新后重试。')).toBeInTheDocument()
    expect(screen.getByText(`traceId：${REFUSED_TRACE_ID}`)).toBeInTheDocument()
    await waitFor(() => {
      expect(requestsTo('/api/v1/admin/billing/rate-limits', 'GET').length).toBeGreaterThan(
        listCallsBefore,
      )
    })
    expect(
      await screen.findByText(/本次保存被服务端拒绝，列表已按服务端最新状态重新加载/),
    ).toBeInTheDocument()
  })
})

describe('定价配置：未登录', () => {
  it('匿名（开发预览）会话不发起任何请求', async () => {
    useAuthStore.setState({ status: 'anonymous', user: null, error: null })
    renderConsole()

    expect(
      await screen.findByText('当前没有已登录的管理员会话，未发起任何请求'),
    ).toBeInTheDocument()
    expect(requests).toHaveLength(0)
    expect(await screen.findByText('暂无价格版本')).toBeInTheDocument()
  })
})

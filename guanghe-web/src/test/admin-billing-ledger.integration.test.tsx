import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { clearStoredAuthToken, storeAuthToken } from '../auth/tokenStorage'
import { AdminLedgerConsole } from '../features/admin-billing/ledger'
import { useAuthStore } from '../stores/authStore'
import {
  adminFixture,
  jsonResponse,
  testAccessToken,
  testExpiresAt,
  testTraceId,
} from './fixtures/auth'

const LEDGER_USER_ID = '1000000000000999'
const MASKED_LEDGER_USER = '••0999'
const CONFIRMATION_TOKEN = `adj_${'A'.repeat(39)}`
const EXPIRED_MESSAGE = '确认令牌已过期，请重新发起'

const ledgerEntry = {
  id: '5001',
  userId: LEDGER_USER_ID,
  availableDelta: -120,
  frozenDelta: 0,
  availableAfter: 880,
  frozenAfter: 0,
  entryType: 'CONSUME',
  businessType: 'TASK',
  businessId: '9001',
  operatorId: null,
  reason: '任务扣费',
  createdAt: '2026-09-19T02:00:00Z',
}

const bookkeepingSummary = {
  generatedAt: '2026-09-20T00:00:00Z',
  recharge: {
    ordersByStatus: [{ status: 'SUCCEEDED', total: 3 }],
    succeededOrders: 3,
    succeededAmountCents: 123400,
    basePoints: 12000,
    giftPoints: 400,
  },
  invoice: {
    applicationsByStatus: [{ status: 'DELIVERED', total: 1 }],
    deliveredAmountCents: 50000,
  },
  feedback: { itemsByStatus: [{ status: 'OPEN', total: 2 }] },
  ledger: { entries: 7, availableDelta: -420, frozenDelta: 0 },
  ratePlan: { productionRate: true, versionId: 'pv-1', versionNo: 3, pointsPerYuan: 100 },
  platformAvailablePoints: 123456,
  platformFrozenPoints: 789,
  ledgerEntryTypes: ['CONSUME', 'RECHARGE'],
  ledgerBusinessTypes: ['TASK', 'RECHARGE_ORDER'],
}

const pendingAdjustment = {
  id: '7001',
  userId: LEDGER_USER_ID,
  points: 250,
  direction: 'CREDIT',
  reason: '上游任务异常补偿',
  status: 'PENDING_CONFIRMATION',
  operatorId: adminFixture.id,
  confirmationToken: CONFIRMATION_TOKEN,
  confirmationExpiresAt: '2099-01-01T00:00:00Z',
  ledgerId: null,
  appliedAt: null,
  createdAt: '2026-09-20T01:00:00Z',
}

const appliedAdjustment = {
  ...pendingAdjustment,
  status: 'APPLIED',
  confirmationToken: null,
  ledgerId: '5009',
  appliedAt: '2026-09-20T01:05:00Z',
}

const expiredAdjustment = {
  ...pendingAdjustment,
  id: '7002',
  confirmationToken: null,
  confirmationExpiresAt: '2020-01-01T00:00:00Z',
}

export interface RecordedCall {
  url: string
  method: string
  body?: string
}

interface FetchStubOptions {
  entries?: readonly unknown[]
  total?: number
  summaryEntries?: number
  adjustments?: readonly unknown[]
  adjustmentsTotal?: number
  onCreateAdjustment?: () => Response
  onConfirmAdjustment?: () => Response
}

function envelope(data: unknown, status = 200): Response {
  return jsonResponse({ code: 'OK', message: 'success', traceId: testTraceId, data }, status)
}

/**
 * A tiny router over the frozen Browser API paths. Every request is recorded with its method and
 * raw body so a test can assert what actually left the browser.
 */
function installFetchStub(options: FetchStubOptions = {}) {
  const calls: RecordedCall[] = []
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    const request = init ?? {}
    const method = (request.method ?? 'GET').toUpperCase()
    calls.push({
      url,
      method,
      body: request.body === undefined || request.body === null ? undefined : String(request.body),
    })

    if (url.startsWith('/api/v1/admin/billing/bookkeeping/summary')) {
      return envelope(bookkeepingSummary)
    }
    if (url.startsWith('/api/v1/admin/billing/point-ledger/summary')) {
      return envelope({
        entries: options.summaryEntries ?? 42,
        availableDelta: -420,
        frozenDelta: 0,
      })
    }
    if (url.startsWith('/api/v1/admin/billing/point-ledger')) {
      return envelope({
        items: options.entries ?? [],
        page: 1,
        size: 20,
        total: options.total ?? 0,
        hasNext: false,
      })
    }
    if (method === 'POST' && /\/point-adjustments\/[^/]+\/confirm$/.test(url)) {
      return options.onConfirmAdjustment?.() ?? envelope(appliedAdjustment)
    }
    if (method === 'POST' && url.endsWith('/point-adjustments')) {
      return options.onCreateAdjustment?.() ?? envelope(pendingAdjustment, 201)
    }
    if (url.startsWith('/api/v1/admin/billing/point-adjustments')) {
      return envelope({
        items: options.adjustments ?? [],
        page: 1,
        size: 20,
        total: options.adjustmentsTotal ?? (options.adjustments?.length ?? 0),
        hasNext: false,
      })
    }
    throw new Error(`unexpected request: ${method} ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, calls }
}

function listCalls(calls: readonly RecordedCall[]): RecordedCall[] {
  return calls.filter(
    (call) => call.url.startsWith('/api/v1/admin/billing/point-ledger') && !call.url.includes('/summary'),
  )
}

function summaryCalls(calls: readonly RecordedCall[]): RecordedCall[] {
  return calls.filter((call) => call.url.startsWith('/api/v1/admin/billing/point-ledger/summary'))
}

function paramsOf(call: RecordedCall | undefined): URLSearchParams {
  expect(call).toBeDefined()
  return new URL((call as RecordedCall).url, 'http://localhost').searchParams
}

function renderConsole() {
  return render(
    <ConfigProvider theme={appTheme}>
      <AntdApp>
        <QueryClientProvider client={createQueryClient()}>
          <AdminLedgerConsole />
        </QueryClientProvider>
      </AntdApp>
    </ConfigProvider>,
  )
}

async function openAdjustmentTab(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('tab', { name: '人工补录' }))
}

async function submitFirstStep(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('用户 ID'), LEDGER_USER_ID)
  await user.type(screen.getByLabelText('积分'), '250')
  await user.type(screen.getByLabelText('原因'), '上游任务异常补偿')
  await user.click(screen.getByRole('button', { name: '发起补录' }))
  return screen.findByRole('dialog')
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  clearStoredAuthToken()
  useAuthStore.setState({ status: 'anonymous', user: null, error: null })
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
})

describe('积分账务 · 积分流水', () => {
  it('渲染服务端返回的流水行，合计与账务汇总各自来自服务端', async () => {
    installFetchStub({ entries: [ledgerEntry], total: 1 })
    renderConsole()

    const table = await screen.findByRole('table', { name: '积分流水列表' })
    expect(within(table).getByText('5001')).toBeInTheDocument()
    expect(within(table).getByText(MASKED_LEDGER_USER)).toBeInTheDocument()
    expect(within(table).queryByText(LEDGER_USER_ID)).not.toBeInTheDocument()
    expect(within(table).getByText('CONSUME')).toBeInTheDocument()
    expect(within(table).getByText('TASK')).toBeInTheDocument()
    expect(within(table).getByText('9001')).toBeInTheDocument()
    expect(within(table).getByText('-120')).toBeInTheDocument()
    expect(within(table).getByText('880')).toBeInTheDocument()
    expect(within(table).getByText('任务扣费')).toBeInTheDocument()
    expect(screen.getByText('共 1 条')).toBeInTheDocument()

    // 账务汇总卡片来自 getBookkeepingSummary。
    expect(await screen.findByText('123,456')).toBeInTheDocument()
    expect(screen.getByText('¥1234.00')).toBeInTheDocument()

    // 合计来自 getAdminPointLedgerSummary，而不是当前页（当前页 total 是 1）。
    const summaryPanel = screen.getByText('合计（与当前分页无关）').closest('.ant-card')
    expect(summaryPanel).not.toBeNull()
    expect(within(summaryPanel as HTMLElement).getByText('42')).toBeInTheDocument()
    expect(within(table).queryByText('42')).not.toBeInTheDocument()
  })

  it('服务端没有流水时展示真实空态', async () => {
    installFetchStub({ entries: [], total: 0 })
    renderConsole()

    expect(await screen.findByText('暂无积分流水')).toBeInTheDocument()
    expect(screen.getByText('账务接口当前没有返回任何流水记录')).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: '积分流水列表' })).not.toBeInTheDocument()
  })

  it('合计请求与列表请求携带相同筛选，翻页不会改变合计的筛选', async () => {
    const user = userEvent.setup()
    const stub = installFetchStub({ entries: [ledgerEntry], total: 45 })
    renderConsole()

    await screen.findByRole('table', { name: '积分流水列表' })

    await user.type(screen.getByLabelText('流水用户 ID'), LEDGER_USER_ID)
    fireEvent.change(screen.getByLabelText('开始时间'), {
      target: { value: '2026-09-01T00:00' },
    })
    // 流水类型的选项来自账务汇总返回的 ledgerEntryTypes，而不是前端硬编码的第二份清单。
    await user.click(screen.getByLabelText('流水类型'))
    await waitFor(() => expect(document.querySelector('.ant-select-dropdown')).not.toBeNull())
    const dropdown = document.querySelector<HTMLElement>('.ant-select-dropdown')
    expect(
      Array.from(dropdown?.querySelectorAll('.ant-select-item-option') ?? []).map((option) =>
        option.getAttribute('title'),
      ),
    ).toEqual(['CONSUME', 'RECHARGE'])
    await user.click(within(dropdown as HTMLElement).getByTitle('CONSUME'))
    await user.click(screen.getByRole('button', { name: /查\s*询/ }))

    await waitFor(() => expect(summaryCalls(stub.calls)).toHaveLength(2))
    const listParams = paramsOf(listCalls(stub.calls).at(-1))
    const summaryParams = paramsOf(summaryCalls(stub.calls).at(-1))

    expect(listParams.get('userId')).toBe(LEDGER_USER_ID)
    expect(summaryParams.get('userId')).toBe(LEDGER_USER_ID)
    expect(listParams.get('entryType')).toBe('CONSUME')
    expect(summaryParams.get('entryType')).toBe('CONSUME')
    expect(listParams.get('from')).toBe('2026-09-01T00:00')
    expect(summaryParams.get('from')).toBe('2026-09-01T00:00')
    for (const key of ['userId', 'entryType', 'businessType', 'from', 'to']) {
      expect(summaryParams.get(key)).toBe(listParams.get(key))
    }
    expect(summaryParams.get('page')).toBeNull()
    expect(listParams.get('page')).toBe('1')
    expect(listParams.get('size')).toBe('20')

    const summaryBefore = summaryCalls(stub.calls).map((call) => call.url)
    await user.click(screen.getByTitle('2'))

    await waitFor(() => expect(paramsOf(listCalls(stub.calls).at(-1)).get('page')).toBe('2'))
    const pagedParams = paramsOf(listCalls(stub.calls).at(-1))
    expect(pagedParams.get('userId')).toBe(LEDGER_USER_ID)
    expect(pagedParams.get('entryType')).toBe('CONSUME')
    // 翻页只影响列表请求：合计请求的筛选与次数都不变。
    const summaryAfter = summaryCalls(stub.calls).map((call) => call.url)
    expect(summaryAfter).toEqual(summaryBefore)
    expect(summaryAfter).toHaveLength(2)
  })
})

describe('积分账务 · 人工补录两步确认', () => {
  it('第一步创建待确认记录，第二步才用返回的令牌入账', async () => {
    const user = userEvent.setup()
    const stub = installFetchStub({ adjustments: [] })
    renderConsole()
    await openAdjustmentTab(user)

    const dialog = await submitFirstStep(user)

    const createCalls = stub.calls.filter(
      (call) => call.method === 'POST' && call.url.endsWith('/point-adjustments'),
    )
    expect(createCalls).toHaveLength(1)
    expect(JSON.parse(String(createCalls[0].body))).toEqual({
      userId: LEDGER_USER_ID,
      points: 250,
      reason: '上游任务异常补偿',
    })

    // 第二步把用户 / 积分数额 / 原因 / 过期时间再呈现一次。
    expect(within(dialog).getByText(LEDGER_USER_ID)).toBeInTheDocument()
    expect(within(dialog).getByText('+250')).toBeInTheDocument()
    expect(within(dialog).getByText('上游任务异常补偿')).toBeInTheDocument()
    expect(within(dialog).getByText('这是第二步：确认后积分才会真正入账')).toBeInTheDocument()
    const shownToken = within(dialog).getByTestId('confirmation-token').textContent
    expect(shownToken).toBe(CONFIRMATION_TOKEN)

    // 未输入令牌前不能确认入账，且没有任何确认请求发出。
    const confirmButton = within(dialog).getByRole('button', { name: '确认入账' })
    expect(confirmButton).toBeDisabled()
    await user.click(confirmButton)
    expect(stub.calls.some((call) => call.url.includes('/confirm'))).toBe(false)

    // 输入错误长度的令牌仍然禁用。
    await user.type(within(dialog).getByLabelText('确认令牌'), 'too-short')
    expect(confirmButton).toBeDisabled()

    await user.clear(within(dialog).getByLabelText('确认令牌'))
    await user.type(within(dialog).getByLabelText('确认令牌'), CONFIRMATION_TOKEN)
    expect(confirmButton).toBeEnabled()
    await user.click(confirmButton)

    await waitFor(() =>
      expect(stub.calls.filter((call) => call.url.includes('/confirm'))).toHaveLength(1),
    )
    const postCalls = stub.calls.filter((call) => call.method === 'POST')
    expect(postCalls.map((call) => call.url)).toEqual([
      '/api/v1/admin/billing/point-adjustments',
      `/api/v1/admin/billing/point-adjustments/${pendingAdjustment.id}/confirm`,
    ])
    expect(JSON.parse(String(postCalls[1].body))).toEqual({
      confirmationToken: CONFIRMATION_TOKEN,
    })

    // 入账结果以服务端响应为准，令牌随即从 DOM 消失。
    expect(await screen.findByText('补录已入账（补录单号 7001）')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.queryByTestId('confirmation-token')).not.toBeInTheDocument(),
    )
    expect(document.body).not.toHaveTextContent(CONFIRMATION_TOKEN)
  })

  it('已过期的待确认补录不能确认入账', async () => {
    const user = userEvent.setup()
    const stub = installFetchStub({ adjustments: [expiredAdjustment], adjustmentsTotal: 1 })
    renderConsole()
    await openAdjustmentTab(user)

    const table = await screen.findByRole('table', { name: '待确认补录列表' })
    expect(table.parentElement).toHaveClass('admin-table-scroll')
    expect(within(table).getByText('已过期')).toBeInTheDocument()
    await user.click(within(table).getByRole('button', { name: `继续确认 ${expiredAdjustment.id}` }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getAllByText(EXPIRED_MESSAGE)).toHaveLength(1)
    const confirmButton = within(dialog).getByRole('button', { name: '确认入账' })
    expect(confirmButton).toBeDisabled()

    // 即使输入了格式合法的令牌也无法入账，页面不提供绕过校验的方式。
    await user.type(within(dialog).getByLabelText('确认令牌'), CONFIRMATION_TOKEN)
    expect(confirmButton).toBeDisabled()
    await user.click(confirmButton)
    expect(stub.calls.some((call) => call.url.includes('/confirm'))).toBe(false)
  })

  it('确认失败时展示服务端 message 与 traceId，且不显示成功结果', async () => {
    const user = userEvent.setup()
    const stub = installFetchStub({
      adjustments: [],
      onConfirmAdjustment: () =>
        jsonResponse(
          { code: 'CONFLICT', message: '确认令牌无效。', details: {}, traceId: testTraceId },
          409,
        ),
    })
    renderConsole()
    await openAdjustmentTab(user)

    const dialog = await submitFirstStep(user)
    await user.type(within(dialog).getByLabelText('确认令牌'), CONFIRMATION_TOKEN)
    await user.click(within(dialog).getByRole('button', { name: '确认入账' }))

    expect(
      await within(dialog).findByText(`traceId：${testTraceId}`),
    ).toBeInTheDocument()
    expect(within(dialog).getByText('确认令牌无效。')).toBeInTheDocument()
    expect(screen.queryByText('补录已入账（补录单号 7001）')).not.toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(stub.calls.filter((call) => call.url.includes('/confirm'))).toHaveLength(1)
  })
})

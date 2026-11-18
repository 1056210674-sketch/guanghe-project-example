/**
 * 用户侧商业化页面的真接口验收。
 *
 * 上游是一个按契约回答的 fetch stub，断言的是渲染的诚实性：通道未配置就写未配置并且不可支付；
 * 不存在的支付二维码不会被画出来；已关闭与已过期是两种结局；审核通过不等于已经开具；每一个被禁用的
 * 控件都必须写明禁用原因。这里不产生任何真实支付、发票或邮件调用，服务端配置仍然是
 * PAYMENT_PROVIDER_NOT_CONFIGURED。
 */
import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  FeedbackDto,
  InvoiceApplicationDto,
  InvoiceCapabilityDto,
  RateCardDto,
  RechargeCapabilityDto,
  RechargeOrderDto,
} from '../api/billingApi'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { clearStoredAuthToken, storeAuthToken } from '../auth/tokenStorage'
import { FeedbackConsole } from '../features/billing/components/FeedbackConsole'
import { InvoiceConsole } from '../features/billing/components/InvoiceConsole'
import { RechargeConsole } from '../features/billing/components/RechargeConsole'
import { RechargeOrderConsole } from '../features/billing/components/RechargeOrderConsole'
import { toInvoiceApplicationView } from '../features/billing/types'
import { useAuthStore } from '../stores/authStore'
import {
  jsonResponse,
  testAccessToken,
  testExpiresAt,
  testTraceId,
  userFixture,
} from './fixtures/auth'
import { renderApp } from './renderApp'

// ---------------------------------------------------------------- stub server

interface RecordedRequest {
  path: string
  method: string
  search: string
  body: unknown
  idempotencyKey: string | null
}

type Handler = (request: RecordedRequest) => Response | undefined

function recordRequest(input: RequestInfo | URL, init?: RequestInit): RecordedRequest {
  const url =
    typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
  const parsed = new URL(url, 'http://localhost')
  const body = init?.body
  return {
    path: parsed.pathname,
    method: (init?.method ?? 'GET').toUpperCase(),
    search: parsed.search,
    body: body === undefined || body === null ? undefined : JSON.parse(String(body)),
    idempotencyKey: new Headers(init?.headers).get('Idempotency-Key'),
  }
}

/**
 * Installs the stub and returns every request it saw. An unstubbed call answers 404 so a missing
 * expectation surfaces as a failed assertion rather than as a silently empty list.
 */
function installServer(handler: Handler): RecordedRequest[] {
  const requests: RecordedRequest[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const request = recordRequest(input, init)
      requests.push(request)
      return (
        handler(request) ??
        jsonResponse(
          {
            code: 'NOT_FOUND',
            message: `stub 未覆盖：${request.method} ${request.path}`,
            traceId: testTraceId,
            data: null,
          },
          404,
        )
      )
    }),
  )
  return requests
}

function ok(data: unknown): Response {
  return jsonResponse({ code: 'OK', message: 'success', traceId: testTraceId, data })
}

function fail(message: string, status = 503): Response {
  return jsonResponse({ code: 'SERVICE_UNAVAILABLE', message, traceId: testTraceId, data: null }, status)
}

function posts(requests: RecordedRequest[], path: string): RecordedRequest[] {
  return requests.filter((request) => request.method === 'POST' && request.path === path)
}

// ---------------------------------------------------------------- fixtures

function capabilityDto(overrides: Partial<RechargeCapabilityDto> = {}): RechargeCapabilityDto {
  return {
    provider: 'WECHAT_NATIVE',
    configured: false,
    payable: false,
    callbackVerifiable: false,
    status: 'PAYMENT_PROVIDER_NOT_CONFIGURED',
    missingRequirements: ['WECHAT_PAY_MCH_ID', 'WECHAT_PAY_CERT_SERIAL_NO'],
    minRechargeAmountCents: 1000,
    maxRechargeAmountCents: 100000,
    pointsPerYuan: 100,
    rechargeOrderTtlSeconds: 1800,
    currency: 'CNY',
    ...overrides,
  }
}

function rateCardDto(overrides: Partial<RateCardDto> = {}): RateCardDto {
  return {
    productionRate: false,
    versionId: null,
    versionNo: null,
    pointsPerYuan: 100,
    minRechargeAmountCents: 1000,
    maxRechargeAmountCents: 100000,
    note: '联调占位费率',
    publishedAt: null,
    tiers: [
      {
        tierId: 'tier-5000',
        amountCents: 5000,
        basePoints: 5000,
        giftPoints: 500,
        points: 5500,
        label: '标准档',
        sortOrder: 1,
      },
      {
        tierId: 'tier-10000',
        amountCents: 10000,
        basePoints: 10000,
        giftPoints: 1500,
        points: 11500,
        label: '大额档',
        sortOrder: 2,
      },
    ],
    currency: 'CNY',
    ...overrides,
  }
}

const BASE_ORDER: RechargeOrderDto = {
  id: '9001',
  orderNo: 'R2026092000001',
  status: 'CREATED',
  provider: 'WECHAT_NATIVE',
  amountCents: 5000,
  points: 5500,
  providerOrderNo: null,
  codeUrl: null,
  returnContext: null,
  expiresAt: '2026-09-20T10:30:00Z',
  paidAt: null,
  closedAt: null,
  failedAt: null,
  failureCode: null,
  createdAt: '2026-09-20T10:00:00Z',
  updatedAt: '2026-09-20T10:00:00Z',
}

function orderDto(overrides: Partial<RechargeOrderDto> = {}): RechargeOrderDto {
  return { ...BASE_ORDER, ...overrides }
}

function orderPage(items: RechargeOrderDto[]) {
  return { items, page: 1, size: 10, total: items.length, hasNext: false }
}

function invoiceCapabilityDto(overrides: Partial<InvoiceCapabilityDto> = {}): InvoiceCapabilityDto {
  return {
    applicationsOpen: true,
    applicationsStatus: 'INVOICE_APPLICATIONS_OPEN',
    deliveryProvider: 'MANUAL_EMAIL',
    deliveryConfigured: true,
    deliverable: true,
    deliveryStatus: 'INVOICE_MANUAL_EMAIL',
    deliveryMissing: [],
    webhookConfigured: false,
    webhookStatus: 'INVOICE_WEBHOOK_NOT_CONFIGURED',
    currency: 'CNY',
    ...overrides,
  }
}

const BASE_APPLICATION: InvoiceApplicationDto = {
  id: '7001',
  applicationNo: 'INV202609200001',
  status: 'SUBMITTED',
  invoiceType: 'VAT_NORMAL',
  titleType: 'PERSONAL',
  title: '张三',
  taxNumber: null,
  amountCents: 10000,
  deliveryEmail: 'payer@example.com',
  remark: null,
  rejectReason: null,
  deliveryProvider: null,
  invoiceNumber: null,
  manualSentBy: null,
  webhookStatus: 'WAITING_CONFIG',
  webhookErrorCode: 'WEBHOOK_NOT_CONFIGURED',
  submittedAt: '2026-09-20T09:00:00Z',
  reviewedAt: null,
  approvedAt: null,
  deliveredAt: null,
  rejectedAt: null,
  cancelledAt: null,
  createdAt: '2026-09-20T09:00:00Z',
  updatedAt: '2026-09-20T09:00:00Z',
}

function applicationDto(
  overrides: Partial<InvoiceApplicationDto> = {},
): InvoiceApplicationDto {
  return { ...BASE_APPLICATION, ...overrides }
}

function applicationPage(items: InvoiceApplicationDto[]) {
  return { items, page: 1, size: 10, total: items.length, hasNext: false }
}

const BASE_FEEDBACK: FeedbackDto = {
  id: '8001',
  category: 'PRODUCT',
  content: '希望增加批量导出的入口。',
  contact: null,
  status: 'SUBMITTED',
  reply: null,
  handledAt: null,
  createdAt: '2026-09-20T08:00:00Z',
  updatedAt: '2026-09-20T08:00:00Z',
}

function feedbackDto(overrides: Partial<FeedbackDto> = {}): FeedbackDto {
  return { ...BASE_FEEDBACK, ...overrides }
}

function feedbackPage(items: FeedbackDto[]) {
  return { items, page: 1, size: 10, total: items.length, hasNext: false }
}

// ---------------------------------------------------------------- helpers

function renderConsole(node: ReactElement) {
  return render(
    <ConfigProvider theme={appTheme}>
      <AntdApp>
        <QueryClientProvider client={createQueryClient()}>
          <MemoryRouter>{node}</MemoryRouter>
        </QueryClientProvider>
      </AntdApp>
    </ConfigProvider>,
  )
}

/**
 * Text-first lookup: an antd table row's accessible name costs ~1s per query under jsdom, while a
 * text lookup costs milliseconds. The attached element is what matters, not how it was found.
 *
 * A card title and its submit button can carry the same text (「提交发票申请」), so the match that is
 * actually inside a button wins.
 */
function button(label: string): HTMLButtonElement {
  for (const element of screen.getAllByText(label)) {
    const candidate = element.closest('button')
    if (candidate) return candidate as HTMLButtonElement
  }
  throw new Error(`未找到按钮：${label}`)
}

function rowButton(label: string, index: number): HTMLButtonElement {
  const element = screen.getAllByText(label)[index]?.closest('button')
  if (!element) throw new Error(`未找到第 ${index} 个「${label}」按钮`)
  return element as HTMLButtonElement
}

function selectTier(tierId: string) {
  const input = document.querySelector<HTMLInputElement>(
    `input[type="radio"][value="${tierId}"]`,
  )
  if (!input) throw new Error(`未找到档位：${tierId}`)
  fireEvent.click(input)
}

async function chooseOption(
  user: ReturnType<typeof userEvent.setup>,
  label: string,
  option: string,
) {
  await user.click(screen.getByLabelText(label))
  await user.click(screen.getByText(option, { selector: '.ant-select-item-option-content' }))
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  clearStoredAuthToken()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

// ---------------------------------------------------------------- 充值能力

describe('积分充值的支付能力', () => {
  it('通道未配置时明确展示暂不可充值，按钮禁用并写明原因，且不发创建请求', async () => {
    const user = userEvent.setup()
    const requests = installServer((request) => {
      if (request.path === '/api/v1/billing/recharge-capability') return ok(capabilityDto())
      if (request.path === '/api/v1/billing/pricing/active') return ok(rateCardDto())
      return undefined
    })

    renderConsole(<RechargeConsole />)

    expect(await screen.findByText('暂不可充值：支付通道未配置')).toBeInTheDocument()
    // 服务端的状态 token 原样展示，客户端的措辞不能替代它。
    expect(screen.getByText('PAYMENT_PROVIDER_NOT_CONFIGURED')).toBeInTheDocument()
    expect(screen.getByText('渠道：WECHAT_NATIVE')).toBeInTheDocument()
    expect(screen.getByText('回调验签：不可用')).toBeInTheDocument()
    expect(
      screen.getByText(
        '缺少的配置项：WECHAT_PAY_MCH_ID、WECHAT_PAY_CERT_SERIAL_NO（仅列出名称，不含任何密钥值）',
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/单笔范围：¥10\.00 – ¥1000\.00；订单有效期 1800 秒。/),
    ).toBeInTheDocument()

    // 费率卡没有被当成正式定价：没有版本号时不会编造版本号。
    expect(screen.getByText('当前费率：100 积分 / 元')).toBeInTheDocument()
    expect(screen.getByText('非生产费率（占位）')).toBeInTheDocument()
    expect(screen.getByText('发布时间：—（未发布）')).toBeInTheDocument()

    selectTier('tier-5000')
    expect(
      await screen.findByText('标准档：¥50.00 · 到账 5,500 积分（含赠送 500）'),
    ).toBeInTheDocument()
    expect(screen.getByText(/应付 ¥50\.00/)).toBeInTheDocument()

    expect(button('创建充值订单')).toBeDisabled()
    expect(screen.getByText('当前不可创建充值订单：暂不可充值：支付通道未配置。')).toBeInTheDocument()
    expect(screen.getByText('按钮不可用的原因：暂不可充值：支付通道未配置')).toBeInTheDocument()

    await user.click(button('创建充值订单'))
    expect(posts(requests, '/api/v1/billing/recharge-orders')).toHaveLength(0)
    expect(requests.some((request) => request.path === '/api/v1/billing/recharge-orders')).toBe(
      false,
    )
  })

  it('充值能力查询失败时给出服务端原因，按钮不可用且不推测', async () => {
    installServer((request) => {
      if (request.path === '/api/v1/billing/recharge-capability') {
        return fail('服务暂时不可用')
      }
      if (request.path === '/api/v1/billing/pricing/active') return ok(rateCardDto())
      return undefined
    })

    renderConsole(<RechargeConsole />)

    expect(await screen.findByText('充值能力查询失败')).toBeInTheDocument()
    expect(
      screen.getByText('服务暂时不可用'),
    ).toBeInTheDocument()
    expect(button('创建充值订单')).toBeDisabled()
    expect(screen.getByText('按钮不可用的原因：充值能力尚未返回')).toBeInTheDocument()
  })

  it('服务端判定可支付时创建订单带幂等键：同额复用，改额轮换', async () => {
    // 这一条验证的是契约的另一分支：服务端返回就绪时客户端仍然只是下单，不产生任何支付成功结论。
    const user = userEvent.setup()
    const requests = installServer((request) => {
      if (request.path === '/api/v1/billing/recharge-capability') {
        return ok(
          capabilityDto({
            configured: true,
            payable: true,
            callbackVerifiable: true,
            status: 'PAYMENT_PROVIDER_READY',
            missingRequirements: [],
          }),
        )
      }
      if (request.path === '/api/v1/billing/pricing/active') return ok(rateCardDto())
      if (request.method === 'POST' && request.path === '/api/v1/billing/recharge-orders') {
        return ok(orderDto())
      }
      return undefined
    })

    renderConsole(<RechargeConsole />)
    await screen.findByText('支付通道已就绪')

    selectTier('tier-5000')
    await screen.findByText(/应付 ¥50\.00/)
    expect(button('创建充值订单')).toBeEnabled()

    await user.click(button('创建充值订单'))
    await screen.findByText('R2026092000001')

    const first = posts(requests, '/api/v1/billing/recharge-orders')[0]
    expect(first.body).toEqual({ amountCents: 5000, returnContext: null })
    expect(first.idempotencyKey).toMatch(UUID_PATTERN)

    // 同一金额重试：沿用同一个幂等键，服务端才能识别为同一次动作的重放。
    await user.click(button('创建充值订单'))
    await waitFor(() =>
      expect(posts(requests, '/api/v1/billing/recharge-orders')).toHaveLength(2),
    )
    const second = posts(requests, '/api/v1/billing/recharge-orders')[1]
    expect(second.idempotencyKey).toBe(first.idempotencyKey)

    // 金额变了就是另一次动作，键也必须换。
    fireEvent.change(screen.getByLabelText('自定义充值金额（元）'), { target: { value: '88' } })
    await screen.findByText(/应付 ¥88\.00/)
    await user.click(button('创建充值订单'))
    await waitFor(() =>
      expect(posts(requests, '/api/v1/billing/recharge-orders')).toHaveLength(3),
    )
    const third = posts(requests, '/api/v1/billing/recharge-orders')[2]
    expect(third.body).toEqual({ amountCents: 8800, returnContext: null })
    expect(third.idempotencyKey).not.toBe(first.idempotencyKey)

    // 通道返回的订单是待支付，没有任何二维码：页面必须说清楚这一点，而不是画一个占位二维码。
    expect(
      screen.getByText('未生成：支付通道未启用，本环境不会产生真实支付二维码'),
    ).toBeInTheDocument()
    expect(screen.getByText('—（通道未返回）')).toBeInTheDocument()
    expect(screen.queryByText('已到账')).not.toBeInTheDocument()
    expect(document.querySelector('img, canvas')).toBeNull()
    expect(screen.getByText('查询支付结果')).toBeInTheDocument()
    expect(screen.getByText('关闭订单')).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------- 充值记录

describe('充值记录', () => {
  const orders = [
    orderDto(),
    orderDto({ id: '9002', orderNo: 'R2026092000002', status: 'EXPIRED' }),
    orderDto({
      id: '9003',
      orderNo: 'R2026092000003',
      status: 'CLOSED',
      closedAt: '2026-09-20T10:05:00Z',
    }),
    orderDto({
      id: '9004',
      orderNo: 'R2026092000004',
      status: 'SUCCEEDED',
      paidAt: '2026-09-20T10:02:00Z',
      providerOrderNo: '4200000000202609200000000001',
    }),
  ]

  function listHandler(request: RecordedRequest): Response | undefined {
    if (request.method === 'GET' && request.path === '/api/v1/billing/recharge-orders') {
      const status = new URLSearchParams(request.search).get('status')
      return ok(orderPage(status ? orders.filter((item) => item.status === status) : orders))
    }
    if (request.method === 'GET' && request.path.startsWith('/api/v1/billing/recharge-orders/')) {
      const id = request.path.split('/').pop()
      const found = orders.find((item) => item.id === id)
      return found ? ok(found) : undefined
    }
    return undefined
  }

  it('订单状态按服务端返回逐条呈现，终态不再提供查询或关闭', async () => {
    const user = userEvent.setup()
    const requests = installServer(listHandler)

    renderConsole(<RechargeOrderConsole />)

    expect(await screen.findByText('R2026092000001')).toBeInTheDocument()
    // 已过期和已关闭是两种不同结局，不能被合并成一个「已结束」。
    for (const label of ['待支付', '已过期', '已关闭', '已到账']) {
      expect(screen.getByText(label)).toBeInTheDocument()
    }

    await user.click(rowButton('查看状态', 1))
    expect(
      await screen.findByText('该订单已进入终态（已过期），不再提供查询或关闭操作。'),
    ).toBeInTheDocument()
    expect(screen.queryByText('查询支付结果')).not.toBeInTheDocument()

    await user.click(rowButton('查看状态', 0))
    expect(await screen.findByText('查询支付结果')).toBeInTheDocument()
    expect(screen.getByText('关闭订单')).toBeInTheDocument()

    // 状态筛选是服务端查询参数，不是对当前一页做的本地切片。
    await chooseOption(user, '订单状态筛选', '已过期')
    await waitFor(() =>
      expect(
        requests.some(
          (request) =>
            request.path === '/api/v1/billing/recharge-orders' &&
            request.search.includes('status=EXPIRED'),
        ),
      ).toBe(true),
    )
    expect(await screen.findByText('R2026092000002')).toBeInTheDocument()
    expect(screen.queryByText('R2026092000001')).not.toBeInTheDocument()
  })

  it('查询与关闭只转述服务端结论，不产生支付成功', async () => {
    const user = userEvent.setup()
    let current = orderDto()
    const requests = installServer((request) => {
      if (request.method === 'GET' && request.path === '/api/v1/billing/recharge-orders') {
        return ok(orderPage([current]))
      }
      if (request.method === 'POST' && request.path.endsWith('/query')) {
        return ok(current)
      }
      if (request.method === 'POST' && request.path.endsWith('/close')) {
        current = orderDto({ status: 'CLOSED', closedAt: '2026-09-20T10:05:00Z' })
        return ok(current)
      }
      if (request.method === 'GET' && request.path.startsWith('/api/v1/billing/recharge-orders/')) {
        return ok(current)
      }
      return undefined
    })

    renderConsole(<RechargeOrderConsole />)
    await user.click(await screen.findByText('查看状态'))

    await user.click(button('查询支付结果'))
    expect(
      await screen.findByText('已向服务端确认：订单仍为「待支付」，没有支付成功的结论。'),
    ).toBeInTheDocument()
    expect(screen.queryByText('已到账')).not.toBeInTheDocument()
    expect(posts(requests, '/api/v1/billing/recharge-orders/9001/query')).toHaveLength(1)

    await user.click(button('关闭订单'))
    expect(await screen.findByText('订单已关闭，服务端状态：CLOSED')).toBeInTheDocument()
    expect(
      await screen.findByText('该订单已进入终态（已关闭），不再提供查询或关闭操作。'),
    ).toBeInTheDocument()
    expect(screen.queryByText('查询支付结果')).not.toBeInTheDocument()
  })
})

// ---------------------------------------------------------------- 发票

describe('发票申请', () => {
  it('历史 DELIVERED 记录不被误写为本次人工发件确认', () => {
    const historic = toInvoiceApplicationView(applicationDto({
      status: 'DELIVERED',
      deliveryProvider: 'SMTP',
      deliveredAt: '2026-09-20T12:00:00Z',
    }))
    expect(historic.statusLabel).toBe('历史完成记录（请核实）')
    expect(historic.statusHint).not.toContain('管理员已记录发票人工发送')
  })

  it('飞书未配置时明确提示申请待推送，列表为空是真实空态', async () => {
    installServer((request) => {
      if (request.path === '/api/v1/billing/invoice-capability') return ok(invoiceCapabilityDto())
      if (request.path === '/api/v1/billing/invoices') return ok(applicationPage([]))
      return undefined
    })

    renderConsole(<InvoiceConsole />)

    expect(await screen.findByText('发票申请已开放')).toBeInTheDocument()
    expect(screen.getByText('INVOICE_APPLICATIONS_OPEN')).toBeInTheDocument()
    expect(screen.getByText('飞书通知尚未配置')).toBeInTheDocument()
    expect(screen.getByText('管理员线下开票并人工邮件发送')).toBeInTheDocument()
    expect(screen.getByText('INVOICE_MANUAL_EMAIL')).toBeInTheDocument()
    expect(screen.getByText(/飞书通知通道未配置/)).toBeInTheDocument()

    expect(button('提交发票申请')).toBeDisabled()
    expect(screen.getByText('按钮不可用的原因：发票抬头不能为空；请填写开票金额；请填写接收邮箱')).toBeInTheDocument()
    expect(await screen.findByText('暂无发票申请：当前账户还没有提交过开票申请。')).toBeInTheDocument()
  })

  it('发票模块未开放时不可提交，并给出服务端状态 token', () => {
    installServer((request) => {
      if (request.path === '/api/v1/billing/invoice-capability') {
        return ok(
          invoiceCapabilityDto({
            applicationsOpen: false,
            applicationsStatus: 'INVOICE_MODULE_DISABLED',
          }),
        )
      }
      if (request.path === '/api/v1/billing/invoices') return ok(applicationPage([]))
      return undefined
    })

    renderConsole(<InvoiceConsole />)

    return screen.findByText('当前不可提交发票申请（INVOICE_MODULE_DISABLED）').then(() => {
      expect(button('提交发票申请')).toBeDisabled()
      expect(
        screen.getByText('按钮不可用的原因：发票申请未开放（INVOICE_MODULE_DISABLED）'),
      ).toBeInTheDocument()
    })
  })

  it('提交发票申请按服务端规则传值，审核通过只显示未开具，仅提交中与审核中可撤销', async () => {
    const user = userEvent.setup()
    const requests = installServer((request) => {
      if (request.path === '/api/v1/billing/invoice-capability') return ok(invoiceCapabilityDto())
      if (request.method === 'GET' && request.path === '/api/v1/billing/invoices') {
        return ok(
          applicationPage([
            applicationDto(),
            applicationDto({
              id: '7002',
              applicationNo: 'INV202609200002',
              status: 'APPROVED',
              approvedAt: '2026-09-20T11:00:00Z',
            }),
            applicationDto({
              id: '7003',
              applicationNo: 'INV202609200003',
              status: 'DELIVERED',
              deliveryProvider: 'MANUAL_EMAIL',
              invoiceNumber: 'FP-20260920-01',
              manualSentBy: '1001',
              deliveredAt: '2026-09-20T12:00:00Z',
            }),
            applicationDto({ id: '7004', applicationNo: 'INV202609200004', status: 'CANCELLED' }),
          ]),
        )
      }
      if (request.method === 'POST' && request.path === '/api/v1/billing/invoices') {
        return ok(applicationDto())
      }
      if (request.method === 'POST' && request.path === '/api/v1/billing/invoices/7001/cancel') {
        return ok(applicationDto({ status: 'CANCELLED', cancelledAt: '2026-09-20T10:00:00Z' }))
      }
      return undefined
    })

    renderConsole(<InvoiceConsole />)
    await screen.findByText('INV202609200001')

    // 审核通过不是已开具：内部审核时间不会被当成开票日期。
    expect(screen.getByText('审核通过（未开具）')).toBeInTheDocument()
    expect(screen.getByText('管理员已确认发出')).toBeInTheDocument()
    expect(screen.getByText('已撤销')).toBeInTheDocument()
    expect(screen.getAllByText('不可撤销')).toHaveLength(3)
    expect(screen.getAllByText('撤销申请')).toHaveLength(1)

    fireEvent.change(screen.getByLabelText('发票抬头'), { target: { value: '张三' } })
    fireEvent.change(screen.getByLabelText('开票金额（元）'), { target: { value: '100' } })
    fireEvent.change(screen.getByLabelText('接收邮箱'), { target: { value: 'payer@example.com' } })
    await waitFor(() => expect(button('提交发票申请')).toBeEnabled())

    await user.click(button('提交发票申请'))
    await waitFor(() => expect(posts(requests, '/api/v1/billing/invoices')).toHaveLength(1))
    const created = posts(requests, '/api/v1/billing/invoices')[0]
    // 个人抬头显式传 null，而不是省略字段或传空串。
    expect(created.body).toEqual({
      invoiceType: 'VAT_NORMAL',
      titleType: 'PERSONAL',
      title: '张三',
      taxNumber: null,
      amountCents: 10000,
      deliveryEmail: 'payer@example.com',
      remark: null,
    })
    expect(created.idempotencyKey).toMatch(UUID_PATTERN)
    expect(await screen.findByText('已提交，服务端状态：已提交')).toBeInTheDocument()

    await user.click(button('撤销申请'))
    expect(await screen.findByText('撤销申请 INV202609200001')).toBeInTheDocument()
    await user.click(button('确认撤销'))
    expect(await screen.findByText('撤销结果')).toBeInTheDocument()
    expect(screen.getByText('服务端返回的状态：已撤销')).toBeInTheDocument()
    expect(posts(requests, '/api/v1/billing/invoices/7001/cancel')).toHaveLength(1)
  })
})

// ---------------------------------------------------------------- 反馈

describe('意见反馈', () => {
  it('运营回复按纯文本展示，未回复明确标注，提交内容与联系方式遵循契约', async () => {
    const user = userEvent.setup()
    const requests = installServer((request) => {
      if (request.method === 'GET' && request.path === '/api/v1/billing/feedback') {
        return ok(
          feedbackPage([
            feedbackDto({
              id: '8002',
              content: '充值没到账',
              category: 'BILLING',
              status: 'RESOLVED',
              reply: '<img src=x onerror=alert(1)>已处理',
              handledAt: '2026-09-20T09:30:00Z',
            }),
            feedbackDto(),
          ]),
        )
      }
      if (request.method === 'POST' && request.path === '/api/v1/billing/feedback') {
        return ok(feedbackDto({ id: '8003', content: '发票没收到', category: 'BILLING' }))
      }
      return undefined
    })

    renderConsole(<FeedbackConsole />)
    await screen.findByText('充值没到账')

    expect(screen.getByText('<img src=x onerror=alert(1)>已处理')).toBeInTheDocument()
    expect(document.querySelector('img[src="x"]')).toBeNull()
    expect(screen.getByText('尚未回复')).toBeInTheDocument()
    expect(screen.getByText('已解决')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('反馈内容'), { target: { value: '发票没收到' } })
    await chooseOption(user, '反馈类型', '充值与发票')
    await waitFor(() => expect(button('提交反馈')).toBeEnabled())

    await user.click(button('提交反馈'))
    await waitFor(() => expect(posts(requests, '/api/v1/billing/feedback')).toHaveLength(1))
    const created = posts(requests, '/api/v1/billing/feedback')[0]
    // 未填写联系方式时显式传 null。
    expect(created.body).toEqual({ category: 'BILLING', content: '发票没收到', contact: null })
    expect(created.idempotencyKey).toMatch(UUID_PATTERN)
    expect(await screen.findByText('已提交，服务端状态：已提交')).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------- 空态

describe('三个列表的空态', () => {
  it('没有数据时给出各自的真实空态，不用样例占位', async () => {
    installServer((request) => {
      if (request.path === '/api/v1/billing/recharge-orders') return ok(orderPage([]))
      if (request.path === '/api/v1/billing/invoices') return ok(applicationPage([]))
      if (request.path === '/api/v1/billing/feedback') return ok(feedbackPage([]))
      if (request.path === '/api/v1/billing/invoice-capability') return ok(invoiceCapabilityDto())
      return undefined
    })

    renderConsole(
      <div>
        <RechargeOrderConsole />
        <InvoiceConsole />
        <FeedbackConsole />
      </div>,
    )

    expect(await screen.findByText('暂无充值订单：当前账户还没有任何充值记录。')).toBeInTheDocument()
    expect(screen.getByText('暂无发票申请：当前账户还没有提交过开票申请。')).toBeInTheDocument()
    expect(screen.getByText('暂无反馈：当前账户还没有提交过意见反馈。')).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------- 来源绑定

describe('通知业务回链', () => {
  function notificationTargetRoutes(request: RecordedRequest): Response | undefined {
    if (request.path === '/api/v1/billing/point-account') return ok({ userId: '1001', availablePoints: 100, frozenPoints: 0, version: 1 })
    if (request.path === '/api/v1/billing/notifications/unread-count') return ok({ unread: 0 })
    if (request.path === '/api/v1/billing/point-ledger') return ok({ items: [], page: 1, size: 20, total: 0, hasNext: false })
    if (request.path === '/api/v1/billing/recharge-orders') return ok(orderPage([]))
    if (request.path === '/api/v1/billing/invoices') return ok(applicationPage([]))
    if (request.path === '/api/v1/billing/feedback') return ok(feedbackPage([]))
    if (request.path === '/api/v1/billing/invoice-capability') return ok(invoiceCapabilityDto())
    return undefined
  }

  it.each([
    ['orders', '9001', '/api/v1/billing/recharge-orders/9001', orderDto({ status: 'SUCCEEDED' }), '订单 R2026092000001'],
    ['invoices', '7001', '/api/v1/billing/invoices/7001', applicationDto({ status: 'REJECTED', rejectReason: '抬头有误，请核对' }), '抬头有误，请核对'],
    ['feedback', '8001', '/api/v1/billing/feedback/8001', feedbackDto({ status: 'RESOLVED', reply: '问题已处理，请重试' }), '问题已处理，请重试'],
  ])('%s：即使指定记录不在第一页，也直接查询通知关联详情', async (tab, id, detailPath, dto, text) => {
    const requests = installServer((request) => request.path === detailPath ? ok(dto) : notificationTargetRoutes(request))
    renderApp([`/app/points-billing?tab=${tab}&targetId=${id}`])
    expect(await screen.findByText(text)).toBeInTheDocument()
    expect(requests.some((request) => request.path === detailPath && request.method === 'GET')).toBe(true)
    expect(requests.every((request) => request.method === 'GET')).toBe(true)
  })

  it('通知关联记录不可访问时明确报错，不将详情查询失败当作空列表', async () => {
    installServer((request) => request.path === '/api/v1/billing/feedback/9999' ? fail('反馈记录不存在或不可访问', 404) : notificationTargetRoutes(request))
    renderApp(['/app/points-billing?tab=feedback&targetId=9999'])
    expect(await screen.findByText('关联反馈无法加载')).toBeInTheDocument()
    expect(screen.getByText(/反馈记录不存在或不可访问/)).toBeInTheDocument()
  })

  it('积分通知精确筛选关联流水，并可返回全部明细', async () => {
    const user = userEvent.setup()
    const requests = installServer((request) => {
      if (request.path === '/api/v1/billing/point-ledger' && request.search.includes('ledgerId=123')) return ok({
        items: [{ id: '123', entryType: 'ADMIN_ADJUSTMENT', businessType: 'ADMIN_ADJUSTMENT', businessId: '456', title: '运营补偿到账', amountPoints: 50, availableDelta: 50, frozenDelta: 0, availableAfter: 150, frozenAfter: 0, createdAt: '2026-09-22T00:00:00Z' }],
        page: 1, size: 20, total: 1, hasNext: false,
      })
      return notificationTargetRoutes(request)
    })
    renderApp(['/app/points-billing?tab=ledger&targetId=123'])
    expect(await screen.findByText('运营补偿到账')).toBeInTheDocument()
    expect(screen.getByText('+50')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '查看全部明细' }))
    expect(await screen.findByText('暂无积分明细')).toBeInTheDocument()
    expect(requests.some((request) => request.path === '/api/v1/billing/point-ledger' && !request.search.includes('ledgerId='))).toBe(true)
  })
})

describe('充值来源绑定', () => {
  function routes(): Handler {
    return (request) => {
      if (request.path === '/api/v1/billing/point-account') {
        return ok({ userId: '1001', availablePoints: 0, frozenPoints: 0, version: 1 })
      }
      if (request.path === '/api/v1/billing/point-ledger') {
        return ok({ items: [], page: 1, size: 20, total: 0, hasNext: false })
      }
      if (request.path === '/api/v1/billing/notifications/unread-count') return ok({ unread: 0 })
      if (request.path === '/api/v1/billing/recharge-capability') {
        return ok(
          capabilityDto({
            configured: true,
            payable: true,
            callbackVerifiable: true,
            status: 'PAYMENT_PROVIDER_READY',
            missingRequirements: [],
          }),
        )
      }
      if (request.path === '/api/v1/billing/pricing/active') return ok(rateCardDto())
      if (request.method === 'POST' && request.path === '/api/v1/billing/recharge-orders') {
        return ok(orderDto({ returnContext: 'TASK_DRAFT:123' }))
      }
      return undefined
    }
  }

  it('带着来源标识进入时打开充值页，标识按文本展示并随订单提交', async () => {
    const user = userEvent.setup()
    const requests = installServer(routes())

    renderApp(['/app/points-billing?returnContext=TASK_DRAFT:123'])

    expect(await screen.findByText('本次充值将绑定来源：任务草稿')).toBeInTheDocument()
    expect(
      screen.getByText('来源标识 TASK_DRAFT:123（按文本展示，不作为链接地址）；充值完成后可回到原页面继续。'),
    ).toBeInTheDocument()
    expect(document.querySelector('a[href*="TASK_DRAFT"]')).toBeNull()

    selectTier('tier-5000')
    await screen.findByText(/应付 ¥50\.00/)
    await user.click(button('创建充值订单'))

    await waitFor(() =>
      expect(posts(requests, '/api/v1/billing/recharge-orders')).toHaveLength(1),
    )
    expect(posts(requests, '/api/v1/billing/recharge-orders')[0].body).toEqual({
      amountCents: 5000,
      returnContext: 'TASK_DRAFT:123',
    })

    // 订单上的来源由客户端按已知路由解析，原始标识不会被当成链接地址。
    expect(await screen.findByText('返回对话继续确认草稿')).toHaveAttribute('href', '/app/chat')
    expect(document.querySelector('a[href*="TASK_DRAFT"]')).toBeNull()
  })

  it('来源标识格式不合法时忽略并明确告警，不会随订单提交', async () => {
    const user = userEvent.setup()
    const requests = installServer(routes())

    renderApp(['/app/points-billing?returnContext=https%3A%2F%2Fevil.example.com%2Fpay'])

    expect(await screen.findByText('链接中的来源标识格式不正确，已忽略')).toBeInTheDocument()
    expect(
      screen.getByText('收到的值为「https://evil.example.com/pay」，不会随充值订单提交。'),
    ).toBeInTheDocument()
    expect(document.querySelector('a[href*="evil.example.com"]')).toBeNull()

    selectTier('tier-5000')
    await screen.findByText(/应付 ¥50\.00/)
    await user.click(button('创建充值订单'))

    await waitFor(() =>
      expect(posts(requests, '/api/v1/billing/recharge-orders')).toHaveLength(1),
    )
    expect(posts(requests, '/api/v1/billing/recharge-orders')[0].body).toEqual({
      amountCents: 5000,
      returnContext: null,
    })
  })
})

import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App as AntdApp, ConfigProvider } from 'antd'
import type { ReactElement } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { InvoiceApplicationDto, RechargeOrderDto } from '../api/billingApi'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { storeAuthToken } from '../auth/tokenStorage'
import { AdminInvoiceConsole } from '../features/admin-billing/invoices'
import { AdminRechargeOrdersConsole } from '../features/admin-billing/orders'
import { useAuthStore } from '../stores/authStore'
import {
  adminFixture,
  jsonResponse,
  testAccessToken,
  testExpiresAt,
  testTraceId,
} from './fixtures/auth'

/**
 * Two admin consoles, one server. Every response below is written as the generated contract type, so
 * a fixture that drifted from the contract would fail to compile rather than silently pass.
 */

const expiredOrder: RechargeOrderDto = {
  id: '7001',
  orderNo: 'R20260920001',
  status: 'EXPIRED',
  provider: 'ALIPAY',
  amountCents: 120000,
  points: 12000,
  providerOrderNo: null,
  codeUrl: null,
  returnContext: 'TASK_DRAFT:123',
  expiresAt: '2026-09-20T01:00:00Z',
  paidAt: null,
  closedAt: null,
  failedAt: null,
  failureCode: null,
  createdAt: '2026-09-20T00:00:00Z',
  updatedAt: '2026-09-20T01:00:00Z',
}

const closedOrder: RechargeOrderDto = {
  id: '7002',
  orderNo: 'R20260920002',
  status: 'CLOSED',
  provider: 'WECHAT_PAY',
  amountCents: 5000,
  points: 500,
  providerOrderNo: 'wx-0002',
  codeUrl: 'weixin://wxpay/bizpayurl?pr=raw-payment-code',
  returnContext: null,
  expiresAt: '2026-09-20T05:00:00Z',
  paidAt: null,
  closedAt: '2026-09-20T03:00:00Z',
  failedAt: null,
  failureCode: 'CLOSED_BY_USER',
  createdAt: '2026-09-20T02:00:00Z',
  updatedAt: '2026-09-20T03:00:00Z',
}

function orderPage(items: readonly RechargeOrderDto[]) {
  return { items, page: 1, size: 20, total: items.length, hasNext: false }
}

const submittedApplication: InvoiceApplicationDto = {
  id: '8001',
  applicationNo: 'INV20260920001',
  status: 'SUBMITTED',
  invoiceType: 'VAT_NORMAL',
  titleType: 'COMPANY',
  title: '杭州光禾AI智能体框架科技有限公司',
  taxNumber: '91330100MA2TEST00X',
  amountCents: 500000,
  deliveryEmail: 'finance@example.com',
  remark: '请开具电子发票',
  rejectReason: null,
  deliveryProvider: null,
  invoiceNumber: null,
  manualSentBy: null,
  webhookStatus: 'WAITING_CONFIG',
  webhookErrorCode: 'WEBHOOK_NOT_CONFIGURED',
  submittedAt: '2026-09-20T02:00:00Z',
  reviewedAt: null,
  approvedAt: null,
  deliveredAt: null,
  rejectedAt: null,
  cancelledAt: null,
  createdAt: '2026-09-20T02:00:00Z',
  updatedAt: '2026-09-20T02:00:00Z',
}

const approvedApplication: InvoiceApplicationDto = {
  ...submittedApplication,
  status: 'APPROVED',
  reviewedAt: '2026-09-20T03:00:00Z',
  approvedAt: '2026-09-20T04:00:00Z',
  updatedAt: '2026-09-20T04:00:00Z',
}

const deliveredApplication: InvoiceApplicationDto = {
  ...approvedApplication,
  status: 'DELIVERED',
  deliveryProvider: 'MANUAL_EMAIL',
  invoiceNumber: 'FP-20260920-01',
  manualSentBy: '1001',
  deliveredAt: '2026-09-20T05:00:00Z',
  updatedAt: '2026-09-20T05:00:00Z',
}

function invoicePage(items: readonly InvoiceApplicationDto[]) {
  return { items, page: 1, size: 20, total: items.length, hasNext: false }
}

function ok(data: unknown): Response {
  return jsonResponse({ code: 'OK', message: 'success', data, traceId: testTraceId })
}

function apiFailure(status: number, code: string, message: string): Response {
  return jsonResponse({ code, message, details: {}, traceId: testTraceId }, status)
}

type RouteHandler = (init: RequestInit) => Response

/** Routes on `METHOD /path`, ignoring the query string. An unrouted call fails loudly. */
function installFetch(routes: Readonly<Record<string, RouteHandler>>) {
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const raw =
      typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = (init?.method ?? 'GET').toUpperCase()
    const key = `${method} ${raw.split('?')[0]}`
    const handler = routes[key]
    if (!handler) throw new Error(`Unexpected request: ${key}`)
    return handler(init ?? {})
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function calledUrls(fetchMock: ReturnType<typeof installFetch>): string[] {
  return fetchMock.mock.calls.map(([input]) =>
    typeof input === 'string' ? input : input instanceof URL ? input.href : input.url,
  )
}

function renderConsole(element: ReactElement) {
  return render(
    <ConfigProvider theme={appTheme}>
      <AntdApp>
        <QueryClientProvider client={createQueryClient()}>{element}</QueryClientProvider>
      </AntdApp>
    </ConfigProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  window.localStorage.clear()
  window.sessionStorage.clear()
})

describe('管理员充值订单台', () => {
  it('渲染服务端订单行，EXPIRED 与 CLOSED 是两个不同状态', async () => {
    installFetch({
      'GET /api/v1/admin/billing/recharge-orders': () =>
        ok(orderPage([expiredOrder, closedOrder])),
    })
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    renderConsole(<AdminRechargeOrdersConsole />)

    const table = await screen.findByRole('table', { name: '充值订单列表' })
    expect(table.parentElement).toHaveClass('admin-table-scroll')
    expect(screen.getByText('共 2 条').parentElement).toHaveClass('admin-observability-footer')
    const rows = within(table).getAllByRole('row').slice(1)

    expect(within(rows[0]).getByText('EXPIRED')).toBeInTheDocument()
    expect(within(rows[1]).getByText('CLOSED')).toBeInTheDocument()
    expect(within(rows[0]).queryByText('CLOSED')).not.toBeInTheDocument()
    expect(within(rows[1]).queryByText('EXPIRED')).not.toBeInTheDocument()

    expect(within(rows[0]).getByText('R20260920001')).toBeInTheDocument()
    expect(within(rows[0]).getByText('¥1200.00')).toBeInTheDocument()
    expect(within(rows[1]).getByText('¥50.00')).toBeInTheDocument()
    expect(within(rows[0]).getByText('TASK_DRAFT:123')).toBeInTheDocument()
    expect(within(rows[1]).getByText('CLOSED_BY_USER')).toBeInTheDocument()
    // No user filter is applied, so the contract gives no account to attribute the row to.
    expect(within(rows[0]).getAllByText('—').length).toBeGreaterThan(0)
  })

  it('服务端返回空列表时展示真实空态', async () => {
    installFetch({
      'GET /api/v1/admin/billing/recharge-orders': () => ok(orderPage([])),
    })
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    renderConsole(<AdminRechargeOrdersConsole />)

    expect(await screen.findByText('暂无充值订单')).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: '充值订单列表' })).not.toBeInTheDocument()
  })

  it('按用户 ID 筛选把账号带回列表与详情，而 codeUrl 只作为文本展示', async () => {
    const user = userEvent.setup()
    const fetchMock = installFetch({
      'GET /api/v1/admin/billing/recharge-orders': () => ok(orderPage([closedOrder])),
      'GET /api/v1/admin/billing/recharge-orders/7002': () => ok(closedOrder),
    })
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    renderConsole(<AdminRechargeOrdersConsole />)

    await screen.findByRole('table', { name: '充值订单列表' })
    await user.type(screen.getByLabelText('用户 ID'), '9001')
    await user.click(screen.getByRole('button', { name: '查询' }))

    await waitFor(() =>
      expect(calledUrls(fetchMock).some((url) => url.includes('userId=9001'))).toBe(true),
    )
    await waitFor(() => {
      const table = screen.getByRole('table', { name: '充值订单列表' })
      expect(within(table).getByText('••01')).toBeInTheDocument()
    })

    const filteredRow = within(
      screen.getByRole('table', { name: '充值订单列表' }),
    ).getAllByRole('row')[1]
    await user.click(within(filteredRow).getByRole('button', { name: '查看订单' }))
    const drawer = await screen.findByRole('dialog')
    expect(await within(drawer).findByText(/weixin:\/\/wxpay\/bizpayurl\?pr=raw-payment-code/)).toBeInTheDocument()
    // The payment code is a provider-issued value, not a payable QR code in this environment.
    expect(within(drawer).getByText(/不是可支付的二维码/)).toBeInTheDocument()
    expect(within(drawer).queryByRole('link')).not.toBeInTheDocument()
    // No image, no anchor: the payment code stays text.
    expect(drawer.querySelector('img')).toBeNull()
    expect(drawer.querySelector('a[href]')).toBeNull()
  })

  it('对账返回未变化的状态时不声称积分已经入账', async () => {
    const user = userEvent.setup()
    const fetchMock = installFetch({
      'GET /api/v1/admin/billing/recharge-orders': () => ok(orderPage([expiredOrder])),
      'GET /api/v1/admin/billing/recharge-orders/7001': () => ok(expiredOrder),
      'POST /api/v1/admin/billing/recharge-orders/7001/reconcile': () => ok(expiredOrder),
    })
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    renderConsole(<AdminRechargeOrdersConsole />)

    const table = await screen.findByRole('table', { name: '充值订单列表' })
    const listCallsBefore = calledUrls(fetchMock).filter((url) =>
      url.startsWith('/api/v1/admin/billing/recharge-orders?'),
    ).length
    await user.click(
      within(within(table).getAllByRole('row')[1]).getByRole('button', { name: '查看订单' }),
    )
    await user.click(await screen.findByRole('button', { name: '对账' }))

    await waitFor(() =>
      expect(screen.getAllByText(/渠道报告无变化：状态仍为 EXPIRED/).length).toBeGreaterThan(0),
    )
    expect(
      screen.getAllByText(/本次对账没有发生任何余额变化/).length,
    ).toBeGreaterThan(0)
    // The provider reported no change, so nothing may be presented as a credit.
    expect(screen.queryByText(/状态由 EXPIRED 变为/)).not.toBeInTheDocument()
    expect(screen.queryByText(/积分已入账|已入账成功|余额已增加/)).not.toBeInTheDocument()

    await waitFor(() =>
      expect(
        calledUrls(fetchMock).filter((url) =>
          url.startsWith('/api/v1/admin/billing/recharge-orders?'),
        ).length,
      ).toBeGreaterThan(listCallsBefore),
    )
  })
})

describe('管理员发票申请台', () => {
  it('渲染服务端申请分页并展示真实空态', async () => {
    installFetch({
      'GET /api/v1/admin/billing/invoices': () =>
        ok(invoicePage([submittedApplication, approvedApplication])),
    })
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    const { unmount } = renderConsole(<AdminInvoiceConsole />)

    const table = await screen.findByRole('table', { name: '发票申请列表' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(within(rows[0]).getByText('INV20260920001')).toBeInTheDocument()
    expect(within(rows[0]).getByText('SUBMITTED')).toBeInTheDocument()
    expect(within(rows[1]).getByText('APPROVED')).toBeInTheDocument()
    expect(within(rows[0]).getByText('增值税普通发票')).toBeInTheDocument()
    expect(within(rows[0]).getByText('¥5000.00')).toBeInTheDocument()
    unmount()

    installFetch({ 'GET /api/v1/admin/billing/invoices': () => ok(invoicePage([])) })
    renderConsole(<AdminInvoiceConsole />)
    expect(await screen.findByText('暂无发票申请')).toBeInTheDocument()
  })

  it('SUBMITTED 申请只提供受理与驳回，且驳回原因必填', async () => {
    const user = userEvent.setup()
    const fetchMock = installFetch({
      'GET /api/v1/admin/billing/invoices': () => ok(invoicePage([submittedApplication])),
      'GET /api/v1/admin/billing/invoices/8001': () => ok(submittedApplication),
      'POST /api/v1/admin/billing/invoices/8001/reject': () =>
        ok({
          ...submittedApplication,
          status: 'REJECTED',
          rejectReason: '抬头与税号不一致',
          rejectedAt: '2026-09-20T06:00:00Z',
        }),
    })
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    renderConsole(<AdminInvoiceConsole />)

    const table = await screen.findByRole('table', { name: '发票申请列表' })
    await user.click(
      within(within(table).getAllByRole('row')[1]).getByRole('button', { name: '查看申请' }),
    )

    expect(await screen.findByRole('button', { name: '受理' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '驳回' })).toBeEnabled()
    // A SUBMITTED application is not APPROVED, so delivery is not offered at all.
    expect(screen.queryByRole('button', { name: '确认已人工发件' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '审核通过' })).not.toBeInTheDocument()
    expect(screen.getByText(/只有 APPROVED 的申请在实际开票并人工发件后才可确认/)).toBeInTheDocument()
    expect(screen.getAllByText(/当前状态为 SUBMITTED/).length).toBeGreaterThan(0)

    await user.click(screen.getByRole('button', { name: '驳回' }))
    const confirm = await screen.findByRole('button', { name: '确认驳回' })
    expect(confirm).toBeDisabled()
    expect(screen.getByText(/驳回原因尚未填写，因此无法提交/)).toBeInTheDocument()

    await user.type(screen.getByLabelText('驳回原因'), '  抬头与税号不一致  ')
    await waitFor(() => expect(screen.getByRole('button', { name: '确认驳回' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: '确认驳回' }))

    await waitFor(() => {
      const rejectCall = fetchMock.mock.calls.find(([input]) =>
        String(input).endsWith('/invoices/8001/reject'),
      )
      expect(rejectCall).toBeDefined()
      expect(JSON.parse(String(rejectCall?.[1]?.body))).toEqual({ reason: '抬头与税号不一致' })
    })
    expect(await screen.findByText(/已被驳回/)).toBeInTheDocument()
  })

  it('飞书未配置的申请展示待推送，并允许管理员排队重试但不宣称已收到', async () => {
    const user = userEvent.setup()
    const fetchMock = installFetch({
      'GET /api/v1/admin/billing/invoices': () => ok(invoicePage([submittedApplication])),
      'GET /api/v1/admin/billing/invoices/8001': () => ok(submittedApplication),
      'POST /api/v1/admin/billing/invoices/8001/webhook/retry': () =>
        ok({ ...submittedApplication, webhookStatus: 'PENDING', webhookErrorCode: null }),
    })
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    renderConsole(<AdminInvoiceConsole />)

    const table = await screen.findByRole('table', { name: '发票申请列表' })
    await user.click(
      within(within(table).getAllByRole('row')[1]).getByRole('button', { name: '查看申请' }),
    )
    expect(await screen.findByText('飞书 Webhook 尚未配置；重试只会排队，不表示管理员已收到通知。')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试推送飞书' }))
    await waitFor(() => {
      expect(calledUrls(fetchMock)).toContain('/api/v1/admin/billing/invoices/8001/webhook/retry')
    })
    expect(screen.queryByText(/开票信息已推送飞书（非发票发出）/)).not.toBeInTheDocument()
  })

  it('APPROVED 只表示内部审核通过，人工发件确认失败时保持原状态', async () => {
    const user = userEvent.setup()
    installFetch({
      'GET /api/v1/admin/billing/invoices': () => ok(invoicePage([approvedApplication])),
      'GET /api/v1/admin/billing/invoices/8001': () => ok(approvedApplication),
      'POST /api/v1/admin/billing/invoices/8001/deliver': () =>
        apiFailure(
          503,
          'INVOICE_DELIVERY_NOT_CONFIGURED',
          '服务端拒绝本次确认。',
        ),
    })
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    renderConsole(<AdminInvoiceConsole />)

    const table = await screen.findByRole('table', { name: '发票申请列表' })
    await user.click(
      within(within(table).getAllByRole('row')[1]).getByRole('button', { name: '查看申请' }),
    )

    const deliver = await screen.findByRole('button', { name: '确认已人工发件' })
    expect(deliver).toBeEnabled()
    expect(screen.queryByRole('button', { name: '受理' })).not.toBeInTheDocument()
    expect(screen.getByText(/APPROVED 只表示内部审核通过/)).toBeInTheDocument()

    await user.click(deliver)
    expect(screen.getByRole('button', { name: '确认已人工发出' })).toBeDisabled()
    await user.type(screen.getByLabelText('发票号'), 'FP-20260920-01')
    await user.click(screen.getByRole('button', { name: '确认已人工发出' }))

    expect(await screen.findByText('人工发件确认未完成')).toBeInTheDocument()
    expect(screen.getByText('服务端拒绝本次确认。')).toBeInTheDocument()
    expect(screen.getByText(`traceId：${testTraceId}`)).toBeInTheDocument()
    // The refusal is never dressed up as a completed send.
    expect(screen.queryByText(/状态为 DELIVERED/)).not.toBeInTheDocument()
  })

  it('只有实际发件后输入发票号且服务端成功时才记录人工发件，不宣称客户收件', async () => {
    const user = userEvent.setup()
    const fetchMock = installFetch({
      'GET /api/v1/admin/billing/invoices': () => ok(invoicePage([approvedApplication])),
      'GET /api/v1/admin/billing/invoices/8001': () => ok(approvedApplication),
      'POST /api/v1/admin/billing/invoices/8001/deliver': () => ok(deliveredApplication),
    })
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    renderConsole(<AdminInvoiceConsole />)

    const table = await screen.findByRole('table', { name: '发票申请列表' })
    await user.click(
      within(within(table).getAllByRole('row')[1]).getByRole('button', { name: '查看申请' }),
    )
    await user.click(await screen.findByRole('button', { name: '确认已人工发件' }))
    await user.type(screen.getByLabelText('发票号'), 'FP-20260920-01')
    await user.click(screen.getByRole('button', { name: '确认已人工发出' }))

    expect(await screen.findByText(/管理员人工发件确认已记录：状态为 DELIVERED/)).toBeInTheDocument()
    expect(screen.getAllByText(/不代表申请人实际收件/).length).toBeGreaterThan(0)
    const call = fetchMock.mock.calls.find(([input]) => String(input).endsWith('/invoices/8001/deliver'))
    expect(JSON.parse(String(call?.[1]?.body))).toEqual({ invoiceNumber: 'FP-20260920-01' })
  })

  it('DELIVERED 之后不再提供任何状态流转操作', async () => {
    installFetch({
      'GET /api/v1/admin/billing/invoices': () => ok(invoicePage([deliveredApplication])),
      'GET /api/v1/admin/billing/invoices/8001': () => ok(deliveredApplication),
    })
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    renderConsole(<AdminInvoiceConsole />)

    const table = await screen.findByRole('table', { name: '发票申请列表' })
    await userEvent.setup().click(
      within(within(table).getAllByRole('row')[1]).getByRole('button', { name: '查看申请' }),
    )

    await waitFor(() =>
      expect(screen.getAllByText(/当前状态为 DELIVERED/).length).toBeGreaterThan(0),
    )
    for (const label of ['受理', '审核通过', '驳回', '确认已人工发件']) {
      expect(screen.queryByRole('button', { name: label })).not.toBeInTheDocument()
    }
  })
})

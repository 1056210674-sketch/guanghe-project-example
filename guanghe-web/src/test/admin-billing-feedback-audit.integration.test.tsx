import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactElement } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearStoredAuthToken, storeAuthToken } from '../auth/tokenStorage'
import { createQueryClient } from '../app/queryClient'
import { AdminAuditConsole } from '../features/admin-billing/audit'
import { AdminFeedbackConsole } from '../features/admin-billing/feedback'
import { useAuthStore } from '../stores/authStore'
import {
  adminFixture,
  apiErrorResponse,
  jsonResponse,
  testAccessToken,
  testExpiresAt,
  testTraceId,
} from './fixtures/auth'

const FEEDBACK_XSS_CONTENT =
  '<img src=x onerror="window.__feedbackXss=1"><b>加粗</b> 反馈正文'
const AUDIT_XSS_DETAILS = JSON.stringify({
  note: '<img src=x onerror="window.__auditXss=1">',
  html: '<script>window.__auditXss=2</script>',
})

const submittedFeedback = {
  id: '7001',
  category: 'BUG',
  content: '生成任务一直停留在排队中',
  contact: 'user@example.com',
  status: 'SUBMITTED',
  reply: null,
  handledAt: null,
  createdAt: '2026-09-10T02:00:00Z',
  updatedAt: '2026-09-10T02:00:00Z',
}

const closedFeedback = {
  id: '7002',
  category: 'BILLING',
  content: '积分扣费疑问',
  contact: null,
  status: 'CLOSED',
  reply: '已核对账务，扣费金额正确',
  handledAt: '2026-09-11T03:30:00Z',
  createdAt: '2026-09-09T02:00:00Z',
  updatedAt: '2026-09-11T03:30:00Z',
}

const auditEvents = [
  {
    id: '90001',
    actorType: 'ADMIN',
    actorUserId: '1002',
    actorRef: null,
    targetType: 'POINT_ADJUSTMENT',
    targetId: '8001',
    eventType: 'POINT_ADJUSTMENT_APPLIED',
    outcome: 'SUCCESS',
    traceId: testTraceId,
    details: AUDIT_XSS_DETAILS,
    createdAt: '2026-09-12T01:00:00Z',
  },
  {
    id: '90002',
    actorType: 'PAYMENT_PROVIDER',
    actorUserId: null,
    actorRef: 'provider-callback',
    targetType: 'RECHARGE_ORDER',
    targetId: null,
    eventType: 'RECHARGE_CALLBACK_REJECTED',
    outcome: 'FAILURE',
    traceId: testTraceId,
    details: '{"reason":"SIGNATURE_INVALID"}',
    createdAt: '2026-09-12T02:00:00Z',
  },
]

function feedbackPage(items: readonly unknown[]): Response {
  return jsonResponse({
    code: 'OK',
    message: 'success',
    traceId: testTraceId,
    data: { items, page: 1, size: 20, total: items.length, hasNext: false },
  })
}

function auditPage(items: readonly unknown[]): Response {
  return jsonResponse({
    code: 'OK',
    message: 'success',
    traceId: testTraceId,
    data: { items, page: 1, size: 20, total: items.length, hasNext: false },
  })
}

type FetchArgs = Parameters<typeof fetch>
const requestsOf = (fetchMock: ReturnType<typeof vi.fn>, method: string): FetchArgs[] =>
  fetchMock.mock.calls.filter(
    (call) => ((call[1] as RequestInit | undefined)?.method ?? 'GET') === method,
  ) as FetchArgs[]

function renderConsole(element: ReactElement) {
  const queryClient = createQueryClient()
  return {
    queryClient,
    ...render(
      <ConfigProvider>
        <AntdApp>
          <QueryClientProvider client={queryClient}>{element}</QueryClientProvider>
        </AntdApp>
      </ConfigProvider>,
    ),
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  clearStoredAuthToken()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
})

describe('用户反馈控制台', () => {
  it('渲染服务端分页记录、按筛选条件重新请求并展示真实空态', async () => {
    const user = userEvent.setup()
    let items: readonly unknown[] = [submittedFeedback, closedFeedback]
    const fetchMock = vi.fn<typeof fetch>(async () => feedbackPage(items))
    vi.stubGlobal('fetch', fetchMock)

    renderConsole(<AdminFeedbackConsole />)

    const table = await screen.findByRole('table', { name: '用户反馈列表' })
    expect(within(table).getByText('生成任务一直停留在排队中')).toBeInTheDocument()
    expect(within(table).getByText('功能异常')).toBeInTheDocument()
    expect(within(table).getByText('计费问题')).toBeInTheDocument()
    expect(within(table).getByText('待处理')).toBeInTheDocument()
    expect(within(table).getByText('已关闭')).toBeInTheDocument()
    expect(within(table).getByText('user@example.com')).toBeInTheDocument()
    expect(within(table).getByText('未回复')).toBeInTheDocument()
    expect(within(table).getByText('已核对账务，扣费金额正确')).toBeInTheDocument()
    expect(screen.getByText('共 2 条')).toBeInTheDocument()
    expect(String(requestsOf(fetchMock, 'GET')[0][0])).toContain('/api/v1/admin/billing/feedback')

    items = []
    await user.type(screen.getByLabelText('用户 ID'), '1003')
    await user.click(screen.getByRole('button', { name: /查\s*询/ }))

    expect(await screen.findByText('没有符合筛选条件的用户反馈')).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: '用户反馈列表' })).not.toBeInTheDocument()
    expect(String(requestsOf(fetchMock, 'GET')[1][0])).toContain('userId=1003')
  })

  it('接口返回空页时给出真实空态而不是样例数据', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => feedbackPage([])))

    renderConsole(<AdminFeedbackConsole />)

    expect(await screen.findByText('暂无用户反馈记录')).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: '用户反馈列表' })).not.toBeInTheDocument()
    expect(
      screen.getByText(/列表未返回提交用户时显示「—」/),
    ).toBeInTheDocument()
  })

  it('已关闭的反馈不提供任何状态变更入口', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn<typeof fetch>(async () => feedbackPage([closedFeedback]))
    vi.stubGlobal('fetch', fetchMock)

    renderConsole(<AdminFeedbackConsole />)

    const row = (await screen.findByText('积分扣费疑问')).closest('tr')
    expect(row).not.toBeNull()
    await user.click(within(row as HTMLElement).getByRole('button', { name: '查看' }))

    expect(await screen.findByText('已关闭的反馈不可再变更')).toBeInTheDocument()
    expect(screen.queryByLabelText('目标状态')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('反馈回复')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '提交处理' })).not.toBeInTheDocument()
    expect(screen.getByText('已记录回复')).toBeInTheDocument()
    expect(
      screen.getAllByText('已核对账务，扣费金额正确').length,
    ).toBeGreaterThan(0)
    expect(requestsOf(fetchMock, 'POST')).toHaveLength(0)
  })

  it('回复必填：为空时不能提交，填写后提交服务端收到去空格的回复', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn<typeof fetch>(async (_input, init) =>
      init?.method === 'POST'
        ? jsonResponse({
            code: 'OK',
            message: 'success',
            traceId: testTraceId,
            data: { ...submittedFeedback, status: 'ACKNOWLEDGED', reply: '已定位到队列积压', handledAt: '2026-09-12T04:00:00Z' },
          })
        : feedbackPage([submittedFeedback]),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderConsole(<AdminFeedbackConsole />)

    const row = (await screen.findByText('生成任务一直停留在排队中')).closest('tr')
    await user.click(within(row as HTMLElement).getByRole('button', { name: '处理' }))

    const submit = await screen.findByRole('button', { name: '提交处理' })
    expect(submit).toBeDisabled()
    expect(screen.getByText(/提交处理前必须填写非空回复/)).toBeInTheDocument()
    expect(screen.getByText(/回复是提交人唯一能看到的内容/)).toBeInTheDocument()

    await user.type(screen.getByLabelText('反馈回复'), '   ')
    expect(submit).toBeDisabled()

    await user.type(screen.getByLabelText('反馈回复'), '  已定位到队列积压  ')
    expect(submit).toBeEnabled()
    await user.click(submit)

    const [postCall] = requestsOf(fetchMock, 'POST')
    expect(String(postCall[0])).toBe('/api/v1/admin/billing/feedback/7001/handle')
    expect(JSON.parse(String((postCall[1] as RequestInit).body))).toEqual({
      status: 'ACKNOWLEDGED',
      reply: '已定位到队列积压',
    })
    expect(
      await screen.findByText(/已提交处理：反馈 7001 → 已受理/),
    ).toBeInTheDocument()
  })

  it('处理失败时展示服务端消息与 traceId，且不报告成功', async () => {
    const user = userEvent.setup()
    const failureMessage = '回复不能为空'
    const fetchMock = vi.fn<typeof fetch>(async (_input, init) =>
      init?.method === 'POST'
        ? apiErrorResponse(422, failureMessage)
        : feedbackPage([submittedFeedback]),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderConsole(<AdminFeedbackConsole />)

    const row = (await screen.findByText('生成任务一直停留在排队中')).closest('tr')
    await user.click(within(row as HTMLElement).getByRole('button', { name: '处理' }))
    await user.type(screen.getByLabelText('反馈回复'), '已收到，正在核实')
    await user.click(screen.getByRole('button', { name: '提交处理' }))

    const messages = await screen.findAllByText(failureMessage)
    expect(messages.length).toBeGreaterThan(0)
    const traceLines = await screen.findAllByText(`traceId：${testTraceId}`)
    expect(traceLines.length).toBeGreaterThan(0)
    expect(screen.queryByText(/已提交处理/)).not.toBeInTheDocument()
  })

  it('反馈内容按纯文本渲染，不解析 HTML 也不生成元素', async () => {
    const fetchMock = vi.fn<typeof fetch>(async () =>
      feedbackPage([{ ...submittedFeedback, content: FEEDBACK_XSS_CONTENT }]),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderConsole(<AdminFeedbackConsole />)

    const cell = await screen.findByText(FEEDBACK_XSS_CONTENT)
    expect(cell.textContent).toBe(FEEDBACK_XSS_CONTENT)
    expect(cell.querySelector('img')).toBeNull()
    expect(cell.querySelector('b')).toBeNull()
    expect(document.querySelector('img')).toBeNull()
    expect(
      (window as unknown as { __feedbackXss?: number }).__feedbackXss,
    ).toBeUndefined()
  })
})

describe('操作日志控制台', () => {
  it('渲染服务端事件、只读且没有导出入口，空列表不回退到样例', async () => {
    const user = userEvent.setup()
    let items: readonly unknown[] = auditEvents
    const fetchMock = vi.fn<typeof fetch>(async () => auditPage(items))
    vi.stubGlobal('fetch', fetchMock)

    renderConsole(<AdminAuditConsole />)

    const table = await screen.findByRole('table', { name: '操作日志列表' })
    expect(within(table).getByText('90001')).toBeInTheDocument()
    expect(within(table).getByText('1002')).toBeInTheDocument()
    expect(within(table).getByText('provider-callback')).toBeInTheDocument()
    expect(within(table).getByText('POINT_ADJUSTMENT_APPLIED')).toBeInTheDocument()
    expect(within(table).getByText('RECHARGE_CALLBACK_REJECTED')).toBeInTheDocument()
    expect(within(table).getByText('POINT_ADJUSTMENT · 8001')).toBeInTheDocument()
    expect(within(table).getByText('成功')).toBeInTheDocument()
    expect(within(table).getByText('失败')).toBeInTheDocument()
    expect(String(requestsOf(fetchMock, 'GET')[0][0])).toContain(
      '/api/v1/admin/billing/audit-events',
    )
    expect(screen.queryByRole('button', { name: /导出|编辑|删除|修改/ })).not.toBeInTheDocument()

    items = []
    await user.type(screen.getByLabelText('对象编号'), '404')
    await user.click(screen.getByRole('button', { name: /查\s*询/ }))
    expect(await screen.findByText('操作日志中没有匹配的记录')).toBeInTheDocument()
    expect(String(requestsOf(fetchMock, 'GET')[1][0])).toContain('targetId=404')

    const emptyMock = vi.fn<typeof fetch>(async () => auditPage([]))
    vi.stubGlobal('fetch', emptyMock)
    const queryClient = createQueryClient()
    render(
      <ConfigProvider>
        <AntdApp>
          <QueryClientProvider client={queryClient}>
            <AdminAuditConsole />
          </QueryClientProvider>
        </AntdApp>
      </ConfigProvider>,
    )
    expect(await screen.findAllByText('操作日志暂无记录')).not.toHaveLength(0)
  })

  it('details 中的 HTML 以文本呈现，不创建任何元素，并说明脱敏范围', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => auditPage(auditEvents)))

    renderConsole(<AdminAuditConsole />)

    const row = (await screen.findByText('POINT_ADJUSTMENT_APPLIED')).closest('tr')
    expect(row).not.toBeNull()
    await user.click(within(row as HTMLElement).getByRole('button', { name: '查看详情' }))

    const details = await screen.findByLabelText('审计事件 details')
    expect(details.tagName).toBe('PRE')
    expect(details.textContent).toContain('<img src=x')
    expect(details.textContent).toContain('"note"')
    expect(details.querySelector('img')).toBeNull()
    expect(details.querySelector('script')).toBeNull()
    expect(details.textContent).not.toContain('[object Object]')
    expect(document.querySelector('img')).toBeNull()
    expect(document.querySelector('script')).toBeNull()
    expect((window as unknown as { __auditXss?: number }).__auditXss).toBeUndefined()
    expect(
      screen.getByText(/不记录密码、令牌、密钥与完整回调报文/),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/只追加记录，只读，不可编辑或删除/),
    ).toBeInTheDocument()
  })

  it('事件类型与操作者类型下拉项来自响应中真实出现的取值', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => auditPage(auditEvents)))

    renderConsole(<AdminAuditConsole />)

    await screen.findByText('POINT_ADJUSTMENT_APPLIED')
    await user.click(screen.getByLabelText('事件类型'))

    const eventTypeOptions = document.querySelectorAll('.ant-select-item-option-content')
    const labels = [...eventTypeOptions].map((option) => option.textContent)
    expect(labels).toContain('POINT_ADJUSTMENT_APPLIED')
    expect(labels).toContain('RECHARGE_CALLBACK_REJECTED')
    expect(labels).toContain('全部事件类型')
    expect(labels).not.toContain('POINT_ADJUSTMENT_REJECTED')
  })
})

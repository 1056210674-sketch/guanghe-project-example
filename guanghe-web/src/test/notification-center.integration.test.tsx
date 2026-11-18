import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { storeAuthToken } from '../auth/tokenStorage'
import { NotificationCenter } from '../features/notifications/components'
import { useAuthStore } from '../stores/authStore'
import {
  jsonResponse,
  testAccessToken,
  testExpiresAt,
  testTraceId,
  userFixture,
} from './fixtures/auth'

const LIST_PATH = '/api/v1/billing/notifications'
const COUNT_PATH = '/api/v1/billing/notifications/unread-count'
const READ_ALL_PATH = '/api/v1/billing/notifications/read-all'
const CAPABILITY_PATH = '/api/v1/billing/notification-capability'

type CategoryFixture = 'RECHARGE' | 'POINTS' | 'INVOICE' | 'FEEDBACK' | 'SYSTEM'

interface NotificationFixture {
  id: string
  category: CategoryFixture
  title: string
  body: string
  targetType: string | null
  targetId: string | null
  read: boolean
  readAt: string | null
  createdAt: string
}

interface CapabilityFixture {
  inApp: boolean
  inAppStatus: 'NOTIFICATION_IN_APP_AVAILABLE' | 'NOTIFICATION_MODULE_DISABLED'
  mailProvider: string
  mailConfigured: boolean
  mailStatus:
    | 'MAIL_DELIVERY_READY'
    | 'MAIL_DELIVERY_NOT_CONFIGURED'
    | 'MAIL_DELIVERY_NOT_IMPLEMENTED'
  mailMissing: string[]
}

function ok(data: unknown): Response {
  return jsonResponse({ code: 'OK', message: 'success', data, traceId: testTraceId })
}

const readyCapability: CapabilityFixture = {
  inApp: true,
  inAppStatus: 'NOTIFICATION_IN_APP_AVAILABLE',
  mailProvider: 'smtp',
  mailConfigured: true,
  mailStatus: 'MAIL_DELIVERY_READY',
  mailMissing: [],
}

function rechargeNotification(
  overrides: Partial<NotificationFixture> = {},
): NotificationFixture {
  return {
    id: 'notice-recharge',
    category: 'RECHARGE',
    title: '充值到账',
    body: '您的充值已到账。',
    targetType: 'RECHARGE_ORDER',
    targetId: 'order-1',
    read: false,
    readAt: null,
    createdAt: '2026-09-18T01:30:00Z',
    ...overrides,
  }
}

function pointsNotification(
  overrides: Partial<NotificationFixture> = {},
): NotificationFixture {
  return rechargeNotification({
    id: 'notice-points',
    category: 'POINTS',
    title: '积分到账',
    body: '积分已入账。',
    targetType: 'POINT_LEDGER',
    targetId: 'ledger-1',
    ...overrides,
  })
}

/**
 * A stubbed commercial API that behaves like the contract: the read endpoints answer with the
 * server's own row, the sweep answers with the count it swept to, and the unread count is a
 * global number that is deliberately unrelated to what a single page holds.
 */
function setup(
  options: {
    items?: NotificationFixture[]
    unread?: number
    capability?: CapabilityFixture
  } = {},
) {
  let items = (options.items ?? []).map((item) => ({ ...item }))
  let unread = options.unread ?? items.filter((item) => !item.read).length
  const capability = options.capability ?? readyCapability
  const readRequests: string[] = []
  let readAllCalls = 0

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'

    if (url === CAPABILITY_PATH && method === 'GET') {
      return ok(capability)
    }
    if (url === COUNT_PATH && method === 'GET') {
      return ok({ unread })
    }
    if (url === READ_ALL_PATH && method === 'POST') {
      readAllCalls += 1
      unread = 0
      items = items.map((item) => ({
        ...item,
        read: true,
        readAt: item.readAt ?? '2026-09-18T03:00:00Z',
      }))
      return ok({ unread: 0 })
    }
    if (url.startsWith(`${LIST_PATH}/`) && url.endsWith('/read') && method === 'POST') {
      const id = decodeURIComponent(url.slice(LIST_PATH.length + 1, -'/read'.length))
      readRequests.push(id)
      const row = items.find((item) => item.id === id)
      if (!row) {
        throw new Error(`Unknown notification id: ${id}`)
      }
      const readRow: NotificationFixture = {
        ...row,
        read: true,
        readAt: row.readAt ?? '2026-09-18T02:00:00Z',
      }
      items = items.map((item) => (item.id === id ? readRow : item))
      if (!row.read) unread -= 1
      return ok(readRow)
    }
    if (url.startsWith(LIST_PATH) && method === 'GET') {
      const params = new URL(url, 'http://localhost').searchParams
      const category = params.get('category')
      const unreadOnly = params.get('unreadOnly') === 'true'
      const page = Number(params.get('page') ?? 1)
      const size = Number(params.get('size') ?? 20)
      const filtered = items.filter((item) => (!category || item.category === category) && (!unreadOnly || !item.read))
      return ok({ items: filtered.slice((page - 1) * size, page * size), page, size, total: filtered.length, hasNext: page * size < filtered.length })
    }
    throw new Error(`Unexpected Browser API request: ${method} ${url}`)
  })

  vi.stubGlobal('fetch', fetchMock)

  return {
    fetchMock,
    readRequests,
    readAllCalls: () => readAllCalls,
    listCalls: () =>
      fetchMock.mock.calls.filter(([url]) => String(url).startsWith(`${LIST_PATH}?`))
        .length,
    countCalls: () =>
      fetchMock.mock.calls.filter(([url]) => String(url) === COUNT_PATH).length,
  }
}

function renderCenter() {
  return render(
    <ConfigProvider theme={appTheme}>
      <AntdApp>
        <MemoryRouter>
          <QueryClientProvider client={createQueryClient()}>
            <NotificationCenter />
          </QueryClientProvider>
        </MemoryRouter>
      </AntdApp>
    </ConfigProvider>,
  )
}

function detailRegion() {
  return within(screen.getByRole('article', { name: '通知详情' }))
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('用户通知中心真实数据', () => {
  it('空响应渲染暂无通知，未读数来自计数接口而不是列表', async () => {
    const server = setup({ items: [], unread: 7 })
    renderCenter()

    expect(await screen.findByText('暂无通知')).toBeInTheDocument()
    const emptyList = screen.getByLabelText('通知列表')
    expect(emptyList.closest('.notification-columns')).toHaveClass('notification-columns--empty')
    expect(emptyList.querySelector('.notification-list__end')).toBeNull()
    expect(screen.queryByText('请选择一条通知查看详情')).toBeNull()
    expect(screen.getByLabelText('未读通知 7 条')).toBeInTheDocument()
    expect(screen.queryByLabelText('未读通知 0 条')).toBeNull()

    await waitFor(() => expect(server.countCalls()).toBe(1))
    expect(server.listCalls()).toBe(1)
    expect(
      server.fetchMock.mock.calls.some(
        ([url]) => String(url) === `${LIST_PATH}?page=1&size=20`,
      ),
    ).toBe(true)
  })

  it('选择只选中，显式标为已读才提交，并同时失效列表与未读数', async () => {
    const user = userEvent.setup()
    const server = setup({ items: [rechargeNotification()], unread: 1 })
    renderCenter()

    const row = await screen.findByRole('button', { name: '充值到账，未读' })
    expect(screen.getByLabelText('通知列表').querySelector('.notification-list__end')).toBeNull()
    await user.click(row)

    expect(row).toHaveAttribute('aria-pressed', 'true')
    expect(server.readRequests).toEqual([])
    expect(server.listCalls()).toBe(1)
    expect(server.countCalls()).toBe(1)
    expect(detailRegion().getByText('您的充值已到账。')).toBeInTheDocument()
    expect(detailRegion().getByText('未读', { selector: '.notification-detail__status' })).toBeInTheDocument()

    await user.click(detailRegion().getByRole('button', { name: '标为已读' }))

    await waitFor(() => expect(server.readRequests).toEqual(['notice-recharge']))
    await waitFor(() => expect(server.listCalls()).toBe(2))
    await waitFor(() => expect(server.countCalls()).toBe(2))
    expect(
      await screen.findByRole('button', { name: '充值到账，已读' }),
    ).toBeInTheDocument()
    expect(detailRegion().getByText(/^已读于 /)).toBeInTheDocument()
  })

  it('已读通知不重复提交，也不本地扣减未读数', async () => {
    const user = userEvent.setup()
    const server = setup({
      items: [rechargeNotification({ read: true, readAt: '2026-09-18T02:00:00Z' })],
      unread: 1,
    })
    renderCenter()

    await user.click(await screen.findByRole('button', { name: '充值到账，已读' }))
    expect(detailRegion().queryByRole('button', { name: '标为已读' })).toBeNull()
    expect(server.readRequests).toEqual([])
    expect(detailRegion().getByText(/^已读于 /)).toBeInTheDocument()
    expect(screen.getByLabelText('未读通知 1 条')).toBeInTheDocument()
    expect(screen.queryByText(/traceId：/)).toBeNull()
  })

  it('正文按纯文本渲染，已知业务对象只生成站内白名单回链', async () => {
    const user = userEvent.setup()
    const html = '<img src=x onerror=alert(1)>'
    setup({
      items: [rechargeNotification({ body: html })],
      unread: 1,
    })
    renderCenter()

    await user.click(await screen.findByRole('button', { name: '充值到账，未读' }))

    const detail = detailRegion()
    expect(detail.getByText(html)).toBeInTheDocument()
    const body = document.querySelector('.notification-detail__body')
    expect(body?.textContent).toBe(html)
    expect(body?.querySelector('img')).toBeNull()
    expect(document.querySelector('img[src="x"]')).toBeNull()

    expect(detail.queryByText('RECHARGE_ORDER')).toBeNull()
    expect(detail.queryByText('order-1')).toBeNull()
    expect(detail.queryByText('通知信息')).toBeNull()
    expect(detail.getByRole('link', { name: '查看充值记录' })).toHaveAttribute(
      'href',
      '/app/points-billing?tab=orders&targetId=order-1',
    )
  })

  it.each([
    ['TASK_DRAFT', '/app/chat', '返回对话继续确认草稿'],
    ['WORKFLOW_PLAN', '/app/workflow-plans/123', '返回组合计划'],
    ['ARTICLE', '/app/articles?article=123', '返回文章'],
    ['GEO_PLAN', '/app/geo/plans', '返回 GEO 计划列表'],
  ])('充值回链复用 returnContext：%s', async (targetType, href, label) => {
    const user = userEvent.setup()
    setup({
      items: [rechargeNotification({ targetType, targetId: '123' })],
      unread: 1,
    })
    renderCenter()

    await user.click(await screen.findByRole('button', { name: '充值到账，未读' }))

    const detail = detailRegion()
    expect(detail.getByRole('link', { name: label })).toHaveAttribute('href', href)
  })

  it('未知业务对象与超长标识不生成链接，也不会把服务端字段当网址', async () => {
    const user = userEvent.setup()
    setup({
      items: [
        rechargeNotification({
          targetType: 'https://evil.example',
          targetId: `javascript:${'x'.repeat(140)}`,
        }),
      ],
      unread: 1,
    })
    renderCenter()

    await user.click(await screen.findByRole('button', { name: '充值到账，未读' }))

    const detail = detailRegion()
    expect(detail.queryByText('https://evil.example')).toBeNull()
    expect(detail.getByText('相关记录暂无法从这里打开，请到对应功能页查询。')).toBeInTheDocument()
    expect(detail.queryByRole('link')).toBeNull()
    expect(document.querySelector('a[href^="javascript:"]')).toBeNull()
  })

  it('邮件未配置时明确说明未配置，且不暗示已发送邮件', async () => {
    setup({
      items: [],
      unread: 0,
      capability: {
        ...readyCapability,
        mailProvider: '',
        mailConfigured: false,
        mailStatus: 'MAIL_DELIVERY_NOT_CONFIGURED',
        mailMissing: ['MAIL_HOST'],
      },
    })
    renderCenter()

    expect(await screen.findByText('当前通过站内通知接收消息，暂不发送邮件。')).toBeInTheDocument()
    expect(screen.queryByText(/MAIL_DELIVERY_NOT_CONFIGURED|MAIL_HOST/)).toBeNull()
    expect(screen.queryByText(/邮件已发送|已为您发送|发送成功/)).toBeNull()
  })

  it('应用内通知关闭时明确说明，而不是渲染成没有新消息', async () => {
    setup({
      items: [],
      unread: 0,
      capability: {
        ...readyCapability,
        inApp: false,
        inAppStatus: 'NOTIFICATION_MODULE_DISABLED',
      },
    })
    renderCenter()

    expect(await screen.findByText('站内通知暂不可用，请稍后重试')).toBeInTheDocument()
    expect(screen.queryByText(/NOTIFICATION_MODULE_DISABLED/)).toBeNull()
    expect(screen.queryByText('暂无通知')).toBeNull()
  })

  it('分类请求服务端全量筛选，匹配数据不在默认第一页也能找到', async () => {
    const user = userEvent.setup()
    const server = setup({
      items: [...Array.from({ length: 21 }, (_, index) => rechargeNotification({ id: `recharge-${index}`, title: `充值${index}` })), pointsNotification()],
      unread: 22,
    })
    renderCenter()

    expect(await screen.findByRole('button', { name: '充值0，未读' })).toBeInTheDocument()
    expect(screen.getAllByRole('tab')).toHaveLength(6)
    expect(screen.queryByText(/只作用于当前已加载的这一页/)).toBeNull()

    const listCallsBefore = server.listCalls()
    await user.click(screen.getByRole('tab', { name: '积分通知' }))

    const list = within(screen.getByLabelText('通知列表'))
    expect(await list.findByRole('button', { name: '积分到账，未读' })).toBeInTheDocument()
    expect(list.queryByRole('button', { name: '充值0，未读' })).toBeNull()
    expect(screen.queryByText(/只作用于当前已加载的这一页/)).toBeNull()
    expect(server.listCalls()).toBe(listCallsBefore + 1)
    expect(server.fetchMock.mock.calls.some(([url]) => String(url).includes('category=POINTS'))).toBe(true)
  })

  it('未读筛选与分类同时传到服务端，标读后从未读列表移除并更新全局计数', async () => {
    const user = userEvent.setup()
    const server = setup({ items: [pointsNotification(), rechargeNotification({ read: true })] })
    renderCenter()
    await screen.findByRole('button', { name: '积分到账，未读' })
    await user.click(screen.getByRole('tab', { name: '积分通知' }))
    await user.click(screen.getByRole('checkbox', { name: '只看未读' }))
    await user.click(await screen.findByRole('button', { name: '积分到账，未读' }))
    await user.click(detailRegion().getByRole('button', { name: '标为已读' }))
    expect(await screen.findByText('暂无未读通知')).toBeInTheDocument()
    expect(screen.getByLabelText('未读通知 0 条')).toBeInTheDocument()
    expect(server.fetchMock.mock.calls.some(([url]) => String(url).includes('category=POINTS&unreadOnly=true'))).toBe(true)
  })

  it.each([
    ['TASK', '/app/tasks/123', '查看任务'],
    ['GEO_REPORT', '/app/geo/reports/123', '查看 GEO 报告'],
    ['INVOICE_APPLICATION', '/app/points-billing?tab=invoices&targetId=123', '查看发票申请'],
    ['USER_FEEDBACK', '/app/points-billing?tab=feedback&targetId=123', '查看反馈'],
  ])('业务结果 %s 可从通知直接进入对应记录', async (targetType, path, label) => {
    const user = userEvent.setup()
    setup({ items: [rechargeNotification({ category: 'SYSTEM', title: '业务结果已更新', targetType, targetId: '123' })] })
    renderCenter()
    await user.click(await screen.findByRole('button', { name: '业务结果已更新，未读' }))
    expect(detailRegion().getByRole('link', { name: label })).toHaveAttribute('href', path)
  })

  it('全部标为已读采用服务端返回的未读数', async () => {
    const user = userEvent.setup()
    const server = setup({
      items: [rechargeNotification(), pointsNotification()],
      unread: 2,
    })
    renderCenter()

    await screen.findByRole('button', { name: '充值到账，未读' })
    expect(screen.getByLabelText('未读通知 2 条')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /全部标为已读/ }))

    expect(await screen.findByText('已标记全部通知为已读')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('未读通知 0 条')).toBeInTheDocument())
    expect(server.readAllCalls()).toBe(1)
  })

  it('未登录时不发起任何通知请求', async () => {
    useAuthStore.setState({ status: 'anonymous', user: null, error: null })
    const server = setup({ items: [], unread: 0 })
    renderCenter()

    expect(await screen.findByText('登录后可查看通知')).toBeInTheDocument()
    expect(server.fetchMock).not.toHaveBeenCalled()
  })
})

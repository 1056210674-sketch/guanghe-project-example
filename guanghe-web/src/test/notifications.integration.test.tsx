import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearStoredAuthToken, storeAuthToken } from '../auth/tokenStorage'
import { NotificationWorkspace } from '../features/notifications/NotificationWorkspace'
import { notificationCategories, notificationPoints, notificationPrice, type NotificationView } from '../features/notifications/types'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, userFixture } from './fixtures/auth'
import { renderApp } from './renderApp'

const notification: NotificationView = { id: 'notice-1', category: 'TASK', kind: 'GEO', title: '任务部分完成', summary: '任务摘要', body: '任务详情正文', createdAt: '2026-09-07 09:42', unread: true, status: '部分完成', product: { name: '商品甲', id: 'product-1', priceCents: 19900 }, execution: { startedAt: '2026-09-07 09:30', durationLabel: '12 分钟' }, platforms: Array.from({ length: 7 }, (_, index) => ({ id: `platform-${index}`, name: `平台${index}`, status: index === 6 ? '失败' : '成功', outcome: index === 6 ? 'FAILED' : 'SUCCESS' })), settlement: { title: '结算摘要', summary: '按实际结果结算', pointsDelta: 120 }, related: { type: 'TASK', id: 'task-1', label: '查看任务详情' } }
beforeEach(() => useAuthStore.setState({ status: 'anonymous', user: null, error: null }))
describe('普通用户通知中心', () => {
  it('六分类真实空态，全部已读禁用，无请求', async () => {
    const request = vi.spyOn(globalThis, 'fetch'); const user = userEvent.setup(); render(<NotificationWorkspace />)
    expect(screen.getByText('暂无通知')).toBeInTheDocument(); expect(screen.getByRole('button', { name: /全部标为已读/ })).toBeDisabled()
    for (const name of Object.values(notificationCategories)) { await user.click(screen.getByRole('tab', { name })); expect(screen.getByText('暂无通知')).toBeInTheDocument() }
    expect(screen.getAllByRole('tab')).toHaveLength(6); expect(request).not.toHaveBeenCalled()
  })
  it('选择通知展示完整详情且不会自动标为已读', async () => {
    const user = userEvent.setup(); const request = vi.spyOn(globalThis, 'fetch'); render(<NotificationWorkspace notifications={[notification]} />)
    const button = screen.getByRole('button', { name: '任务部分完成，未读' }); await user.click(button)
    expect(button).toHaveAttribute('aria-pressed', 'true')
    const detail = within(screen.getByRole('article', { name: '通知详情' }))
    for (const text of ['任务详情正文', '商品甲', '价格：¥199.00', '结算摘要', '+120 积分']) expect(detail.getByText(text)).toBeInTheDocument()
    expect(detail.getAllByRole('listitem')).toHaveLength(7)
    for (const name of ['标为已读', '查看任务详情', '查看商品详情']) { expect(detail.getByRole('button', { name })).toBeDisabled(); await user.click(detail.getByRole('button', { name })) }
    expect(screen.getByRole('button', { name: '任务部分完成，未读' })).toBeInTheDocument(); expect(request).not.toHaveBeenCalled()
  })
  it.each(Object.entries(notificationCategories))('分类 %s 仅展示匹配通知', async (category, label) => {
    const user = userEvent.setup(); const items = Object.keys(notificationCategories).map(key => ({ ...notification, id: key, category: key as NotificationView['category'], title: `${key}消息` }))
    render(<NotificationWorkspace notifications={items} />); await user.click(screen.getByRole('tab', { name: label }))
    expect(within(screen.getByLabelText('通知列表')).getAllByRole('button')).toHaveLength(1); expect(screen.getByRole('button', { name: `${category}消息，未读` })).toBeInTheDocument()
  })
  it('切换分类清除不匹配详情，无匹配显示空态', async () => {
    const user = userEvent.setup(); render(<NotificationWorkspace notifications={[notification]} />)
    await user.click(screen.getByRole('button', { name: '任务部分完成，未读' })); await user.click(screen.getByRole('tab', { name: '积分通知' }))
    expect(screen.getByText('暂无此类通知')).toBeInTheDocument(); expect(screen.queryByText('任务详情正文')).not.toBeInTheDocument()
  })
  it('通知被删除后清除详情，重新出现不恢复旧选择', async () => {
    const user = userEvent.setup(); const { rerender } = render(<NotificationWorkspace notifications={[notification]} />)
    await user.click(screen.getByRole('button', { name: '任务部分完成，未读' })); rerender(<NotificationWorkspace notifications={[]} />)
    await waitFor(() => expect(screen.queryByRole('article')).not.toBeInTheDocument()); rerender(<NotificationWorkspace notifications={[notification]} />); expect(screen.queryByRole('article')).not.toBeInTheDocument()
  })
  it('Props 刷新使用当前正文和已读状态，不保留旧详情', async () => {
    const user = userEvent.setup(); const { rerender } = render(<NotificationWorkspace notifications={[notification]} />)
    await user.click(screen.getByRole('button', { name: '任务部分完成，未读' })); rerender(<NotificationWorkspace notifications={[{ ...notification, body: '新正文', unread: false }]} />)
    expect(screen.getByText('新正文')).toBeInTheDocument(); expect(screen.queryByText('任务详情正文')).not.toBeInTheDocument(); expect(screen.getByRole('button', { name: '已读' })).toBeDisabled()
  })
  it('键盘可选择通知，内容不解释 HTML 或任意 URL', async () => {
    const user = userEvent.setup(); render(<NotificationWorkspace notifications={[{ ...notification, body: '<img src=x onerror=alert(1)>' }]} />)
    screen.getByRole('button', { name: '任务部分完成，未读' }).focus(); await user.keyboard('{Enter}')
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument(); expect(document.querySelector('img[src="x"],a[href^="javascript:"]')).toBeNull()
  })
  it('精确价格及正负积分格式，非法数值拒绝', () => {
    expect(notificationPrice(9007199254740990)).toBe('¥90071992547409.90'); expect(notificationPoints(-120)).toBe('-120 积分'); expect(notificationPoints(0)).toBe('0 积分')
    for (const value of [undefined, NaN, Infinity, 1.5, Number.MAX_SAFE_INTEGER + 1]) { expect(notificationPrice(value)).toBe('—'); expect(notificationPoints(value)).toBe('—') }
    expect(notificationPrice(-1)).toBe('—')
  })
  it('正式通知路由渲染真实通知中心，不展示预览样例', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(new Response(
      JSON.stringify({ code: 'SERVICE_UNAVAILABLE', message: '服务暂时不可用', data: null, traceId: 'f'.repeat(32) }),
      { status: 503, headers: { 'Content-Type': 'application/json' } },
    )))
    try {
      storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
      useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null }); renderApp(['/app/notifications'])
      // The route is a real page now: it mounts the API-backed centre, which states the server's
      // failure instead of falling back to preview samples or to a 404.
      expect(await screen.findByRole('heading', { name: '通知中心' })).toBeInTheDocument()
      expect(screen.queryByText('404 页面未找到')).not.toBeInTheDocument()
      expect(screen.queryByText('GEO 监测任务部分完成（样例）')).not.toBeInTheDocument()
      expect(await screen.findByText('服务暂时不可用')).toBeInTheDocument()
    } finally {
      clearStoredAuthToken()
      vi.unstubAllGlobals()
    }
  })
  it('正式用户页铃铛接真实未读接口，取不到数据时不编造数字', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockRejectedValue(new Error('offline'))
    vi.stubGlobal('fetch', fetchMock)
    try {
      storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
      useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null }); renderApp(['/app/points-billing'])
      expect(await screen.findByRole('heading', { name: '积分与账单' })).toBeInTheDocument()
      // The bell is a real contract call now: it asks for the unread count and, when the request
      // fails, it renders no number at all rather than a zero the server never sent.
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/notifications/unread-count'),
        expect.anything(),
      )
      expect(await screen.findByRole('button', { name: '打开通知中心' })).toBeInTheDocument()
      await waitFor(() => expect(document.querySelector('.ant-badge-count')).toBeNull())
    } finally {
      clearStoredAuthToken()
      vi.unstubAllGlobals()
    }
  })
  it('预览页铃铛跳转 DEV 通知页，可选择样例详情', async () => {
    const user = userEvent.setup(); renderApp(['/preview/points-billing'])
    await user.click(await screen.findByRole('button', { name: '打开通知中心' })); expect(await screen.findByRole('heading', { name: '通知中心' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'GEO 监测任务部分完成（样例），未读' })); expect(screen.getByRole('article', { name: '通知详情' })).toBeInTheDocument()
  })
})

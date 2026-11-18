import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { PropsWithChildren } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { storeAuthToken } from '../auth/tokenStorage'
import { toUnreadCount } from '../features/notifications/live/notificationAdapters'
import { notificationKeys, NOTIFICATION_REFRESH_MS, useMarkNotificationRead, useUnreadNotificationCount } from '../features/notifications/live/notificationQueries'
import { useAuthStore } from '../stores/authStore'
import { adminFixture, jsonResponse, testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

function harness() {
  const client = createQueryClient()
  return { client, wrapper: ({ children }: PropsWithChildren) => <QueryClientProvider client={client}>{children}</QueryClientProvider> }
}
const ok = (data: unknown) => jsonResponse({ code: 'OK', message: 'success', data, traceId: testTraceId })

beforeEach(() => {
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

describe('通知跨页面同步', () => {
  it('无需打开通知中心，用户壳上的未读数也会定时获取后台任务结果', async () => {
    vi.useFakeTimers()
    let unread = 0
    const fetchMock = vi.fn(async () => ok({ unread }))
    vi.stubGlobal('fetch', fetchMock)
    const { wrapper, client } = harness()
    const hook = renderHook(() => useUnreadNotificationCount(), { wrapper })
    await act(async () => { await vi.advanceTimersByTimeAsync(10) })
    expect(hook.result.current.data?.unread).toBe(0)
    unread = 2
    await act(async () => { await vi.advanceTimersByTimeAsync(NOTIFICATION_REFRESH_MS + 10) })
    expect(hook.result.current.data?.unread).toBe(2)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    hook.unmount()
    await vi.advanceTimersByTimeAsync(NOTIFICATION_REFRESH_MS + 10)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    client.clear()
  })

  it('标读后失效所有分页和筛选缓存，不只失效当前页', async () => {
    const row = { id: '1', category: 'SYSTEM', title: '已完成', body: '查看结果', targetType: 'TASK', targetId: '2', read: true, readAt: '2026-09-22T01:00:00Z', createdAt: '2026-09-22T00:00:00Z' }
    vi.stubGlobal('fetch', vi.fn(async () => ok(row)))
    const { client, wrapper } = harness()
    const keys = notificationKeys.forOwner(userFixture.id)
    const anotherPage = keys.list({ page: 2, size: 20 })
    const unreadFilter = keys.list({ page: 1, unreadOnly: true })
    client.setQueryData(anotherPage, { items: [], page: 2, size: 20, total: 21 })
    client.setQueryData(unreadFilter, { items: [], page: 1, size: 20, total: 1 })
    client.setQueryData(keys.unreadCount(), { unread: 1 })
    const hook = renderHook(() => useMarkNotificationRead({ page: 1, size: 20 }), { wrapper })
    await act(async () => { await hook.result.current.mutateAsync('1') })
    expect(client.getQueryState(anotherPage)?.isInvalidated).toBe(true)
    expect(client.getQueryState(unreadFilter)?.isInvalidated).toBe(true)
    expect(client.getQueryState(keys.unreadCount())?.isInvalidated).toBe(true)
    hook.unmount()
    client.clear()
  })

  it('ADMIN和未登录态不读取USER通知，切换账号不复用前一个账号计数', async () => {
    const fetchMock = vi.fn(async () => ok({ unread: 3 }))
    vi.stubGlobal('fetch', fetchMock)
    useAuthStore.setState({ user: adminFixture })
    const { client, wrapper } = harness()
    const hook = renderHook(() => useUnreadNotificationCount(), { wrapper })
    expect(fetchMock).not.toHaveBeenCalled()
    act(() => useAuthStore.setState({ user: userFixture }))
    await waitFor(() => expect(hook.result.current.data?.unread).toBe(3))
    act(() => useAuthStore.setState({ status: 'anonymous', user: null }))
    expect(hook.result.current.data).toBeUndefined()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    hook.unmount()
    client.clear()
  })

  it('非法未读计数不能伪装为没有通知', () => {
    expect(toUnreadCount({ unread: 0 })).toBe(0)
    for (const unread of [-1, NaN, Infinity, 0.5]) expect(toUnreadCount({ unread })).toBeUndefined()
  })
})

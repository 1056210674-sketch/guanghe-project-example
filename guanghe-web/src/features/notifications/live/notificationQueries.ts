import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getNotificationCapability,
  getUnreadNotificationCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from '../../../api/billingApi'
import type {
  NotificationDto,
  NotificationListQuery,
  NotificationPageDto,
} from '../../../api/billingApi'
import { useAuthStore } from '../../../stores/authStore'

/**
 * The notification centre's server state.
 *
 * Every query is gated on an authenticated owner, so the anonymous DEV preview route issues no
 * request at all. The unread count is its own endpoint: it is global while the list is paged, so
 * counting the loaded page would silently under-report.
 */

export const NOTIFICATION_PAGE_SIZE = 20
export const NOTIFICATION_REFRESH_MS = 15_000

function createNotificationKeys(ownerId?: string | null) {
  const owner = ['billing', ownerId ?? null, 'notifications'] as const
  return {
    owner,
    lists: [...owner, 'list'] as const,
    list: (query: NotificationListQuery) => [...owner, 'list', query] as const,
    unreadCount: () => [...owner, 'unread-count'] as const,
    capability: () => [...owner, 'capability'] as const,
  }
}

export const notificationKeys = {
  all: (ownerId?: string | null) => ['billing', ownerId ?? null, 'notifications'] as const,
  forOwner: createNotificationKeys,
}

function useNotificationOwner() {
  return useAuthStore((state) => state.user?.role === 'USER' ? state.user.id : undefined)
}

export function useNotifications(query: NotificationListQuery = {}) {
  const ownerId = useNotificationOwner()
  return useQuery({
    queryKey: notificationKeys.forOwner(ownerId).list(query),
    queryFn: () => listNotifications(query),
    enabled: Boolean(ownerId),
    refetchInterval: NOTIFICATION_REFRESH_MS,
    refetchOnWindowFocus: true,
  })
}

export function useUnreadNotificationCount(enabled = true) {
  const ownerId = useNotificationOwner()
  return useQuery({
    queryKey: notificationKeys.forOwner(ownerId).unreadCount(),
    queryFn: getUnreadNotificationCount,
    enabled: Boolean(ownerId) && enabled,
    staleTime: 5_000,
    refetchInterval: NOTIFICATION_REFRESH_MS,
    refetchOnWindowFocus: true,
  })
}

export function useNotificationCapability() {
  const ownerId = useNotificationOwner()
  return useQuery({
    queryKey: notificationKeys.forOwner(ownerId).capability(),
    queryFn: getNotificationCapability,
    enabled: Boolean(ownerId),
  })
}

/**
 * Marks one notification read and reconciles both server-owned views.
 *
 * The row is written into the loaded page from the mutation's own response — the server's read
 * state, never a local guess — and then the list and the global unread count are both invalidated.
 * Re-marking an already-read notification is a no-op on the server and stays harmless here.
 */
export function useMarkNotificationRead(query: NotificationListQuery = {}) {
  const ownerId = useNotificationOwner()
  const queryClient = useQueryClient()
  const keys = notificationKeys.forOwner(ownerId)

  return useMutation({
    mutationFn: (notificationId: string) => markNotificationRead(notificationId),
    onSuccess: async (notification: NotificationDto) => {
      queryClient.setQueryData<NotificationPageDto>(keys.list(query), (page) => {
        if (!page || !page.items.some((item) => item.id === notification.id)) {
          return page
        }
        return {
          ...page,
          items: page.items.map((item) =>
            item.id === notification.id ? notification : item,
          ),
        }
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.lists }),
        queryClient.invalidateQueries({ queryKey: keys.unreadCount() }),
      ])
    },
  })
}

/**
 * Sweeps every unread notification and adopts the count the server returned for the sweep, so the
 * badge reflects the server's number rather than a locally assumed zero.
 */
export function useMarkAllNotificationsRead() {
  const ownerId = useNotificationOwner()
  const queryClient = useQueryClient()
  const keys = notificationKeys.forOwner(ownerId)

  return useMutation({
    mutationFn: () => markAllNotificationsRead(),
    onSuccess: async (count) => {
      queryClient.setQueryData(keys.unreadCount(), count)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.lists }),
        queryClient.invalidateQueries({ queryKey: keys.unreadCount() }),
      ])
    },
  })
}

import { useQuery } from '@tanstack/react-query'
import { getAdminUser, listAdminUsers, type AdminUserListQuery } from '../../api/adminUserApi'

export const adminUserKeys = {
  all: (ownerId?: string) => ['admin-users', ownerId ?? 'anonymous'] as const,
  list: (ownerId: string | undefined, query: AdminUserListQuery) => [...adminUserKeys.all(ownerId), 'list', query] as const,
  detail: (ownerId: string | undefined, userId?: string) => [...adminUserKeys.all(ownerId), 'detail', userId ?? 'none'] as const,
}

export function useAdminUsers(ownerId: string | undefined, query: AdminUserListQuery) {
  return useQuery({ queryKey: adminUserKeys.list(ownerId, query), queryFn: () => listAdminUsers(query), enabled: Boolean(ownerId) })
}

export function useAdminUser(ownerId: string | undefined, userId?: string) {
  return useQuery({ queryKey: adminUserKeys.detail(ownerId, userId), queryFn: () => getAdminUser(userId!), enabled: Boolean(ownerId && userId) })
}

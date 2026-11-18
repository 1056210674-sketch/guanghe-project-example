import { useQuery } from '@tanstack/react-query'
import {
  listOperationalAuditEvents,
  type OperationalAuditEventListQuery,
} from '../../../../api/adminBillingApi'
import { useAuthStore } from '../../../../stores/authStore'

export const auditKeys = {
  all: (ownerId?: string) => ['admin-billing', ownerId ?? null, 'audit-events'] as const,
  list: (ownerId: string | undefined, query: OperationalAuditEventListQuery) =>
    [...auditKeys.all(ownerId), 'list', query] as const,
}

/** Read-only: the audit trail has no write surface to expose. */
export function useOperationalAuditEvents(query: OperationalAuditEventListQuery) {
  const ownerId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: auditKeys.list(ownerId, query),
    queryFn: () => listOperationalAuditEvents(query),
    enabled: Boolean(ownerId),
  })
}

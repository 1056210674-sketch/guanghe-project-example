import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getAdminRechargeOrder,
  listAdminRechargeOrders,
  reconcileAdminRechargeOrder,
  type AdminRechargeOrderListQuery,
} from '../../../../api/adminBillingApi'
import { useAuthStore } from '../../../../stores/authStore'

/**
 * Admin recharge-order queries.
 *
 * The owner id is part of every key so one administrator's cached page can never be served to a
 * different signed-in operator, and every query is disabled until that id exists.
 */
export const orderKeys = {
  all: (ownerId?: string | null) =>
    ['admin-billing', ownerId ?? null, 'recharge-orders'] as const,
  list: (ownerId: string | undefined, query: AdminRechargeOrderListQuery) =>
    [...orderKeys.all(ownerId), query] as const,
  detail: (ownerId: string | undefined, orderId?: string) =>
    [...orderKeys.all(ownerId), 'detail', orderId ?? null] as const,
}

export function useAdminRechargeOrders(query: AdminRechargeOrderListQuery) {
  const ownerId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: orderKeys.list(ownerId, query),
    queryFn: () => listAdminRechargeOrders(query),
    enabled: Boolean(ownerId),
  })
}

export function useAdminRechargeOrder(orderId?: string) {
  const ownerId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: orderKeys.detail(ownerId, orderId),
    queryFn: () => getAdminRechargeOrder(orderId as string),
    enabled: Boolean(ownerId && orderId),
  })
}

/**
 * Asks the provider for the order's state. The resolved value is the server's answer — the caller
 * must render that answer rather than assume the credit moved, and the invalidated list is refetched
 * so the table and the drawer agree with the server.
 */
export function useReconcileAdminRechargeOrder() {
  const ownerId = useAuthStore((state) => state.user?.id)
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (orderId: string) => reconcileAdminRechargeOrder(orderId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: orderKeys.all(ownerId) })
    },
  })
}

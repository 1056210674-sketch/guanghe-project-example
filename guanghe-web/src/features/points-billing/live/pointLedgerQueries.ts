import { useQuery } from '@tanstack/react-query'
import { listPointLedger } from '../../../api/pointLedgerApi'
import type { PointLedgerListQuery } from '../../../api/pointLedgerApi'
import { useAuthStore } from '../../../stores/authStore'

export function usePointLedger(query: PointLedgerListQuery) {
  const ownerId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: ['billing', ownerId ?? null, 'point-ledger', query],
    queryFn: () => listPointLedger(query),
    enabled: Boolean(ownerId),
  })
}

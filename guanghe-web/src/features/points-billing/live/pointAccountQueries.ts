import { useQuery } from '@tanstack/react-query'
import { getPointAccount } from '../../../api/conversationApi'
import { useAuthStore } from '../../../stores/authStore'

export function pointAccountKey(ownerId?: string | null) {
  return ['billing', ownerId ?? null, 'point-account'] as const
}

export function usePointAccountSummary(enabled = true) {
  const ownerId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: pointAccountKey(ownerId),
    queryFn: getPointAccount,
    enabled: enabled && Boolean(ownerId),
  })
}

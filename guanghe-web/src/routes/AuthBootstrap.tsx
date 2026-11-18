import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useEffect } from 'react'
import { installAuthSessionListener, restoreAuthSession } from '../auth'
import { ErrorState, PageLoading } from '../components/GlobalState'
import { useAuthStore } from '../stores/authStore'
import { subscribeToAuthSessionChanges } from '../auth/tokenStorage'

export function AuthBootstrap({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const status = useAuthStore((state) => state.status)
  const error = useAuthStore((state) => state.error)
  const restoreQuery = useQuery({
    queryKey: ['auth', 'session'],
    queryFn: restoreAuthSession,
    staleTime: Number.POSITIVE_INFINITY,
    retry: false,
  })

  useEffect(() => {
    const clearPrivateQueries = () => {
      const filters = { predicate: (query: { queryKey: readonly unknown[] }) => query.queryKey[0] !== 'auth' }
      void queryClient.cancelQueries(filters)
      queryClient.removeQueries(filters)
    }
    const unsubscribeChanges = subscribeToAuthSessionChanges(clearPrivateQueries)
    const uninstall = installAuthSessionListener()
    return () => {
      unsubscribeChanges()
      uninstall()
    }
  }, [queryClient])

  if (status === 'unknown' || status === 'restoring' || restoreQuery.isPending) {
    return <PageLoading label="正在恢复登录状态…" />
  }

  if (status === 'error' && error?.status !== 403) {
    return (
      <ErrorState
        message={error?.message}
        traceId={error?.traceId}
        onRetry={() => void restoreQuery.refetch()}
      />
    )
  }

  return children
}

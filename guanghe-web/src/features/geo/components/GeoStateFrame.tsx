import type { ReactNode } from 'react'
import { EmptyState, ErrorState, PageLoading } from '../../../components/GlobalState'
import type { GeoAsyncViewProps } from '../types'

export interface GeoStateFrameProps extends GeoAsyncViewProps {
  label: string
  empty: boolean
  emptyTitle: string
  emptyDescription: string
  emptyAction?: ReactNode
  children: ReactNode
}
export function GeoStateFrame({
  label,
  loading,
  error,
  onRetry,
  empty,
  emptyTitle,
  emptyDescription,
  emptyAction,
  children,
}: GeoStateFrameProps) {
  if (loading) {
    return (
      <section className="geo-state" aria-label={`${label}加载状态`}>
        <PageLoading label={`正在加载${label}…`} />
      </section>
    )
  }

  if (error) {
    return (
      <section className="geo-state" aria-label={`${label}错误状态`}>
        <ErrorState
          message={error.message}
          traceId={error.traceId}
          onRetry={onRetry}
        />
      </section>
    )
  }

  if (empty) {
    return (
      <section className="geo-state" aria-label={`${label}空状态`}>
        <EmptyState
          title={emptyTitle}
          description={emptyDescription}
          action={emptyAction}
        />
      </section>
    )
  }

  return <>{children}</>
}

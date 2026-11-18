import { useState } from 'react'
import { isApiError } from '../../../../api/apiError'
import type { AdminErrorView } from '../../format'
import { useOperationalAuditEvents } from '../live/auditQueries'
import {
  toAdminAuditEventRow,
  toAdminAuditQuery,
  type AdminAuditFiltersView,
} from '../types'
import { AdminAuditWorkspace } from './AdminAuditWorkspace'

const PAGE_SIZE = 20

function toAdminErrorView(error: unknown): AdminErrorView | null {
  if (!error) return null
  if (isApiError(error)) {
    return { message: error.message, traceId: error.traceId, code: error.code }
  }
  return { message: '请求失败，请稍后重试。' }
}

/**
 * Owns the audit read: one paged query and no mutation, because the audit trail is append-only and
 * this API exposes no way to change it.
 */
export function AdminAuditConsole() {
  const [filters, setFilters] = useState<AdminAuditFiltersView>({})
  const [page, setPage] = useState(1)
  const listQuery = useOperationalAuditEvents(toAdminAuditQuery(filters, page, PAGE_SIZE))

  return (
    <AdminAuditWorkspace
      items={(listQuery.data?.items ?? []).map(toAdminAuditEventRow)}
      total={listQuery.data?.total ?? 0}
      page={listQuery.data?.page ?? page}
      pageSize={listQuery.data?.size ?? PAGE_SIZE}
      loading={listQuery.isLoading}
      error={toAdminErrorView(listQuery.error)}
      filters={filters}
      onFiltersChange={(next) => {
        setFilters(next)
        setPage(1)
      }}
      onPageChange={setPage}
      onRetry={() => void listQuery.refetch()}
    />
  )
}

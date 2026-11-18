import { useState } from 'react'
import { isApiError } from '../../../../api/apiError'
import type { AdminErrorView } from '../../format'
import { useAdminFeedback, useHandleFeedback } from '../live/feedbackQueries'
import {
  toAdminFeedbackQuery,
  toAdminFeedbackRow,
  type AdminFeedbackFiltersView,
  type AdminFeedbackHandleDraft,
  type AdminFeedbackHandleResultView,
} from '../types'
import { AdminFeedbackWorkspace } from './AdminFeedbackWorkspace'

const PAGE_SIZE = 20

function toAdminErrorView(error: unknown): AdminErrorView | null {
  if (!error) return null
  if (isApiError(error)) {
    return { message: error.message, traceId: error.traceId, code: error.code }
  }
  return { message: '请求失败，请稍后重试。' }
}

/**
 * Owns the feedback data: the list query, the status transition, and the server's answer to it.
 * `AdminFeedbackWorkspace` only renders what it is handed.
 */
export function AdminFeedbackConsole() {
  const [filters, setFilters] = useState<AdminFeedbackFiltersView>({})
  const [page, setPage] = useState(1)
  const [actionError, setActionError] = useState<AdminErrorView | null>(null)
  const [actionResult, setActionResult] = useState<AdminFeedbackHandleResultView | null>(null)
  const listQuery = useAdminFeedback(toAdminFeedbackQuery(filters, page, PAGE_SIZE))
  const handleFeedbackMutation = useHandleFeedback()

  const items = (listQuery.data?.items ?? []).map(toAdminFeedbackRow)
  const pendingFeedbackId = handleFeedbackMutation.isPending
    ? handleFeedbackMutation.variables?.feedbackId
    : undefined

  const handle = (draft: AdminFeedbackHandleDraft) => {
    setActionError(null)
    setActionResult(null)
    handleFeedbackMutation.mutate(
      { feedbackId: draft.feedbackId, body: { status: draft.status, reply: draft.reply } },
      {
        onSuccess: (feedback) => {
          setActionResult({ feedbackId: feedback.id, status: feedback.status })
        },
        onError: (error) => {
          setActionError(toAdminErrorView(error))
        },
      },
    )
  }

  return (
    <AdminFeedbackWorkspace
      items={items}
      total={listQuery.data?.total ?? 0}
      page={listQuery.data?.page ?? page}
      pageSize={listQuery.data?.size ?? PAGE_SIZE}
      loading={listQuery.isLoading}
      error={toAdminErrorView(listQuery.error)}
      filters={filters}
      pendingFeedbackId={pendingFeedbackId}
      actionError={actionError}
      actionResult={actionResult}
      onFiltersChange={(next) => {
        setFilters(next)
        setPage(1)
      }}
      onPageChange={setPage}
      onHandle={handle}
      onRetry={() => void listQuery.refetch()}
    />
  )
}

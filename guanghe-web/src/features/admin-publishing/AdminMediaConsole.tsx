import { useMemo, useState } from 'react'
import type {
  AdminMediaCallback,
  AdminMediaResource,
  AdminMediaSubmissionSummary,
} from '../../api/adminMediaApi'
import { isApiError } from '../../api/apiError'
import type { ApiErrorView } from '../../shared/format'
import {
  AdminMediaWorkspace,
  type AdminMediaDetailProps,
  type AdminMediaDetailTarget,
  type AdminMediaTab,
} from './AdminMediaWorkspace'
import {
  useAdminMediaCallback,
  useAdminMediaCallbacks,
  useAdminMediaOverview,
  useAdminMediaResource,
  useAdminMediaResources,
  useAdminMediaSubmission,
  useAdminMediaSubmissions,
  useAdminMediaSyncStates,
} from './adminMediaQueries'
import {
  MEDIA_SUBMISSION_FILTER_ALL,
  mediaSubmissionGroupFilterValue,
  toAdminMediaCallbackQuery,
  toAdminMediaResourceQuery,
  toAdminMediaSubmissionQuery,
  type AdminMediaResourceFiltersView,
  type AdminMediaSubmissionFilterView,
} from './types'

const PAGE_SIZE = 20

function toErrorView(error: unknown): ApiErrorView | null {
  if (!error) return null
  if (isApiError(error)) {
    return { message: error.message, traceId: error.traceId, code: error.code }
  }
  return { message: '请求失败，请稍后重试。' }
}

/** The clicked row, kept so the drawer has something to show while the by-id read is in flight. */
type MediaSelection =
  | { kind: 'resource'; id: string; resource: AdminMediaResource }
  | { kind: 'submission'; id: string; submission: AdminMediaSubmissionSummary }
  | { kind: 'callback'; id: string; callback: AdminMediaCallback }

/**
 * Container for the media console: it owns the tabs, the filters, the paging and the detail
 * selection. The workspace below receives data and callbacks only.
 *
 * Only the visible tab reads. Four tabs, four list endpoints: firing all of them on open would
 * spend four requests to draw one table, and would let the sidebar imply there are callbacks
 * waiting on the strength of data the operator never asked for.
 */
export function AdminMediaConsole() {
  const [activeTab, setActiveTab] = useState<AdminMediaTab>('resources')
  const [resourceFilters, setResourceFilters] = useState<AdminMediaResourceFiltersView>({})
  const [resourcePage, setResourcePage] = useState(1)
  const [submissionFilter, setSubmissionFilter] = useState<AdminMediaSubmissionFilterView>(
    MEDIA_SUBMISSION_FILTER_ALL,
  )
  const [submissionPage, setSubmissionPage] = useState(1)
  const [callbackPage, setCallbackPage] = useState(1)
  const [selection, setSelection] = useState<MediaSelection>()

  const resourceQuery = useMemo(
    () => toAdminMediaResourceQuery(resourceFilters, resourcePage, PAGE_SIZE),
    [resourceFilters, resourcePage],
  )
  const submissionQuery = useMemo(
    () => toAdminMediaSubmissionQuery(submissionFilter, submissionPage, PAGE_SIZE),
    [submissionFilter, submissionPage],
  )
  const callbackQuery = useMemo(
    () => toAdminMediaCallbackQuery(callbackPage, PAGE_SIZE),
    [callbackPage],
  )

  const overview = useAdminMediaOverview()
  const resources = useAdminMediaResources(resourceQuery, activeTab === 'resources')
  const submissions = useAdminMediaSubmissions(submissionQuery, activeTab === 'submissions')
  const callbacks = useAdminMediaCallbacks(callbackQuery, activeTab === 'callbacks')
  const syncStates = useAdminMediaSyncStates(activeTab === 'sync')

  const resourceDetail = useAdminMediaResource(
    selection?.kind === 'resource' ? selection.id : undefined,
  )
  const submissionDetail = useAdminMediaSubmission(
    selection?.kind === 'submission' ? selection.id : undefined,
  )
  const callbackDetail = useAdminMediaCallback(
    selection?.kind === 'callback' ? selection.id : undefined,
  )

  // The generated page schemas intersect `Page`, whose `items` is `unknown[]`, so the rows are
  // named once here where the element type is known; a later bare `.find(cb)` would infer `unknown`.
  const resourceRows: readonly AdminMediaResource[] = resources.data?.items ?? []
  const submissionRows: readonly AdminMediaSubmissionSummary[] = submissions.data?.items ?? []
  const callbackRows: readonly AdminMediaCallback[] = callbacks.data?.items ?? []

  const openDetail = (target: AdminMediaDetailTarget) => {
    if (target.kind === 'resource') {
      const row = resourceRows.find((item) => item.id === target.id)
      if (row) setSelection({ kind: 'resource', id: row.id, resource: row })
      return
    }
    if (target.kind === 'submission') {
      const row = submissionRows.find((item) => item.id === target.id)
      if (row) setSelection({ kind: 'submission', id: row.id, submission: row })
      return
    }
    const row = callbackRows.find((item) => item.id === target.id)
    if (row) setSelection({ kind: 'callback', id: row.id, callback: row })
  }

  const detail: AdminMediaDetailProps | null = selection
    ? {
        target: { kind: selection.kind, id: selection.id },
        // The list row is the placeholder; the by-id read replaces it once it lands.
        resource:
          selection.kind === 'resource'
            ? (resourceDetail.data ?? selection.resource)
            : undefined,
        submission: selection.kind === 'submission' ? selection.submission : undefined,
        submissionDetail:
          selection.kind === 'submission' ? submissionDetail.data : undefined,
        callback:
          selection.kind === 'callback' ? (callbackDetail.data ?? selection.callback) : undefined,
        loading:
          (selection.kind === 'resource' && resourceDetail.isLoading) ||
          (selection.kind === 'submission' && submissionDetail.isLoading) ||
          (selection.kind === 'callback' && callbackDetail.isLoading),
        error: toErrorView(
          selection.kind === 'resource'
            ? resourceDetail.error
            : selection.kind === 'submission'
              ? submissionDetail.error
              : callbackDetail.error,
        ),
        onRetry: () => {
          if (selection.kind === 'resource') void resourceDetail.refetch()
          else if (selection.kind === 'submission') void submissionDetail.refetch()
          else void callbackDetail.refetch()
        },
      }
    : null

  return (
    <AdminMediaWorkspace
      activeTab={activeTab}
      onTabChange={setActiveTab}
      overview={overview.data}
      overviewError={toErrorView(overview.error)}
      onRetryOverview={() => void overview.refetch()}
      refreshing={
        overview.isFetching ||
        (activeTab === 'resources' && resources.isFetching) ||
        (activeTab === 'submissions' && submissions.isFetching) ||
        (activeTab === 'callbacks' && callbacks.isFetching) ||
        (activeTab === 'sync' && syncStates.isFetching) ||
        (selection?.kind === 'resource' && resourceDetail.isFetching) ||
        (selection?.kind === 'submission' && submissionDetail.isFetching) ||
        (selection?.kind === 'callback' && callbackDetail.isFetching)
      }
      onRefresh={() => {
        void overview.refetch()
        if (activeTab === 'resources') void resources.refetch()
        if (activeTab === 'submissions') void submissions.refetch()
        if (activeTab === 'callbacks') void callbacks.refetch()
        if (activeTab === 'sync') void syncStates.refetch()
        if (selection?.kind === 'resource') void resourceDetail.refetch()
        if (selection?.kind === 'submission') void submissionDetail.refetch()
        if (selection?.kind === 'callback') void callbackDetail.refetch()
      }}
      resources={{
        items: resourceRows,
        filters: resourceFilters,
        page: resources.data?.page ?? resourcePage,
        pageSize: resources.data?.size ?? PAGE_SIZE,
        total: resources.data?.total ?? 0,
        loading: resources.isLoading,
        error: toErrorView(resources.error),
        onFiltersChange: (next) => {
          setResourceFilters(next)
          setResourcePage(1)
        },
        onPageChange: setResourcePage,
        onRetry: () => void resources.refetch(),
      }}
      submissions={{
        items: submissionRows,
        filter: submissionFilter,
        page: submissions.data?.page ?? submissionPage,
        pageSize: submissions.data?.size ?? PAGE_SIZE,
        total: submissions.data?.total ?? 0,
        loading: submissions.isLoading,
        error: toErrorView(submissions.error),
        onFilterChange: (next) => {
          setSubmissionFilter(next)
          setSubmissionPage(1)
        },
        onPageChange: setSubmissionPage,
        onRetry: () => void submissions.refetch(),
      }}
      callbacks={{
        items: callbackRows,
        page: callbacks.data?.page ?? callbackPage,
        pageSize: callbacks.data?.size ?? PAGE_SIZE,
        total: callbacks.data?.total ?? 0,
        loading: callbacks.isLoading,
        error: toErrorView(callbacks.error),
        onPageChange: setCallbackPage,
        onRetry: () => void callbacks.refetch(),
      }}
      syncStates={{
        items: syncStates.data ?? [],
        loading: syncStates.isLoading,
        error: toErrorView(syncStates.error),
        onRetry: () => void syncStates.refetch(),
      }}
      detail={detail}
      onOpenDetail={openDetail}
      onCloseDetail={() => setSelection(undefined)}
      onShowExceptionSubmissions={() => {
        setActiveTab('submissions')
        setSubmissionFilter(mediaSubmissionGroupFilterValue('EXCEPTION'))
        setSubmissionPage(1)
      }}
    />
  )
}

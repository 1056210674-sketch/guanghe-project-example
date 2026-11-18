import { useMemo, useState } from 'react'
import type {
  AdminAgentVersionRun,
  AdminAgentVersionSummary,
} from '../../api/adminAgentVersionsApi'
import { isApiError } from '../../api/apiError'
import type { ApiErrorView } from '../../shared/format'
import {
  AdminAgentVersionsWorkspace,
  type AgentVersionDetailProps,
} from './AdminAgentVersionsWorkspace'
import {
  useAdminAgentVersion,
  useAdminAgentVersionEvaluations,
  useAdminAgentVersionRuns,
  useAdminAgentVersions,
} from './adminAgentVersionQueries'

const PAGE_SIZE = 20
const EVALUATION_PAGE_SIZE = 20

function toErrorView(error: unknown): ApiErrorView | null {
  if (!error) return null
  if (isApiError(error)) {
    return { message: error.message, traceId: error.traceId, code: error.code }
  }
  return { message: '请求失败，请稍后重试。' }
}

export function AdminAgentVersionsConsole() {
  const [page, setPage] = useState(1)
  const [runsPage, setRunsPage] = useState(1)
  const [selection, setSelection] = useState<AdminAgentVersionSummary>()

  const listQuery = useMemo(() => ({ page, size: PAGE_SIZE }), [page])
  const runsQuery = useMemo(
    () => ({ page: runsPage, size: PAGE_SIZE }),
    [runsPage],
  )
  const evaluationQuery = useMemo(
    () => ({ page: 1, size: EVALUATION_PAGE_SIZE }),
    [],
  )

  const versions = useAdminAgentVersions(listQuery)
  const detail = useAdminAgentVersion(selection?.bundleVersion)
  const detailReady = Boolean(detail.data)
  const runs = useAdminAgentVersionRuns(
    selection?.bundleVersion,
    runsQuery,
    detailReady,
  )
  const evaluations = useAdminAgentVersionEvaluations(
    selection?.bundleVersion,
    evaluationQuery,
    detailReady,
  )

  const versionRows: readonly AdminAgentVersionSummary[] = versions.data?.versions.items ?? []
  const runRows: readonly AdminAgentVersionRun[] = runs.data?.items ?? []
  const latestSelection = selection
    ? (versionRows.find((item) => item.bundleVersion === selection.bundleVersion) ?? selection)
    : undefined

  const detailProps: AgentVersionDetailProps | null = latestSelection
    ? {
        summary: latestSelection,
        data: detail.data,
        loading: detail.isLoading,
        error: toErrorView(detail.error),
        onRetry: () => void detail.refetch(),
        runs: {
          items: runRows,
          page: runs.data?.page ?? runsPage,
          pageSize: runs.data?.size ?? PAGE_SIZE,
          total: runs.data?.total ?? 0,
          loading: detailReady && runs.isLoading,
          error: toErrorView(runs.error),
          onRetry: () => void runs.refetch(),
          onPageChange: setRunsPage,
        },
        evaluations: {
          data: evaluations.data,
          loading: detailReady && evaluations.isLoading,
          error: toErrorView(evaluations.error),
          onRetry: () => void evaluations.refetch(),
        },
      }
    : null

  return (
    <AdminAgentVersionsWorkspace
      versions={{
        runtime: versions.data?.currentRuntime,
        items: versionRows,
        page: versions.data?.versions.page ?? page,
        pageSize: versions.data?.versions.size ?? PAGE_SIZE,
        total: versions.data?.versions.total ?? 0,
        loading: versions.isLoading,
        error: toErrorView(versions.error),
        onRetry: () => void versions.refetch(),
        onPageChange: setPage,
      }}
      detail={detailProps}
      onRefresh={() => {
        void versions.refetch()
        if (selection) {
          void detail.refetch()
          if (detailReady) {
            void runs.refetch()
            void evaluations.refetch()
          }
        }
      }}
      onOpenVersion={(bundleVersion) => {
        const row = versionRows.find((item) => item.bundleVersion === bundleVersion)
        if (!row) return
        setSelection(row)
        setRunsPage(1)
      }}
      onCloseDetail={() => setSelection(undefined)}
    />
  )
}

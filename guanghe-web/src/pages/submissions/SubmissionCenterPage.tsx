import { useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { isApiError } from '../../api/apiError'
import type { MediaResource, MediaResourceListQuery, MediaSubmissionStatus } from '../../api/mediaApi'
import { ErrorState } from '../../components/GlobalState'
import { toLiveMediaResource, toLiveMediaSubmission, toLiveMediaSubmissionDetail } from '../../features/articles/live/mediaAdapters'
import { useMediaResources, useMediaSubmission, useMediaSubmissions } from '../../features/articles/live/mediaQueries'
import type { ArticleViewError } from '../../features/articles/types'
import { SubmissionCenterWorkspace } from '../../features/submissions/SubmissionCenterWorkspace'
import type { DirectoryFilters } from '../../features/submissions/SubmissionCenterWorkspace'
import { UserShell } from '../../layouts/UserShell'
import { APP_PATHS, appLinks } from '../../routes/paths'
import { useAuthStore } from '../../stores/authStore'
import '../../styles/submission-center.css'

function viewError(error: unknown, fallback: string): ArticleViewError | null {
  if (!error) return null
  return isApiError(error)
    ? { message: fallback, traceId: error.traceId }
    : { message: fallback }
}

function OwnedSubmissionCenter() {
  const navigate = useNavigate()
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  // The public records deep link and the existing internal tab name both
  // resolve to the same read-only view. URL state also survives reload/history.
  const tab = ['records', 'submissions'].includes(params.get('tab') ?? '') ? 'submissions' : 'resources'
  const [filters, setFilters] = useState<DirectoryFilters>({ search: '', status: 'ACTIVE', minYuan: null, maxYuan: null })
  const [resourceQuery, setResourceQuery] = useState<MediaResourceListQuery>({ status: 'ACTIVE', page: 1, size: 20 })
  const [filterError, setFilterError] = useState<string>()
  const [status, setStatus] = useState<MediaSubmissionStatus>()
  const [submissionPage, setSubmissionPage] = useState({ current: 1, pageSize: 20 })
  const [selection, setSelection] = useState<{ id: string; locationKey: string }>()
  const selectedId = tab === 'submissions' && selection?.locationKey === location.key ? selection.id : undefined
  const selectSubmission = (id?: string) => setSelection(id ? { id, locationKey: location.key } : undefined)
  const resources = useMediaResources(resourceQuery)
  const submissions = useMediaSubmissions({ status, page: submissionPage.current, size: submissionPage.pageSize }, tab === 'submissions' && !selectedId, tab === 'submissions')
  const detail = useMediaSubmission(selectedId)
  const search = () => {
    const prices = [filters.minYuan, filters.maxYuan].filter((value): value is number => value !== null)
    if (filters.search.length > 200 || prices.some((value) => !Number.isFinite(value) || value < 0
      || value > 21_474_836.47 || Math.abs(value * 100 - Math.round(value * 100)) > 0.000001)) {
      setFilterError('请填写有效的价格范围，最多保留两位小数，搜索名称不超过 200 字。')
      return
    }
    if (filters.minYuan !== null && filters.maxYuan !== null && filters.minYuan > filters.maxYuan) {
      setFilterError('最低参考价不能高于最高参考价。')
      return
    }
    setFilterError(undefined)
    setResourceQuery({
      search: filters.search.trim() || undefined, status: filters.status,
      minSaleAmountCents: filters.minYuan === null ? undefined : Math.round(filters.minYuan * 100),
      maxSaleAmountCents: filters.maxYuan === null ? undefined : Math.round(filters.maxYuan * 100),
      page: 1, size: resourceQuery.size,
    })
  }
  return <SubmissionCenterWorkspace tab={tab} onTabChange={(next) => {
    setSelection(undefined)
    setParams((current) => {
      const search = new URLSearchParams(current)
      if (next === 'submissions') search.set('tab', 'records')
      else search.delete('tab')
      return search
    })
  }}
    filters={filters} onFiltersChange={setFilters} onSearch={search} filterError={filterError}
    resources={(resources.data?.items ?? []).map((resource: MediaResource) => toLiveMediaResource(resource))}
    resourcesLoading={resources.isLoading} resourcesError={viewError(resources.error, '媒体目录暂不可用，请稍后重试。')}
    resourcePage={{ current: resourceQuery.page ?? 1, pageSize: resourceQuery.size ?? 20, total: resources.data?.total ?? 0 }}
    onResourcePageChange={(page, size) => setResourceQuery((current) => ({ ...current, page: size !== current.size ? 1 : page, size }))}
    onRefreshResources={() => void resources.refetch()}
    submissions={(submissions.data?.items ?? []).map(toLiveMediaSubmission)}
    submissionsLoading={submissions.isLoading} submissionsRefreshing={submissions.isFetching}
    submissionsError={viewError(submissions.error, '投稿记录暂不可用，请稍后重试。')}
    status={status} onStatusChange={(next) => { setStatus(next); setSubmissionPage((current) => ({ ...current, current: 1 })) }}
    submissionPage={{ ...submissionPage, total: submissions.data?.total ?? 0 }}
    onSubmissionPageChange={(current, pageSize) => setSubmissionPage((old) => ({ current: old.pageSize !== pageSize ? 1 : current, pageSize }))}
    onRefreshSubmissions={() => void submissions.refetch()} onOpenSubmission={selectSubmission}
    detailOpen={Boolean(selectedId)} detail={detail.data ? toLiveMediaSubmissionDetail(detail.data) : undefined}
    detailLoading={detail.isLoading} detailRefreshing={detail.isFetching}
    detailError={viewError(detail.error, '投稿详情暂不可用，或该记录不属于当前账号。')}
    onRefreshDetail={() => void detail.refetch()}
    onOpenArticle={(id) => navigate(appLinks.articleDetail(id))} onGoSubmit={() => navigate(`${APP_PATHS.assets}?tab=articles`)} />
}

export function SubmissionCenterPage() {
  const ownerId = useAuthStore((state) => state.user?.id)
  return <UserShell>{ownerId ? <OwnedSubmissionCenter key={ownerId} /> :
    <ErrorState message="请先登录后查看投稿。" />}</UserShell>
}

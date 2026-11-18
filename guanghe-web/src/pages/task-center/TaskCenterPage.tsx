import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { isApiError } from '../../api/apiError'
import { createAssetDownloadUrl } from '../../api/assetApi'
import { useAssetDetails, useAssetDownloadUrls } from '../../features/assets'
import { toLiveMediaSubmission } from '../../features/articles/live/mediaAdapters'
import { useMediaSubmissions } from '../../features/articles/live/mediaQueries'
import { useProjects } from '../../features/agent-platform/queries'
import {
  TaskCenter,
  useTaskCenterDetail,
  useTaskCenterList,
} from '../../features/task-center'
import { openExternalHttps } from '../../features/shared/openExternalHttps'
import type {
  TaskCenterActionType,
  TaskCenterFilterValue,
  TaskCenterPagination,
  TaskCenterViewError,
} from '../../features/task-center'
import { UserShell } from '../../layouts/UserShell'
import { APP_PATHS, appLinks } from '../../routes/paths'
import '../../styles/task-center.css'
import {
  EMPTY_TASK_CENTER_PAGINATION,
  EMPTY_TASK_CENTER_SUMMARY,
} from './pageDefaults'

function taskCenterError(error: unknown): TaskCenterViewError | null {
  if (!error) return null
  if (isApiError(error)) {
    return { message: error.message, traceId: error.traceId }
  }
  return { message: '任务数据加载失败，请稍后重试。' }
}

export function TaskCenterPage() {
  const navigate = useNavigate()
  const { taskId } = useParams<{ taskId: string }>()
  const taskDetailQuery = useTaskCenterDetail(taskId)
  const mediaTaskId = taskDetailQuery.data?.taskType === 'MEDIA' ? taskId : undefined
  const mediaSubmissionsQuery = useMediaSubmissions(
    { taskId: mediaTaskId, page: 1, size: 20 },
    true,
    Boolean(mediaTaskId),
  )
  const [filters, setFilters] = useState<TaskCenterFilterValue>({})
  const [searchValue, setSearchValue] = useState('')
  const [pagination, setPagination] = useState<TaskCenterPagination>(
    EMPTY_TASK_CENTER_PAGINATION,
  )
  const projectsQuery = useProjects()
  const taskListQuery = useTaskCenterList({
    taskType: filters.taskType,
    status: filters.status,
    spaceId: filters.businessSpaceId,
    page: pagination.current,
    size: pagination.pageSize,
  })

  const filterOptions = useMemo(
    () => ({
      businessSpaces: (projectsQuery.data ?? []).map((project) => ({
        id: project.id,
        label: project.name,
      })),
      products: [],
    }),
    [projectsQuery.data],
  )

  const handleAction = (id: string, actionType: TaskCenterActionType) => {
    if (actionType === 'VIEW_DETAILS') {
      navigate(appLinks.taskDetail(id))
    }
    if (actionType === 'VIEW_COMPLETED_REPORT') {
      const task = taskDetailQuery.data
      if (task?.id === id && task.geoReportId) {
        navigate(appLinks.geoReportDetail(task.geoReportId))
      }
    }
  }

  const generatedAssetIds = useMemo(
    () => [
      ...(taskDetailQuery.data?.generatedImageResultGroup?.images.map(
        (image) => image.assetId,
      ) ?? []),
      ...(taskDetailQuery.data?.generatedVideoResult
        ? [taskDetailQuery.data.generatedVideoResult.assetId]
        : []),
    ],
    [taskDetailQuery.data],
  )
  const [resultActionError, setResultActionError] = useState<TaskCenterViewError | null>(null)
  const generatedAssetDetails = useAssetDetails(generatedAssetIds)
  const generatedAssetDownloads = useAssetDownloadUrls(generatedAssetIds)
  const taskDetail = useMemo(() => {
    const task = taskDetailQuery.data
    if (!task) return null
    return {
      ...task,
      generatedImageResultGroup: task.generatedImageResultGroup
        ? {
            ...task.generatedImageResultGroup,
            images: task.generatedImageResultGroup.images.map((image) => ({
              ...image,
              name: generatedAssetDetails[image.assetId]?.name ?? image.name,
              imageUrl: generatedAssetDownloads[image.assetId]?.url,
              expiresAt: generatedAssetDownloads[image.assetId]?.expiresAt,
            })),
          }
        : undefined,
      generatedVideoResult: task.generatedVideoResult
        ? {
            ...task.generatedVideoResult,
            name:
              generatedAssetDetails[task.generatedVideoResult.assetId]?.name ??
              task.generatedVideoResult.name,
            videoUrl:
              generatedAssetDownloads[task.generatedVideoResult.assetId]?.url,
            expiresAt:
              generatedAssetDownloads[task.generatedVideoResult.assetId]?.expiresAt,
          }
        : undefined,
    }
  }, [generatedAssetDetails, generatedAssetDownloads, taskDetailQuery.data])

  const openGeneratedImage = async (assetId: string) => {
    setResultActionError(null)
    try {
      const download = await createAssetDownloadUrl(assetId)
      if (!openExternalHttps(download.url)) {
        setResultActionError({ message: '资产临时链接无法安全打开。' })
      }
    } catch (error) {
      setResultActionError(taskCenterError(error))
    }
  }

  return (
    <UserShell>
      <TaskCenter
        list={{
          tasks: taskListQuery.data?.items ?? [],
          summaryMetrics: EMPTY_TASK_CENTER_SUMMARY,
          loading: taskListQuery.isLoading || projectsQuery.isLoading,
          error: taskCenterError(taskListQuery.error ?? projectsQuery.error),
          filters,
          filterOptions,
          searchValue,
          pagination: {
            current: taskListQuery.data?.page ?? pagination.current,
            pageSize: taskListQuery.data?.size ?? pagination.pageSize,
            total: taskListQuery.data?.total ?? 0,
          },
          onFiltersChange: (nextFilters) => {
            setFilters(nextFilters)
            setPagination((previous) => ({ ...previous, current: 1 }))
          },
          onSearchChange: setSearchValue,
          taskTypes: ['GEO', 'IMAGE', 'VIDEO', 'ARTICLE', 'MEDIA'],
          dateFilterEnabled: false,
          searchEnabled: false,
          onPageChange: (current, pageSize) =>
            setPagination((previous) => ({
              ...previous,
              current,
              pageSize,
            })),
          onAction: handleAction,
          onRetry: () => void Promise.all([
            taskListQuery.refetch(),
            projectsQuery.refetch(),
          ]),
        }}
        detail={{
          open: Boolean(taskId),
          task: taskDetail,
          loading: taskDetailQuery.isLoading,
          error: taskCenterError(taskDetailQuery.error),
          onClose: () => navigate(APP_PATHS.tasks),
          onAction: handleAction,
          onRetry: () => void taskDetailQuery.refetch(),
          onPreviewGeneratedImage: (assetId) =>
            void openGeneratedImage(assetId),
          onDownloadGeneratedImage: (assetId) =>
            void openGeneratedImage(assetId),
          onOpenGeneratedImageAsset: (assetId) =>
            navigate(`${APP_PATHS.assets}?asset=${encodeURIComponent(assetId)}`),
          onOpenGeneratedVideo: (assetId) => void openGeneratedImage(assetId),
          onRefreshGeneratedVideo: async (assetId) => {
            const download = await createAssetDownloadUrl(assetId)
            return { videoUrl: download.url, expiresAt: download.expiresAt }
          },
          resultActionError,
          mediaSubmissions: (mediaSubmissionsQuery.data?.items ?? []).map(toLiveMediaSubmission),
          mediaSubmissionsLoading: mediaSubmissionsQuery.isLoading,
          mediaSubmissionsRefreshing: mediaSubmissionsQuery.isFetching,
          mediaSubmissionsError: taskCenterError(mediaSubmissionsQuery.error),
          onRetryMediaSubmissions: () => void mediaSubmissionsQuery.refetch(),
          onRefreshMediaSubmissions: () => void mediaSubmissionsQuery.refetch(),
          onOpenArticle: (articleId) => navigate(appLinks.articleDetail(articleId)),
        }}
      />
    </UserShell>
  )
}

export function TaskCenterDetailPage() {
  return <TaskCenterPage />
}

import type {
  GeneratedImageResultGroupView,
  GeneratedVideoResultView,
  ParallelTaskStatusTone,
  TaskSettlementView,
  TaskOutcomeActionType,
} from '../chat'

/**
 * Presentation models for the task center.
 *
 * Generated Browser API DTOs are converted into these presentation-only
 * models by the task-center adapter.
 */

export type TaskCenterId = string

export type TaskCenterTaskType =
  | 'GEO'
  | 'IMAGE'
  | 'VIDEO'
  | 'ARTICLE'
  | 'MEDIA'

export type TaskCenterTaskStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'RETRY_WAIT'
  | 'SUCCEEDED'
  | 'PARTIAL'
  | 'FAILED'
  | 'SKIPPED'
  | 'SYSTEM_TERMINATED'

export type TaskCenterStatusTone = ParallelTaskStatusTone

export type TaskCenterActionType =
  | 'VIEW_DETAILS'
  | 'OPEN_RESULT'
  | TaskOutcomeActionType

export interface TaskCenterActionView {
  type: TaskCenterActionType
  enabled: boolean
}

export interface TaskCenterViewError {
  message: string
  traceId?: string
}

export interface TaskCenterPagination {
  current: number
  pageSize: number
  total: number
}

export interface TaskCenterFilterValue {
  taskType?: TaskCenterTaskType
  status?: TaskCenterTaskStatus
  businessSpaceId?: TaskCenterId
  productId?: TaskCenterId
  createdFrom?: string
  createdTo?: string
}

export interface TaskCenterFilterOption {
  id: TaskCenterId
  label: string
}

export interface TaskCenterProductFilterOption extends TaskCenterFilterOption {
  businessSpaceId?: TaskCenterId
}

export interface TaskCenterFilterOptions {
  businessSpaces: readonly TaskCenterFilterOption[]
  products: readonly TaskCenterProductFilterOption[]
}

export interface TaskCenterSummaryMetricView {
  id: TaskCenterId
  label: string
  value?: number
  tone?: TaskCenterStatusTone
}

export interface TaskCenterProgressView {
  completedUnits?: number
  totalUnits?: number
  percent?: number
}

export interface TaskCenterStageView {
  code?: string
  label: string
}

export interface TaskCenterRelatedObjectView {
  label: string
  description?: string
}

export interface TaskCenterPointsView extends TaskSettlementView {
  quotedPoints?: number
  frozenPoints?: number
}

export interface TaskCenterListItemView {
  id: TaskCenterId
  name?: string
  previewAssetId?: string
  taskNumber?: string
  taskType: TaskCenterTaskType
  status: TaskCenterTaskStatus
  relatedObject?: TaskCenterRelatedObjectView
  stage?: TaskCenterStageView
  progress?: TaskCenterProgressView
  points?: TaskCenterPointsView
  durationLabel?: string
  createdAt: string
  actions?: readonly TaskCenterActionView[]
}

// Task-center subtasks keep canonical task status codes. Chat parallel-task
// cards intentionally accept provider-formatted labels, so they cannot be
// substituted without losing this contract boundary.
export interface TaskCenterSubtaskView {
  id: TaskCenterId
  label: string
  status: TaskCenterTaskStatus
  stage?: TaskCenterStageView
  resultSummary?: string
  errorMessage?: string
}

// Task-center result items also preserve canonical status codes; chat outcome
// items carry already-formatted status labels for a different presentation.
export interface TaskCenterResultItemView {
  id: TaskCenterId
  label: string
  value?: string
  status?: TaskCenterTaskStatus
}

export interface TaskCenterResultSummaryView {
  title: string
  summary?: string
  items?: readonly TaskCenterResultItemView[]
}

export interface TaskCenterTimelineEventView {
  id: TaskCenterId
  label: string
  occurredAt?: string
  description?: string
  status?: TaskCenterTaskStatus
}

export interface TaskCenterTaskDetailView extends TaskCenterListItemView {
  businessSpaceName?: string
  productName?: string
  geoReportId?: TaskCenterId
  startedAt?: string
  finishedAt?: string
  failureReason?: string
  subtasks?: readonly TaskCenterSubtaskView[]
  resultSummary?: TaskCenterResultSummaryView
  generatedImageResultGroup?: GeneratedImageResultGroupView
  generatedVideoResult?: GeneratedVideoResultView
  timeline?: readonly TaskCenterTimelineEventView[]
}

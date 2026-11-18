import type {
  TaskCenterFilterOptions,
  TaskCenterListItemView,
  TaskCenterPagination,
  TaskCenterSummaryMetricView,
} from '../../features/task-center'

export const EMPTY_TASK_CENTER_TASKS: readonly TaskCenterListItemView[] = []
export const EMPTY_TASK_CENTER_SUMMARY: readonly TaskCenterSummaryMetricView[] = []

export const EMPTY_TASK_CENTER_FILTER_OPTIONS: TaskCenterFilterOptions = {
  businessSpaces: [],
  products: [],
}

export const EMPTY_TASK_CENTER_PAGINATION: TaskCenterPagination = {
  current: 1,
  pageSize: 20,
  total: 0,
}

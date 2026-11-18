import type {
  TaskCenterActionType,
  TaskCenterStatusTone,
  TaskCenterTaskStatus,
  TaskCenterTaskType,
} from './types'

export const TASK_CENTER_TYPE_LABELS = {
  GEO: 'GEO',
  IMAGE: '图片生成',
  VIDEO: '视频生成',
  ARTICLE: '文章生成',
  MEDIA: '媒体投稿',
} satisfies Record<TaskCenterTaskType, string>

export const TASK_CENTER_STATUS_LABELS = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  RETRY_WAIT: '等待重试',
  SUCCEEDED: '已完成',
  PARTIAL: '部分完成',
  FAILED: '已失败',
  SKIPPED: '已跳过',
  SYSTEM_TERMINATED: '系统终止',
} satisfies Record<TaskCenterTaskStatus, string>

export const TASK_CENTER_STATUS_TONES = {
  QUEUED: 'neutral',
  RUNNING: 'info',
  RETRY_WAIT: 'warning',
  SUCCEEDED: 'success',
  PARTIAL: 'warning',
  FAILED: 'danger',
  SKIPPED: 'neutral',
  SYSTEM_TERMINATED: 'danger',
} satisfies Record<TaskCenterTaskStatus, TaskCenterStatusTone>

export const TASK_CENTER_ACTION_LABELS = {
  VIEW_DETAILS: '查看详情',
  OPEN_RESULT: '打开结果',
  VIEW_COMPLETED_REPORT: '查看已完成报告',
  VIEW_GEO_TREND: '查看该专题趋势',
  VIEW_GEO_COMPETITORS: '查看本次竞品洞察',
  CREATE_ARTICLE_FROM_GEO: '根据监测结果写文章',
  PREPARE_RETRY_FAILED_ITEMS: '仅重试失败项',
  PREPARE_ADJUST_AND_RETRY: '调整内容重新生成',
  PREPARE_RETRY: '稍后重试',
  CONTACT_SUPPORT: '联系支持',
} satisfies Record<TaskCenterActionType, string>

export function formatTaskCenterCount(value?: number) {
  return value !== undefined && Number.isSafeInteger(value) && value >= 0
    ? value.toLocaleString('zh-CN')
    : '—'
}

export function formatTaskCenterPoints(value?: number) {
  return value !== undefined && Number.isSafeInteger(value) && value >= 0
    ? `${value.toLocaleString('zh-CN')} 积分`
    : value === undefined ? '暂不可查' : '—'
}

export function taskCenterSettlementPoints(
  value: number | undefined,
  status: TaskCenterTaskStatus,
) {
  const pending = ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(status)
  return pending && (value === undefined || value === 0)
    ? '待结算'
    : formatTaskCenterPoints(value)
}

export function formatTaskCenterProgress(
  completedUnits?: number,
  totalUnits?: number,
) {
  const completedIsValid =
    completedUnits !== undefined &&
    Number.isSafeInteger(completedUnits) &&
    completedUnits >= 0
  const totalIsValid =
    totalUnits !== undefined &&
    Number.isSafeInteger(totalUnits) &&
    totalUnits >= 0

  return completedIsValid && totalIsValid
    ? `已处理 ${completedUnits.toLocaleString('zh-CN')}/${totalUnits.toLocaleString('zh-CN')}`
    : '—'
}

export function normalizeTaskCenterPercent(value?: number) {
  return value !== undefined && Number.isFinite(value) && value >= 0 && value <= 100
    ? value
    : undefined
}

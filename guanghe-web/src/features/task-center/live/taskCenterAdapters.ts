import type { PaidTask, PaidTaskEvent } from '../../../api/conversationApi'
import {
  generatedImageAssetReferences,
  generatedImageResultSummary,
  toGeneratedImageResultGroup,
  toGeneratedVideoResult,
} from '../../chat/live/chatAdapters'
import type {
  TaskCenterListItemView,
  TaskCenterTaskDetailView,
  TaskCenterTimelineEventView,
} from '../types'
import { taskFailureMessage } from '../../task-common/taskFailurePresentation'

function formatDateTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      }).format(date)
}

function isTerminal(status: PaidTask['status']): boolean {
  return ['SUCCEEDED', 'PARTIAL', 'FAILED', 'SKIPPED', 'SYSTEM_TERMINATED'].includes(
    status,
  )
}

const EVENT_LABELS: Record<PaidTaskEvent['eventType'], string> = {
  'task.queued': '任务已排队',
  'task.progress': '任务执行进度更新',
  'task.completed': '任务执行完成',
  'task.failed': '任务执行失败',
}

const STAGE_LABELS: Readonly<Record<string, string>> = {
  QUEUED: '等待执行',
  PROVIDER_GENERATING: '服务生成中',
  RESULT_READY: '结果已返回',
  ASSET_INGESTION_PENDING: '正在保存到资产中心',
  ASSET_INGESTION_RETRY: '正在重试保存资产',
  GEO_SAMPLING: '正在采集平台回答',
  GEO_RETRYING: '正在补采失败的监测项',
  COMPLETED: '已完成',
  IMAGE_PARTIAL_COMPLETED: '部分图片已保存到资产中心',
  FAILED: '执行失败',
  SYSTEM_TERMINATED: '系统终止',
}

export function toTaskCenterTimelineEvent(
  event: PaidTaskEvent,
): TaskCenterTimelineEventView {
  return {
    id: event.id,
    label:
      (event.status === 'RETRY_WAIT' ? '执行中断，等待重试' : undefined) ??
      (event.status === 'PARTIAL' ? '任务部分完成' : undefined) ??
      (event.stageCode ? STAGE_LABELS[event.stageCode] : undefined) ??
      EVENT_LABELS[event.eventType],
    occurredAt: formatDateTime(event.occurredAt),
    description: taskFailureMessage(event.errorCode, { status: event.status }),
    status: event.status ?? undefined,
  }
}

export function toTaskCenterListItem(
  task: PaidTask,
  context: { businessSpaceName?: string; productName?: string } = {},
): TaskCenterListItemView {
  const taskName = taskTypeName(task.taskType)
  return {
    id: task.id,
    name: taskName,
    previewAssetId: generatedImageAssetReferences(task)[0]?.assetId,
    taskNumber: task.id,
    taskType: task.taskType,
    status: task.status,
    relatedObject: context.productName
      ? { label: context.productName }
      : task.productId
        ? { label: `商品 ${task.productId}` }
        : context.businessSpaceName
          ? { label: context.businessSpaceName }
          : { label: `经营空间 ${task.spaceId}` },
    stage: {
      code: task.stageCode,
      label: task.status === 'RETRY_WAIT' ? '等待重新执行' : task.stageLabel,
    },
    progress: task.progress ?? undefined,
    points: {
      quotedPoints: task.quotedPoints,
      frozenPoints: task.frozenPoints ?? undefined,
      settledPoints: task.settledPoints ?? undefined,
      returnedPoints: task.releasedPoints ?? undefined,
    },
    createdAt: formatDateTime(task.createdAt),
    actions: [{ type: 'VIEW_DETAILS', enabled: true }],
  }
}

export function toTaskCenterTaskDetail(
  task: PaidTask,
  context: { businessSpaceName?: string; productName?: string } = {},
  events: readonly PaidTaskEvent[] = [],
): TaskCenterTaskDetailView {
  const generatedImageResultGroup = toGeneratedImageResultGroup(task)
  const imageSummary = generatedImageResultSummary(task)
  const generatedVideoResult = toGeneratedVideoResult(task)
  const taskName = taskTypeName(task.taskType)
  const articleResult = task.taskType === 'ARTICLE' && 'articleId' in task.result
    ? task.result
    : undefined
  const mediaResult = task.taskType === 'MEDIA' && 'submissionBatchId' in task.result
    ? task.result
    : undefined
  const geoReportId = task.taskType === 'GEO'
    && ['SUCCEEDED', 'PARTIAL'].includes(task.status)
    && 'reportId' in task.result ? task.result.reportId : undefined

  return {
    id: task.id,
    name: taskName,
    taskNumber: task.id,
    taskType: task.taskType,
    status: task.status,
    relatedObject: context.productName
      ? { label: context.productName }
      : undefined,
    businessSpaceName: context.businessSpaceName,
    productName: context.productName,
    geoReportId,
    actions: geoReportId ? [{ type: 'VIEW_COMPLETED_REPORT', enabled: true }] : undefined,
    stage: {
      code: task.stageCode,
      label: task.status === 'RETRY_WAIT' ? '等待重新执行' : task.stageLabel,
    },
    progress: task.progress ?? undefined,
    points: {
      quotedPoints: task.quotedPoints,
      frozenPoints: task.frozenPoints ?? undefined,
      settledPoints: task.settledPoints ?? undefined,
      returnedPoints: task.releasedPoints ?? undefined,
    },
    createdAt: formatDateTime(task.createdAt),
    finishedAt: isTerminal(task.status)
      ? formatDateTime(task.updatedAt)
      : undefined,
    failureReason: taskFailureMessage(task.errorCode, { status: task.status }),
    resultSummary: task.taskType === 'GEO' && 'runId' in task.result
      ? {
          title: '监测结果',
          summary: `计划 ${task.result.expectedUnits} 个采样单元，成功 ${task.result.succeededUnits} 个，失败 ${task.result.failedUnits} 个，缺失 ${task.result.missingUnits} 个。`,
        }
      : articleResult
      ? {
          title:
            articleResult.mode === 'TITLE'
              ? `已生成 ${articleResult.titleCandidates.length} 个标题候选`
              : articleResult.mode === 'OUTLINE'
                ? '文章创作方案已生成'
                : articleResult.title ?? '文章生成结果',
          summary:
            articleResult.mode === 'TITLE'
              ? `标题待人工确认 · V${articleResult.articleVersion}`
              : articleResult.mode === 'OUTLINE'
                ? `创作方案待人工确认 · V${articleResult.articleVersion}`
                : `${articleResult.mode === 'BODY' ? '正文' : '局部改写'}已生成 · V${articleResult.articleVersion}`,
        }
      : mediaResult
        ? {
            title: '媒体投稿结果',
            summary: `投稿 ${mediaResult.total} 家，已发布 ${mediaResult.published} 家，退稿 ${mediaResult.rejected} 家。`,
          }
      : generatedImageResultGroup || generatedVideoResult || imageSummary
      ? { title: '生成结果', summary: imageSummary }
      : ['ASSET_INGESTION_PENDING', 'ASSET_INGESTION_RETRY'].includes(task.stageCode)
        ? {
            title: `${task.taskType === 'VIDEO' ? '视频' : '图片'}正在保存到资产中心`,
            summary: task.stageLabel,
          }
        : undefined,
    generatedImageResultGroup: generatedImageResultGroup
      ? {
          ...generatedImageResultGroup,
          images: generatedImageResultGroup.images.map((image) => ({
            ...image,
            saveToAssetsEnabled: true,
            selectEnabled: false,
          })),
        }
      : undefined,
    generatedVideoResult,
    timeline: events.map(toTaskCenterTimelineEvent),
  }
}

function taskTypeName(taskType: PaidTask['taskType']): string {
  const labels: Record<PaidTask['taskType'], string> = {
    IMAGE: '图片创作',
    VIDEO: '视频创作',
    GEO: 'GEO 监测',
    ARTICLE: '文章创作',
    MEDIA: '媒体投稿',
  }
  return labels[taskType]
}

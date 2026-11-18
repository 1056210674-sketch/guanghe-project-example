import type {
  TaskDiagnosticDto,
  TaskDiagnosticPageDto,
  TaskDiagnosticSummaryDto,
} from '../../../api/adminDiagnosticsApi'
import type {
  AdminTaskDiagnosticDetailView,
  AdminTaskDiagnosticPageView,
  AdminTaskDiagnosticSummaryView,
  AdminTaskStatusTone,
} from '../types'

function statusTone(status: string): AdminTaskStatusTone {
  switch (status) {
    case 'SUCCEEDED':
      return 'success'
    case 'PARTIAL':
    case 'RETRY_WAIT':
      return 'warning'
    case 'FAILED':
    case 'SYSTEM_TERMINATED':
      return 'danger'
    case 'RUNNING':
      return 'info'
    default:
      return 'neutral'
  }
}

function summaryView(
  task: TaskDiagnosticSummaryDto | TaskDiagnosticDto,
): AdminTaskDiagnosticSummaryView {
  return {
    id: task.taskId,
    type: task.taskType,
    status: task.taskStatus,
    statusTone: statusTone(task.taskStatus),
    stage: task.stage,
    createdAt: task.createdAt,
    updatedAt: task.updatedAt,
    finishedAt: task.finishedAt ?? undefined,
    failureCategory: task.decision.failureCategory,
    retryability: task.decision.retryability,
    stableErrorCode: task.decision.stableErrorCode ?? undefined,
    retryable: task.decision.retryable,
    waitingExternalRecovery: task.decision.waitingExternalRecovery,
    traceId: task.traceId ?? undefined,
  }
}

export function toAdminTaskDiagnosticPageView(
  page: TaskDiagnosticPageDto,
): AdminTaskDiagnosticPageView {
  return {
    items: (page.items ?? []).map(summaryView),
    page: page.page,
    size: page.size,
    total: page.total,
    hasNext: page.hasNext,
  }
}

export function toAdminTaskDiagnosticDetailView(
  task: TaskDiagnosticDto,
): AdminTaskDiagnosticDetailView {
  return {
    ...summaryView(task),
    quotedPoints: task.quotedPoints,
    settledPoints: task.settledPoints,
    queuedAt: task.queuedAt ?? undefined,
    startedAt: task.startedAt ?? undefined,
    timeline: task.timeline.map((item, index) => ({ ...item, id: `${index}-${item.occurredAt}` })),
    executions: task.executions.map((item) => ({ ...item })),
    outbox: task.outbox.map((item) => ({ ...item })),
    inbox: task.inbox.map((item) => ({ ...item })),
    billing: task.billing ? { ...task.billing, ledger: task.billing.ledger.map((item) => ({ ...item })) } : undefined,
    assetIngestion: task.assetIngestion.map((item) => ({ ...item })),
    geo: task.geo ? { ...task.geo } : undefined,
    article: task.article ? { ...task.article } : undefined,
    media: task.media ? { ...task.media, submissions: task.media.submissions.map((item) => ({ ...item })) } : undefined,
    dataGaps: [...task.dataGaps],
  }
}

import type {
  RunDiagnosticDto,
  RunDiagnosticPageDto,
  RunDiagnosticSummaryDto,
} from '../../../api/adminDiagnosticsApi'
import type {
  AdminRunDiagnosticDetailView,
  AdminRunDiagnosticPageView,
  AdminRunDiagnosticSummaryView,
  AdminTaskStatusTone,
} from '../types'

function statusTone(status: string): AdminTaskStatusTone {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'STARTING':
    case 'RUNNING':
    case 'STOP_REQUESTED':
      return 'info'
    case 'STOPPED':
      return 'warning'
    default:
      return 'neutral'
  }
}

function defined<T>(value: T | null | undefined): T | undefined {
  return value ?? undefined
}

function summaryView(
  run: RunDiagnosticSummaryDto | RunDiagnosticDto,
): AdminRunDiagnosticSummaryView {
  return {
    id: run.runId,
    conversationId: run.conversationId,
    status: run.status,
    statusTone: statusTone(run.status),
    failureCategory: run.decision.failureCategory,
    retryability: run.decision.retryability,
    stableErrorCode: defined(run.stableErrorCode),
    retryable: run.decision.retryable,
    waitingExternalRecovery: run.decision.waitingExternalRecovery,
    traceId: defined(run.traceId),
    createdAt: run.createdAt,
    updatedAt: run.updatedAt,
    completedAt: defined(run.completedAt),
  }
}

export function toAdminRunDiagnosticPageView(
  page: RunDiagnosticPageDto,
): AdminRunDiagnosticPageView {
  return {
    items: (page.items ?? []).map(summaryView),
    page: page.page,
    size: page.size,
    total: page.total,
    hasNext: page.hasNext,
  }
}

export function toAdminRunDiagnosticDetailView(
  run: RunDiagnosticDto,
): AdminRunDiagnosticDetailView {
  return {
    ...summaryView(run),
    lastPythonSequence: run.lastPythonSequence,
    stopRequestedAt: defined(run.stopRequestedAt),
    startedAt: defined(run.startedAt),
    usage: {
      workflowStepCount: defined(run.usage.workflowStepCount),
      modelCallCount: defined(run.usage.modelCallCount),
      contextLoadCount: defined(run.usage.contextLoadCount),
      toolCallCount: defined(run.usage.toolCallCount),
      providerCallCount: defined(run.usage.providerCallCount),
      estimatedInputTokens: defined(run.usage.estimatedInputTokens),
      providerInputTokens: defined(run.usage.providerInputTokens),
      providerOutputTokens: defined(run.usage.providerOutputTokens),
      providerLatencyMs: defined(run.usage.providerLatencyMs),
      provider: defined(run.usage.provider),
      model: defined(run.usage.model),
    },
    contextBudget: {
      targetInputTokens: defined(run.contextBudget.targetInputTokens),
      reservedOutputTokens: defined(run.contextBudget.reservedOutputTokens),
      reservedToolTokens: defined(run.contextBudget.reservedToolTokens),
      reservedSafetyTokens: defined(run.contextBudget.reservedSafetyTokens),
      availableHistoryTokens: defined(run.contextBudget.availableHistoryTokens),
      estimatedHistoryTokens: defined(run.contextBudget.estimatedHistoryTokens),
      truncated: defined(run.contextBudget.truncated),
      contextSource: defined(run.contextBudget.contextSource),
      cacheHit: defined(run.contextBudget.cacheHit),
    },
    linkedTaskId: defined(run.linkedTaskId),
    timeline: [...run.timeline]
      .sort((left, right) => left.sequence - right.sequence)
      .map((item) => ({
        id: `${item.sequence}-${item.occurredAt}`,
        sequence: item.sequence,
        eventType: item.eventType,
        stableErrorCode: defined(item.stableErrorCode),
        occurredAt: item.occurredAt,
      })),
    dataGaps: [...run.dataGaps],
  }
}

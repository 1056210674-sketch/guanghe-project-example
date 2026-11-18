export type AdminTaskStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'PARTIAL_FAILED'
  | 'FAILED'
  | 'UNKNOWN'

export type AdminServiceStatus = 'HEALTHY' | 'DEGRADED' | 'UNAVAILABLE'

export interface AdminTaskTimelineItem {
  id: string
  title: string
  description: string
  occurredAt: string
  status: 'SUCCESS' | 'RUNNING' | 'FAILED' | 'PENDING'
}

export interface AdminPlatformExecution {
  id: string
  platform: string
  status: 'SUCCEEDED' | 'FAILED' | 'RUNNING'
  durationMs?: number
  retryCount: number
  errorCategory?: string
}

export interface AdminExecutionTrace {
  id: string
  stage: string
  status: 'SUCCEEDED' | 'FAILED' | 'RUNNING'
  occurredAt: string
  durationMs?: number
  traceId?: string
}

export interface AdminTaskSummary {
  id: string
  type: string
  userMasked: string
  productName?: string
  status: AdminTaskStatus
  createdAt: string
  completedAt?: string
  durationMs?: number
  settledPoints?: number
  traceId?: string
  timeline?: readonly AdminTaskTimelineItem[]
  platformExecutions?: readonly AdminPlatformExecution[]
  traces?: readonly AdminExecutionTrace[]
  errorCategory?: string
  errorMessage?: string
  recoverySuggestion?: string
}

export interface AdminServiceSummary {
  id: string
  name: string
  kind: 'PROVIDER' | 'QUEUE' | 'WORKER'
  status: AdminServiceStatus
  latencyMs?: number
  errorRatePercent?: number
  queueDepth?: number
  updatedAt: string
}

export interface AdminTaskActionDraft {
  taskId: string
  action: 'REFRESH_STATUS' | 'CONTROLLED_RETRY'
  reason: string
}

export type AdminTaskStatusTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

export interface AdminTaskDiagnosticSummaryView {
  id: string
  type: 'IMAGE' | 'VIDEO' | 'GEO' | 'ARTICLE' | 'MEDIA'
  status: string
  statusTone: AdminTaskStatusTone
  stage: string
  createdAt: string
  updatedAt: string
  finishedAt?: string
  failureCategory: string
  retryability: string
  stableErrorCode?: string
  retryable: boolean
  waitingExternalRecovery: boolean
  traceId?: string
}

export interface AdminTaskDiagnosticPageView {
  items: readonly AdminTaskDiagnosticSummaryView[]
  page: number
  size: number
  total: number
  hasNext: boolean
}

export interface AdminTaskDiagnosticTimelineView {
  id: string
  eventType: string
  fromStatus?: string | null
  toStatus?: string | null
  source: string
  stableErrorCode?: string | null
  occurredAt: string
}

export interface AdminTaskDiagnosticExecutionView {
  executionNo: number
  status: string
  stage: string
  provider?: string | null
  model?: string | null
  submissionState?: string | null
  latencyMs?: number | null
  stableErrorCode?: string | null
  lastHeartbeatAt?: string | null
  startedAt?: string | null
  finishedAt?: string | null
}

export interface AdminTaskDiagnosticDetailView extends AdminTaskDiagnosticSummaryView {
  quotedPoints: number
  settledPoints: number
  queuedAt?: string
  startedAt?: string
  timeline: readonly AdminTaskDiagnosticTimelineView[]
  executions: readonly AdminTaskDiagnosticExecutionView[]
  outbox: readonly {
    executionAttempt: number
    status: string
    attempts: number
    stableErrorCode?: string | null
    availableAt: string
    publishedAt?: string | null
    createdAt: string
  }[]
  inbox: readonly { disposition: string; createdAt: string }[]
  billing?: {
    reservationStatus: string
    quotedPoints: number
    frozenPoints: number
    settledPoints: number
    releasedPoints: number
    ledger: readonly {
      entryType: string
      availableDelta: number
      frozenDelta: number
      createdAt: string
    }[]
  }
  assetIngestion: readonly {
    status: string
    attempt: number
    maxAttempts: number
    provider?: string | null
    model?: string | null
    stableErrorCode?: string | null
    availableAt: string
    completedAt?: string | null
    updatedAt: string
  }[]
  geo?: {
    runId: string
    status: string
    expectedUnits: number
    succeededUnits: number
    failedUnits: number
    missingUnits: number
    startedAt?: string | null
    finishedAt?: string | null
    updatedAt: string
  }
  article?: { articleId: string; status: string; version: number; updatedAt: string }
  media?: {
    status: string
    quotedPoints: number
    settledPoints: number
    releasedPoints: number
    finishedAt?: string | null
    updatedAt: string
    submissions: readonly {
      status: string
      submissionState: string
      provider?: string | null
      model?: string | null
      stableErrorCode?: string | null
      updatedAt: string
    }[]
  }
  dataGaps: readonly string[]
}

export interface AdminTaskDiagnosticFiltersView {
  taskId?: string
  taskType?: 'IMAGE' | 'VIDEO' | 'GEO' | 'ARTICLE' | 'MEDIA'
  taskStatus?: 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'SKIPPED' | 'SYSTEM_TERMINATED'
  stableErrorCode?: string
  traceId?: string
  from?: string
  to?: string
}

export interface AdminTaskDiagnosticErrorView {
  message: string
  traceId?: string
}

export type AdminRunDiagnosticStatus =
  | 'QUEUED'
  | 'STARTING'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'STOP_REQUESTED'
  | 'STOPPED'

export interface AdminRunDiagnosticSummaryView {
  id: string
  conversationId: string
  status: AdminRunDiagnosticStatus
  statusTone: AdminTaskStatusTone
  failureCategory: string
  retryability: string
  stableErrorCode?: string
  retryable: boolean
  waitingExternalRecovery: boolean
  traceId?: string
  createdAt: string
  updatedAt: string
  completedAt?: string
}

export interface AdminRunDiagnosticPageView {
  items: readonly AdminRunDiagnosticSummaryView[]
  page: number
  size: number
  total: number
  hasNext: boolean
}

export interface AdminRunDiagnosticUsageView {
  workflowStepCount?: number
  modelCallCount?: number
  contextLoadCount?: number
  toolCallCount?: number
  providerCallCount?: number
  estimatedInputTokens?: number
  providerInputTokens?: number
  providerOutputTokens?: number
  providerLatencyMs?: number
  provider?: string
  model?: string
}

export interface AdminRunDiagnosticContextBudgetView {
  targetInputTokens?: number
  reservedOutputTokens?: number
  reservedToolTokens?: number
  reservedSafetyTokens?: number
  availableHistoryTokens?: number
  estimatedHistoryTokens?: number
  truncated?: boolean
  contextSource?: 'REDIS' | 'POSTGRESQL'
  cacheHit?: boolean
}

export interface AdminRunDiagnosticTimelineView {
  id: string
  sequence: number
  eventType: string
  stableErrorCode?: string
  occurredAt: string
}

export interface AdminRunDiagnosticDetailView
  extends AdminRunDiagnosticSummaryView {
  lastPythonSequence: number
  stopRequestedAt?: string
  startedAt?: string
  usage: AdminRunDiagnosticUsageView
  contextBudget: AdminRunDiagnosticContextBudgetView
  linkedTaskId?: string
  timeline: readonly AdminRunDiagnosticTimelineView[]
  dataGaps: readonly ('TRACE_ID_NOT_PERSISTED' | 'USAGE_NOT_REPORTED')[]
}

export interface AdminRunDiagnosticFiltersView {
  runId?: string
  conversationId?: string
  status?: AdminRunDiagnosticStatus
  stableErrorCode?: string
  traceId?: string
  from?: string
  to?: string
}

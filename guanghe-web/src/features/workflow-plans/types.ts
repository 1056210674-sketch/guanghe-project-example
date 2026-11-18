import type { TaskCenterTaskStatus } from '../task-center/types'

export type WorkflowPlanStatusView =
  | 'DRAFT'
  | 'WAITING_CONFIRMATION'
  | 'CONFIRMED'
  | 'QUEUED'
  | 'RUNNING'
  | 'WAITING_INPUT'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'RECONFIRMATION_REQUIRED'
  | 'ABANDONED'

export interface WorkflowPlanErrorView {
  message: string
  traceId?: string
}

export interface WorkflowPlanSummaryView {
  id: string
  goalSummary: string
  status: WorkflowPlanStatusView
  version: number
  stepCount: number
  totalQuotedPoints: number
  maxPointCost: number
  confirmationExpiresAt: string
  createdAt: string
  updatedAt: string
}

export interface WorkflowPlanDraftView {
  draftId: string
  taskType: 'IMAGE' | 'VIDEO' | 'GEO' | 'ARTICLE'
  quotedPoints: number
}

export type WorkflowTaskResultKind = 'task' | 'asset' | 'geo-report' | 'article'

export interface WorkflowTaskResultView {
  raw: string
  kind?: WorkflowTaskResultKind
  id?: string
}

export interface WorkflowPlanStepView {
  id: string
  ordinal: number
  type: 'QUERY' | 'ANALYZE' | 'PREPARE' | 'SYNTHESIZE'
  typeLabel: string
  toolLabel: string
  riskLabel: string
  statusLabel: string
  statusTone: 'neutral' | 'info' | 'success' | 'warning' | 'danger'
  dependsOn: readonly string[]
  successCriteria: readonly string[]
  draft?: WorkflowPlanDraftView
  taskId?: string
  taskStatus?: TaskCenterTaskStatus
  taskResult?: WorkflowTaskResultView
}

export interface WorkflowPlanEventView {
  id: string
  label: string
  transition?: string
  occurredAt: string
  errorCode?: string
}

export interface WorkflowPlanDetailView extends WorkflowPlanSummaryView {
  spaceId: string
  productId?: string
  deadlineAt: string
  budgetRows: readonly { label: string; value: string }[]
  steps: readonly WorkflowPlanStepView[]
  confirmation: {
    consumptionEnabled: boolean
    quotedPoints: number
    maxPointCost: number
    expiresAt: string
    expired: boolean
  }
  events: readonly WorkflowPlanEventView[]
  executionEnabled: boolean
  executionStarted: boolean
  lastStableError?: string
  confirmedAt?: string
  abandonedAt?: string
}

export interface WorkflowPlanStartResultView {
  planId: string
  planVersion: number
  status: WorkflowPlanStatusView
  executionEnabled: boolean
  executionStarted: boolean
  executionNo?: number
}

export interface WorkflowPlanConfirmationPreviewView {
  planId: string
  planVersion: number
  totalQuotedPoints: number
  maxPointCost: number
  expiresAt: string
  executionEnabled: false
}

export interface WorkflowPlanConfirmationResultView {
  planId: string
  planVersion: number
  totalQuotedPoints: number
  authorizedMaxPoints: number
  confirmedAt: string
  executionEnabled: false
  executionStarted: false
}

export interface WorkflowPlanPaginationView {
  current: number
  pageSize: number
  total: number
}

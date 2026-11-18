import { isApiError } from '../../api/apiError'
import type {
  WorkflowConfirmationAuthorization,
  WorkflowPlan,
  WorkflowPlanConfirmResponse,
  WorkflowStartResponse,
  WorkflowPlanSummary,
} from '../../api/workflowPlanApi'
import type {
  WorkflowPlanDetailView,
  WorkflowPlanConfirmationPreviewView,
  WorkflowPlanConfirmationResultView,
  WorkflowPlanErrorView,
  WorkflowPlanStatusView,
  WorkflowPlanStartResultView,
  WorkflowPlanSummaryView,
  WorkflowTaskResultView,
} from './types'
import { taskFailureMessage } from '../task-common/taskFailurePresentation'

const dateTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
})

const STEP_TYPE_LABELS = { QUERY: '查询', ANALYZE: '分析', PREPARE: '准备任务', SYNTHESIZE: '汇总' } as const
const TOOL_LABELS: Record<string, string> = {
  'context.catalog.inspect': '读取经营资料',
  'context.memory.search': '检索会话记忆',
  'context.task_workspace.inspect': '读取任务工作区',
  'analysis.competitor.compare': '竞品对比',
  'analysis.evidence.aggregate': '证据汇总',
  'task_draft.propose': '生成任务草稿',
}
const EVENT_LABELS = {
  PLAN_PROPOSED: '计划已生成',
  PLAN_WAITING_CONFIRMATION: '等待用户确认',
  PLAN_CONFIRMED: '计划已确认',
  PLAN_QUEUED: '计划已进入队列',
  PLAN_STARTED: '计划开始执行',
  STEP_STATE_CHANGED: '步骤状态已更新',
  OBSERVATION_RECORDED: '执行观察已记录',
  PLAN_REPLANNED: '计划已重新规划',
  PLAN_WAITING_INPUT: '计划等待补充输入',
  PLAN_RETRY_QUEUED: '重试已进入队列',
  PLAN_SUCCEEDED: '计划执行成功',
  PLAN_FAILED: '计划执行失败',
  PLAN_ABANDONED: '计划已放弃',
} as const

const STEP_STATUS = {
  PENDING: ['待执行', 'neutral'], READY: ['就绪', 'info'], RUNNING: ['执行中', 'info'],
  WAITING: ['等待输入', 'warning'], SUCCEEDED: ['成功', 'success'], FAILED: ['失败', 'danger'],
  SKIPPED: ['已跳过', 'neutral'],
} as const

export const WORKFLOW_PLAN_STATUS_LABELS: Record<WorkflowPlanStatusView, string> = {
  DRAFT: '草稿',
  WAITING_CONFIRMATION: '等待确认',
  CONFIRMED: '已确认',
  QUEUED: '排队中',
  RUNNING: '执行中',
  WAITING_INPUT: '等待输入',
  SUCCEEDED: '已成功',
  FAILED: '已失败',
  RECONFIRMATION_REQUIRED: '需要重新确认',
  ABANDONED: '已放弃',
}

export function formatWorkflowDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateTime.format(date)
}

const SAFE_TASK_RESULT_REF = /^(task|asset|geo-report|article):([1-9]\d*)$/

export function toWorkflowTaskResult(
  value: string | null | undefined,
): WorkflowTaskResultView | undefined {
  if (!value) return undefined
  const match = SAFE_TASK_RESULT_REF.exec(value)
  if (!match) return { raw: value }
  return {
    raw: value,
    kind: match[1] as WorkflowTaskResultView['kind'],
    id: match[2],
  }
}

export function toWorkflowPlanError(error: unknown): WorkflowPlanErrorView | null {
  if (!error) return null
  if (isApiError(error)) return { message: error.message, traceId: error.traceId }
  return { message: '请求失败，请稍后重试。' }
}

export function toWorkflowPlanSummary(plan: WorkflowPlanSummary): WorkflowPlanSummaryView {
  return {
    id: plan.id,
    goalSummary: plan.goalSummary,
    status: plan.status,
    version: plan.version,
    stepCount: plan.stepCount,
    totalQuotedPoints: plan.totalQuotedPoints,
    maxPointCost: plan.maxPointCost,
    confirmationExpiresAt: formatWorkflowDate(plan.confirmationExpiresAt),
    createdAt: formatWorkflowDate(plan.createdAt),
    updatedAt: formatWorkflowDate(plan.updatedAt),
  }
}

export function toWorkflowPlanDetail(plan: WorkflowPlan): WorkflowPlanDetailView {
  const confirmationExpiry = Date.parse(plan.confirmation.expiresAt)
  const confirmedEvent = [...plan.events]
    .reverse()
    .find((event) => event.eventType === 'PLAN_CONFIRMED')
  return {
    ...toWorkflowPlanSummary(plan),
    spaceId: plan.spaceId,
    productId: plan.productId ?? undefined,
    deadlineAt: formatWorkflowDate(plan.budget.deadlineAt),
    budgetRows: [
      { label: '最大步骤', value: String(plan.budget.maxSteps) },
      { label: '最大重规划', value: String(plan.budget.maxReplans) },
      { label: '最大模型调用', value: String(plan.budget.maxModelCalls) },
      { label: '最大工具调用', value: String(plan.budget.maxToolCalls) },
      { label: '最多外部查询', value: String(plan.budget.maxProviderCalls) },
    ],
    steps: plan.steps.map((step) => ({
      id: step.stepId,
      ordinal: step.ordinal,
      type: step.type,
      typeLabel: STEP_TYPE_LABELS[step.type],
      toolLabel: TOOL_LABELS[step.toolName] ?? step.toolName,
      riskLabel: step.risk === 'PREPARE' ? '需确认' : step.risk === 'ANALYZE' ? '分析' : '只读',
      statusLabel: STEP_STATUS[step.status][0],
      statusTone: STEP_STATUS[step.status][1],
      dependsOn: [...step.dependsOn],
      successCriteria: [...step.successCriteria],
      draft: step.taskDraft ? {
        draftId: step.taskDraft.draftId,
        taskType: step.taskDraft.taskType,
        quotedPoints: step.taskDraft.quotedPoints,
      } : undefined,
      taskId: step.taskId ?? undefined,
      taskStatus: step.taskStatus ?? undefined,
      taskResult: toWorkflowTaskResult(step.taskResultRef),
    })),
    confirmation: {
      consumptionEnabled: plan.confirmation.consumptionEnabled,
      quotedPoints: plan.confirmation.quotedPoints,
      maxPointCost: plan.confirmation.maxPointCost,
      expiresAt: formatWorkflowDate(plan.confirmation.expiresAt),
      expired: !Number.isFinite(confirmationExpiry) || confirmationExpiry <= Date.now(),
    },
    events: plan.events.map((event) => ({
      id: String(event.sequence),
      label: EVENT_LABELS[event.eventType],
      transition: event.fromStatus
        ? `${WORKFLOW_PLAN_STATUS_LABELS[event.fromStatus]} → ${WORKFLOW_PLAN_STATUS_LABELS[event.toStatus]}`
        : WORKFLOW_PLAN_STATUS_LABELS[event.toStatus],
      occurredAt: formatWorkflowDate(event.occurredAt),
      errorCode: taskFailureMessage(event.stableErrorCode, {
        retryable: event.eventType === 'PLAN_RETRY_QUEUED',
      }),
    })),
    executionEnabled: plan.executionEnabled,
    executionStarted: plan.executionStarted,
    lastStableError: taskFailureMessage(plan.lastStableError, {
      retryable: plan.events.at(-1)?.eventType === 'PLAN_RETRY_QUEUED',
    }),
    confirmedAt: confirmedEvent ? formatWorkflowDate(confirmedEvent.occurredAt) : undefined,
    abandonedAt: plan.abandonedAt ? formatWorkflowDate(plan.abandonedAt) : undefined,
  }
}

export function toWorkflowStartResult(result: WorkflowStartResponse): WorkflowPlanStartResultView {
  return {
    planId: result.planId,
    planVersion: result.planVersion,
    status: result.status,
    executionEnabled: result.executionEnabled,
    executionStarted: result.executionStarted,
    executionNo: result.executionNo ?? undefined,
  }
}

export function toWorkflowConfirmationPreview(
  authorization: WorkflowConfirmationAuthorization,
): WorkflowPlanConfirmationPreviewView {
  return {
    planId: authorization.planId,
    planVersion: authorization.planVersion,
    totalQuotedPoints: authorization.totalQuotedPoints,
    maxPointCost: authorization.maxPointCost,
    expiresAt: formatWorkflowDate(authorization.expiresAt),
    executionEnabled: authorization.executionEnabled,
  }
}

export function toWorkflowConfirmationResult(
  result: WorkflowPlanConfirmResponse,
): WorkflowPlanConfirmationResultView {
  return {
    planId: result.planId,
    planVersion: result.planVersion,
    totalQuotedPoints: result.totalQuotedPoints,
    authorizedMaxPoints: result.authorizedMaxPoints,
    confirmedAt: formatWorkflowDate(result.confirmedAt),
    executionEnabled: result.executionEnabled,
    executionStarted: result.executionStarted,
  }
}

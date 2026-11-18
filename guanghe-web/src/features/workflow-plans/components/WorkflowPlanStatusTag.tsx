import { Tag } from 'antd'
import type { WorkflowPlanStatusView } from '../types'
import { WORKFLOW_PLAN_STATUS_LABELS } from '../workflowPlanAdapters'

const STATUS_COLORS: Record<WorkflowPlanStatusView, string> = {
  DRAFT: 'default',
  WAITING_CONFIRMATION: 'processing',
  CONFIRMED: 'success',
  QUEUED: 'processing',
  RUNNING: 'processing',
  WAITING_INPUT: 'warning',
  SUCCEEDED: 'success',
  FAILED: 'error',
  RECONFIRMATION_REQUIRED: 'warning',
  ABANDONED: 'default',
}

export function WorkflowPlanStatusTag({ status }: { status: WorkflowPlanStatusView }) {
  const label = WORKFLOW_PLAN_STATUS_LABELS[status] ?? '状态不可识别'
  return <Tag color={STATUS_COLORS[status] ?? 'default'}>{label}</Tag>
}

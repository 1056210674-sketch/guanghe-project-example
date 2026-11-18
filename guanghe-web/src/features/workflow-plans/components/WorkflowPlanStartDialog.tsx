import { Alert, Button, Descriptions, Modal, Typography } from 'antd'
import { diagnosticTraceDescription } from '../../../shared/tracePresentation'
import type { WorkflowPlanDetailView, WorkflowPlanErrorView } from '../types'

export interface WorkflowPlanStartDialogProps {
  open: boolean
  plan: WorkflowPlanDetailView | null
  starting: boolean
  error: WorkflowPlanErrorView | null
  onClose: () => void
  onStart: () => void
}

export function WorkflowPlanStartDialog(props: WorkflowPlanStartDialogProps) {
  const plan = props.plan
  return (
    <Modal open={props.open} title="开始执行组合计划" footer={null} onCancel={props.onClose} destroyOnHidden>
      {!plan ? null : (
        <div className="workflow-confirmation-dialog-body">
          <Alert
            type="warning"
            showIcon
            title="开始执行将冻结整份计划授权"
            description="系统将按计划步骤创建任务并冻结授权积分；执行前请再次检查步骤和预算。"
          />
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label="计划总报价">{plan.totalQuotedPoints} 积分</Descriptions.Item>
            <Descriptions.Item label="计划最大授权">{plan.maxPointCost} 积分</Descriptions.Item>
            <Descriptions.Item label="计划版本">{plan.version}</Descriptions.Item>
          </Descriptions>
          <section className="workflow-confirmation-summary" aria-label="执行步骤与依赖">
            <Typography.Title level={5}>执行步骤与依赖</Typography.Title>
            <ol>
              {plan.steps.map((step) => (
                <li key={step.id}>
                  {step.typeLabel} · {step.toolLabel}（依赖：{step.dependsOn.length ? step.dependsOn.join('、') : '无'}）
                </li>
              ))}
            </ol>
          </section>
          {props.error ? (
            <Alert
              type="error"
              showIcon
              title={props.error.message}
              description={diagnosticTraceDescription(props.error.traceId)}
            />
          ) : null}
          <div className="workflow-confirmation-dialog-actions">
            <Button disabled={props.starting} onClick={props.onClose}>返回检查</Button>
            <Button type="primary" loading={props.starting} disabled={!plan.executionEnabled || plan.status !== 'CONFIRMED'} onClick={props.onStart}>
              确认开始执行
            </Button>
          </div>
        </div>
      )}
    </Modal>
  )
}

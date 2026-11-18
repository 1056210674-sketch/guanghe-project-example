import { Alert, Button, Descriptions, Modal, Typography } from 'antd'
import { diagnosticTraceDescription } from '../../../shared/tracePresentation'
import type {
  WorkflowPlanDetailView,
  WorkflowPlanErrorView,
  WorkflowPlanConfirmationPreviewView,
} from '../types'

export interface WorkflowPlanConfirmationDialogProps {
  open: boolean
  plan: WorkflowPlanDetailView | null
  preview: WorkflowPlanConfirmationPreviewView | null
  loading: boolean
  confirming: boolean
  error: WorkflowPlanErrorView | null
  confirmEnabled: boolean
  onClose: () => void
  onRetryPreview: () => void
  onConfirm: () => void
}

export function WorkflowPlanConfirmationDialog(
  props: WorkflowPlanConfirmationDialogProps,
) {
  const deliverySteps = props.plan?.steps.filter((step) => step.draft) ?? []

  return (
    <Modal
      title="确认组合计划"
      open={props.open}
      width={680}
      destroyOnHidden
      closable={!props.confirming}
      mask={{ closable: false }}
      onCancel={props.onClose}
      footer={
        <div className="workflow-confirmation-dialog-actions">
          <Button disabled={props.confirming} onClick={props.onClose}>返回检查</Button>
          {props.error && !props.preview ? (
            <Button disabled={props.confirming || props.loading} onClick={props.onRetryPreview}>
              重新获取确认信息
            </Button>
          ) : null}
          <Button
            type="primary"
            loading={props.confirming}
            disabled={!props.confirmEnabled || props.loading}
            onClick={props.onConfirm}
          >
            明确确认计划
          </Button>
        </div>
      }
    >
      <div className="workflow-confirmation-dialog-body">
        <Alert
          type="warning"
          showIcon
          title="确认后仅记录授权，尚不执行、不冻结积分"
          description="本次确认不会创建任务、调用服务或改变积分余额。确认后返回计划详情，执行能力开放时可再明确开始执行。"
        />

        {props.loading ? (
          <Typography.Text type="secondary">正在获取最新确认信息…</Typography.Text>
        ) : props.error ? (
          <Alert
            type="error"
            showIcon
            title={props.error.message}
            description={diagnosticTraceDescription(props.error.traceId)}
          />
        ) : null}

        {props.plan ? (
          <section className="workflow-confirmation-summary" aria-labelledby="workflow-confirmation-summary-title">
            <Typography.Title id="workflow-confirmation-summary-title" level={5}>计划摘要</Typography.Title>
            <Typography.Paragraph>{props.plan.goalSummary}</Typography.Paragraph>
            <ol>
              {props.plan.steps.map((step) => (
                <li key={step.id}>{step.ordinal}. {step.typeLabel} · {step.toolLabel}</li>
              ))}
            </ol>
            <Typography.Text type="secondary">
              交付物：{deliverySteps.length
                ? deliverySteps.map((step) => `${step.draft?.taskType}（${step.draft?.quotedPoints} 积分）`).join('、')
                : '本计划没有付费任务草稿'}
            </Typography.Text>
          </section>
        ) : null}

        {props.preview ? (
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label="计划版本">{props.preview.planVersion}</Descriptions.Item>
            <Descriptions.Item label="计划总报价">{props.preview.totalQuotedPoints} 积分</Descriptions.Item>
            <Descriptions.Item label="预算上限">{props.preview.maxPointCost} 积分</Descriptions.Item>
            <Descriptions.Item label="本次授权上限">
              {props.preview.totalQuotedPoints} 积分（固定等于计划总报价）
            </Descriptions.Item>
            <Descriptions.Item label="确认有效期">{props.preview.expiresAt}</Descriptions.Item>
            <Descriptions.Item label="执行能力">{props.preview.executionEnabled ? '已开放' : '未开放'}</Descriptions.Item>
          </Descriptions>
        ) : null}
      </div>
    </Modal>
  )
}

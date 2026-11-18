import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Space,
  Tag,
  Timeline,
  Typography,
} from 'antd'
import { EmptyState, ErrorState, PageLoading } from '../../../components/GlobalState'
import { diagnosticTraceDescription } from '../../../shared/tracePresentation'
import { TaskStatusTag } from '../../task-center'
import type {
  WorkflowPlanConfirmationResultView,
  WorkflowPlanDetailView,
  WorkflowPlanErrorView,
  WorkflowPlanStartResultView,
  WorkflowTaskResultView,
} from '../types'
import { WorkflowPlanStatusTag } from './WorkflowPlanStatusTag'

export interface WorkflowPlanDetailDrawerProps {
  open: boolean
  plan: WorkflowPlanDetailView | null
  loading: boolean
  error: WorkflowPlanErrorView | null
  abandonError: WorkflowPlanErrorView | null
  confirmationResult: WorkflowPlanConfirmationResultView | null
  startResult: WorkflowPlanStartResultView | null
  abandoning: boolean
  onClose: () => void
  onRetry: () => void
  onRequestConfirmation: (planId: string, version: number) => void
  onRequestStart: (planId: string, version: number) => void
  onAbandon: (planId: string, version: number) => void
  onOpenTask: (taskId: string) => void
  onOpenTaskResult: (result: WorkflowTaskResultView) => void
}

function WorkflowPlanSteps({
  plan,
  onOpenTask,
  onOpenTaskResult,
}: {
  plan: WorkflowPlanDetailView
  onOpenTask: (taskId: string) => void
  onOpenTaskResult: (result: WorkflowTaskResultView) => void
}) {
  if (!plan.steps.length) {
    return <EmptyState title="暂无计划步骤" description="该计划当前没有可展示的步骤。" />
  }

  return (
    <section className="workflow-plan-section" aria-labelledby="workflow-plan-steps">
      <Typography.Title id="workflow-plan-steps" level={4}>计划步骤</Typography.Title>
      <div className="workflow-plan-steps">
        {plan.steps.map((step) => {
          const taskId = step.taskId
          const taskResult = step.taskResult?.kind && step.taskResult.id
            ? step.taskResult
            : undefined
          return (
            <Card key={step.id} size="small" className="workflow-plan-step">
            <div className="workflow-plan-step-heading">
              <Space wrap>
                <Tag color="red">{step.ordinal}</Tag>
                <Typography.Text strong>{step.typeLabel}</Typography.Text>
                <Tag>{step.riskLabel}</Tag>
              </Space>
              <Tag color={step.statusTone === 'danger' ? 'error' : step.statusTone === 'success' ? 'success' : step.statusTone === 'warning' ? 'warning' : step.statusTone === 'info' ? 'processing' : 'default'}>
                {step.statusLabel}
              </Tag>
            </div>
            <Typography.Text>{step.toolLabel}</Typography.Text>
            <Typography.Text type="secondary">
              依赖：{step.dependsOn.length ? step.dependsOn.join('、') : '无'}
            </Typography.Text>
            {step.successCriteria.length ? (
              <ul className="workflow-plan-criteria">
                {step.successCriteria.map((item) => <li key={item}>{item}</li>)}
              </ul>
            ) : null}
            {step.draft ? (
              <Descriptions size="small" column={1} className="workflow-plan-draft">
                <Descriptions.Item label="任务类型">{step.draft.taskType}</Descriptions.Item>
                <Descriptions.Item label="草稿编号">{step.draft.draftId}</Descriptions.Item>
                <Descriptions.Item label="报价积分">{step.draft.quotedPoints}</Descriptions.Item>
              </Descriptions>
            ) : null}
            {step.type === 'PREPARE' ? (
              <div className="workflow-plan-task-projection">
                <Typography.Text strong>关联任务与交付物</Typography.Text>
                {!taskId ? (
                  <>
                    <Typography.Text type="secondary">尚未创建任务</Typography.Text>
                    {plan.status === 'SUCCEEDED' ? (
                      <Typography.Text type="secondary">暂无可用交付物</Typography.Text>
                    ) : null}
                  </>
                ) : (
                  <>
                    <Space wrap>
                      <Typography.Text type="secondary">任务状态</Typography.Text>
                      {step.taskStatus ? (
                        <TaskStatusTag status={step.taskStatus} />
                      ) : (
                        <Typography.Text>—</Typography.Text>
                      )}
                    </Space>
                    <Space wrap>
                      <Button type="link" onClick={() => onOpenTask(taskId)}>
                        查看任务详情
                      </Button>
                      {taskResult ? (
                        <Button type="link" onClick={() => onOpenTaskResult(taskResult)}>
                          打开交付结果
                        </Button>
                      ) : null}
                    </Space>
                    {step.taskResult && !step.taskResult.kind ? (
                      <Alert
                        type="warning"
                        showIcon
                        title="当前交付引用无法安全打开"
                        description="任务状态仍以最新记录为准；当前交付结果暂不支持直接打开，请稍后重试。"
                      />
                    ) : !step.taskResult && plan.status === 'SUCCEEDED' ? (
                      <Typography.Text type="secondary">暂无可用交付物</Typography.Text>
                    ) : null}
                  </>
                )}
              </div>
            ) : null}
            </Card>
          )
        })}
      </div>
    </section>
  )
}

export function WorkflowPlanDetailDrawer(props: WorkflowPlanDetailDrawerProps) {
  const plan = props.plan
  const confirmedByCurrentAction = Boolean(
    plan
    && plan.status === 'WAITING_CONFIRMATION'
    && props.confirmationResult?.planId === plan.id
    && props.confirmationResult.planVersion === plan.version,
  )
  const startedByCurrentAction = Boolean(
    plan
    && props.startResult?.planId === plan.id
    && props.startResult.planVersion === plan.version,
  )
  const displayStatus = startedByCurrentAction && plan?.status === 'CONFIRMED'
    ? props.startResult?.status ?? plan?.status
    : confirmedByCurrentAction ? 'CONFIRMED' : plan?.status
  const isConfirmed = displayStatus === 'CONFIRMED'
  const canAbandon = !isConfirmed
    && (plan?.status === 'DRAFT' || plan?.status === 'WAITING_CONFIRMATION')
  return (
    <Drawer
      className="workflow-plan-drawer"
      title="组合计划详情"
      open={props.open}
      size="large"
      onClose={props.onClose}
      destroyOnHidden
    >
      {props.loading ? <PageLoading label="正在加载计划详情…" /> : props.error ? (
        <ErrorState message={props.error.message} traceId={props.error.traceId} onRetry={props.onRetry} />
      ) : !plan ? (
        <EmptyState title="暂无可查看的计划详情" description="请选择计划后查看完整信息。" />
      ) : (
        <div className="workflow-plan-detail">
          <header className="workflow-plan-detail-heading">
            <div>
              <Typography.Title level={3}>{plan.goalSummary}</Typography.Title>
              <Typography.Text type="secondary">计划编号：{plan.id} · 版本 {plan.version}</Typography.Text>
            </div>
            <WorkflowPlanStatusTag status={displayStatus ?? plan.status} />
          </header>

          {displayStatus === 'QUEUED' ? (
            <Alert type="info" showIcon title="计划已进入执行队列" description="系统正在统一调度，页面将自动更新最新进度。" />
          ) : displayStatus === 'RUNNING' ? (
            <Alert type="info" showIcon title="计划执行中" description="可在下方查看各步骤的最新进度。" />
          ) : displayStatus === 'WAITING_INPUT' ? (
            <Alert type="warning" showIcon title="计划等待补充输入" description="当前为只读状态；请根据后续正式交互入口补充信息。" />
          ) : displayStatus === 'RECONFIRMATION_REQUIRED' ? (
            <Alert type="warning" showIcon title="计划需要重新确认" description="旧确认授权不会被复用；请刷新详情后确认最新信息。" />
          ) : displayStatus === 'SUCCEEDED' ? (
            <Alert type="success" showIcon title="组合任务已完成" description="可在各步骤中查看任务状态与交付结果。" />
          ) : displayStatus === 'FAILED' ? (
            <Alert type="error" showIcon title="计划执行失败" description="请查看失败步骤和关联任务的最新状态。" />
          ) : isConfirmed ? (
            <Alert
              type="success"
              showIcon
              title="计划已确认"
              description={plan.executionEnabled
                ? '执行能力已开放，可在检查整份计划后开始执行。'
                : '当前暂不可开始执行；计划尚未开始，也未冻结积分。'}
            />
          ) : plan.status === 'WAITING_CONFIRMATION' ? (
            <Alert
              type="info"
              showIcon
              title="计划已生成，尚未确认、未执行、未冻结积分"
              description={plan.confirmation.expired
                ? '确认信息已过期，请刷新计划后检查最新状态。'
                : '确认前将再次获取最新报价和有效期；确认只记录授权，不会执行计划或冻结积分。'}
            />
          ) : plan.status === 'ABANDONED' ? (
            <Alert type="warning" showIcon title="该计划已放弃" description="已放弃计划仅供查看，不能确认或再次放弃。" />
          ) : null}

          <Descriptions bordered size="small" column={2}>
            <Descriptions.Item label="步骤数量">{plan.stepCount}</Descriptions.Item>
            <Descriptions.Item label="计划报价">{plan.totalQuotedPoints} 积分</Descriptions.Item>
            <Descriptions.Item label="积分上限">{plan.maxPointCost} 积分</Descriptions.Item>
            <Descriptions.Item label="确认截止">{plan.confirmationExpiresAt}</Descriptions.Item>
            <Descriptions.Item label="经营空间">{plan.spaceId}</Descriptions.Item>
            <Descriptions.Item label="商品">{plan.productId ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="执行期限">{plan.deadlineAt}</Descriptions.Item>
            <Descriptions.Item label="创建时间">{plan.createdAt}</Descriptions.Item>
            <Descriptions.Item label="更新时间">{plan.updatedAt}</Descriptions.Item>
            <Descriptions.Item label="执行能力">{plan.executionEnabled ? '已开放' : '暂未开放'}</Descriptions.Item>
            <Descriptions.Item label="是否已启动">{plan.executionStarted ? '是' : '否'}</Descriptions.Item>
            {props.startResult?.planId === plan.id && props.startResult.executionNo ? (
              <Descriptions.Item label="执行序号">{props.startResult.executionNo}</Descriptions.Item>
            ) : null}
            {plan.abandonedAt ? (
              <Descriptions.Item label="放弃时间">{plan.abandonedAt}</Descriptions.Item>
            ) : null}
          </Descriptions>

          <section className="workflow-plan-section" aria-labelledby="workflow-budget-title">
            <Typography.Title id="workflow-budget-title" level={4}>执行预算</Typography.Title>
            <div className="workflow-plan-budget">
              {plan.budgetRows.map((row) => (
                <Card key={row.label} size="small">
                  <Typography.Text type="secondary">{row.label}</Typography.Text>
                  <Typography.Text strong>{row.value}</Typography.Text>
                </Card>
              ))}
            </div>
          </section>

          <WorkflowPlanSteps
            plan={plan}
            onOpenTask={props.onOpenTask}
            onOpenTaskResult={props.onOpenTaskResult}
          />

          {plan.lastStableError ? (
            <Alert type="error" showIcon title="处理未完成" description={plan.lastStableError} />
          ) : null}

          <section className="workflow-plan-section" aria-labelledby="workflow-confirm-title">
            <Typography.Title id="workflow-confirm-title" level={4}>确认与授权</Typography.Title>
            <Descriptions size="small" column={1}>
              <Descriptions.Item label="报价积分">{plan.confirmation.quotedPoints}</Descriptions.Item>
              <Descriptions.Item label="积分上限">{plan.confirmation.maxPointCost}</Descriptions.Item>
              <Descriptions.Item label="有效期至">{plan.confirmation.expiresAt}</Descriptions.Item>
              <Descriptions.Item label="执行能力">{plan.confirmation.consumptionEnabled ? '已开放' : '未开放'}</Descriptions.Item>
              {props.confirmationResult ? (
                <>
                  <Descriptions.Item label="已授权积分上限">
                    {props.confirmationResult.authorizedMaxPoints}
                  </Descriptions.Item>
                  <Descriptions.Item label="确认时间">
                    {props.confirmationResult.confirmedAt}
                  </Descriptions.Item>
                  <Descriptions.Item label="执行状态">
                    {props.confirmationResult.executionStarted ? '已开始' : '未开始'}
                  </Descriptions.Item>
                </>
              ) : plan.confirmedAt ? (
                <Descriptions.Item label="确认时间">{plan.confirmedAt}</Descriptions.Item>
              ) : null}
            </Descriptions>
            {isConfirmed ? plan.executionEnabled ? (
              <Button type="primary" onClick={() => props.onRequestStart(plan.id, plan.version)}>开始执行</Button>
            ) : (
              <Button type="primary" disabled>暂不可开始执行</Button>
            ) : displayStatus === 'QUEUED' ? (
              <Button type="primary" disabled>已进入执行队列</Button>
            ) : plan.status === 'WAITING_CONFIRMATION' ? (
              <Button
                type="primary"
                disabled={plan.confirmation.expired}
                onClick={() => props.onRequestConfirmation(plan.id, plan.version)}
              >
                {plan.confirmation.expired ? '确认已过期' : '确认计划'}
              </Button>
            ) : null}
          </section>

          {plan.events.length ? (
            <section className="workflow-plan-section" aria-labelledby="workflow-events-title">
              <Typography.Title id="workflow-events-title" level={4}>计划记录</Typography.Title>
              <Timeline
                items={plan.events.map((event) => ({
                  content: (
                    <div>
                      <Typography.Text strong>{event.label}</Typography.Text>
                      <Typography.Text type="secondary" className="workflow-plan-event-meta">
                        {event.transition} · {event.occurredAt}
                      </Typography.Text>
                      {event.errorCode ? <Typography.Text type="danger">处理说明：{event.errorCode}</Typography.Text> : null}
                    </div>
                  ),
                }))}
              />
            </section>
          ) : null}

          {props.abandonError ? (
            <Alert
              type="error"
              showIcon
              title={props.abandonError.message}
              description={diagnosticTraceDescription(props.abandonError.traceId)}
            />
          ) : null}
          {canAbandon ? (
            <div className="workflow-plan-actions">
              <Button danger loading={props.abandoning} onClick={() => props.onAbandon(plan.id, plan.version)}>放弃计划</Button>
            </div>
          ) : null}
        </div>
      )}
    </Drawer>
  )
}

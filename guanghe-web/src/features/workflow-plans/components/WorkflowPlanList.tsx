import { Button, Card, Select, Table, Typography } from 'antd'
import type { TableProps } from 'antd'
import { EmptyState, ErrorState, PageLoading } from '../../../components/GlobalState'
import type {
  WorkflowPlanErrorView,
  WorkflowPlanPaginationView,
  WorkflowPlanStatusView,
  WorkflowPlanSummaryView,
} from '../types'
import { WORKFLOW_PLAN_STATUS_LABELS } from '../workflowPlanAdapters'
import { WorkflowPlanStatusTag } from './WorkflowPlanStatusTag'

export interface WorkflowPlanListProps {
  plans: readonly WorkflowPlanSummaryView[]
  loading: boolean
  error: WorkflowPlanErrorView | null
  status?: WorkflowPlanStatusView
  pagination: WorkflowPlanPaginationView
  onStatusChange: (status?: WorkflowPlanStatusView) => void
  onPageChange: (page: number, pageSize: number) => void
  onView: (planId: string) => void
  onRetry: () => void
}

export function WorkflowPlanList(props: WorkflowPlanListProps) {
  const columns: TableProps<WorkflowPlanSummaryView>['columns'] = [
    {
      title: '目标',
      dataIndex: 'goalSummary',
      render: (value: string, plan) => (
        <div className="workflow-plan-goal">
          <Typography.Text strong ellipsis={{ tooltip: value }}>{value}</Typography.Text>
          <Typography.Text type="secondary">版本 {plan.version}</Typography.Text>
        </div>
      ),
    },
    { title: '状态', dataIndex: 'status', width: 110, render: (status: WorkflowPlanStatusView) => <WorkflowPlanStatusTag status={status} /> },
    { title: '步骤', dataIndex: 'stepCount', width: 80 },
    { title: '报价积分', dataIndex: 'totalQuotedPoints', width: 110 },
    { title: '积分上限', dataIndex: 'maxPointCost', width: 110 },
    { title: '确认截止', dataIndex: 'confirmationExpiresAt', width: 180 },
    { title: '更新时间', dataIndex: 'updatedAt', width: 180 },
    { title: '操作', key: 'action', width: 100, fixed: 'right', render: (_, plan) => <Button type="link" onClick={() => props.onView(plan.id)}>查看详情</Button> },
  ]

  return (
    <section className="workflow-plan-workspace" aria-labelledby="workflow-plan-title">
      <header className="workflow-plan-heading">
        <div>
          <Typography.Title id="workflow-plan-title" level={1}>组合计划</Typography.Title>
          <Typography.Paragraph type="secondary">查看复杂任务的计划步骤、报价预览与状态记录。</Typography.Paragraph>
        </div>
      </header>
      <Card className="workflow-plan-list-card">
        <div className="workflow-plan-filter">
          <Select
            allowClear
            aria-label="按计划状态筛选"
            placeholder="全部计划状态"
            value={props.status}
            options={(Object.keys(WORKFLOW_PLAN_STATUS_LABELS) as WorkflowPlanStatusView[]).map((value) => ({ value, label: WORKFLOW_PLAN_STATUS_LABELS[value] }))}
            onChange={(value) => props.onStatusChange(value)}
          />
        </div>
        <div className="workflow-plan-list-body">
          {props.loading ? <PageLoading label="正在加载组合计划…" /> : props.error ? (
            <ErrorState message={props.error.message} traceId={props.error.traceId} onRetry={props.onRetry} />
          ) : props.plans.length === 0 ? (
            <EmptyState title="还没有组合计划" description="复杂任务生成正式计划后，将在这里展示。" />
          ) : (
            <Table
              rowKey="id"
              dataSource={[...props.plans]}
              columns={columns}
              scroll={{ x: 1050 }}
              pagination={{
                current: props.pagination.current,
                pageSize: props.pagination.pageSize,
                total: props.pagination.total,
                showSizeChanger: true,
                showTotal: (total) => `共 ${total} 条`,
              }}
              onChange={(page) => props.onPageChange(page.current ?? 1, page.pageSize ?? props.pagination.pageSize)}
            />
          )}
        </div>
      </Card>
    </section>
  )
}

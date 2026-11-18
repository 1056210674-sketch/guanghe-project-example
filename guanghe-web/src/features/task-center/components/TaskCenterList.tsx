import { SearchOutlined } from '@ant-design/icons'
import {
  Button,
  Card,
  Input,
  Progress,
  Select,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { TableProps } from 'antd'
import { EmptyState, ErrorState, PageLoading } from '../../../components/GlobalState'
import {
  TASK_CENTER_STATUS_LABELS,
  TASK_CENTER_TYPE_LABELS,
  formatTaskCenterCount,
  formatTaskCenterPoints,
  formatTaskCenterProgress,
  normalizeTaskCenterPercent,
  taskCenterSettlementPoints,
} from '../presentation'
import type {
  TaskCenterActionType,
  TaskCenterFilterOptions,
  TaskCenterFilterValue,
  TaskCenterId,
  TaskCenterListItemView,
  TaskCenterPagination,
  TaskCenterSummaryMetricView,
  TaskCenterTaskStatus,
  TaskCenterTaskType,
  TaskCenterViewError,
} from '../types'
import { TaskActionButtons } from './TaskActionButtons'
import { TaskStatusTag } from './TaskStatusTag'

const TASK_TYPES: readonly TaskCenterTaskType[] = [
  'GEO',
  'IMAGE',
  'VIDEO',
  'ARTICLE',
  'MEDIA',
]

const TASK_STATUSES: readonly TaskCenterTaskStatus[] = [
  'QUEUED',
  'RUNNING',
  'RETRY_WAIT',
  'SUCCEEDED',
  'PARTIAL',
  'FAILED',
  'SKIPPED',
  'SYSTEM_TERMINATED',
]

export interface TaskCenterListProps {
  tasks: readonly TaskCenterListItemView[]
  summaryMetrics?: readonly TaskCenterSummaryMetricView[]
  loading: boolean
  error: TaskCenterViewError | null
  filters: TaskCenterFilterValue
  filterOptions: TaskCenterFilterOptions
  searchValue: string
  pagination: TaskCenterPagination
  onFiltersChange: (filters: TaskCenterFilterValue) => void
  onSearchChange: (value: string) => void
  onSearch?: (value: string) => void
  onPageChange: (page: number, pageSize: number) => void
  onAction: (taskId: TaskCenterId, actionType: TaskCenterActionType) => void
  onRetry?: () => void
  taskTypes?: readonly TaskCenterTaskType[]
  dateFilterEnabled?: boolean
  searchEnabled?: boolean
}

function TaskProgressCell({ task }: { task: TaskCenterListItemView }) {
  const percent = normalizeTaskCenterPercent(task.progress?.percent)
  const unitProgress = formatTaskCenterProgress(
    task.progress?.completedUnits,
    task.progress?.totalUnits,
  )

  if (!task.stage && !task.progress) {
    return <Typography.Text type="secondary">—</Typography.Text>
  }

  return (
    <div className="task-center-stage-cell">
      {task.stage ? <Typography.Text>{task.stage.label}</Typography.Text> : null}
      {unitProgress !== '—' ? (
        <Typography.Text type="secondary">{unitProgress}</Typography.Text>
      ) : null}
      {percent !== undefined ? (
        <Progress
          aria-label={`真实任务进度 ${percent}%`}
          percent={percent}
          size="small"
          showInfo={false}
        />
      ) : null}
    </div>
  )
}

function TaskPointsCell({ task }: { task: TaskCenterListItemView }) {
  if (!task.points) {
    return <Typography.Text type="secondary">—</Typography.Text>
  }

  const entries = [
    ['报价', task.points.quotedPoints],
    ['冻结', task.points.frozenPoints],
    ['结算', task.points.settledPoints],
    ['退回', task.points.returnedPoints],
  ] as const
  return (
    <div className="task-center-points-cell">
      {entries.map(([label, value]) => (
        <Typography.Text key={label} type="secondary">
          {label}：{label === '结算' || (label === '退回' && value === undefined)
            ? taskCenterSettlementPoints(value, task.status)
            : formatTaskCenterPoints(value)}
        </Typography.Text>
      ))}
    </div>
  )
}

function TaskCenterSummary({
  metrics,
}: {
  metrics: readonly TaskCenterSummaryMetricView[]
}) {
  if (metrics.length === 0) {
    return null
  }

  return (
    <div className="task-center-summary" aria-label="任务概览">
      {metrics.map((metric) => (
        <Card key={metric.id} className="task-center-summary__card" size="small">
          <Typography.Text type="secondary">{metric.label}</Typography.Text>
          <Typography.Title
            className={
              metric.tone
                ? `task-center-summary__value task-center-summary__value--${metric.tone}`
                : 'task-center-summary__value'
            }
            level={3}
          >
            {formatTaskCenterCount(metric.value)}
          </Typography.Title>
        </Card>
      ))}
    </div>
  )
}

export function TaskCenterList({
  tasks,
  summaryMetrics = [],
  loading,
  error,
  filters,
  filterOptions,
  searchValue,
  pagination,
  onFiltersChange,
  onSearchChange,
  onSearch,
  onPageChange,
  onAction,
  onRetry,
  taskTypes = TASK_TYPES,
  dateFilterEnabled = true,
  searchEnabled = true,
}: TaskCenterListProps) {
  const hasQuery = Boolean(
    (searchEnabled && searchValue) ||
      filters.taskType ||
      filters.status ||
      filters.businessSpaceId ||
      (dateFilterEnabled && (filters.createdFrom || filters.createdTo)),
  )

  const columns: TableProps<TaskCenterListItemView>['columns'] = [
    {
      title: '任务名称',
      width: 180,
      render: (_, task) => (
        <div className="task-center-name-cell">
          <Typography.Text strong>{task.name || '—'}</Typography.Text>
          <Typography.Text type="secondary">
            任务编号：{task.taskNumber || '—'}
          </Typography.Text>
          <Tag>{TASK_CENTER_TYPE_LABELS[task.taskType]}</Tag>
        </div>
      ),
    },
    {
      title: '关联对象',
      key: 'relatedObject',
      width: 150,
      render: (_, task) =>
        task.relatedObject ? (
          <div className="task-center-object-cell">
            <Typography.Text>{task.relatedObject.label}</Typography.Text>
            {task.relatedObject.description ? (
              <Typography.Text type="secondary">
                {task.relatedObject.description}
              </Typography.Text>
            ) : null}
          </div>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    {
      title: '当前阶段',
      key: 'stage',
      width: 150,
      render: (_, task) => <TaskProgressCell task={task} />,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (status: TaskCenterTaskStatus) => <TaskStatusTag status={status} />,
    },
    {
      title: '积分',
      key: 'points',
      width: 140,
      render: (_, task) => <TaskPointsCell task={task} />,
    },
    {
      title: '耗时',
      dataIndex: 'durationLabel',
      width: 110,
      responsive: ['xxl'],
      render: (value: string | undefined) => value || '—',
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 140,
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 180,
      render: (_, task) => (
        <TaskActionButtons
          taskId={task.id}
          actions={task.actions ?? []}
          onAction={onAction}
          compact
        />
      ),
    },
  ]

  return (
    <section className="task-center" aria-labelledby="task-center-title">
      <header className="task-center-heading">
        <div>
          <Typography.Title id="task-center-title" level={1}>
            任务中心
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            查看已确认任务的执行阶段、交付结果与积分结算。
          </Typography.Paragraph>
        </div>
      </header>

      <TaskCenterSummary metrics={summaryMetrics} />

      <Card className="task-center-list-card">
        <div className="task-center-filter-bar" aria-label="任务筛选">
          <Select
            allowClear
            aria-label="按任务类型筛选"
            placeholder="全部任务类型"
            value={filters.taskType}
            options={taskTypes.map((taskType) => ({
              label: TASK_CENTER_TYPE_LABELS[taskType],
              value: taskType,
            }))}
            onChange={(taskType) =>
              onFiltersChange({ ...filters, taskType: taskType ?? undefined })
            }
          />
          <Select
            allowClear
            aria-label="按任务状态筛选"
            placeholder="全部状态"
            value={filters.status}
            options={TASK_STATUSES.map((status) => ({
              label: TASK_CENTER_STATUS_LABELS[status],
              value: status,
            }))}
            onChange={(status) =>
              onFiltersChange({ ...filters, status: status ?? undefined })
            }
          />
          <Select
            allowClear
            aria-label="按客户项目筛选"
            placeholder="全部客户项目"
            value={filters.businessSpaceId}
            options={filterOptions.businessSpaces.map((option) => ({
              label: option.label,
              value: option.id,
            }))}
            onChange={(businessSpaceId) =>
              onFiltersChange({
                ...filters,
                businessSpaceId: businessSpaceId ?? undefined,
                productId: undefined,
              })
            }
          />
          {dateFilterEnabled ? (
            <>
              <Input
                aria-label="创建日期起始"
                type="date"
                value={filters.createdFrom ?? ''}
                onChange={(event) =>
                  onFiltersChange({
                    ...filters,
                    createdFrom: event.target.value || undefined,
                  })
                }
              />
              <Input
                aria-label="创建日期结束"
                type="date"
                value={filters.createdTo ?? ''}
                onChange={(event) =>
                  onFiltersChange({
                    ...filters,
                    createdTo: event.target.value || undefined,
                  })
                }
              />
            </>
          ) : null}
          {searchEnabled ? (
            <Input.Search
              allowClear
              aria-label="搜索任务名称"
              className="task-center-filter-bar__search"
              prefix={<SearchOutlined />}
              placeholder="搜索任务名称"
              value={searchValue}
              enterButton="搜索"
              onChange={(event) => onSearchChange(event.target.value)}
              onSearch={(value) => onSearch?.(value)}
            />
          ) : null}
          {hasQuery ? (
            <Button
              type="link"
              onClick={() => {
                onFiltersChange({})
                onSearchChange('')
                onSearch?.('')
              }}
            >
              清空筛选
            </Button>
          ) : null}
        </div>

        <div className="task-center-list-card__body">
          {loading ? (
            <PageLoading label="正在加载任务…" />
          ) : error ? (
            <ErrorState
              message={error.message}
              traceId={error.traceId}
              onRetry={onRetry}
            />
          ) : tasks.length === 0 ? (
            <EmptyState
              title={hasQuery ? '没有匹配的任务' : '还没有正式任务'}
              description={
                hasQuery
                  ? '请调整筛选条件后再试。'
                  : '任务确认并成功创建后，将在这里展示。'
              }
            />
          ) : (
            <Table<TaskCenterListItemView>
              rowKey="id"
              dataSource={[...tasks]}
              columns={columns}
              scroll={{ x: 1040 }}
              pagination={{
                current: pagination.current,
                pageSize: pagination.pageSize,
                total: pagination.total,
                showSizeChanger: true,
                showTotal: (total) => `共 ${total} 条`,
              }}
              onChange={(nextPagination) =>
                onPageChange(
                  nextPagination.current ?? 1,
                  nextPagination.pageSize ?? pagination.pageSize,
                )
              }
            />
          )}
        </div>
      </Card>
    </section>
  )
}

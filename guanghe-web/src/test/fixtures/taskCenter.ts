import type {
  TaskCenterFilterOptions,
  TaskCenterListItemView,
  TaskCenterPagination,
  TaskCenterSummaryMetricView,
  TaskCenterTaskDetailView,
} from '../../features/task-center'

export const taskCenterPagination: TaskCenterPagination = {
  current: 1,
  pageSize: 20,
  total: 1,
}

export const taskCenterFilterOptions: TaskCenterFilterOptions = {
  businessSpaces: [{ id: 'space-fixture-1', label: '测试经营空间' }],
  products: [
    {
      id: 'product-fixture-1',
      label: '测试商品',
      businessSpaceId: 'space-fixture-1',
    },
  ],
}

export const taskCenterSummaryFixture: readonly TaskCenterSummaryMetricView[] = [
  { id: 'all', label: '全部任务', value: 1 },
  { id: 'partial', label: '部分完成', value: 1, tone: 'warning' },
]

export const partialTaskFixture: TaskCenterListItemView = {
  id: 'task-fixture-partial',
  name: '测试 GEO 任务',
  taskNumber: 'TASK-FIXTURE-001',
  taskType: 'GEO',
  status: 'PARTIAL',
  relatedObject: {
    label: '测试商品',
    description: '测试经营空间',
  },
  stage: { code: 'RESULT_READY', label: '结果已汇总' },
  progress: { completedUnits: 6, totalUnits: 7, percent: 85 },
  points: {
    quotedPoints: 70,
    frozenPoints: 0,
    settledPoints: 60,
    returnedPoints: 10,
  },
  durationLabel: '测试耗时',
  createdAt: '测试创建时间',
  actions: [
    { type: 'VIEW_DETAILS', enabled: true },
    { type: 'VIEW_COMPLETED_REPORT', enabled: true },
    { type: 'PREPARE_RETRY_FAILED_ITEMS', enabled: true },
  ],
}

export const partialTaskDetailFixture: TaskCenterTaskDetailView = {
  ...partialTaskFixture,
  businessSpaceName: '测试经营空间',
  productName: '测试商品',
  startedAt: '测试开始时间',
  finishedAt: '测试完成时间',
  failureReason: '一个平台执行失败，其余结果已保留。',
  subtasks: [
    {
      id: 'unit-success',
      label: '测试成功平台',
      status: 'SUCCEEDED',
      resultSummary: '已生成结果摘要',
    },
    {
      id: 'unit-failed',
      label: '测试失败平台',
      status: 'FAILED',
      errorMessage: '测试平台暂时不可用',
    },
  ],
  resultSummary: {
    title: 'GEO 结果摘要',
    summary: '只汇总真实成功单元。',
    items: [
      {
        id: 'result-success',
        label: '成功范围',
        value: '六个平台',
        status: 'SUCCEEDED',
      },
      {
        id: 'result-failed',
        label: '失败范围',
        value: '一个平台',
        status: 'FAILED',
      },
    ],
  },
  timeline: [
    { id: 'event-created', label: '任务已创建', status: 'QUEUED' },
    { id: 'event-partial', label: '任务部分完成', status: 'PARTIAL' },
  ],
}

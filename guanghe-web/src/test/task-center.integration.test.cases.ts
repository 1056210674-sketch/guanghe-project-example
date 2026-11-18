import { describe, expect, it } from 'vitest'
import {
  TASK_CENTER_ACTION_LABELS,
  TASK_CENTER_STATUS_LABELS,
  TASK_CENTER_STATUS_TONES,
  formatTaskCenterPoints,
  formatTaskCenterProgress,
  taskCenterSettlementPoints,
} from '../features/task-center/presentation'
import {
  toTaskCenterListItem,
  toTaskCenterTaskDetail,
} from '../features/task-center'
import type { PaidTask, PaidTaskEvent } from '../api/conversationApi'
import {
  EMPTY_TASK_CENTER_FILTER_OPTIONS,
  EMPTY_TASK_CENTER_SUMMARY,
  EMPTY_TASK_CENTER_TASKS,
} from '../pages/task-center/pageDefaults'
import { APP_PATHS, APP_ROUTE_PATTERNS, appLinks } from '../routes/paths'
import './geo.integration.test.cases'
import {
  partialTaskDetailFixture,
  partialTaskFixture,
  taskCenterFilterOptions,
  taskCenterPagination,
} from './fixtures/taskCenter'

const taskProductionModules = import.meta.glob(
  '../{features,pages}/task-center/**/*.{ts,tsx}',
  { eager: true, import: 'default', query: '?raw' },
) as Record<string, string>
const taskStyleModules = import.meta.glob('../styles/task-center.css', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>
const routeModules = import.meta.glob('../routes/AppRoutes.tsx', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

const taskSource = Object.values(taskProductionModules).join('\n')
const taskStyles = Object.values(taskStyleModules).join('\n')
const routeSource = Object.values(routeModules).join('\n')

describe('任务中心展示契约', () => {
  it('21条处理中16成功5失败，与结算16退回5独立展示', () => {
    const task: PaidTask = {
      id: '61', taskType: 'GEO', status: 'PARTIAL', stageCode: 'COMPLETED', stageLabel: '采样已处理', spaceId: '21',
      quotedPoints: 21, settledPoints: 16, frozenPoints: 0, releasedPoints: 5,
      progress: { percent: 100, completedUnits: 21, totalUnits: 21 },
      result: { runId: '71', reportId: '81', status: 'PARTIAL', expectedUnits: 21, succeededUnits: 16, failedUnits: 5, missingUnits: 0, settledPoints: 16, releasedPoints: 5 },
      createdAt: '2026-10-05T00:00:00Z', updatedAt: '2026-10-05T00:01:00Z',
    }
    const detail = toTaskCenterTaskDetail(task)
    expect(detail.points).toEqual({ quotedPoints: 21, frozenPoints: 0, settledPoints: 16, returnedPoints: 5 })
    expect(detail.resultSummary?.summary).toBe('计划 21 个采样单元，成功 16 个，失败 5 个，缺失 0 个。')
    expect(formatTaskCenterProgress(detail.progress?.completedUnits, detail.progress?.totalUnits)).toBe('已处理 21/21')
    expect(toTaskCenterListItem({ ...task, frozenPoints: null, settledPoints: null, releasedPoints: null }).points).toEqual({ quotedPoints: 21, frozenPoints: undefined, settledPoints: undefined, returnedPoints: undefined })
    expect(formatTaskCenterPoints(undefined)).toBe('暂不可查')
    expect(formatTaskCenterPoints(0)).toBe('0 积分')
    expect(taskCenterSettlementPoints(0, 'RUNNING')).toBe('待结算')
    expect(taskCenterSettlementPoints(undefined, 'RUNNING')).toBe('待结算')
    expect(taskCenterSettlementPoints(undefined, 'PARTIAL')).toBe('暂不可查')
  })
  it('图片任务列表只关联已经就绪的真实结果资产', () => {
    const task: PaidTask = {
      id: 'image-task', taskType: 'IMAGE', status: 'SUCCEEDED',
      stageCode: 'COMPLETED', stageLabel: '已完成', spaceId: '21',
      quotedPoints: 5, settledPoints: 5, frozenPoints: 0, progress: null,
      result: {
        provider: 'QWEN_IMAGE', model: 'qwen-image-3.0', providerCostReported: false,
        asset: {
          assetId: 'real-result-asset', assetType: 'IMAGE', status: 'READY',
          contentType: 'image/png', sizeBytes: 1000, sha256: 'a'.repeat(64),
          width: 1024, height: 1024, safetyStatus: 'APPROVED',
        },
      },
      createdAt: '2026-09-24T01:00:00Z', updatedAt: '2026-09-24T01:01:00Z',
    }

    expect(toTaskCenterListItem(task).previewAssetId).toBe('real-result-asset')
    expect(toTaskCenterListItem({ ...task, status: 'FAILED', result: {} }).previewAssetId).toBeUndefined()
  })

  it('真实 VIDEO Task 列表和事件保序映射，不伪造运行百分比', () => {
    const task: PaidTask = {
      id: '61',
      taskType: 'VIDEO',
      status: 'RUNNING',
      stageCode: 'PROVIDER_GENERATING',
      stageLabel: '视频生成中',
      spaceId: '21',
      productId: '31',
      quotedPoints: 10,
      settledPoints: 0,
      frozenPoints: 10,
      progress: null,
      result: {},
      errorCode: null,
      createdAt: '2026-09-08T01:00:00Z',
      updatedAt: '2026-09-08T01:01:00Z',
    }
    const events: PaidTaskEvent[] = [
      {
        id: '71', sequence: 1, eventType: 'task.queued', status: 'QUEUED',
        stageCode: 'QUEUED', errorCode: null, occurredAt: task.createdAt,
      },
      {
        id: '72', sequence: 2, eventType: 'task.progress', status: 'RUNNING',
        stageCode: 'PROVIDER_GENERATING', errorCode: null,
        occurredAt: task.updatedAt,
      },
    ]

    const listItem = toTaskCenterListItem(task)
    const namedListItem = toTaskCenterListItem(task, {
      businessSpaceName: '抖店',
      productName: '美式咖啡',
    })
    const detail = toTaskCenterTaskDetail(task, {}, events)

    expect(listItem.taskType).toBe('VIDEO')
    expect(listItem.status).toBe('RUNNING')
    expect(listItem.stage?.label).toBe('视频生成中')
    expect(listItem.progress).toBeUndefined()
    expect(namedListItem.relatedObject?.label).toBe('美式咖啡')
    expect(detail.timeline?.map((event) => event.id)).toEqual(['71', '72'])
    expect(detail.timeline?.map((event) => event.label)).toEqual([
      '等待执行',
      '服务生成中',
    ])
  })

  it('失败任务和时间线只展示用户可操作说明，不泄露内部错误码', () => {
    const failedTask: PaidTask = {
      id: 'task-failed-1',
      taskType: 'GEO',
      status: 'FAILED',
      stageCode: 'FAILED',
      stageLabel: '监测未完成',
      spaceId: '21',
      productId: '31',
      quotedPoints: 10,
      settledPoints: 0,
      frozenPoints: 0,
      progress: null,
      result: {},
      errorCode: 'WORKER_LEASE_EXPIRED',
      createdAt: '2026-09-08T01:00:00Z',
      updatedAt: '2026-09-08T01:01:00Z',
    }
    const events: PaidTaskEvent[] = [{
      id: 'event-failed-1',
      sequence: 1,
      eventType: 'task.failed',
      status: 'FAILED',
      stageCode: 'FAILED',
      errorCode: 'WORKER_LEASE_EXPIRED',
      occurredAt: failedTask.updatedAt,
    }]

    const detail = toTaskCenterTaskDetail(failedTask, {}, events)

    expect(detail.failureReason).toContain('执行服务暂时异常')
    expect(detail.timeline?.[0]?.description).toContain('执行服务暂时异常')
    expect(JSON.stringify(detail)).not.toContain('WORKER_LEASE_EXPIRED')
  })

  it('租约过期等待重试时不把旧执行阶段误报成仍在处理中', () => {
    const task: PaidTask = {
      id: 'task-retry-1',
      taskType: 'ARTICLE',
      status: 'RETRY_WAIT',
      stageCode: 'PROCESSING',
      stageLabel: '处理中',
      spaceId: '21',
      productId: '31',
      quotedPoints: 1,
      settledPoints: 0,
      frozenPoints: 1,
      progress: null,
      result: {},
      errorCode: 'WORKER_LEASE_EXPIRED',
      createdAt: '2026-09-30T04:18:00Z',
      updatedAt: '2026-09-30T04:20:00Z',
    }
    const event: PaidTaskEvent = {
      id: 'event-retry-1',
      sequence: 2,
      eventType: 'task.progress',
      status: 'RETRY_WAIT',
      stageCode: 'PROCESSING',
      errorCode: 'WORKER_LEASE_EXPIRED',
      occurredAt: task.updatedAt,
    }

    const listItem = toTaskCenterListItem(task)
    const detail = toTaskCenterTaskDetail(task, {}, [event])

    expect(listItem.stage?.label).toBe('等待重新执行')
    expect(detail.stage?.label).toBe('等待重新执行')
    expect(detail.timeline?.[0]?.label).toBe('执行中断，等待重试')
    expect(detail.failureReason).toContain('暂勿重复发起')
    expect(JSON.stringify(detail)).not.toContain('WORKER_LEASE_EXPIRED')
  })

  it('生产页面以真实空数据开始，并具备 Loading、Empty、Error 展示', () => {
    expect(EMPTY_TASK_CENTER_TASKS).toEqual([])
    expect(EMPTY_TASK_CENTER_SUMMARY).toEqual([])
    expect(EMPTY_TASK_CENTER_FILTER_OPTIONS).toEqual({
      businessSpaces: [],
      products: [],
    })
    expect(taskSource).toContain('loading')
    expect(taskSource).toContain('error')
    expect(taskSource).toContain('还没有正式任务')
    expect(taskSource).not.toContain('TASK-FIXTURE-001')
  })

  it('任务列表和详情共享同一状态，部分完成保留成功项、失败项和积分结算', () => {
    expect(partialTaskDetailFixture.id).toBe(partialTaskFixture.id)
    expect(partialTaskDetailFixture.status).toBe(partialTaskFixture.status)
    expect(TASK_CENTER_STATUS_LABELS[partialTaskFixture.status]).toBe('部分完成')
    expect(TASK_CENTER_STATUS_TONES[partialTaskFixture.status]).toBe('warning')
    expect(partialTaskDetailFixture.subtasks?.map((item) => item.status)).toEqual([
      'SUCCEEDED',
      'FAILED',
    ])
    expect(partialTaskDetailFixture.failureReason).toBeTruthy()
    expect(formatTaskCenterProgress(6, 7)).toBe('已处理 6/7')
    expect(formatTaskCenterPoints(partialTaskFixture.points?.settledPoints)).toBe(
      '60 积分',
    )
    expect(formatTaskCenterPoints(partialTaskFixture.points?.returnedPoints)).toBe(
      '10 积分',
    )
  })

  it('筛选、搜索、分页和结果操作均保留外部回调边界', () => {
    expect(taskCenterFilterOptions.businessSpaces).toHaveLength(1)
    expect(taskCenterFilterOptions.products[0]?.businessSpaceId).toBe(
      taskCenterFilterOptions.businessSpaces[0]?.id,
    )
    expect(taskCenterPagination).toEqual({ current: 1, pageSize: 20, total: 1 })
    for (const callbackName of [
      'onFiltersChange',
      'onSearchChange',
      'onPageChange',
      'onAction',
    ]) {
      expect(taskSource).toContain(callbackName)
    }
    expect(TASK_CENTER_ACTION_LABELS.VIEW_COMPLETED_REPORT).toBe(
      '查看已完成报告',
    )
    expect(TASK_CENTER_ACTION_LABELS.PREPARE_RETRY_FAILED_ITEMS).toBe(
      '仅重试失败项',
    )
    expect(taskSource).toContain('onAction(taskId, action.type)')
    expect(taskSource).not.toMatch(/取消任务|终止任务|撤销任务/)
  })

  it('任务类型、状态和结算值不由展示层猜测或计算', () => {
    expect(Object.keys(TASK_CENTER_STATUS_LABELS)).toEqual([
      'QUEUED',
      'RUNNING',
      'RETRY_WAIT',
      'SUCCEEDED',
      'PARTIAL',
      'FAILED',
      'SKIPPED',
      'SYSTEM_TERMINATED',
    ])
    expect(formatTaskCenterPoints(-1)).toBe('—')
    expect(formatTaskCenterPoints(Number.NaN)).toBe('—')
    expect(formatTaskCenterProgress(undefined, 7)).toBe('—')
    expect(taskSource).not.toMatch(/settledPoints\s*[+\-*/]|returnedPoints\s*[+\-*/]/)
  })
})

describe('任务中心路由和生产边界', () => {
  it('任务列表与详情路由集中定义并位于 USER 守卫内', () => {
    expect(APP_PATHS.tasks).toBe('/app/tasks')
    expect(APP_ROUTE_PATTERNS.taskDetail).toBe('/app/tasks/:taskId')
    expect(appLinks.taskDetail('task id')).toBe('/app/tasks/task%20id')

    const userRouteSection = routeSource
      .split('<Route element={<ProtectedRoute role="USER" />}>')[1]
      ?.split('<Route element={<ProtectedRoute role="ADMIN" />}>')[0]
    expect(userRouteSection).toContain('path={APP_PATHS.tasks}')
    expect(userRouteSection).toContain('path={APP_ROUTE_PATTERNS.taskDetail}')
  })

  it('生产代码不导入 Fixture、运行时 Mock、轮询或越界接口', () => {
    expect(Object.keys(taskProductionModules).length).toBeGreaterThan(0)
    for (const [path, source] of Object.entries(taskProductionModules)) {
      expect(source, path).not.toMatch(
        /(?:from|import\s*)[\s(']*['"][^'"]*(?:\/test\/|fixtures?)/,
      )
      expect(source, path).not.toMatch(
        /\b(?:fetch|axios|EventSource|WebSocket)\b/,
      )
      expect(source, path).not.toMatch(/set(?:Timeout|Interval)\s*\(/)
      expect(source, path).not.toMatch(/\/api\/v1\/|\/internal\/v1\/|:8000/)
      expect(source, path).not.toMatch(
        /location\.|URL\.createObjectURL|navigator\.clipboard/,
      )
      expect(source.replaceAll('window.open', ''), path).not.toMatch(/window\./)
      expect(source, path).not.toMatch(/<a\s|\bhref=|\bdownload=/)
      expect(source, path).not.toMatch(/采购原价|采购成本|利润|35%/)
    }
    expect(taskSource).toContain('useTaskCenterDetail(taskId)')
  })

  it('1280px 与 1440px 布局保留关键操作且不强制横向溢出', () => {
    expect(taskStyles).toContain('min-width: 0')
    expect(taskStyles).toMatch(/@media\s*\(max-width:\s*1360px\)/)
    expect(taskSource).toContain("fixed: 'right'")
    expect(taskSource).toContain('scroll={{ x: 1040 }}')
  })
})

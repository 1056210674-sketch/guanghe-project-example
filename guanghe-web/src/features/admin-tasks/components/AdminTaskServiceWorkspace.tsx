import {
  ApartmentOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  DatabaseOutlined,
  ExclamationCircleOutlined,
  ReloadOutlined,
  SearchOutlined,
  SafetyCertificateOutlined,
  ToolOutlined,
} from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Input,
  Modal,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd'
import { useMemo, useState } from 'react'
import type {
  AdminExecutionTrace,
  AdminPlatformExecution,
  AdminServiceStatus,
  AdminServiceSummary,
  AdminTaskActionDraft,
  AdminTaskStatus,
  AdminTaskSummary,
  AdminTaskTimelineItem,
} from '../types'

type WorkspaceTab = 'TASKS' | 'SERVICES'

const TASK_STATUS_META: Record<AdminTaskStatus, { label: string; color: string }> = {
  QUEUED: { label: '排队中', color: 'default' },
  RUNNING: { label: '执行中', color: 'processing' },
  SUCCEEDED: { label: '成功', color: 'success' },
  PARTIAL_FAILED: { label: '部分失败', color: 'warning' },
  FAILED: { label: '失败', color: 'error' },
  UNKNOWN: { label: '结果未知', color: 'default' },
}

const SERVICE_STATUS_META: Record<AdminServiceStatus, { label: string; color: string }> = {
  HEALTHY: { label: '正常', color: 'success' },
  DEGRADED: { label: '性能下降', color: 'warning' },
  UNAVAILABLE: { label: '不可用', color: 'error' },
}

function isNonNegativeSafeInteger(value: number | undefined): value is number {
  return value !== undefined && Number.isSafeInteger(value) && value >= 0
}

function formatInteger(value: number | undefined): string {
  return isNonNegativeSafeInteger(value) ? value.toLocaleString('zh-CN') : '—'
}

function formatDuration(value: number | undefined): string {
  if (!isNonNegativeSafeInteger(value)) {
    return '—'
  }
  if (value < 1000) {
    return `${value}ms`
  }
  return `${(value / 1000).toFixed(2)}s`
}

function formatErrorRate(value: number | undefined): string {
  return value !== undefined && Number.isFinite(value) && value >= 0 && value <= 100
    ? `${value}%`
    : '—'
}

function taskActionIsEligible(
  task: AdminTaskSummary | undefined,
  action: AdminTaskActionDraft['action'] | undefined,
): boolean {
  if (!task || !action) {
    return false
  }

  if (action === 'REFRESH_STATUS') {
    return true
  }

  return (
    task.status === 'FAILED' ||
    task.status === 'PARTIAL_FAILED' ||
    task.status === 'UNKNOWN'
  )
}

function TimelineStatusIcon({ status }: { status: AdminTaskTimelineItem['status'] }) {
  if (status === 'SUCCESS') return <CheckCircleOutlined />
  if (status === 'FAILED') return <CloseCircleOutlined />
  if (status === 'RUNNING') return <ClockCircleOutlined />
  return <span className="admin-task-dot" />
}

function PlatformTable({ executions }: { executions: readonly AdminPlatformExecution[] }) {
  if (executions.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无平台执行数据" />
  }

  return (
    <div className="admin-table-scroll">
      <table className="admin-data-table" aria-label="平台执行结果">
        <thead>
          <tr>
            <th scope="col">平台</th>
            <th scope="col">状态</th>
            <th scope="col">耗时</th>
            <th scope="col">重试次数</th>
            <th scope="col">错误分类</th>
          </tr>
        </thead>
        <tbody>
          {executions.map((execution) => (
            <tr key={execution.id}>
              <td><strong>{execution.platform}</strong></td>
              <td>
                <Tag color={execution.status === 'SUCCEEDED' ? 'success' : execution.status === 'FAILED' ? 'error' : 'processing'}>
                  {execution.status === 'SUCCEEDED' ? '成功' : execution.status === 'FAILED' ? '失败' : '执行中'}
                </Tag>
              </td>
              <td>{formatDuration(execution.durationMs)}</td>
              <td>{formatInteger(execution.retryCount)}</td>
              <td>{execution.errorCategory?.trim() || '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function TraceList({ traces }: { traces: readonly AdminExecutionTrace[] }) {
  if (traces.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无执行轨迹" />
  }

  return (
    <ul className="admin-trace-list" aria-label="执行轨迹">
      {traces.map((trace) => (
        <li key={trace.id}>
          <span className={`admin-trace-list__status is-${trace.status.toLocaleLowerCase()}`} aria-hidden="true" />
          <strong>{trace.stage}</strong>
          <span>{trace.occurredAt}</span>
          <span>{formatDuration(trace.durationMs)}</span>
          <code>{trace.traceId?.trim() || '—'}</code>
        </li>
      ))}
    </ul>
  )
}

export interface AdminTaskServiceWorkspaceProps {
  tasks: readonly AdminTaskSummary[]
  services: readonly AdminServiceSummary[]
  actionsEnabled?: boolean
  preview?: boolean
  onTaskAction?: (draft: AdminTaskActionDraft) => void
}

export function AdminTaskServiceWorkspace({
  tasks,
  services,
  actionsEnabled = false,
  preview = false,
  onTaskAction,
}: AdminTaskServiceWorkspaceProps) {
  const [tab, setTab] = useState<WorkspaceTab>('TASKS')
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<'ALL' | AdminTaskStatus>('ALL')
  const [type, setType] = useState('ALL')
  const [selectedTaskId, setSelectedTaskId] = useState<string>()
  const [action, setAction] = useState<AdminTaskActionDraft['action']>()
  const [actionTaskId, setActionTaskId] = useState<string>()
  const [actionReason, setActionReason] = useState('')

  const taskTypes = useMemo(
    () => Array.from(new Set(tasks.map((task) => task.type))).sort(),
    [tasks],
  )
  const normalizedQuery = query.trim().toLocaleLowerCase('zh-CN')
  const filteredTasks = useMemo(
    () =>
      tasks.filter((task) => {
        const matchesStatus = status === 'ALL' || task.status === status
        const matchesType = type === 'ALL' || task.type === type
        const matchesQuery =
          !normalizedQuery ||
          [task.id, task.userMasked, task.productName, task.traceId].some((value) =>
            value?.toLocaleLowerCase('zh-CN').includes(normalizedQuery),
          )
        return matchesStatus && matchesType && matchesQuery
      }),
    [normalizedQuery, status, tasks, type],
  )
  const selectedTask = tasks.find((task) => task.id === selectedTaskId)
  const failedTaskCount = tasks.filter((task) =>
    ['FAILED', 'PARTIAL_FAILED', 'UNKNOWN'].includes(task.status),
  ).length
  const unhealthyServiceCount = services.filter((service) => service.status !== 'HEALTHY').length
  const canOperate = actionsEnabled && Boolean(onTaskAction)
  const actionEligible =
    actionTaskId !== undefined &&
    actionTaskId === selectedTask?.id &&
    taskActionIsEligible(selectedTask, action)
  const actionValid = actionEligible && actionReason.trim().length >= 2

  if (action && !actionEligible) {
    setAction(undefined)
    setActionTaskId(undefined)
    setActionReason('')
  }

  const closeAction = () => {
    setAction(undefined)
    setActionTaskId(undefined)
    setActionReason('')
  }

  const openAction = (nextAction: AdminTaskActionDraft['action']) => {
    if (!taskActionIsEligible(selectedTask, nextAction)) {
      return
    }

    setAction(nextAction)
    setActionTaskId(selectedTask?.id)
    setActionReason('')
  }

  const submitAction = () => {
    if (
      !selectedTask ||
      !action ||
      actionTaskId !== selectedTask.id ||
      !taskActionIsEligible(selectedTask, action) ||
      !canOperate ||
      !onTaskAction ||
      actionReason.trim().length < 2
    ) {
      return
    }
    onTaskAction({
      taskId: selectedTask.id,
      action,
      reason: actionReason.trim(),
    })
    closeAction()
  }

  return (
    <section className="admin-workspace" aria-labelledby="admin-tasks-title">
      <header className="admin-workspace__heading">
        <div>
          <Typography.Title id="admin-tasks-title" level={1}>
            任务与服务
          </Typography.Title>
          <Typography.Text type="secondary">
            诊断异步任务、平台执行结果与服务健康状态
          </Typography.Text>
        </div>
        <Tag icon={<SafetyCertificateOutlined />} color="red">
          管理员诊断
        </Tag>
      </header>

      {preview ? (
        <Alert
          type="info"
          showIcon
          title="开发设计预览"
          description="以下任务、Trace 和服务状态均为界面验收样例，不代表生产运行结果。"
        />
      ) : (
        <Alert
          type="warning"
          showIcon
          title="任务诊断接口尚未接入"
          description="当前不执行重试、终止或积分补偿；任务和服务状态必须由服务端提供。"
        />
      )}

      <div className="admin-stat-grid" aria-label="任务与服务概览">
        <Card variant="borderless"><ApartmentOutlined /><span>任务总数</span><strong>{tasks.length.toLocaleString('zh-CN')}</strong></Card>
        <Card variant="borderless"><ClockCircleOutlined /><span>执行中</span><strong>{tasks.filter((task) => task.status === 'RUNNING').length.toLocaleString('zh-CN')}</strong></Card>
        <Card variant="borderless"><ExclamationCircleOutlined /><span>需关注任务</span><strong>{failedTaskCount.toLocaleString('zh-CN')}</strong></Card>
        <Card variant="borderless"><ToolOutlined /><span>异常服务</span><strong>{unhealthyServiceCount.toLocaleString('zh-CN')}</strong></Card>
      </div>

      <nav className="admin-workspace-tabs" aria-label="任务与服务模块">
        <button type="button" className={tab === 'TASKS' ? 'is-active' : ''} aria-current={tab === 'TASKS' ? 'page' : undefined} onClick={() => setTab('TASKS')}>任务诊断</button>
        <button type="button" className={tab === 'SERVICES' ? 'is-active' : ''} aria-current={tab === 'SERVICES' ? 'page' : undefined} onClick={() => setTab('SERVICES')}>服务状态</button>
      </nav>

      {tab === 'TASKS' ? (
        <Card className="admin-data-card" variant="borderless">
          <div className="admin-filter-bar admin-filter-bar--tasks">
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="搜索任务号、用户、商品或 Trace"
              aria-label="搜索任务"
              value={query}
              disabled={tasks.length === 0}
              onChange={(event) => setQuery(event.target.value)}
            />
            <Select
              aria-label="任务类型"
              value={type}
              disabled={tasks.length === 0}
              options={[{ value: 'ALL', label: '全部类型' }, ...taskTypes.map((value) => ({ value, label: value }))]}
              onChange={setType}
            />
            <Select
              aria-label="任务状态"
              value={status}
              disabled={tasks.length === 0}
              options={[
                { value: 'ALL', label: '全部状态' },
                ...Object.entries(TASK_STATUS_META).map(([value, meta]) => ({ value, label: meta.label })),
              ]}
              onChange={(value) => setStatus(value)}
            />
          </div>
          {tasks.length === 0 ? (
            <Empty
              image={<DatabaseOutlined />}
              description={<div className="admin-empty-copy"><strong>暂无任务诊断数据</strong><span>任务查询与 Trace 接口接入后将在此展示真实执行记录</span></div>}
            />
          ) : filteredTasks.length === 0 ? (
            <Empty description="没有符合条件的任务" />
          ) : (
            <div className="admin-table-scroll">
              <table className="admin-data-table admin-task-table" aria-label="管理员任务列表">
                <thead><tr><th scope="col">任务号</th><th scope="col">用户</th><th scope="col">业务类型</th><th scope="col">状态</th><th scope="col">创建时间</th><th scope="col">耗时</th><th scope="col">结算积分</th><th scope="col">Trace</th><th scope="col">操作</th></tr></thead>
                <tbody>
                  {filteredTasks.map((task) => (
                    <tr key={task.id}>
                      <td><strong>{task.id}</strong></td>
                      <td>{task.userMasked}</td>
                      <td>{task.type}</td>
                      <td><Tag color={TASK_STATUS_META[task.status].color}>{TASK_STATUS_META[task.status].label}</Tag></td>
                      <td>{task.createdAt}</td>
                      <td>{formatDuration(task.durationMs)}</td>
                      <td>{formatInteger(task.settledPoints)}</td>
                      <td><code>{task.traceId?.trim() || '—'}</code></td>
                      <td><Button type="link" onClick={() => setSelectedTaskId(task.id)}>诊断详情</Button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      ) : (
        <Card className="admin-data-card" variant="borderless">
          {services.length === 0 ? (
            <Empty
              image={<DatabaseOutlined />}
              description={<div className="admin-empty-copy"><strong>暂无服务状态数据</strong><span>Provider、队列和 Worker 监控接口接入后将在此展示</span></div>}
            />
          ) : (
            <div className="admin-service-grid" aria-label="服务状态列表">
              {services.map((service) => (
                <article key={service.id} className="admin-service-card">
                  <div><ToolOutlined /><strong>{service.name}</strong><Tag color={SERVICE_STATUS_META[service.status].color}>{SERVICE_STATUS_META[service.status].label}</Tag></div>
                  <dl>
                    <div><dt>类型</dt><dd>{service.kind}</dd></div>
                    <div><dt>延迟</dt><dd>{formatDuration(service.latencyMs)}</dd></div>
                    <div><dt>错误率</dt><dd>{formatErrorRate(service.errorRatePercent)}</dd></div>
                    <div><dt>队列深度</dt><dd>{formatInteger(service.queueDepth)}</dd></div>
                  </dl>
                  <span>更新时间：{service.updatedAt}</span>
                </article>
              ))}
            </div>
          )}
        </Card>
      )}

      <Drawer
        className="admin-task-drawer"
        title={selectedTask ? `任务诊断 · ${selectedTask.id}` : '任务诊断'}
        size="min(980px, 92vw)"
        open={Boolean(selectedTask)}
        onClose={() => setSelectedTaskId(undefined)}
      >
        {selectedTask ? (
          <div className="admin-task-detail">
            <div className="admin-task-detail__hero">
              <div><Typography.Title level={2}>{selectedTask.type}</Typography.Title><Tag color={TASK_STATUS_META[selectedTask.status].color}>{TASK_STATUS_META[selectedTask.status].label}</Tag></div>
              <Space>
                <Button icon={<ReloadOutlined />} aria-label="重新拉取状态" disabled={!canOperate || !taskActionIsEligible(selectedTask, 'REFRESH_STATUS')} onClick={() => openAction('REFRESH_STATUS')}>重新拉取状态</Button>
                <Button danger aria-label="受控重试" disabled={!canOperate || !taskActionIsEligible(selectedTask, 'CONTROLLED_RETRY')} onClick={() => openAction('CONTROLLED_RETRY')}>受控重试</Button>
              </Space>
            </div>
            <dl className="admin-task-facts">
              <div><dt>用户（脱敏）</dt><dd>{selectedTask.userMasked}</dd></div>
              <div><dt>关联商品</dt><dd>{selectedTask.productName?.trim() || '—'}</dd></div>
              <div><dt>创建时间</dt><dd>{selectedTask.createdAt}</dd></div>
              <div><dt>完成时间</dt><dd>{selectedTask.completedAt?.trim() || '—'}</dd></div>
              <div><dt>已结算积分</dt><dd>{formatInteger(selectedTask.settledPoints)}</dd></div>
              <div><dt>Trace ID</dt><dd><code>{selectedTask.traceId?.trim() || '—'}</code></dd></div>
            </dl>
            <div className="admin-task-detail__grid">
              <Card title="业务状态时间线" variant="borderless">
                {selectedTask.timeline?.length ? (
                  <ol className="admin-timeline">
                    {selectedTask.timeline.map((item) => (
                      <li key={item.id} className={`is-${item.status.toLocaleLowerCase()}`}>
                        <TimelineStatusIcon status={item.status} />
                        <div><strong>{item.title}</strong><span>{item.description}</span></div>
                        <time>{item.occurredAt}</time>
                      </li>
                    ))}
                  </ol>
                ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无时间线数据" />}
              </Card>
              <Card title="平台执行结果" variant="borderless">
                <PlatformTable executions={selectedTask.platformExecutions ?? []} />
              </Card>
            </div>
            <div className="admin-task-detail__bottom">
              <Card title="执行轨迹" variant="borderless"><TraceList traces={selectedTask.traces ?? []} /></Card>
              <Card title="异常摘要" variant="borderless">
                {selectedTask.errorMessage ? (
                  <div className="admin-issue-summary">
                    <Tag color="error">{selectedTask.errorCategory || '执行异常'}</Tag>
                    <dl><div><dt>错误信息</dt><dd>{selectedTask.errorMessage}</dd></div><div><dt>处理建议</dt><dd>{selectedTask.recoverySuggestion || '请结合服务端日志和 Trace 进一步核查'}</dd></div></dl>
                  </div>
                ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有异常摘要" />}
              </Card>
            </div>
            <Alert type="warning" showIcon title="诊断操作必须由服务端重新核验任务状态、保证幂等，并写入管理员审计日志" />
          </div>
        ) : null}
      </Drawer>

      <Modal
        title={action === 'CONTROLLED_RETRY' ? '确认受控重试' : '确认重新拉取状态'}
        open={Boolean(action) && actionEligible}
        forceRender
        okText="确认操作"
        cancelText="取消"
        okButtonProps={{ danger: action === 'CONTROLLED_RETRY', disabled: !actionValid || !canOperate || !taskActionIsEligible(selectedTask, action) }}
        onOk={submitAction}
        onCancel={closeAction}
      >
        <div className="admin-modal-form">
          <Alert type="warning" showIcon title="不得凭前端状态重复执行或重复结算任务" />
          <label><span>操作原因</span><Input.TextArea value={actionReason} aria-label="任务操作原因" placeholder="请输入可审计的操作原因" onChange={(event) => setActionReason(event.target.value)} /></label>
        </div>
      </Modal>
    </section>
  )
}

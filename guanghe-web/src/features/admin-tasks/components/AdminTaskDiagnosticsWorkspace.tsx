import {
  ClockCircleOutlined,
  CopyOutlined,
  DatabaseOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Input,
  Pagination,
  Select,
  Skeleton,
  Space,
  Tag,
  Typography,
} from 'antd'
import { useState } from 'react'
import type {
  AdminTaskDiagnosticDetailView,
  AdminTaskDiagnosticErrorView,
  AdminTaskDiagnosticFiltersView,
  AdminTaskDiagnosticSummaryView,
  AdminTaskStatusTone,
} from '../types'

const taskTypes = ['IMAGE', 'VIDEO', 'GEO', 'ARTICLE', 'MEDIA'] as const
const taskStatuses = [
  'QUEUED',
  'RUNNING',
  'RETRY_WAIT',
  'SUCCEEDED',
  'PARTIAL',
  'FAILED',
  'SKIPPED',
  'SYSTEM_TERMINATED',
] as const

const toneColors: Record<AdminTaskStatusTone, string> = {
  neutral: 'default',
  info: 'processing',
  success: 'success',
  warning: 'warning',
  danger: 'error',
}

function value(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  return String(value)
}

function points(valueToFormat: number | undefined): string {
  return valueToFormat !== undefined && Number.isSafeInteger(valueToFormat) && valueToFormat >= 0
    ? valueToFormat.toLocaleString('zh-CN')
    : '—'
}

function ErrorNotice({ error }: { error?: AdminTaskDiagnosticErrorView | null }) {
  if (!error) return null
  return (
    <Alert
      type="error"
      showIcon
      title={error.message}
      description={error.traceId ? `traceId：${error.traceId}` : undefined}
    />
  )
}

function SimpleTable({
  label,
  headings,
  rows,
}: {
  label: string
  headings: readonly string[]
  rows: readonly (readonly (string | number | null | undefined)[])[]
}) {
  if (rows.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={`暂无${label}`} />
  }
  return (
    <div className="admin-table-scroll">
      <table className="admin-data-table admin-diagnostic-small-table" aria-label={label}>
        <thead><tr>{headings.map((heading) => <th key={heading}>{heading}</th>)}</tr></thead>
        <tbody>{rows.map((row, rowIndex) => (
          <tr key={`${label}-${rowIndex}`}>{row.map((cell, cellIndex) => <td key={`${rowIndex}-${cellIndex}`}>{value(cell)}</td>)}</tr>
        ))}</tbody>
      </table>
    </div>
  )
}

function Detail({ detail }: { detail: AdminTaskDiagnosticDetailView }) {
  return (
    <div className="admin-task-detail admin-diagnostic-detail">
      <div className="admin-task-detail__hero">
        <div>
          <Typography.Title level={2}>{detail.type}</Typography.Title>
          <Tag color={toneColors[detail.statusTone]}>{detail.status}</Tag>
          <Tag>{detail.stage}</Tag>
        </div>
      </div>

      <dl className="admin-task-facts">
        <div><dt>任务号</dt><dd>{detail.id}</dd></div>
        <div><dt>创建时间</dt><dd>{detail.createdAt}</dd></div>
        <div><dt>更新时间</dt><dd>{detail.updatedAt}</dd></div>
        <div><dt>结束时间</dt><dd>{value(detail.finishedAt)}</dd></div>
        <div><dt>报价积分</dt><dd>{points(detail.quotedPoints)}</dd></div>
        <div><dt>结算积分</dt><dd>{points(detail.settledPoints)}</dd></div>
        <div><dt>Trace ID</dt><dd><code>{value(detail.traceId)}</code>{detail.traceId ? <Button type="text" icon={<CopyOutlined />} aria-label="复制 Trace ID" onClick={() => void navigator.clipboard?.writeText(detail.traceId as string)} /> : null}</dd></div>
      </dl>

      <Card title="诊断决策" variant="borderless">
        <dl className="admin-task-facts">
          <div><dt>失败分类</dt><dd>{detail.failureCategory}</dd></div>
          <div><dt>稳定错误码</dt><dd>{value(detail.stableErrorCode)}</dd></div>
          <div><dt>重试语义</dt><dd>{detail.retryability}</dd></div>
          <div><dt>可重试</dt><dd>{detail.retryable ? '是' : '否'}</dd></div>
          <div><dt>等待外部恢复</dt><dd>{detail.waitingExternalRecovery ? '是' : '否'}</dd></div>
        </dl>
      </Card>

      <Card title="状态时间线" variant="borderless">
        <SimpleTable label="状态时间线" headings={['事件', '状态变化', '来源', '错误码', '发生时间']} rows={detail.timeline.map((item) => [item.eventType, `${value(item.fromStatus)} → ${value(item.toStatus)}`, item.source, item.stableErrorCode, item.occurredAt])} />
      </Card>
      <Card title="执行尝试" variant="borderless">
        <SimpleTable label="执行尝试" headings={['序号', '状态', '阶段', 'Provider', '模型', '提交状态', '耗时(ms)', '错误码']} rows={detail.executions.map((item) => [item.executionNo, item.status, item.stage, item.provider, item.model, item.submissionState, item.latencyMs, item.stableErrorCode])} />
      </Card>
      <div className="admin-task-detail__grid">
        <Card title="消息出站" variant="borderless"><SimpleTable label="消息出站" headings={['执行尝试', '状态', '次数', '错误码', '可用时间', '发布时间']} rows={detail.outbox.map((item) => [item.executionAttempt, item.status, item.attempts, item.stableErrorCode, item.availableAt, item.publishedAt])} /></Card>
        <Card title="消息入站" variant="borderless"><SimpleTable label="消息入站" headings={['处置', '创建时间']} rows={detail.inbox.map((item) => [item.disposition, item.createdAt])} /></Card>
      </div>

      <Card title="积分与结算" variant="borderless">
        {detail.billing ? <>
          <dl className="admin-task-facts">
            <div><dt>预留状态</dt><dd>{detail.billing.reservationStatus}</dd></div>
            <div><dt>报价积分</dt><dd>{points(detail.billing.quotedPoints)}</dd></div>
            <div><dt>冻结积分</dt><dd>{points(detail.billing.frozenPoints)}</dd></div>
            <div><dt>结算积分</dt><dd>{points(detail.billing.settledPoints)}</dd></div>
            <div><dt>释放积分</dt><dd>{points(detail.billing.releasedPoints)}</dd></div>
          </dl>
          <SimpleTable label="积分账本摘要" headings={['类型', '可用积分变化', '冻结积分变化', '创建时间']} rows={detail.billing.ledger.map((item) => [item.entryType, item.availableDelta, item.frozenDelta, item.createdAt])} />
        </> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无积分结算诊断" />}
      </Card>

      <Card title="资产入库" variant="borderless">
        <SimpleTable label="资产入库" headings={['状态', '尝试', '上限', 'Provider', '模型', '错误码', '更新时间']} rows={detail.assetIngestion.map((item) => [item.status, item.attempt, item.maxAttempts, item.provider, item.model, item.stableErrorCode, item.updatedAt])} />
      </Card>

      <div className="admin-task-detail__grid">
        <Card title="GEO 摘要" variant="borderless">
          {detail.geo ? <dl className="admin-task-facts"><div><dt>Run ID</dt><dd>{detail.geo.runId}</dd></div><div><dt>状态</dt><dd>{detail.geo.status}</dd></div><div><dt>预期/成功/失败/缺失</dt><dd>{detail.geo.expectedUnits} / {detail.geo.succeededUnits} / {detail.geo.failedUnits} / {detail.geo.missingUnits}</dd></div></dl> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无 GEO 诊断摘要" />}
        </Card>
        <Card title="文章摘要" variant="borderless">
          {detail.article ? <dl className="admin-task-facts"><div><dt>文章 ID</dt><dd>{detail.article.articleId}</dd></div><div><dt>状态</dt><dd>{detail.article.status}</dd></div><div><dt>版本</dt><dd>{detail.article.version}</dd></div></dl> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无文章诊断摘要" />}
        </Card>
      </div>
      <Card title="媒体投稿摘要" variant="borderless">
        {detail.media ? <><dl className="admin-task-facts"><div><dt>状态</dt><dd>{detail.media.status}</dd></div><div><dt>报价/结算/释放</dt><dd>{points(detail.media.quotedPoints)} / {points(detail.media.settledPoints)} / {points(detail.media.releasedPoints)}</dd></div></dl><SimpleTable label="媒体投稿" headings={['状态', '提交状态', 'Provider', '模型', '错误码', '更新时间']} rows={detail.media.submissions.map((item) => [item.status, item.submissionState, item.provider, item.model, item.stableErrorCode, item.updatedAt])} /></> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无媒体投稿诊断摘要" />}
      </Card>
      <Card title="数据缺口" variant="borderless">
        {detail.dataGaps.length ? <Space wrap>{detail.dataGaps.map((gap) => <Tag color="warning" key={gap}>{gap}</Tag>)}</Space> : <Typography.Text type="secondary">未发现契约声明的数据缺口</Typography.Text>}
      </Card>
      <Alert type="info" showIcon title="该页面只读，仅展示 Java Browser API 返回的诊断白名单字段。" />
    </div>
  )
}

export interface AdminTaskDiagnosticsWorkspaceProps {
  tasks: readonly AdminTaskDiagnosticSummaryView[]
  total: number
  page: number
  pageSize: number
  loading?: boolean
  error?: AdminTaskDiagnosticErrorView | null
  filters: AdminTaskDiagnosticFiltersView
  selectedTaskId?: string
  detail?: AdminTaskDiagnosticDetailView
  detailLoading?: boolean
  detailError?: AdminTaskDiagnosticErrorView | null
  embedded?: boolean
  onFiltersChange: (filters: AdminTaskDiagnosticFiltersView) => void
  onPageChange: (page: number) => void
  onOpenTask: (taskId: string) => void
  onCloseTask: () => void
  onRetryList?: () => void
  onRetryDetail?: () => void
}

export function AdminTaskDiagnosticsWorkspace({
  tasks, total, page, pageSize, loading = false, error, filters,
  selectedTaskId, detail, detailLoading = false, detailError,
  embedded = false,
  onFiltersChange, onPageChange, onOpenTask, onCloseTask, onRetryList, onRetryDetail,
}: AdminTaskDiagnosticsWorkspaceProps) {
  const [draft, setDraft] = useState(filters)
  const attentionCount = tasks.filter((task) => task.statusTone === 'danger' || task.statusTone === 'warning').length

  return (
    <section
      className="admin-workspace"
      aria-labelledby={embedded ? undefined : 'admin-task-diagnostics-title'}
      aria-label={embedded ? '付费任务诊断' : undefined}
    >
      {!embedded ? <header className="admin-workspace__heading">
        <div><Typography.Title id="admin-task-diagnostics-title" level={1}>任务诊断</Typography.Title><Typography.Text type="secondary">查询只读任务生命周期、消息、执行、积分与资产入库诊断</Typography.Text></div>
        <Tag icon={<SafetyCertificateOutlined />} color="red">管理员只读</Tag>
      </header> : null}
      <div className="admin-stat-grid" aria-label="任务诊断概览">
        <Card variant="borderless"><DatabaseOutlined /><span>当前页任务</span><strong>{tasks.length}</strong></Card>
        <Card variant="borderless"><ClockCircleOutlined /><span>执行中</span><strong>{tasks.filter((task) => task.status === 'RUNNING').length}</strong></Card>
        <Card variant="borderless"><SafetyCertificateOutlined /><span>需关注</span><strong>{attentionCount}</strong></Card>
      </div>
      <Card className="admin-data-card" variant="borderless">
        <form className="admin-filter-bar admin-filter-bar--diagnostics" onSubmit={(event) => { event.preventDefault(); onFiltersChange(draft) }}>
          <Input prefix={<SearchOutlined />} aria-label="任务号" placeholder="任务号" value={draft.taskId ?? ''} onChange={(event) => setDraft({ ...draft, taskId: event.target.value || undefined })} />
          <Select allowClear aria-label="任务类型" placeholder="全部类型" value={draft.taskType} options={taskTypes.map((item) => ({ value: item, label: item }))} onChange={(taskType) => setDraft({ ...draft, taskType })} />
          <Select allowClear aria-label="任务状态" placeholder="全部状态" value={draft.taskStatus} options={taskStatuses.map((item) => ({ value: item, label: item }))} onChange={(taskStatus) => setDraft({ ...draft, taskStatus })} />
          <Input aria-label="稳定错误码" placeholder="稳定错误码" value={draft.stableErrorCode ?? ''} onChange={(event) => setDraft({ ...draft, stableErrorCode: event.target.value || undefined })} />
          <Input aria-label="Trace ID" placeholder="32位 traceId" value={draft.traceId ?? ''} onChange={(event) => setDraft({ ...draft, traceId: event.target.value || undefined })} />
          <Input type="datetime-local" aria-label="开始时间" value={draft.from ?? ''} onChange={(event) => setDraft({ ...draft, from: event.target.value || undefined })} />
          <Input type="datetime-local" aria-label="结束时间" value={draft.to ?? ''} onChange={(event) => setDraft({ ...draft, to: event.target.value || undefined })} />
          <Button type="primary" htmlType="submit">查询</Button>
          <Button onClick={() => { setDraft({}); onFiltersChange({}) }}>重置</Button>
        </form>
      </Card>
      <ErrorNotice error={error} />
      <Card className="admin-data-card" variant="borderless">
        {loading ? <Skeleton active paragraph={{ rows: 6 }} /> : tasks.length === 0 ? <Empty image={<DatabaseOutlined />} description="暂无任务诊断数据" /> : <div className="admin-table-scroll"><table className="admin-data-table admin-task-table" aria-label="任务诊断列表"><thead><tr>{['任务号', '类型', '状态', '阶段', '失败分类', '稳定错误码', 'Trace ID', '更新时间', '操作'].map((heading) => <th key={heading}>{heading}</th>)}</tr></thead><tbody>{tasks.map((task) => <tr key={task.id}><td><strong>{task.id}</strong></td><td>{task.type}</td><td><Tag color={toneColors[task.statusTone]}>{task.status}</Tag></td><td>{task.stage}</td><td>{task.failureCategory}</td><td>{value(task.stableErrorCode)}</td><td><code>{value(task.traceId)}</code></td><td>{task.updatedAt}</td><td><Button type="link" onClick={() => onOpenTask(task.id)}>诊断详情</Button></td></tr>)}</tbody></table></div>}
        {!loading && !error && total > 0 ? <div className="admin-observability-footer"><span>共 {total} 条</span><Pagination current={page} pageSize={pageSize} total={total} showSizeChanger={false} onChange={onPageChange} /></div> : null}
        {error && onRetryList ? <Button onClick={onRetryList}>重新加载</Button> : null}
      </Card>
      <Drawer className="admin-task-drawer" title={selectedTaskId ? `任务诊断 · ${selectedTaskId}` : '任务诊断'} size="min(1080px, 94vw)" open={Boolean(selectedTaskId)} onClose={onCloseTask}>
        {detailLoading ? <Skeleton active paragraph={{ rows: 12 }} /> : detailError ? <><ErrorNotice error={detailError} />{onRetryDetail ? <Button onClick={onRetryDetail}>重新加载详情</Button> : null}</> : detail ? <Detail detail={detail} /> : null}
      </Drawer>
    </section>
  )
}

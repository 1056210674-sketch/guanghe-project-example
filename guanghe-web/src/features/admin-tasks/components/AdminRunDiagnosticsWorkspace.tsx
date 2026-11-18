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
  AdminRunDiagnosticDetailView,
  AdminRunDiagnosticFiltersView,
  AdminRunDiagnosticSummaryView,
  AdminRunDiagnosticStatus,
  AdminTaskDiagnosticErrorView,
  AdminTaskStatusTone,
} from '../types'

const runStatuses: readonly AdminRunDiagnosticStatus[] = [
  'QUEUED',
  'STARTING',
  'RUNNING',
  'COMPLETED',
  'FAILED',
  'STOP_REQUESTED',
  'STOPPED',
]

const toneColors: Record<AdminTaskStatusTone, string> = {
  neutral: 'default',
  info: 'processing',
  success: 'success',
  warning: 'warning',
  danger: 'error',
}

const dataGapCopy = {
  TRACE_ID_NOT_PERSISTED: '历史 Run 未持久化 Trace ID，无法关联完整链路。',
  USAGE_NOT_REPORTED: '该 Run 未上报完整 Usage，Token、调用次数或延迟可能缺失。',
} as const

function value(valueToFormat: string | number | null | undefined): string {
  if (valueToFormat === null || valueToFormat === undefined || valueToFormat === '') {
    return '—'
  }
  return String(valueToFormat)
}

function count(valueToFormat: number | undefined): string {
  return valueToFormat !== undefined &&
    Number.isSafeInteger(valueToFormat) &&
    valueToFormat >= 0
    ? valueToFormat.toLocaleString('zh-CN')
    : '—'
}

function yesNo(valueToFormat: boolean | undefined): string {
  if (valueToFormat === undefined) return '—'
  return valueToFormat ? '是' : '否'
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

function RunDetail({
  detail,
  onOpenLinkedTask,
}: {
  detail: AdminRunDiagnosticDetailView
  onOpenLinkedTask?: (taskId: string) => void
}) {
  return (
    <div className="admin-task-detail admin-diagnostic-detail">
      <div className="admin-task-detail__hero">
        <div>
          <Typography.Title level={2}>AI 对话 Run</Typography.Title>
          <Tag color={toneColors[detail.statusTone]}>{detail.status}</Tag>
        </div>
      </div>

      <dl className="admin-task-facts">
        <div><dt>Run ID</dt><dd>{detail.id}</dd></div>
        <div><dt>Conversation ID</dt><dd>{detail.conversationId}</dd></div>
        <div><dt>最后 Python 序号</dt><dd>{count(detail.lastPythonSequence)}</dd></div>
        <div><dt>创建时间</dt><dd>{detail.createdAt}</dd></div>
        <div><dt>开始时间</dt><dd>{value(detail.startedAt)}</dd></div>
        <div><dt>完成时间</dt><dd>{value(detail.completedAt)}</dd></div>
        <div><dt>停止请求时间</dt><dd>{value(detail.stopRequestedAt)}</dd></div>
        <div>
          <dt>Trace ID</dt>
          <dd>
            <code>{value(detail.traceId)}</code>
            {detail.traceId ? (
              <Button
                type="text"
                icon={<CopyOutlined />}
                aria-label="复制 Run Trace ID"
                onClick={() => void navigator.clipboard?.writeText(detail.traceId as string)}
              />
            ) : null}
          </dd>
        </div>
        <div>
          <dt>关联任务</dt>
          <dd>
            {detail.linkedTaskId ? (
              <Button type="link" onClick={() => onOpenLinkedTask?.(detail.linkedTaskId as string)}>
                {detail.linkedTaskId}
              </Button>
            ) : '—'}
          </dd>
        </div>
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

      <Card title="Usage 与调用摘要" variant="borderless">
        <dl className="admin-task-facts">
          <div><dt>Workflow 步骤</dt><dd>{count(detail.usage.workflowStepCount)}</dd></div>
          <div><dt>模型调用</dt><dd>{count(detail.usage.modelCallCount)}</dd></div>
          <div><dt>Context 加载</dt><dd>{count(detail.usage.contextLoadCount)}</dd></div>
          <div><dt>工具调用</dt><dd>{count(detail.usage.toolCallCount)}</dd></div>
          <div><dt>Provider 调用</dt><dd>{count(detail.usage.providerCallCount)}</dd></div>
          <div><dt>估算输入 Token</dt><dd>{count(detail.usage.estimatedInputTokens)}</dd></div>
          <div><dt>Provider 输入 Token</dt><dd>{count(detail.usage.providerInputTokens)}</dd></div>
          <div><dt>Provider 输出 Token</dt><dd>{count(detail.usage.providerOutputTokens)}</dd></div>
          <div><dt>Provider 延迟(ms)</dt><dd>{count(detail.usage.providerLatencyMs)}</dd></div>
          <div><dt>Provider</dt><dd>{value(detail.usage.provider)}</dd></div>
          <div><dt>模型</dt><dd>{value(detail.usage.model)}</dd></div>
        </dl>
      </Card>

      <Card title="Context 预算" variant="borderless">
        <dl className="admin-task-facts">
          <div><dt>目标输入 Token</dt><dd>{count(detail.contextBudget.targetInputTokens)}</dd></div>
          <div><dt>预留输出 Token</dt><dd>{count(detail.contextBudget.reservedOutputTokens)}</dd></div>
          <div><dt>预留工具 Token</dt><dd>{count(detail.contextBudget.reservedToolTokens)}</dd></div>
          <div><dt>预留安全 Token</dt><dd>{count(detail.contextBudget.reservedSafetyTokens)}</dd></div>
          <div><dt>可用历史 Token</dt><dd>{count(detail.contextBudget.availableHistoryTokens)}</dd></div>
          <div><dt>历史估算 Token</dt><dd>{count(detail.contextBudget.estimatedHistoryTokens)}</dd></div>
          <div><dt>历史已截断</dt><dd>{yesNo(detail.contextBudget.truncated)}</dd></div>
          <div><dt>Context 来源</dt><dd>{value(detail.contextBudget.contextSource)}</dd></div>
          <div><dt>缓存命中</dt><dd>{yesNo(detail.contextBudget.cacheHit)}</dd></div>
        </dl>
      </Card>

      <Card title="安全时间线" variant="borderless">
        {detail.timeline.length ? (
          <div className="admin-table-scroll">
            <table className="admin-data-table admin-diagnostic-small-table" aria-label="Run 安全时间线">
              <thead><tr><th>序号</th><th>事件</th><th>稳定错误码</th><th>发生时间</th></tr></thead>
              <tbody>
                {detail.timeline.map((item) => (
                  <tr key={item.id}>
                    <td>{item.sequence}</td>
                    <td>{item.eventType}</td>
                    <td>{value(item.stableErrorCode)}</td>
                    <td>{item.occurredAt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无 Run 时间线" />
        )}
      </Card>

      <Card title="数据缺口" variant="borderless">
        {detail.dataGaps.length ? (
          <Space orientation="vertical" size={8}>
            {detail.dataGaps.map((gap) => (
              <Alert key={gap} type="warning" showIcon title={gap} description={dataGapCopy[gap]} />
            ))}
          </Space>
        ) : (
          <Typography.Text type="secondary">未发现契约声明的数据缺口</Typography.Text>
        )}
      </Card>

      <Alert
        type="info"
        showIcon
        title="该页面只读，不展示消息正文、Prompt、Context 快照、工具参数、Provider 原始响应或异常正文。"
      />
    </div>
  )
}

export interface AdminRunDiagnosticsWorkspaceProps {
  runs: readonly AdminRunDiagnosticSummaryView[]
  total: number
  page: number
  pageSize: number
  loading?: boolean
  error?: AdminTaskDiagnosticErrorView | null
  filters: AdminRunDiagnosticFiltersView
  selectedRunId?: string
  detail?: AdminRunDiagnosticDetailView
  detailLoading?: boolean
  detailError?: AdminTaskDiagnosticErrorView | null
  embedded?: boolean
  onFiltersChange: (filters: AdminRunDiagnosticFiltersView) => void
  onPageChange: (page: number) => void
  onOpenRun: (runId: string) => void
  onCloseRun: () => void
  onOpenLinkedTask?: (taskId: string) => void
  onRetryList?: () => void
  onRetryDetail?: () => void
}

export function AdminRunDiagnosticsWorkspace({
  runs,
  total,
  page,
  pageSize,
  loading = false,
  error,
  filters,
  selectedRunId,
  detail,
  detailLoading = false,
  detailError,
  embedded = false,
  onFiltersChange,
  onPageChange,
  onOpenRun,
  onCloseRun,
  onOpenLinkedTask,
  onRetryList,
  onRetryDetail,
}: AdminRunDiagnosticsWorkspaceProps) {
  const [draft, setDraft] = useState(filters)
  const attentionCount = runs.filter(
    (run) => run.statusTone === 'danger' || run.statusTone === 'warning',
  ).length

  return (
    <section
      className="admin-workspace"
      aria-labelledby={embedded ? undefined : 'admin-run-diagnostics-title'}
      aria-label={embedded ? 'AI 对话 Run 诊断' : undefined}
    >
      {!embedded ? (
        <header className="admin-workspace__heading">
          <div>
            <Typography.Title id="admin-run-diagnostics-title" level={1}>AI 对话 Run 诊断</Typography.Title>
            <Typography.Text type="secondary">查询 Run 阶段、Usage、Context 预算和安全事件</Typography.Text>
          </div>
          <Tag icon={<SafetyCertificateOutlined />} color="red">管理员只读</Tag>
        </header>
      ) : null}
      <div className="admin-stat-grid" aria-label="Run 诊断概览">
        <Card variant="borderless"><DatabaseOutlined /><span>当前页 Run</span><strong>{runs.length}</strong></Card>
        <Card variant="borderless"><ClockCircleOutlined /><span>执行中</span><strong>{runs.filter((run) => run.status === 'RUNNING' || run.status === 'STARTING').length}</strong></Card>
        <Card variant="borderless"><SafetyCertificateOutlined /><span>需关注</span><strong>{attentionCount}</strong></Card>
      </div>
      <Card className="admin-data-card" variant="borderless">
        <form
          className="admin-filter-bar admin-filter-bar--diagnostics admin-filter-bar--run-diagnostics"
          onSubmit={(event) => { event.preventDefault(); onFiltersChange(draft) }}
        >
          <Input prefix={<SearchOutlined />} aria-label="Run ID" placeholder="Run ID" value={draft.runId ?? ''} onChange={(event) => setDraft({ ...draft, runId: event.target.value || undefined })} />
          <Input aria-label="Conversation ID" placeholder="Conversation ID" value={draft.conversationId ?? ''} onChange={(event) => setDraft({ ...draft, conversationId: event.target.value || undefined })} />
          <Select allowClear aria-label="Run 状态" placeholder="全部状态" value={draft.status} options={runStatuses.map((item) => ({ value: item, label: item }))} onChange={(status) => setDraft({ ...draft, status })} />
          <Input aria-label="Run 稳定错误码" placeholder="稳定错误码" value={draft.stableErrorCode ?? ''} onChange={(event) => setDraft({ ...draft, stableErrorCode: event.target.value || undefined })} />
          <Input aria-label="Run Trace ID" placeholder="32位 traceId" value={draft.traceId ?? ''} onChange={(event) => setDraft({ ...draft, traceId: event.target.value || undefined })} />
          <Input type="datetime-local" aria-label="Run 开始时间" value={draft.from ?? ''} onChange={(event) => setDraft({ ...draft, from: event.target.value || undefined })} />
          <Input type="datetime-local" aria-label="Run 结束时间" value={draft.to ?? ''} onChange={(event) => setDraft({ ...draft, to: event.target.value || undefined })} />
          <Button type="primary" htmlType="submit">查询</Button>
          <Button onClick={() => { setDraft({}); onFiltersChange({}) }}>重置</Button>
        </form>
      </Card>
      <ErrorNotice error={error} />
      <Card className="admin-data-card" variant="borderless">
        {loading ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : runs.length === 0 ? (
          <Empty image={<DatabaseOutlined />} description="暂无 AI 对话 Run 诊断数据" />
        ) : (
          <div className="admin-table-scroll">
            <table className="admin-data-table admin-task-table" aria-label="Run 诊断列表">
              <thead><tr>{['Run ID', 'Conversation ID', '状态', '失败分类', '稳定错误码', 'Trace ID', '更新时间', '操作'].map((heading) => <th key={heading}>{heading}</th>)}</tr></thead>
              <tbody>
                {runs.map((run) => (
                  <tr key={run.id}>
                    <td><strong>{run.id}</strong></td>
                    <td>{run.conversationId}</td>
                    <td><Tag color={toneColors[run.statusTone]}>{run.status}</Tag></td>
                    <td>{run.failureCategory}</td>
                    <td>{value(run.stableErrorCode)}</td>
                    <td><code>{value(run.traceId)}</code></td>
                    <td>{run.updatedAt}</td>
                    <td><Button type="link" onClick={() => onOpenRun(run.id)}>诊断详情</Button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {!loading && !error && total > 0 ? (
          <div className="admin-observability-footer">
            <span>共 {total} 条</span>
            <Pagination current={page} pageSize={pageSize} total={total} showSizeChanger={false} onChange={onPageChange} />
          </div>
        ) : null}
        {error && onRetryList ? <Button onClick={onRetryList}>重新加载</Button> : null}
      </Card>
      <Drawer
        className="admin-task-drawer"
        title={selectedRunId ? `Run 诊断 · ${selectedRunId}` : 'Run 诊断'}
        size="min(1080px, 94vw)"
        open={Boolean(selectedRunId)}
        onClose={onCloseRun}
      >
        {detailLoading ? (
          <Skeleton active paragraph={{ rows: 12 }} />
        ) : detailError ? (
          <><ErrorNotice error={detailError} />{onRetryDetail ? <Button onClick={onRetryDetail}>重新加载详情</Button> : null}</>
        ) : detail ? (
          <RunDetail detail={detail} onOpenLinkedTask={onOpenLinkedTask} />
        ) : null}
      </Drawer>
    </section>
  )
}

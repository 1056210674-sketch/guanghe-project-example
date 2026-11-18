import { useMemo } from 'react'
import { Alert, Button, Card, Empty, Pagination, Skeleton, Tag, Typography } from 'antd'
import type {
  AdminAgentEvaluationPage,
  AdminAgentRuntimeSnapshot,
  AdminAgentVersionDetail,
  AdminAgentVersionRun,
  AdminAgentVersionSummary,
} from '../../api/adminAgentVersionsApi'
import { ErrorState } from '../../components/GlobalState'
import { formatDateTime, type ApiErrorView } from '../../shared/format'
import { Metadata, ReadOnlyDetail } from './ReadOnlyDetail'

const VERSION_STATUS_META = {
  CURRENT: { label: '当前运行', color: 'success' },
  OBSERVED: { label: '历史观测', color: 'default' },
} as const

const RUN_SOURCE_LABEL = {
  TASK: '任务执行',
  WORKFLOW: '工作流执行',
} as const

const DATA_GAP_LABEL: Readonly<Record<string, string>> = {
  EVALUATIONS_NOT_REGISTERED: '该版本没有已登记的评测证据',
  HISTORICAL_RUNTIME_METADATA_NOT_PERSISTED: '历史运行时清单未持久化',
  CURRENT_RUNTIME_UNAVAILABLE: '当前运行时能力不可用',
}

function value(input: string | null | undefined): string {
  return input?.trim() || '—'
}

function count(input: number | null | undefined): string {
  return typeof input === 'number' && Number.isSafeInteger(input) && input >= 0
    ? input.toLocaleString('zh-CN')
    : '—'
}

function duration(input: number | null | undefined): string {
  if (typeof input !== 'number' || !Number.isSafeInteger(input) || input < 0) return '—'
  if (input < 1000) return `${input} ms`
  return `${(input / 1000).toFixed(3)} s`
}

export interface AgentVersionListProps {
  runtime?: AdminAgentRuntimeSnapshot
  items: readonly AdminAgentVersionSummary[]
  page: number
  pageSize: number
  total: number
  loading: boolean
  error: ApiErrorView | null
  onRetry?: () => void
  onPageChange?: (page: number) => void
}

export interface AgentVersionRunsProps {
  items: readonly AdminAgentVersionRun[]
  page: number
  pageSize: number
  total: number
  loading: boolean
  error: ApiErrorView | null
  onRetry?: () => void
  onPageChange?: (page: number) => void
}

export interface AgentVersionEvaluationsProps {
  data?: AdminAgentEvaluationPage
  loading: boolean
  error: ApiErrorView | null
  onRetry?: () => void
}

export interface AgentVersionDetailProps {
  summary: AdminAgentVersionSummary
  data?: AdminAgentVersionDetail
  loading: boolean
  error: ApiErrorView | null
  onRetry?: () => void
  runs: AgentVersionRunsProps
  evaluations: AgentVersionEvaluationsProps
}

export interface AdminAgentVersionsWorkspaceProps {
  versions: AgentVersionListProps
  detail?: AgentVersionDetailProps | null
  onRefresh?: () => void
  onOpenVersion?: (bundleVersion: string) => void
  onCloseDetail?: () => void
  preview?: boolean
}

function runtimeAvailabilityCopy(runtime?: AdminAgentRuntimeSnapshot) {
  if (runtime?.availability === 'AVAILABLE') {
    return {
      title: '当前运行版本标识不完整',
      description: '运行时能力可用，但未返回 Bundle 版本；页面不会从历史记录、列表顺序或观测时间推断当前版本。',
    }
  }
  if (!runtime || runtime.availability === 'NOT_CONFIGURED') {
    return {
      title: '当前运行版本未配置',
      description: '运行时能力接口尚未配置，历史执行观测仍可查看；页面不会从历史记录推断当前版本。',
    }
  }
  return {
    title: '当前运行版本暂不可确认',
    description: `运行时能力暂不可用（${value(runtime.reasonCode)}），历史执行观测仍可查看；页面不会从最近执行记录推断当前版本。`,
  }
}

function CurrentRuntimeCard({
  runtime,
  version,
}: {
  runtime?: AdminAgentRuntimeSnapshot
  version?: AdminAgentVersionSummary
}) {
  if (!runtime || runtime.availability !== 'AVAILABLE' || !runtime.bundleVersion) {
    const copy = runtimeAvailabilityCopy(runtime)
    return <Alert showIcon type="warning" title={copy.title} description={copy.description} />
  }

  return (
    <Card aria-label="当前运行版本">
      <dl className="admin-agent-current">
        {[
          ['当前运行 Bundle', runtime.bundleVersion],
          ['服务版本', runtime.serviceVersion],
          ['Bundle 哈希', runtime.bundleHash],
          ['Prompt 哈希', runtime.promptHash],
          ['Git Commit', runtime.gitCommit],
          ['已观测执行数', version ? count(version.runCount) : '当前页未载入'],
        ].map(([label, displayed]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value(displayed)}</dd>
          </div>
        ))}
      </dl>
    </Card>
  )
}

function VersionList({
  versions,
  onOpenVersion,
}: {
  versions: AgentVersionListProps
  onOpenVersion?: (bundleVersion: string) => void
}) {
  if (versions.loading) return <Skeleton active paragraph={{ rows: 8 }} />
  if (versions.error) {
    return <ErrorState message={versions.error.message} traceId={versions.error.traceId} onRetry={versions.onRetry} />
  }
  if (versions.items.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无 Agent 版本观测记录" />
  }

  return (
    <>
      <div className="admin-table-scroll">
        <table className="admin-data-table admin-observability-records" aria-label="Agent 版本列表">
          <thead>
            <tr>
              {['Bundle 版本', '证据状态', '首次观测', '最近观测', '关联执行数', '详情'].map(
                (label) => <th key={label}>{label}</th>,
              )}
            </tr>
          </thead>
          <tbody>
            {versions.items.map((version) => {
              const meta = VERSION_STATUS_META[version.status]
              return (
                <tr key={version.bundleVersion}>
                  <td><strong>{version.bundleVersion}</strong></td>
                  <td><Tag color={meta.color}>{meta.label}</Tag></td>
                  <td>{formatDateTime(version.firstObservedAt)}</td>
                  <td>{formatDateTime(version.lastObservedAt)}</td>
                  <td>{count(version.runCount)}</td>
                  <td>
                    <Button type="link" onClick={() => onOpenVersion?.(version.bundleVersion)} disabled={!onOpenVersion}>
                      查看版本
                    </Button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <div className="admin-observability-footer">
        <span>共 {count(versions.total)} 个版本证据</span>
        <Pagination
          size="small"
          current={versions.page}
          pageSize={versions.pageSize}
          total={versions.total}
          showSizeChanger={false}
          hideOnSinglePage
          onChange={(page) => versions.onPageChange?.(page)}
        />
      </div>
    </>
  )
}

function RuntimeEvidence({ runtime, bundleVersion }: { runtime: AdminAgentRuntimeSnapshot; bundleVersion: string }) {
  const gateway = runtime.modelGateway
  return (
    <>
      <section>
        <h3>当前运行时证据</h3>
        <Metadata rows={[
          ['Bundle 版本', value(runtime.bundleVersion)],
          ['服务版本', value(runtime.serviceVersion)],
          ['Bundle 哈希', value(runtime.bundleHash)],
          ['Prompt 哈希', value(runtime.promptHash)],
          ['Git Commit', value(runtime.gitCommit)],
          ['模型网关模式', value(gateway?.mode)],
          ['模型网关', gateway ? `${value(gateway.provider)} / ${value(gateway.model)}` : '—'],
          ['网关状态', value(gateway?.status)],
          ['网关原因码', value(gateway?.reasonCode)],
        ]} />
        <p className="admin-observability-muted">这些字段来自当前运行服务的能力证据，仅适用于 Bundle {bundleVersion}。</p>
      </section>
      <section>
        <h3>Agent Profiles</h3>
        {runtime.profiles.length ? (
          <table className="admin-observability-small-table" aria-label="Agent Profile 列表">
            <thead><tr><th>Profile</th><th>版本</th><th>任务类型</th></tr></thead>
            <tbody>
              {runtime.profiles.map((profile) => (
                <tr key={`${profile.profileCode}-${profile.profileVersion}`}>
                  <td>{profile.profileCode}</td><td>{profile.profileVersion}</td><td>{profile.taskTypes.join('、') || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前运行时未返回 Profile" />}
      </section>
      <section>
        <h3>Provider 状态</h3>
        {runtime.providers.length ? (
          <table className="admin-observability-small-table" aria-label="Provider 状态列表">
            <thead><tr><th>Provider</th><th>模型</th><th>状态</th></tr></thead>
            <tbody>
              {runtime.providers.map((provider) => (
                <tr key={provider.code}><td>{provider.code}</td><td>{value(provider.model)}</td><td>{provider.status}</td></tr>
              ))}
            </tbody>
          </table>
        ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前运行时未返回 Provider" />}
      </section>
    </>
  )
}

function EvaluationEvidence({ evaluations }: { evaluations: AgentVersionEvaluationsProps }) {
  if (evaluations.loading) return <Skeleton active paragraph={{ rows: 2 }} />
  if (evaluations.error) {
    return <ErrorState message={evaluations.error.message} traceId={evaluations.error.traceId} onRetry={evaluations.onRetry} />
  }
  return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无与该版本绑定的已登记评测证据" />
}

function Runs({ runs }: { runs: AgentVersionRunsProps }) {
  if (runs.loading) return <Skeleton active paragraph={{ rows: 5 }} />
  if (runs.error) {
    return <ErrorState message={runs.error.message} traceId={runs.error.traceId} onRetry={runs.onRetry} />
  }
  if (runs.items.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无关联任务或工作流执行" />
  }

  return (
    <>
      <div className="admin-observability-detail-scroll">
        <table className="admin-observability-small-table" aria-label="关联执行列表">
          <thead><tr><th>来源</th><th>执行 ID</th><th>状态</th><th>开始 / 结束</th><th>耗时</th></tr></thead>
          <tbody>
            {runs.items.map((run) => (
              <tr key={`${run.source}-${run.executionId}`}>
                <td>{RUN_SOURCE_LABEL[run.source]}</td>
                <td>{run.executionId}<br /><span className="admin-observability-muted">关联 {run.parentId}</span></td>
                <td>{value(run.status)}<br /><span className="admin-observability-muted">{value(run.stableErrorCode)}</span></td>
                <td>{formatDateTime(run.startedAt)}<br />{formatDateTime(run.finishedAt)}</td>
                <td>{duration(run.durationMs)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="admin-observability-footer">
        <span>共 {count(runs.total)} 条关联执行</span>
        <Pagination size="small" current={runs.page} pageSize={runs.pageSize} total={runs.total} showSizeChanger={false} hideOnSinglePage onChange={(page) => runs.onPageChange?.(page)} />
      </div>
    </>
  )
}

function VersionDetail({ detail, onClose }: { detail: AgentVersionDetailProps; onClose: () => void }) {
  const latest = detail.data?.version ?? detail.summary
  const runtime = detail.data?.currentRuntime
  const isCurrent = Boolean(latest.status === 'CURRENT' && runtime?.availability === 'AVAILABLE' && runtime.bundleVersion === latest.bundleVersion)

  return (
    <ReadOnlyDetail title="版本详情" onClose={onClose}>
      {detail.loading && !detail.data ? <Skeleton active paragraph={{ rows: 8 }} /> : detail.error ? (
        <ErrorState message={detail.error.message} traceId={detail.error.traceId} onRetry={detail.onRetry} />
      ) : (
        <>
          <section>
            <div className="admin-observability-actions">
              <h3>Bundle {latest.bundleVersion}</h3>
              <Tag color={VERSION_STATUS_META[latest.status].color}>{VERSION_STATUS_META[latest.status].label}</Tag>
            </div>
            <Metadata rows={[
              ['首次观测', formatDateTime(latest.firstObservedAt)],
              ['最近观测', formatDateTime(latest.lastObservedAt)],
              ['关联执行数', count(latest.runCount)],
            ]} />
          </section>

          {isCurrent && runtime ? <RuntimeEvidence runtime={runtime} bundleVersion={latest.bundleVersion} /> : (
            <Alert showIcon type="info" title="历史执行观测" description="该版本只有任务或工作流执行留下的观测证据，没有已保存的历史运行时清单；页面不会补写部署人、部署时间、文件清单或工具预算。" />
          )}

          <section>
            <h3>已知数据缺口</h3>
            {detail.data?.dataGaps.length ? (
              <ul>{detail.data.dataGaps.map((gap) => <li key={gap}>{DATA_GAP_LABEL[gap] ?? gap}</li>)}</ul>
            ) : <p className="admin-observability-muted">服务端未声明额外数据缺口。</p>}
          </section>
          <section><h3>已登记评测证据</h3><EvaluationEvidence evaluations={detail.evaluations} /></section>
          <section><h3>关联任务与工作流执行</h3><Runs runs={detail.runs} /></section>
        </>
      )}
    </ReadOnlyDetail>
  )
}

export function AdminAgentVersionsWorkspace({ versions, detail, onRefresh, onOpenVersion, onCloseDetail, preview = false }: AdminAgentVersionsWorkspaceProps) {
  // Runtime capability is the authority for the live Bundle. A matching summary is optional and is
  // used only for its observed run count; pagination, status, array order and time never determine
  // the runtime identity.
  const currentObservation = useMemo(() => {
    const runtime = versions.runtime
    if (runtime?.availability !== 'AVAILABLE' || !runtime.bundleVersion) return undefined
    return versions.items.find((item) => item.bundleVersion === runtime.bundleVersion)
  }, [versions.items, versions.runtime])

  return (
    <section className="admin-workspace admin-observability" aria-label="Agent 版本">
      <div className="admin-workspace__heading">
        <div>
          <Typography.Title level={1}>Prompt / Agent 版本</Typography.Title>
          <Typography.Text type="secondary">当前运行证据来自服务能力接口，历史版本只代表任务或工作流曾记录过该 Bundle。</Typography.Text>
        </div>
        <div className="admin-observability-actions"><Button onClick={onRefresh} disabled={!onRefresh || versions.loading}>刷新证据</Button></div>
      </div>
      <Alert showIcon type="info" title={preview ? '开发设计预览 · 数据仅用于界面验收' : '只读版本观测台'} description="本页面不提供发布、回滚、评测或部署动作，也不展示完整 Prompt 内容。" />
      {versions.loading ? <Card><Skeleton active paragraph={{ rows: 2 }} /></Card> : versions.error ? null : (
        <CurrentRuntimeCard runtime={versions.runtime} version={currentObservation} />
      )}
      <h2 className="admin-observability-section-title">版本证据</h2>
      <Card className="admin-data-card"><VersionList versions={versions} onOpenVersion={onOpenVersion} /></Card>
      {detail && onCloseDetail ? <VersionDetail detail={detail} onClose={onCloseDetail} /> : null}
    </section>
  )
}

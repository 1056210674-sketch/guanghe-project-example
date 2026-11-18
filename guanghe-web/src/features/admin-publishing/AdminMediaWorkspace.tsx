import {
  DatabaseOutlined,
  DollarOutlined,
  FileTextOutlined,
  SafetyCertificateOutlined,
  SyncOutlined,
  WarningOutlined,
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
  Tabs,
  Tag,
  Typography,
} from 'antd'
import { useState } from 'react'
import type {
  AdminMediaCallback,
  AdminMediaOverview,
  AdminMediaResource,
  AdminMediaSubmission,
  AdminMediaSubmissionStatus,
  AdminMediaSubmissionSummary,
  AdminMediaSyncState,
} from '../../api/adminMediaApi'
import { formatCents, formatDateTime, formatPoints } from '../../shared/format'
import type { ApiErrorView } from '../../shared/format'
import {
  MEDIA_CALLBACK_STATUS_META,
  MEDIA_RESOURCE_STATUS_META,
  MEDIA_SUBMISSION_FILTER_ALL,
  MEDIA_SUBMISSION_GROUP_META,
  MEDIA_SUBMISSION_STATE_META,
  MEDIA_SUBMISSION_STATUS_META,
  capabilityEntries,
  exactDecimal,
  formatUpstreamPrice,
  mediaSubmissionGroupFilterValue,
  mediaSubmissionStatusFilterValue,
  syncStatusMeta,
  type AdminMediaResourceFiltersView,
  type AdminMediaSubmissionFilterView,
  type AdminMediaSubmissionGroup,
} from './types'

export type AdminMediaTab = 'resources' | 'submissions' | 'callbacks' | 'sync'

/** What the operator opened. The container turns this into the by-id read. */
export interface AdminMediaDetailTarget {
  kind: 'resource' | 'submission' | 'callback'
  id: string
}

interface TableNav {
  page: number
  pageSize: number
  total: number
  loading: boolean
  error?: ApiErrorView | null
  onPageChange?: (page: number) => void
  onRetry?: () => void
}

export interface AdminMediaResourceTableProps extends TableNav {
  items: readonly AdminMediaResource[]
  filters: AdminMediaResourceFiltersView
  onFiltersChange?: (filters: AdminMediaResourceFiltersView) => void
}

export interface AdminMediaSubmissionTableProps extends TableNav {
  items: readonly AdminMediaSubmissionSummary[]
  filter: AdminMediaSubmissionFilterView
  onFilterChange?: (filter: AdminMediaSubmissionFilterView) => void
}

export interface AdminMediaCallbackTableProps extends TableNav {
  items: readonly AdminMediaCallback[]
}

export interface AdminMediaSyncStateTableProps {
  items: readonly AdminMediaSyncState[]
  loading: boolean
  error?: ApiErrorView | null
  onRetry?: () => void
}

export interface AdminMediaDetailProps {
  target: AdminMediaDetailTarget
  /** The row the operator clicked: shown immediately, behind the by-id read. */
  resource?: AdminMediaResource
  submission?: AdminMediaSubmissionSummary
  callback?: AdminMediaCallback
  /**
   * The full submission, which is the only shape carrying the event trail. Read separately from the
   * row the operator clicked, because a list row has no history on it.
   */
  submissionDetail?: AdminMediaSubmission
  loading: boolean
  error?: ApiErrorView | null
  onRetry?: () => void
}

export interface AdminMediaWorkspaceProps {
  preview?: boolean
  activeTab?: AdminMediaTab
  onTabChange?: (tab: AdminMediaTab) => void
  /** Counts. A count the server has not answered yet renders as — rather than as zero. */
  overview?: AdminMediaOverview
  overviewError?: ApiErrorView | null
  onRetryOverview?: () => void
  onRefresh?: () => void
  refreshing?: boolean
  resources?: AdminMediaResourceTableProps
  submissions?: AdminMediaSubmissionTableProps
  callbacks?: AdminMediaCallbackTableProps
  syncStates?: AdminMediaSyncStateTableProps
  detail?: AdminMediaDetailProps | null
  onOpenDetail?: (target: AdminMediaDetailTarget) => void
  onCloseDetail?: () => void
  /** Moves the operator to the submissions tab, already narrowed to the exceptions. */
  onShowExceptionSubmissions?: () => void
}

const SEARCH_MAX_LENGTH = 200
const PAGE_SIZE = 20

const emptyResourceTable: AdminMediaResourceTableProps = {
  items: [],
  filters: {},
  page: 1,
  pageSize: PAGE_SIZE,
  total: 0,
  loading: false,
}

const emptySubmissionTable: AdminMediaSubmissionTableProps = {
  items: [],
  filter: MEDIA_SUBMISSION_FILTER_ALL,
  page: 1,
  pageSize: PAGE_SIZE,
  total: 0,
  loading: false,
}

const emptyCallbackTable: AdminMediaCallbackTableProps = {
  items: [],
  page: 1,
  pageSize: PAGE_SIZE,
  total: 0,
  loading: false,
}

const emptySyncStates: AdminMediaSyncStateTableProps = { items: [], loading: false }

function ErrorNotice({ error, onRetry }: { error?: ApiErrorView | null; onRetry?: () => void }) {
  if (!error) return null
  return (
    <Alert
      showIcon
      type="error"
      title={error.message}
      description={error.traceId ? `traceId：${error.traceId}` : undefined}
      action={onRetry ? <Button onClick={onRetry}>重新加载</Button> : undefined}
    />
  )
}

/** A value the server did not send is shown as absent, never as a plausible substitute. */
function value(text: string | undefined | null): string {
  return text && text.trim().length > 0 ? text : '—'
}

function days(count: number | null | undefined): string {
  return count === null || count === undefined ? '—' : `${count} 天`
}

/**
 * Both ends of an event's transition read as the same eight labels the list does. The event DTO
 * types these as plain strings, so a value outside the eight is printed as itself — the same rule
 * the sync statuses follow — rather than indexed into and thrown on.
 */
const SUBMISSION_STATUS_LABELS: Record<string, string> = Object.fromEntries(
  Object.entries(MEDIA_SUBMISSION_STATUS_META).map(([status, meta]) => [status, meta.label]),
)

function submissionStatusLabel(status: string | null | undefined): string {
  if (!status) return '—'
  return SUBMISSION_STATUS_LABELS[status] ?? status
}

function TableFooter({
  page,
  pageSize,
  total,
  onPageChange,
}: {
  page: number
  pageSize: number
  total: number
  onPageChange?: (page: number) => void
}) {
  return (
    <div className="admin-publishing-footer">
      <span>共 {total} 条</span>
      <Pagination
        current={page}
        pageSize={pageSize}
        total={total}
        showSizeChanger={false}
        onChange={onPageChange}
        disabled={!onPageChange}
      />
    </div>
  )
}

/**
 * The operator's read-only view of media procurement.
 *
 * Everything is driven by props; the container owns the reads. Three things are deliberate:
 *
 * - **No write is offered.** Resources sync in the background; this page refreshes its local
 *   read model and does not expose manual sync, resource enablement or callback replay.
 * - **Money is never recomputed.** The purchase price is the exact decimal the server stores and the
 *   reference amounts are the server's integer cents; the browser formats them and does no
 *   arithmetic, so it cannot disagree with what was charged.
 * - **Only what the server holds is shown.** There is no upstream trace id, no sync batch, no
 *   operator name and no callback payload on this surface, because nothing records or exposes one.
 *   The panels say so rather than leaving the operator to wonder whether a field is missing.
 */
export function AdminMediaWorkspace({
  preview = false,
  activeTab: activeTabProp,
  onTabChange,
  overview,
  overviewError,
  onRetryOverview,
  onRefresh,
  refreshing = false,
  resources = emptyResourceTable,
  submissions = emptySubmissionTable,
  callbacks = emptyCallbackTable,
  syncStates = emptySyncStates,
  detail,
  onOpenDetail,
  onCloseDetail,
  onShowExceptionSubmissions,
}: AdminMediaWorkspaceProps) {
  const [internalTab, setInternalTab] = useState<AdminMediaTab>('resources')
  const activeTab = activeTabProp ?? internalTab

  const [resourceDraft, setResourceDraft] = useState<AdminMediaResourceFiltersView>(
    resources.filters,
  )
  const [appliedResourceFilters, setAppliedResourceFilters] = useState(() =>
    JSON.stringify(resources.filters),
  )
  const resourceFiltersKey = JSON.stringify(resources.filters)
  // Re-sync the form when the applied filters change underneath it — the exception shortcut moves
  // them — without resetting it on every render, since an equal object stringifies equally.
  if (appliedResourceFilters !== resourceFiltersKey) {
    setAppliedResourceFilters(resourceFiltersKey)
    setResourceDraft(resources.filters)
  }

  const submissionStatusOptions = [
    { value: MEDIA_SUBMISSION_FILTER_ALL, label: '全部状态' },
    ...(Object.keys(MEDIA_SUBMISSION_GROUP_META) as AdminMediaSubmissionGroup[]).map((group) => ({
      value: mediaSubmissionGroupFilterValue(group),
      label: `${MEDIA_SUBMISSION_GROUP_META[group].label}（分组）`,
    })),
    ...(Object.keys(MEDIA_SUBMISSION_STATUS_META) as AdminMediaSubmissionStatus[]).map(
      (status) => ({
        value: mediaSubmissionStatusFilterValue(status),
        label: `状态：${MEDIA_SUBMISSION_STATUS_META[status].label}`,
      }),
    ),
  ]

  const statCards: readonly { label: string; icon: React.ReactNode; count?: number }[] = [
    { label: '可用媒体资源', icon: <DatabaseOutlined />, count: overview?.activeResources },
    { label: '上游账号余额', icon: <DollarOutlined /> },
    { label: '待处理投稿', icon: <FileTextOutlined />, count: overview?.pendingSubmissions },
    { label: '异常投稿', icon: <WarningOutlined />, count: overview?.exceptionSubmissions },
  ]

  return (
    <section className="admin-workspace admin-publishing" aria-label="媒体与投稿">
      <header className="admin-workspace__heading">
        <div>
          <Typography.Title level={1}>媒体资源与投稿</Typography.Title>
          <Typography.Text type="secondary">
            查看媒体资源、投稿进度与上游回执，只读，不触发上游写操作
          </Typography.Text>
        </div>
        <div className="admin-publishing-actions">
          {onRefresh ? (
            <Button
              icon={<SyncOutlined aria-hidden="true" />}
              loading={refreshing}
              disabled={refreshing}
              onClick={onRefresh}
            >
              刷新当前数据
            </Button>
          ) : null}
          <Tag icon={<SafetyCertificateOutlined />} color="red">
            管理员只读
          </Tag>
        </div>
      </header>

      <Alert
        showIcon
        type="info"
        title={
          preview
            ? '开发设计预览 · 媒体与投稿样例仅用于界面验收'
            : '本页为只读视图，不发起上游写操作'
        }
        description="媒体资源由后台自动同步。本页展示已记录的资源、投稿和回执；需要最新状态时可刷新当前数据。上游账号余额暂无可靠数据源，因此显示为 —。"
      />

      <div className="admin-stat-grid">
        {statCards.map((item) => (
          <Card key={item.label}>
            {item.icon}
            <span>{item.label}</span>
            <strong>{formatPoints(item.count)}</strong>
          </Card>
        ))}
      </div>
      <ErrorNotice error={overviewError} onRetry={onRetryOverview} />

      <Tabs
        activeKey={activeTab}
        onChange={(key) => {
          setInternalTab(key as AdminMediaTab)
          onTabChange?.(key as AdminMediaTab)
        }}
        items={[
          { key: 'resources', label: '媒体资源' },
          { key: 'submissions', label: '投稿订单' },
          { key: 'callbacks', label: '异常回调' },
          { key: 'sync', label: '当前同步状态' },
        ]}
      />

      {activeTab === 'resources' ? (
        <>
          <Card className="admin-data-card" variant="borderless">
            <form
              className="admin-publishing-media-filters"
              onSubmit={(event) => {
                event.preventDefault()
                resources.onFiltersChange?.(resourceDraft)
              }}
            >
              <Input
                allowClear
                aria-label="搜索媒体"
                placeholder="媒体名称 / 上游资源名"
                maxLength={SEARCH_MAX_LENGTH}
                value={resourceDraft.search ?? ''}
                onChange={(event) =>
                  setResourceDraft({ ...resourceDraft, search: event.target.value })
                }
              />
              <Input
                allowClear
                aria-label="上游模型 ID"
                placeholder="上游模型 ID"
                maxLength={SEARCH_MAX_LENGTH}
                value={resourceDraft.modelId ?? ''}
                onChange={(event) =>
                  setResourceDraft({ ...resourceDraft, modelId: event.target.value })
                }
              />
              <Input
                allowClear
                aria-label="资源类型"
                placeholder="资源类型"
                maxLength={SEARCH_MAX_LENGTH}
                value={resourceDraft.type ?? ''}
                onChange={(event) =>
                  setResourceDraft({ ...resourceDraft, type: event.target.value })
                }
              />
              <Select
                aria-label="资源状态"
                value={resourceDraft.status ?? MEDIA_SUBMISSION_FILTER_ALL}
                options={[
                  { value: MEDIA_SUBMISSION_FILTER_ALL, label: '全部状态' },
                  ...Object.entries(MEDIA_RESOURCE_STATUS_META).map(([status, meta]) => ({
                    value: status,
                    label: meta.label,
                  })),
                ]}
                onChange={(status: string) =>
                  setResourceDraft({
                    ...resourceDraft,
                    status:
                      status === MEDIA_SUBMISSION_FILTER_ALL
                        ? undefined
                        : (status as AdminMediaResource['status']),
                  })
                }
              />
              <div className="admin-publishing-actions">
                <Button
                  aria-label="重置"
                  onClick={() => {
                    setResourceDraft({})
                    resources.onFiltersChange?.({})
                  }}
                >
                  重置
                </Button>
                <Button aria-label="查询" type="primary" htmlType="submit">
                  查询
                </Button>
              </div>
            </form>
            <ResourceTable
              table={resources}
              onOpen={(id) => onOpenDetail?.({ kind: 'resource', id })}
            />
          </Card>
          <Card
            className="admin-data-card"
            title="异常投稿"
            variant="borderless"
            extra={
              <Button type="link" onClick={onShowExceptionSubmissions}>
                查看异常投稿
              </Button>
            }
          >
            <div className="admin-publishing-note">
              异常投稿共 {formatPoints(overview?.exceptionSubmissions)} 条。受理结果未知或提交失败不代表可以直接重投，请查看状态记录。
            </div>
          </Card>
        </>
      ) : null}

      {activeTab === 'submissions' ? (
        <Card className="admin-data-card" variant="borderless">
          <div className="admin-filter-bar admin-media-submission-filter">
            <Select
              aria-label="投稿状态"
              value={submissions.filter}
              options={submissionStatusOptions}
              onChange={(next) => submissions.onFilterChange?.(next)}
            />
            <Button
              aria-label="重置"
              onClick={() => submissions.onFilterChange?.(MEDIA_SUBMISSION_FILTER_ALL)}
            >
              重置
            </Button>
          </div>
          <ErrorNotice error={submissions.error} onRetry={submissions.onRetry} />
          {submissions.loading ? (
            <Skeleton active paragraph={{ rows: 8 }} />
          ) : submissions.error && submissions.items.length === 0 ? null : submissions.items.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={
                submissions.filter === MEDIA_SUBMISSION_FILTER_ALL
                  ? '暂无投稿订单'
                  : '没有符合条件的投稿订单'
              }
            />
          ) : (
            <>
              <div className="admin-table-scroll">
                <table className="admin-data-table" aria-label="投稿订单列表">
                  <thead>
                    <tr>
                      {[
                        '投稿号',
                        '客户订单号',
                        '媒体',
                        '用户 ID',
                        '本地状态',
                        '上游状态',
                        '上游订单号',
                        '成交积分',
                        '创建时间',
                        '操作',
                      ].map((heading) => (
                        <th key={heading}>{heading}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {submissions.items.map((item) => (
                      <tr key={item.id}>
                        <td>
                          <strong>{item.id}</strong>
                        </td>
                        <td>{item.customerOrderId}</td>
                        <td className="admin-publishing-title">{item.mediaName}</td>
                        <td>{item.userId}</td>
                        <td>
                          <Tag color={MEDIA_SUBMISSION_STATUS_META[item.status].color}>
                            {MEDIA_SUBMISSION_STATUS_META[item.status].label}
                          </Tag>
                        </td>
                        <td>{value(item.upstreamStatus)}</td>
                        <td>{value(item.upstreamOrderNo)}</td>
                        <td>{formatPoints(item.chargedPoints)}</td>
                        <td>{formatDateTime(item.createdAt)}</td>
                        <td>
                          <Button
                            type="link"
                            onClick={() => onOpenDetail?.({ kind: 'submission', id: item.id })}
                          >
                            查看投稿
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <TableFooter
                page={submissions.page}
                pageSize={submissions.pageSize}
                total={submissions.total}
                onPageChange={submissions.onPageChange}
              />
            </>
          )}
          <div className="admin-publishing-note">
            成交积分与采购原价是下单时冻结的值，资源改价不会改写历史投稿。列表按服务端返回的顺序展示，不支持关键词搜索。
          </div>
        </Card>
      ) : null}

      {activeTab === 'callbacks' ? (
        <Card className="admin-data-card" variant="borderless">
          <div className="admin-publishing-note">
            仅列出待处理与处理失败的上游回执。回执以自身编号为准，不展示请求体或正文摘要。
          </div>
          <ErrorNotice error={callbacks.error} onRetry={callbacks.onRetry} />
          {callbacks.loading ? (
            <Skeleton active paragraph={{ rows: 6 }} />
          ) : callbacks.error && callbacks.items.length === 0 ? null : callbacks.items.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无异常回调" />
          ) : (
            <>
              <div className="admin-table-scroll">
                <table className="admin-data-table" aria-label="异常回调列表">
                  <thead>
                    <tr>
                      {[
                        '回执 ID',
                        '客户订单号',
                        '上游提供方',
                        '事件类型',
                        '状态',
                        '尝试次数',
                        '错误码',
                        '接收时间',
                        '处理时间',
                        '操作',
                      ].map((heading) => (
                        <th key={heading}>{heading}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {callbacks.items.map((item) => (
                      <tr key={item.id}>
                        <td>
                          <strong>{item.id}</strong>
                        </td>
                        <td>{value(item.customerOrderId)}</td>
                        <td>{item.providerCode}</td>
                        <td>{item.eventType}</td>
                        <td>
                          <Tag color={MEDIA_CALLBACK_STATUS_META[item.status].color}>
                            {MEDIA_CALLBACK_STATUS_META[item.status].label}
                          </Tag>
                        </td>
                        <td>{item.attempts}</td>
                        <td>{value(item.stableErrorCode)}</td>
                        <td>{formatDateTime(item.receivedAt)}</td>
                        <td>{formatDateTime(item.processedAt)}</td>
                        <td>
                          <Button
                            type="link"
                            onClick={() => onOpenDetail?.({ kind: 'callback', id: item.id })}
                          >
                            查看回执
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <TableFooter
                page={callbacks.page}
                pageSize={callbacks.pageSize}
                total={callbacks.total}
                onPageChange={callbacks.onPageChange}
              />
            </>
          )}
        </Card>
      ) : null}

      {activeTab === 'sync' ? (
        <Card className="admin-data-card" variant="borderless">
          <div className="admin-publishing-note">
            仅记录每个上游模型的最近一次同步结果。没有同步批次、操作人或本次新增/更新计数，因此不展示这些字段。
          </div>
          <ErrorNotice error={syncStates.error} onRetry={syncStates.onRetry} />
          {syncStates.loading ? (
            <Skeleton active paragraph={{ rows: 6 }} />
          ) : syncStates.error && syncStates.items.length === 0 ? null : syncStates.items.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无同步状态记录" />
          ) : (
            <div className="admin-table-scroll">
              <table className="admin-data-table" aria-label="当前同步状态列表">
                <thead>
                  <tr>
                    {[
                      '上游提供方',
                      '上游模型',
                      '状态',
                      '最近尝试',
                      '最近成功',
                      '错误码',
                      '资源数',
                      '记录版本',
                      '更新时间',
                    ].map((heading) => (
                      <th key={heading}>{heading}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {syncStates.items.map((item) => (
                    <tr key={`${item.providerCode}:${item.upstreamModelId}`}>
                      <td>{item.providerCode}</td>
                      <td>
                        <code>{item.upstreamModelId}</code>
                      </td>
                      <td>
                        <Tag color={syncStatusMeta(item.status).color}>
                          {syncStatusMeta(item.status).label}
                        </Tag>
                      </td>
                      <td>{formatDateTime(item.lastAttemptedAt)}</td>
                      <td>{formatDateTime(item.lastSucceededAt)}</td>
                      <td>{value(item.lastErrorCode)}</td>
                      <td>{item.lastResourceCount}</td>
                      <td>{item.version}</td>
                      <td>{formatDateTime(item.updatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      ) : null}

      <Drawer
        title={detailTitle(detail)}
        size="min(820px, 94vw)"
        open={Boolean(detail)}
        onClose={onCloseDetail}
      >
        {detail ? <DetailBody detail={detail} /> : null}
      </Drawer>
    </section>
  )
}

function detailTitle(detail?: AdminMediaDetailProps | null): string {
  if (!detail) return '详情'
  if (detail.target.kind === 'resource') return '资源详情'
  if (detail.target.kind === 'submission') return '投稿详情'
  return '回调回执详情'
}

function Facts({ rows }: { rows: readonly (readonly [string, string])[] }) {
  return (
    <dl className="admin-detail-list">
      {rows.map(([label, text]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{text}</dd>
        </div>
      ))}
    </dl>
  )
}

function ResourceTable({
  table,
  onOpen,
}: {
  table: AdminMediaResourceTableProps
  onOpen: (id: string) => void
}) {
  // The failure notice stands above the empty state: a request that failed is not an empty result.
  const body = table.loading ? (
    <Skeleton active paragraph={{ rows: 8 }} />
  ) : table.error && table.items.length === 0 ? null : table.items.length === 0 ? (
    <Empty
      image={Empty.PRESENTED_IMAGE_SIMPLE}
      description={table.page > 1 ? '没有符合条件的媒体资源' : '暂无媒体资源'}
    />
  ) : (
    <>
      <div className="admin-table-scroll">
        <table className="admin-data-table" aria-label="媒体资源列表">
          <thead>
            <tr>
              {[
                '媒体名称',
                '资源类型',
                '上游资源 ID',
                '采购原价',
                '加成系数',
                '上取整后售价',
                '用户积分价',
                '预计时效',
                '本地状态',
                '最近上游可见',
                '操作',
              ].map((heading) => (
                <th key={heading}>{heading}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {table.items.map((item) => (
              <tr key={item.id}>
                <td>
                  <strong>{item.name}</strong>
                </td>
                <td>{value(item.resourceType)}</td>
                <td>
                  <code>{item.upstreamResourceId}</code>
                </td>
                <td>
                  {formatUpstreamPrice(item.upstreamPrice)}
                  {item.status === 'INACTIVE' ? <small>（历史）</small> : null}
                </td>
                <td>{exactDecimal(item.markupRate)}</td>
                <td>
                  {formatCents(item.referenceSaleAmountCents)}
                  {item.status === 'INACTIVE' ? <small>（历史）</small> : null}
                </td>
                <td>
                  {formatPoints(item.referenceChargedPoints)}
                  {item.status === 'INACTIVE' ? <small>（历史）</small> : null}
                </td>
                <td>{days(item.estimatedDays)}</td>
                <td>
                  <Tag color={MEDIA_RESOURCE_STATUS_META[item.status].color}>
                    {MEDIA_RESOURCE_STATUS_META[item.status].label}
                  </Tag>
                </td>
                <td>{formatDateTime(item.lastSeenAt)}</td>
                <td>
                  <Button type="link" onClick={() => onOpen(item.id)}>
                    查看资源
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <TableFooter
        page={table.page}
        pageSize={table.pageSize}
        total={table.total}
        onPageChange={table.onPageChange}
      />
      <div className="admin-publishing-note">
        采购原价是上游记账原值（元，四位小数）；上取整后售价与用户积分价由服务端按当前定价策略计算。停用资源标注的历史金额仅供追溯，当前不可投稿。
      </div>
    </>
  )

  return (
    <>
      <ErrorNotice error={table.error} onRetry={table.onRetry} />
      {body}
    </>
  )
}

function DetailBody({ detail }: { detail: AdminMediaDetailProps }) {
  if (detail.error) return <ErrorNotice error={detail.error} onRetry={detail.onRetry} />
  if (detail.target.kind === 'resource') {
    return detail.resource ? <ResourceDetail resource={detail.resource} /> : <Skeleton active />
  }
  if (detail.target.kind === 'callback') {
    return detail.callback ? <CallbackDetail callback={detail.callback} /> : <Skeleton active />
  }
  return detail.submission ? (
    <SubmissionDetail
      summary={detail.submission}
      full={detail.submissionDetail}
      loading={detail.loading}
    />
  ) : (
    <Skeleton active />
  )
}

function ResourceDetail({ resource }: { resource: AdminMediaResource }) {
  const capabilities = capabilityEntries(resource.capabilities)
  return (
    <div className="admin-detail-stack">
      <section>
        <Typography.Title level={3}>
          {resource.name}{' '}
          <Tag color={MEDIA_RESOURCE_STATUS_META[resource.status].color}>
            {MEDIA_RESOURCE_STATUS_META[resource.status].label}
          </Tag>
        </Typography.Title>
        <Typography.Text type="secondary">
          {resource.providerCode} · 上游模型 <code>{resource.upstreamModelId}</code> · 上游资源 ID{' '}
          <code>{resource.upstreamResourceId}</code>
        </Typography.Text>
      </section>

      <section>
        <Typography.Title level={3}>
          {resource.status === 'ACTIVE' ? '报价（服务端当前策略）' : '最近记录的报价（当前不可投稿）'}
        </Typography.Title>
        <Facts
          rows={[
            ['采购原价（元）', formatUpstreamPrice(resource.upstreamPrice)],
            ['加成系数', exactDecimal(resource.markupRate)],
            ['上取整后售价', formatCents(resource.referenceSaleAmountCents)],
            ['用户积分价', `${formatPoints(resource.referenceChargedPoints)} 积分`],
          ]}
        />
        <div className="admin-publishing-note">
          {resource.status === 'ACTIVE'
            ? '参考售价按采购原价乘以（1 + 加成系数）后向上取整到整元，再换算为分，由服务端给出；前端只格式化、不做运算。资源改价不会改写历史投稿的成交价。'
            : '以上金额是本地最后一次记录值，仅供核对。资源当前不可投稿，恢复可投稿后才会重新提供有效报价。'}
        </div>
      </section>

      <section>
        <Typography.Title level={3}>上架信息</Typography.Title>
        <Facts
          rows={[
            ['资源类型', value(resource.resourceType)],
            ['渠道分类', value(resource.channelCategory)],
            ['地区', value(resource.region)],
            ['预计时效', days(resource.estimatedDays)],
          ]}
        />
      </section>

      <section>
        <Typography.Title level={3}>上游能力说明</Typography.Title>
        {capabilities.length > 0 ? (
          <Facts rows={capabilities.map((entry) => [entry.key, entry.value] as const)} />
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="上游未提供能力说明" />
        )}
        <div className="admin-publishing-note">字段名与取值来自上游原文，不翻译、不合并。</div>
      </section>

      <section>
        <Typography.Title level={3}>状态信息</Typography.Title>
        <Facts
          rows={[
            ['本地状态', MEDIA_RESOURCE_STATUS_META[resource.status].label],
            ['最近上游可见', formatDateTime(resource.lastSeenAt)],
            ['最近同步', formatDateTime(resource.syncedAt)],
            ['记录版本', String(resource.version)],
          ]}
        />
      </section>
    </div>
  )
}

function SubmissionDetail({
  summary,
  full,
  loading,
}: {
  summary: AdminMediaSubmissionSummary
  full?: AdminMediaSubmission
  loading: boolean
}) {
  // The list row is only an immediate loading fallback. Once the by-id read arrives it is the
  // authoritative source for every subject field, not merely for the event trail: a submission can
  // move from QUEUED to PUBLISHED between the list read and opening this drawer.
  const current = full ?? summary
  const publishedUrls = [...new Set(
    [current.publishedUrl, ...(current.publishedUrls ?? [])]
      .map(safePublishedUrl)
      .filter((url): url is string => Boolean(url)),
  )]
  return (
    <div className="admin-detail-stack">
      <section>
        <Typography.Title level={3}>
          {current.mediaName}{' '}
          <Tag color={MEDIA_SUBMISSION_STATUS_META[current.status].color}>
            {MEDIA_SUBMISSION_STATUS_META[current.status].label}
          </Tag>
        </Typography.Title>
        <Typography.Text type="secondary">客户订单号 {current.customerOrderId}</Typography.Text>
      </section>

      <section>
        <Typography.Title level={3}>投稿信息</Typography.Title>
        <Facts
          rows={[
            ['投稿号', current.id],
            ['批次 ID', current.batchId],
            ['任务 ID', current.taskId],
            ['用户 ID', current.userId],
            ['媒体资源 ID', current.mediaResourceId],
            ['上游提供方', current.providerCode],
            ['上游资源 ID', current.upstreamResourceId],
            ['本地状态', MEDIA_SUBMISSION_STATUS_META[current.status].label],
            ['受理状态', MEDIA_SUBMISSION_STATE_META[current.submissionState].label],
            ['上游状态', value(current.upstreamStatus)],
            ['上游订单号', value(current.upstreamOrderNo)],
            ['上游订单 ID', value(current.upstreamOrderId)],
            ['错误码', value(current.stableErrorCode)],
            ['备注', value(current.remark)],
          ]}
        />
      </section>

      <section>
        <Typography.Title level={3}>成交价格（下单时冻结）</Typography.Title>
        <Facts
          rows={[
            ['采购原价（元）', formatUpstreamPrice(current.upstreamPrice)],
            ['加成系数', exactDecimal(current.markupRate)],
            ['售价', formatCents(current.saleAmountCents)],
            ['扣除积分', `${formatPoints(current.chargedPoints)} 积分`],
          ]}
        />
      </section>

      <section>
        <Typography.Title level={3}>时间</Typography.Title>
        <Facts
          rows={[
            ['创建时间', formatDateTime(current.createdAt)],
            ['最近提交尝试', formatDateTime(current.submitAttemptedAt)],
            ['下次轮询', formatDateTime(current.nextPollAt)],
            ['发布时间', formatDateTime(current.publishedAt)],
            ['拒绝时间', formatDateTime(current.rejectedAt)],
            ['更新时间', formatDateTime(current.updatedAt)],
          ]}
        />
      </section>

      <section>
        <Typography.Title level={3}>发布回链</Typography.Title>
        {publishedUrls.length > 0 ? (
          <ul>
            {publishedUrls.map((url, index) => (
              <li key={url}>
                <a href={url} target="_blank" rel="noopener noreferrer">
                  查看已发布文章 {index + 1}
                </a>
              </li>
            ))}
          </ul>
        ) : (
          <Typography.Text type="secondary">
            {current.status === 'PUBLISHED'
              ? '已发布，上游暂未提供可访问链接'
              : '暂无有效发布回链'}
          </Typography.Text>
        )}
      </section>

      <section>
        <Typography.Title level={3}>状态事件</Typography.Title>
        {loading ? (
          <Skeleton active paragraph={{ rows: 3 }} />
        ) : full && full.events.length > 0 ? (
          <div className="admin-table-scroll">
            <table className="admin-data-table" aria-label="投稿状态事件列表">
              <thead>
                <tr>
                  {['事件 ID', '序号', '事件类型', '变更', '错误码', '发生时间'].map((heading) => (
                    <th key={heading}>{heading}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {full.events.map((event) => (
                  <tr key={event.id}>
                    <td>
                      <code>{event.id}</code>
                    </td>
                    <td>{event.sequence}</td>
                    <td>{event.eventType}</td>
                    <td>
                      {submissionStatusLabel(event.fromStatus)} →{' '}
                      {submissionStatusLabel(event.toStatus)}
                    </td>
                    <td>{value(event.stableErrorCode)}</td>
                    <td>{formatDateTime(event.occurredAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该投稿尚未记录状态事件" />
        )}
        <div className="admin-publishing-note">
          事件 ID 是本地状态事件的编号。平台不向管理端提供上游链路追踪号，因此这里没有 Trace。
        </div>
      </section>

      <Alert
        showIcon
        type="warning"
        title="上游结果未知或超时不代表失败，不得直接重投"
        description="须由服务端重新核验上游状态与幂等性之后再决定后续动作。"
      />
    </div>
  )
}

function CallbackDetail({ callback }: { callback: AdminMediaCallback }) {
  return (
    <div className="admin-detail-stack">
      <section>
        <Typography.Title level={3}>
          回执 {callback.id}{' '}
          <Tag color={MEDIA_CALLBACK_STATUS_META[callback.status].color}>
            {MEDIA_CALLBACK_STATUS_META[callback.status].label}
          </Tag>
        </Typography.Title>
        <Typography.Text type="secondary">
          客户订单号 {value(callback.customerOrderId)}
        </Typography.Text>
      </section>
      <section>
        <Facts
          rows={[
            ['回执 ID', callback.id],
            ['上游提供方', callback.providerCode],
            ['上游模型', value(callback.upstreamModelId)],
            ['上游资源 ID', value(callback.upstreamResourceId)],
            ['事件类型', callback.eventType],
            ['状态', MEDIA_CALLBACK_STATUS_META[callback.status].label],
            ['尝试次数', String(callback.attempts)],
            ['错误码', value(callback.stableErrorCode)],
            ['接收时间', formatDateTime(callback.receivedAt)],
            ['处理时间', formatDateTime(callback.processedAt)],
            ['更新时间', formatDateTime(callback.updatedAt)],
          ]}
        />
      </section>
      <Alert
        showIcon
        type="info"
        title="不展示回调请求体与正文摘要"
        description="回执以自身编号为准；报文摘要由未脱敏的正文推导而来，因此不会下发到管理端。"
      />
    </div>
  )
}

/**
 * The provider may return HTTP or HTTPS publication links. Never render an executable URL scheme
 * or a URL with embedded credentials as a clickable link.
 */
function safePublishedUrl(text: string | undefined | null): string | undefined {
  if (!text?.trim()) return undefined
  try {
    const url = new URL(text)
    return (url.protocol === 'http:' || url.protocol === 'https:') &&
      !url.username && !url.password ? url.href : undefined
  } catch {
    return undefined
  }
}

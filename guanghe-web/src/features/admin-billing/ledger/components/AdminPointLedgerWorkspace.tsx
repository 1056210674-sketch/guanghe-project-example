/**
 * 积分流水 — the presentational half of the ledger console.
 *
 * It receives view models, loading flags, error views and callbacks. It never calls the API and it
 * never computes a total: 合计 is whatever `getAdminPointLedgerSummary` returned for the current
 * filters, which is why it is labelled 与当前分页无关.
 */
import {
  DatabaseOutlined,
  ReloadOutlined,
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
import { formatCents, formatDateTime, formatPoints, formatSignedPoints, maskUserId } from '../../format'
import type {
  AdminErrorView,
  AdminLedgerFiltersView,
  AdminPointLedgerEntryView,
  BookkeepingView,
  LedgerSummaryView,
  StatusCountView,
} from '../types'
import { adminLedgerFiltersAreEmpty } from '../types'

function ErrorNotice({ error }: { error: AdminErrorView | null }) {
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

function Value({ value }: { value: string | undefined | null }) {
  const text = value?.trim()
  return <>{text ? text : '—'}</>
}

function StatusCounts({ counts }: { counts: readonly StatusCountView[] }) {
  if (counts.length === 0) {
    return <Typography.Text type="secondary">—</Typography.Text>
  }
  return (
    <Space wrap size={4}>
      {counts.map((count) => (
        <Tag key={count.status}>
          {count.status} {formatPoints(count.total)}
        </Tag>
      ))}
    </Space>
  )
}

const LEDGER_COLUMNS = [
  '流水号',
  '用户',
  '流水类型',
  '业务类型',
  '业务单号',
  '可用变动',
  '冻结变动',
  '可用余额',
  '冻结余额',
  '操作人',
  '原因',
  '发生时间',
  '操作',
] as const

export interface AdminPointLedgerWorkspaceProps {
  entries: readonly AdminPointLedgerEntryView[]
  total: number
  page: number
  pageSize: number
  loading: boolean
  error: AdminErrorView | null
  filters: AdminLedgerFiltersView
  summary?: LedgerSummaryView
  summaryLoading: boolean
  summaryError: AdminErrorView | null
  bookkeeping?: BookkeepingView
  bookkeepingLoading: boolean
  bookkeepingError: AdminErrorView | null
  detail?: AdminPointLedgerEntryView
  onFiltersChange: (filters: AdminLedgerFiltersView) => void
  onPageChange: (page: number) => void
  onOpenEntry: (entryId: string) => void
  onCloseEntry: () => void
  onRetryList?: () => void
  onRetrySummary?: () => void
  onRetryBookkeeping?: () => void
}

export function AdminPointLedgerWorkspace({
  entries,
  total,
  page,
  pageSize,
  loading,
  error,
  filters,
  summary,
  summaryLoading,
  summaryError,
  bookkeeping,
  bookkeepingLoading,
  bookkeepingError,
  detail,
  onFiltersChange,
  onPageChange,
  onOpenEntry,
  onCloseEntry,
  onRetryList,
  onRetrySummary,
  onRetryBookkeeping,
}: AdminPointLedgerWorkspaceProps) {
  const [draft, setDraft] = useState<AdminLedgerFiltersView>(filters)
  const entryTypeOptions = bookkeeping?.ledgerEntryTypes ?? []
  const businessTypeOptions = bookkeeping?.ledgerBusinessTypes ?? []
  const hasFilters = !adminLedgerFiltersAreEmpty(filters)
  const optionsAvailable = entryTypeOptions.length > 0 && businessTypeOptions.length > 0

  const submitFilters = () => {
    onFiltersChange({
      userId: draft.userId?.trim() || undefined,
      entryType: draft.entryType || undefined,
      businessType: draft.businessType || undefined,
      from: draft.from?.trim() || undefined,
      to: draft.to?.trim() || undefined,
    })
  }

  const resetFilters = () => {
    setDraft({})
    onFiltersChange({})
  }

  return (
    <section className="admin-workspace admin-ledger" aria-labelledby="admin-point-ledger-title">
      <header className="admin-workspace__heading">
        <div>
          <Typography.Title id="admin-point-ledger-title" level={2}>
            积分流水
          </Typography.Title>
          <Typography.Text type="secondary">
            按用户、流水类型、业务类型与时间区间查询流水，并单独核对合计
          </Typography.Text>
        </div>
        <Tag icon={<SafetyCertificateOutlined />} color="red">
          管理员专用
        </Tag>
      </header>

      <div className="admin-stat-grid" aria-label="账务汇总">
        {bookkeepingLoading ? (
          <Card variant="borderless">
            <Skeleton active paragraph={{ rows: 1 }} />
          </Card>
        ) : bookkeeping ? (
          <>
            <Card variant="borderless">
              <DatabaseOutlined />
              <span>平台可用积分</span>
              <strong>{formatPoints(bookkeeping.platformAvailablePoints)}</strong>
            </Card>
            <Card variant="borderless">
              <DatabaseOutlined />
              <span>平台冻结积分</span>
              <strong>{formatPoints(bookkeeping.platformFrozenPoints)}</strong>
            </Card>
            <Card variant="borderless">
              <DatabaseOutlined />
              <span>充值成功订单</span>
              <strong>{formatPoints(bookkeeping.recharge.succeededOrders)}</strong>
            </Card>
            <Card variant="borderless">
              <DatabaseOutlined />
              <span>充值成功金额</span>
              <strong>{formatCents(bookkeeping.recharge.succeededAmountCents)}</strong>
            </Card>
            <Card variant="borderless">
              <DatabaseOutlined />
              <span>发票已交付金额</span>
              <strong>{formatCents(bookkeeping.invoice.deliveredAmountCents)}</strong>
            </Card>
            <Card variant="borderless">
              <DatabaseOutlined />
              <span>流水条数（全平台）</span>
              <strong>{formatPoints(bookkeeping.ledger.entries)}</strong>
            </Card>
          </>
        ) : (
          <Card variant="borderless">
            <DatabaseOutlined />
            <span>账务汇总</span>
            <strong>未取到</strong>
          </Card>
        )}
      </div>

      <ErrorNotice error={bookkeepingError} />
      {bookkeepingError && onRetryBookkeeping ? (
        <Button icon={<ReloadOutlined />} onClick={onRetryBookkeeping}>
          重新加载账务汇总
        </Button>
      ) : null}

      <Card
        className="admin-data-card admin-ledger-summary-card"
        variant="borderless"
        title="账务汇总明细"
        extra={bookkeeping ? `统计时间 ${formatDateTime(bookkeeping.generatedAt)}` : undefined}
      >
        {bookkeeping ? (
          <dl className="admin-detail-list">
            <div>
              <dt>充值订单状态</dt>
              <dd>
                <StatusCounts counts={bookkeeping.recharge.ordersByStatus} />
              </dd>
            </div>
            <div>
              <dt>充值发放积分</dt>
              <dd>
                基础 {formatPoints(bookkeeping.recharge.basePoints)} · 赠送{' '}
                {formatPoints(bookkeeping.recharge.giftPoints)}
              </dd>
            </div>
            <div>
              <dt>发票申请状态</dt>
              <dd>
                <StatusCounts counts={bookkeeping.invoice.applicationsByStatus} />
              </dd>
            </div>
            <div>
              <dt>反馈状态</dt>
              <dd>
                <StatusCounts counts={bookkeeping.feedback.itemsByStatus} />
              </dd>
            </div>
            <div>
              <dt>流水合计（全量）</dt>
              <dd>
                条数 {formatPoints(bookkeeping.ledger.entries)} · 可用变动{' '}
                {formatSignedPoints(bookkeeping.ledger.availableDelta)} · 冻结变动{' '}
                {formatSignedPoints(bookkeeping.ledger.frozenDelta)}
              </dd>
            </div>
          </dl>
        ) : bookkeepingLoading ? (
          <Skeleton active paragraph={{ rows: 3 }} />
        ) : (
          <Typography.Text type="secondary">未取到账务汇总，不使用页面数据推算。</Typography.Text>
        )}
      </Card>

      <Card className="admin-data-card" variant="borderless">
        <form
          className="admin-filter-bar admin-filter-bar--ledger"
          onSubmit={(event) => {
            event.preventDefault()
            submitFilters()
          }}
        >
          <Input
            allowClear
            prefix={<SearchOutlined />}
            aria-label="流水用户 ID"
            placeholder="用户 ID"
            value={draft.userId ?? ''}
            onChange={(event) => setDraft({ ...draft, userId: event.target.value })}
          />
          <Select
            allowClear
            aria-label="流水类型"
            placeholder="全部流水类型"
            value={draft.entryType}
            disabled={entryTypeOptions.length === 0}
            options={entryTypeOptions.map((value) => ({ value, label: value }))}
            onChange={(entryType) => setDraft({ ...draft, entryType })}
          />
          <Select
            allowClear
            aria-label="业务类型"
            placeholder="全部业务类型"
            value={draft.businessType}
            disabled={businessTypeOptions.length === 0}
            options={businessTypeOptions.map((value) => ({ value, label: value }))}
            onChange={(businessType) => setDraft({ ...draft, businessType })}
          />
          <Input
            type="datetime-local"
            aria-label="开始时间"
            value={draft.from ?? ''}
            onChange={(event) => setDraft({ ...draft, from: event.target.value })}
          />
          <Input
            type="datetime-local"
            aria-label="结束时间"
            value={draft.to ?? ''}
            onChange={(event) => setDraft({ ...draft, to: event.target.value })}
          />
          <Button type="primary" htmlType="submit">
            查询
          </Button>
          <Button onClick={resetFilters} disabled={!hasFilters && adminLedgerFiltersAreEmpty(draft)}>
            重置
          </Button>
        </form>
        {!optionsAvailable ? (
          <Typography.Text className="admin-billing-inline-note" type="secondary">
            流水类型与业务类型选项来自账务汇总接口，汇总未返回时无法选择（当前汇总
            {bookkeepingLoading ? '加载中' : '不可用'}）。
          </Typography.Text>
        ) : null}
      </Card>

      <ErrorNotice error={error} />

      <Card className="admin-data-card admin-ledger-summary-card" variant="borderless" title="合计（与当前分页无关）">
        {summaryLoading ? (
          <Skeleton active paragraph={{ rows: 1 }} />
        ) : summaryError ? (
          <Typography.Text type="secondary">
            未取到合计，合计不按当前页数据推算。
          </Typography.Text>
        ) : summary ? (
          <dl className="admin-detail-list" aria-label="合计">
            <div>
              <dt>流水条数</dt>
              <dd>{formatPoints(summary.entries)}</dd>
            </div>
            <div>
              <dt>可用积分变动</dt>
              <dd>{formatSignedPoints(summary.availableDelta)}</dd>
            </div>
            <div>
              <dt>冻结积分变动</dt>
              <dd>{formatSignedPoints(summary.frozenDelta)}</dd>
            </div>
          </dl>
        ) : (
          <Typography.Text type="secondary">合计接口暂未返回数据。</Typography.Text>
        )}
        <ErrorNotice error={summaryError} />
        {summaryError && onRetrySummary ? (
          <Button icon={<ReloadOutlined />} onClick={onRetrySummary}>
            重新加载合计
          </Button>
        ) : null}
      </Card>

      <Card className="admin-data-card" variant="borderless">
        {loading ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : entries.length === 0 ? (
          <Empty
            image={<DatabaseOutlined />}
            description={
              <div className="admin-empty-copy">
                <strong>{hasFilters ? '没有符合条件的积分流水' : '暂无积分流水'}</strong>
                <span>
                  {hasFilters
                    ? '请调整用户、流水类型、业务类型或时间区间后重新查询'
                    : '账务接口当前没有返回任何流水记录'}
                </span>
              </div>
            }
          />
        ) : (
          <div className="admin-table-scroll">
            <table className="admin-data-table admin-ledger-table" aria-label="积分流水列表">
              <thead>
                <tr>
                  {LEDGER_COLUMNS.map((heading) => (
                    <th key={heading} scope="col">
                      {heading}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {entries.map((entry) => (
                  <tr key={entry.id}>
                    <td>
                      <strong>{entry.id}</strong>
                    </td>
                    <td>{maskUserId(entry.userId)}</td>
                    <td>
                      <Tag>{entry.entryType}</Tag>
                    </td>
                    <td>
                      <Tag>{entry.businessType}</Tag>
                    </td>
                    <td>
                      <Value value={entry.businessId} />
                    </td>
                    <td>{formatSignedPoints(entry.availableDelta)}</td>
                    <td>{formatSignedPoints(entry.frozenDelta)}</td>
                    <td>{formatPoints(entry.availableAfter)}</td>
                    <td>{formatPoints(entry.frozenAfter)}</td>
                    <td>
                      <Value value={entry.operatorId ? maskUserId(entry.operatorId) : undefined} />
                    </td>
                    <td>
                      <Value value={entry.reason} />
                    </td>
                    <td>{formatDateTime(entry.createdAt)}</td>
                    <td>
                      <Button
                        type="link"
                        aria-label={`查看流水 ${entry.id}`}
                        onClick={() => onOpenEntry(entry.id)}
                      >
                        查看
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {!loading && total > 0 ? (
          <div className="admin-observability-footer">
            <span>共 {formatPoints(total)} 条</span>
            <Pagination
              current={page}
              pageSize={pageSize}
              total={total}
              showSizeChanger={false}
              onChange={onPageChange}
            />
          </div>
        ) : null}
        {error && onRetryList ? (
          <Button icon={<ReloadOutlined />} onClick={onRetryList}>
            重新加载流水
          </Button>
        ) : null}
      </Card>

      <Drawer
        title={detail ? `流水详情 · ${detail.id}` : '流水详情'}
        size={480}
        open={Boolean(detail)}
        onClose={onCloseEntry}
      >
        {detail ? (
          <div className="admin-detail-stack">
            <section>
              <Typography.Title level={3}>流水</Typography.Title>
              <dl className="admin-detail-list">
                <div>
                  <dt>流水号</dt>
                  <dd>{detail.id}</dd>
                </div>
                <div>
                  <dt>用户</dt>
                  <dd>{maskUserId(detail.userId)}</dd>
                </div>
                <div>
                  <dt>流水类型</dt>
                  <dd>
                    <Tag>{detail.entryType}</Tag>
                  </dd>
                </div>
                <div>
                  <dt>业务类型</dt>
                  <dd>
                    <Tag>{detail.businessType}</Tag>
                  </dd>
                </div>
                <div>
                  <dt>业务单号</dt>
                  <dd>
                    <Value value={detail.businessId} />
                  </dd>
                </div>
              </dl>
            </section>
            <section>
              <Typography.Title level={3}>积分变动</Typography.Title>
              <dl className="admin-detail-list">
                <div>
                  <dt>可用积分变动</dt>
                  <dd>{formatSignedPoints(detail.availableDelta)}</dd>
                </div>
                <div>
                  <dt>冻结积分变动</dt>
                  <dd>{formatSignedPoints(detail.frozenDelta)}</dd>
                </div>
                <div>
                  <dt>可用余额（变动后）</dt>
                  <dd>{formatPoints(detail.availableAfter)}</dd>
                </div>
                <div>
                  <dt>冻结余额（变动后）</dt>
                  <dd>{formatPoints(detail.frozenAfter)}</dd>
                </div>
              </dl>
            </section>
            <section>
              <Typography.Title level={3}>操作与原因</Typography.Title>
              <dl className="admin-detail-list">
                <div>
                  <dt>操作人</dt>
                  <dd>
                    <Value value={detail.operatorId ? maskUserId(detail.operatorId) : undefined} />
                  </dd>
                </div>
                <div>
                  <dt>原因</dt>
                  <dd>
                    <Value value={detail.reason} />
                  </dd>
                </div>
                <div>
                  <dt>发生时间</dt>
                  <dd>{formatDateTime(detail.createdAt)}</dd>
                </div>
              </dl>
            </section>
            <Alert
              type="info"
              showIcon
              title="该流水由服务端写入，页面不可修改"
              description="余额与合计以服务端返回为准，页面不按当前分页推算。"
            />
          </div>
        ) : null}
      </Drawer>
    </section>
  )
}

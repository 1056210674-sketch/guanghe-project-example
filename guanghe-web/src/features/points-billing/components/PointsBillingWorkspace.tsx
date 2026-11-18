import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  SearchOutlined,
  SafetyCertificateOutlined,
  StarFilled,
} from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  Pagination,
  Select,
  Spin,
  Tag,
  Typography,
} from 'antd'
import { useMemo, useState } from 'react'
import type {
  PointsBalanceSummary,
  PointsLedgerEntry,
  PointsLedgerStatus,
  PointsLedgerType,
} from '../types'

/**
 * The balance and ledger half of the billing page.
 *
 * Recharge, invoice and feedback live in `src/features/billing`: they are separate API surfaces and
 * mixing them into one workspace made it possible to render a recharge button from a fixture without
 * anyone noticing. What is left here only ever renders what the point account and the point ledger
 * returned.
 */

const LEDGER_TYPE_META: Record<PointsLedgerType, { label: string; color: string }> = {
  RECHARGE: { label: '充值', color: 'green' },
  CONSUMPTION: { label: '消费', color: 'red' },
  FREEZE: { label: '冻结', color: 'orange' },
  UNFREEZE: { label: '解冻', color: 'blue' },
  REFUND: { label: '退回', color: 'cyan' },
  GIFT: { label: '赠送', color: 'purple' },
  ADJUSTMENT: { label: '调整', color: 'default' },
}

const LEDGER_STATUS_META: Record<
  PointsLedgerStatus,
  { label: string; color: string; icon: React.ReactNode }
> = {
  COMPLETED: {
    label: '已完成',
    color: 'success',
    icon: <CheckCircleOutlined />,
  },
  PENDING: {
    label: '处理中',
    color: 'processing',
    icon: <ClockCircleOutlined />,
  },
  FAILED: {
    label: '失败',
    color: 'error',
    icon: <CloseCircleOutlined />,
  },
}

function isNonNegativeSafeInteger(value: number | undefined): value is number {
  return value !== undefined && Number.isSafeInteger(value) && value >= 0
}

function isSafeInteger(value: number): boolean {
  return Number.isSafeInteger(value)
}

function formatPoints(value: number | undefined): string {
  return isNonNegativeSafeInteger(value) ? value.toLocaleString('zh-CN') : '—'
}

function formatPointsChange(value: number): string {
  if (!isSafeInteger(value)) {
    return '—'
  }

  return `${value > 0 ? '+' : ''}${value.toLocaleString('zh-CN')}`
}

export function PointsBillingHeader() {
  return (
    <header className="points-workspace-heading">
      <div>
        <Typography.Title level={1} id="points-workspace-title">
          积分与账单
        </Typography.Title>
        <Typography.Text type="secondary">
          查看真实积分余额、冻结金额与账务变动记录
        </Typography.Text>
      </div>
      <Tag icon={<SafetyCertificateOutlined />} color="default">
        账务数据以服务端为准
      </Tag>
    </header>
  )
}

export function PointsBalanceOverview({ summary }: { summary?: PointsBalanceSummary }) {
  const hasExpiry =
    isNonNegativeSafeInteger(summary?.expiringPoints) &&
    summary.expiringPoints > 0 &&
    Boolean(summary.expiresAt?.trim())

  return (
    <section className="points-overview" aria-labelledby="points-balance-title">
      <Card className="points-balance-card" variant="borderless">
        <div className="points-balance-card__icon" aria-hidden="true">
          <StarFilled />
        </div>
        <div>
          <Typography.Text id="points-balance-title">当前可用积分</Typography.Text>
          <div className="points-balance-card__value">
            {formatPoints(summary?.availablePoints)}
          </div>
          <Typography.Text>
            积分数据以服务端账务为准，不支持前端修改
          </Typography.Text>
        </div>
      </Card>

      <div className="points-summary-grid" aria-label="积分账户摘要">
        <Card size="small">
          <Typography.Text type="secondary">冻结积分</Typography.Text>
          <strong>{formatPoints(summary?.frozenPoints)}</strong>
          <span>任务完成或失败后由服务端结算</span>
        </Card>
        <Card size="small">
          <Typography.Text type="secondary">累计获得</Typography.Text>
          <strong>{formatPoints(summary?.totalEarnedPoints)}</strong>
          <span>充值、赠送及补偿积分</span>
        </Card>
        <Card size="small">
          <Typography.Text type="secondary">累计消耗</Typography.Text>
          <strong>{formatPoints(summary?.totalConsumedPoints)}</strong>
          <span>已完成任务的实际结算</span>
        </Card>
        <Card size="small">
          <Typography.Text type="secondary">即将过期</Typography.Text>
          <strong>{formatPoints(summary?.expiringPoints)}</strong>
          <span>{hasExpiry ? `${summary.expiresAt} 到期` : '暂无到期数据'}</span>
        </Card>
      </div>
    </section>
  )
}

export function PointsLedgerPanel({
  entries,
  loading = false,
  error,
  pagination,
  onPageChange,
  onRetry,
  emptyMessage,
}: {
  entries: readonly PointsLedgerEntry[]
  loading?: boolean
  error?: string
  pagination?: { current: number; pageSize: number; total: number }
  onPageChange?: (page: number, pageSize: number) => void
  onRetry?: () => void
  emptyMessage?: string
}) {
  const [searchValue, setSearchValue] = useState('')
  const [type, setType] = useState<PointsLedgerType | 'ALL'>('ALL')
  const availableTypes = useMemo(
    () => [...new Set(entries.map((entry) => entry.type))],
    [entries],
  )
  const visibleEntries = useMemo(() => {
    const keyword = searchValue.trim().toLocaleLowerCase('zh-CN')
    return entries.filter((entry) => {
      const matchesType = type === 'ALL' || entry.type === type
      const matchesKeyword =
        keyword.length === 0 ||
        [entry.title, entry.description, entry.referenceNo]
          .filter(Boolean)
          .some((value) => value?.toLocaleLowerCase('zh-CN').includes(keyword))
      return matchesType && matchesKeyword
    })
  }, [entries, searchValue, type])

  return (
    <Card className="points-panel-card" title="积分明细" variant="borderless">
      <div className="points-filter-bar">
        <Input
          aria-label="搜索积分明细"
          prefix={<SearchOutlined />}
          placeholder="搜索用途或关联单号"
          value={searchValue}
          onChange={(event) => setSearchValue(event.target.value)}
          disabled={entries.length === 0}
          allowClear
        />
        <Select
          aria-label="积分类型"
          value={type}
          onChange={setType}
          disabled={entries.length === 0}
          options={[
            { label: '全部类型', value: 'ALL' },
            ...availableTypes.map((entryType) => ({
              label: LEDGER_TYPE_META[entryType].label,
              value: entryType,
            })),
          ]}
        />
      </div>

      {error ? (
        <Alert
          type="error"
          showIcon
          title={error}
          action={onRetry ? <Button type="link" onClick={onRetry}>重新加载</Button> : undefined}
        />
      ) : loading ? (
        <Spin description="正在加载积分明细…" />
      ) : entries.length === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={
            <div className="points-empty-copy">
              <strong>{emptyMessage ?? '暂无积分明细'}</strong>
              {!emptyMessage && <span>当前账户暂无积分记录</span>}
            </div>
          }
        />
      ) : visibleEntries.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有匹配的积分记录" />
      ) : (
        <div className="points-table-scroll">
          <table className="points-data-table" aria-label="积分明细列表">
            <thead>
              <tr>
                <th scope="col">时间</th>
                <th scope="col">类型</th>
                <th scope="col">事项</th>
                <th scope="col">积分变动</th>
                <th scope="col">变动后余额</th>
                <th scope="col">状态</th>
                <th scope="col">关联单号</th>
              </tr>
            </thead>
            <tbody>
              {visibleEntries.map((entry) => {
                const typeMeta = LEDGER_TYPE_META[entry.type]
                const statusMeta = LEDGER_STATUS_META[entry.status]
                return (
                  <tr key={entry.id}>
                    <td>{entry.occurredAt}</td>
                    <td>
                      <Tag color={typeMeta.color}>{typeMeta.label}</Tag>
                    </td>
                    <th scope="row">
                      <strong>{entry.title}</strong>
                      <span>{entry.description?.trim() || '—'}</span>
                    </th>
                    <td
                      className={
                        isSafeInteger(entry.pointsChange) && entry.pointsChange > 0
                          ? 'is-positive'
                          : isSafeInteger(entry.pointsChange) && entry.pointsChange < 0
                            ? 'is-negative'
                            : undefined
                      }
                    >
                      {formatPointsChange(entry.pointsChange)}
                    </td>
                    <td>{formatPoints(entry.balanceAfter)}</td>
                    <td>
                      <Tag icon={statusMeta.icon} color={statusMeta.color}>
                        {statusMeta.label}
                      </Tag>
                    </td>
                    <td>{entry.referenceNo?.trim() || '—'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      {pagination && pagination.total > 0 ? (
        <Pagination
          current={pagination.current}
          pageSize={pagination.pageSize}
          total={pagination.total}
          showSizeChanger
          onChange={onPageChange}
        />
      ) : null}
    </Card>
  )
}

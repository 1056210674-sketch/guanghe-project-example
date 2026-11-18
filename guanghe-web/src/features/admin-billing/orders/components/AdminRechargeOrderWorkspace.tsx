import {
  DatabaseOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  SyncOutlined,
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
  Space,
  Tag,
  Typography,
} from 'antd'
import { useState } from 'react'
import { ErrorState, PageLoading } from '../../../../components/GlobalState'
import { formatCents, formatDateTime, formatPoints, maskUserId, type AdminErrorView } from '../../format'
import {
  CODE_URL_NOTICE,
  POINTS_SNAPSHOT_NOTICE,
  RECONCILE_NOTICE,
  RECHARGE_ORDER_STATUSES,
  RECHARGE_ORDER_STATUS_META,
  RETURN_CONTEXT_NOTICE,
  type AdminReconcileOutcome,
  type AdminRechargeOrderStatus,
  type AdminRechargeOrderView,
} from '../types'

const ALL_STATUSES = 'ALL'

/** A missing value stays visibly missing instead of becoming an invented one. */
function text(value: string | null | undefined): string {
  const trimmed = value?.trim()
  return trimmed ? trimmed : '—'
}

interface StatusSelectOption {
  value: AdminRechargeOrderStatus | typeof ALL_STATUSES
  label: string
}

const STATUS_SELECT_OPTIONS: StatusSelectOption[] = [
  { value: ALL_STATUSES, label: '全部状态' },
  ...RECHARGE_ORDER_STATUSES.map((status) => ({
    value: status,
    label: `${status}｜${RECHARGE_ORDER_STATUS_META[status].label}`,
  })),
]

export interface AdminRechargeOrdersWorkspaceProps {
  orders: readonly AdminRechargeOrderView[]
  /** The applied user filter, mirrored back so the column can say whose page this is. */
  userId: string
  status: AdminRechargeOrderStatus | undefined
  page: number
  pageSize: number
  total: number
  loading: boolean
  error: AdminErrorView | null
  onUserIdChange: (userId: string) => void
  onStatusChange: (status: AdminRechargeOrderStatus | undefined) => void
  onPageChange: (page: number) => void
  onRetry: () => void
  onOpenOrder: (orderId: string) => void
  detail?: AdminRechargeOrderView
  detailLoading: boolean
  detailError: AdminErrorView | null
  onCloseDetail: () => void
  onRetryDetail: () => void
  onReconcile: (orderId: string) => void
  reconcilePending: boolean
  reconcileError: AdminErrorView | null
  reconcileOutcome: AdminReconcileOutcome | null
}

export function AdminRechargeOrdersWorkspace(props: AdminRechargeOrdersWorkspaceProps) {
  const [userIdInput, setUserIdInput] = useState(props.userId)
  const appliedStatus = props.status ?? ALL_STATUSES
  const selectedOrderId = props.detail?.id

  const resetFilters = () => {
    setUserIdInput('')
    props.onUserIdChange('')
    props.onStatusChange(undefined)
  }

  const reconcileDisabled = props.reconcilePending || !selectedOrderId
  const reconcileDisabledReason = props.reconcilePending
    ? '正在等待服务端返回渠道答复，请稍候。'
    : !selectedOrderId
      ? '尚未选中订单，无法对账。'
      : null

  return (
    <section className="admin-workspace" aria-labelledby="admin-recharge-orders-title">
      <header className="admin-workspace__heading">
        <div>
          <Typography.Title id="admin-recharge-orders-title" level={1}>
            充值订单
          </Typography.Title>
          <Typography.Text type="secondary">
            按用户与状态查询充值订单，查看渠道原文并对账
          </Typography.Text>
        </div>
        <Tag icon={<SafetyCertificateOutlined />} color="red">
          管理员专用
        </Tag>
      </header>

      <Alert
        type="info"
        showIcon
        title="订单状态原样展示"
        description={`状态列渲染服务端返回的原始状态码，CLOSED（已关闭）与 EXPIRED（已过期）是两个不同状态，不会合并。${RECONCILE_NOTICE}`}
      />

      {props.reconcileError ? (
        <Alert
          type="error"
          showIcon
          title="对账请求失败"
          description={
            <div>
              <div>{props.reconcileError.message}</div>
              <div>未收到渠道答复，因此没有记录任何对账结果，也没有任何余额变化。</div>
              {props.reconcileError.traceId ? <div>traceId：{props.reconcileError.traceId}</div> : null}
            </div>
          }
        />
      ) : null}

      {props.reconcileOutcome ? (
        <Alert
          type={props.reconcileOutcome.statusChanged === true ? 'success' : 'warning'}
          showIcon
          title={reconcileOutcomeTitle(props.reconcileOutcome)}
          description={
            <div>
              <div>{reconcileOutcomeDescription(props.reconcileOutcome)}</div>
              <div>{RECONCILE_NOTICE}</div>
            </div>
          }
        />
      ) : null}

      <Card className="admin-data-card" variant="borderless">
        <div className="admin-filter-bar admin-filter-bar--recharge">
          <Input
            allowClear
            prefix={<SearchOutlined />}
            aria-label="用户 ID"
            placeholder="按用户 ID 精确筛选"
            value={userIdInput}
            onChange={(event) => setUserIdInput(event.target.value)}
            onPressEnter={() => props.onUserIdChange(userIdInput.trim())}
          />
          <Select<AdminRechargeOrderStatus | typeof ALL_STATUSES>
            aria-label="订单状态"
            value={appliedStatus}
            options={STATUS_SELECT_OPTIONS}
            onChange={(value) =>
              props.onStatusChange(value === ALL_STATUSES ? undefined : value)
            }
          />
          <Button
            type="primary"
            aria-label="查询"
            onClick={() => props.onUserIdChange(userIdInput.trim())}
          >
            查询
          </Button>
          <Button aria-label="重置" onClick={resetFilters}>
            重置
          </Button>
        </div>

        <div className="admin-billing-inline-note">
          <Typography.Text type="secondary">
            订单契约不返回下单用户标识：只有按用户 ID 筛选时，服务端才保证本页每一行都属于该账号，
            用户列因此显示该筛选账号（脱敏）；未筛选时显示 —。{POINTS_SNAPSHOT_NOTICE}
          </Typography.Text>
        </div>

        {props.loading ? (
          <PageLoading label="正在加载充值订单…" />
        ) : props.error ? (
          <ErrorState
            message={props.error.message}
            traceId={props.error.traceId}
            onRetry={props.onRetry}
          />
        ) : props.orders.length === 0 ? (
          <Empty
            image={<DatabaseOutlined />}
            description={
              <div className="admin-empty-copy">
                <strong>暂无充值订单</strong>
                <span>
                  服务端在当前筛选条件下没有返回订单；请调整用户 ID 或状态后重新查询。
                </span>
              </div>
            }
          />
        ) : (
          <>
            <div className="admin-table-scroll">
              <table className="admin-data-table" aria-label="充值订单列表">
                <thead>
                  <tr>
                    <th scope="col">订单号</th>
                    <th scope="col">用户</th>
                    <th scope="col">状态</th>
                    <th scope="col">金额</th>
                    <th scope="col">积分</th>
                    <th scope="col">渠道</th>
                    <th scope="col">渠道单号</th>
                    <th scope="col">创建时间</th>
                    <th scope="col">过期时间</th>
                    <th scope="col">支付时间</th>
                    <th scope="col">关闭时间</th>
                    <th scope="col">失败时间</th>
                    <th scope="col">失败码</th>
                    <th scope="col">来源</th>
                    <th scope="col">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {props.orders.map((order) => (
                    <tr key={order.id}>
                      <td>
                        <strong>{order.orderNo}</strong>
                      </td>
                      <td>{order.scopedUserId ? maskUserId(order.scopedUserId) : '—'}</td>
                      <td>
                        <Tag color={RECHARGE_ORDER_STATUS_META[order.status].color}>
                          {order.status}
                        </Tag>
                      </td>
                      <td>{formatCents(order.amountCents)}</td>
                      <td>{formatPoints(order.points)}</td>
                      <td>{text(order.provider)}</td>
                      <td>{text(order.providerOrderNo)}</td>
                      <td>{formatDateTime(order.createdAt)}</td>
                      <td>{formatDateTime(order.expiresAt)}</td>
                      <td>{formatDateTime(order.paidAt)}</td>
                      <td>{formatDateTime(order.closedAt)}</td>
                      <td>{formatDateTime(order.failedAt)}</td>
                      <td>{text(order.failureCode)}</td>
                      <td>{text(order.returnContext)}</td>
                      <td>
                        <Button type="link" onClick={() => props.onOpenOrder(order.id)}>
                          查看订单
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="admin-observability-footer admin-recharge-pagination-footer">
              <span>共 {formatPoints(props.total)} 条</span>
              <Pagination
                current={props.page}
                pageSize={props.pageSize}
                total={props.total}
                showSizeChanger={false}
                onChange={props.onPageChange}
              />
            </div>
          </>
        )}
      </Card>

      <Drawer
        title={props.detail ? `订单 ${props.detail.orderNo}` : '订单详情'}
        size={560}
        open={Boolean(selectedOrderId) || props.detailLoading || Boolean(props.detailError)}
        onClose={props.onCloseDetail}
      >
        {props.detailLoading ? (
          <PageLoading label="正在加载订单详情…" />
        ) : props.detailError ? (
          <ErrorState
            message={props.detailError.message}
            traceId={props.detailError.traceId}
            onRetry={props.onRetryDetail}
          />
        ) : props.detail ? (
          <div className="admin-detail-stack">
            <section>
              <Typography.Title level={3}>订单</Typography.Title>
              <dl className="admin-detail-list">
                <div>
                  <dt>订单 ID</dt>
                  <dd>{props.detail.id}</dd>
                </div>
                <div>
                  <dt>订单号</dt>
                  <dd>{props.detail.orderNo}</dd>
                </div>
                <div>
                  <dt>状态</dt>
                  <dd>
                    <Tag color={RECHARGE_ORDER_STATUS_META[props.detail.status].color}>
                      {props.detail.status}
                    </Tag>{' '}
                    {RECHARGE_ORDER_STATUS_META[props.detail.status].label}
                  </dd>
                </div>
                <div>
                  <dt>用户</dt>
                  <dd>
                    {props.detail.scopedUserId
                      ? `${maskUserId(props.detail.scopedUserId)}（来自列表筛选，订单契约不返回用户标识）`
                      : '—（订单契约不返回用户标识；按用户 ID 筛选后可标明归属）'}
                  </dd>
                </div>
                <div>
                  <dt>金额</dt>
                  <dd>{formatCents(props.detail.amountCents)}</dd>
                </div>
                <div>
                  <dt>金额（分）</dt>
                  <dd>{props.detail.amountCents}</dd>
                </div>
                <div>
                  <dt>积分（下单快照）</dt>
                  <dd>{formatPoints(props.detail.points)}</dd>
                </div>
              </dl>
            </section>

            <section>
              <Typography.Title level={3}>渠道</Typography.Title>
              <dl className="admin-detail-list">
                <div>
                  <dt>渠道 provider</dt>
                  <dd>{text(props.detail.provider)}</dd>
                </div>
                <div>
                  <dt>渠道单号</dt>
                  <dd>{text(props.detail.providerOrderNo)}</dd>
                </div>
                <div>
                  <dt>付款码 codeUrl</dt>
                  <dd>{text(props.detail.codeUrl)}</dd>
                </div>
                <div>
                  <dt>来源 returnContext</dt>
                  <dd>{text(props.detail.returnContext)}</dd>
                </div>
                <div>
                  <dt>失败码</dt>
                  <dd>{text(props.detail.failureCode)}</dd>
                </div>
              </dl>
              <Alert type="warning" showIcon title="付款码说明" description={CODE_URL_NOTICE} />
              <Alert type="info" showIcon title="来源标记说明" description={RETURN_CONTEXT_NOTICE} />
            </section>

            <section>
              <Typography.Title level={3}>时间</Typography.Title>
              <dl className="admin-detail-list">
                <div>
                  <dt>创建时间</dt>
                  <dd>{formatDateTime(props.detail.createdAt)}</dd>
                </div>
                <div>
                  <dt>过期时间</dt>
                  <dd>{formatDateTime(props.detail.expiresAt)}</dd>
                </div>
                <div>
                  <dt>支付时间</dt>
                  <dd>{formatDateTime(props.detail.paidAt)}</dd>
                </div>
                <div>
                  <dt>关闭时间</dt>
                  <dd>{formatDateTime(props.detail.closedAt)}</dd>
                </div>
                <div>
                  <dt>失败时间</dt>
                  <dd>{formatDateTime(props.detail.failedAt)}</dd>
                </div>
                <div>
                  <dt>更新时间</dt>
                  <dd>{formatDateTime(props.detail.updatedAt)}</dd>
                </div>
              </dl>
            </section>

            <section>
              <Typography.Title level={3}>对账</Typography.Title>
              <Alert type="info" showIcon title="对账说明" description={RECONCILE_NOTICE} />
              {props.reconcileOutcome && props.reconcileOutcome.order.id === props.detail.id ? (
                <Alert
                  type={props.reconcileOutcome.statusChanged === true ? 'success' : 'warning'}
                  showIcon
                  title={reconcileOutcomeTitle(props.reconcileOutcome)}
                  description={reconcileOutcomeDescription(props.reconcileOutcome)}
                />
              ) : null}
              <Space wrap>
                <Button
                  icon={<SyncOutlined />}
                  aria-label="对账"
                  disabled={reconcileDisabled}
                  onClick={() => {
                    if (selectedOrderId) props.onReconcile(selectedOrderId)
                  }}
                >
                  {props.reconcilePending ? '正在对账…' : '对账'}
                </Button>
                <Button
                  icon={<ReloadOutlined />}
                  aria-label="重新读取订单"
                  disabled={props.detailLoading}
                  onClick={props.onRetryDetail}
                >
                  重新读取订单
                </Button>
              </Space>
              {reconcileDisabledReason ? (
                <Typography.Text type="secondary">{reconcileDisabledReason}</Typography.Text>
              ) : (
                <Typography.Text type="secondary">
                  对账会向渠道发起查询并记录渠道的答复；结果只以服务端返回的订单为准。
                </Typography.Text>
              )}
            </section>
          </div>
        ) : null}
      </Drawer>
    </section>
  )
}

function reconcileOutcomeTitle(outcome: AdminReconcileOutcome): string {
  if (outcome.statusChanged === true) {
    return `渠道答复已记录：状态由 ${outcome.previousStatus} 变为 ${outcome.order.status}`
  }
  if (outcome.statusChanged === false) {
    return `渠道报告无变化：状态仍为 ${outcome.order.status}`
  }
  return `已记录渠道答复：状态为 ${outcome.order.status}`
}

function reconcileOutcomeDescription(outcome: AdminReconcileOutcome): string {
  if (outcome.statusChanged === true) {
    return `订单 ${outcome.order.orderNo} 的当前状态以本次渠道答复为准。是否入账取决于服务端账务记录：本次对账没有直接为账户增加积分。`
  }
  if (outcome.statusChanged === false) {
    return `订单 ${outcome.order.orderNo} 的状态与对账前一致，本次对账没有发生任何余额变化；只有经验证的回调或渠道确认的答复才会入账。`
  }
  return `订单 ${outcome.order.orderNo} 在对账前没有读到状态，因此无法判断是否变化；本次对账只记录渠道答复，不会改变余额。`
}

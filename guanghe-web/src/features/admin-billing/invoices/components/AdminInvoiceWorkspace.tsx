import { DatabaseOutlined, MailOutlined, SafetyCertificateOutlined, SearchOutlined } from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Input,
  Modal,
  Pagination,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd'
import { useState } from 'react'
import { ErrorState, PageLoading } from '../../../../components/GlobalState'
import { formatCents, formatDateTime, maskUserId, type AdminErrorView } from '../../format'
import {
  APPROVED_IS_NOT_ISSUED_NOTICE,
  DELIVERY_CAPABILITY_NOTICE,
  INVOICE_ACTIONS,
  INVOICE_ACTION_LABEL,
  INVOICE_APPLICATION_STATUSES,
  INVOICE_APPLICATION_STATUS_META,
  INVOICE_TITLE_TYPE_LABEL,
  INVOICE_TYPE_LABEL,
  REJECT_REASON_NOTICE,
  invoiceActionUnavailableReason,
  isInvoiceActionLegal,
  type AdminDeliveryRefusalView,
  type AdminInvoiceAction,
  type AdminInvoiceActionOutcome,
  type AdminInvoiceApplicationStatus,
  type AdminInvoiceApplicationView,
} from '../types'

const ALL_STATUSES = 'ALL'

/** A value the server did not send stays visibly missing. */
function text(value: string | null | undefined): string {
  const trimmed = value?.trim()
  return trimmed ? trimmed : '—'
}

function webhookStatusLabel(status: AdminInvoiceApplicationView['webhookStatus']): string {
  switch (status) {
    case 'PENDING': return '待推送飞书'
    case 'WAITING_CONFIG': return '飞书未配置，待推送'
    case 'WAITING_MANUAL': return '历史申请，待手动补推'
    case 'SENT': return '开票信息已推送飞书（非发票发出）'
    case 'SKIPPED': return '已跳过飞书推送'
  }
}

interface StatusSelectOption {
  value: AdminInvoiceApplicationStatus | typeof ALL_STATUSES
  label: string
}

const STATUS_SELECT_OPTIONS: StatusSelectOption[] = [
  { value: ALL_STATUSES, label: '全部状态' },
  ...INVOICE_APPLICATION_STATUSES.map((status) => ({
    value: status,
    label: `${status}｜${INVOICE_APPLICATION_STATUS_META[status].label}`,
  })),
]

export interface AdminInvoiceWorkspaceProps {
  applications: readonly AdminInvoiceApplicationView[]
  userId: string
  status: AdminInvoiceApplicationStatus | undefined
  page: number
  pageSize: number
  total: number
  loading: boolean
  error: AdminErrorView | null
  onUserIdChange: (userId: string) => void
  onStatusChange: (status: AdminInvoiceApplicationStatus | undefined) => void
  onPageChange: (page: number) => void
  onRetry: () => void
  onOpenApplication: (applicationId: string) => void
  detail?: AdminInvoiceApplicationView
  detailLoading: boolean
  detailError: AdminErrorView | null
  onCloseDetail: () => void
  onRetryDetail: () => void
  /** The transition the container is currently submitting, if any. */
  pendingAction?: AdminInvoiceAction
  webhookRetrying: boolean
  actionError: AdminErrorView | null
  /** The server's refusal of a delivery attempt, kept separate from ordinary failures. */
  deliveryRefusal: AdminDeliveryRefusalView | null
  /** The application as the server returned it after the last accepted transition. */
  actionOutcome: AdminInvoiceActionOutcome | null
  onAccept: (applicationId: string) => void
  onApprove: (applicationId: string) => void
  onReject: (applicationId: string, reason: string) => void
  onDeliver: (applicationId: string, invoiceNumber: string) => void
  onRetryWebhook: (applicationId: string) => void
}

export function AdminInvoiceWorkspace(props: AdminInvoiceWorkspaceProps) {
  const [userIdInput, setUserIdInput] = useState(props.userId)
  const [rejectOpen, setRejectOpen] = useState(false)
  const [rejectReason, setRejectReason] = useState('')
  const [manualSendOpen, setManualSendOpen] = useState(false)
  const [invoiceNumber, setInvoiceNumber] = useState('')

  const detail = props.detail
  const appliedStatus = props.status ?? ALL_STATUSES
  const legalActions = detail
    ? INVOICE_ACTIONS.filter((action) => isInvoiceActionLegal(action, detail.status))
    : []
  const unavailableActions = detail
    ? INVOICE_ACTIONS.filter((action) => !isInvoiceActionLegal(action, detail.status))
    : []
  const actionsBlockedReason = props.pendingAction
    ? `正在提交「${INVOICE_ACTION_LABEL[props.pendingAction]}」，请等待服务端响应后再继续。`
    : null
  const rejectReasonMissing = rejectReason.trim().length === 0
  const rejectDisabledReason = rejectReasonMissing
    ? '驳回原因尚未填写，因此无法提交；该原因会原样展示给申请人。'
    : null

  const resetFilters = () => {
    setUserIdInput('')
    props.onUserIdChange('')
    props.onStatusChange(undefined)
  }

  const submitReject = () => {
    if (!detail || rejectReasonMissing || props.pendingAction) return
    props.onReject(detail.id, rejectReason.trim())
    setRejectOpen(false)
    setRejectReason('')
  }

  const confirmManualSend = () => {
    if (!detail || !invoiceNumber.trim() || props.pendingAction) return
    props.onDeliver(detail.id, invoiceNumber.trim())
    setManualSendOpen(false)
    setInvoiceNumber('')
  }

  return (
    <section className="admin-workspace" aria-labelledby="admin-invoices-title">
      <header className="admin-workspace__heading">
        <div>
          <Typography.Title id="admin-invoices-title" level={1}>
            发票申请
          </Typography.Title>
          <Typography.Text type="secondary">
            按用户与状态查询发票申请；线下开票、人工发件后再记录确认
          </Typography.Text>
        </div>
        <Tag icon={<SafetyCertificateOutlined />} color="red">
          管理员专用
        </Tag>
      </header>

      <Alert type="warning" showIcon title="审核通过不等于已开票" description={APPROVED_IS_NOT_ISSUED_NOTICE} />

      {props.actionError ? (
        <Alert
          type="error"
          showIcon
          title="操作未完成"
          description={
            <div>
              <div>{props.actionError.message}</div>
              <div>未收到服务端的成功响应，因此本页没有改写申请状态。</div>
              {props.actionError.traceId ? <div>traceId：{props.actionError.traceId}</div> : null}
            </div>
          }
        />
      ) : null}

      {props.deliveryRefusal ? (
        <Alert
          type={props.deliveryRefusal.capability ? 'warning' : 'error'}
          showIcon
          title="人工发件确认未完成"
          description={
            <div>
              <div>{props.deliveryRefusal.message}</div>
              <div>
                服务端未确认该操作；请核实实际邮件与当前状态后再尝试，不能据此宣称客户已收件。
              </div>
              {props.deliveryRefusal.code ? <div>错误码：{props.deliveryRefusal.code}</div> : null}
              {props.deliveryRefusal.traceId ? (
                <div>traceId：{props.deliveryRefusal.traceId}</div>
              ) : null}
            </div>
          }
        />
      ) : null}

      {props.actionOutcome ? (
        <Alert
          type={props.actionOutcome.action === 'DELIVER' ? 'success' : 'info'}
          showIcon
          title={actionOutcomeTitle(props.actionOutcome)}
          description={actionOutcomeDescription(props.actionOutcome)}
        />
      ) : null}

      <Card className="admin-data-card" variant="borderless">
        <div className="admin-filter-bar">
          <Input
            allowClear
            prefix={<SearchOutlined />}
            aria-label="用户 ID"
            placeholder="按用户 ID 精确筛选"
            value={userIdInput}
            onChange={(event) => setUserIdInput(event.target.value)}
            onPressEnter={() => props.onUserIdChange(userIdInput.trim())}
          />
          <Select<AdminInvoiceApplicationStatus | typeof ALL_STATUSES>
            aria-label="申请状态"
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

        <Typography.Text className="admin-billing-inline-note" type="secondary">
          申请契约不返回申请人标识：只有按用户 ID 筛选时，服务端才保证本页每一行都属于该账号，
          用户列因此显示该筛选账号（脱敏）；未筛选时显示 —。
        </Typography.Text>

        {props.loading ? (
          <PageLoading label="正在加载发票申请…" />
        ) : props.error ? (
          <ErrorState
            message={props.error.message}
            traceId={props.error.traceId}
            onRetry={props.onRetry}
          />
        ) : props.applications.length === 0 ? (
          <Empty
            image={<DatabaseOutlined />}
            description={
              <div className="admin-empty-copy">
                <strong>暂无发票申请</strong>
                <span>服务端在当前筛选条件下没有返回申请；请调整用户 ID 或状态后重新查询。</span>
              </div>
            }
          />
        ) : (
          <>
            <div className="admin-table-scroll">
              <table className="admin-data-table" aria-label="发票申请列表">
                <thead>
                  <tr>
                    <th scope="col">申请号</th>
                    <th scope="col">用户</th>
                    <th scope="col">状态</th>
                    <th scope="col">发票类型</th>
                    <th scope="col">抬头</th>
                    <th scope="col">金额</th>
                    <th scope="col">申请邮箱</th>
                    <th scope="col">飞书通知</th>
                    <th scope="col">提交时间</th>
                    <th scope="col">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {props.applications.map((application) => (
                    <tr key={application.id}>
                      <td>
                        <strong>{application.applicationNo}</strong>
                      </td>
                      <td>
                        {application.scopedUserId ? maskUserId(application.scopedUserId) : '—'}
                      </td>
                      <td>
                        <Tag color={INVOICE_APPLICATION_STATUS_META[application.status].color}>
                          {application.status}
                        </Tag>
                      </td>
                      <td>{INVOICE_TYPE_LABEL[application.invoiceType]}</td>
                      <td>{text(application.title)}</td>
                      <td>{formatCents(application.amountCents)}</td>
                      <td>{text(application.deliveryEmail)}</td>
                      <td>{webhookStatusLabel(application.webhookStatus)}</td>
                      <td>{formatDateTime(application.submittedAt)}</td>
                      <td>
                        <Button type="link" onClick={() => props.onOpenApplication(application.id)}>
                          查看申请
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              current={props.page}
              pageSize={props.pageSize}
              total={props.total}
              showSizeChanger={false}
              onChange={props.onPageChange}
            />
          </>
        )}
      </Card>

      <Drawer
        title={detail ? `申请 ${detail.applicationNo}` : '申请详情'}
        size={560}
        open={Boolean(detail?.id) || props.detailLoading || Boolean(props.detailError)}
        onClose={props.onCloseDetail}
      >
        {props.detailLoading ? (
          <PageLoading label="正在加载申请详情…" />
        ) : props.detailError ? (
          <ErrorState
            message={props.detailError.message}
            traceId={props.detailError.traceId}
            onRetry={props.onRetryDetail}
          />
        ) : detail ? (
          <div className="admin-detail-stack">
            <section>
              <Typography.Title level={3}>申请</Typography.Title>
              <dl className="admin-detail-list">
                <div>
                  <dt>申请 ID</dt>
                  <dd>{detail.id}</dd>
                </div>
                <div>
                  <dt>申请号</dt>
                  <dd>{detail.applicationNo}</dd>
                </div>
                <div>
                  <dt>状态</dt>
                  <dd>
                    <Tag color={INVOICE_APPLICATION_STATUS_META[detail.status].color}>
                      {detail.status}
                    </Tag>{' '}
                    {detail.status === 'DELIVERED' && detail.deliveryProvider === 'MANUAL_EMAIL'
                      ? '管理员已确认人工发件（非客户收件回执）'
                      : INVOICE_APPLICATION_STATUS_META[detail.status].label}
                  </dd>
                </div>
                <div>
                  <dt>用户</dt>
                  <dd>
                    {detail.scopedUserId
                      ? `${maskUserId(detail.scopedUserId)}（来自列表筛选，申请契约不返回申请人标识）`
                      : '—（申请契约不返回申请人标识；按用户 ID 筛选后可标明归属）'}
                  </dd>
                </div>
              </dl>
            </section>

            <section>
              <Typography.Title level={3}>开票信息</Typography.Title>
              <dl className="admin-detail-list">
                <div>
                  <dt>发票类型</dt>
                  <dd>
                    {INVOICE_TYPE_LABEL[detail.invoiceType]}（{detail.invoiceType}）
                  </dd>
                </div>
                <div>
                  <dt>抬头类型</dt>
                  <dd>
                    {INVOICE_TITLE_TYPE_LABEL[detail.titleType]}（{detail.titleType}）
                  </dd>
                </div>
                <div>
                  <dt>抬头</dt>
                  <dd>{text(detail.title)}</dd>
                </div>
                <div>
                  <dt>税号</dt>
                  <dd>{text(detail.taxNumber)}</dd>
                </div>
                <div>
                  <dt>金额</dt>
                  <dd>{formatCents(detail.amountCents)}</dd>
                </div>
                <div>
                  <dt>金额（分）</dt>
                  <dd>{detail.amountCents}</dd>
                </div>
                <div>
                  <dt>申请邮箱</dt>
                  <dd>{text(detail.deliveryEmail)}</dd>
                </div>
                <div>
                  <dt>备注</dt>
                  <dd>{text(detail.remark)}</dd>
                </div>
                <div>
                  <dt>驳回原因</dt>
                  <dd>{text(detail.rejectReason)}</dd>
                </div>
              </dl>
            </section>

            <section>
              <Typography.Title level={3}>飞书申请通知</Typography.Title>
              <dl className="admin-detail-list">
                <div><dt>状态</dt><dd>{webhookStatusLabel(detail.webhookStatus)}</dd></div>
                <div><dt>错误码</dt><dd>{text(detail.webhookErrorCode)}</dd></div>
              </dl>
              {['PENDING', 'WAITING_CONFIG', 'WAITING_MANUAL'].includes(detail.webhookStatus) ? (
                <Button
                  disabled={props.webhookRetrying || Boolean(props.pendingAction)}
                  loading={props.webhookRetrying}
                  onClick={() => props.onRetryWebhook(detail.id)}
                >
                  重试推送飞书
                </Button>
              ) : null}
              {detail.webhookStatus === 'WAITING_CONFIG' ? (
                <Typography.Paragraph type="warning">飞书 Webhook 尚未配置；重试只会排队，不表示管理员已收到通知。</Typography.Paragraph>
              ) : null}
            </section>

            <section>
              <Typography.Title level={3}>人工发件记录</Typography.Title>
              <dl className="admin-detail-list">
                <div>
                  <dt>发件渠道</dt>
                  <dd>{text(detail.deliveryProvider)}</dd>
                </div>
                <div>
                  <dt>发票号</dt>
                  <dd>{text(detail.invoiceNumber)}</dd>
                </div>
                <div>
                  <dt>确认操作人 ID</dt>
                  <dd>{text(detail.manualSentBy)}</dd>
                </div>
                <div>
                  <dt>管理员确认时间</dt>
                  <dd>
                    {formatDateTime(detail.deliveredAt)}
                    {detail.deliveredAt
                      ? detail.deliveryProvider === 'MANUAL_EMAIL'
                        ? '（管理员确认人工发出；不代表申请人实际收件）'
                        : '（历史完成记录，发送方式须核实；不代表客户收件）'
                      : '（尚未确认人工发出）'}
                  </dd>
                </div>
              </dl>
            </section>

            <section>
              <Typography.Title level={3}>时间</Typography.Title>
              <dl className="admin-detail-list">
                <div>
                  <dt>提交时间</dt>
                  <dd>{formatDateTime(detail.submittedAt)}</dd>
                </div>
                <div>
                  <dt>审核时间</dt>
                  <dd>{formatDateTime(detail.reviewedAt)}</dd>
                </div>
                <div>
                  <dt>审核通过时间</dt>
                  <dd>
                    {formatDateTime(detail.approvedAt)}
                    {detail.approvedAt ? '（内部审核通过，不是开票时间）' : ''}
                  </dd>
                </div>
                <div>
                  <dt>管理员确认发件时间</dt>
                  <dd>{formatDateTime(detail.deliveredAt)}</dd>
                </div>
                <div>
                  <dt>驳回时间</dt>
                  <dd>{formatDateTime(detail.rejectedAt)}</dd>
                </div>
                <div>
                  <dt>取消时间</dt>
                  <dd>{formatDateTime(detail.cancelledAt)}</dd>
                </div>
                <div>
                  <dt>创建时间</dt>
                  <dd>{formatDateTime(detail.createdAt)}</dd>
                </div>
                <div>
                  <dt>更新时间</dt>
                  <dd>{formatDateTime(detail.updatedAt)}</dd>
                </div>
              </dl>
            </section>

            <section>
              <Typography.Title level={3}>状态流转</Typography.Title>
              <Alert type="info" showIcon title="人工发件说明" description={DELIVERY_CAPABILITY_NOTICE} />
              {actionsBlockedReason ? (
                <Typography.Text type="secondary">{actionsBlockedReason}</Typography.Text>
              ) : null}
              {legalActions.length === 0 ? (
                <Typography.Text type="secondary">
                  当前状态为 {detail.status}，没有可执行的状态流转操作。
                </Typography.Text>
              ) : (
                <Space wrap>
                  {legalActions.map((action) => (
                    <Button
                      key={action}
                      icon={action === 'DELIVER' ? <MailOutlined /> : undefined}
                      aria-label={INVOICE_ACTION_LABEL[action]}
                      danger={action === 'REJECT'}
                      disabled={Boolean(props.pendingAction)}
                      onClick={() => {
                        if (action === 'ACCEPT') props.onAccept(detail.id)
                        else if (action === 'APPROVE') props.onApprove(detail.id)
                        else if (action === 'DELIVER') {
                          setInvoiceNumber('')
                          setManualSendOpen(true)
                        }
                        else {
                          setRejectReason('')
                          setRejectOpen(true)
                        }
                      }}
                    >
                      {INVOICE_ACTION_LABEL[action]}
                    </Button>
                  ))}
                </Space>
              )}
              {unavailableActions.length === 0 ? null : (
                <Alert
                  type="info"
                  showIcon
                  title="当前状态不提供的操作及原因"
                  description={
                    <ul className="admin-compact-list">
                      {unavailableActions.map((action) => (
                        <li key={action}>
                          <div>
                            <strong>{INVOICE_ACTION_LABEL[action]}</strong>
                            <span>{invoiceActionUnavailableReason(action, detail.status)}</span>
                          </div>
                        </li>
                      ))}
                    </ul>
                  }
                />
              )}
            </section>
          </div>
        ) : null}
      </Drawer>

      <Modal
        title="确认已线下开票并人工发送"
        open={manualSendOpen}
        okText="确认已人工发出"
        cancelText="取消"
        okButtonProps={{ disabled: !invoiceNumber.trim() || Boolean(props.pendingAction) }}
        confirmLoading={props.pendingAction === 'DELIVER'}
        onOk={confirmManualSend}
        onCancel={() => { setManualSendOpen(false); setInvoiceNumber('') }}
      >
        <Typography.Paragraph>
          仅在已线下开具发票，并已从外部邮箱人工发送到申请邮箱后确认。此记录不证明申请人实际收件。
        </Typography.Paragraph>
        <Typography.Paragraph>申请邮箱：{text(detail?.deliveryEmail)}</Typography.Paragraph>
        <label>发票号（必填）</label>
        <Input
          aria-label="发票号"
          maxLength={64}
          value={invoiceNumber}
          onChange={(event) => setInvoiceNumber(event.target.value)}
        />
      </Modal>

      <Modal
        title="驳回发票申请"
        open={rejectOpen}
        okText="确认驳回"
        cancelText="取消"
        okButtonProps={{
          danger: true,
          disabled: rejectReasonMissing || Boolean(props.pendingAction),
        }}
        confirmLoading={props.pendingAction === 'REJECT'}
        onOk={submitReject}
        onCancel={() => {
          setRejectOpen(false)
          setRejectReason('')
        }}
      >
        <div className="admin-modal-form">
          <Typography.Paragraph>{REJECT_REASON_NOTICE}</Typography.Paragraph>
          <label>
            <span>驳回原因（必填）</span>
            <Input.TextArea
              value={rejectReason}
              aria-label="驳回原因"
              placeholder="请输入会展示给申请人的驳回原因"
              onChange={(event) => setRejectReason(event.target.value)}
            />
          </label>
          {rejectDisabledReason ? (
            <Typography.Text type="secondary">{rejectDisabledReason}</Typography.Text>
          ) : (
            <Typography.Text type="secondary">
              提交后申请状态将变为 REJECTED，该原因会随申请一并展示给申请人。
            </Typography.Text>
          )}
        </div>
      </Modal>
    </section>
  )
}

function actionOutcomeTitle(outcome: AdminInvoiceActionOutcome): string {
  const status = outcome.application.status
  if (outcome.action === 'DELIVER') return `管理员人工发件确认已记录：状态为 ${status}`
  return `服务端已返回最新状态：${INVOICE_ACTION_LABEL[outcome.action]} → ${status}`
}

function actionOutcomeDescription(outcome: AdminInvoiceActionOutcome): string {
  const application = outcome.application
  if (outcome.action === 'APPROVE') {
    return `申请 ${application.applicationNo} 的内部审核已通过，服务端记录的审核通过时间为 ${formatDateTime(application.approvedAt)}。这还不是开票或发件。`
  }
  if (outcome.action === 'REJECT') {
    return `申请 ${application.applicationNo} 已被驳回，驳回原因为「${text(application.rejectReason)}」，该原因会展示给申请人。`
  }
  if (outcome.action === 'DELIVER') {
    return `申请 ${application.applicationNo} 的人工发件确认已写入：发票号 ${text(application.invoiceNumber)}，确认时间 ${formatDateTime(application.deliveredAt)}。不代表申请人实际收件。`
  }
  return `申请 ${application.applicationNo} 已受理：服务端返回的状态为 ${application.status}。受理只是进入审核，此时仍没有发票，也不代表已开票。`
}

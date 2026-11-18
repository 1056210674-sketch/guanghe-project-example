/**
 * 人工补录 — the two-step manual adjustment surface.
 *
 * Step one creates a PENDING_CONFIRMATION record and returns a confirmation token that exists in
 * that response only. Step two asks the operator to type that token back before 确认入账 can be
 * clicked, because a single click must never be able to move points.
 *
 * Everything here is presentational: the container owns the mutations, and hands this component the
 * view models, the pending flags, the error views and the callbacks.
 */
import {
  DatabaseOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  WarningOutlined,
} from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  InputNumber,
  Modal,
  Pagination,
  Skeleton,
  Tag,
  Typography,
} from 'antd'
import { useState } from 'react'
import { formatDateTime, formatPoints, maskUserId } from '../../format'
import type {
  AdminErrorView,
  PointAdjustmentDraft,
  PointAdjustmentView,
} from '../types'
import {
  CONFIRMATION_EXPIRED_MESSAGE,
  POINT_ADJUSTMENT_MIN_POINTS,
  POINT_ADJUSTMENT_STATUS_LABELS,
  isValidConfirmationToken,
} from '../types'

const STATUS_COLORS: Record<PointAdjustmentView['status'], string> = {
  PENDING_CONFIRMATION: 'processing',
  APPLIED: 'success',
  CANCELLED: 'default',
}

const CONFIRM_HINT_ID = 'admin-adjustment-confirm-hint'

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

interface ConfirmationDialogProps {
  adjustment: PointAdjustmentView
  pending: boolean
  error: AdminErrorView | null
  onCancel: () => void
  onConfirm: (confirmationToken: string) => void
}

function ConfirmationDialog({
  adjustment,
  pending,
  error,
  onCancel,
  onConfirm,
}: ConfirmationDialogProps) {
  const [typedToken, setTypedToken] = useState('')
  const expectedToken = adjustment.confirmationToken
  const typed = typedToken.trim()
  const expiryState = adjustment.confirmationExpiryState
  const patternValid = isValidConfirmationToken(typed)
  const matchesExpected = expectedToken === undefined || typed === expectedToken

  const blockedReason =
    expiryState === 'EXPIRED'
      ? CONFIRMATION_EXPIRED_MESSAGE
      : expiryState === 'UNKNOWN'
        ? '无法识别确认令牌的过期时间，请重新发起'
        : !typed
          ? '请输入确认令牌后才能确认入账'
          : !patternValid
            ? '确认令牌应为 43 位字母、数字、下划线或连字符'
            : !matchesExpected
              ? '输入的确认令牌与本次返回的令牌不一致'
              : undefined

  const canConfirm = blockedReason === undefined && !pending

  const copyToken = () => {
    if (!expectedToken) return
    void navigator.clipboard?.writeText(expectedToken)
  }

  return (
    <Modal
      open
      title="确认积分入账"
      width={560}
      maskClosable={false}
      okText="确认入账"
      cancelText="取消"
      confirmLoading={pending}
      okButtonProps={{
        disabled: !canConfirm,
        'aria-describedby': blockedReason ? CONFIRM_HINT_ID : undefined,
      }}
      onOk={() => {
        if (canConfirm) onConfirm(typed)
      }}
      onCancel={onCancel}
    >
      <div className="admin-modal-form">
        <Alert
          type="warning"
          showIcon
          title="这是第二步：确认后积分才会真正入账"
          description="第一步只创建了待确认记录，积分尚未变动。确认入账由服务端在同一事务内写入流水，之后不可撤销。"
        />
        <dl className="admin-detail-list">
          <div>
            <dt>用户</dt>
            <dd>{adjustment.userId}</dd>
          </div>
          <div>
            <dt>积分数额</dt>
            <dd>+{formatPoints(adjustment.points)}</dd>
          </div>
          <div>
            <dt>原因</dt>
            <dd>
              <Value value={adjustment.reason} />
            </dd>
          </div>
          <div>
            <dt>过期时间</dt>
            <dd>{formatDateTime(adjustment.confirmationExpiresAt)}</dd>
          </div>
          <div>
            <dt>当前状态</dt>
            <dd>
              <Tag color={STATUS_COLORS[adjustment.status]}>
                {POINT_ADJUSTMENT_STATUS_LABELS[adjustment.status]}
              </Tag>
            </dd>
          </div>
        </dl>

        {expectedToken ? (
          <Alert
            type="info"
            showIcon
            title="确认令牌（仅显示一次）"
            description="令牌只存在于创建响应中，服务端只保存它的哈希值：关闭本窗口后无法再次查看，请在此处复制。"
            action={
              <Button size="small" onClick={copyToken}>
                复制令牌
              </Button>
            }
          />
        ) : (
          <Alert
            type="info"
            showIcon
            title="确认令牌不在本次响应中"
            description="该待确认记录来自列表接口，服务端不会再次明文返回令牌。请粘贴发起时保存的令牌，服务端会校验它的哈希。"
          />
        )}

        {expectedToken ? (
          <Typography.Paragraph>
            <code data-testid="confirmation-token">{expectedToken}</code>
          </Typography.Paragraph>
        ) : null}

        <label>
          <span>确认令牌</span>
          <Input
            aria-label="确认令牌"
            autoComplete="off"
            placeholder="粘贴或输入 43 位确认令牌"
            value={typedToken}
            onChange={(event) => setTypedToken(event.target.value)}
          />
        </label>

        {expiryState !== 'VALID' ? (
          <Alert
            id={CONFIRM_HINT_ID}
            type="warning"
            showIcon
            title={blockedReason ?? CONFIRMATION_EXPIRED_MESSAGE}
            description="超时或过期时间不可识别的待确认补录无法入账，服务端同样会拒绝；请重新发起一笔补录，页面不会提供绕过校验的方式。"
          />
        ) : (
          <Typography.Text id={CONFIRM_HINT_ID} type="secondary">
            {blockedReason ?? '令牌校验通过，可确认入账。'}
          </Typography.Text>
        )}

        <ErrorNotice error={error} />
      </div>
    </Modal>
  )
}

export interface AdminAdjustmentWorkspaceProps {
  adjustments: readonly PointAdjustmentView[]
  total: number
  page: number
  pageSize: number
  loading: boolean
  error: AdminErrorView | null
  userFilter?: string
  requestPending: boolean
  requestError: AdminErrorView | null
  confirmation?: PointAdjustmentView
  confirmationPending: boolean
  confirmationError: AdminErrorView | null
  lastApplied?: PointAdjustmentView
  onUserFilterChange: (userId?: string) => void
  onPageChange: (page: number) => void
  onRequestAdjustment: (draft: PointAdjustmentDraft) => Promise<boolean>
  onResumeAdjustment: (adjustmentId: string) => void
  onCloseConfirmation: () => void
  onConfirmAdjustment: (adjustmentId: string, confirmationToken: string) => void
  onRetryList?: () => void
}

export function AdminAdjustmentWorkspace({
  adjustments,
  total,
  page,
  pageSize,
  loading,
  error,
  userFilter,
  requestPending,
  requestError,
  confirmation,
  confirmationPending,
  confirmationError,
  lastApplied,
  onUserFilterChange,
  onPageChange,
  onRequestAdjustment,
  onResumeAdjustment,
  onCloseConfirmation,
  onConfirmAdjustment,
  onRetryList,
}: AdminAdjustmentWorkspaceProps) {
  const [userId, setUserId] = useState('')
  const [points, setPoints] = useState<number | null>(null)
  const [reason, setReason] = useState('')
  const [userFilterDraft, setUserFilterDraft] = useState(userFilter ?? '')

  const trimmedUserId = userId.trim()
  const trimmedReason = reason.trim()
  const pointsValid =
    points !== null && Number.isSafeInteger(points) && points >= POINT_ADJUSTMENT_MIN_POINTS
  const missing = [
    trimmedUserId ? undefined : '用户 ID',
    pointsValid ? undefined : `积分（≥${POINT_ADJUSTMENT_MIN_POINTS} 的整数）`,
    trimmedReason ? undefined : '原因',
  ].filter((item): item is string => item !== undefined)
  const confirmationOpen = confirmation !== undefined
  const canSubmit = missing.length === 0 && !requestPending && !confirmationOpen

  const submitRequest = async () => {
    if (!canSubmit || points === null) return
    const accepted = await onRequestAdjustment({
      userId: trimmedUserId,
      points,
      reason: trimmedReason,
    })
    // The form is cleared only after the server answered with a pending record: a failed request
    // keeps the operator's input so nothing has to be retyped.
    if (accepted) {
      setUserId('')
      setPoints(null)
      setReason('')
    }
  }

  return (
    <section className="admin-workspace admin-adjustment" aria-labelledby="admin-adjustment-title">
      <header className="admin-workspace__heading">
        <div>
          <Typography.Title id="admin-adjustment-title" level={2}>
            人工补录
          </Typography.Title>
          <Typography.Text type="secondary">
            两段式补录：先创建待确认记录，再由操作人输入一次性令牌确认入账
          </Typography.Text>
        </div>
        <Tag icon={<SafetyCertificateOutlined />} color="red">
          管理员专用
        </Tag>
      </header>

      <Alert
        type="warning"
        showIcon
        title="第一步不会变动积分"
        description="提交后服务端返回一条待确认记录与一次性确认令牌，积分在第二步确认前不会入账。令牌只在创建响应中返回一次，页面不保存、不记录日志；关闭确认窗口后只能凭操作人自己保存的令牌继续。"
      />

      <Card className="admin-data-card" variant="borderless" title="第一步：发起补录（CREDIT）">
        <form
          className="admin-modal-form admin-adjustment-request-form"
          onSubmit={(event) => {
            event.preventDefault()
            void submitRequest()
          }}
        >
          <label>
            <span>用户 ID</span>
            <Input
              aria-label="用户 ID"
              autoComplete="off"
              placeholder="请输入要补录的用户 ID"
              value={userId}
              onChange={(event) => setUserId(event.target.value)}
            />
          </label>
          <label>
            <span>积分</span>
            <InputNumber
              aria-label="积分"
              min={POINT_ADJUSTMENT_MIN_POINTS}
              precision={0}
              value={points}
              placeholder={`请输入不小于 ${POINT_ADJUSTMENT_MIN_POINTS} 的整数`}
              onChange={setPoints}
            />
          </label>
          <label>
            <span>原因</span>
            <Input.TextArea
              aria-label="原因"
              placeholder="请输入可审计的补录原因"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
          <Button
            type="primary"
            htmlType="submit"
            loading={requestPending}
            disabled={!canSubmit}
            aria-describedby={!canSubmit ? 'admin-adjustment-submit-hint' : undefined}
          >
            发起补录
          </Button>
          <Typography.Text id="admin-adjustment-submit-hint" type="secondary">
            {confirmationOpen
              ? '存在待确认的补录，请先完成或取消该确认后再发起新的补录。'
              : missing.length > 0
                ? `发起补录前请填写：${missing.join('、')}`
                : '将创建待确认记录，积分在第二步确认前不会变动。'}
          </Typography.Text>
          <Typography.Text type="secondary">
            方向固定为 CREDIT（增加积分），页面不提供扣减或负数补录；如需扣减请走其他流程。
          </Typography.Text>
        </form>
        <ErrorNotice error={requestError} />
      </Card>

      {lastApplied ? (
        <Alert
          type="success"
          showIcon
          title={`补录已入账（补录单号 ${lastApplied.id}）`}
          description={
            lastApplied.ledgerId
              ? `服务端返回流水号 ${lastApplied.ledgerId}${lastApplied.appliedAt ? `，入账时间 ${formatDateTime(lastApplied.appliedAt)}` : ''}。`
              : '服务端已返回 APPLIED 状态，但未返回流水号，请以流水列表为准。'
          }
        />
      ) : null}

      <Card className="admin-data-card" variant="borderless" title="待确认补录">
        <form
          className="admin-filter-bar admin-adjustment-filter-bar"
          onSubmit={(event) => {
            event.preventDefault()
            onUserFilterChange(userFilterDraft.trim() || undefined)
          }}
        >
          <Input
            allowClear
            prefix={<SearchOutlined />}
            aria-label="待确认用户 ID"
            placeholder="按用户 ID 筛选"
            value={userFilterDraft}
            onChange={(event) => setUserFilterDraft(event.target.value)}
          />
          <Button type="primary" htmlType="submit">
            查询
          </Button>
          <Button
            onClick={() => {
              setUserFilterDraft('')
              onUserFilterChange(undefined)
            }}
            disabled={!userFilterDraft && !userFilter}
          >
            重置
          </Button>
        </form>
        <Typography.Text type="secondary">
          列表只显示 PENDING_CONFIRMATION 状态：这些补录都还没有入账，需要输入确认令牌完成第二步。
        </Typography.Text>

        <ErrorNotice error={error} />

        {loading ? (
          <Skeleton active paragraph={{ rows: 5 }} />
        ) : adjustments.length === 0 ? (
          <Empty
            image={<DatabaseOutlined />}
            description={
              <div className="admin-empty-copy">
                <strong>
                  {userFilter ? '该用户没有待确认的补录' : '暂无待确认的补录'}
                </strong>
                <span>
                  {userFilter
                    ? '请更换用户 ID 或清除筛选后重试'
                    : '没有待确认记录时，第一步发起的补录会在提交后出现在这里'}
                </span>
              </div>
            }
          />
        ) : (
          <div className="admin-table-scroll">
            <table className="admin-data-table" aria-label="待确认补录列表">
              <thead>
                <tr>
                  {[
                    '补录单号',
                    '用户',
                    '积分',
                    '状态',
                    '发起人',
                    '原因',
                    '创建时间',
                    '过期时间',
                    '操作',
                  ].map((heading) => (
                    <th key={heading} scope="col">
                      {heading}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {adjustments.map((adjustment) => (
                  <tr key={adjustment.id}>
                    <td>
                      <strong>{adjustment.id}</strong>
                    </td>
                    <td>{maskUserId(adjustment.userId)}</td>
                    <td>+{formatPoints(adjustment.points)}</td>
                    <td>
                      <Tag color={STATUS_COLORS[adjustment.status]}>
                        {POINT_ADJUSTMENT_STATUS_LABELS[adjustment.status]}
                      </Tag>
                      {adjustment.confirmationExpiryState !== 'VALID' ? (
                        <Tag icon={<WarningOutlined />} color="warning">
                          {adjustment.confirmationExpiryState === 'EXPIRED'
                            ? '已过期'
                            : '过期时间未知'}
                        </Tag>
                      ) : null}
                    </td>
                    <td>
                      <Value
                        value={adjustment.operatorId ? maskUserId(adjustment.operatorId) : undefined}
                      />
                    </td>
                    <td>
                      <Value value={adjustment.reason} />
                    </td>
                    <td>{formatDateTime(adjustment.createdAt)}</td>
                    <td>{formatDateTime(adjustment.confirmationExpiresAt)}</td>
                    <td>
                      <Button
                        type="link"
                        aria-label={`继续确认 ${adjustment.id}`}
                        onClick={() => onResumeAdjustment(adjustment.id)}
                      >
                        继续确认
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
            重新加载待确认补录
          </Button>
        ) : null}
      </Card>

      {confirmation ? (
        <ConfirmationDialog
          key={confirmation.id}
          adjustment={confirmation}
          pending={confirmationPending}
          error={confirmationError}
          onCancel={onCloseConfirmation}
          onConfirm={(confirmationToken) => onConfirmAdjustment(confirmation.id, confirmationToken)}
        />
      ) : null}
    </section>
  )
}

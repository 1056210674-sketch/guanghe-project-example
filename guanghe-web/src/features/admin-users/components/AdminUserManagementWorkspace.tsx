import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  DatabaseOutlined,
  LockOutlined,
  SearchOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
  UnlockOutlined,
  WalletOutlined,
} from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd'
import { useMemo, useState } from 'react'
import type {
  AdminAccountStatusDraft,
  AdminPointsCompensationDraft,
  AdminUserStatus,
  AdminUserSummary,
} from '../types'

const USER_STATUS_META: Record<AdminUserStatus, { label: string; color: string }> = {
  ACTIVE: { label: '正常', color: 'success' },
  FROZEN: { label: '已冻结', color: 'warning' },
  DISABLED: { label: '已停用', color: 'default' },
}

function isNonNegativeSafeInteger(value: number | undefined): value is number {
  return value !== undefined && Number.isSafeInteger(value) && value >= 0
}

function formatPoints(value: number | undefined): string {
  return isNonNegativeSafeInteger(value) ? value.toLocaleString('zh-CN') : '—'
}

function accountStatusActionIsEligible(
  user: AdminUserSummary | undefined,
  action: AdminAccountStatusDraft['action'] | undefined,
): boolean {
  if (!user || !action) {
    return false
  }

  return (
    (user.status === 'ACTIVE' && action === 'FREEZE') ||
    (user.status === 'FROZEN' && action === 'UNFREEZE')
  )
}

export interface AdminUserManagementWorkspaceProps {
  users: readonly AdminUserSummary[]
  actionsEnabled?: boolean
  preview?: boolean
  onCompensatePoints?: (draft: AdminPointsCompensationDraft) => void
  onChangeAccountStatus?: (draft: AdminAccountStatusDraft) => void
}

export function AdminUserManagementWorkspace({
  users,
  actionsEnabled = false,
  preview = false,
  onCompensatePoints,
  onChangeAccountStatus,
}: AdminUserManagementWorkspaceProps) {
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<'ALL' | AdminUserStatus>('ALL')
  const [selectedUserId, setSelectedUserId] = useState<string>()
  const [compensationOpen, setCompensationOpen] = useState(false)
  const [compensationPoints, setCompensationPoints] = useState<number | null>(null)
  const [compensationReason, setCompensationReason] = useState('')
  const [relatedTaskId, setRelatedTaskId] = useState('')
  const [statusAction, setStatusAction] = useState<'FREEZE' | 'UNFREEZE'>()
  const [statusActionUserId, setStatusActionUserId] = useState<string>()
  const [statusReason, setStatusReason] = useState('')

  const normalizedQuery = query.trim().toLocaleLowerCase('zh-CN')
  const filteredUsers = useMemo(
    () =>
      users.filter((user) => {
        const matchesStatus = status === 'ALL' || user.status === status
        const matchesQuery =
          !normalizedQuery ||
          user.username.toLocaleLowerCase('zh-CN').includes(normalizedQuery) ||
          user.phoneMasked?.toLocaleLowerCase('zh-CN').includes(normalizedQuery)
        return matchesStatus && Boolean(matchesQuery)
      }),
    [normalizedQuery, status, users],
  )
  const selectedUser = users.find((user) => user.id === selectedUserId)
  const canCompensate = actionsEnabled && Boolean(onCompensatePoints)
  const canChangeStatus = actionsEnabled && Boolean(onChangeAccountStatus)
  const compensationValid =
    selectedUser !== undefined &&
    Number.isSafeInteger(compensationPoints) &&
    (compensationPoints ?? 0) > 0 &&
    compensationReason.trim().length >= 2
  const statusActionEligible =
    statusActionUserId !== undefined &&
    statusActionUserId === selectedUser?.id &&
    accountStatusActionIsEligible(selectedUser, statusAction)
  const statusChangeValid = statusActionEligible && statusReason.trim().length >= 2

  if (statusAction && !statusActionEligible) {
    setStatusAction(undefined)
    setStatusActionUserId(undefined)
    setStatusReason('')
  }

  const closeCompensation = () => {
    setCompensationOpen(false)
    setCompensationPoints(null)
    setCompensationReason('')
    setRelatedTaskId('')
  }

  const submitCompensation = () => {
    if (
      !selectedUser ||
      !canCompensate ||
      !onCompensatePoints ||
      !compensationValid ||
      compensationPoints === null
    ) {
      return
    }

    onCompensatePoints({
      userId: selectedUser.id,
      points: compensationPoints,
      reason: compensationReason.trim(),
      taskId: relatedTaskId.trim() || undefined,
    })
    closeCompensation()
  }

  const closeStatusChange = () => {
    setStatusAction(undefined)
    setStatusActionUserId(undefined)
    setStatusReason('')
  }

  const openStatusChange = (nextAction: AdminAccountStatusDraft['action']) => {
    if (!accountStatusActionIsEligible(selectedUser, nextAction)) {
      return
    }

    setStatusAction(nextAction)
    setStatusActionUserId(selectedUser?.id)
    setStatusReason('')
  }

  const submitStatusChange = () => {
    if (
      !selectedUser ||
      !statusAction ||
      statusActionUserId !== selectedUser.id ||
      !accountStatusActionIsEligible(selectedUser, statusAction) ||
      !canChangeStatus ||
      !onChangeAccountStatus ||
      statusReason.trim().length < 2
    ) {
      return
    }

    onChangeAccountStatus({
      userId: selectedUser.id,
      action: statusAction,
      reason: statusReason.trim(),
    })
    closeStatusChange()
  }

  return (
    <section className="admin-workspace" aria-labelledby="admin-users-title">
      <header className="admin-workspace__heading">
        <div>
          <Typography.Title id="admin-users-title" level={1}>
            用户管理
          </Typography.Title>
          <Typography.Text type="secondary">
            查询用户资料、账号状态与积分摘要
          </Typography.Text>
        </div>
        <Tag icon={<SafetyCertificateOutlined />} color="red">
          管理员专用
        </Tag>
      </header>

      {preview ? (
        <Alert
          type="info"
          showIcon
          title="开发设计预览"
          description="以下为界面验收样例，不代表真实用户或账务数据，所有高风险操作均保持禁用。"
        />
      ) : (
        <Alert
          type="warning"
          showIcon
          title="用户管理接口尚未接入"
          description="当前只展示安全空态；账号状态、积分和任务信息必须以服务端数据为准。"
        />
      )}

      <div className="admin-stat-grid" aria-label="用户数据概览">
        <Card variant="borderless">
          <TeamOutlined />
          <span>当前结果</span>
          <strong>{filteredUsers.length.toLocaleString('zh-CN')}</strong>
        </Card>
        <Card variant="borderless">
          <CheckCircleOutlined />
          <span>正常账号</span>
          <strong>
            {users.filter((user) => user.status === 'ACTIVE').length.toLocaleString('zh-CN')}
          </strong>
        </Card>
        <Card variant="borderless">
          <LockOutlined />
          <span>冻结账号</span>
          <strong>
            {users.filter((user) => user.status === 'FROZEN').length.toLocaleString('zh-CN')}
          </strong>
        </Card>
        <Card variant="borderless">
          <ClockCircleOutlined />
          <span>数据状态</span>
          <strong>{preview ? '预览' : '待接入'}</strong>
        </Card>
      </div>

      <Card className="admin-data-card" variant="borderless">
        <div className="admin-filter-bar">
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder="搜索账号或脱敏手机号"
            aria-label="搜索用户"
            value={query}
            disabled={users.length === 0}
            onChange={(event) => setQuery(event.target.value)}
          />
          <Select
            aria-label="账号状态"
            value={status}
            disabled={users.length === 0}
            options={[
              { value: 'ALL', label: '全部状态' },
              ...Object.entries(USER_STATUS_META).map(([value, meta]) => ({
                value,
                label: meta.label,
              })),
            ]}
            onChange={(value) => setStatus(value)}
          />
          <Button
            disabled={!query && status === 'ALL'}
            onClick={() => {
              setQuery('')
              setStatus('ALL')
            }}
          >
            重置
          </Button>
        </div>

        {users.length === 0 ? (
          <Empty
            image={<DatabaseOutlined />}
            description={
              <div className="admin-empty-copy">
                <strong>暂无用户数据</strong>
                <span>管理员用户查询接口接入后将在此展示真实记录</span>
              </div>
            }
          />
        ) : filteredUsers.length === 0 ? (
          <Empty
            description={
              <div className="admin-empty-copy">
                <strong>没有符合条件的用户</strong>
                <span>请调整账号关键词或状态筛选</span>
              </div>
            }
          />
        ) : (
          <div className="admin-table-scroll">
            <table className="admin-data-table" aria-label="用户列表">
              <thead>
                <tr>
                  <th scope="col">账号</th>
                  <th scope="col">脱敏手机号</th>
                  <th scope="col">经营空间</th>
                  <th scope="col">商品数</th>
                  <th scope="col">可用积分</th>
                  <th scope="col">账号状态</th>
                  <th scope="col">注册时间</th>
                  <th scope="col">最近登录</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                {filteredUsers.map((user) => {
                  const statusMeta = USER_STATUS_META[user.status]
                  return (
                    <tr key={user.id}>
                      <td><strong>{user.username}</strong></td>
                      <td>{user.phoneMasked?.trim() || '—'}</td>
                      <td>{formatPoints(user.businessSpaceCount)}</td>
                      <td>{formatPoints(user.productCount)}</td>
                      <td>{formatPoints(user.availablePoints)}</td>
                      <td><Tag color={statusMeta.color}>{statusMeta.label}</Tag></td>
                      <td>{user.registeredAt}</td>
                      <td>{user.lastLoginAt?.trim() || '—'}</td>
                      <td>
                        <Button type="link" onClick={() => setSelectedUserId(user.id)}>
                          查看
                        </Button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Drawer
        title={selectedUser ? `${selectedUser.username} 详情` : '用户详情'}
        size={420}
        open={Boolean(selectedUser)}
        onClose={() => setSelectedUserId(undefined)}
      >
        {selectedUser ? (
          <div className="admin-detail-stack">
            <section>
              <Typography.Title level={3}>基本信息</Typography.Title>
              <dl className="admin-detail-list">
                <div><dt>账号</dt><dd>{selectedUser.username}</dd></div>
                <div><dt>脱敏手机号</dt><dd>{selectedUser.phoneMasked || '—'}</dd></div>
                <div><dt>经营空间数</dt><dd>{formatPoints(selectedUser.businessSpaceCount)}</dd></div>
                <div><dt>商品数</dt><dd>{formatPoints(selectedUser.productCount)}</dd></div>
              </dl>
            </section>
            <section>
              <Typography.Title level={3}>账号状态</Typography.Title>
              <dl className="admin-detail-list">
                <div>
                  <dt>当前状态</dt>
                  <dd><Tag color={USER_STATUS_META[selectedUser.status].color}>{USER_STATUS_META[selectedUser.status].label}</Tag></dd>
                </div>
                <div><dt>注册时间</dt><dd>{selectedUser.registeredAt}</dd></div>
                <div><dt>最近登录</dt><dd>{selectedUser.lastLoginAt || '—'}</dd></div>
              </dl>
            </section>
            <section>
              <Typography.Title level={3}>积分摘要</Typography.Title>
              <dl className="admin-detail-list">
                <div><dt>可用积分</dt><dd>{formatPoints(selectedUser.availablePoints)}</dd></div>
                <div><dt>累计获得</dt><dd>{formatPoints(selectedUser.totalEarnedPoints)}</dd></div>
                <div><dt>累计消耗</dt><dd>{formatPoints(selectedUser.totalConsumedPoints)}</dd></div>
                <div><dt>即将过期</dt><dd>{formatPoints(selectedUser.expiringPoints)}</dd></div>
              </dl>
            </section>
            <section>
              <Typography.Title level={3}>近期任务</Typography.Title>
              {selectedUser.recentTasks?.length ? (
                <ul className="admin-compact-list">
                  {selectedUser.recentTasks.map((task) => (
                    <li key={task.id}>
                      <div><strong>{task.type}</strong><span>{task.id}</span></div>
                      <div><Tag>{task.status}</Tag><span>{task.createdAt}</span></div>
                    </li>
                  ))}
                </ul>
              ) : (
                <Typography.Text type="secondary">暂无近期任务数据</Typography.Text>
              )}
            </section>
            <Alert
              type="warning"
              showIcon
              title="高风险操作必须留痕"
              description="冻结、解冻和积分补偿均须由服务端复核权限、业务状态并写入不可删除的审计日志。"
            />
            <Space wrap>
              <Button
                icon={<WalletOutlined />}
                aria-label="补偿积分"
                disabled={!canCompensate}
                onClick={() => setCompensationOpen(true)}
              >
                补偿积分
              </Button>
              {selectedUser.status === 'FROZEN' ? (
                <Button
                  icon={<UnlockOutlined />}
                  aria-label="解冻账号"
                  disabled={!canChangeStatus || !accountStatusActionIsEligible(selectedUser, 'UNFREEZE')}
                  onClick={() => openStatusChange('UNFREEZE')}
                >
                  解冻账号
                </Button>
              ) : (
                <Button
                  danger
                  icon={<LockOutlined />}
                  aria-label="冻结账号"
                  disabled={!canChangeStatus || !accountStatusActionIsEligible(selectedUser, 'FREEZE')}
                  onClick={() => openStatusChange('FREEZE')}
                >
                  冻结账号
                </Button>
              )}
            </Space>
            {!actionsEnabled || (!onCompensatePoints && !onChangeAccountStatus) ? (
              <Typography.Text type="secondary">
                管理操作接口尚未接入，当前仅可查看界面结构
              </Typography.Text>
            ) : null}
          </div>
        ) : null}
      </Drawer>

      <Modal
        title="补偿积分"
        open={compensationOpen}
        okText="确认补偿"
        cancelText="取消"
        okButtonProps={{ disabled: !compensationValid || !canCompensate }}
        onOk={submitCompensation}
        onCancel={closeCompensation}
      >
        <div className="admin-modal-form">
          <label>
            <span>补偿积分数</span>
            <InputNumber
              min={1}
              precision={0}
              value={compensationPoints}
              placeholder="请输入正整数"
              aria-label="补偿积分数"
              onChange={setCompensationPoints}
            />
          </label>
          <label>
            <span>补偿原因</span>
            <Input.TextArea
              value={compensationReason}
              placeholder="请输入可审计的补偿原因"
              aria-label="补偿原因"
              onChange={(event) => setCompensationReason(event.target.value)}
            />
          </label>
          <label>
            <span>关联任务号（选填）</span>
            <Input
              value={relatedTaskId}
              placeholder="请输入关联任务号"
              aria-label="关联任务号"
              onChange={(event) => setRelatedTaskId(event.target.value)}
            />
          </label>
          <Alert type="warning" showIcon title="实际积分变更必须由服务端执行并记录审计日志" />
        </div>
      </Modal>

      <Modal
        title={statusAction === 'UNFREEZE' ? '确认解冻账号' : '确认冻结账号'}
        open={Boolean(statusAction) && statusActionEligible}
        forceRender
        okText={statusAction === 'UNFREEZE' ? '确认解冻' : '确认冻结'}
        okButtonProps={{ danger: statusAction === 'FREEZE', disabled: !statusChangeValid || !canChangeStatus || !accountStatusActionIsEligible(selectedUser, statusAction) }}
        onOk={submitStatusChange}
        onCancel={closeStatusChange}
      >
        <div className="admin-modal-form">
          <Alert
            type="warning"
            showIcon
            title="账号状态变更不会删除财务、投稿或审计记录"
          />
          <label>
            <span>操作原因</span>
            <Input.TextArea
              value={statusReason}
              placeholder="请输入可审计的操作原因"
              aria-label="账号状态变更原因"
              onChange={(event) => setStatusReason(event.target.value)}
            />
          </label>
        </div>
      </Modal>
    </section>
  )
}

import { DatabaseOutlined, PlusOutlined, SafetyCertificateOutlined, SearchOutlined } from '@ant-design/icons'
import { Alert, Button, Drawer, Empty, Form, Input, InputNumber, Modal, Pagination, Radio, Space, Tag, Typography } from 'antd'
import { useState } from 'react'
import { ErrorState, PageLoading } from '../../../components/GlobalState'
import { adminLoginIdentifier, type AdminAccountErrorView, type AdminAccountUserView, type AdminCreateAccountDraft } from '../types'

interface CreateFormValues { accountType: 'PHONE' | 'USERNAME'; phone?: string; username?: string; displayName: string; initialPointGrant?: number }

export interface AdminAccountManagementWorkspaceProps {
  users: readonly AdminAccountUserView[]
  total: number
  page: number
  pageSize: number
  loading: boolean
  error: AdminAccountErrorView | null
  detail?: AdminAccountUserView
  detailLoading: boolean
  detailError: AdminAccountErrorView | null
  actionError: AdminAccountErrorView | null
  pendingAction?: 'create' | 'reset' | 'disable' | 'restore'
  onSearch: (value: string) => void
  onPageChange: (page: number) => void
  onOpenUser: (userId: string) => void
  onCloseUser: () => void
  onCreate: (draft: AdminCreateAccountDraft) => Promise<boolean>
  onResetPassword: (userId: string) => void
  onDisable: (userId: string) => void
  onRestore: (userId: string) => void
  onRetryList: () => void
  onRetryDetail: () => void
}

const accountTone = { ACTIVE: 'success', DISABLED: 'default' } as const
const credentialLabel = { ACTIVE: '正常凭据', MUST_CHANGE_PASSWORD: '首次登录待改密' } as const

export function AdminAccountManagementWorkspace(props: AdminAccountManagementWorkspaceProps) {
  const [search, setSearch] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [form] = Form.useForm<CreateFormValues>()
  const accountType = Form.useWatch('accountType', form) ?? 'PHONE'
  const [confirmAction, setConfirmAction] = useState<'reset' | 'disable' | 'restore'>()
  const selectedId = props.detail?.id
  const closeCreate = () => { setCreateOpen(false); form.resetFields() }
  const submitCreate = async (values: CreateFormValues) => {
    const succeeded = await props.onCreate({
      ...(values.accountType === 'USERNAME'
        ? { username: values.username!.trim().toLowerCase() }
        : { phone: `+86${values.phone!.trim()}` }),
      displayName: values.displayName.trim(),
      initialPointGrant: values.initialPointGrant ?? 0,
    })
    if (succeeded) closeCreate()
  }
  const submitAction = () => {
    if (!selectedId || !confirmAction) return
    if (confirmAction === 'reset') props.onResetPassword(selectedId)
    else if (confirmAction === 'disable') props.onDisable(selectedId)
    else props.onRestore(selectedId)
    setConfirmAction(undefined)
  }

  return (
    <section className="admin-workspace" aria-labelledby="admin-users-title">
      <header className="admin-workspace__heading">
        <div>
          <Typography.Title id="admin-users-title" level={1}>用户管理</Typography.Title>
          <Typography.Text type="secondary">线下开户、凭据重置与账号状态管理</Typography.Text>
        </div>
        <Space>
          <Tag icon={<SafetyCertificateOutlined />} color="red">管理员专用</Tag>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>管理员开户</Button>
        </Space>
      </header>
      <Alert type="info" showIcon title="可使用手机号或用户名开户；用户凭临时密码首次登录后须设置新密码。暂不开放公开注册、短信找回或线上申请。" />
      {props.actionError ? <Alert type="error" showIcon title={props.actionError.message} description={props.actionError.traceId ? `traceId：${props.actionError.traceId}` : undefined} /> : null}
      <div className="admin-filter-bar admin-account-filter">
        <Input allowClear prefix={<SearchOutlined />} value={search} aria-label="搜索用户" placeholder="搜索手机号、用户名或称呼" onChange={(event) => setSearch(event.target.value)} onPressEnter={() => props.onSearch(search.trim())} />
        <Button onClick={() => props.onSearch(search.trim())}>搜索</Button>
        <Button disabled={!search} onClick={() => { setSearch(''); props.onSearch('') }}>重置</Button>
      </div>
      {props.loading ? <PageLoading label="正在加载用户…" /> : props.error ? (
        <ErrorState message={props.error.message} traceId={props.error.traceId} onRetry={props.onRetryList} />
      ) : !props.users.length ? (
        <Empty image={<DatabaseOutlined />} description={<div className="admin-empty-copy"><strong>暂无用户数据</strong><span>可以使用管理员开户创建首个 USER 账号</span></div>} />
      ) : (
        <>
          <div className="admin-table-scroll">
            <table className="admin-data-table" aria-label="用户列表">
              <thead><tr><th>登录账号</th><th>用户名称</th><th>账号状态</th><th>凭据状态</th><th>创建时间</th><th>操作</th></tr></thead>
              <tbody>{props.users.map((user) => (
                <tr key={user.id}>
                  <td><strong>{adminLoginIdentifier(user)}</strong></td><td>{user.displayName}</td>
                  <td><Tag color={accountTone[user.status]}>{user.status === 'ACTIVE' ? '正常' : '已停用'}</Tag></td>
                  <td><Tag color={user.credentialState === 'ACTIVE' ? 'success' : 'warning'}>{credentialLabel[user.credentialState]}</Tag></td>
                  <td>{user.createdAt ?? '—'}</td>
                  <td><Button type="link" onClick={() => props.onOpenUser(user.id)}>查看</Button></td>
                </tr>
              ))}</tbody>
            </table>
          </div>
          <Pagination current={props.page} pageSize={props.pageSize} total={props.total} showSizeChanger={false} onChange={props.onPageChange} />
        </>
      )}

      <Drawer title="用户详情" open={Boolean(selectedId) || props.detailLoading || Boolean(props.detailError)} size={460} onClose={props.onCloseUser}>
        {props.detailLoading ? <PageLoading label="正在加载用户详情…" /> : props.detailError ? <ErrorState message={props.detailError.message} traceId={props.detailError.traceId} onRetry={props.onRetryDetail} /> : props.detail ? (
          <div className="admin-detail-stack">
            <dl className="admin-detail-list">
              <div><dt>登录账号</dt><dd>{adminLoginIdentifier(props.detail)}</dd></div>
              <div><dt>手机号</dt><dd>{props.detail.phone || '未绑定'}</dd></div>
              <div><dt>用户名称</dt><dd>{props.detail.displayName}</dd></div>
              <div><dt>账号状态</dt><dd>{props.detail.status === 'ACTIVE' ? '正常' : '已停用'}</dd></div>
              <div><dt>凭据状态</dt><dd>{credentialLabel[props.detail.credentialState]}</dd></div>
              <div><dt>创建时间</dt><dd>{props.detail.createdAt ?? '—'}</dd></div>
            </dl>
            <Alert type="warning" showIcon title="重置密码会使现有会话失效，并生成仅显示一次的新临时密码。" />
            <Space wrap>
              <Button danger onClick={() => setConfirmAction('reset')}>重置密码</Button>
              {props.detail.status === 'ACTIVE' ? (
                <Button danger onClick={() => setConfirmAction('disable')}>停用账号</Button>
              ) : <Button onClick={() => setConfirmAction('restore')}>恢复账号</Button>}
            </Space>
          </div>
        ) : null}
      </Drawer>

      <Modal title="管理员开户" open={createOpen} okText="确认开户" cancelText="取消" confirmLoading={props.pendingAction === 'create'} onOk={() => form.submit()} onCancel={closeCreate} destroyOnHidden>
        <Form form={form} layout="vertical" onFinish={submitCreate} initialValues={{ accountType: 'PHONE', initialPointGrant: 0 }}>
          <Form.Item label="开户方式" name="accountType">
            <Radio.Group options={[{ label: '手机号开户', value: 'PHONE' }, { label: '用户名开户', value: 'USERNAME' }]} />
          </Form.Item>
          {accountType === 'USERNAME' ? (
            <Form.Item label="登录用户名" name="username" preserve={false} extra="3–32位，字母开头，可包含字母、数字和下划线；不区分大小写。" rules={[{ required: true, whitespace: true, message: '请输入登录用户名' }, { transform: (value: string) => value?.trim(), pattern: /^[A-Za-z][A-Za-z0-9_]{2,31}$/, message: '请输入3–32位用户名，以字母开头，仅含字母、数字和下划线' }]}>
              <Input maxLength={32} autoComplete="off" />
            </Form.Item>
          ) : (
            <Form.Item label="中国大陆手机号" name="phone" preserve={false} rules={[{ required: true, message: '请输入手机号' }, { pattern: /^1[3-9]\d{9}$/, message: '请输入11位中国大陆手机号' }]}><Input maxLength={11} autoComplete="off" /></Form.Item>
          )}
          <Form.Item label="用户名称/称呼" name="displayName" rules={[{ required: true, message: '请输入用户名称' }, { max: 64, message: '最多64个字符' }]}><Input maxLength={64} /></Form.Item>
          <Form.Item label="初始赠送积分（选填）" name="initialPointGrant"><InputNumber min={0} max={1000000} precision={0} /></Form.Item>
        </Form>
      </Modal>

      <Modal title={confirmAction === 'reset' ? '确认重置密码' : confirmAction === 'disable' ? '确认停用账号' : '确认恢复账号'} open={Boolean(confirmAction)} okText="确认" okButtonProps={{ danger: confirmAction !== 'restore' }} confirmLoading={Boolean(props.pendingAction && props.pendingAction !== 'create')} onOk={submitAction} onCancel={() => setConfirmAction(undefined)}>
        <Typography.Paragraph>{confirmAction === 'reset' ? '该操作会使用户现有登录会话失效，并要求使用新临时密码完成首次登录。' : confirmAction === 'disable' ? '停用后用户将无法继续登录，但业务和审计数据不会删除。' : '恢复账号不会绕过首次登录改密要求。'}</Typography.Paragraph>
      </Modal>
    </section>
  )
}

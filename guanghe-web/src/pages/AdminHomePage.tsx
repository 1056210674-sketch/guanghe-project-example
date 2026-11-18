import { ArrowRightOutlined, ContainerOutlined, TeamOutlined } from '@ant-design/icons'
import { Alert, Card, Skeleton, Typography } from 'antd'
import type { KeyboardEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAdminTaskDiagnostics } from '../features/admin-tasks'
import { useAdminUsers } from '../features/admin-users'
import { AdminShell } from '../layouts/AdminShell'
import { APP_PATHS } from '../routes/paths'
import { useAuthStore } from '../stores/authStore'

function totalLabel(value: number | undefined) {
  return value !== undefined && Number.isSafeInteger(value) && value >= 0
    ? value.toLocaleString('zh-CN')
    : '—'
}

export function AdminHomePage() {
  const navigate = useNavigate()
  const ownerId = useAuthStore((state) => state.user?.id)
  const usersQuery = useAdminUsers(ownerId, { page: 1, size: 1 })
  const tasksQuery = useAdminTaskDiagnostics({ page: 1, size: 1 })
  const loading = usersQuery.isLoading || tasksQuery.isLoading
  const hasError = Boolean(usersQuery.error || tasksQuery.error)
  const linkKeyDown = (path: string) => (event: KeyboardEvent) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      navigate(path)
    }
  }

  return (
    <AdminShell>
      <section className="admin-home" aria-labelledby="admin-home-title">
        <div className="page-heading">
          <div>
            <Typography.Title id="admin-home-title" level={1}>运营概览</Typography.Title>
            <Typography.Text type="secondary">管理用户账号并排查任务与 Run 的执行问题</Typography.Text>
          </div>
        </div>
        {hasError ? <Alert type="warning" showIcon message="部分运营摘要暂时无法获取" description="管理入口仍可使用，请进入对应模块查看或稍后刷新。" /> : null}
        {loading ? <Skeleton active paragraph={{ rows: 4 }} /> : (
          <div className="admin-home-grid">
            <Card hoverable role="link" tabIndex={0} onClick={() => navigate(APP_PATHS.adminUsers)} onKeyDown={linkKeyDown(APP_PATHS.adminUsers)}>
              <span className="admin-home-card__icon"><TeamOutlined /></span>
              <div><Typography.Text type="secondary">用户管理</Typography.Text><strong>{totalLabel(usersQuery.data?.total)}</strong><span>个账号</span></div>
              <Typography.Paragraph type="secondary">创建账号、重置密码，以及停用或恢复用户。</Typography.Paragraph>
              <span className="admin-home-card__link">进入用户管理 <ArrowRightOutlined /></span>
            </Card>
            <Card hoverable role="link" tabIndex={0} onClick={() => navigate(APP_PATHS.adminTasks)} onKeyDown={linkKeyDown(APP_PATHS.adminTasks)}>
              <span className="admin-home-card__icon"><ContainerOutlined /></span>
              <div><Typography.Text type="secondary">任务诊断记录</Typography.Text><strong>{totalLabel(tasksQuery.data?.total)}</strong><span>项</span></div>
              <Typography.Paragraph type="secondary">查询付费任务和 AI Run 的状态、错误码与执行时间线。</Typography.Paragraph>
              <span className="admin-home-card__link">进入任务诊断 <ArrowRightOutlined /></span>
            </Card>
          </div>
        )}
      </section>
    </AdminShell>
  )
}

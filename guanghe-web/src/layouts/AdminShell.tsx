import {
  AuditOutlined,
  ContainerOutlined,
  ControlOutlined,
  DashboardOutlined,
  DollarOutlined,
  FileProtectOutlined,
  FileTextOutlined,
  LogoutOutlined,
  MessageOutlined,
  RobotOutlined,
  TeamOutlined,
  TransactionOutlined,
} from '@ant-design/icons'
import {
  Avatar,
  Button,
  Dropdown,
  Layout,
  Space,
  Tooltip,
  Typography,
} from 'antd'
import type { MenuProps } from 'antd'
import type { ReactNode } from 'react'
import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { signOutAndRedirectToLogin } from '../auth'
import { Brand } from '../components/Brand'
import { APP_PATHS } from '../routes/paths'
import { useAuthStore } from '../stores/authStore'
import '../styles/admin.css'

const { Header, Sider, Content } = Layout

/**
 * `previewOnly` means "no production route yet": the entry is hidden outside the DEV preview shell.
 * `previewPath: null` means the opposite — the page is live in production but DEV has no preview for
 * it, so the entry is shown disabled there instead of pointing at a route that does not exist.
 */
const adminNavigation: Array<{
  label: string
  icon: ReactNode
  path: string
  previewOnly?: boolean
  previewPath?: string | null
}> = [
  { label: '运营概览', icon: <DashboardOutlined />, path: APP_PATHS.adminHome, previewPath: null },
  {
    label: '用户管理',
    icon: <TeamOutlined />,
    path: APP_PATHS.adminUsers,
  },
  { label: '定价配置', icon: <ControlOutlined />, path: APP_PATHS.adminPricing },
  { label: '积分账务', icon: <DollarOutlined />, path: APP_PATHS.adminLedger },
  {
    label: '充值订单',
    icon: <TransactionOutlined />,
    path: APP_PATHS.adminRechargeOrders,
    previewPath: null,
  },
  { label: '媒体与投稿', icon: <FileTextOutlined />, path: APP_PATHS.adminMedia },
  { label: '发票管理', icon: <FileProtectOutlined />, path: APP_PATHS.adminInvoices },
  { label: '用户反馈', icon: <MessageOutlined />, path: APP_PATHS.adminFeedback },
  { label: '操作日志', icon: <AuditOutlined />, path: APP_PATHS.adminAudit },
  { label: 'Agent 版本', icon: <RobotOutlined />, path: APP_PATHS.adminAgentVersions },
  { label: '智能体平台', icon: <RobotOutlined />, path: APP_PATHS.adminPlatform, previewPath: null },
  {
    label: '任务诊断',
    icon: <ContainerOutlined />,
    path: APP_PATHS.adminTasks,
  },
]

export function AdminShell({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const location = useLocation()
  const user = useAuthStore((state) => state.user)
  const [logoutBusy, setLogoutBusy] = useState(false)
  const displayName = user?.displayName?.trim() || user?.username || '管理员'
  const avatarText = displayName.slice(0, 1).toUpperCase()
  const previewMode = import.meta.env.DEV && location.pathname.startsWith('/preview/admin/')
  const effectivePathname = previewMode
    ? location.pathname.replace('/preview', '')
    : location.pathname

  const handleLogout = async () => {
    if (logoutBusy) {
      return
    }

    setLogoutBusy(true)
    try {
      await signOutAndRedirectToLogin()
    } finally {
      setLogoutBusy(false)
    }
  }

  const menuItems: MenuProps['items'] = [
    {
      key: 'identity',
      label: (
        <div className="account-menu__identity">
          <Typography.Text strong>{displayName}</Typography.Text>
          <Typography.Text type="secondary">管理员</Typography.Text>
        </div>
      ),
      disabled: true,
    },
    { type: 'divider' },
    {
      key: 'logout',
      label: '退出登录',
      icon: <LogoutOutlined />,
      danger: true,
      onClick: () => void handleLogout(),
    },
  ]

  return (
    <Layout className="app-shell app-shell--admin">
      <Header className="admin-header">
        <Brand admin compact />
        <Space className="shell-account" size={12}>
          <Dropdown menu={{ items: menuItems }} placement="bottomRight">
            <Button type="text" className="account-trigger" loading={logoutBusy}>
              <Avatar>{avatarText}</Avatar>
              <span className="account-trigger__name">{displayName}</span>
            </Button>
          </Dropdown>
        </Space>
      </Header>
      <Layout className="admin-body">
        <Sider width={232} className="admin-sider" theme="light">
          <nav className="shell-nav shell-nav--admin" aria-label="管理员主导航">
            {adminNavigation.filter((item) => previewMode || !item.previewOnly).map((item) => {
              const target = previewMode
                ? item.previewPath === null
                  ? undefined
                  : (item.previewPath ?? `/preview${item.path}`)
                : item.path
              const active = Boolean(
                effectivePathname === item.path ||
                  (item.path !== APP_PATHS.adminHome &&
                    effectivePathname.startsWith(`${item.path}/`)),
              )
              return (
                <Tooltip
                  key={item.label}
                  title={target ? undefined : '预览未提供该页面'}
                  placement="right"
                >
                  <button
                    type="button"
                    className={`shell-nav__item ${active ? 'shell-nav__item--active' : ''}`}
                    disabled={!target}
                    aria-label={item.label}
                    aria-current={active ? 'page' : undefined}
                    onClick={() => target && navigate(target)}
                  >
                    {item.icon}
                    <span>{item.label}</span>
                  </button>
                </Tooltip>
              )
            })}
          </nav>
        </Sider>
        <Content className="admin-content">
          <main className="app-shell__content-inner">{children}</main>
        </Content>
      </Layout>
    </Layout>
  )
}

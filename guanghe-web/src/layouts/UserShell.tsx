import { AppstoreOutlined, BellOutlined, BookOutlined, ContainerOutlined, DeleteOutlined, EditOutlined, FileTextOutlined, FolderOutlined, LogoutOutlined, MenuOutlined, MessageOutlined, MoreOutlined, PictureOutlined, PlusOutlined, RadarChartOutlined, RightOutlined, RobotOutlined, SearchOutlined, StarOutlined } from '@ant-design/icons'
import { Alert, Avatar, Badge, Button, Dropdown, Input, Layout, Modal, Spin, Tooltip, Typography } from 'antd'
import type { MenuProps } from 'antd'
import { useEffect, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { signOutAndRedirectToLogin } from '../auth'
import { captureAuthSession, isCurrentAuthSession } from '../auth/tokenStorage'
import { Brand } from '../components/Brand'
import { useProjects, useCreateProject } from '../features/agent-platform/queries'
import { useConversations } from '../features/chat/live/chatQueries'
import { toUnreadCount, useUnreadNotificationCount } from '../features/notifications/live'
import { usePointAccountSummary } from '../features/points-billing'
import { APP_PATHS, appLinks } from '../routes/paths'
import { useAuthStore } from '../stores/authStore'
import type { Conversation } from '../api/conversationApi'
import '../styles/agent-platform.css'

interface UserShellProps {
  children: ReactNode
  onSelectConversation?: (id: string) => void
  onRenameConversation?: (id: string) => void
  onDeleteConversation?: (id: string) => void
}

export function UserShell({ children, onSelectConversation, onRenameConversation, onDeleteConversation }: UserShellProps) {
  const navigate = useNavigate()
  const location = useLocation()
  const user = useAuthStore((state) => state.user)
  const [logoutBusy, setLogoutBusy] = useState(false)
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [collapsedProjects, setCollapsedProjects] = useState<ReadonlySet<string>>(new Set())
  const [projectEditorOpen, setProjectEditorOpen] = useState(false)
  const [projectName, setProjectName] = useState('')
  const [projectError, setProjectError] = useState<string>()
  useEffect(() => {
    if (!sidebarOpen) return
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const navigation = document.getElementById('platform-navigation')
    const controls = () => Array.from(navigation?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), [tabindex="0"]') ?? [])
    controls()[0]?.focus()
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); setSidebarOpen(false) }
      if (event.key !== 'Tab') return
      const items = controls()
      if (event.shiftKey && document.activeElement === items[0]) { event.preventDefault(); items.at(-1)?.focus() }
      else if (!event.shiftKey && document.activeElement === items.at(-1)) { event.preventDefault(); items[0]?.focus() }
    }
    navigation?.addEventListener('keydown', handleKey)
    return () => { navigation?.removeEventListener('keydown', handleKey); if (previousFocus?.isConnected) previousFocus.focus() }
  }, [sidebarOpen])
  const previewMode = import.meta.env.DEV && location.pathname.startsWith('/preview/')
  const effectivePathname = previewMode ? location.pathname.replace('/preview/', '/app/') : location.pathname
  const points = usePointAccountSummary(!previewMode)
  const unread = useUnreadNotificationCount(!previewMode)
  const projects = useProjects(!previewMode)
  const conversations = useConversations(!previewMode)
  const createProject = useCreateProject()
  const selectedId = new URLSearchParams(location.search).get('conversation')
  const selectedProjectId = new URLSearchParams(location.search).get('project')
  const notificationPath = previewMode ? '/preview/notifications' : APP_PATHS.notifications
  const displayName = user?.displayName?.trim() || user?.username || '用户'
  const selectedConversation = conversations.data?.find((item) => item.id === selectedId)
  const projectId = selectedId ? selectedConversation?.agentIdentity?.projectId : selectedProjectId
  const visibleConversations = (conversations.data ?? []).filter((item) => item.title.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()))
  const knownProjectIds = new Set(projects.data?.map((project) => project.id))
  const unlistedProjectConversations = visibleConversations.filter((item) => item.agentIdentity?.projectId && !knownProjectIds.has(item.agentIdentity.projectId))
  const go = (path: string) => { setSidebarOpen(false); navigate(path) }
  const selectConversation = (id: string) => {
    setSidebarOpen(false)
    if (onSelectConversation) onSelectConversation(id)
    else navigate(APP_PATHS.chat + '?' + new URLSearchParams({ conversation: id }))
  }
  const accountItems: MenuProps['items'] = [
    { key: 'identity', label: displayName, disabled: true },
    { key: 'tasks', label: '任务记录', icon: <BookOutlined />, onClick: () => go(APP_PATHS.tasks) },
    { key: 'billing', label: '积分与账单', icon: <StarOutlined />, onClick: () => go(APP_PATHS.pointsBilling) },
    { key: 'notifications', label: '通知中心', icon: <BellOutlined />, onClick: () => go(notificationPath) },
    { type: 'divider' },
    { key: 'logout', label: '退出登录', icon: <LogoutOutlined />, danger: true, onClick: () => {
      if (logoutBusy) return
      setLogoutBusy(true)
      void signOutAndRedirectToLogin().finally(() => setLogoutBusy(false))
    } },
  ]
  const renderConversation = (conversation: Conversation, nested = false) => <div key={conversation.id} className={'platform-conversation' + (nested ? ' platform-conversation--nested' : '')}>
    <button type="button" title={conversation.title} className={'platform-nav-item' + (selectedId === conversation.id ? ' platform-nav-item--active' : '')} aria-current={selectedId === conversation.id ? 'page' : undefined} onClick={() => selectConversation(conversation.id)}>
      {!nested ? <MessageOutlined /> : null}<span>{conversation.title}</span>
    </button>
    {onRenameConversation || onDeleteConversation ? <Dropdown trigger={['click']} menu={{ items: [
      ...(onRenameConversation ? [{ key: 'rename', label: '重命名', icon: <EditOutlined />, onClick: () => onRenameConversation(conversation.id) }] : []),
      ...(onDeleteConversation ? [{ key: 'delete', label: '删除对话', danger: true, icon: <DeleteOutlined />, onClick: () => onDeleteConversation(conversation.id) }] : []),
    ] }}><Button type="text" size="small" icon={<MoreOutlined />} aria-label={'管理会话：' + conversation.title} /></Dropdown> : null}
  </div>

  return <Layout className={'app-shell app-shell--user platform-shell' + (sidebarOpen ? ' platform-shell--sidebar-open' : '')}>
    {sidebarOpen ? <button className="platform-sidebar-backdrop" aria-label="关闭导航" onClick={() => setSidebarOpen(false)} /> : null}
    <Layout.Sider width={232} className="app-shell__sider platform-sidebar" theme="light" id="platform-navigation" role={sidebarOpen ? 'dialog' : undefined} aria-modal={sidebarOpen || undefined} aria-label="工作区导航">
      <div className="platform-brand"><Brand compact /></div>
      <nav className="platform-primary-nav" aria-label="普通用户主导航">
        {[
          { label: '新聊天', icon: <PlusOutlined />, path: APP_PATHS.userHome, active: location.pathname === APP_PATHS.userHome && !selectedId },
          { label: '智能体', icon: <RobotOutlined />, path: APP_PATHS.agents, active: location.pathname === APP_PATHS.agents },
          { label: '创作画布', icon: <PictureOutlined />, path: APP_PATHS.canvases, active: location.pathname.startsWith(APP_PATHS.canvases) },
          { label: '品牌知识库', icon: <ContainerOutlined />, path: APP_PATHS.knowledge, active: location.pathname === APP_PATHS.knowledge },
          { label: '资产中心', icon: <AppstoreOutlined />, path: APP_PATHS.assets, active: location.pathname === APP_PATHS.assets },
          { label: '自动监测', icon: <RadarChartOutlined />, path: APP_PATHS.geoPlans, active: location.pathname === APP_PATHS.geoPlans },
          { label: '投稿', icon: <FileTextOutlined />, path: APP_PATHS.submissions, active: location.pathname === APP_PATHS.submissions },
        ].map((item) => <button key={item.label} type="button" aria-label={item.label} className={'platform-nav-item' + (item.active ? ' platform-nav-item--active' : '')} aria-current={item.active ? 'page' : undefined} onClick={() => go(item.path)}>{item.icon}<span>{item.label}</span></button>)}
      </nav>
      <div className="platform-sidebar-search"><Input size="small" prefix={<SearchOutlined />} aria-label="搜索聊天" placeholder="搜索聊天" value={search} allowClear onChange={(event) => setSearch(event.target.value)} /></div>
      <div className="platform-sidebar-lists">
        <section aria-labelledby="platform-projects-title">
          <div className="platform-group-heading"><span id="platform-projects-title">项目</span><Button type="text" size="small" icon={<PlusOutlined />} aria-label="新建项目" disabled={previewMode} onClick={() => { setProjectName(''); setProjectError(undefined); setProjectEditorOpen(true) }} /></div>
          {projects.isLoading ? <Spin size="small" /> : projects.isError ? <div className="platform-sidebar-state" role="status">项目加载失败<Button type="link" size="small" onClick={() => void projects.refetch()}>重试</Button></div> : null}
          {projects.data?.map((project) => <div key={project.id} className="platform-project">
            <div className="platform-project-heading">
              <button type="button" className="platform-project-toggle" aria-label={(collapsedProjects.has(project.id) ? '展开' : '收起') + '项目 ' + project.name} aria-expanded={!collapsedProjects.has(project.id)} onClick={() => setCollapsedProjects((current) => { const next = new Set(current); if (next.has(project.id)) next.delete(project.id); else next.add(project.id); return next })}><RightOutlined rotate={collapsedProjects.has(project.id) ? 0 : 90} /></button>
              <button type="button" className={'platform-nav-item' + (projectId === project.id ? ' platform-nav-item--current-project' : '')} title={project.name} onClick={() => go(appLinks.newAgentTask(undefined, project.id))}><FolderOutlined /><span>{project.name}</span>{project.status === 'ARCHIVED' ? <small>已归档</small> : null}</button>
              <Dropdown trigger={['click']} menu={{ items: [{ key: 'manage', label: '品牌资料、记忆与项目设置', onClick: () => go(appLinks.projectKnowledge(project.id)) }, { key: 'new', label: '在此项目新建聊天', disabled: project.status !== 'ACTIVE', onClick: () => go(appLinks.newAgentTask(undefined, project.id)) }] }}><Button type="text" size="small" icon={<MoreOutlined />} aria-label={`管理项目 ${project.name}`} /></Dropdown>
            </div>
            {!collapsedProjects.has(project.id) ? visibleConversations.filter((item) => item.agentIdentity?.projectId === project.id).map((item) => renderConversation(item, true)) : null}
          </div>)}
          {projects.isSuccess && projects.data.length === 0 ? <p className="platform-sidebar-state">暂无项目，可直接开始聊天。</p> : null}
          {unlistedProjectConversations.length > 0 ? <div className="platform-project"><p className="platform-sidebar-state">暂未载入的项目聊天</p>{unlistedProjectConversations.map((item) => renderConversation(item, true))}</div> : null}
        </section>
        <section aria-labelledby="platform-recent-title">
          <div className="platform-group-heading"><span id="platform-recent-title">最近聊天</span></div>
          {conversations.isLoading ? <Spin size="small" /> : conversations.isError ? <div className="platform-sidebar-state" role="status">聊天加载失败<Button type="link" size="small" onClick={() => void conversations.refetch()}>重试</Button></div> : null}
          {visibleConversations.filter((item) => !item.agentIdentity?.projectId).map((item) => renderConversation(item))}
          {conversations.isSuccess && visibleConversations.length === 0 ? <p className="platform-sidebar-state">{search ? '没有匹配的聊天' : '暂无聊天'}</p> : null}
        </section>
      </div>
      <div className="platform-account-footer">
        <div className="platform-account-actions">
          <Tooltip title={points.isError ? '积分暂时无法获取' : '查看积分与账单'}><Button type="text" size="small" icon={<StarOutlined />} aria-label="积分与账单" aria-current={effectivePathname === APP_PATHS.pointsBilling ? 'page' : undefined} onClick={() => go(APP_PATHS.pointsBilling)}>{points.data?.availablePoints !== undefined ? points.data.availablePoints.toLocaleString('zh-CN') + ' 积分' : '积分与账单'}</Button></Tooltip>
          <Badge count={unread.data ? toUnreadCount(unread.data) : 0} overflowCount={99}><Button type="text" size="small" icon={<BellOutlined />} aria-label="打开通知中心" onClick={() => go(notificationPath)} /></Badge>
        </div>
        <Dropdown trigger={['click']} menu={{ items: accountItems }} placement="topLeft"><Button type="text" block className="platform-account" loading={logoutBusy} aria-label="账户菜单"><Avatar size={28}>{displayName.slice(0, 1).toUpperCase()}</Avatar><span>{displayName}</span><MoreOutlined /></Button></Dropdown>
      </div>
    </Layout.Sider>
    <Layout className="app-shell__body">
      <Layout.Header className="platform-mobile-header"><Button type="text" icon={<MenuOutlined />} aria-label="展开导航" aria-controls="platform-navigation" aria-expanded={sidebarOpen} onClick={() => setSidebarOpen(true)} /><Typography.Text>光禾AI智能体框架</Typography.Text></Layout.Header>
      <Layout.Content className="app-shell__content"><div className="app-shell__content-inner">{children}</div></Layout.Content>
    </Layout>
    <Modal title="新建项目" open={projectEditorOpen} okText="创建项目" cancelText="取消" confirmLoading={createProject.isPending} okButtonProps={{ disabled: !projectName.trim() }} onCancel={() => { if (!createProject.isPending) setProjectEditorOpen(false) }} onOk={() => {
      if (!projectName.trim() || createProject.isPending) return
      const session = captureAuthSession()
      setProjectError(undefined)
      void createProject.mutateAsync({ name: projectName.trim() }).then(() => { if (isCurrentAuthSession(session)) setProjectEditorOpen(false) }).catch(() => { if (isCurrentAuthSession(session)) setProjectError('项目创建失败，请重试。') })
    }}>
      <Typography.Paragraph type="secondary">为每个客户建立项目，分别整理聊天、品牌资料和记忆。也可以不创建项目，直接聊天。</Typography.Paragraph>
      <Input aria-label="项目名称" placeholder="项目名称" maxLength={100} value={projectName} disabled={createProject.isPending} onChange={(event) => setProjectName(event.target.value)} />
      {projectError ? <Alert type="error" showIcon title={projectError} /> : null}
    </Modal>
  </Layout>
}

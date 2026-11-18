import { Alert, Button, Empty, Select, Space, Tag, Typography } from 'antd'
import type { AgentDefinition, AgentInstallation, Project } from '../../api/agentPlatformApi'
import type { Conversation } from '../../api/conversationApi'
import { isAgentInstallationAvailable } from './installedAgent'

interface TaskAgentControlsProps {
  existingIdentity?: Conversation['agentIdentity']
  existingConversation: boolean
  platformStart: boolean
  installations: readonly AgentInstallation[]
  agents: readonly AgentDefinition[]
  projects: readonly Project[]
  installationId?: string
  projectId?: string
  loading: boolean
  busy: boolean
  error?: string
  guidance?: string
  onRetry: () => void
  onInstallationChange: (id: string) => void
  onProjectChange: (id?: string) => void
  onOpenLibrary: () => void
}

export function TaskAgentControls(props: TaskAgentControlsProps) {
  const identity = props.existingIdentity
  const projectName = props.projects.find((project) => project.id === identity?.projectId)?.name
  if (props.existingConversation || !props.platformStart) return <Space wrap className="platform-task-identity" aria-label="当前会话智能体">
    {identity ? <><Tag>{identity.name}</Tag><Typography.Text type="secondary">{identity.projectId ? `项目：${projectName ?? '当前客户项目'}` : '未归入项目'}{identity.installationId ? '' : ' · 历史聊天'}</Typography.Text></> : <Typography.Text type="secondary">历史聊天</Typography.Text>}
    <Button type="link" onClick={props.onOpenLibrary}>选择智能体开始新聊天</Button>
  </Space>

  return <div className="platform-task-identity">
    <Space wrap>
      <Select aria-label="使用智能体" placeholder="选择我的智能体" value={props.installationId} loading={props.loading} disabled={props.busy || props.loading || Boolean(props.error)} onChange={props.onInstallationChange}
        notFoundContent={<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={props.error ? '智能体加载失败' : props.loading ? '正在加载智能体…' : '还没有添加智能体'}><Button size="small" type="primary" disabled={props.busy || props.loading} onClick={props.error ? props.onRetry : props.onOpenLibrary}>{props.error ? '重新加载' : '添加智能体'}</Button></Empty>}
        options={props.error ? [] : props.installations.map((installation) => ({ value: installation.id, label: `${installation.name}${installation.enabled ? '' : '（已停用）'}`, disabled: !isAgentInstallationAvailable(installation, props.agents) }))} />
      <Select aria-label="任务所属项目" placeholder="不归入项目" value={props.projectId} allowClear loading={props.loading} disabled={props.busy || props.loading} onChange={props.onProjectChange}
        notFoundContent={props.error ? '项目暂时无法读取' : '暂无项目，可以不选'}
        options={props.projects.map((project) => ({ value: project.id, label: project.name + (project.status === 'ARCHIVED' ? '（已归档）' : ''), disabled: project.status !== 'ACTIVE' }))} />
      <Button type={!props.loading && !props.error && props.installations.length === 0 ? 'primary' : 'link'} onClick={props.onOpenLibrary} disabled={props.busy}>{!props.loading && !props.error && props.installations.length === 0 ? '添加智能体' : '前往智能体库'}</Button>
    </Space>
    {props.error ? <Alert type="error" showIcon title={props.error} action={<Button size="small" onClick={props.onRetry}>重试</Button>} /> : props.guidance && (props.loading || props.installations.length > 0) ? <Typography.Paragraph className="platform-task-guidance" type="secondary" role="status">{props.guidance}</Typography.Paragraph> : null}
    {!props.loading && !props.error && props.installations.length === 0 ? <Typography.Paragraph type="secondary">这个账号还没有添加智能体。点击“添加智能体”，选择需要的智能体即可开始；已有对话不受影响。</Typography.Paragraph> : null}
    {!props.loading && !props.error && props.installations.length > 0 && !props.installations.some((installation) => isAgentInstallationAvailable(installation, props.agents)) ? <Typography.Paragraph type="secondary">已添加的智能体目前不可用于新任务。请前往智能体库查看状态，或启用可用的智能体。</Typography.Paragraph> : null}
  </div>
}

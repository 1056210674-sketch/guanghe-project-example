import { PlusOutlined, RobotOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Empty, Modal, Space, Spin, Tabs, Tag, Typography } from 'antd'
import { useState } from 'react'
import type { AgentDefinition, AgentInstallation } from '../../api/agentPlatformApi'
import { findInstalledAgentDefinition, isAgentInstallationAvailable, isInstalledAgentVersionAvailable } from './installedAgent'

const capabilityNames: Record<string, string> = {
  chat: '对话', 'resources.read': '读取授权资料', 'geo.read': '查看监测结果',
  'geo.monitor': 'GEO 监测', 'article.generate': '文章创作', 'image.generate': '图片生成',
  'video.generate': '视频生成', 'media.recommend': '媒体推荐', 'text.summarize': '文本摘要',
}

export function AgentLibraryWorkspace({ agents, installations, loading, error, notice, busy, activeTab, onTabChange, onRetry, onAdd, onUse, onToggle, onRemove }: {
  agents: readonly AgentDefinition[]
  installations: readonly AgentInstallation[]
  loading: boolean
  error?: string
  notice?: string
  busy: boolean
  activeTab: string
  onTabChange: (tab: string) => void
  onRetry: () => void
  onAdd: (agentKey: string) => void
  onUse: (installationId: string) => void
  onToggle: (installation: AgentInstallation) => void
  onRemove: (installation: AgentInstallation) => void
}) {
  const [detail, setDetail] = useState<AgentDefinition>()
  const capabilities = detail ? [...new Set(detail.capabilities.map((key) => capabilityNames[key] ?? '其他已登记能力'))] : []
  const buttonsDisabled = busy || Boolean(error)
  // Java orders each key's latest visible catalogue version before any installed historical version.
  // Keep the full array for exact installed-version resolution; deduplicate only the shop display.
  const seenKeys = new Set<string>()
  const catalogueAgents = agents.filter((agent) => {
    if (seenKeys.has(agent.agentKey)) return false
    seenKeys.add(agent.agentKey)
    return true
  })
  return <section className="agent-library" aria-labelledby="agent-library-title">
    <header className="platform-page-heading"><Typography.Title level={1} id="agent-library-title">智能体</Typography.Title><Typography.Paragraph type="secondary">添加你需要的智能体，再用一句话交代任务。</Typography.Paragraph></header>
    {error ? <Alert type="error" showIcon title={error} action={<Button onClick={onRetry}>重新加载</Button>} /> : null}
    {notice ? <Alert type="success" showIcon title={notice} /> : null}
    <Tabs activeKey={activeTab} onChange={onTabChange} items={[
      { key: 'catalog', label: '智能体库', children: loading ? <Spin description="正在加载智能体库…" /> : catalogueAgents.length === 0 ? <Empty description={error ? '暂时无法显示智能体库' : '暂无已发布的智能体'} /> : <div className="agent-card-grid">{catalogueAgents.map((agent) => {
        const installed = installations.find((item) => item.agentKey === agent.agentKey)
        return <Card key={agent.agentKey} className="agent-card">
          <div className="agent-card-heading"><span className="agent-avatar"><RobotOutlined /></span><div><Typography.Title level={2}>{agent.name}</Typography.Title><Typography.Text type="secondary">版本 {agent.version}</Typography.Text></div></div>
          <Typography.Paragraph>{agent.description}</Typography.Paragraph>
          <Space wrap><Tag color={agent.status === 'PUBLISHED' ? 'default' : 'warning'}>{agent.status === 'PUBLISHED' ? installed ? '已添加' : '可添加' : agent.status === 'DISABLED' ? '暂时停用' : '已下架'}</Tag>{installed && !installed.enabled ? <Tag>我的智能体已停用</Tag> : null}</Space>
          <div className="agent-card-actions"><Button onClick={() => setDetail(agent)}>了解能力</Button>{installed ? <Button type="primary" disabled={buttonsDisabled || !isAgentInstallationAvailable(installed, agents)} onClick={() => onUse(installed.id)}>使用 {agent.name}</Button> : <Button type="primary" icon={<PlusOutlined />} disabled={buttonsDisabled || agent.status !== 'PUBLISHED' || Boolean(agent.installedInstallationId)} onClick={() => onAdd(agent.agentKey)}>添加 {agent.name}</Button>}</div>
        </Card>
      })}</div> },
      { key: 'installed', label: '我的智能体', children: loading ? <Spin description="正在加载我的智能体…" /> : installations.length === 0 ? <Empty description="还没有添加智能体"><Button onClick={() => onTabChange('catalog')}>前往智能体库</Button></Empty> : <div className="agent-card-grid">{installations.map((installation) => {
        const definition = findInstalledAgentDefinition(installation, agents)
        const catalogueDefinition = catalogueAgents.find((item) => item.agentKey === installation.agentKey)
        const available = isInstalledAgentVersionAvailable(definition)
        return <Card key={installation.id} className="agent-card">
          <div className="agent-card-heading"><span className="agent-avatar"><RobotOutlined /></span><div><Typography.Title level={2}>{installation.name}</Typography.Title><Typography.Text type="secondary">版本 {installation.agentVersion}</Typography.Text></div></div>
          {definition ? <Typography.Paragraph>{definition.description}</Typography.Paragraph> : null}
          <Tag color={available && installation.enabled ? 'success' : 'default'}>{!available ? '暂不可用' : installation.enabled ? '可使用' : '已停用'}</Tag>
          {!available ? <Typography.Paragraph type="secondary">当前智能体暂不可执行新任务，已有记录仍可查看。</Typography.Paragraph> : null}
          {catalogueDefinition && catalogueDefinition.version !== installation.agentVersion ? <Typography.Paragraph type="secondary">智能体库已提供版本 {catalogueDefinition.version}。已有会话保留原版本。</Typography.Paragraph> : null}
          <div className="agent-card-actions"><Button type="primary" disabled={buttonsDisabled || !isAgentInstallationAvailable(installation, agents)} onClick={() => onUse(installation.id)}>使用 {installation.name}</Button><Button disabled={buttonsDisabled || (!installation.enabled && !available)} onClick={() => onToggle(installation)}>{installation.enabled ? '停用' : '启用'}</Button><Button type="text" danger disabled={buttonsDisabled} onClick={() => onRemove(installation)}>移除</Button></div>
        </Card>
      })}</div> },
    ]} />
    <Modal open={Boolean(detail)} title={detail?.name} footer={<Button onClick={() => setDetail(undefined)}>关闭</Button>} onCancel={() => setDetail(undefined)}>
      <Typography.Paragraph>{detail?.description}</Typography.Paragraph>
      <Typography.Paragraph type="secondary">版本 {detail?.version}</Typography.Paragraph>
      <Space wrap>{capabilities.map((label) => <Tag key={label}>{label}</Tag>)}</Space>
    </Modal>
  </section>
}

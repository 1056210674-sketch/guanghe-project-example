import { DeleteOutlined, EditOutlined, FileTextOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Collapse, Empty, List, Space, Spin, Tabs, Tag, Typography } from 'antd'
import type { ReactNode } from 'react'
import type { KnowledgeDocument, KnowledgeDocumentDetail, MemoryPreference } from '../../api/knowledgeApi'
import { formatDateTime } from '../../shared/format'

interface KnowledgeWorkspaceProps {
  projectName?: string
  projectAvailable: boolean
  loading: boolean
  error?: string
  documents: readonly KnowledgeDocument[]
  detail?: KnowledgeDocumentDetail
  detailLoading: boolean
  detailError?: string
  memories: readonly MemoryPreference[]
  personalMemoriesLoading: boolean
  personalMemoriesError?: string
  projectMemoriesLoading: boolean
  projectMemoriesError?: string
  effectiveMemoryContent?: ReactNode
  busy: boolean
  onReload: () => void
  onSelectDocument: (document: KnowledgeDocument) => void
  onCreateDocument: () => void
  onEditDocument: (document: KnowledgeDocumentDetail) => void
  onDeleteDocument: (document: KnowledgeDocument) => void
}

const SOURCE_LABELS: Record<string, string> = {
  USER_TEXT: '粘贴文本', TEXT: '粘贴文本', PASTED_TEXT: '粘贴文本', TXT: '文本文件', MARKDOWN: 'Markdown 文件', PDF: 'PDF 文件',
  FILE: '上传文件', FILE_TXT: '文本文件', FILE_MARKDOWN: 'Markdown 文件', FILE_PDF: 'PDF 文件', LINK_PASTED_TEXT: '网页摘录',
}
const STATUS_LABELS: Record<string, string> = { READY: '可引用', UPLOADING: '上传中', PARSING: '解析中', PROCESSING: '处理中', FAILED: '解析失败', PENDING: '待处理', REVOKED: '已移除', DELETED: '已移除' }
const parseFailure = (code: string | null) => ({
  KNOWLEDGE_PDF_NO_EXTRACTABLE_TEXT: '文件没有可提取的文字，扫描 PDF 暂不支持。请粘贴正文或上传文本文件。',
  KNOWLEDGE_PDF_PROTECTED: 'PDF 已加密或禁止提取内容，请提供可读取的文本文件。',
  KNOWLEDGE_PARSE_TIMEOUT: '文件解析超时，请减少内容后重新添加。',
  KNOWLEDGE_UTF8_REQUIRED: '文本文件需要使用 UTF-8 编码。',
  KNOWLEDGE_UPLOAD_INTERRUPTED: '上传曾被中断，请重新添加文件。',
  KNOWLEDGE_FILE_TYPE_MISMATCH: '文件内容与类型不一致，请检查文件。',
} as Record<string, string>)[code ?? ''] ?? '文件没有解析成功，请换成文本资料或重新上传。'

export function KnowledgeWorkspace(props: KnowledgeWorkspaceProps) {
  const memories = (scope: 'PERSONAL' | 'PROJECT') => props.memories.filter((memory) => memory.scope === scope)
  const renderMemories = (scope: 'PERSONAL' | 'PROJECT') => <Card title={scope === 'PERSONAL' ? '旧版账号偏好' : '本项目旧版偏好'} className="knowledge-card">
    {scope === 'PROJECT' && !props.projectName ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择一个项目后查看其偏好" />
      : (scope === 'PERSONAL' ? props.personalMemoriesError : props.projectMemoriesError) ? <Alert type="error" showIcon title={scope === 'PERSONAL' ? props.personalMemoriesError : props.projectMemoriesError} action={<Button onClick={props.onReload}>重新加载</Button>} />
        : (scope === 'PERSONAL' ? props.personalMemoriesLoading : props.projectMemoriesLoading) ? <Spin /> : <List
      dataSource={[...memories(scope)]} locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无已保存的偏好" /> }}
      renderItem={(memory) => <List.Item><List.Item.Meta title={memory.content} description={`${memory.sourceType === 'USER_MESSAGE' ? '来源：对话中设置' : '来源：手动设置'} · 版本 ${memory.version} · 更新于 ${formatDateTime(memory.updatedAt)}`} /></List.Item>} />}
  </Card>
  return <Tabs items={[
    { key: 'documents', label: '项目资料', children: <>
      <div className="knowledge-toolbar"><Typography.Paragraph type="secondary">管理当前项目的品牌资料，供智能体在对话中引用。</Typography.Paragraph>
        <Space><Button icon={<ReloadOutlined />} onClick={props.onReload}>刷新</Button><Button type="primary" icon={<PlusOutlined />} disabled={!props.projectAvailable || props.busy} onClick={props.onCreateDocument}>添加资料</Button></Space></div>
      {!props.projectName ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择客户项目后，管理这个品牌的资料。" /> : props.error ? <Alert type="error" showIcon title={props.error} action={<Button onClick={props.onReload}>重新加载</Button>} /> : props.loading ? <Spin /> : <div className="knowledge-layout">
        <Card title={props.projectName} className="knowledge-card"><List dataSource={[...props.documents]} locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="这个项目还没有资料" /> }}
          renderItem={(document) => <List.Item className={props.detail?.id === document.id ? 'knowledge-document--selected' : undefined} actions={[
            <Button key="delete" type="text" danger icon={<DeleteOutlined />} aria-label={`移除资料 ${document.name}`} disabled={props.busy || !props.projectAvailable} onClick={() => props.onDeleteDocument(document)} />,
          ]}><List.Item.Meta avatar={<FileTextOutlined />} title={<Button type="link" className="knowledge-document-name" onClick={() => props.onSelectDocument(document)}>{document.name}</Button>}
            description={<Space wrap><Tag color={document.status === 'READY' ? 'success' : document.status === 'FAILED' ? 'error' : 'processing'}>{STATUS_LABELS[document.status] ?? document.status}</Tag><span>{SOURCE_LABELS[document.sourceType] ?? '项目资料'} · v{document.version}</span></Space>} /></List.Item>} /></Card>
        <Card title="资料内容" className="knowledge-card" extra={props.detail ? <Button icon={<EditOutlined />} disabled={props.busy || !props.projectAvailable || props.detail.readLimitApplied || ['UPLOADING', 'PARSING'].includes(props.detail.status)} onClick={() => props.detail && props.onEditDocument(props.detail)}>修订文本</Button> : null}>
          {props.detailError ? <Alert type="error" showIcon title={props.detailError} /> : props.detailLoading ? <Spin /> : props.detail ? <>
            <Typography.Title level={4}>{props.detail.name}</Typography.Title>
            <Space wrap><Tag>{STATUS_LABELS[props.detail.status] ?? props.detail.status}</Tag><Typography.Text type="secondary">版本 {props.detail.version} · {props.detail.paragraphCount} 段</Typography.Text>{props.detail.readLimitApplied ? <Tag>内容节选</Tag> : null}</Space>
            {props.detail.sourceUrl ? <Typography.Paragraph className="knowledge-source" type="secondary">摘录来源：{props.detail.sourceUrl}</Typography.Paragraph> : null}
            {props.detail.status === 'FAILED' ? <Alert type="warning" showIcon title="资料解析失败" description={parseFailure(props.detail.failureCode)} /> : null}
            <div className="knowledge-paragraphs">{props.detail.paragraphs.map((paragraph) => <Typography.Paragraph key={paragraph.index}>{paragraph.page ? <small>第 {paragraph.page} 页 · </small> : null}{paragraph.text}</Typography.Paragraph>)}</div>
          </> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择一份资料查看来源和解析内容" />}
        </Card></div>}
    </> },
    { key: 'memories', label: '记忆与偏好', children: <>
      <div className="knowledge-toolbar"><Typography.Text type="secondary">项目记忆只属于当前客户；账号通用偏好单独管理。</Typography.Text><Button icon={<ReloadOutlined />} disabled={props.busy} onClick={props.onReload}>刷新</Button></div>
      {props.effectiveMemoryContent}
      {props.memories.length || props.personalMemoriesError || props.projectMemoriesError ? <Collapse className="knowledge-legacy-memories" items={[{ key: 'legacy', label: '历史偏好（只读存档）', children: <>
        <Typography.Paragraph type="secondary">保留旧版记录供查阅，不会自动应用到智能体回复。当前使用的品牌事实和表达偏好统一在上方管理。</Typography.Paragraph>
        <div className="knowledge-memory-grid">{renderMemories('PROJECT')}{renderMemories('PERSONAL')}</div>
      </> }]} /> : null}
    </> },
  ]} />
}

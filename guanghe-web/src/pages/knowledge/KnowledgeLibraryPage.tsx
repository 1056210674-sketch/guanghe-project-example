import { DeleteOutlined, EditOutlined, UploadOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Input, Modal, Pagination, Progress, Radio, Select, Space, Statistic, Typography } from 'antd'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { isApiError } from '../../api/apiError'
import { updateProject, getProjectBusinessHistory, type Project } from '../../api/agentPlatformApi'
import { createKnowledgeDocument, deleteKnowledgeDocument, getKnowledgeDocument, listKnowledgeDocuments, listMemoryPreferences, updateKnowledgeDocument, uploadKnowledgeDocument } from '../../api/knowledgeApi'
import type { KnowledgeDocument, KnowledgeDocumentDetail } from '../../api/knowledgeApi'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import { KnowledgeWorkspace } from '../../features/agent-platform/KnowledgeWorkspace'
import { agentPlatformKeys, useProjects } from '../../features/agent-platform/queries'
import { UserShell } from '../../layouts/UserShell'
import { useAuthStore } from '../../stores/authStore'
import { ProjectBrandProfileCard } from './ProjectBrandProfileCard'
import { useMemoryEntries } from './useMemoryEntries'
import '../../styles/knowledge.css'

type AddMode = 'document' | 'excerpt' | 'file'
type DocumentReadyHandler = (document: KnowledgeDocumentDetail) => void
type DocumentEditor = { document?: KnowledgeDocumentDetail; conflicted?: boolean; type: 'text' | 'file' | 'excerpt'; name: string; text: string; sourceUrl: string; file?: File }
const failureMessage = (error: unknown) => isApiError(error) && error.status === 409
  ? '内容或版本已变化，已重新加载。你填写的内容仍保留，请核对后重新操作。'
  : isApiError(error) ? error.message : '操作未完成，请稍后重试。'
const keys = (owner?: string, project?: string) => ['project-knowledge', owner ?? null, project ?? null] as const

export function KnowledgeLibraryPanel({ initialProjectId, add, lockProject = false, onUseDocument }: { initialProjectId?: string; add?: AddMode; lockProject?: boolean; onUseDocument?: DocumentReadyHandler }) {
  const { modal } = App.useApp()
  const owner = useAuthStore((state) => state.user?.id)
  const projects = useProjects()
  const [projectId, setProjectId] = useState(initialProjectId)
  const project = projects.data?.find((item) => item.id === projectId)
  const validProjectId = !projects.isError ? project?.id : undefined
  return <section className="knowledge-page" aria-label="资料与记忆管理">
    <header className="knowledge-heading"><div><Typography.Title level={1}>品牌知识库</Typography.Title><Typography.Text type="secondary">选择客户项目，统一管理品牌公开名称、资料、事实和表达偏好。</Typography.Text></div>
      <Select className="knowledge-project-selector" aria-label="资料所属项目" placeholder="选择项目" allowClear disabled={lockProject} loading={projects.isLoading} value={projectId}
        options={(projects.data ?? []).map((item) => ({ value: item.id, label: item.name + (item.status === 'ARCHIVED' ? '（已归档）' : '') }))}
        onChange={(value?: string) => {
          const editor = document.querySelector('[data-knowledge-dirty="true"]')
          if (!editor) { setProjectId(value); return }
          modal.confirm({ title: '离开未保存的编辑？', content: '尚未保存的资料或偏好不会提交。', okText: '放弃编辑', cancelText: '继续编辑', onOk: () => setProjectId(value) })
        }} /></header>
    {projects.isError ? <Alert type="error" showIcon title="项目加载失败，暂不能选择资料归属" action={<Button onClick={() => void projects.refetch()}>重新加载</Button>} /> : projectId && !projects.isLoading && !project ? <Alert type="warning" showIcon title="这个项目不可访问，请选择其他项目" /> : null}
    <KnowledgeContent key={`${owner ?? ''}:${validProjectId ?? ''}`} project={validProjectId ? project : undefined} add={add} onUseDocument={onUseDocument} />
  </section>
}

function KnowledgeContent({ project, add, onUseDocument }: { project?: Project; add?: AddMode; onUseDocument?: DocumentReadyHandler }) {
  const { modal, message } = App.useApp()
  const owner = useAuthStore((state) => state.user?.id)
  const client = useQueryClient()
  const [pageSession] = useState(captureAuthSession)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const busyLock = useRef(false)
  const projectId = project?.id
  const writable = project?.status === 'ACTIVE'
  const [page, setPage] = useState(1)
  const [selectedId, setSelectedId] = useState<string>()
  const [editor, setEditor] = useState<DocumentEditor | undefined>(() => writable && add ? { type: add === 'excerpt' ? 'excerpt' : add === 'file' ? 'file' : 'text', name: '', text: '', sourceUrl: '' } : undefined)
  const [projectEditor, setProjectEditor] = useState<string>()
  const [busy, setBusy] = useState(false)
  const memoryEntries = useMemoryEntries(project, busy)
  const [progress, setProgress] = useState<number>()
  const [actionError, setActionError] = useState<string>()
  const documents = useQuery({ queryKey: [...keys(owner, projectId), 'documents', page], queryFn: () => listKnowledgeDocuments(projectId!, page), enabled: Boolean(owner && projectId),
    refetchInterval: (query) => query.state.data?.items.some((item: KnowledgeDocument) => ['UPLOADING', 'PARSING'].includes(item.status)) ? 3000 : false, refetchIntervalInBackground: false })
  const detail = useQuery({ queryKey: [...keys(owner, projectId), 'detail', selectedId], queryFn: () => getKnowledgeDocument(projectId!, selectedId!), enabled: Boolean(owner && projectId && selectedId),
    refetchInterval: (query) => query.state.data && ['UPLOADING', 'PARSING'].includes(query.state.data.status) ? 3000 : false, refetchIntervalInBackground: false })
  const personal = useQuery({ queryKey: [...keys(owner), 'preferences'], queryFn: () => listMemoryPreferences(), enabled: Boolean(owner) })
  const projectMemories = useQuery({ queryKey: [...keys(owner, projectId), 'preferences'], queryFn: () => listMemoryPreferences(projectId), enabled: Boolean(owner && projectId) })
  const history = useQuery({ queryKey: [...keys(owner, projectId), 'business-history'], queryFn: () => getProjectBusinessHistory(projectId!), enabled: Boolean(owner && projectId) })
  const current = () => alive.current && Boolean(owner) && isCurrentAuthSession(pageSession)
  const reload = () => Promise.all([client.invalidateQueries({ queryKey: ['project-knowledge', owner ?? null] }), client.invalidateQueries({ queryKey: agentPlatformKeys.projects(owner) })])
  const perform = async (operation: () => Promise<unknown>, after?: () => void) => {
    if (busyLock.current || memoryEntries.busy || !current()) return
    busyLock.current = true
    const session = captureAuthSession()
    setBusy(true); setActionError(undefined)
    try {
      await operation()
      await reload()
      if (current() && isCurrentAuthSession(session)) after?.()
    } catch (error) {
      await reload()
      if (current() && isCurrentAuthSession(session)) setActionError(failureMessage(error))
    } finally { busyLock.current = false; if (current() && isCurrentAuthSession(session)) { setBusy(false); setProgress(undefined) } }
  }
  const closeEditor = () => {
    if (busy) return
    if (editor && (editor.name || editor.text || editor.sourceUrl || editor.file)) modal.confirm({ title: '放弃这次编辑？', okText: '放弃编辑', cancelText: '继续编辑', onOk: () => setEditor(undefined) })
    else setEditor(undefined)
  }
  const saveDocument = () => {
    if (!editor || !projectId || !writable || busy || editor.conflicted || !editor.name.trim()) return
    if (editor.type !== 'file' && !editor.text.trim()) return
    if (editor.type === 'excerpt') {
      try { if (!['https:', 'http:'].includes(new URL(editor.sourceUrl).protocol)) throw new Error() }
      catch { setActionError('请输入有效的 HTTP 或 HTTPS 来源链接，并粘贴希望使用的正文。'); return }
    }
    const body = { name: editor.name.trim(), text: editor.text, ...(editor.type === 'excerpt' ? { sourceUrl: editor.sourceUrl.trim() } : {}) }
    void perform(async () => {
      const result = editor.document ? await updateKnowledgeDocument(projectId, editor.document.id, { ...body, version: editor.document.version }).catch((error: unknown) => { if (current() && isApiError(error) && error.status === 409) setEditor((value) => value ? { ...value, conflicted: true } : value); throw error })
        : editor.type === 'file' && editor.file ? await uploadKnowledgeDocument(projectId, editor.file, editor.name, (value) => { if (current()) setProgress(value) })
          : await createKnowledgeDocument(projectId, body)
      if (current()) setSelectedId(result.id)
      if (result.status === 'FAILED' && current()) void message.warning('资料已保存，但没有解析成功；请查看原因，不会作为已读内容使用。')
    }, () => setEditor(undefined))
  }
  const removeDocument = (document: KnowledgeDocument) => modal.confirm({ title: `移除“${document.name}”？`, content: '后续运行将不再引用该资料。已生成成果和历史账务不会删除。', okText: '移除资料', cancelText: '取消', okButtonProps: { danger: true }, onOk: () => perform(() => deleteKnowledgeDocument(document.projectId, document.id, document.version), () => { if (selectedId === document.id) setSelectedId(undefined) }) })
  const updateProjectStatus = () => {
    if (!project) return
    const archived = project.status === 'ARCHIVED'
    modal.confirm({ title: archived ? '恢复项目？' : '归档项目？', content: archived ? '恢复后可以继续开始任务；已暂停的自动监测计划不会自动恢复。' : '归档后不能在本项目开始新的业务执行，自动监测计划将暂停。历史成果仍可查看。', okText: archived ? '恢复项目' : '归档项目', cancelText: '取消', onOk: () => perform(() => updateProject(project.id, { version: project.version, status: archived ? 'ACTIVE' : 'ARCHIVED' })) })
  }
  const memoryItems = [...new Map([...(personal.isError ? [] : personal.data ?? []).filter((item) => item.scope === 'PERSONAL' && item.projectId == null), ...(projectMemories.isError ? [] : projectMemories.data ?? []).filter((item) => item.scope === 'PROJECT' && item.projectId === projectId)].map((item) => [item.id, item])).values()]
  return <div data-knowledge-busy={busy || memoryEntries.busy}>
    {onUseDocument && detail.isSuccess && detail.data.projectId === projectId && detail.data.status === 'READY' ? <Alert type="success" showIcon
      title={`“${detail.data.name}”已保存到本项目，可用于聊天`}
      description="智能体会结合相关内容整理资料；如有遗漏或冲突会继续询问。不会自动启动监测或投稿。"
      action={<Button disabled={!writable || busy || memoryEntries.busy} onClick={() => { if (current()) onUseDocument(detail.data) }}>回到聊天，整理资料</Button>} /> : null}
    {project ? <Space wrap className="knowledge-toolbar"><Typography.Text strong>{project.name}</Typography.Text><Button icon={<EditOutlined />} disabled={busy || memoryEntries.busy} onClick={() => { setProjectEditor(project.name); setActionError(undefined) }}>重命名项目</Button><Button icon={<DeleteOutlined />} disabled={busy || memoryEntries.busy} onClick={updateProjectStatus}>{writable ? '归档项目' : '恢复项目'}</Button></Space> : null}
    {project?.status === 'ARCHIVED' ? <Alert type="info" showIcon title="项目已归档，历史资料仍可查看；恢复后才能继续添加资料或执行任务。" /> : null}
    {actionError ? <Alert type="error" showIcon title={actionError} /> : null}
    {memoryEntries.errorContent}
    {project ? <ProjectBrandProfileCard key={`${owner ?? ''}:${project.id}`} project={project} completedMemoryFields={memoryEntries.completedProfileFields}>{memoryEntries.profileContent}</ProjectBrandProfileCard> : null}
    {memoryEntries.editorContent}
    {history.data && !history.isError ? <Card size="small" className="knowledge-card"><Space size={32} wrap><Statistic title="已生成完整文章" value={history.data.writtenArticleCount} /><Statistic title="已发布文章" value={history.data.publishedArticleCount} /><Statistic title="已发布媒体记录" value={history.data.publishedPlacementCount} /></Space></Card> : history.isError ? <Typography.Paragraph type="secondary">暂时无法读取创作历史，可点击刷新重试。</Typography.Paragraph> : null}
    <KnowledgeWorkspace projectName={project?.name} projectAvailable={Boolean(writable)} loading={documents.isLoading} error={documents.error ? failureMessage(documents.error) : undefined}
      documents={documents.isError ? [] : documents.data?.items ?? []} detail={detail.isError ? undefined : detail.data} detailLoading={detail.isLoading} detailError={detail.error ? failureMessage(detail.error) : undefined}
      memories={memoryItems} personalMemoriesLoading={personal.isLoading} projectMemoriesLoading={projectMemories.isLoading}
      personalMemoriesError={personal.error ? '旧版账号偏好读取失败，请重新加载。' : undefined} projectMemoriesError={projectMemories.error ? '本项目旧版偏好读取失败，请重新加载。' : undefined}
      effectiveMemoryContent={memoryEntries.content}
      busy={busy || memoryEntries.busy} onReload={() => void reload()} onSelectDocument={(document) => setSelectedId(document.id)}
      onCreateDocument={() => { setActionError(undefined); setEditor({ type: 'text', name: '', text: '', sourceUrl: '' }) }}
      onEditDocument={(document) => { setActionError(undefined); setEditor({ document, type: document.sourceType === 'LINK_PASTED_TEXT' ? 'excerpt' : 'text', name: document.name, text: document.paragraphs.map((paragraph) => paragraph.text).join('\n\n'), sourceUrl: document.sourceUrl ?? '' }) }}
      onDeleteDocument={removeDocument} />
    {documents.data && documents.data.total > documents.data.size ? <Pagination current={page} pageSize={20} total={documents.data.total} showSizeChanger={false} onChange={(value) => { setPage(value); setSelectedId(undefined) }} /> : null}
    <Modal title={editor?.document ? '修订项目资料' : '添加项目资料'} open={Boolean(editor)} width={640} onCancel={closeEditor} okText="保存到此项目" cancelText="取消" confirmLoading={busy}
      okButtonProps={{ disabled: !writable || editor?.conflicted || !editor?.name.trim() || (editor?.type === 'file' ? !editor.file : !editor?.text.trim()) }} onOk={saveDocument} destroyOnHidden>
      {editor ? <div className="knowledge-editor" data-knowledge-dirty={Boolean(editor.name || editor.text || editor.sourceUrl || editor.file)}>
        <Typography.Text type="secondary">保存到项目“{project?.name}”。不是临时附件，不会修改当前会话的所属项目。</Typography.Text>
        {editor.document ? null : <Radio.Group value={editor.type} disabled={busy} options={[{ value: 'text', label: '粘贴文本' }, { value: 'file', label: '上传文件' }, { value: 'excerpt', label: '网页摘录' }]}
          onChange={(event) => setEditor({ ...editor, type: event.target.value as DocumentEditor['type'] })} />}
        <label><Typography.Text>资料名称</Typography.Text><Input aria-label="资料名称" maxLength={255} value={editor.name} disabled={busy} onChange={(event) => setEditor({ ...editor, name: event.target.value })} /></label>
        {editor.type === 'excerpt' ? <><label><Typography.Text>来源网页</Typography.Text><Input aria-label="来源网页" maxLength={2048} value={editor.sourceUrl} disabled={busy} onChange={(event) => setEditor({ ...editor, sourceUrl: event.target.value })} /></label><Alert type="info" title="请粘贴网页正文；仅保存你提供的摘录，不自动读取网页。" /></> : null}
        {editor.type === 'file' ? <><label><Typography.Text>TXT、Markdown 或可提取文本的 PDF，最多 20 MB</Typography.Text><Input type="file" accept=".txt,.md,.markdown,.pdf" aria-label="选择资料文件" disabled={busy} prefix={<UploadOutlined />} onChange={(event) => {
          const file = event.target.files?.[0]
          if (!file) return
          if (!/\.(txt|md|markdown|pdf)$/i.test(file.name) || file.size > 20 * 1024 * 1024 || file.size === 0) { setActionError('仅支持非空的 TXT、Markdown 和 PDF，文件不能超过 20 MB。'); setEditor({ ...editor, file: undefined }); return }
          setActionError(undefined); setEditor({ ...editor, file, name: editor.name || file.name })
        }} /></label><Typography.Text type="secondary">扫描 PDF 或无法提取文字的文件会标为解析失败，不宣称已读。</Typography.Text>{progress !== undefined ? <Progress percent={progress} /> : null}</>
          : <label><Typography.Text>正文</Typography.Text><Input.TextArea aria-label="资料正文" maxLength={100000} showCount autoSize={{ minRows: 7, maxRows: 15 }} value={editor.text} disabled={busy} onChange={(event) => setEditor({ ...editor, text: event.target.value })} /></label>}
        {actionError ? <Alert type="error" title={actionError} showIcon /> : null}
        {editor.conflicted && detail.data && !detail.isError && detail.data.id === editor.document?.id ? <Card size="small" title={`服务器当前版本 v${detail.data.version}`}><div style={{ maxHeight: 180, overflow: 'auto', whiteSpace: 'pre-wrap' }}>{detail.data.paragraphs.map((paragraph) => paragraph.text).join('\n\n')}</div><Button disabled={busy || detail.data.version === editor.document.version} onClick={() => { if (detail.data) { setEditor({ ...editor, document: detail.data, conflicted: false }); setActionError(undefined) } }}>已核对最新版本，保留我的修改</Button></Card> : null}
      </div> : null}
    </Modal>
    <Modal title="重命名项目" open={projectEditor !== undefined} okText="保存名称" cancelText="取消" confirmLoading={busy} okButtonProps={{ disabled: !projectEditor?.trim() }} onCancel={() => { if (!busy) setProjectEditor(undefined) }} onOk={() => {
      if (project && projectEditor?.trim()) void perform(() => updateProject(project.id, { version: project.version, name: projectEditor.trim() }), () => setProjectEditor(undefined))
    }}><Input aria-label="新的项目名称" maxLength={100} value={projectEditor} disabled={busy} onChange={(event) => setProjectEditor(event.target.value)} />{actionError ? <Alert type="error" title={actionError} showIcon /> : null}</Modal>
  </div>
}

export function KnowledgeLibraryPage() {
  const [params] = useSearchParams()
  const add = params.get('add')
  return <UserShell><KnowledgeLibraryPanel key={params.get('project') ?? ''} initialProjectId={params.get('project') ?? undefined} add={add === 'document' || add === 'excerpt' ? add : undefined} /></UserShell>
}

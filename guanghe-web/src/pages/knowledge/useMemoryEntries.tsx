import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Empty, Input, List, Modal, Space, Spin, Tag, Typography } from 'antd'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import type { Project } from '../../api/agentPlatformApi'
import { isApiError } from '../../api/apiError'
import { createMemoryEntry, deleteMemoryEntry, listMemoryEntries, updateMemoryEntry } from '../../api/knowledgeApi'
import type { MemoryEntry } from '../../api/knowledgeApi'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import { APP_PATHS } from '../../routes/paths'
import { formatDateTime } from '../../shared/format'
import { useAuthStore } from '../../stores/authStore'
import { BRAND_PROFILE_FIELDS, isBrandProfileEntry, type BrandProfileField } from './brandProfileFields'

interface EntryEditor {
  entry?: MemoryEntry
  scope: MemoryEntry['scope']
  kind: MemoryEntry['kind']
  key: string
  label: string
  value: string
  conflicted?: boolean
  profileField?: BrandProfileField
}

const entryKey = (owner: string | undefined, scope: MemoryEntry['scope'], projectId?: string) => ['project-knowledge', owner ?? null, projectId ?? null, scope, 'memory-entries'] as const
const failure = (error: unknown) => isApiError(error) && error.status === 409
  ? '这条记忆已变化，已重新读取当前内容。你的修改仍保留，请核对后再保存。'
  : isApiError(error) && [403, 404, 410].includes(error.status)
    ? '这条记忆或所属项目已不可操作，请重新加载后核对。'
    : isApiError(error) && error.status === 422
      ? '没有保存成功：' + error.message + '。输入的内容仍保留。'
      : '操作未完成，请稍后重试。输入的内容仍保留。'

/** The existing knowledge page owns these reads and writes; scope never comes from model-generated text. */
export function useMemoryEntries(project?: Project, externallyBusy = false) {
  const { modal } = App.useApp()
  const owner = useAuthStore((state) => state.user?.id)
  const client = useQueryClient()
  const projectId = project?.id
  const projectWritable = project?.status === 'ACTIVE'
  const [session] = useState(captureAuthSession)
  const alive = useRef(true)
  const lock = useRef(false)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const current = () => alive.current && Boolean(owner) && isCurrentAuthSession(session)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const [editor, setEditor] = useState<EntryEditor>()
  const projectEntries = useQuery({ queryKey: entryKey(owner, 'PROJECT', projectId), queryFn: () => listMemoryEntries('PROJECT', projectId, true), enabled: Boolean(owner && projectId) })
  const accountEntries = useQuery({ queryKey: entryKey(owner, 'ACCOUNT'), queryFn: () => listMemoryEntries('ACCOUNT'), enabled: Boolean(owner) })
  const disabled = busy || externallyBusy
  const queryFilter = { queryKey: ['project-knowledge', owner ?? null], predicate: (query: { queryKey: readonly unknown[] }) => query.queryKey.at(-1) === 'memory-entries' }
  const reload = () => client.invalidateQueries(queryFilter)
  const applyConfirmedChange = async (updated?: MemoryEntry, deletedId?: string) => {
    if (!current()) return
    await client.cancelQueries(queryFilter)
    if (!current()) return
    client.setQueriesData<MemoryEntry[]>(queryFilter, (items) => items?.filter((item) => item.id !== deletedId).map((item) => item.id === updated?.id ? updated : item))
    if (updated && !updated.deleted) client.setQueryData<MemoryEntry[]>(entryKey(owner, updated.scope, updated.projectId ?? undefined), (items) => [updated, ...(items ?? []).filter((item) => item.id !== updated.id)])
  }
  const perform = async (operation: () => Promise<void>, editorKey?: string) => {
    if (lock.current || externallyBusy || !current()) return
    lock.current = true; setBusy(true); setError(undefined)
    try {
      await operation()
      await reload()
      if (current() && editorKey) setEditor((value) => value?.key === editorKey ? undefined : value)
    } catch (error) {
      await reload()
      if (current()) {
        setError(failure(error))
        if (editorKey && isApiError(error) && error.status === 409) setEditor((value) => value?.key === editorKey ? { ...value, conflicted: true } : value)
      }
    } finally { lock.current = false; if (current()) setBusy(false) }
  }
  const save = () => {
    if (!editor || editor.conflicted || disabled || !editor.value.trim() || (editor.scope === 'PROJECT' && !projectWritable)) return
    const draft = editor
    const label = draft.label.trim() || draft.value.trim().slice(0, 100)
    void perform(async () => {
      const updated = draft.entry
        ? await updateMemoryEntry(draft.entry, { version: draft.entry.version, label, value: draft.value.trim(), restore: draft.entry.deleted })
        : await createMemoryEntry({ scope: draft.scope, ...(draft.scope === 'PROJECT' ? { projectId } : {}), kind: draft.kind, key: draft.key, label, value: draft.value.trim() })
      await applyConfirmedChange(updated)
    }, draft.key)
  }
  const remove = (entry: MemoryEntry) => modal.confirm({ title: `删除“${entry.label}”？`, content: '后续对话不再使用这条有效记忆。历史消息和已经生成的成果不会改写。', okText: '删除记忆', cancelText: '取消', okButtonProps: { danger: true },
    onOk: () => perform(async () => { await deleteMemoryEntry(entry.id, entry.version); await applyConfirmedChange(undefined, entry.id) }) })
  const beginPreference = (scope: MemoryEntry['scope']) => {
    if (disabled || (scope === 'PROJECT' && !projectWritable)) return
    setError(undefined)
    setEditor({ scope, kind: 'PREFERENCE', key: `preference.${crypto.randomUUID().replaceAll('-', '')}`, label: '', value: '' })
  }
  const activeProjectEntries = projectEntries.isError || !projectId ? [] : (projectEntries.data ?? []).filter((entry) => !entry.deleted && entry.scope === 'PROJECT' && entry.projectId === projectId)
  const profileEntry = (field: BrandProfileField) => activeProjectEntries.find((entry) => entry.key === field.key && entry.kind === field.kind)
  const profileRecord = (field: BrandProfileField) => projectEntries.isError ? undefined : projectEntries.data?.find((entry) => entry.scope === 'PROJECT' && entry.projectId === projectId && entry.key === field.key && entry.kind === field.kind)
  const beginProfileField = (field: BrandProfileField) => {
    if (disabled || !projectWritable || !projectEntries.isSuccess) return
    const entry = profileRecord(field)
    setError(undefined)
    setEditor({ entry, scope: 'PROJECT', kind: field.kind, key: field.key, label: field.label, value: entry?.deleted ? '' : entry?.value ?? '', profileField: field })
  }
  const source = (entry: MemoryEntry) => <div className="knowledge-memory-source"><Typography.Text type="secondary">来源：{entry.sourceType === 'USER_MESSAGE' ? '对话中提供' : '手动设置'} · 更新于 {formatDateTime(entry.updatedAt)} · 版本 {entry.version}</Typography.Text>
    {entry.sourceType === 'USER_MESSAGE' && entry.sourceConversationId && /^[1-9]\d{0,18}$/.test(entry.sourceConversationId) ? <div><a href={`${APP_PATHS.chat}?${new URLSearchParams({ conversation: entry.sourceConversationId })}`} target="_blank" rel="noopener noreferrer">查看来源对话</a></div> : null}</div>
  const renderProfileField = (field: BrandProfileField) => {
    const entry = profileEntry(field)
    const removed = profileRecord(field)?.deleted
    return <section key={field.key} className="knowledge-profile-field" aria-label={field.label}>
      <div className="knowledge-profile-field-heading"><Typography.Text strong>{field.label}</Typography.Text><Space size={4}>
        <Button size="small" aria-label={`${removed ? '重新填写' : entry ? '修改' : '补充'}${field.label}`} disabled={disabled || !projectWritable} onClick={() => beginProfileField(field)}>{removed ? '重新填写' : entry ? '修改' : '补充'}</Button>
        {entry ? <Button size="small" type="text" danger icon={<DeleteOutlined />} aria-label={`删除档案 ${field.label}`} disabled={disabled || !projectWritable} onClick={() => remove(entry)} /> : null}
      </Space></div>
      <Typography.Paragraph type="secondary">{field.hint}</Typography.Paragraph>
      <Typography.Paragraph className="knowledge-memory-value" type={entry?.value?.trim() ? undefined : 'secondary'}>{removed ? '已移除。可以重新填写新内容，不会恢复已删除的旧值。' : entry?.value?.trim() || (field.required ? '待补充' : '未设置')}</Typography.Paragraph>
      {entry ? source(entry) : null}
    </section>
  }
  const renderCard = (scope: MemoryEntry['scope']) => {
    const query = scope === 'PROJECT' ? projectEntries : accountEntries
    const unavailable = scope === 'PROJECT' && !projectId
    const entries = query.isError || unavailable ? [] : (query.data ?? []).filter((entry) => !entry.deleted && entry.scope === scope && (scope === 'ACCOUNT' ? entry.projectId === null : entry.projectId === projectId) && !isBrandProfileEntry(entry))
    return <Card className="knowledge-card" title={scope === 'PROJECT' ? '本项目有效记忆' : '账号通用偏好'}
      extra={<Button icon={<PlusOutlined />} disabled={disabled || (scope === 'PROJECT' && !projectWritable)} onClick={() => beginPreference(scope)}>添加偏好</Button>}>
      <Typography.Paragraph type="secondary">{scope === 'PROJECT'
        ? project ? `仅属于“${project.name}”，不会用于其他客户。品牌事实可以在聊天中补充，也可在这里修订。` : '先选择项目，再查看该客户的品牌事实与表达偏好。'
        : '适用于这个账号的所有项目，只保存明确的通用表达习惯；客户品牌事实请放在对应项目。'}</Typography.Paragraph>
      {unavailable ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择一个项目" /> : query.isError ? <Alert type="error" showIcon title="记忆暂时无法读取，旧内容不再展示" action={<Button onClick={() => void query.refetch()}>重新加载</Button>} /> : query.isLoading ? <Spin /> : <List dataSource={entries}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={scope === 'PROJECT' ? '这个项目还没有有效记忆' : '还没有账号通用偏好'} /> }}
        renderItem={(entry) => <List.Item className="knowledge-memory-entry" actions={[
          <Button key="edit" type="text" icon={<EditOutlined />} aria-label={`修改记忆 ${entry.label}`} disabled={disabled || (scope === 'PROJECT' && !projectWritable)} onClick={() => { setError(undefined); setEditor({ entry, scope, kind: entry.kind, key: entry.key, label: entry.label, value: entry.value ?? '' }) }} />,
          <Button key="delete" danger type="text" icon={<DeleteOutlined />} aria-label={`删除记忆 ${entry.label}`} disabled={disabled || (scope === 'PROJECT' && !projectWritable)} onClick={() => remove(entry)} />,
        ]}>
          <List.Item.Meta title={<Space wrap><Tag>{entry.kind === 'FACT' ? '品牌与业务事实' : scope === 'PROJECT' ? '项目偏好' : '账号通用偏好'}</Tag><span>{entry.label}</span></Space>}
            description={<div><Typography.Paragraph className="knowledge-memory-value">{entry.value}</Typography.Paragraph>{source(entry)}</div>} />
        </List.Item>} />}
    </Card>
  }
  const latestItems = editor?.scope === 'PROJECT' ? projectEntries : accountEntries
  const latest = !latestItems.isError ? latestItems.data?.find((entry) => (!entry.deleted || editor?.profileField) && (editor?.entry ? entry.id === editor.entry.id : entry.key === editor?.key)) : undefined
  const dirty = Boolean(editor && (editor.entry ? editor.label !== editor.entry.label || editor.value !== editor.entry.value : editor.label || editor.value))
  return {
    busy,
    errorContent: error && !editor ? <Alert className="knowledge-memory-error" type="error" showIcon title={error} /> : null,
    completedProfileFields: projectEntries.isSuccess ? BRAND_PROFILE_FIELDS.filter((field) => field.required && profileEntry(field)?.value?.trim()).length : undefined,
    profileContent: projectEntries.isError ? <Alert type="error" showIcon title="品牌档案暂时无法读取，不能视为资料缺失" action={<Button onClick={() => void projectEntries.refetch()}>重新加载品牌档案</Button>} /> : projectEntries.isLoading ? <Spin /> : <>
      <div className="knowledge-profile-fields">{BRAND_PROFILE_FIELDS.filter((field) => field.required).map(renderProfileField)}</div>
      <Typography.Title level={5} className="knowledge-profile-optional-heading">可逐步补充的偏好（选填）</Typography.Title>
      <div className="knowledge-profile-fields">{BRAND_PROFILE_FIELDS.filter((field) => !field.required).map(renderProfileField)}</div>
    </>,
    content: <>
      <div className="knowledge-memory-grid">{renderCard('PROJECT')}{renderCard('ACCOUNT')}</div>
    </>,
    editorContent: <Modal title={editor?.profileField ? `编辑品牌档案：${editor.profileField.label}` : editor?.entry ? '修改有效记忆' : '添加表达偏好'} open={Boolean(editor)} width={600} destroyOnHidden
        okText={editor?.profileField ? '保存到品牌档案' : '保存为有效记忆'} cancelText="取消" confirmLoading={busy} okButtonProps={{ disabled: disabled || editor?.conflicted || !editor?.value.trim() || (editor?.scope === 'PROJECT' && !projectWritable) }} onOk={save}
        onCancel={() => { if (disabled) return; if (!dirty) setEditor(undefined); else modal.confirm({ title: '放弃未保存的修改？', okText: '放弃修改', cancelText: '继续编辑', onOk: () => setEditor(undefined) }) }}>
        {editor ? <div className="knowledge-editor" data-knowledge-dirty={dirty}>
          <Typography.Text type="secondary">{editor.scope === 'PROJECT' ? `只保存到项目“${project?.name}”。` : '保存为账号通用偏好，会应用于所有项目；不要填写某个客户的品牌事实。'}不会授权发布或扣费。</Typography.Text>
          {editor.entry?.deleted ? <Alert type="info" showIcon title="这项资料已删除。请重新填写新内容；保存后才会重新作为有效记忆使用。" /> : null}
          {editor.profileField ? <Typography.Paragraph type="secondary">{editor.profileField.hint}</Typography.Paragraph> : <label><Typography.Text>记忆名称</Typography.Text><Input aria-label="记忆名称" maxLength={100} value={editor.label} placeholder="可留空，按内容自动命名" disabled={disabled} onChange={(event) => setEditor({ ...editor, label: event.target.value })} /></label>}
          <label><Typography.Text>{editor.profileField?.label ?? '内容'}</Typography.Text><Input.TextArea aria-label="有效记忆内容" placeholder={editor.profileField?.example} value={editor.value} maxLength={2000} showCount autoSize={{ minRows: 4, maxRows: 10 }} disabled={disabled} onChange={(event) => setEditor({ ...editor, value: event.target.value })} /></label>
          {error ? <Alert type="error" showIcon title={error} /> : null}
          {editor.conflicted ? latest ? <Card size="small" title={`当前记忆（版本 ${latest.version}）`}><Typography.Text strong>{latest.label}</Typography.Text><Typography.Paragraph>{latest.deleted ? '这条记忆已删除，保存你的新内容才会重新启用。' : latest.value}</Typography.Paragraph><Button disabled={disabled || latest.version === editor.entry?.version} onClick={() => { setEditor({ ...editor, entry: latest, kind: latest.kind, conflicted: false }); setError(undefined) }}>已核对当前记忆，保留我的修改</Button></Card>
            : <Alert type="warning" title="原记忆已删除或暂时无法读取。请取消本次编辑，不会创建替代记录。" /> : null}
        </div> : null}
      </Modal>,
  }
}

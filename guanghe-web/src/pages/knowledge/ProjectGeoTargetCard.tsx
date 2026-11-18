import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Card, Input, Space, Spin, Typography } from 'antd'
import { getProjectGeoTarget, updateProjectGeoTarget, type Project, type ProjectGeoTarget } from '../../api/agentPlatformApi'
import { isApiError } from '../../api/apiError'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import { agentPlatformKeys } from '../../features/agent-platform/queries'
import { useAuthStore } from '../../stores/authStore'
import { formatDateTime } from '../../shared/format'

type Editor = { snapshot: ProjectGeoTarget; name: string; aliasesText: string; conflicted: boolean }
const aliasesOf = (text: string) => text.split(/\r?\n/).map((item) => item.trim()).filter(Boolean)
const errorMessage = (error: unknown) => isApiError(error) ? error.message : '监测对象暂时无法保存，请稍后重试。'

/** Project-scoped public identity is explicit, not inferred from project labels or private documents. */
export function ProjectGeoTargetCard({ project, embedded = false }: { project: Project; embedded?: boolean }) {
  const { modal } = App.useApp()
  const owner = useAuthStore((state) => state.user?.id)
  const client = useQueryClient()
  const [session] = useState(captureAuthSession)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const current = () => alive.current && isCurrentAuthSession(session)
  const queryKey = ['project-geo-target', owner ?? null, project.id] as const
  const target = useQuery({ queryKey, queryFn: () => getProjectGeoTarget(project.id, session), enabled: Boolean(owner), retry: false })
  const [editor, setEditor] = useState<Editor>()
  const [busy, setBusy] = useState(false)
  const lock = useRef(false)
  const [error, setError] = useState<string>()
  const writable = project.status === 'ACTIVE'
  const aliases = editor ? aliasesOf(editor.aliasesText) : []
  const validInput = Boolean(editor && editor.name.trim().length >= 2 && editor.name.trim().length <= 100
    && aliases.length <= 50 && aliases.every((item) => item.length >= 2 && item.length <= 100))
  const dirty = Boolean(editor && (editor.name !== (editor.snapshot.name ?? '') || editor.aliasesText !== editor.snapshot.aliases.join('\n')))
  const beginEdit = () => {
    if (!target.data || target.isError || !writable || busy) return
    setEditor({ snapshot: target.data, name: target.data.name ?? '', aliasesText: target.data.aliases.join('\n'), conflicted: false })
    setError(undefined)
  }
  const cancel = () => {
    if (busy) return
    if (dirty) modal.confirm({ title: '放弃监测对象的修改？', okText: '放弃修改', cancelText: '继续编辑', onOk: () => { setEditor(undefined); setError(undefined) } })
    else { setEditor(undefined); setError(undefined) }
  }
  const save = async () => {
    if (!editor || editor.conflicted || target.isError || !validInput || !writable || lock.current || !current()) return
    lock.current = true; setBusy(true); setError(undefined)
    try {
      const result = await updateProjectGeoTarget(project.id, { version: editor.snapshot.version, name: editor.name.trim(), aliases }, session)
      if (!current()) return
      client.setQueryData(queryKey, result)
      await client.invalidateQueries({ queryKey: agentPlatformKeys.projects(owner) })
      if (current()) setEditor(undefined)
    } catch (problem) {
      if (!current()) return
      if (isApiError(problem) && problem.status === 409) {
        setEditor((value) => value ? { ...value, conflicted: true } : value)
        await target.refetch()
        if (current()) setError('监测对象或项目版本已在别处更新。你的输入仍保留，请核对当前设置后再保存。')
      } else setError(errorMessage(problem))
    } finally { lock.current = false; if (current()) setBusy(false) }
  }
  const editAction = target.data?.configured && !editor ? <Button size="small" disabled={!writable || busy || target.isError} onClick={beginEdit}>{embedded ? '修改品牌名称' : '修改监测对象'}</Button> : null
  const content = <>
    <Typography.Paragraph type="secondary">{embedded ? '客户对外使用的品牌、门店或机构名称，不能用内部项目名称代替。' : '填写客户在 AI 回答中会看到的品牌、门店或机构名称，用于核对名称提及。项目名称和资料正文不会自动当作监测对象；修改只影响后续监测。'}</Typography.Paragraph>
    {target.isError ? <Alert showIcon type="error" title="监测对象读取失败，不能猜测公开名称" action={<Button size="small" onClick={() => void target.refetch()}>重新读取监测对象</Button>} /> : null}
    {editor ? <div className="knowledge-editor" data-knowledge-dirty={dirty}>
        <label><Typography.Text>{embedded ? '对外品牌名称' : '公开监测名称'}</Typography.Text><Input aria-label={embedded ? '对外品牌名称' : '公开监测名称'} placeholder="例如：小行星咖啡店" maxLength={100} value={editor.name} disabled={busy || !writable} onChange={(event) => setEditor({ ...editor, name: event.target.value })} /></label>
        <label><Typography.Text>常用别名（选填，每行一个）</Typography.Text><Input.TextArea aria-label="监测对象别名" placeholder="只填写同一对象的真实名称，不填写咖啡、蛋糕等品类词" autoSize={{ minRows: 2, maxRows: 5 }} value={editor.aliasesText} disabled={busy || !writable} onChange={(event) => setEditor({ ...editor, aliasesText: event.target.value })} /></label>
        {!validInput ? <Typography.Text type="secondary">名称与每个别名需 2–100 个字符，最多 50 个别名。</Typography.Text> : null}
        {error ? <Alert showIcon type="error" title={error} /> : null}
        {editor.conflicted && target.data && !target.isError ? <Card size="small" title="服务器当前设置"><Typography.Paragraph>{target.data.name ?? '尚未配置公开名称'}</Typography.Paragraph><Typography.Paragraph type="secondary">{target.data.aliases.length ? target.data.aliases.join('、') : '无别名'}</Typography.Paragraph>
          <Button disabled={busy || target.isError || target.data.version === editor.snapshot.version} onClick={() => { if (target.data) { setEditor({ ...editor, snapshot: target.data, conflicted: false }); setError(undefined) } }}>已核对当前设置，保留我的修改</Button></Card> : null}
        <Space wrap><Button type="primary" loading={busy} disabled={!writable || !validInput || editor.conflicted || target.isError} onClick={() => void save()}>{embedded ? '保存品牌名称' : '保存监测对象'}</Button><Button disabled={busy} onClick={cancel}>取消修改</Button></Space>
      </div> : !target.isError && target.data?.configured ? <><Typography.Text strong>{target.data.name}</Typography.Text><Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>常用别名：{target.data.aliases.length ? target.data.aliases.join('、') : '未设置'}</Typography.Paragraph>{embedded ? <Typography.Text type="secondary">更新于 {formatDateTime(target.data.updatedAt)} · 版本 {target.data.version}</Typography.Text> : null}</>
        : !target.isError && target.data ? <Space wrap><Typography.Text>{embedded ? '待补充' : '尚未设置公开监测名称'}</Typography.Text><Button size="small" type="primary" disabled={!writable || busy} onClick={beginEdit}>{embedded ? '补充品牌名称' : '设置监测对象'}</Button></Space> : null}
  </>
  return embedded ? <section className="knowledge-profile-field" aria-label="对外品牌名称"><div className="knowledge-profile-field-heading"><Typography.Text strong>对外品牌名称</Typography.Text>{editAction}</div>{target.isLoading ? <Spin /> : content}</section>
    : <Card size="small" className="knowledge-card" style={{ marginBottom: 20 }} title="GEO 监测对象" loading={target.isLoading} extra={editAction}>{content}</Card>
}

import { Alert, App, Button, Card, Drawer, Input, Modal, Select, Space, Statistic, Table, Tabs, Tag, Typography } from 'antd'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { getAdminPlatformSummary, listAdminDefinitions, listAdminGeoPlans, listAdminGeoPlanSlots, listAdminKnowledgeMetadata, listAdminPreferenceMetadata, pauseAdminGeoPlan, resumeAdminGeoPlan, updateAdminDefinition } from '../../api/adminPlatformApi'
import type { AdminAgentDefinition, AdminGeoPlan, AdminGeoPlanSlot, AdminKnowledgeMetadata, AdminPreferenceMetadata } from '../../api/adminPlatformApi'
import { isApiError } from '../../api/apiError'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import { AdminShell } from '../../layouts/AdminShell'
import { useAuthStore } from '../../stores/authStore'

const errorText = (error: unknown) => isApiError(error) ? error.message + (error.status === 409 ? ' 已重新读取，核对当前版本后再操作。' : '') : '读取或操作失败，请重试。'
const scheduleName = { HOURLY: '每小时', DAILY: '每天', WEEKLY: '每周', INTERVAL_DAYS: '按天间隔' }
const definitionStatus = { PUBLISHED: '已发布', DISABLED: '已停用', WITHDRAWN: '已撤回' }
const time = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN') : '—'

export function AdminPlatformPage() {
  const owner = useAuthStore((state) => state.user?.id)
  return <AdminShell><AdminPlatformContent key={owner} owner={owner} /></AdminShell>
}
export function AdminPlatformContent({ owner }: { owner?: string }) {
  const { modal } = App.useApp()
  const client = useQueryClient()
  const [pageSession] = useState(captureAuthSession)
  const alive = useRef(true)
  const lock = useRef(false)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const [tab, setTab] = useState('agents')
  const [page, setPage] = useState(1)
  const [docPage, setDocPage] = useState(1)
  const [memoryPage, setMemoryPage] = useState(1)
  const [filters, setFilters] = useState<{ userId?: string; projectId?: string; status?: 'ACTIVE' | 'PAUSED' }>({})
  const [draftFilters, setDraftFilters] = useState({ userId: '', projectId: '', status: undefined as 'ACTIVE' | 'PAUSED' | undefined })
  const [slotsPlan, setSlotsPlan] = useState<AdminGeoPlan>()
  const [slotPage, setSlotPage] = useState(1)
  const [pausePlan, setPausePlan] = useState<AdminGeoPlan>()
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string>()
  const key = ['admin-platform', owner ?? null] as const
  const definitions = useQuery({ queryKey: [...key, 'definitions'], queryFn: listAdminDefinitions, enabled: Boolean(owner) })
  const plans = useQuery({ queryKey: [...key, 'plans', filters, page], queryFn: () => listAdminGeoPlans({ ...filters, page, size: 20 }), enabled: Boolean(owner && tab === 'plans') })
  const slots = useQuery({ queryKey: [...key, 'slots', slotsPlan?.id, slotPage], queryFn: () => listAdminGeoPlanSlots(slotsPlan!.id, slotPage), enabled: Boolean(owner && slotsPlan) })
  const summary = useQuery({ queryKey: [...key, 'metadata-summary'], queryFn: getAdminPlatformSummary, enabled: Boolean(owner && tab === 'metadata') })
  const docs = useQuery({ queryKey: [...key, 'documents', docPage], queryFn: () => listAdminKnowledgeMetadata(docPage), enabled: Boolean(owner && tab === 'metadata') })
  const memories = useQuery({ queryKey: [...key, 'memories', memoryPage], queryFn: () => listAdminPreferenceMetadata(memoryPage), enabled: Boolean(owner && tab === 'metadata') })
  const reload = () => client.invalidateQueries({ queryKey: key })
  const perform = async (operation: (session: ReturnType<typeof captureAuthSession>) => Promise<unknown>, after?: () => void) => {
    if (lock.current || !alive.current || !isCurrentAuthSession(pageSession)) return
    const session = captureAuthSession(); lock.current = true; setBusy(true); setActionError(undefined)
    try { await operation(session); await reload(); if (alive.current && isCurrentAuthSession(session)) after?.() }
    catch (error) { await reload(); if (alive.current && isCurrentAuthSession(session)) setActionError(errorText(error)) }
    finally { lock.current = false; if (alive.current && isCurrentAuthSession(session)) setBusy(false) }
  }
  const changeDefinition = (item: AdminAgentDefinition, change: { status?: AdminAgentDefinition['status']; visibility?: AdminAgentDefinition['visibility'] }) => modal.confirm({
    title: change.visibility ? '更改智能体可见范围？' : '更改智能体版本状态？',
    content: `${item.name} · ${item.version}。既有历史记录不会删除。停用后不能继续开始新的任务；已执行任务不会因此撤销。`,
    okText: '确认更改', cancelText: '取消',
    onOk: () => perform((session) => updateAdminDefinition(item, { expectedStatus: item.status, expectedVisibility: item.visibility, definitionHash: item.definitionHash, ...change }, session)),
  })
  const readError = (error: unknown, retry: () => unknown) => error ? <Alert type="error" showIcon title={errorText(error)} action={<Button onClick={() => void retry()}>重新加载</Button>} /> : null
  const openSlots = (plan: AdminGeoPlan) => { setSlotPage(1); setSlotsPlan(plan) }
  return <section className="page-stack">
    <Space className="page-heading" style={{ width: '100%', justifyContent: 'space-between' }}><div><Typography.Title level={2}>智能体平台</Typography.Title><Typography.Text type="secondary">管理已发布智能体、自动监测和资料处理状态。不展示用户资料或记忆正文。</Typography.Text></div><Button onClick={() => void reload()}>刷新</Button></Space>
    {actionError ? <Alert type="error" title={actionError} showIcon /> : null}
    <Tabs activeKey={tab} onChange={setTab} items={[
      { key: 'agents', label: '智能体目录', children: <Card>
        {readError(definitions.error, definitions.refetch)}
        <Table<AdminAgentDefinition> rowKey={(item) => `${item.agentKey}:${item.version}`} loading={definitions.isLoading} dataSource={definitions.isError ? [] : definitions.data} scroll={{ x: 960 }} pagination={false} columns={[
          { title: '智能体', dataIndex: 'name', render: (value: string, item) => <div><strong>{value}</strong><div>{item.agentKey} · {item.version}</div></div> },
          { title: '可见范围', dataIndex: 'visibility', render: (value: AdminAgentDefinition['visibility']) => value === 'PUBLIC' ? '用户可见' : '内部可见' },
          { title: '状态', dataIndex: 'status', render: (value: AdminAgentDefinition['status']) => <Tag color={value === 'PUBLISHED' ? 'success' : undefined}>{definitionStatus[value]}</Tag> },
          { title: '已安装', dataIndex: 'installationCount' }, { title: '发布时间', dataIndex: 'publishedAt', render: time },
          { title: '操作', render: (_, item) => <Space wrap><Button size="small" disabled={busy || definitions.isError} onClick={() => changeDefinition(item, { status: item.status === 'PUBLISHED' ? 'DISABLED' : 'PUBLISHED' })}>{item.status === 'PUBLISHED' ? '停用' : '发布'}</Button><Button size="small" disabled={busy || definitions.isError} onClick={() => changeDefinition(item, { visibility: item.visibility === 'PUBLIC' ? 'INTERNAL' : 'PUBLIC' })}>{item.visibility === 'PUBLIC' ? '设为内部' : '向用户开放'}</Button>{item.status !== 'WITHDRAWN' ? <Button danger size="small" disabled={busy || definitions.isError} onClick={() => changeDefinition(item, { status: 'WITHDRAWN' })}>撤回</Button> : null}</Space> },
        ]} />
      </Card> },
      { key: 'plans', label: '自动监测', children: <Card>
        <Space wrap style={{ marginBottom: 16 }}><Input aria-label="筛选用户编号" placeholder="用户编号" value={draftFilters.userId} onChange={(event) => setDraftFilters({ ...draftFilters, userId: event.target.value.replace(/\D/g, '') })} /><Input aria-label="筛选项目编号" placeholder="项目编号" value={draftFilters.projectId} onChange={(event) => setDraftFilters({ ...draftFilters, projectId: event.target.value.replace(/\D/g, '') })} /><Select aria-label="计划状态" style={{ width: 140 }} value={draftFilters.status} allowClear placeholder="全部状态" options={[{ value: 'ACTIVE', label: '启用' }, { value: 'PAUSED', label: '暂停' }]} onChange={(status: 'ACTIVE' | 'PAUSED' | undefined) => setDraftFilters({ ...draftFilters, status })} /><Button onClick={() => { setPage(1); setFilters({ userId: draftFilters.userId || undefined, projectId: draftFilters.projectId || undefined, status: draftFilters.status }) }}>筛选</Button></Space>
        {readError(plans.error, plans.refetch)}
        <Table<AdminGeoPlan> rowKey="id" loading={plans.isLoading} dataSource={plans.isError ? [] : plans.data?.items} scroll={{ x: 1000 }} pagination={{ current: page, pageSize: 20, total: plans.data?.total ?? 0, showSizeChanger: false, onChange: setPage }} columns={[
          { title: '计划 / 用户 / 项目', render: (_, item) => <div>{item.id}<div>用户 {item.userId} · 项目 {item.projectId}</div></div> }, { title: '频率', dataIndex: 'scheduleType', render: (value: AdminGeoPlan['scheduleType']) => scheduleName[value] },
          { title: '状态', render: (_, item) => <div><Tag color={item.status === 'ACTIVE' ? 'success' : undefined}>{item.status === 'ACTIVE' ? '启用' : '暂停'}</Tag>{item.pauseReason ? <Typography.Paragraph>{item.pauseReason}</Typography.Paragraph> : null}</div> },
          { title: '单次积分上限', dataIndex: 'maxPointsPerRun' }, { title: '下次执行', dataIndex: 'nextRunAt', render: time },
          { title: '操作', render: (_, item) => <Space><Button size="small" onClick={() => openSlots(item)}>执行记录</Button><Button size="small" disabled={busy || plans.isError} onClick={() => item.status === 'ACTIVE' ? (setReason(''), setPausePlan(item)) : modal.confirm({ title: '恢复自动监测？', content: `恢复计划 ${item.id}，仍受原有单次积分上限约束，不补跑所有历史时段。`, okText: '恢复监测', cancelText: '取消', onOk: () => perform((session) => resumeAdminGeoPlan(item, session)) })}>{item.status === 'ACTIVE' ? '暂停' : '恢复'}</Button></Space> },
        ]} />
      </Card> },
      { key: 'metadata', label: '资料与记忆状态', children: <>
        {readError(summary.error, summary.refetch)}
        {summary.data && !summary.isError ? <Card><Space size={24} wrap><Statistic title="资料总数" value={summary.data.knowledgeDocumentCount} /><Statistic title="可引用资料" value={summary.data.readyKnowledgeDocumentCount} /><Statistic title="解析失败" value={summary.data.failedKnowledgeDocumentCount} /><Statistic title="个人偏好" value={summary.data.activePersonalPreferenceCount} /><Statistic title="项目偏好" value={summary.data.activeProjectPreferenceCount} /></Space></Card> : null}
        <Card title="项目资料处理记录" style={{ marginTop: 16 }}>{readError(docs.error, docs.refetch)}<Table<AdminKnowledgeMetadata> rowKey="id" loading={docs.isLoading} dataSource={docs.isError ? [] : docs.data?.items} scroll={{ x: 900 }} pagination={{ current: docPage, pageSize: 20, total: docs.data?.total ?? 0, showSizeChanger: false, onChange: setDocPage }} columns={[
          { title: '记录编号', dataIndex: 'id' }, { title: '用户', dataIndex: 'userId' }, { title: '项目', dataIndex: 'projectId' }, { title: '类型', dataIndex: 'sourceType' }, { title: '处理状态', dataIndex: 'status' }, { title: '版本', dataIndex: 'version' }, { title: '字节数', dataIndex: 'sizeBytes' }, { title: '段数', dataIndex: 'paragraphCount' }, { title: '失败码', dataIndex: 'failureCode' }, { title: '更新时间', dataIndex: 'updatedAt', render: time },
        ]} /></Card>
        <Card title="偏好元数据" style={{ marginTop: 16 }}>{readError(memories.error, memories.refetch)}<Table<AdminPreferenceMetadata> rowKey="id" loading={memories.isLoading} dataSource={memories.isError ? [] : memories.data?.items} scroll={{ x: 800 }} pagination={{ current: memoryPage, pageSize: 20, total: memories.data?.total ?? 0, showSizeChanger: false, onChange: setMemoryPage }} columns={[
          { title: '记录编号', dataIndex: 'id' }, { title: '用户', dataIndex: 'userId' }, { title: '项目', dataIndex: 'projectId', render: (value: string | null) => value ?? '个人范围' }, { title: '范围', dataIndex: 'scope', render: (value: AdminPreferenceMetadata['scope']) => value === 'PERSONAL' ? '个人' : '项目' }, { title: '状态', dataIndex: 'status' }, { title: '版本', dataIndex: 'version' }, { title: '更新时间', dataIndex: 'updatedAt', render: time },
        ]} /></Card>
      </> },
    ]} />
    <Modal title="暂停自动监测" open={Boolean(pausePlan)} okText="暂停监测" cancelText="取消" confirmLoading={busy} okButtonProps={{ disabled: !reason.trim() }} onCancel={() => !busy && setPausePlan(undefined)} onOk={() => pausePlan && reason.trim() && void perform((session) => pauseAdminGeoPlan(pausePlan, reason.trim(), session), () => setPausePlan(undefined))}><Typography.Paragraph>填写原因，用户能够知道为什么暂停；不会改变已执行的任务或账务。</Typography.Paragraph><Input.TextArea aria-label="暂停原因" maxLength={200} value={reason} disabled={busy} onChange={(event) => setReason(event.target.value)} />{actionError ? <Alert type="error" title={actionError} /> : null}</Modal>
    <Drawer title={`监测执行记录 · ${slotsPlan?.id ?? ''}`} open={Boolean(slotsPlan)} onClose={() => setSlotsPlan(undefined)} size="large">{readError(slots.error, slots.refetch)}<Table<AdminGeoPlanSlot> rowKey="id" loading={slots.isLoading} dataSource={slots.isError ? [] : slots.data?.items} scroll={{ x: 650 }} pagination={{ current: slotPage, pageSize: 20, total: slots.data?.total ?? 0, showSizeChanger: false, onChange: setSlotPage }} columns={[
      { title: '时段', dataIndex: 'scheduledSlot', render: time }, { title: '状态', dataIndex: 'status' }, { title: '任务', dataIndex: 'taskId' }, { title: '报价积分', dataIndex: 'quotedPoints' }, { title: '原因', dataIndex: 'errorCode' },
    ]} /></Drawer>
  </section>
}

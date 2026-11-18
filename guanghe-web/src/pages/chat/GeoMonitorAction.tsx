import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button, Space, Typography } from 'antd'
import { RadarChartOutlined } from '@ant-design/icons'
import { getGeoTopic, listGeoPlatforms, listGeoTopics } from '../../api/geoApi'
import { getProjectGeoTarget } from '../../api/agentPlatformApi'
import type { MessageAccepted } from '../../api/conversationApi'
import { isApiError } from '../../api/apiError'
import {
  createMonitorAuthorization, getExecutionBudget, listMonitorAuthorizations,
  previewMonitorAction, putExecutionBudget, revokeMonitorAuthorization, updateMonitorAuthorization,
} from '../../api/monitorExecutionApi'
import type { BudgetPolicy, FrozenMonitorRange, MonitorActionPreview, MonitorAdmission, MonitorAuthorization, MonitorExecutionIntent } from '../../api/monitorExecutionApi'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import type { GeoMonitorEditor } from '../../features/geo/monitorActionEditor'
import { monitorExpiryIso, positiveMonitorPoints } from '../../features/geo/monitorActionEditor'
import { monitorAdmissionView, monitorWaitMessage } from '../../features/geo/monitorAdmissionView'
import { clearMonitorIntent, readMonitorIntent, storeMonitorIntent } from '../../features/geo/monitorIntentRecovery'
import { useMonitorAdmission } from '../../features/geo/monitorAdmissionQuery'
import { monitorBudgetAvailability } from '../../features/geo/monitorBudgetAvailability'
import { messageFingerprint, readMessageAttempt } from '../../features/chat/live/messageAttempt'

interface Props {
  ownerId: string
  recoveryScope: string
  conversationId?: string
  projectId?: string
  restoredRunId?: string
  resultOnly?: boolean
  disabled: boolean
  onEnsureConversation: () => Promise<string | undefined>
  onSend: (intent: MonitorExecutionIntent, conversationId: string) => Promise<MessageAccepted | undefined>
  onViewTask: (id: string) => void
  onAdmission?: (conversationId: string, value: MonitorAdmission) => void
  onConfigureTarget?: () => void
}

const blankEditor: GeoMonitorEditor = { questionIds: [], platformCodes: [], sampleMode: 'STANDARD', actionPoints: '', dayPoints: '', expiresLocal: '' }
const GeoMonitorActionPanel = lazy(() => import('../../features/geo/components/GeoMonitorActionPanel').then((module) => ({ default: module.GeoMonitorActionPanel })))
const sameValues = (a: readonly string[], b: readonly string[]) => a.length === b.length && a.every((value) => b.includes(value))
function sameRange(a: FrozenMonitorRange, b: FrozenMonitorRange) {
  return a.inputHash === b.inputHash && a.topicId === b.topicId && a.topicVersion === b.topicVersion
    && a.sampleMode === b.sampleMode && sameValues(a.questionIds, b.questionIds) && sameValues(a.platformCodes, b.platformCodes)
}
function rangeMatchesEditor(range: FrozenMonitorRange, editor: GeoMonitorEditor) {
  return range.topicId === editor.topicId && range.sampleMode === editor.sampleMode
    && sameValues(range.questionIds, editor.questionIds) && sameValues(range.platformCodes, editor.platformCodes)
}
const safeError = (error: unknown) => error instanceof Error ? error.message : '暂时无法核对监测设置，请重试。'
const localExpiry = (value: string) => new Date(Date.parse(value) + 8 * 60 * 60 * 1000).toISOString().slice(0, 16)

/** Page-owned API orchestration. Mount keyed by owner and immutable conversation/launch identity. */
export function GeoMonitorAction(props: Props) {
  const [session] = useState(captureAuthSession)
  const [recovery] = useState(() => readMonitorIntent(props.ownerId, props.recoveryScope, session.revision))
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const current = () => alive.current && isCurrentAuthSession(session)
  const readScoped = async <T,>(read: () => Promise<T>): Promise<T> => {
    if (!current()) throw new Error('登录或会话状态已改变，请重新打开。')
    const value = await read()
    if (!current()) throw new Error('登录或会话状态已改变，请重新打开。')
    return value
  }
  const [open, setOpen] = useState(false)
  const [checkedAt, setCheckedAt] = useState(Date.now)
  const [editor, setEditor] = useState<GeoMonitorEditor>(blankEditor)
  const [page, setPage] = useState(1)
  const [search, setSearch] = useState('')
  const [busy, setBusy] = useState(false)
  const locked = useRef(false)
  const [preparedConversation, setPreparedConversation] = useState(props.conversationId ?? recovery?.conversationId)
  const [policy, setPolicy] = useState<BudgetPolicy>()
  const [grant, setGrant] = useState<MonitorAuthorization>()
  const [preview, setPreview] = useState<MonitorActionPreview>()
  const [error, setError] = useState<string>()
  const [notice, setNotice] = useState<string>()
  const [runId, setRunId] = useState<string>()
  // An uncertain send retries exactly the same intent through existing UUID recovery.
  const [pendingIntent, setPendingIntent] = useState<MonitorExecutionIntent | undefined>(recovery?.intent)
  const topics = useQuery({ queryKey: ['monitor-action', props.ownerId, props.projectId, 'topics', page, search],
    queryFn: () => readScoped(() => listGeoTopics({ spaceId: props.projectId, page, size: 20, keyword: search || undefined })), enabled: open, retry: false })
  const topic = useQuery({ queryKey: ['monitor-action', props.ownerId, 'topic', editor.topicId],
    queryFn: () => readScoped(() => getGeoTopic(editor.topicId!)), enabled: open && Boolean(editor.topicId), retry: false })
  const platforms = useQuery({ queryKey: ['monitor-action', props.ownerId, 'platforms'], queryFn: () => readScoped(listGeoPlatforms), enabled: open, retry: false })
  const activeRunId = runId ?? props.restoredRunId
  const admission = useMonitorAdmission(props.ownerId, preparedConversation, activeRunId, session)
  const onAdmission = props.onAdmission
  useEffect(() => {
    if (preparedConversation && admission.data && admission.data.runId === activeRunId && alive.current && isCurrentAuthSession(session)) {
      onAdmission?.(preparedConversation, admission.data)
    }
  }, [activeRunId, admission.data, onAdmission, preparedConversation, session])
  const status = admission.data ? monitorAdmissionView(admission.data) : undefined
  const availability = monitorBudgetAvailability(policy, checkedAt)
  const admissionPending = admission.isFetching || ['WAITING_PROPOSAL', 'PENDING'].includes(admission.data?.status ?? '')

  async function perform(action: () => Promise<void>) {
    if (locked.current || !current()) return
    locked.current = true
    setBusy(true); setError(undefined)
    try { await action() } catch (problem) {
      if (current()) {
        setError(safeError(problem)); setPreview(undefined)
        if (isApiError(problem) && [400, 401, 403, 404, 409, 410, 422, 429].includes(problem.status)) {
          setPendingIntent(undefined); clearMonitorIntent(props.ownerId, props.recoveryScope)
        }
      }
    } finally { locked.current = false; if (current()) setBusy(false) }
  }
  async function loadSettings() {
    const nextPolicy = await getExecutionBudget(session)
    if (!current()) return
    setPolicy(nextPolicy)
    setCheckedAt(Date.now())
    setEditor((value) => ({ ...value, actionPoints: nextPolicy.maxPointsPerAction?.toString() ?? '', dayPoints: nextPolicy.maxPointsPerDay?.toString() ?? '' }))
    if (preparedConversation) {
      const grants = await listMonitorAuthorizations(preparedConversation, session)
      if (!current()) return
      setGrant(grants[0])
      if (grants[0]) setEditor((value) => ({ ...value, expiresLocal: localExpiry(grants[0].expiresAt) }))
    }
  }
  function openPanel() {
    setCheckedAt(Date.now())
    setOpen(true)
    void perform(loadSettings)
  }
  function changeEditor(value: GeoMonitorEditor) {
    if (pendingIntent || locked.current) return
    if (preview?.range && !rangeMatchesEditor(preview.range, value)) setPreview(undefined)
    setEditor(value); setError(undefined); setNotice(undefined)
  }
  async function prepare() {
    if (!editor.topicId || !topic.data?.region?.trim()) return
    await perform(async () => {
      if (props.projectId && !topic.data?.productId) {
        const target = await getProjectGeoTarget(props.projectId, session)
        if (!current()) return
        if (!target.configured) {
          setPreview(undefined)
          setNotice('请先在项目资料中设置公开监测名称，才能判断品牌是否被 AI 提及。不会开始监测或扣费。')
          if (props.onConfigureTarget) { setOpen(false); props.onConfigureTarget() }
          return
        }
      }
      let id = preparedConversation
      if (!id) {
        id = await props.onEnsureConversation()
        if (!current() || !id) return
        setPreparedConversation(id)
      }
      const value = await previewMonitorAction(id, { topicId: editor.topicId!, questionIds: editor.questionIds, platformCodes: editor.platformCodes, sampleMode: editor.sampleMode }, session)
      if (!current()) return
      setPreview(value)
      setNotice(value.status === 'READY' ? '费用已核对。点击下方按钮才会保存额度、授权并发起本次监测。' : monitorWaitMessage(value.reasonCode))
    })
  }
  async function start() {
    if (props.disabled || admissionPending) return
    const id = preparedConversation
    if (!id) return
    await perform(async () => {
      let intent = pendingIntent
      if (intent) {
        const fingerprint = await messageFingerprint({ content: '执行本次已选专题的单次监测。', executionIntent: intent, contextSelectionMode: 'INHERIT', attachments: [] })
        if (!current()) return
        // Another ordinary message may already have reconciled this operation.
        // A retry must never create a fresh UUID after its original attempt vanished.
        if (readMessageAttempt(props.ownerId, id)?.fingerprint !== fingerprint) {
          clearMonitorIntent(props.ownerId, props.recoveryScope)
          setPendingIntent(undefined); setPreview(undefined)
          setNotice('上一条请求记录已更新，请查看已有受理状态。未重新发送监测请求。')
          if (activeRunId) void admission.refetch()
          return
        }
      }
      if (!intent) {
        if (!monitorBudgetAvailability(policy, Date.now()).available) return
        const expiresAt = monitorExpiryIso(editor.expiresLocal)
        const perAction = positiveMonitorPoints(editor.actionPoints)
        const perDay = positiveMonitorPoints(editor.dayPoints)
        if (!preview?.range || preview.status !== 'READY' || !preview.pricingVersionId || !preview.quotedPoints
          || !rangeMatchesEditor(preview.range, editor) || !expiresAt || !perAction || !perDay || perAction > perDay || preview.quotedPoints > perAction) return
        // Always reconcile before writes. Unknown outcomes and cross-tab edits never reuse stale versions.
        const [livePolicy, grants] = await Promise.all([getExecutionBudget(session), listMonitorAuthorizations(id, session)])
        if (!current()) return
        const liveAvailability = monitorBudgetAvailability(livePolicy, Date.now())
        if (!liveAvailability.available) { setPolicy(livePolicy); setPreview(undefined); setNotice(liveAvailability.notice); return }
        if (policy && livePolicy.version !== policy.version) {
          setPolicy(livePolicy); setPreview(undefined); setNotice('执行额度已在别处更新，请重新加载后核对。'); return
        }
        const liveGrant = grants[0]
        if (grant && (!liveGrant || liveGrant.id !== grant.id || liveGrant.version !== grant.version)) {
          setGrant(liveGrant); setPreview(undefined); setNotice('本次授权已在别处更新，请重新核对。'); return
        }
        let savedPolicy = livePolicy
        if (!livePolicy.enabled || livePolicy.maxPointsPerAction !== perAction || livePolicy.maxPointsPerDay !== perDay) {
          savedPolicy = await putExecutionBudget({ version: livePolicy.version, enabled: true, maxPointsPerAction: perAction, maxPointsPerDay: perDay }, session)
          if (!current()) return
        }
        setPolicy(savedPolicy)
        const savedAvailability = monitorBudgetAvailability(savedPolicy, Date.now())
        if (!savedAvailability.available) { setPreview(undefined); setNotice(savedAvailability.notice); return }
        let savedGrant = liveGrant
        if (!liveGrant || liveGrant.status !== 'ACTIVE' || !sameRange(liveGrant.range, preview.range) || Date.parse(liveGrant.expiresAt) !== Date.parse(expiresAt)) {
          savedGrant = liveGrant ? await updateMonitorAuthorization(id, liveGrant.id, { version: liveGrant.version, range: preview.range, expiresAt }, session)
            : await createMonitorAuthorization(id, { expectedVersion: 0, range: preview.range, expiresAt }, session)
          if (!current()) return
        }
        if (!savedGrant || savedGrant.status !== 'ACTIVE' || !sameRange(savedGrant.range, preview.range)) throw new Error('授权范围未确认，未发送监测请求。')
        setGrant(savedGrant)
        intent = { action: 'MONITOR_RUN', grantId: savedGrant.id, grantVersion: savedGrant.version, range: preview.range, pricingVersionId: preview.pricingVersionId, quotedPoints: preview.quotedPoints }
        intent = storeMonitorIntent(props.ownerId, props.recoveryScope, { conversationId: id, intent, authRevision: session.revision }).intent
        setPendingIntent(intent)
      }
      if (!current()) return
      const accepted = await props.onSend(intent, id)
      if (!current()) return
      if (!accepted) { setError('发送结果尚未确认。请点击“重试同一次请求”，不会另建重复任务。'); return }
      clearMonitorIntent(props.ownerId, props.recoveryScope)
      setRunId(accepted.runId); setPendingIntent(undefined); setPreview(undefined)
    })
  }
  function revoke() {
    const id = preparedConversation
    if (!id || !grant) return
    void perform(async () => {
      const value = await revokeMonitorAuthorization(id, grant.id, { version: grant.version }, session)
      if (!current()) return
      setGrant(value); setPreview(undefined); setPendingIntent(undefined)
      clearMonitorIntent(props.ownerId, props.recoveryScope)
      setNotice('授权已撤销。已经受理的任务不受影响。')
      if (activeRunId) void admission.refetch()
    })
  }
  const validPreview = preview?.status === 'READY' && preview.range && preview.pricingVersionId && preview.quotedPoints && preview.expectedUnits
  return <>
    <Space wrap size={4}>
      {!props.resultOnly ? <Button htmlType="button" size="small" icon={<RadarChartOutlined aria-hidden />} disabled={props.disabled && !activeRunId} onClick={openPanel}>监测一次</Button> : null}
      {status ? <Typography.Text type="secondary">{status.title}</Typography.Text> : null}
      {activeRunId ? <Button htmlType="button" size="small" type="link" loading={admission.isFetching} onClick={() => void admission.refetch()}>刷新受理状态</Button> : null}
      {pendingIntent && !open ? <Button htmlType="button" size="small" loading={busy} disabled={props.disabled} onClick={() => void start()}>重试同一次请求</Button> : null}
    </Space>
    {!open && notice ? <Typography.Paragraph type="secondary">{notice}</Typography.Paragraph> : null}
    {open ? <Suspense fallback={<Typography.Text type="secondary">正在打开监测设置…</Typography.Text>}><GeoMonitorActionPanel open={open} loading={topics.isLoading || platforms.isLoading || topic.isLoading} busy={busy}
      editor={editor} topics={topics.data?.items ?? []} topic={topic.data} topicPage={page} topicTotal={topics.data?.total ?? 0} topicSearch={search} platforms={platforms.data ?? []}
      quote={validPreview ? { points: preview.quotedPoints!, sampleCount: preview.expectedUnits! } : undefined}
      authorizationLabel={grant ? `本专题授权${grant.status === 'REVOKED' ? '已撤销：' : Date.parse(grant.expiresAt) <= checkedAt ? '已过期：' : '有效期至'} ${localExpiry(grant.expiresAt).replace('T', ' ')}（北京时间）` : undefined}
      canRevoke={grant?.status === 'ACTIVE'} canStart={availability.available && !props.disabled && !admissionPending && Boolean(validPreview) && !pendingIntent}
      startLabel="授权并开始本次监测"
      error={error ?? (topics.error || platforms.error || topic.error || admission.error ? '数据暂时无法加载，请重试。不会自动再次发起监测。' : undefined)} notice={availability.notice ?? notice}
      admission={status} onClose={() => setOpen(false)} onChange={changeEditor}
      onSearch={(value) => { setSearch(value.trim()); setPage(1) }} onPageChange={setPage}
      onPreview={() => void prepare()} onStart={() => void start()} onRevoke={revoke}
      onRetryPending={pendingIntent ? () => void start() : undefined}
      onRetry={() => { if (!current()) return; void perform(loadSettings); void topics.refetch(); void platforms.refetch(); if (editor.topicId) void topic.refetch(); if (activeRunId) void admission.refetch() }} onViewTask={props.onViewTask} /></Suspense> : null}
  </>
}

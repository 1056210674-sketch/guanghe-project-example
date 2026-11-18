import { useEffect, useRef, useState } from 'react'
import { useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Spin } from 'antd'
import { createGeoDiagnostic, generateGeoDiagnostic, getGeoDiagnostic, patchGeoDiagnostic, quoteGeoDiagnostic, submitGeoDiagnostic, type GeoDiagnostic, type GeoDiagnosticGenerate, type GeoDiagnosticPatch, type GeoDiagnosticSnapshot } from '../../api/geoDiagnosticApi'
import { listGeoPlatforms } from '../../api/geoApi'
import { isApiError } from '../../api/apiError'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import { useAuthStore } from '../../stores/authStore'
import { chatKeysFor } from '../../features/chat/live/chatQueries'
import type { ChatGeoDiagnosticReference } from '../../features/chat/types'
import { diagnosticKeys, isScopedGeoDiagnostic, mergeGeoDiagnostic, useGeoDiagnostic } from '../../features/geo-diagnostics/queries'
import { GeoDiagnosticCard, type DiagnosticEditor } from '../../features/geo-diagnostics/GeoDiagnosticCard'
import { DiagnosticDeliveryProgress } from '../../features/geo-diagnostics/DiagnosticDeliveryProgress'
import { clearDiagnosticSubmission, readDiagnosticSubmission, saveDiagnosticSubmission, type DiagnosticSubmissionReceipt } from '../../features/geo-diagnostics/submissionReceipt'
import { appLinks } from '../../routes/paths'

const phaseForStep: Record<number, GeoDiagnosticGenerate['phase']> = {
  1: 'BRAND_RESEARCH', 2: 'BRAND_RESEARCH', 3: 'COMPETITORS', 4: 'DISCOVERY_QUESTIONS', 5: 'REPUTATION_QUESTIONS',
}
const phaseLabel: Record<GeoDiagnosticGenerate['phase'], string> = {
  BRAND_RESEARCH: '正在查找品牌的公开资料…', COMPETITORS: '正在寻找合适的同类品牌…',
  DISCOVERY_QUESTIONS: '正在生成顾客会问的可见度问题…', REPUTATION_QUESTIONS: '正在生成品牌口碑问题…',
}
function safeError(error: unknown) {
  if (isApiError(error)) {
    if ([401, 403, 404].includes(error.status)) return '这个诊断记录不存在或当前账号无权查看。'
    if (error.status === 409) return '诊断记录已更新，请刷新后核对。你的填写内容仍保留在卡片中。'
    if (error.status === 429) return '请求较多，请稍后再试。'
    if (error.code === 'VALIDATION_FAILED' && [400, 422].includes(error.status)) return error.message
  }
  return '这一步暂时无法完成，已保存的资料会保留，请刷新后重试。'
}
function fieldsFor(step: number, values: DiagnosticEditor): Partial<GeoDiagnosticPatch> {
  if (step < 3) return { profile: values.profile }
  if (step === 3) return { competitors: values.competitors }
  if (step === 4) return { discoveryQuestions: values.discoveryQuestions }
  if (step === 5) return { reputationQuestions: values.reputationQuestions }
  return { platformCodes: values.platformCodes }
}

/** A diagnostic card is an owned Java record, independent from normal reply streaming. */
export function GeoDiagnosticCardContainer({ reference, messageId, paged, onResume }: { reference: ChatGeoDiagnosticReference; messageId: string; paged?: boolean; onResume?: () => void }) {
  const ownerId = useAuthStore((state) => state.user?.id)
  const client = useQueryClient()
  const mutationKey = diagnosticKeys.action(ownerId, reference.diagnosticId)
  const sharedBusy = useIsMutating({ mutationKey }) > 0
  const mutation = useMutation({ mutationKey, mutationFn: (action: () => Promise<void>) => action() })
  const query = useGeoDiagnostic(ownerId, reference.diagnosticId, reference.conversationId, reference.step, messageId, paged ? 'none' : 'current-card')
  const alive = useRef(true)
  const lock = useRef(false)
  const newRequestId = useRef<string | undefined>(undefined)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string>()
  const [quoteRecheck, setQuoteRecheck] = useState(false)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const diagnostic = !query.isError && isScopedGeoDiagnostic(query.data, { diagnosticId: reference.diagnosticId, conversationId: reference.conversationId }) ? query.data : undefined
  const card = diagnostic?.cards.find((item) => item.messageId === messageId && item.step === reference.step)
  const latestCard = diagnostic?.cards.filter((item) => item.step === reference.step).at(-1)
  const latestMessageId = diagnostic?.cards.at(-1)?.messageId
  useEffect(() => {
    if (!ownerId || !diagnostic || !latestMessageId) return
    const headKey = diagnosticKeys.cardHead(ownerId, diagnostic.id)
    if (client.getQueryData<string>(headKey) === latestMessageId) return
    client.setQueryData(headKey, latestMessageId)
    // Background preparation can add a completed card without a chat Run.
    // Refresh the snapshot once per new head, shared by all historical cards.
    if (latestMessageId !== messageId) void client.invalidateQueries({ queryKey: chatKeysFor(ownerId).messages(reference.conversationId), exact: true })
  }, [client, diagnostic, latestMessageId, messageId, ownerId, reference.conversationId])
  const editable = Boolean(diagnostic && card && diagnostic.step === reference.step && latestCard?.messageId === messageId)
  const generationActive = ['QUEUED', 'RUNNING'].includes(diagnostic?.generation?.status ?? '')
  const generating = editable && generationActive
  const receiptKey = diagnosticKeys.receipt(ownerId, reference.diagnosticId)
  const receiptQuery = useQuery({ queryKey: receiptKey, queryFn: async () => ownerId && diagnostic ? readDiagnosticSubmission(ownerId, diagnostic) ?? null : null,
    enabled: Boolean(ownerId && diagnostic), staleTime: Infinity, retry: false })
  const receipt = receiptQuery.data
  const frozen = Boolean(diagnostic?.delivery || receipt)
  useEffect(() => {
    if (!diagnostic?.delivery || !ownerId) return
    clearDiagnosticSubmission(ownerId, diagnostic)
    client.setQueryData(diagnosticKeys.receipt(ownerId, reference.diagnosticId), null)
  }, [client, diagnostic, ownerId, reference.diagnosticId])
  const platforms = useQuery({ queryKey: diagnosticKeys.platforms(ownerId), queryFn: listGeoPlatforms,
    enabled: Boolean(ownerId && editable && reference.step === 6), retry: false })
  const current = (session: ReturnType<typeof captureAuthSession>) => alive.current && isCurrentAuthSession(session) && useAuthStore.getState().user?.id === ownerId

  async function accept(result: GeoDiagnostic, session: ReturnType<typeof captureAuthSession>) {
    if (!current(session)) return
    const key = diagnosticKeys.detail(ownerId, reference.diagnosticId)
    const accepted = client.setQueryData<GeoDiagnostic>(key, (previous) => mergeGeoDiagnostic(previous, result, {
      diagnosticId: reference.diagnosticId, conversationId: reference.conversationId, projectId: diagnostic?.projectId,
    }))
    const latest = accepted?.cards.at(-1)?.messageId
    if (latest) client.setQueryData(diagnosticKeys.cardHead(ownerId, reference.diagnosticId), latest)
    await client.invalidateQueries({ queryKey: chatKeysFor(ownerId).messages(reference.conversationId), exact: true })
  }

  async function perform(action: (record: GeoDiagnostic, session: ReturnType<typeof captureAuthSession>) => Promise<void>, allowHistory = false, allowFrozen = false) {
    if (!ownerId || !diagnostic || lock.current || client.isMutating({ mutationKey }) > 0 || frozen && !allowFrozen || generationActive && !allowHistory || (!editable && !allowHistory)) return
    const session = captureAuthSession()
    lock.current = true
    setBusy(true); setActionError(undefined)
    try {
      await mutation.mutateAsync(async () => {
        await client.cancelQueries({ queryKey: diagnosticKeys.detail(ownerId, diagnostic.id), exact: true })
        if (current(session)) await action(diagnostic, session)
      })
    } catch (error) { if (current(session)) setActionError(safeError(error)) }
    finally { lock.current = false; if (current(session)) setBusy(false) }
  }

  async function save(values: DiagnosticEditor, advance: boolean) {
    await perform(async (record, session) => {
      const saved = await patchGeoDiagnostic(record.id, { version: record.version, step: reference.step, ...fieldsFor(reference.step, values), advance: reference.step === 1 ? false : advance }, session)
      await accept(saved, session)
      if (reference.step === 1 && advance && current(session)) {
        const result = await generateGeoDiagnostic(saved.id, { version: saved.version, phase: 'BRAND_RESEARCH', requestId: crypto.randomUUID() }, session)
        await accept(result, session)
      }
    })
  }

  async function submit() {
    await perform(async (record, session) => {
      if (record.delivery || !record.submissionEnabled) return
      let pending: DiagnosticSubmissionReceipt | undefined = receipt ?? readDiagnosticSubmission(ownerId!, record)
      if (!pending) {
        const quote = record.quote
        if (!quote || quote.version !== record.version || quote.blockedReason || Date.parse(quote.expiresAt) <= Date.now()) return
        pending = saveDiagnosticSubmission(ownerId!, record, { version: record.version, quoteId: quote.id, inputHash: quote.inputHash, requestId: crypto.randomUUID() })
        client.setQueryData(receiptKey, pending)
      }
      try {
        const result = await submitGeoDiagnostic(record.id, pending.request, session)
        await accept(result, session)
        if (current(session) && result.delivery) { clearDiagnosticSubmission(ownerId!, record); client.setQueryData(receiptKey, null) }
      } catch (error) {
        // No automatic replay: a timeout can mean Java already accepted the paid task.
        if (current(session) && isApiError(error) && error.status >= 400 && error.status < 500 && error.status !== 408) {
          clearDiagnosticSubmission(ownerId!, record); client.setQueryData(receiptKey, null)
        }
        throw error
      }
    }, false, true)
  }

  async function reconcile() {
    await perform(async (record, session) => {
      const result = await getGeoDiagnostic(record.id, session)
      await accept(result, session)
      if (current(session) && result.delivery) { clearDiagnosticSubmission(ownerId!, record); client.setQueryData(receiptKey, null) }
      else if (current(session)) setActionError('尚未查到提交回执。可以继续核对，或明确重试同一次提交；不会创建另一个订单。')
    }, false, true)
  }

  async function regenerate(values: DiagnosticEditor) {
    const phase = phaseForStep[reference.step]
    if (!phase) return
    await perform(async (record, session) => {
      const saved = await patchGeoDiagnostic(record.id, { version: record.version, step: reference.step, ...fieldsFor(reference.step, values), advance: false }, session)
      await accept(saved, session)
      if (!current(session)) return
      const result = await generateGeoDiagnostic(saved.id, { version: saved.version, phase, requestId: crypto.randomUUID() }, session)
      await accept(result, session)
    })
  }

  async function newDiagnostic() {
    await perform(async (record, session) => {
      const storageKey = `zxh.diagnostic-new.${ownerId}.${record.conversationId}.${messageId}`
      if (!newRequestId.current) {
        try { newRequestId.current = sessionStorage.getItem(storageKey) ?? undefined } catch { /* The same in-memory attempt remains stable. */ }
        if (!newRequestId.current || !/^[0-9a-f-]{36}$/i.test(newRequestId.current)) newRequestId.current = crypto.randomUUID()
        try { sessionStorage.setItem(storageKey, newRequestId.current) } catch { /* Only the harmless request UUID is stored. */ }
      }
      const result = await createGeoDiagnostic({ requestId: newRequestId.current, conversationId: record.conversationId }, session)
      if (!current(session)) return
      const accepted = client.setQueryData<GeoDiagnostic>(diagnosticKeys.detail(ownerId, result.id), (previous) => mergeGeoDiagnostic(previous, result, {
        diagnosticId: result.id, conversationId: record.conversationId, projectId: record.projectId,
      }))
      const latest = accepted?.cards.at(-1)?.messageId
      if (latest) client.setQueryData(diagnosticKeys.cardHead(ownerId, result.id), latest)
      await client.invalidateQueries({ queryKey: chatKeysFor(ownerId).messages(record.conversationId), exact: true })
      if (current(session)) { newRequestId.current = undefined; try { sessionStorage.removeItem(storageKey) } catch { /* Receipt is already persisted by Java. */ } }
    }, true, true)
  }

  if (query.isError) return <Alert type="error" showIcon title={safeError(query.error)} action={<Button onClick={() => void query.refetch()}>重新加载诊断</Button>} />
  if (query.isLoading) return <div role="status"><Spin size="small" /> 正在读取诊断卡片…</div>
  if (!diagnostic || !card) return <Alert type="warning" title="诊断卡片暂时不可用，请刷新记录。" action={<Button onClick={() => void query.refetch()}>刷新</Button>} />
  const snapshot: GeoDiagnosticSnapshot = editable ? {
    version: diagnostic.version, profile: diagnostic.profile, competitors: diagnostic.competitors,
    discoveryQuestions: diagnostic.discoveryQuestions, reputationQuestions: diagnostic.reputationQuestions,
    platformCodes: diagnostic.platformCodes, sources: diagnostic.sources, warnings: diagnostic.warnings,
  } : card.snapshot
  return <><GeoDiagnosticCard key={`${messageId}:${editable}`} step={reference.step} paged={paged} snapshot={snapshot} editable={editable} frozen={frozen} submissionPending={Boolean(receipt && !diagnostic.delivery)}
    busy={busy || sharedBusy} generating={generating} generationLabel={diagnostic.generation ? phaseLabel[diagnostic.generation.phase] : undefined}
    generationFailed={editable && diagnostic.generation?.status === 'FAILED'} error={actionError}
    platforms={platforms.isError ? [] : platforms.data ?? []} platformsLoading={platforms.isLoading}
    platformsError={platforms.isError ? '监测平台暂时无法加载，请重新读取。' : undefined}
    quote={editable && !quoteRecheck ? diagnostic.quote : undefined} submissionEnabled={diagnostic.submissionEnabled} submitting={Boolean(busy && receipt && !diagnostic.delivery)} onSubmit={() => void submit()} onSave={(values, advance) => void save(values, advance)}
    onRegenerate={(values) => void regenerate(values)} onQuote={(platformCodes) => void perform(async (record, session) => {
      if (current(session)) setQuoteRecheck(true)
      const result = await quoteGeoDiagnostic(record.id, { version: record.version, platformCodes }, session)
      await accept(result, session)
      if (current(session)) setQuoteRecheck(false)
    })}
    onEdit={() => void perform(async (record, session) => {
      const result = await patchGeoDiagnostic(record.id, { version: record.version, step: reference.step, advance: false }, session)
      if (current(session)) onResume?.()
      await accept(result, session)
    }, true)}
    onRefresh={() => void query.refetch()} onRefreshPlatforms={() => void platforms.refetch()} onNew={() => void newDiagnostic()} />
    {editable && reference.step === 6 && receipt && !diagnostic.delivery ? <Alert type="warning" showIcon title="提交结果尚未确认" description="已保留这次提交的身份，先核对后台回执。不自动重新监测或再次收费。" action={<><Button loading={busy || sharedBusy} onClick={() => void reconcile()}>核对提交结果</Button><Button disabled={busy || sharedBusy} onClick={() => void submit()}>重试同一次提交</Button></>} /> : null}
    {editable && reference.step === 6 && diagnostic.delivery ? <DiagnosticDeliveryProgress status={diagnostic.delivery.status} progress={diagnostic.delivery.progressPercent} noData={diagnostic.delivery.hasNoData} partial={diagnostic.delivery.samplingStatus === 'PARTIAL'} canRetry={false} reportHref={appLinks.geoDiagnosticReport(diagnostic.id)} onRefresh={() => void query.refetch()} /> : null}</>
}

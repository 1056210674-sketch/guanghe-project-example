import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, isApiError } from '../../api/apiError'
import { getGeoDiagnostic, getGeoDiagnosticDownload, getGeoDiagnosticObservations, getGeoDiagnosticReport, retryGeoDiagnosticReport, type GeoDiagnostic, type GeoDiagnosticReport } from '../../api/geoDiagnosticApi'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import { diagnosticDeliveryActive, diagnosticKeys, mergeGeoDiagnostic } from '../../features/geo-diagnostics/queries'
import { useAuthStore } from '../../stores/authStore'

export const diagnosticReportError = (error: unknown) => isApiError(error) && [401, 403, 404].includes(error.status) ? '这份诊断报告不存在或当前账号无权查看。'
  : isApiError(error) && error.status === 409 ? '报告状态或版本已更新，请刷新后核对。'
    : '诊断报告暂时无法读取，请稍后重试。'
const validId = (value?: string) => Boolean(value && /^[1-9]\d{0,18}$/.test(value))
export function mergeDiagnosticReport(previous: unknown, incoming: unknown, id: string): GeoDiagnosticReport {
  const next = incoming as GeoDiagnosticReport
  if (!next || next.id !== id || !Number.isSafeInteger(next.version) || next.version < 1 || !Number.isSafeInteger(next.reportVersion) || next.reportVersion < 1) throw new ApiError({ status: 403, code: 'FORBIDDEN', message: '报告记录不匹配。' })
  const old = previous as GeoDiagnosticReport | undefined
  return old?.id === id && old.version > next.version ? old : next
}

/** Authenticated delivery reads and retries never create another monitoring task. */
export function useGeoDiagnosticReport(diagnosticId?: string) {
  const owner = useAuthStore((state) => state.user?.id)
  const client = useQueryClient()
  const scope = `${owner ?? ''}:${diagnosticId ?? ''}`
  const active = useRef(scope)
  useEffect(() => { active.current = scope; return () => { active.current = '' } }, [scope])
  const detailQuery = useQuery({ queryKey: diagnosticKeys.report(owner, diagnosticId), queryFn: () => getGeoDiagnosticReport(diagnosticId!, captureAuthSession()), enabled: Boolean(owner && validId(diagnosticId)), retry: false,
    structuralSharing: (previous, incoming) => mergeDiagnosticReport(previous, incoming, diagnosticId!), refetchInterval: (query) => !query.state.error && diagnosticDeliveryActive(query.state.data?.status) ? 2500 : false, refetchIntervalInBackground: false })
  const report = !detailQuery.isError && detailQuery.data?.id === diagnosticId ? detailQuery.data : undefined
  const diagnosticQuery = useQuery({ queryKey: diagnosticKeys.detail(owner, diagnosticId), queryFn: async () => {
    // Read ownership from Java; the report intentionally does not expose a parallel project binding.
    const value = await getGeoDiagnostic(diagnosticId!, captureAuthSession())
    if (value.id !== diagnosticId || !validId(value.projectId) || !validId(value.conversationId)) throw new ApiError({ status: 403, code: 'FORBIDDEN', message: '诊断范围不匹配。' })
    return value
  }, enabled: Boolean(owner && validId(diagnosticId)), retry: false,
    structuralSharing: (previous, incoming) => {
      const next = incoming as GeoDiagnostic
      const old = previous as typeof next | undefined
      return mergeGeoDiagnostic(previous, incoming, { diagnosticId: diagnosticId!, conversationId: old && old.id === diagnosticId ? old.conversationId : next.conversationId, projectId: old && old.id === diagnosticId ? old.projectId : next.projectId })
    } })
  const diagnostic = !diagnosticQuery.isError && diagnosticQuery.data?.id === diagnosticId ? diagnosticQuery.data : undefined
  const bound = Boolean(diagnostic?.delivery && report && diagnostic.delivery.taskId === report.taskId && diagnostic.delivery.runId === report.runId && diagnostic.delivery.reportVersion === report.reportVersion)
  const [observationsSelection, setObservationsSelection] = useState<{ scope: string; open: boolean; page: number }>()
  const observationsOpen = observationsSelection?.scope === scope && observationsSelection.open
  const page = observationsSelection?.scope === scope ? observationsSelection.page : 1
  const observations = useQuery({ queryKey: diagnosticKeys.observations(owner, diagnosticId, report?.reportVersion, page), queryFn: () => getGeoDiagnosticObservations(diagnosticId!, page, captureAuthSession()), enabled: Boolean(bound && observationsOpen), retry: false })
  const [actionError, setActionError] = useState<{ scope: string; message: string }>()
  const mutationKey = diagnosticKeys.reportAction(owner, diagnosticId)
  const retry = useMutation({ mutationKey, mutationFn: async () => {
    if (!bound || !report?.canRetry || !diagnostic) return
    const session = captureAuthSession(), started = scope
    setActionError(undefined)
    const storageKey = `zxh.diagnostic-report-retry.${owner}.${diagnosticId}.${report.version}`
    let requestId: string | null = null
    try { requestId = sessionStorage.getItem(storageKey) } catch { /* No report or auth data is stored. */ }
    if (!requestId || !/^[0-9a-f-]{36}$/i.test(requestId)) requestId = crypto.randomUUID()
    try { sessionStorage.setItem(storageKey, requestId) } catch { /* Identity remains stable in this request. */ }
    try {
      const result = await retryGeoDiagnosticReport(report.id, { version: report.version, requestId }, session)
      if (active.current !== started || !isCurrentAuthSession(session)) return
      client.setQueryData(diagnosticKeys.report(owner, report.id), (previous) => mergeDiagnosticReport(previous, result, report.id))
      await client.invalidateQueries({ queryKey: diagnosticKeys.detail(owner, report.id), exact: true })
      try { sessionStorage.removeItem(storageKey) } catch { /* Accepted delivery is persisted. */ }
    } catch (error) { if (active.current === started && isCurrentAuthSession(session)) setActionError({ scope: started, message: diagnosticReportError(error) }) }
  } })
  const download = useMutation({ mutationKey, mutationFn: async () => {
    if (!bound || !report || !diagnostic || report.status !== 'READY' || !report.pdf) return
    const session = captureAuthSession(), started = scope, version = report.reportVersion
    setActionError(undefined)
    try {
      const receipt = await getGeoDiagnosticDownload(report.id, version, session)
      if (active.current !== started || !isCurrentAuthSession(session)) return
      const currentReport = client.getQueryData<GeoDiagnosticReport>(diagnosticKeys.report(owner, report.id))
      if (currentReport?.reportVersion !== version || currentReport.status !== 'READY' || currentReport.pdf?.filename !== receipt.filename) throw new ApiError({ status: 409, code: 'VERSION_CONFLICT', message: '报告版本已变化。' })
      const url = new URL(receipt.downloadUrl)
      if (url.protocol !== 'https:' || url.username || url.password || Date.parse(receipt.expiresAt) <= Date.now() || !Number.isFinite(Date.parse(receipt.expiresAt))) throw new Error('INVALID_DOWNLOAD')
      const anchor = document.createElement('a')
      anchor.href = url.href; anchor.download = [...receipt.filename].map((character) => character.charCodeAt(0) < 32 || '/\\'.includes(character) ? '_' : character).join(''); anchor.rel = 'noopener noreferrer'; anchor.referrerPolicy = 'no-referrer'; anchor.hidden = true
      document.body.append(anchor)
      try { anchor.click() } finally { anchor.remove() }
    } catch (error) { if (active.current === started && isCurrentAuthSession(session)) setActionError({ scope: started, message: diagnosticReportError(error) }) }
  } })
  return { report: bound ? report : undefined, diagnostic, loading: detailQuery.isPending || diagnosticQuery.isPending,
    error: !validId(diagnosticId) ? '报告链接无效。' : detailQuery.isError ? diagnosticReportError(detailQuery.error) : diagnosticQuery.isError ? diagnosticReportError(diagnosticQuery.error) : diagnostic && report && !bound ? '报告与本次诊断任务不匹配，请重新读取。' : undefined,
    refresh: () => { void detailQuery.refetch(); void diagnosticQuery.refetch() }, retrying: retry.isPending, downloading: download.isPending,
    retry: () => { if (client.isMutating({ mutationKey }) === 0) void retry.mutateAsync() }, download: () => { if (client.isMutating({ mutationKey }) === 0) void download.mutateAsync() },
    actionError: actionError?.scope === scope ? actionError.message : undefined,
    observationsOpen, observations: observations.isError ? undefined : observations.data, observationsLoading: observations.isFetching,
    observationsError: observations.isError ? diagnosticReportError(observations.error) : undefined, observationsPage: page,
    toggleObservations: () => setObservationsSelection({ scope, open: !observationsOpen, page }), changePage: (next: number) => setObservationsSelection({ scope, open: true, page: next }),
    refreshObservations: () => void observations.refetch(),
  }
}

import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { isApiError } from '../../api/apiError'
import { createGeoPeriodReport, exportGeoPeriodReportPdf, getGeoPeriodReport, listGeoPeriodReports, retryGeoPeriodReportSummary, type GeoPeriodReportCreate } from '../../api/geoPeriodReportApi'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import { useProjects } from '../../features/agent-platform/queries'
import { periodDateError } from '../../features/geo-period-reports/dateRange'
import type { PeriodReportFormValues } from '../../features/geo-period-reports/PeriodReportForm'
import { downloadBlobFile } from '../../features/shared/browserTransfer'

const validId = (id?: string) => Boolean(id && /^[1-9]\d{0,18}$/.test(id))
const keys = {
  detail: (owner?: string, id?: string) => ['geo-period-reports', owner ?? null, 'detail', id ?? null] as const,
  list: (owner?: string, project?: string) => ['geo-period-reports', owner ?? null, 'list', project ?? null] as const,
}
const reportError = (error: unknown) => isApiError(error) && [401, 403, 404].includes(error.status)
  ? '这份报告不存在或当前账号无权查看。'
  : isApiError(error) && error.status === 429 ? '请求较多，请稍后重试。'
    : isApiError(error) && error.status === 409 ? '报告状态已更新，请刷新后重试。'
      : '报告暂时无法处理，请稍后重试。'

interface Options {
  ownerId?: string
  scopeKey: string
  reportId?: string
  defaultProjectId?: string
  onSelectReport: (id?: string) => void
}

/** Data-owning hook. Report actions never pass through chat messages or paid task endpoints. */
export function usePeriodReportWorkspace({ ownerId, scopeKey, reportId, defaultProjectId, onSelectReport }: Options) {
  const client = useQueryClient()
  const [formScope, setFormScope] = useState<string>()
  const [historySelection, setHistorySelection] = useState<{ scope: string; projectId: string }>()
  const [actionState, setActionState] = useState<{ scope: string; action: 'create' | 'retry' | 'download'; reportId?: string; error?: string; pending: boolean }>()
  const lock = useRef(false)
  const active = useRef({ scopeKey, reportId })
  useEffect(() => { active.current = { scopeKey, reportId }; return () => { active.current = { scopeKey: '', reportId: undefined } } }, [scopeKey, reportId])
  const formOpen = formScope === scopeKey
  const projects = useProjects(Boolean(ownerId && formOpen))
  const historyProjectId = historySelection?.scope === scopeKey ? historySelection.projectId : defaultProjectId
  const historyProjectAvailable = projects.data?.some((project) => project.id === historyProjectId && project.status === 'ACTIVE') && !projects.isError
  const history = useQuery({ queryKey: keys.list(ownerId, historyProjectId),
    queryFn: () => listGeoPeriodReports(historyProjectId!, 1, captureAuthSession()), enabled: Boolean(ownerId && formOpen && validId(historyProjectId) && historyProjectAvailable), retry: false })
  const detail = useQuery({ queryKey: keys.detail(ownerId, reportId), queryFn: () => getGeoPeriodReport(reportId!, captureAuthSession()),
    enabled: Boolean(ownerId && validId(reportId)), retry: false,
    refetchInterval: (query) => query.state.error ? false : ['QUEUED', 'RUNNING'].includes(query.state.data?.status ?? '') ? 2500 : false })
  // Never continue showing cached customer data after permission/ownership verification fails.
  const report = detail.isError ? undefined : detail.data?.id === reportId ? detail.data : undefined
  const visibleAction = actionState?.scope === scopeKey ? actionState : undefined
  const isCurrent = (scope: string, session: ReturnType<typeof captureAuthSession>) => active.current.scopeKey === scope && isCurrentAuthSession(session)

  async function generate(values: PeriodReportFormValues) {
    if (!ownerId || lock.current) return
    const validation = periodDateError(values.startDate, values.endDate)
    if (validation || !projects.data?.some((project) => project.id === values.projectId && project.status === 'ACTIVE') || projects.isError) return
    const scope = scopeKey
    const session = captureAuthSession()
    lock.current = true
    setActionState({ scope, action: 'create', pending: true })
    try {
      const payload = { projectId: values.projectId, startDate: values.startDate, endDate: values.endDate,
        ...(values.title.trim() ? { title: values.title.trim() } : {}), ...(values.focus.trim() ? { focus: values.focus.trim() } : {}) }
      const fingerprint = [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(payload))))].map((byte) => byte.toString(16).padStart(2, '0')).join('')
      const storageKey = `zxh.period-report-request.${ownerId}`
      let saved: { fingerprint?: string; requestId?: string } | undefined
      try { saved = JSON.parse(sessionStorage.getItem(storageKey) ?? 'null') as typeof saved } catch { /* In-memory request still carries its own explicit identity. */ }
      const requestId = saved?.fingerprint === fingerprint && typeof saved.requestId === 'string' ? saved.requestId : crypto.randomUUID()
      try { sessionStorage.setItem(storageKey, JSON.stringify({ fingerprint, requestId })) } catch { /* Request identity remains stable for this attempt. */ }
      const body: GeoPeriodReportCreate = { ...payload, requestId }
      const result = await createGeoPeriodReport(body, session)
      if (!isCurrent(scope, session)) return
      if (!validId(result.id) || result.projectId !== values.projectId) throw new Error('REPORT_SCOPE_MISMATCH')
      client.setQueryData(keys.detail(ownerId, result.id), result)
      void client.invalidateQueries({ queryKey: keys.list(ownerId, values.projectId) })
      try { sessionStorage.removeItem(storageKey) } catch { /* Harmless receipt only; no credential is stored. */ }
      setActionState({ scope, action: 'create', pending: false })
      setFormScope(undefined)
      onSelectReport(result.id)
    } catch (error) {
      if (isCurrent(scope, session)) setActionState({ scope, action: 'create', pending: false, error: reportError(error) })
    } finally { lock.current = false }
  }

  async function retrySummary() {
    if (!report || !report.canRetrySummary || report.status !== 'SUMMARY_UNAVAILABLE' || lock.current) return
    const scope = scopeKey, id = report.id, session = captureAuthSession()
    lock.current = true
    setActionState({ scope, action: 'retry', reportId: id, pending: true })
    try {
      const result = await retryGeoPeriodReportSummary(id, session)
      if (!isCurrent(scope, session) || active.current.reportId !== id) return
      client.setQueryData(keys.detail(ownerId, id), result)
      setActionState({ scope, action: 'retry', reportId: id, pending: false })
    } catch (error) {
      if (isCurrent(scope, session) && active.current.reportId === id) setActionState({ scope, action: 'retry', reportId: id, pending: false, error: reportError(error) })
    } finally { lock.current = false }
  }

  async function downloadPdf() {
    if (!report || !['READY', 'SUMMARY_UNAVAILABLE'].includes(report.status) || lock.current) return
    const scope = scopeKey, id = report.id, session = captureAuthSession()
    lock.current = true
    setActionState({ scope, action: 'download', reportId: id, pending: true })
    try {
      const blob = await exportGeoPeriodReportPdf(id, report.version, session)
      if (!isCurrent(scope, session) || active.current.reportId !== id) return
      downloadBlobFile(blob, `客户GEO报告-${report.startDate}-${report.endDate}-${id}.pdf`)
      setActionState({ scope, action: 'download', reportId: id, pending: false })
    } catch (error) {
      if (isCurrent(scope, session) && active.current.reportId === id) setActionState({ scope, action: 'download', reportId: id, pending: false, error: reportError(error) })
    } finally { lock.current = false }
  }

  return {
    formOpen, projects: projects.data ?? [], projectsLoading: projects.isLoading, projectsError: projects.isError ? '客户项目暂时无法加载。' : undefined,
    retryProjects: () => void projects.refetch(), openForm: () => { setFormScope(scopeKey); setHistorySelection({ scope: scopeKey, projectId: defaultProjectId ?? '' }) }, closeForm: () => setFormScope(undefined),
    submitting: visibleAction?.action === 'create' && visibleAction.pending, createError: visibleAction?.action === 'create' ? visibleAction.error : undefined,
    generate, report, reportOpen: Boolean(reportId), loading: detail.isFetching && !report,
    loadError: reportId && !validId(reportId) ? '报告链接无效，请重新选择已生成的报告。' : detail.isError ? reportError(detail.error) : undefined,
    refreshReport: () => { if (validId(reportId)) void detail.refetch() },
    actionError: visibleAction && visibleAction.reportId === reportId ? visibleAction.error : undefined,
    retrying: visibleAction?.action === 'retry' && visibleAction.reportId === reportId && visibleAction.pending,
    downloading: visibleAction?.action === 'download' && visibleAction.reportId === reportId && visibleAction.pending,
    retrySummary, downloadPdf,
    history: historyProjectAvailable && !history.isError ? history.data?.items ?? [] : [], historyLoading: history.isFetching,
    historyError: history.isError ? '历史报告暂时无法加载。' : undefined,
    changeHistoryProject: (projectId: string) => setHistorySelection({ scope: scopeKey, projectId }),
    refreshHistory: () => { if (validId(historyProjectId)) void history.refetch() },
    openSaved: (id: string) => { if (validId(id)) { setFormScope(undefined); onSelectReport(id) } },
    closeReport: () => onSelectReport(undefined),
  }
}

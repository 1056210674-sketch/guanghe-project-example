import { useQueryClient } from '@tanstack/react-query'
import { App as AntApp } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { isApiError } from '../../api/apiError'
import {
  confirmWorkflowPlan,
  previewWorkflowPlanConfirmation,
  startWorkflowPlan,
} from '../../api/workflowPlanApi'
import type {
  WorkflowConfirmationAuthorization,
  WorkflowPlanConfirmRequest,
  WorkflowPlanListQuery,
} from '../../api/workflowPlanApi'
import {
  WorkflowPlanConfirmationDialog,
  WorkflowPlanDetailDrawer,
  WorkflowPlanList,
  WorkflowPlanStartDialog,
  clearWorkflowPlanStartRecovery,
  prepareWorkflowPlanStartAttempt,
  toWorkflowConfirmationPreview,
  toWorkflowConfirmationResult,
  toWorkflowPlanDetail,
  toWorkflowPlanError,
  toWorkflowPlanSummary,
  toWorkflowStartResult,
  useAbandonWorkflowPlan,
  useWorkflowPlan,
  useWorkflowPlans,
  workflowPlanKeys,
} from '../../features/workflow-plans'
import type {
  WorkflowPlanConfirmationPreviewView,
  WorkflowPlanConfirmationResultView,
  WorkflowPlanErrorView,
  WorkflowPlanStartResultView,
  WorkflowTaskResultView,
} from '../../features/workflow-plans'
import { UserShell } from '../../layouts/UserShell'
import { APP_PATHS, appLinks, workflowTaskResultPath } from '../../routes/paths'
import { useAuthStore } from '../../stores/authStore'
import '../../styles/task-center.css'
import '../../styles/workflow-plans.css'

const DEFAULT_PAGE = 1
const DEFAULT_SIZE = 20
type WorkflowPlanListStatus = NonNullable<WorkflowPlanListQuery['status']>
const statuses = new Set<WorkflowPlanListStatus>([
  'DRAFT',
  'WAITING_CONFIRMATION',
  'CONFIRMED',
  'ABANDONED',
])

function positiveInteger(value: string | null, fallback: number) {
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback
}

interface ConfirmationAttempt {
  planId: string
  request: WorkflowPlanConfirmRequest
  idempotencyKey: string
}

function sameConfirmationAttempt(
  attempt: ConfirmationAttempt | null,
  planId: string,
  request: WorkflowPlanConfirmRequest,
) {
  return Boolean(
    attempt
    && attempt.planId === planId
    && attempt.request.planVersion === request.planVersion
    && attempt.request.authorizedMaxPoints === request.authorizedMaxPoints
    && attempt.request.confirmationToken === request.confirmationToken,
  )
}

interface WorkflowPlanDetailExperienceProps {
  planId?: string
  abandonError: WorkflowPlanErrorView | null
  abandoning: boolean
  onClose: () => void
  onAbandon: (planId: string, version: number) => void
}

function WorkflowPlanDetailExperience(props: WorkflowPlanDetailExperienceProps) {
  const { message } = AntApp.useApp()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const ownerId = useAuthStore((state) => state.user?.id)
  const detailQuery = useWorkflowPlan(props.planId)
  const detail = useMemo(
    () => detailQuery.data ? toWorkflowPlanDetail(detailQuery.data) : null,
    [detailQuery.data],
  )
  const [confirmationOpen, setConfirmationOpen] = useState(false)
  const [confirmationPreview, setConfirmationPreview] =
    useState<WorkflowPlanConfirmationPreviewView | null>(null)
  const [confirmationResult, setConfirmationResult] =
    useState<WorkflowPlanConfirmationResultView | null>(null)
  const [confirmationError, setConfirmationError] = useState<WorkflowPlanErrorView | null>(null)
  const [previewing, setPreviewing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [authorizationReady, setAuthorizationReady] = useState(false)
  const authorizationRef = useRef<WorkflowConfirmationAuthorization | null>(null)
  const confirmationAttemptRef = useRef<ConfirmationAttempt | null>(null)
  const confirmingLockRef = useRef(false)
  const previewSequenceRef = useRef(0)
  const [startOpen, setStartOpen] = useState(false)
  const [starting, setStarting] = useState(false)
  const [startError, setStartError] = useState<WorkflowPlanErrorView | null>(null)
  const [startResult, setStartResult] = useState<WorkflowPlanStartResultView | null>(null)
  const startingLockRef = useRef(false)

  const clearConfirmationSecrets = useCallback(() => {
    authorizationRef.current = null
    confirmationAttemptRef.current = null
    confirmingLockRef.current = false
  }, [])

  const closeConfirmation = useCallback(() => {
    previewSequenceRef.current += 1
    clearConfirmationSecrets()
    setAuthorizationReady(false)
    setConfirmationOpen(false)
    setConfirmationPreview(null)
    setConfirmationError(null)
    setPreviewing(false)
    setConfirming(false)
  }, [clearConfirmationSecrets])

  useEffect(() => () => {
    previewSequenceRef.current += 1
    clearConfirmationSecrets()
  }, [clearConfirmationSecrets])

  useEffect(() => {
    const authorization = authorizationRef.current
    if (!confirmationOpen || !confirmationPreview || !authorization) return undefined
    const expiresIn = Date.parse(authorization.expiresAt) - Date.now()
    const delay = Number.isFinite(expiresIn)
      ? Math.min(Math.max(expiresIn, 0), 2_147_483_647)
      : 0
    const timer = window.setTimeout(() => {
      clearConfirmationSecrets()
      setAuthorizationReady(false)
      setConfirmationPreview(null)
      setConfirmationError({ message: '确认信息已过期，请重新获取。' })
      void detailQuery.refetch()
    }, delay)
    return () => window.clearTimeout(timer)
  }, [clearConfirmationSecrets, confirmationOpen, confirmationPreview, detailQuery])

  const requestConfirmationPreview = useCallback(async (id: string, version: number) => {
    if (
      !detail
      || detail.id !== id
      || detail.version !== version
      || detail.status !== 'WAITING_CONFIRMATION'
      || detail.confirmation.expired
    ) return

    previewSequenceRef.current += 1
    const sequence = previewSequenceRef.current
    clearConfirmationSecrets()
    setAuthorizationReady(false)
    setConfirmationOpen(true)
    setConfirmationPreview(null)
    setConfirmationError(null)
    setPreviewing(true)
    try {
      const authorization = await previewWorkflowPlanConfirmation(id, { planVersion: version })
      if (sequence !== previewSequenceRef.current) return
      if (authorization.planId !== id || authorization.planVersion !== version) {
        setConfirmationError({ message: '确认信息与当前计划不一致，请刷新后重试。' })
        void detailQuery.refetch()
        return
      }
      const expiresAt = Date.parse(authorization.expiresAt)
      if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
        setConfirmationError({ message: '确认信息已过期，请重新获取。' })
        void detailQuery.refetch()
        return
      }
      authorizationRef.current = authorization
      setAuthorizationReady(true)
      setConfirmationPreview(toWorkflowConfirmationPreview(authorization))
    } catch (error) {
      if (sequence !== previewSequenceRef.current) return
      setConfirmationError(toWorkflowPlanError(error))
      if (isApiError(error) && [409, 410, 422].includes(error.status)) {
        clearConfirmationSecrets()
        setAuthorizationReady(false)
        void detailQuery.refetch()
      }
    } finally {
      if (sequence === previewSequenceRef.current) setPreviewing(false)
    }
  }, [clearConfirmationSecrets, detail, detailQuery])

  const confirmPlan = useCallback(async () => {
    const authorization = authorizationRef.current
    if (!authorization || confirmingLockRef.current) return
    const expiresAt = Date.parse(authorization.expiresAt)
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
      clearConfirmationSecrets()
      setAuthorizationReady(false)
      setConfirmationPreview(null)
      setConfirmationError({ message: '确认信息已过期，请重新获取。' })
      void detailQuery.refetch()
      return
    }

    const request: WorkflowPlanConfirmRequest = {
      planVersion: authorization.planVersion,
      authorizedMaxPoints: authorization.totalQuotedPoints,
      confirmationToken: authorization.confirmationToken,
    }
    const previousAttempt = confirmationAttemptRef.current
    const attempt = sameConfirmationAttempt(previousAttempt, authorization.planId, request)
      ? previousAttempt as ConfirmationAttempt
      : { planId: authorization.planId, request, idempotencyKey: crypto.randomUUID() }
    confirmationAttemptRef.current = attempt
    confirmingLockRef.current = true
    setConfirming(true)
    setConfirmationError(null)
    try {
      const result = await confirmWorkflowPlan(attempt.planId, attempt.request, attempt.idempotencyKey)
      const resultView = toWorkflowConfirmationResult(result)
      const keys = workflowPlanKeys(ownerId)
      setConfirmationResult(resultView)
      closeConfirmation()
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.lists() }),
        queryClient.invalidateQueries({ queryKey: keys.detail(result.planId) }),
      ])
      void message.success('计划已确认，等待执行能力接入')
    } catch (error) {
      setConfirmationError(toWorkflowPlanError(error))
      if (isApiError(error) && [409, 410, 422].includes(error.status)) {
        clearConfirmationSecrets()
        setAuthorizationReady(false)
        setConfirmationPreview(null)
        void detailQuery.refetch()
      } else {
        confirmingLockRef.current = false
      }
    } finally {
      setConfirming(false)
    }
  }, [clearConfirmationSecrets, closeConfirmation, detailQuery, message, ownerId, queryClient])

  const requestStart = useCallback((id: string, version: number) => {
    if (!detail || detail.id !== id || detail.version !== version || detail.status !== 'CONFIRMED' || !detail.executionEnabled) return
    setStartError(null)
    setStartOpen(true)
  }, [detail])

  const startPlan = useCallback(async () => {
    if (!detail || detail.status !== 'CONFIRMED' || !detail.executionEnabled || startingLockRef.current) return
    startingLockRef.current = true
    setStarting(true)
    setStartError(null)
    try {
      const attempt = await prepareWorkflowPlanStartAttempt(ownerId, detail.id, detail.version)
      const result = await startWorkflowPlan(
        detail.id,
        { planVersion: detail.version },
        attempt.idempotencyKey,
      )
      setStartResult(toWorkflowStartResult(result))
      clearWorkflowPlanStartRecovery(ownerId)
      setStartOpen(false)
      const keys = workflowPlanKeys(ownerId)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.lists() }),
        queryClient.invalidateQueries({ queryKey: keys.detail(result.planId) }),
      ])
      void message.success(result.status === 'QUEUED' ? '计划已进入执行队列' : '执行状态已更新')
    } catch (error) {
      setStartError(toWorkflowPlanError(error))
      if (isApiError(error) && [409, 422].includes(error.status)) {
        clearWorkflowPlanStartRecovery(ownerId)
        void detailQuery.refetch()
      }
    } finally {
      startingLockRef.current = false
      setStarting(false)
    }
  }, [detail, detailQuery, message, ownerId, queryClient])

  return (
    <>
      <WorkflowPlanDetailDrawer
        open={Boolean(props.planId)}
        plan={detail}
        loading={detailQuery.isLoading}
        error={toWorkflowPlanError(detailQuery.error)}
        abandonError={props.abandonError}
        confirmationResult={confirmationResult}
        startResult={startResult}
        abandoning={props.abandoning}
        onClose={props.onClose}
        onRetry={() => void detailQuery.refetch()}
        onRequestConfirmation={(id, version) => void requestConfirmationPreview(id, version)}
        onRequestStart={requestStart}
        onAbandon={props.onAbandon}
        onOpenTask={(taskId) => navigate(appLinks.taskDetail(taskId))}
        onOpenTaskResult={(result: WorkflowTaskResultView) => {
          const destination = workflowTaskResultPath(result.kind, result.id)
          if (destination) navigate(destination)
        }}
      />
      <WorkflowPlanConfirmationDialog
        open={confirmationOpen}
        plan={detail}
        preview={confirmationPreview}
        loading={previewing}
        confirming={confirming}
        error={confirmationError}
        confirmEnabled={Boolean(confirmationPreview && authorizationReady)}
        onClose={closeConfirmation}
        onRetryPreview={() => {
          if (detail) void requestConfirmationPreview(detail.id, detail.version)
        }}
        onConfirm={() => void confirmPlan()}
      />
      <WorkflowPlanStartDialog
        open={startOpen}
        plan={detail}
        starting={starting}
        error={startError}
        onClose={() => setStartOpen(false)}
        onStart={() => void startPlan()}
      />
    </>
  )
}

export function WorkflowPlansPage() {
  const { modal, message } = AntApp.useApp()
  const navigate = useNavigate()
  const { planId } = useParams<{ planId: string }>()
  const [search, setSearch] = useSearchParams()
  const page = positiveInteger(search.get('page'), DEFAULT_PAGE)
  const size = positiveInteger(search.get('size'), DEFAULT_SIZE)
  const statusParam = search.get('status')
  const status = statusParam && statuses.has(statusParam as WorkflowPlanListStatus)
    ? statusParam as WorkflowPlanListStatus
    : undefined
  const listQuery = useWorkflowPlans({ page, size, status })
  const abandonMutation = useAbandonWorkflowPlan()

  const plans = useMemo(
    () => (listQuery.data?.items ?? []).map(toWorkflowPlanSummary),
    [listQuery.data],
  )

  const updateSearch = (updates: Record<string, string | undefined>) => {
    const next = new URLSearchParams(search)
    Object.entries(updates).forEach(([key, value]) => {
      if (value === undefined) next.delete(key)
      else next.set(key, value)
    })
    setSearch(next)
  }

  const confirmAbandon = (id: string, version: number) => {
    modal.confirm({
      title: '确认放弃该计划？',
      content: '放弃后计划将变为只读，不能再确认执行。',
      okText: '确认放弃',
      okButtonProps: { danger: true },
      cancelText: '暂不放弃',
      onOk: async () => {
        try {
          await abandonMutation.mutateAsync({ planId: id, request: { version } })
          void message.success('计划已放弃')
        } catch {
          // The drawer keeps the backend message; trace visibility follows the account role.
        }
      },
    })
  }

  const searchSuffix = search.toString() ? `?${search.toString()}` : ''
  return (
    <UserShell>
      <WorkflowPlanList
        plans={plans}
        loading={listQuery.isLoading}
        error={toWorkflowPlanError(listQuery.error)}
        status={status}
        pagination={{
          current: listQuery.data?.page ?? page,
          pageSize: listQuery.data?.size ?? size,
          total: listQuery.data?.total ?? 0,
        }}
        onStatusChange={(next) => updateSearch({ status: next, page: String(DEFAULT_PAGE) })}
        onPageChange={(nextPage, nextSize) => updateSearch({ page: String(nextPage), size: String(nextSize) })}
        onView={(id) => navigate(`${appLinks.workflowPlanDetail(id)}${searchSuffix}`)}
        onRetry={() => void listQuery.refetch()}
      />
      <WorkflowPlanDetailExperience
        key={planId ?? 'no-plan'}
        planId={planId}
        abandonError={toWorkflowPlanError(abandonMutation.error)}
        abandoning={abandonMutation.isPending}
        onClose={() => navigate(`${APP_PATHS.workflowPlans}${searchSuffix}`)}
        onAbandon={confirmAbandon}
      />
    </UserShell>
  )
}

export function WorkflowPlanDetailPage() {
  return <WorkflowPlansPage />
}

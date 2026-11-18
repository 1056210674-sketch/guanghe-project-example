import { App as AntApp } from 'antd'
import { useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import {
  confirmGeoPlan,
  createGeoPlanDraft,
  updateGeoPlan,
} from '../../api/geoApi'
import type { GeoPlan, GeoPlanDraft as GeoPlanDraftDto } from '../../api/geoApi'
import { isApiError } from '../../api/apiError'
import {
  GeoPlanManager,
  geoKeys,
  useAllGeoTopics,
  useGeoPlans,
  useGeoPlatforms,
  useGeoTopic,
  usePauseGeoPlan,
  useResumeGeoPlan,
} from '../../features/geo'
import type {
  GeoPlanAuthorizationView,
  GeoPlanDraftChange,
  GeoPlanDraftView,
  GeoPlanFrequency,
  GeoPlanStatus,
  GeoPlanView,
} from '../../features/geo'
import {
  fingerprintJsonInput,
  reuseOrCreateInputAttempt,
} from '../../features/task-draft/formTaskDraftLifecycle'
import { geoPlanRecoveryStore } from '../../features/geo/geoPlanRecovery'
import { useAuthStore } from '../../stores/authStore'
import { APP_PATHS } from '../../routes/paths'
import {
  toCreateGeoPlanDraftRequest,
  toGeoPlanDraftView,
  toGeoPlanPagination,
  toGeoPlanView,
  toGeoPlatformView,
  toGeoQuestionViews,
  toGeoTopicSummaryView,
  toGeoViewError,
} from './viewAdapters'

const DEFAULT_PAGE = 1
const DEFAULT_SIZE = 20

interface InMemoryAuthorization {
  dto: GeoPlanDraftDto
  view: GeoPlanAuthorizationView
}

function positiveInteger(value: string | null, fallback: number) {
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback
}

function browserTimeZone() {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai' }
  catch { return 'Asia/Shanghai' }
}

function newDraft(): GeoPlanDraftView {
  return {
    name: '',
    questionIds: [],
    platformCodes: [],
    mode: 'STANDARD',
    schedule: { frequency: 'DAILY', localTime: '09:00', timeZone: browserTimeZone() },
  }
}

function changeFrequency(draft: GeoPlanDraftView, frequency: GeoPlanFrequency): GeoPlanDraftView {
  const base = { localTime: draft.schedule.localTime, timeZone: draft.schedule.timeZone }
  if (frequency === 'WEEKLY') return { ...draft, schedule: { frequency, weekdays: [], ...base } }
  if (frequency === 'INTERVAL_DAYS') return { ...draft, schedule: { frequency, intervalDays: 1, ...base } }
  return { ...draft, schedule: { frequency, ...base } }
}

function reduceGeoPlanDraft(draft: GeoPlanDraftView, change: GeoPlanDraftChange): GeoPlanDraftView {
  switch (change.field) {
    case 'NAME': return { ...draft, name: change.value }
    case 'TOPIC': return { ...draft, topicId: change.value, questionIds: [] }
    case 'QUESTIONS': return { ...draft, questionIds: [...change.value] }
    case 'PLATFORMS': return { ...draft, platformCodes: [...change.value] }
    case 'MODE': return { ...draft, mode: change.value }
    case 'FREQUENCY': return changeFrequency(draft, change.value)
    case 'LOCAL_TIME': return { ...draft, schedule: { ...draft.schedule, localTime: change.value } }
    case 'TIME_ZONE': return { ...draft, schedule: { ...draft.schedule, timeZone: change.value } }
    case 'WEEKDAYS': return draft.schedule.frequency === 'WEEKLY'
      ? { ...draft, schedule: { ...draft.schedule, weekdays: [...change.value] } } : draft
    case 'INTERVAL_DAYS': return draft.schedule.frequency === 'INTERVAL_DAYS'
      ? { ...draft, schedule: { ...draft.schedule, intervalDays: change.value ?? 1 } } : draft
    case 'MAX_POINTS_PER_RUN': return { ...draft, maxPointsPerRun: change.value }
  }
}

function riskConfiguration(plan: GeoPlan, draft: GeoPlanDraftView) {
  const existing = toGeoPlanDraftView(plan)
  return JSON.stringify({
    topicId: draft.topicId,
    schedule: draft.schedule,
    questionIds: draft.questionIds,
    platformCodes: draft.platformCodes,
    mode: draft.mode,
    maxPointsPerRun: draft.maxPointsPerRun,
  }) !== JSON.stringify({
    topicId: existing.topicId,
    schedule: existing.schedule,
    questionIds: existing.questionIds,
    platformCodes: existing.platformCodes,
    mode: existing.mode,
    maxPointsPerRun: existing.maxPointsPerRun,
  })
}

export function GeoPlansSection() {
  const navigate = useNavigate()
  const { message, modal } = AntApp.useApp()
  const queryClient = useQueryClient()
  const ownerId = useAuthStore((state) => state.user?.id)
  const [params, setParams] = useSearchParams()
  const page = positiveInteger(params.get('planPage'), DEFAULT_PAGE)
  const size = positiveInteger(params.get('planSize'), DEFAULT_SIZE)
  const rawStatus = params.get('planStatus')
  const status: GeoPlanStatus | undefined = rawStatus === 'ACTIVE' || rawStatus === 'PAUSED' ? rawStatus : undefined
  const selectedPlanId = params.get('planId') ?? undefined
  const selectedTopicId = params.get('topicId') ?? undefined

  const plansQuery = useGeoPlans({ page, size, status, topicId: selectedTopicId })
  const topicsQuery = useAllGeoTopics()
  const platformsQuery = useGeoPlatforms()
  const pauseMutation = usePauseGeoPlan()
  const resumeMutation = useResumeGeoPlan()
  const [draft, setDraft] = useState<GeoPlanDraftView>()
  const [editingPlan, setEditingPlan] = useState<GeoPlan>()
  const [authorization, setAuthorization] = useState<InMemoryAuthorization>()
  const [actionError, setActionError] = useState<unknown>()
  const [saving, setSaving] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [operatingPlanId, setOperatingPlanId] = useState<string>()
  const saveLock = useRef(false)
  const confirmLock = useRef(false)
  const persistedRecovery = geoPlanRecoveryStore.read(ownerId)
  const restoredPlan = selectedPlanId && selectedPlanId !== 'new'
    ? plansQuery.data?.items.find((item) => item.id === selectedPlanId)
    : undefined
  const activeEditingPlan = editingPlan ?? restoredPlan
  const recoveredDraft = persistedRecovery.editorTarget === selectedPlanId
    ? persistedRecovery.formDraft
    : undefined
  const activeDraft = draft ?? recoveredDraft ?? (selectedPlanId === 'new'
    ? { ...newDraft(), topicId: selectedTopicId }
    : restoredPlan ? toGeoPlanDraftView(restoredPlan) : undefined)
  const topicQuery = useGeoTopic(activeDraft?.topicId)

  const topics = (topicsQuery.data ?? []).map((topic) => toGeoTopicSummaryView(topic))
  const platforms = (platformsQuery.data ?? []).map(toGeoPlatformView)
  const plans = (plansQuery.data?.items ?? []).map((plan) => {
    const topicName = topicsQuery.data?.find((topic) => topic.id === plan.topicId)?.name
    return toGeoPlanView(plan, topicName)
  })
  const questions = topicQuery.data ? toGeoQuestionViews(topicQuery.data) : []

  function updateUrl(values: Record<string, string | number | undefined>) {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(values)) {
      if (value === undefined || value === '') next.delete(key)
      else next.set(key, String(value))
    }
    setParams(next, { replace: true })
  }

  function clearEditor() {
    setDraft(undefined)
    setEditingPlan(undefined)
    setAuthorization(undefined)
    setActionError(undefined)
    geoPlanRecoveryStore.clear(ownerId)
    updateUrl({ planId: undefined })
  }

  function openCreate() {
    const initialDraft = { ...newDraft(), topicId: selectedTopicId }
    setDraft(initialDraft)
    setEditingPlan(undefined)
    setAuthorization(undefined)
    setActionError(undefined)
    geoPlanRecoveryStore.write(ownerId, { editorTarget: 'new', formDraft: initialDraft })
    updateUrl({ planId: 'new' })
  }

  function openEdit(planId: string) {
    const plan = plansQuery.data?.items.find((item) => item.id === planId)
    if (!plan) return
    setEditingPlan(plan)
    setDraft(toGeoPlanDraftView(plan))
    setAuthorization(undefined)
    setActionError(undefined)
    geoPlanRecoveryStore.write(ownerId, {
      editorTarget: plan.id,
      formDraft: toGeoPlanDraftView(plan),
    })
    updateUrl({ planId })
  }

  async function generateAuthorization(nextDraft: GeoPlanDraftView) {
    if (saveLock.current || !ownerId) return
    saveLock.current = true
    setSaving(true)
    setActionError(undefined)
    try {
      if (activeEditingPlan && !riskConfiguration(activeEditingPlan, nextDraft)) {
        if (nextDraft.name.trim() === activeEditingPlan.name) {
          clearEditor()
          return
        }
        await updateGeoPlan(activeEditingPlan.id, { version: activeEditingPlan.version, name: nextDraft.name.trim() })
        await queryClient.invalidateQueries({ queryKey: geoKeys.forOwner(ownerId).planLists() })
        message.success('计划名称已更新')
        clearEditor()
        return
      }

      const request = toCreateGeoPlanDraftRequest(nextDraft)
      const fingerprint = await fingerprintJsonInput(request)
      const recovery = geoPlanRecoveryStore.read(ownerId)
      const draftAttempt = reuseOrCreateInputAttempt(recovery.draftAttempt, fingerprint)
      geoPlanRecoveryStore.write(ownerId, { ...recovery, draftAttempt })
      const dto = await createGeoPlanDraft(request, draftAttempt.idempotencyKey)
      const topic = topicsQuery.data?.find((item) => item.id === request.topicId)
      const questionLabels = topicQuery.data?.questions
        .filter((question) => request.questionIds.includes(question.id))
        .map((question) => question.text) ?? []
      const platformNames = request.platformCodes.map((code) =>
        platformsQuery.data?.find((item) => item.code === code)?.name ?? code)
      const confirmationAttempt = {
        draftId: dto.draftId,
        inputHash: dto.inputHash,
        pricingVersionId: dto.pricingVersionId,
        idempotencyKey: crypto.randomUUID(),
      }
      geoPlanRecoveryStore.write(ownerId, {
        editorTarget: selectedPlanId ?? 'new',
        formDraft: nextDraft,
        draftAttempt,
        confirmationAttempt,
      })
      setAuthorization({
        dto,
        view: {
          draftId: dto.draftId,
          title: request.name,
          topicName: topic?.name ?? `专题 ${request.topicId}`,
          questionLabels,
          platformNames,
          mode: request.sampleMode,
          schedule: nextDraft.schedule,
          estimatedPointsPerRun: dto.estimatedPointsPerRun,
          maxPointsPerRun: request.maxPointsPerRun,
          pricingVersionId: dto.pricingVersionId,
          expiresAt: new Date(dto.expiresAt).toLocaleString('zh-CN', { hour12: false }),
          confirmEnabled: Boolean(dto.confirmationToken),
        },
      })
    } catch (error) {
      setActionError(error)
    } finally {
      saveLock.current = false
      setSaving(false)
    }
  }

  async function confirmAuthorization() {
    if (confirmLock.current || !authorization || !ownerId || !authorization.dto.confirmationToken) return
    confirmLock.current = true
    setConfirming(true)
    setActionError(undefined)
    try {
      const recovery = geoPlanRecoveryStore.read(ownerId)
      const persisted = recovery.confirmationAttempt
      const idempotencyKey = persisted?.draftId === authorization.dto.draftId
        ? persisted.idempotencyKey : crypto.randomUUID()
      const request = {
        draftId: authorization.dto.draftId,
        inputHash: authorization.dto.inputHash,
        pricingVersionId: authorization.dto.pricingVersionId,
        confirmationToken: authorization.dto.confirmationToken,
      }
      if (activeEditingPlan) {
        await updateGeoPlan(activeEditingPlan.id, { version: activeEditingPlan.version, ...request })
      } else {
        await confirmGeoPlan(request, idempotencyKey)
      }
      geoPlanRecoveryStore.clear(ownerId)
      await queryClient.invalidateQueries({ queryKey: geoKeys.forOwner(ownerId).planLists() })
      message.success(activeEditingPlan ? '计划配置已更新' : '自动监测计划已创建')
      clearEditor()
    } catch (error) {
      if (isApiError(error) && error.status === 410) {
        setAuthorization(undefined)
        geoPlanRecoveryStore.write(ownerId, {
          editorTarget: selectedPlanId,
          formDraft: activeDraft,
        })
      }
      setActionError(error)
    } finally {
      confirmLock.current = false
      setConfirming(false)
    }
  }

  function changeStatus(plan: GeoPlanView, action: 'pause' | 'resume') {
    modal.confirm({
      title: action === 'pause' ? '确认暂停自动监测计划？' : '确认恢复自动监测计划？',
      content: action === 'pause'
        ? '暂停后不会创建新的计划运行。'
        : '恢复后从下一个计划时间开始，不会补跑暂停期间的历史窗口。',
      okText: action === 'pause' ? '确认暂停' : '确认恢复',
      cancelText: '取消',
      onOk: async () => {
        if (operatingPlanId) return
        setOperatingPlanId(plan.id)
        setActionError(undefined)
        try {
          const mutation = action === 'pause' ? pauseMutation : resumeMutation
          await mutation.mutateAsync({ planId: plan.id, request: { version: plan.version } })
          message.success(action === 'pause' ? '计划已暂停' : '计划已恢复')
        } catch (error) {
          setActionError(error)
          throw error
        } finally { setOperatingPlanId(undefined) }
      },
    })
  }

  return <GeoPlanManager
    loading={plansQuery.isPending || topicsQuery.isPending || platformsQuery.isPending}
    error={toGeoViewError(plansQuery.error ?? topicsQuery.error ?? platformsQuery.error)}
    onRetry={() => { void plansQuery.refetch(); void topicsQuery.refetch(); void platformsQuery.refetch() }}
    plans={plans} platforms={platforms} topics={topics} questions={questions}
    pagination={toGeoPlanPagination(plansQuery.data, page, size)} statusFilter={status}
    editorOpen={Boolean(activeDraft) || Boolean(selectedPlanId)} draft={activeDraft}
    authorization={authorization?.view} editingPlanId={activeEditingPlan?.id}
    actionError={toGeoViewError(actionError)} saving={saving} confirming={confirming}
    operatingPlanId={operatingPlanId}
    onPageChange={(nextPage, nextSize) => updateUrl({ planPage: nextPage, planSize: nextSize })}
    onStatusFilterChange={(nextStatus) => updateUrl({ planStatus: nextStatus, planPage: DEFAULT_PAGE })}
    topicFilter={selectedTopicId}
    onTopicFilterChange={(topicId) => updateUrl({ topicId, planPage: DEFAULT_PAGE })}
    onOpenCreatePlan={openCreate} onCloseEditor={clearEditor}
    onBackToEditor={() => { setAuthorization(undefined); setActionError(undefined) }}
    onDraftChange={(change) => {
      const nextDraft = reduceGeoPlanDraft(draft ?? activeDraft ?? newDraft(), change)
      setDraft(nextDraft)
      geoPlanRecoveryStore.write(ownerId, {
        ...geoPlanRecoveryStore.read(ownerId),
        editorTarget: selectedPlanId ?? 'new',
        formDraft: nextDraft,
      })
    }}
    onSavePlanIntent={(value) => { void generateAuthorization(value) }}
    onConfirmPlan={() => { void confirmAuthorization() }}
    onEditPlanIntent={openEdit}
    onPausePlanIntent={(planId) => { const plan = plans.find((item) => item.id === planId); if (plan) changeStatus(plan, 'pause') }}
    onResumePlanIntent={(planId) => { const plan = plans.find((item) => item.id === planId); if (plan) changeStatus(plan, 'resume') }}
    onOpenTasks={() => navigate(APP_PATHS.tasks)} onOpenReports={() => navigate(APP_PATHS.geoReports)}
  />
}

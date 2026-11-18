import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import {
  getPaidTask,
  getTaskDraft,
  listPaidTaskEvents,
} from '../../api/conversationApi'
import type { PaidTask } from '../../api/conversationApi'
import { getProjectGeoTarget } from '../../api/agentPlatformApi'
import {
  useAllBusinessSpaceOptions,
  useAllProductOptions,
} from '../../features/business-data'
import {
  toPaidTaskConfirmation,
  toParallelTaskItem,
  toTaskCreatedReceipt,
  toTaskExecutionOutcome,
  usePointAccount,
} from '../../features/chat'
import { useConversationEventStream } from '../../features/chat/live/useConversationEventStream'
import type { ParsedConversationEvent } from '../../features/chat/live/conversationStream'
import {
  GeoManualMonitoring,
  useAllGeoTopics,
  useGeoPlatforms,
  useGeoRunProgress,
  useGeoTopic,
} from '../../features/geo'
import type {
  GeoManualMonitoringIntent,
  GeoMonitoringMode,
  GeoViewError,
} from '../../features/geo'
import {
  readGeoManualRecovery,
  readGeoQuickRecovery,
  storeGeoManualRecovery,
  storeGeoQuickRecovery,
} from '../../features/geo/geoManualRecovery'
import type { GeoManualRecoveryState } from '../../features/geo/geoManualRecovery'
import {
  prepareFormTaskDraft,
  resolveFormTaskDraftConversation,
  submitFormTaskDraftConfirmation,
} from '../../features/task-draft'
import { APP_PATHS, appLinks } from '../../routes/paths'
import { useAuthStore } from '../../stores/authStore'
import {
  toGeoPlatformView,
  toGeoQuestionViews,
  toGeoTopicSummaryView,
  toGeoViewError,
} from './viewAdapters'
import {
  toGeoTaskDraftRequest,
} from './geoManualTaskFlow'

const GEO_MANUAL_CONVERSATION_TITLE = 'GEO 手动监测'
const GEO_QUICK_CONVERSATION_TITLE = 'GEO 快速监测'

const TERMINAL_STATUSES: readonly PaidTask['status'][] = [
  'SUCCEEDED',
  'PARTIAL',
  'FAILED',
  'SKIPPED',
  'SYSTEM_TERMINATED',
]

function isTerminalTask(task?: PaidTask): boolean {
  return Boolean(task && TERMINAL_STATUSES.includes(task.status))
}

function refreshDelay(failureCount: number): number {
  return Math.min(5_000 * (2 ** Math.max(0, failureCount)), 30_000)
}

function parsedMode(value: string | null): GeoMonitoringMode {
  return value === 'DEEP' ? 'DEEP' : 'STANDARD'
}

function firstError(...errors: unknown[]) {
  return errors.find(Boolean)
}

function toActionError(error: unknown): GeoViewError {
  return toGeoViewError(error) ?? { message: '请求失败，请稍后重试。' }
}

interface GeoManualMonitoringSectionProps {
  experience?: 'MANUAL' | 'QUICK'
}

export function GeoManualMonitoringSection({
  experience = 'MANUAL',
}: GeoManualMonitoringSectionProps = {}) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const ownerId = useAuthStore((state) => state.user?.id)
  const quickExperience = experience === 'QUICK'
  const flowKey = quickExperience ? 'geo-quick' : 'geo-manual'
  const readRecovery = quickExperience ? readGeoQuickRecovery : readGeoManualRecovery
  const storeRecovery = quickExperience ? storeGeoQuickRecovery : storeGeoManualRecovery
  const conversationTitle = quickExperience
    ? GEO_QUICK_CONVERSATION_TITLE
    : GEO_MANUAL_CONVERSATION_TITLE
  const [searchParams, setSearchParams] = useSearchParams()
  const [topicOverride, setTopicOverride] = useState<string | undefined>(
    searchParams.get('topicId') ?? undefined,
  )
  const [questionOverride, setQuestionOverride] = useState<string[]>()
  const [platformOverride, setPlatformOverride] = useState<string[] | undefined>(() => {
    const platform = searchParams.get('platformCode')
    return platform ? [platform] : undefined
  })
  const [modeOverride, setModeOverride] = useState<GeoMonitoringMode>()
  const [recovery, setRecovery] = useState<GeoManualRecoveryState>(() =>
    readRecovery(ownerId),
  )
  const [editingDraft, setEditingDraft] = useState(false)
  const [preparing, setPreparing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [actionError, setActionError] = useState<GeoViewError | null>(null)
  const prepareLock = useRef(false)
  const confirmLock = useRef(false)

  const updateRecovery = (next: GeoManualRecoveryState) => {
    setRecovery(next)
    storeRecovery(ownerId, next)
  }

  const draftId = recovery.draftAttempt?.draftId
  const taskId = recovery.confirmationAttempt?.taskId
  const draftQuery = useQuery({
    queryKey: [flowKey, ownerId ?? null, 'draft', draftId ?? null],
    queryFn: () => getTaskDraft(draftId as string),
    enabled: Boolean(draftId),
  })
  const recoveredParameters =
    draftQuery.data?.taskType === 'GEO' &&
    'topicId' in draftQuery.data.parameters
      ? draftQuery.data.parameters
      : undefined
  const urlTopicId = searchParams.get('topicId') ?? undefined
  const recoveredMatchesUrl =
    !urlTopicId || recoveredParameters?.topicId === urlTopicId
  const selectedTopicId =
    topicOverride ??
    (recoveredMatchesUrl ? recoveredParameters?.topicId : undefined)
  const topicsQuery = useAllGeoTopics()
  const topicQuery = useGeoTopic(selectedTopicId)
  const platformsQuery = useGeoPlatforms()
  const spacesQuery = useAllBusinessSpaceOptions()
  const productsQuery = useAllProductOptions(topicQuery.data?.spaceId)
  const taskQuery = useQuery({
    queryKey: [flowKey, ownerId ?? null, 'task', taskId ?? null],
    queryFn: () => getPaidTask(taskId as string),
    enabled: Boolean(taskId),
    refetchInterval: (query) =>
      isTerminalTask(query.state.data)
        ? false
        : refreshDelay(query.state.fetchFailureCount),
    refetchIntervalInBackground: false,
  })
  const taskEventsQuery = useQuery({
    queryKey: [flowKey, ownerId ?? null, 'task-events', taskId ?? null],
    queryFn: () => listPaidTaskEvents(taskId as string),
    enabled: Boolean(taskId),
    refetchInterval: (query) =>
      isTerminalTask(taskQuery.data)
        ? false
        : refreshDelay(query.state.fetchFailureCount),
    refetchIntervalInBackground: false,
  })

  const topic = topicQuery.data
  const enabledQuestions = useMemo(
    () => topic
      ? toGeoQuestionViews(topic).filter((question) => question.status === 'ENABLED')
      : [],
    [topic],
  )
  const platforms = useMemo(
    () => (platformsQuery.data ?? []).map(toGeoPlatformView),
    [platformsQuery.data],
  )
  const recoveredQuestionIds = recoveredMatchesUrl && recoveredParameters
    ? [...recoveredParameters.questionIds]
    : undefined
  const recoveredPlatformCodes = recoveredMatchesUrl && recoveredParameters
    ? [...recoveredParameters.platformCodes]
    : undefined
  const selectedQuestionIds =
    questionOverride ??
    recoveredQuestionIds ??
    (quickExperience
      ? enabledQuestions.slice(0, 3).map((question) => question.id)
      : [])
  const selectedPlatformCodes =
    platformOverride ??
    recoveredPlatformCodes ??
    (quickExperience
      ? platforms
          .filter((platform) => platform.availability === 'AVAILABLE')
          .slice(0, 1)
          .map((platform) => platform.code)
      : [])
  const mode =
    modeOverride ??
    (recoveredMatchesUrl ? recoveredParameters?.sampleMode : undefined) ??
    parsedMode(searchParams.get('sampleMode'))
  const topics = useMemo(() => {
    const spaces = new Map((spacesQuery.data ?? []).map((space) => [space.id, space.name]))
    return (topicsQuery.data ?? []).map((item) =>
      toGeoTopicSummaryView(item, spaces.get(item.spaceId)),
    )
  }, [spacesQuery.data, topicsQuery.data])
  const products = productsQuery.data ?? []
  const productName = products.find((item) => item.id === topic?.productId)?.name
  const businessSpaceName = (spacesQuery.data ?? []).find(
    (space) => space.id === topic?.spaceId,
  )?.name

  const draftMatchesTopic =
    draftQuery.data?.taskType === 'GEO' &&
    'topicId' in draftQuery.data.parameters &&
    draftQuery.data.parameters.topicId === selectedTopicId
  const activeDraft =
    !editingDraft && !taskId && draftMatchesTopic ? draftQuery.data : undefined
  const pointAccountQuery = usePointAccount(Boolean(activeDraft))
  const task = taskQuery.data
  const terminal = isTerminalTask(task)
  const geoResult = task && 'runId' in task.result ? task.result : undefined
  const geoRunProgressQuery = useGeoRunProgress(
    geoResult?.runId,
    Boolean(taskId),
  )
  const authoritativeRunProgress = geoRunProgressQuery.data
  const selectedQuestionNames = enabledQuestions
    .filter((question) => selectedQuestionIds.includes(question.id))
    .map((question) => question.text)
  const selectedPlatformNames = platforms
    .filter((platform) => selectedPlatformCodes.includes(platform.code))
    .map((platform) => platform.name)

  const taskProgressItem = task
    ? (() => {
        const base = toParallelTaskItem(task, productName)
        if (!authoritativeRunProgress) return base
        const recoveryPrefix = authoritativeRunProgress.retryable
          ? '自动恢复中 · '
          : ''
        return {
          ...base,
          progressLabel: `${recoveryPrefix}${task.stageLabel} · 已处理 ${authoritativeRunProgress.completedUnits}/${authoritativeRunProgress.totalUnits} 个监测点`,
          progressPercent: authoritativeRunProgress.percent,
        }
      })()
    : undefined

  const refreshTaskData = async () => {
    if (!taskId) return
    await Promise.all([
      queryClient.invalidateQueries({
        queryKey: [flowKey, ownerId ?? null, 'task', taskId],
      }),
      queryClient.invalidateQueries({
        queryKey: [flowKey, ownerId ?? null, 'task-events', taskId],
      }),
      queryClient.invalidateQueries({
        queryKey: ['task-center', ownerId ?? null],
      }),
      queryClient.invalidateQueries({
        queryKey: ['geo-foundation', 'owner', ownerId ?? null, 'runs'],
      }),
      queryClient.invalidateQueries({
        queryKey: ['geo-foundation', 'owner', ownerId ?? null, 'reports'],
      }),
    ])
  }

  const handleStreamEvent = async (event: ParsedConversationEvent) => {
    if (event.event.startsWith('task.')) {
      await refreshTaskData()
    }
  }

  useConversationEventStream({
    conversationId: recovery.conversationId,
    enabled: Boolean(taskId && task && !terminal),
    onEvent: handleStreamEvent,
    onError: (error) => setActionError(toActionError(error)),
  })

  const configurationNotice = !selectedTopicId
    ? undefined
    : topicQuery.isPending
      ? undefined
      : !topic?.region?.trim()
        ? '该专题尚未配置监测区域，不能创建正式监测草稿。'
        : enabledQuestions.length === 0
          ? '该专题暂无正式启用的问题。'
          : platforms.every((platform) => platform.availability !== 'AVAILABLE')
            ? '当前没有可选择的 GEO 平台。'
            : undefined

  const error =
    actionError ??
    toGeoViewError(firstError(
      topicsQuery.error,
      topicQuery.error,
      platformsQuery.error,
      spacesQuery.error,
      productsQuery.error,
      draftQuery.error,
      pointAccountQuery.error,
      taskQuery.error,
      taskEventsQuery.error,
    ))

  const updateUrl = (topicId: string | undefined, nextMode: GeoMonitoringMode) => {
    const next = new URLSearchParams(searchParams)
    if (topicId) next.set('topicId', topicId)
    else next.delete('topicId')
    next.set('sampleMode', nextMode)
    setSearchParams(next, { replace: true })
  }

  const beginEditing = () => {
    setEditingDraft(true)
    setActionError(null)
    setTopicOverride(selectedTopicId)
    setQuestionOverride([...selectedQuestionIds])
    setPlatformOverride([...selectedPlatformCodes])
    setModeOverride(mode)
    if (taskId) {
      updateRecovery({ conversationId: recovery.conversationId })
    }
  }

  const handleTopicChange = (topicId: string) => {
    beginEditing()
    setTopicOverride(topicId)
    setQuestionOverride(quickExperience ? undefined : [])
    setPlatformOverride(quickExperience ? undefined : [])
    updateUrl(topicId, mode)
  }

  const handlePrepareConfirmation = async (intent: GeoManualMonitoringIntent) => {
    if (prepareLock.current || !topic || !topic.region?.trim()) return
    const orderedQuestions = enabledQuestions
      .filter((question) => intent.questionIds.includes(question.id))
      .map((question) => question.id)
    const selectedPlatformSet = new Set(intent.platformCodes)
    const orderedPlatforms = (platformsQuery.data ?? [])
      .filter((platform) => platform.selectable && selectedPlatformSet.has(platform.code))
      .map((platform) => platform.code)
    if (orderedQuestions.length < 3 || orderedQuestions.length > 10) {
      setActionError({ message: '请选择 3～10 个正式启用的问题。' })
      return
    }
    if (!orderedPlatforms.length) return

    prepareLock.current = true
    setPreparing(true)
    setActionError(null)
    try {
      if (!topic.productId) {
        const target = await getProjectGeoTarget(topic.spaceId)
        if (!target.configured) {
          setActionError({ message: '请先设置项目的公开监测名称，再核对监测费用。尚未创建会话或付费任务。' })
          navigate(`${APP_PATHS.knowledge}?${new URLSearchParams({ project: topic.spaceId })}`)
          return
        }
      }
      const resolved = await resolveFormTaskDraftConversation({
        recovery,
        title: conversationTitle,
        onRecoveryChange: updateRecovery,
      })
      const { conversationId } = resolved
      const request = toGeoTaskDraftRequest(conversationId, {
        ...intent,
        questionIds: orderedQuestions,
        platformCodes: orderedPlatforms,
      }, {
        spaceId: topic.spaceId,
        productId: topic.productId,
        region: topic.region.trim(),
        platformCodes: orderedPlatforms,
      })
      const { draft: created } = await prepareFormTaskDraft({
        recovery: resolved.recovery,
        request,
        currentDraft: draftQuery.data,
        onRecoveryChange: updateRecovery,
      })
      queryClient.setQueryData(
        [flowKey, ownerId ?? null, 'draft', created.id],
        created,
      )
      setEditingDraft(false)
    } catch (caught) {
      setActionError(toActionError(caught))
    } finally {
      prepareLock.current = false
      setPreparing(false)
    }
  }

  const handleConfirm = async (cardId: string) => {
    const currentDraft = draftQuery.data
    if (
      confirmLock.current ||
      !currentDraft ||
      currentDraft.id !== cardId ||
      !currentDraft.confirmationToken
    ) return

    confirmLock.current = true
    setConfirming(true)
    setActionError(null)
    try {
      await submitFormTaskDraftConfirmation({
        recovery,
        draft: currentDraft,
        onRecoveryChange: updateRecovery,
      })
      await queryClient.invalidateQueries({
        queryKey: ['task-center', ownerId ?? null, 'list'],
      })
    } catch (caught) {
      setActionError(toActionError(caught))
    } finally {
      confirmLock.current = false
      setConfirming(false)
    }
  }

  return (
    <GeoManualMonitoring
      experience={experience}
      loading={
        topicsQuery.isPending ||
        platformsQuery.isPending ||
        spacesQuery.isPending ||
        (Boolean(selectedTopicId) && topicQuery.isPending)
      }
      error={error}
      onRetry={() => {
        setActionError(null)
        void topicsQuery.refetch()
        void topicQuery.refetch()
        void platformsQuery.refetch()
        void spacesQuery.refetch()
        if (taskId) {
          void taskQuery.refetch()
          void taskEventsQuery.refetch()
        }
      }}
      topics={topics}
      questionGroups={[]}
      questions={enabledQuestions}
      platforms={platforms}
      selectedTopicId={selectedTopicId}
      selectedQuestionIds={selectedQuestionIds}
      selectedPlatformCodes={selectedPlatformCodes}
      mode={mode}
      businessSpaceName={businessSpaceName}
      productName={productName}
      region={topic?.region ?? undefined}
      configurationNotice={configurationNotice}
      preparing={preparing}
      confirmation={activeDraft
        ? toPaidTaskConfirmation(
            activeDraft,
            pointAccountQuery.data,
            productName,
            confirming,
            {
              questionNames: selectedQuestionNames,
              platformNames: selectedPlatformNames,
            },
          )
        : undefined}
      taskReceipt={task
        ? toTaskCreatedReceipt(task, pointAccountQuery.data, productName)
        : undefined}
      taskProgress={task && !terminal
        ? {
            id: `geo-task-progress-${task.id}`,
            title: quickExperience ? 'GEO 快速监测执行状态' : 'GEO 监测执行状态',
            tasks: taskProgressItem ? [taskProgressItem] : [],
          }
        : undefined}
      taskOutcome={task && terminal
        ? {
            id: `geo-task-outcome-${task.id}`,
            outcomes: [toTaskExecutionOutcome(task)],
          }
        : undefined}
      reportId={geoResult?.reportId}
      onTopicChange={handleTopicChange}
      onQuestionSelectionChange={(ids) => {
        beginEditing()
        const selected = new Set(ids)
        setQuestionOverride(
          enabledQuestions.filter((question) => selected.has(question.id)).map((question) => question.id),
        )
      }}
      onPlatformSelectionChange={(codes) => {
        beginEditing()
        const selected = new Set(codes)
        setPlatformOverride(
          platforms.filter((platform) =>
            platform.availability === 'AVAILABLE' && selected.has(platform.code),
          ).map((platform) => platform.code),
        )
      }}
      onModeChange={(nextMode) => {
        beginEditing()
        setModeOverride(nextMode)
        updateUrl(selectedTopicId, nextMode)
      }}
      onBack={activeDraft ? beginEditing : undefined}
      onPrepareConfirmation={(intent) => void handlePrepareConfirmation(intent)}
      onEditConfirmation={beginEditing}
      onConfirm={(cardId) => void handleConfirm(cardId)}
      onOpenRecharge={() => navigate(APP_PATHS.pointsBilling)}
      onPrepareReestimate={beginEditing}
      onViewTask={(id) => navigate(appLinks.taskDetail(id))}
      onTaskOutcomeAction={(_id, actionType) => {
        if (actionType === 'VIEW_COMPLETED_REPORT' && geoResult?.reportId) {
          navigate(appLinks.geoReportDetail(geoResult.reportId))
        } else if (actionType.startsWith('PREPARE_')) {
          beginEditing()
        }
      }}
      onViewReport={(reportId) => navigate(appLinks.geoReportDetail(reportId))}
    />
  )
}

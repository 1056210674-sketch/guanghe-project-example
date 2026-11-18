import {
  EditOutlined,
  FileImageOutlined,
  PlaySquareOutlined,
  RadarChartOutlined,
  CloseOutlined,
} from '@ant-design/icons'
import { App as AntApp, Button, Drawer, Grid, Input, Modal, Space, Spin, Typography } from 'antd'
import { useQueryClient, type InfiniteData } from '@tanstack/react-query'
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useAgentCatalog, useAgentInstallations, useProjects } from '../../features/agent-platform/queries'
import { resolveCreativeStorage } from '../../api/agentPlatformApi'
import { TaskAgentControls } from '../../features/agent-platform/TaskAgentControls'
import { GeoReportReferencePicker } from './GeoReportReferencePicker'
import { ArticlePreparationContent } from '../articles/ArticlePreparationPage'
import { isApiError } from '../../api/apiError'
import { errorTextWithDiagnosticTrace } from '../../shared/tracePresentation'
import {
  confirmArticleBody,
  confirmArticleCreativePlan,
  confirmArticleTitle,
  getArticle,
  getArticleSubmissionSnapshot,
} from '../../api/articleApi'
import type { Article } from '../../api/articleApi'
import { getGeoReport } from '../../api/geoApi'
import type { GeoReport } from '../../api/geoApi'
import { geoArticleUnavailableReason } from '../../features/geo/geoArticleEligibility'
import {
  createBrowserTraceContext,
  type BrowserTraceContext,
} from '../../api/browserTrace'
import { createAssetDownloadUrl } from '../../api/assetApi'
import { getProduct } from '../../api/catalogApi'
import {
  abandonTaskDraft,
  confirmTaskDraft,
  createTaskDraft,
  getConversation,
  getPaidTask,
  getConversationMessageOperation,
  getTaskDraft,
  listPaidTasks,
} from '../../api/conversationApi'
import type { ConversationMessage, ConversationSnapshot, ConversationResourceSelectionRequest, SendConversationMessageRequest, PaidTask, TaskDraft } from '../../api/conversationApi'
import { listArticleMediaRecommendations } from '../../api/mediaApi'
import {
  RecommendedMediaSubmissionError,
  submitRecommendedMedia,
} from '../../features/articles/live/recommendedMediaSubmission'
import {
  readMediaTaskRecovery,
  storeMediaTaskRecovery,
} from '../../features/articles/live/mediaRecovery'
import { toArticleMediaRecommendationSet } from '../../features/articles/live/mediaAdapters'
import {
  toArticleTaskDraftRequest,
  toArticleWorkspaceRecord,
} from '../../features/articles/live/articleAdapters'
import type {
  ArticleMediaRecommendationSetView,
  ArticleBodyConfirmationInput,
  ArticleRecommendedSubmissionSelection,
  ArticleStage,
  ArticleTaskFormDraft,
  ArticleWorkflowStage,
} from '../../features/articles/types'
import {
  useAssetDetails,
  useAssetDetailsState,
  useAssetDownloadUrls,
  useUploadImageAsset,
} from '../../features/assets/live/assetQueries'
import { ImageAssetPickerDialog } from '../../features/assets/components/ImageAssetPickerDialog'
import { GeneratedImagePreviewDialog } from '../../features/chat/components/GeneratedImagePreviewDialog'
import {
  toConversationContextRequest,
  type ConversationContextSelectionOverride,
} from './chatContextSelection'
import {
  creativeGroundingErrorMessage,
  decideCreativeSource,
} from '../../features/chat/creativeGrounding'
import { ChatWorkspace } from '../../features/chat/components/ChatWorkspace'
import { REPORT_ARTICLE_PROMPT } from '../../features/chat/composerCapabilities'
import { hasAgentCapability, supportsPersonalCreativeAssets } from '../../features/agent-platform/capabilities'
import { findInstalledAgentDefinition, isAgentInstallationAvailable } from '../../features/agent-platform/installedAgent'
import { GeoMascot } from '../../features/chat/mascot/GeoMascot'
import { GeoMonitorAction } from './GeoMonitorAction'
import { ArticleFullAction } from './ArticleFullAction'
import { useFullArticleInvocation } from './useFullArticleInvocation'
import { messageArticlePreparation, taskArticlePreparationId, useChatArticlePreparations } from './useChatArticlePreparations'
import { usePeriodReportWorkspace } from './usePeriodReportWorkspace'
import { useGeoDiagnosticEntry } from './useGeoDiagnosticEntry'
import { PeriodReportForm } from '../../features/geo-period-reports/PeriodReportForm'
import { PeriodReportPreview } from '../../features/geo-period-reports/PeriodReportPreview'
import '../../styles/geo-period-reports.css'
import type { MonitorAdmission, MonitorExecutionIntent } from '../../api/monitorExecutionApi'
import type { ChatComposerAttachmentView } from '../../features/chat/creativeGrounding'
import type {
  ActiveArticleWorkflowView,
  ChatMessageView,
  ChatRecommendationView,
  TaskOutcomeActionType,
} from '../../features/chat/types'
import {
  embeddedTaskDraftId,
  generatedImageAssetReference,
  generatedImageAssetReferences,
  generatedVideoAssetReference,
  toBackgroundTaskNotification,
  toChatMessageView,
  toChatSessionView,
  toChatTaskStatusSummary,
  toPaidTaskConfirmation,
} from '../../features/chat/live/chatAdapters'
import {
  chatKeysFor,
  useConversation,
  useConversationMessages,
  useConversations,
  useCreateConversation,
  useDeleteConversation,
  usePointAccount,
  useSendConversationMessage,
  useStopConversationStream,
  useUpdateConversation,
} from '../../features/chat/live/chatQueries'
import {
  readChatRecoveryState,
  reserveConfirmationAttempt,
  storeChatRecoveryState,
  upsertRecoveryDraft,
  upsertRecoveryTask,
} from '../../features/chat/live/chatRecovery'
import type { ChatRecoveryState } from '../../features/chat/live/chatRecovery'
import { clearMessageAttempt, messageFingerprint, readMessageAttempt, storeMessageAttempt } from '../../features/chat/live/messageAttempt'
import {
  moveCurrentAttachmentIds,
  readCurrentAttachmentIds,
  storeCurrentAttachmentIds,
} from '../../features/chat/live/attachmentRecovery'
import type { ParsedConversationEvent } from '../../features/chat/live/conversationStream'
import {
  readConversationEventCursor,
} from '../../features/chat/live/conversationStream'
import type { ConversationEventCursor } from '../../features/chat/live/conversationStream'
import {
  applyConversationEventToTimelines,
  readExecutionTimelineRecovery,
  reconcileTaskExecutionTimelines,
  storeExecutionTimelineRecovery,
} from '../../features/chat/live/executionTimeline'
import type { ExecutionTimelineMap } from '../../features/chat/live/executionTimeline'
import { acceptReplyExecutionTimeline, reconcileReplyExecutionTimelines } from '../../features/chat/live/replyTimelineState'
import { placeExecutionTimelines } from '../../features/chat/live/messageTimelinePlacement'
import { appendReplyDelta, pruneReplyDeltas, readReplyDelta, withStreamedReplyContent } from '../../features/chat/live/streamedReply'
import type { StreamedReplies } from '../../features/chat/live/streamedReply'
import { useConversationEventStream } from '../../features/chat/live/useConversationEventStream'
import { openExternalHttps } from '../../features/shared/openExternalHttps'
import { UserShell } from '../../layouts/UserShell'
import { APP_PATHS, appLinks } from '../../routes/paths'
import { useAuthStore } from '../../stores/authStore'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import { GEO_REPORT_QUESTION, useGeoReportChatSource } from './useGeoReportChatSource'
import {
  EMPTY_CHAT_TASK_FOCUS_OPTIONS,
} from './pageDefaults'

const ACTIVE_PAID_TASK_STATUSES = new Set<PaidTask['status']>([
  'QUEUED',
  'RUNNING',
  'RETRY_WAIT',
])
const KnowledgeLibraryPanel = lazy(() => import('../knowledge/KnowledgeLibraryPage').then((module) => ({ default: module.KnowledgeLibraryPanel })))
const GeoOnboarding = lazy(() => import('./GeoOnboarding').then((module) => ({ default: module.GeoOnboarding })))
const GeoDiagnosticPagerContainer = lazy(() => import('./GeoDiagnosticPagerContainer').then((module) => ({ default: module.GeoDiagnosticPagerContainer })))

const BACKGROUND_TASK_NOTIFICATION_PREFIX = 'background-task-notification:'
const RECENT_TERMINAL_TASK_LIMIT = 20

function draftEditRestriction(draftId: string, activeDraftId?: string | null) {
  if (draftId === activeDraftId) return undefined
  return activeDraftId
    ? '这份不是当前修改中的方案，暂不能从此卡直接修改；仍可按本卡报价确认。'
    : '当前无法确定唯一的待修改方案，暂不能从此卡直接修改；请在对话中明确需求，也可以另做一份新方案。'
}

async function listConversationTasksForChat(
  conversationId: string,
): Promise<PaidTask[]> {
  const tasksById = new Map<string, PaidTask>()
  let page = 1

  while (true) {
    const result = await listPaidTasks({ conversationId, page, size: 100 })
    for (const task of result.items) tasksById.set(task.id, task)
    const terminalCount = [...tasksById.values()].filter(
      (task) => !ACTIVE_PAID_TASK_STATUSES.has(task.status),
    ).length
    if (!result.hasNext || terminalCount >= RECENT_TERMINAL_TASK_LIMIT) break
    page = result.page + 1
  }

  const tasks = [...tasksById.values()]
  return [
    ...tasks.filter((task) => ACTIVE_PAID_TASK_STATUSES.has(task.status)),
    ...tasks
      .filter((task) => !ACTIVE_PAID_TASK_STATUSES.has(task.status))
      .slice(0, RECENT_TERMINAL_TASK_LIMIT),
  ]
}

const CHAT_RECOMMENDATIONS: readonly ChatRecommendationView[] = [
  {
    id: 'monitor-performance',
    title: '制定品牌优化方案',
    description: '结合项目资料分析，先给建议，不自动启动监测',
    prompt: '结合这个项目的品牌资料和已有报告，帮我制定GEO优化方案。',
    icon: <RadarChartOutlined />,
  },
  {
    id: 'create-images',
    title: '生成图片',
    description: '描述画面，也可以上传参考图片',
    prompt: '帮我生成一张图片：',
    icon: <FileImageOutlined />,
  },
  {
    id: 'create-video',
    title: '生成视频',
    description: '描述内容，也可以选择参考图片',
    prompt: '帮我生成一段视频：',
    icon: <PlaySquareOutlined />,
  },
  {
    id: 'write-article',
    title: '写品牌文章',
    description: '直接生成完整稿件，满意后再选择投稿',
    prompt: '结合项目品牌资料，帮我写一篇面向顾客的完整文章。',
    icon: <EditOutlined />,
  },
]

const BARE_WORKFLOW_CONFIRMATIONS = new Set([
  '确认',
  '确认一下',
  '确认吧',
  '确认当前步骤',
  '确定',
  '确定一下',
  '确定吧',
  '继续',
  '继续当前文章',
  '开始',
  '开始吧',
  '执行',
  '执行吧',
])

function isBareWorkflowConfirmation(value: string): boolean {
  const normalized = value
    .replace(/[\s，。！？、,.!?；;：:“”"'‘’（）()【】]/g, '')
    .replace(/[\u005b\u005d]/g, '')
    .toLocaleLowerCase('zh-CN')
  return BARE_WORKFLOW_CONFIRMATIONS.has(normalized)
}

interface MessageResourceState {
  draft?: TaskDraft
  draftVerificationPending?: boolean
  draftVerificationError?: string
  task?: PaidTask
  article?: Article
  mediaRecommendations?: ArticleMediaRecommendationSetView
  mediaRecommendationError?: { message: string; conflict?: boolean; code?: string }
  geoReport?: GeoReport
  productName?: string
}

interface SendMessageOptions {
  executionIntent?: MonitorExecutionIntent
  targetConversationId?: string
  replyToMessageId?: string
  resourceSelection?: ConversationResourceSelectionRequest
  stoppedRunId?: string
  articleContext?: {
    spaceId: string
    productId: string | null
  }
}

type ArticleTaskParameters = Extract<
  TaskDraft['parameters'],
  {
    articleId: string
    mode: 'TITLE' | 'OUTLINE' | 'BODY' | 'REWRITE'
  }
>

const EMPTY_MESSAGE_RESOURCES: Record<string, MessageResourceState> = {}
const EMPTY_CONVERSATION_MESSAGES: readonly ConversationMessage[] = []
const EMPTY_STREAMED_TEXT: StreamedReplies = {}
const EMPTY_TASK_IDS: ReadonlySet<string> = new Set()
const GEO_ARTICLE_GOAL = REPORT_ARTICLE_PROMPT

function record(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}

function errorMessage(error: unknown) {
  if (isApiError(error)) {
    const message = creativeGroundingErrorMessage(error.code, error.message)
    return errorTextWithDiagnosticTrace(message, error.traceId)
  }
  if (error instanceof Error && error.message) return error.message
  return '操作失败，请稍后重试。'
}

function newRequestId() {
  return crypto.randomUUID()
}

function taskDraftRequestText(taskDraft: TaskDraft): string | undefined {
  return 'requestText' in taskDraft.parameters
    ? taskDraft.parameters.requestText
    : undefined
}

function articleTaskParameters(
  taskDraft?: TaskDraft,
): ArticleTaskParameters | undefined {
  if (
    taskDraft?.taskType !== 'ARTICLE' ||
    !('articleId' in taskDraft.parameters) ||
    !('mode' in taskDraft.parameters)
  ) {
    return undefined
  }
  return taskDraft.parameters as ArticleTaskParameters
}

function articleResultId(task?: PaidTask): string | undefined {
  return task?.taskType === 'ARTICLE' && 'articleId' in task.result
    ? task.result.articleId
    : undefined
}

function articleCanRecommendMedia(article: Article): boolean {
  return (
    article.workflowStage === 'READY_TO_SUBMIT' && Boolean(article.bodyText.trim())
  )
}

const ARTICLE_WORKFLOW_LABELS: Record<
  ArticleWorkflowStage,
  { stageLabel: string; nextActionLabel: string }
> = {
  TITLE_INPUT: { stageLabel: '准备标题', nextActionLabel: '继续' },
  TITLE_GENERATING: { stageLabel: '生成标题', nextActionLabel: '查看进度' },
  TITLE_AWAITING_CONFIRMATION: { stageLabel: '待选标题', nextActionLabel: '去选择' },
  PLAN_READY: { stageLabel: '准备创作方案', nextActionLabel: '继续' },
  PLAN_GENERATING: { stageLabel: '生成创作方案', nextActionLabel: '查看进度' },
  PLAN_AWAITING_CONFIRMATION: {
    stageLabel: '待确认创作方案',
    nextActionLabel: '去确认',
  },
  BODY_READY_TO_GENERATE: { stageLabel: '准备正文', nextActionLabel: '继续' },
  BODY_GENERATING: { stageLabel: '生成正文', nextActionLabel: '查看进度' },
  BODY_READY: { stageLabel: '待确认正文', nextActionLabel: '去确认' },
  EDITING: { stageLabel: '编辑正文', nextActionLabel: '继续编辑' },
  READY_TO_SUBMIT: { stageLabel: '待投稿', nextActionLabel: '选择媒体' },
}

function findActiveArticleWorkflow(
  messages: readonly ChatMessageView[],
): ActiveArticleWorkflowView | undefined {
  let active: ActiveArticleWorkflowView | undefined
  for (const message of messages) {
    const article = message.articleWorkflowAnchor
    if (article) {
      const labels = ARTICLE_WORKFLOW_LABELS[article.workflowStage]
      active = {
        articleId: article.articleId,
        title: article.title || '未命名文章',
        ...labels,
      }
    }
    const completedMediaSubmission =
      message.taskExecutionOutcomeGroup?.outcomes.some(
        (outcome) => outcome.statusTone === 'success' || outcome.statusTone === 'warning',
      ) &&
      message.taskExecutionOutcomeGroup.outcomes.some(
        (outcome) => outcome.taskType === '媒体投稿',
      )
    if (active && completedMediaSubmission) {
      active = undefined
    }
  }
  return active
}

function terminalTask(task?: PaidTask): boolean {
  return Boolean(
    task &&
      ['SUCCEEDED', 'PARTIAL', 'FAILED', 'SKIPPED', 'SYSTEM_TERMINATED'].includes(
        task.status,
      ),
  )
}

function backgroundTaskNotificationMessageId(taskId: string): string {
  return `${BACKGROUND_TASK_NOTIFICATION_PREFIX}${taskId}`
}

function isBackgroundTaskNotificationMessageId(messageId: string): boolean {
  return messageId.startsWith(BACKGROUND_TASK_NOTIFICATION_PREFIX)
}

function isSyntheticTaskSourceMessageId(messageId: string): boolean {
  return messageId.startsWith('task-event-')
}

function isActiveConfirmationMessageId(messageId: string): boolean {
  return messageId.startsWith('active-confirmation:')
}

function sourceMessageIdForTask(
  recovery: ChatRecoveryState,
  taskId: string,
): string | undefined {
  const durableSource = recovery.tasks.find(
    (link) =>
      link.resourceId === taskId &&
      !isBackgroundTaskNotificationMessageId(link.messageId) &&
      !isSyntheticTaskSourceMessageId(link.messageId),
  )?.messageId
  return durableSource ?? recovery.tasks.find(
    (link) =>
      link.resourceId === taskId &&
      !isBackgroundTaskNotificationMessageId(link.messageId),
  )?.messageId
}

function bindRecoveryTaskToSourceMessage(
  recovery: ChatRecoveryState,
  messageId: string,
  taskId: string,
): ChatRecoveryState {
  return upsertRecoveryTask(
    {
      ...recovery,
      tasks: recovery.tasks.filter(
        (link) =>
          link.resourceId !== taskId ||
          !isSyntheticTaskSourceMessageId(link.messageId),
      ),
    },
    messageId,
    taskId,
  )
}

function pendingDraftForDisplay(
  draft?: TaskDraft,
  task?: PaidTask,
): TaskDraft | undefined {
  if (!draft) return undefined
  if (draft.status !== 'WAITING_CONFIRMATION') {
    return draft.status === 'CONFIRMED' && task ? undefined : draft
  }
  if (!task) return draft
  const parameters = articleTaskParameters(draft)
  if (
    task.taskType === 'ARTICLE' &&
    parameters &&
    'articleId' in task.result &&
    task.result.articleId === parameters.articleId &&
    task.result.mode !== parameters.mode
  ) {
    return draft
  }
  return undefined
}

async function clearStaleMediaQuote(
  ownerId: string | undefined,
  article: Article,
): Promise<void> {
  if (!ownerId) return
  const recovery = readMediaTaskRecovery(ownerId)
  const draftId = recovery.draftAttempt?.draftId
  if (!draftId || recovery.confirmationAttempt?.taskId) return
  let draft: TaskDraft | undefined
  try {
    draft = await getTaskDraft(draftId)
    if (
      draft.taskType !== 'MEDIA' ||
      !('articleId' in draft.parameters) ||
      draft.parameters.articleId !== article.id ||
      !('articleSnapshotId' in draft.parameters)
    ) {
      return
    }
    const snapshot = await getArticleSubmissionSnapshot(
      article.id,
      draft.parameters.articleSnapshotId,
    )
    if (snapshot.articleVersion === article.version) return
    if (draft.status !== 'WAITING_CONFIRMATION') return
    await abandonTaskDraft(draft.id).catch(() => undefined)
    storeMediaTaskRecovery(ownerId, {})
  } catch (error) {
    if (
      isApiError(error) &&
      error.status === 404 &&
      draft?.status !== 'CONFIRMED'
    ) {
      storeMediaTaskRecovery(ownerId, {})
      return
    }
    throw error
  }
}

function targetWordCount(targetLength: ArticleTaskParameters['targetLength']) {
  return targetLength === 'SHORT' ? 800 : targetLength === 'LONG' ? 1600 : 1200
}

export function ChatPage() {
  const { modal } = AntApp.useApp()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const activeLocationKey = useRef<string | undefined>(location.key)
  useEffect(() => { activeLocationKey.current = location.key; return () => { activeLocationKey.current = undefined } }, [location.key])
  const ownerId = useAuthStore((state) => state.user?.id)
  const [searchParams, setSearchParams] = useSearchParams()
  const selectedSessionId = searchParams.get('conversation') ?? undefined
  const periodReportId = searchParams.get('periodReportId') ?? undefined
  const platformStart = !selectedSessionId && (location.pathname === APP_PATHS.userHome || searchParams.has('installation') || searchParams.has('project'))
  const installationId = platformStart ? searchParams.get('installation') ?? undefined : undefined
  const owningProjectId = platformStart ? searchParams.get('project') ?? undefined : undefined
  const agentCatalog = useAgentCatalog(platformStart)
  const agentInstallations = useAgentInstallations(platformStart)
  const projects = useProjects()
  const selectedInstallation = agentInstallations.data?.find((item) => item.id === installationId)
  const selectedDefinition = findInstalledAgentDefinition(selectedInstallation, agentCatalog.data)
  const selectedOwningProject = projects.data?.find((item) => item.id === owningProjectId)
  const launchLoading = platformStart && (agentCatalog.isLoading || agentInstallations.isLoading || Boolean(owningProjectId && projects.isLoading))
  const launchError = platformStart && (agentCatalog.isError || agentInstallations.isError || Boolean(owningProjectId && projects.isError)) ? '开始任务所需的数据暂时无法加载，请重试。' : undefined
  const launchGuidance = !platformStart ? undefined : launchLoading ? '正在加载我的智能体…'
    : launchError ?? (!installationId ? '先选择要使用的智能体，项目可以留空。'
      : !selectedInstallation ? '这个智能体已被移除或不可访问，请重新选择。'
        : !selectedInstallation.enabled ? '这个智能体已停用，请到“我的智能体”启用或选择其他智能体。'
          : !isAgentInstallationAvailable(selectedInstallation, agentCatalog.data) ? '这个智能体暂不可用于新任务，历史会话仍可查看。'
            : owningProjectId && (!selectedOwningProject || selectedOwningProject.status !== 'ACTIVE') ? '所属项目不可用或已归档，请选择其他项目，或不归入项目。' : undefined)
  const newConversationRequest = platformStart ? { installationId, ...(owningProjectId ? { projectId: owningProjectId } : {}) } : undefined
  const changeLaunchSelection = (key: 'installation' | 'project', value?: string) => {
    if ((key === 'installation' ? installationId : owningProjectId) === value) return
    if ((attachmentUploadsByConversation.new ?? []).some(({ status }) => status === 'UPLOADING')) {
      setComposerNotice('图片上传完成后再切换项目或智能体。')
      return
    }
    // A new chat has one project selector. Never carry the previous customer's
    // explicit report context or attachments into a different launch selection.
    setContextSelections((current) => { const next = { ...current }; delete next.new; return next })
    if (currentAttachmentIds.length) {
      setComposerNotice('已保留输入文字，请为当前项目重新选择图片。')
    }
    updateCurrentAttachmentIds('new', [])
    setAttachmentUploadsByConversation((current) => ({ ...current, new: [] }))
    setAttachmentSelectorOpen(false)
    setKnowledgePanel(undefined)
    geoReportSource.clear()
    setReportPickerScope(undefined)
    setSearchParams((current) => {
      const next = new URLSearchParams(current)
      if (value) next.set(key, value)
      else next.delete(key)
      next.delete('geoReportId')
      next.delete('articleGoal')
      // The previous customer's saved report must not stay open under a new project.
      if (key === 'project') next.delete('periodReportId')
      return next
    }, { replace: true })
  }
  const [sessionSearchValue, setSessionSearchValue] = useState('')
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [onboardingDraftScope, setOnboardingDraftScope] = useState<string>()
  const [contextSelections, setContextSelections] = useState<
    Record<string, ConversationContextSelectionOverride>
  >({})
  const [attachmentSelectorOpen, setAttachmentSelectorOpen] = useState(false)
  const [knowledgePanel, setKnowledgePanel] = useState<{ projectId?: string; add?: 'document' | 'excerpt' | 'file'; onboardingScope?: string }>()
  const [reportPickerScope, setReportPickerScope] = useState<string>()
  const [attachmentIdsByConversation, setAttachmentIdsByConversation] =
    useState<Record<string, string[]>>({})
  const [attachmentUploadsByConversation, setAttachmentUploadsByConversation] =
    useState<Record<string, ChatComposerAttachmentView[]>>({})
  const [resourcesByConversation, setResourcesByConversation] = useState<
    Record<string, Record<string, MessageResourceState>>
  >({})
  const resourcesByConversationRef = useRef(resourcesByConversation)
  const [admittedMonitorDrafts, setAdmittedMonitorDrafts] = useState<Record<string, Record<string, string>>>({})
  const admittedMonitorDraftsRef = useRef(admittedMonitorDrafts)
  const draftLoadVersionsRef = useRef(new Map<string, number>())
  useEffect(() => {
    resourcesByConversationRef.current = resourcesByConversation
  }, [resourcesByConversation])
  const [streamedTextByConversation, setStreamedTextByConversation] = useState<
    Record<string, StreamedReplies>
  >({})
  const latestStreamCursorRef = useRef<Record<string, ConversationEventCursor>>({})
  const [executionTimelinesByConversation, setExecutionTimelinesByConversation] =
    useState<Record<string, ExecutionTimelineMap>>({})
  const [activeRuns, setActiveRuns] = useState<Record<string, boolean>>({})
  const [streamBootstrapComplete, setStreamBootstrapComplete] = useState<
    Record<string, boolean>
  >({})
  const [streamResumeCursors, setStreamResumeCursors] = useState<
    Record<string, ConversationEventCursor | null>
  >({})
  const [streamTraces, setStreamTraces] = useState<
    Record<string, BrowserTraceContext>
  >({})
  const [actionError, setActionError] = useState<string>()
  const [imagePreview, setImagePreview] = useState<{ assetId: string; ownerId?: string; conversationId?: string; url?: string; loading: boolean; error: boolean }>()
  const imagePreviewRequest = useRef(0)
  const activeImagePreview = imagePreview?.ownerId === ownerId && imagePreview?.conversationId === selectedSessionId ? imagePreview : undefined
  const [sendError, setSendError] = useState<string>()
  const [composerNotice, setComposerNotice] = useState<string>()
  const [pendingMessageInputs, setPendingMessageInputs] = useState<Record<string, {
    content: string
    attachmentIds: string[]
    context?: ConversationContextSelectionOverride
  }>>({})
  const [renamingSessionId, setRenamingSessionId] = useState<string>()
  const [renameValue, setRenameValue] = useState('')
  const [deletingSessionId, setDeletingSessionId] = useState<string>()
  const [confirmingDraftId, setConfirmingDraftId] = useState<string>()
  const [selectedTaskId, setSelectedTaskId] = useState<string>()
  const [liveNotificationTaskIdsByConversation, setLiveNotificationTaskIdsByConversation] =
    useState<Record<string, ReadonlySet<string>>>({})
  const [recommendedSubmittingArticleId, setRecommendedSubmittingArticleId] =
    useState<string>()
  const sendLock = useRef(false)
  const autoCreatedConversationRef = useRef<string | undefined>(undefined)
  const confirmLock = useRef<string | undefined>(undefined)
  const articleDraftLock = useRef<string | undefined>(undefined)
  const [preparingGeoArticleMessageId, setPreparingGeoArticleMessageId] = useState<string>()
  const [geoArticleDirections, setGeoArticleDirections] = useState<Record<string, Record<string, string>>>({})
  const geoActionEpoch = useRef(0)
  const recommendedSubmitLock = useRef<string | undefined>(undefined)
  const activeDraftLoadsInFlightRef = useRef<Set<string>>(new Set())
  const activeDraftRetryAttemptsRef = useRef<Map<string, number>>(new Map())
  const activeDraftRetryTimersRef = useRef<Map<string, number>>(new Map())
  const [activeDraftRetryVersion, setActiveDraftRetryVersion] = useState(0)
  const selectedSessionRef = useRef(selectedSessionId)
  const executionTimelinesRef = useRef<Record<string, ExecutionTimelineMap>>({})
  const streamShouldReconnectRef = useRef<Record<string, boolean>>({})
  const activeTaskLinksRef = useRef<
    Record<string, Record<string, string>>
  >({})
  const recoveryRef = useRef<ChatRecoveryState>({
    drafts: [],
    tasks: [],
    confirmationAttempts: [],
  })
  const previousCatalogOwnerIdRef = useRef(ownerId)

  useEffect(() => {
    if (previousCatalogOwnerIdRef.current === ownerId) return

    previousCatalogOwnerIdRef.current = ownerId
    // A selector can stay mounted while the auth store transitions through
    // logout/login.  Do not carry an override (or an open modal) from the
    // previous session into the next account.
    setContextSelections({})
    setDrafts({})
    setGeoArticleDirections({})
    setAttachmentIdsByConversation({})
    setAttachmentUploadsByConversation({})
    setStreamedTextByConversation({})
    latestStreamCursorRef.current = {}
    setResourcesByConversation({})
    setAdmittedMonitorDrafts({})
    admittedMonitorDraftsRef.current = {}
    setActiveRuns({})
    setComposerNotice(undefined)
    setPendingMessageInputs({})
    setSendError(undefined)
    setActionError(undefined)
    imagePreviewRequest.current += 1
    setImagePreview(undefined)
    setSearchParams((current) => { const next = new URLSearchParams(current); next.delete('geoReportId'); return next }, { replace: true })
  }, [ownerId, setSearchParams])

  useEffect(() => {
    selectedSessionRef.current = selectedSessionId
    imagePreviewRequest.current += 1
    setImagePreview(undefined)
    if (!selectedSessionId) {
      autoCreatedConversationRef.current = undefined
    }
  }, [selectedSessionId])

  const conversationsQuery = useConversations()
  const conversationQuery = useConversation(selectedSessionId)
  const messagesQuery = useConversationMessages(selectedSessionId)
  const pointAccountQuery = usePointAccount(Boolean(selectedSessionId))
  const createConversationMutation = useCreateConversation()
  const updateConversationMutation = useUpdateConversation()
  const deleteConversationMutation = useDeleteConversation()
  const sendMutation = useSendConversationMessage()
  const uploadImageMutation = useUploadImageAsset()
  const stopMutation = useStopConversationStream()

  const conversation = conversationQuery.data
  const capabilities = conversation?.agentIdentity?.capabilities ?? selectedDefinition?.capabilities
  const supportsCapability = (capability: string) => hasAgentCapability(capabilities, capability)
  const imageCreationAvailable = supportsCapability('image.generate')
  const videoCreationAvailable = supportsCapability('video.generate')
  const creativeOutputLabel = imageCreationAvailable && videoCreationAvailable ? '图片和视频' : imageCreationAvailable ? '图片' : '视频'
  const creativePromptLabel = imageCreationAvailable && videoCreationAvailable ? '画面或视频' : imageCreationAvailable ? '画面' : '视频'
  const creativePlatformWelcome = platformStart && !launchGuidance && (imageCreationAvailable || videoCreationAvailable)
    ? {
        title: `你好，我是${selectedInstallation?.name ?? '创作智能体'}。`,
        description: `我可以帮你生成${creativeOutputLabel}。直接描述想要的${creativePromptLabel}，也可以上传参考图片后告诉我保留什么、修改什么。`,
      }
    : undefined
  const draftKey = selectedSessionId ?? 'new'
  const onboardingScope = `${ownerId ?? ''}:${installationId ?? ''}:${owningProjectId ?? ''}`
  const knowledgePanelVisible = Boolean(knowledgePanel && (!knowledgePanel.onboardingScope || knowledgePanel.onboardingScope === onboardingScope && !selectedSessionId))
  // Text explicitly assembled for one customer's onboarding must not follow a
  // launch/project switch. Ordinary free-text drafts retain their existing behaviour.
  if (onboardingDraftScope && onboardingDraftScope !== onboardingScope) {
    setOnboardingDraftScope(undefined)
    setDrafts((current) => ({ ...current, new: '' }))
  }
  const fullArticleInvocation = useFullArticleInvocation({ ownerId, conversationId: selectedSessionId,
    isScopeCurrent: (id) => selectedSessionRef.current === id || (!selectedSessionRef.current && autoCreatedConversationRef.current === id),
    onResult: (id) => navigate(appLinks.articlePreparation(id)),
  })
  const draft = drafts[draftKey] ?? (!selectedSessionId && searchParams.get('articleGoal') === 'true' ? GEO_ARTICLE_GOAL : '')
  const prefillOnboarding = (value: string) => {
    if (sendMutation.isPending || createConversationMutation.isPending || selectedSessionId || launchGuidance) return false
    const combined = draft.trim() ? `${draft.trim()}\n\n${value}` : value
    if (combined.length > 16000) {
      setComposerNotice('资料与现有输入合计过长，已保留原输入。请缩短内容，或将详细资料上传到品牌知识库。')
      return false
    }
    setDrafts((current) => ({ ...current, new: combined }))
    setOnboardingDraftScope(onboardingScope)
    setComposerNotice('资料已放入输入框，检查后点击发送；尚未提交。切换项目或智能体会清除这份未发送的资料。')
    return true
  }
  const currentAttachmentIds = useMemo(() =>
    attachmentIdsByConversation[draftKey] ??
    (ownerId ? readCurrentAttachmentIds(ownerId, draftKey) : []),
  [attachmentIdsByConversation, draftKey, ownerId])
  const contextKey = selectedSessionId ?? 'new'
  const reportProjectId = conversation?.agentIdentity?.projectId ?? owningProjectId
  const reportPickerKey = `${ownerId ?? ''}:${contextKey}:${reportProjectId ?? ''}`
  if (reportPickerScope && reportPickerScope !== reportPickerKey) setReportPickerScope(undefined)
  const contextSelection = contextSelections[contextKey]
  const selectedBusinessSpaceId = contextSelection
    ? contextSelection.businessSpaceId
    : conversation?.selectedSpaceId ?? conversation?.agentIdentity?.projectId ?? owningProjectId
  const selectedProductId = contextSelection
    ? contextSelection.productId
    : conversation?.selectedProductId ?? undefined
  const personalCreativeAssetsAllowed = supportsPersonalCreativeAssets(capabilities)
  const creativeInstallationId = conversation?.agentIdentity?.installationId ?? installationId
  const creativeScopeKey = `${ownerId ?? ''}:${selectedSessionId ?? 'new'}:${creativeInstallationId ?? ''}:${owningProjectId ?? ''}`
  const [personalCreativeStorage, setPersonalCreativeStorage] = useState<{ scope: string; spaceId: string }>()
  const attachmentSpaceId = selectedBusinessSpaceId ?? (personalCreativeAssetsAllowed ? owningProjectId
    ?? (personalCreativeStorage?.scope === creativeScopeKey ? personalCreativeStorage.spaceId : undefined) : undefined)
  const resolveAttachmentSpace = async () => {
    if (attachmentSpaceId) return attachmentSpaceId
    if (!personalCreativeAssetsAllowed || !creativeInstallationId) throw new Error('请先选择项目，再上传本次要参考的图片。')
    const session = captureAuthSession()
    const requestLocationKey = location.key
    const result = await resolveCreativeStorage(creativeInstallationId, session)
    if (!isCurrentAuthSession(session) || activeLocationKey.current !== requestLocationKey) throw new Error('对话已切换，请在当前对话重新选择图片。')
    setPersonalCreativeStorage({ scope: creativeScopeKey, spaceId: result.spaceId })
    return result.spaceId
  }
  const chooseAttachmentAssets = async () => {
    const session = captureAuthSession()
    const requestLocationKey = location.key
    const current = () => isCurrentAuthSession(session) && activeLocationKey.current === requestLocationKey
    try {
      await resolveAttachmentSpace()
      if (current()) setAttachmentSelectorOpen(true)
    } catch (error) {
      if (current()) setSendError(errorMessage(error))
    }
  }
  useEffect(() => {
    geoActionEpoch.current += 1
    return () => { geoActionEpoch.current += 1 }
  }, [ownerId, selectedSessionId, selectedBusinessSpaceId, selectedProductId])
  const geoReportSource = useGeoReportChatSource({ ownerId, conversationId: selectedSessionId, projectId: reportProjectId,
    requestedReportId: searchParams.get('geoReportId'), onLoaded: (source, origin) => {
      setContextSelections((current) => ({ ...current, [contextKey]: { businessSpaceId: source.spaceId, productId: source.productId } }))
      if (origin === 'link') setDrafts((current) => current[contextKey]?.trim() ? current : { ...current, [contextKey]: !selectedSessionId && searchParams.get('articleGoal') === 'true' ? GEO_ARTICLE_GOAL : GEO_REPORT_QUESTION })
    } })
  const clearReportSource = geoReportSource.clear
  const clearGeoReportSource = useCallback(() => {
    clearReportSource()
    if (searchParams.has('geoReportId')) setSearchParams((current) => {
      const next = new URLSearchParams(current); next.delete('geoReportId'); return next
    }, { replace: true })
  }, [clearReportSource, searchParams, setSearchParams])
  const validGeoReportSource = geoReportSource.source?.spaceId === selectedBusinessSpaceId
    && geoReportSource.source?.productId === selectedProductId ? geoReportSource.source : undefined
  const messageResources = selectedSessionId
    ? resourcesByConversation[selectedSessionId] ?? EMPTY_MESSAGE_RESOURCES
    : EMPTY_MESSAGE_RESOURCES
  const conversationTasks = useMemo(() => [...new Map(Object.values(messageResources)
    .flatMap(({ task }) => task ? [[task.id, task] as const] : [])).values()], [messageResources])
  const articlePreparationViews = useChatArticlePreparations(ownerId, selectedSessionId,
    messagesQuery.data ?? EMPTY_CONVERSATION_MESSAGES, conversationTasks)
  const screens = Grid.useBreakpoint()
  const articleCanvasScope = `${ownerId ?? ''}:${selectedSessionId ?? ''}`
  const latestPreparationId = Object.keys(articlePreparationViews).sort((a, b) => BigInt(a) > BigInt(b) ? -1 : 1)[0]
  const [articleCanvasSelection, setArticleCanvasSelection] = useState<{ scope: string; seen?: string; id?: string }>()
  const articleCanvasId = articleCanvasSelection?.scope === articleCanvasScope && articleCanvasSelection.seen === latestPreparationId
    ? articleCanvasSelection.id : latestPreparationId
  const openArticleCanvas = (id?: string) => {
    if (id && periodReportId) setSearchParams((current) => { const next = new URLSearchParams(current); next.delete('periodReportId'); return next }, { replace: true })
    setArticleCanvasSelection({ scope: articleCanvasScope, seen: latestPreparationId, id })
  }
  const periodReports = usePeriodReportWorkspace({ ownerId, scopeKey: `${articleCanvasScope}:${owningProjectId ?? ''}`, reportId: periodReportId,
    defaultProjectId: conversation?.agentIdentity?.projectId ?? owningProjectId,
    onSelectReport: (id) => setSearchParams((current) => { const next = new URLSearchParams(current); if (id) next.set('periodReportId', id); else next.delete('periodReportId'); return next }, { replace: true }) })
  const recommendedSubmissionBlockedArticleIds = useMemo(
    () =>
      Object.entries(messageResources).flatMap(([messageId, resource]) => {
        if (resource.task?.taskType !== 'MEDIA' || terminalTask(resource.task)) {
          return []
        }
        const marker = ':media:'
        const markerIndex = messageId.lastIndexOf(marker)
        return markerIndex >= 0 ? [messageId.slice(markerIndex + marker.length)] : []
      }),
    [messageResources],
  )
  const streamedText = selectedSessionId
    ? streamedTextByConversation[selectedSessionId] ?? EMPTY_STREAMED_TEXT
    : EMPTY_STREAMED_TEXT
  const executionTimelines = useMemo(
    () => selectedSessionId
      ? executionTimelinesByConversation[selectedSessionId] ?? {}
      : {},
    [executionTimelinesByConversation, selectedSessionId],
  )
  const activeRun = selectedSessionId
    ? activeRuns[selectedSessionId] ?? false
    : false
  const generatedAssetIds = useMemo(
    () => [...new Set(Object.values(messageResources).flatMap(({ task }) => {
      if (!task) return []
      const video = generatedVideoAssetReference(task)
      return [...generatedImageAssetReferences(task).map(({ assetId }) => assetId), ...(video ? [video.assetId] : [])]
    }))],
    [messageResources],
  )
  const draftReferenceAssetIds = useMemo(
    () => [
      ...new Set(
        Object.values(messageResources).flatMap(
          ({ draft: taskDraft }) => taskDraft?.referenceAssetIds ?? [],
        ),
      ),
    ],
    [messageResources],
  )
  const generatedAssetDetails = useAssetDetails(generatedAssetIds)
  const generatedAssetDownloads = useAssetDownloadUrls(generatedAssetIds)
  const draftReferenceAssetDetails = useAssetDetails(draftReferenceAssetIds)
  const draftReferenceAssetDownloads =
    useAssetDownloadUrls(draftReferenceAssetIds)
  const currentAttachmentState = useAssetDetailsState(currentAttachmentIds)
  const currentAttachmentDetails = currentAttachmentState.data
  const currentAttachmentDownloads = useAssetDownloadUrls(currentAttachmentIds)
  const currentAttachments: ChatComposerAttachmentView[] = [
    ...(attachmentUploadsByConversation[draftKey] ?? []),
    ...currentAttachmentIds.map((assetId, index) => {
      const asset = currentAttachmentDetails[assetId]
      const status: ChatComposerAttachmentView['status'] = !asset
        ? currentAttachmentState.error ? 'FAILED' : 'PROCESSING'
        : asset.status === 'READY'
          ? 'READY'
          : ['UPLOADING', 'SCANNING', 'SAFETY_REVIEW'].includes(asset.status)
            ? 'PROCESSING'
            : 'FAILED'
      return {
        assetId,
        name: asset?.name ?? `本次图片 ${index + 1}`,
        status,
        thumbnailUrl: currentAttachmentDownloads[assetId]?.url,
        errorMessage:
          status === 'FAILED' ? '这张图片当前不可用，请删除后重新选择。' : undefined,
      }
    }),
  ]

  const selectedTimelineRecovery = useMemo(
    () => readExecutionTimelineRecovery(ownerId, selectedSessionId),
    [ownerId, selectedSessionId],
  )
  const historicalTaskReplayCutoffRef = useRef<Record<string, number>>({})
  // Resume only from a server snapshot containing the text at this watermark.
  // A browser cursor alone cannot prove that partial text survived a reload.
  const initialStreamCursor = useMemo(() => messagesQuery.snapshot ? {
    lastEventId: String(messagesQuery.snapshot.highWatermark),
    lastSequence: messagesQuery.snapshot.highWatermark,
  } : null, [messagesQuery.snapshot])

  useEffect(() => {
    if (!selectedSessionId) return
    if (initialStreamCursor === null) {
      historicalTaskReplayCutoffRef.current[selectedSessionId] ??= Date.now()
      return
    }
    delete historicalTaskReplayCutoffRef.current[selectedSessionId]
  }, [initialStreamCursor, selectedSessionId])

  useEffect(() => {
    if (!selectedSessionId || !initialStreamCursor) return
    setStreamedTextByConversation((current) => ({
      ...current, [selectedSessionId]: pruneReplyDeltas(current[selectedSessionId] ?? {}, initialStreamCursor.lastSequence),
    }))
    // A slower snapshot must not erase newer deltas, resurrect replaced text,
    // stop a live reply, or restart the stream from an older watermark.
    if ((latestStreamCursorRef.current[selectedSessionId]?.lastSequence ?? 0) > initialStreamCursor.lastSequence) return
    const restored = reconcileReplyExecutionTimelines(executionTimelinesRef.current[selectedSessionId] ??
      selectedTimelineRecovery?.timelines ?? {}, messagesQuery.snapshot?.messages ?? [])
    const serverActiveRun = messagesQuery.snapshot?.activeRun
    const reconciled = Object.fromEntries(Object.entries(restored).filter(([, timeline]) =>
      timeline.taskId || timeline.status !== 'RUNNING' || timeline.runId === serverActiveRun?.runId,
    ))
    executionTimelinesRef.current = {
      ...executionTimelinesRef.current,
      [selectedSessionId]: reconciled,
    }
    setExecutionTimelinesByConversation((current) => ({
      ...current,
      [selectedSessionId]: reconciled,
    }))
    setActiveRuns((current) => ({ ...current, [selectedSessionId]: Boolean(serverActiveRun) }))
    setStreamResumeCursors((current) => ({
      ...current,
      [selectedSessionId]: initialStreamCursor,
    }))
    setStreamBootstrapComplete((current) => ({
      ...current,
      [selectedSessionId]: initialStreamCursor !== null,
    }))
  }, [initialStreamCursor, selectedSessionId, selectedTimelineRecovery, messagesQuery.snapshot])

  useEffect(() => {
    if (!selectedSessionId) return
    const current = executionTimelinesRef.current[selectedSessionId] ?? {}
    const reconciled = reconcileTaskExecutionTimelines(current,
      Object.values(messageResources).flatMap(({ task }) => task ? [task] : []))
    if (reconciled === current) return
    executionTimelinesRef.current = { ...executionTimelinesRef.current, [selectedSessionId]: reconciled }
    setExecutionTimelinesByConversation((previous) => ({ ...previous, [selectedSessionId]: reconciled }))
    const cursor = latestStreamCursorRef.current[selectedSessionId]
      ?? initialStreamCursor ?? selectedTimelineRecovery?.cursor
    if (cursor) storeExecutionTimelineRecovery(ownerId, selectedSessionId, cursor, reconciled)
  }, [executionTimelines, initialStreamCursor, messageResources, ownerId, selectedSessionId, selectedTimelineRecovery])

  const loadDraftResource = useCallback(
    async (conversationId: string, messageId: string, draftId: string) => {
      const session = captureAuthSession()
      const loadKey = `${conversationId}:${messageId}:${draftId}`
      const version = (draftLoadVersionsRef.current.get(loadKey) ?? 0) + 1
      draftLoadVersionsRef.current.set(loadKey, version)
      const isCurrent = () => isCurrentAuthSession(session)
        && selectedSessionRef.current === conversationId
        && draftLoadVersionsRef.current.get(loadKey) === version
      const markVerification = (pending: boolean, error?: string) => {
        if (!isCurrent()) return
        setResourcesByConversation((current) => {
          const resource = current[conversationId]?.[messageId]
          if (!resource?.draft || resource.draft.id !== draftId) return current
          return { ...current, [conversationId]: { ...current[conversationId],
            [messageId]: { ...resource, draftVerificationPending: pending, draftVerificationError: error },
          } }
        })
      }
      markVerification(true)
      let taskDraft: TaskDraft
      try {
        taskDraft = await getTaskDraft(draftId)
      } catch (error) {
        markVerification(false, '暂时无法核对方案状态，不能确认。请重新打开对话后重试。')
        throw error
      }
      const previousResource = resourcesByConversationRef.current[conversationId]?.[messageId]
      let productName: string | undefined
      let article: Article | undefined
      if (previousResource?.draft?.productId === taskDraft.productId) {
        productName = previousResource?.productName
      }
      if (taskDraft.productId && !productName) {
        try {
          productName = (await getProduct(taskDraft.productId)).name
        } catch {
          // The task draft remains usable when its optional product display data
          // is no longer available to the current user.
        }
      }
      const parameters = articleTaskParameters(taskDraft)
      if (parameters) {
        try {
          article = await getArticle(parameters.articleId)
        } catch {
          // Confirmation stays usable when optional article display data is
          // temporarily unavailable.
        }
      }
      if (!isCurrent()) return
      setResourcesByConversation((current) => ({
        ...current,
        [conversationId]: (() => {
          const conversationResources = { ...current[conversationId] }
          const durableEntry = Object.entries(conversationResources).find(
            ([resourceMessageId, resource]) =>
              !isActiveConfirmationMessageId(resourceMessageId) &&
              resource.draft?.id === draftId,
          )
          if (isActiveConfirmationMessageId(messageId) && durableEntry) {
            return conversationResources
          }
          if (!isActiveConfirmationMessageId(messageId)) {
            for (const [resourceMessageId, resource] of Object.entries(
              conversationResources,
            )) {
              if (
                isActiveConfirmationMessageId(resourceMessageId) &&
                resource.draft?.id === draftId
              ) {
                delete conversationResources[resourceMessageId]
              }
            }
          }
          conversationResources[messageId] = {
            ...conversationResources[messageId],
            // A slow older GET must not reactivate a terminal draft.
            draft: conversationResources[messageId]?.draft?.status !== undefined
              && conversationResources[messageId].draft!.status !== 'WAITING_CONFIRMATION'
              && taskDraft.status === 'WAITING_CONFIRMATION'
              ? conversationResources[messageId].draft : taskDraft,
            draftVerificationPending: false,
            draftVerificationError: undefined,
            article: article ?? conversationResources[messageId]?.article,
            productName,
          }
          return conversationResources
        })(),
      }))
      return taskDraft
    },
    [],
  )

  const refreshPendingDraftResources = useCallback(async (conversationId: string, draftIds?: readonly string[]) => {
    // Terminal SSE events and focus are reconciliation points, never every progress event.
    // Bound the visible/recovered set; every explicit confirm also verifies its exact ID.
    const links = Object.entries(resourcesByConversationRef.current[conversationId] ?? {})
      .filter(([, resource]) => resource.draft?.status === 'WAITING_CONFIRMATION'
        && (!draftIds || draftIds.includes(resource.draft.id)))
      .sort(([, left], [, right]) => Date.parse(right.draft!.createdAt) - Date.parse(left.draft!.createdAt))
      .slice(0, 50)
    await Promise.allSettled(links.map(([messageId, resource]) =>
      loadDraftResource(conversationId, messageId, resource.draft!.id)))
  }, [loadDraftResource])

  const loadTaskResource = useCallback(
    async (
      conversationId: string,
      messageId: string,
      taskId: string,
      knownTask?: PaidTask,
    ) => {
      const session = captureAuthSession()
      const task = knownTask ?? await getPaidTask(taskId)
      if (!isCurrentAuthSession(session) || selectedSessionRef.current !== conversationId) return
      const activeLinks = {
        ...activeTaskLinksRef.current[conversationId],
      }
      if (ACTIVE_PAID_TASK_STATUSES.has(task.status)) {
        activeLinks[taskId] = messageId
      } else {
        delete activeLinks[taskId]
      }
      activeTaskLinksRef.current = {
        ...activeTaskLinksRef.current,
        [conversationId]: activeLinks,
      }
      let productName: string | undefined
      let article: Article | undefined
      let geoReport: GeoReport | undefined
      let mediaRecommendations: ArticleMediaRecommendationSetView | undefined
      let mediaRecommendationError: MessageResourceState['mediaRecommendationError']
      if (task.productId) {
        try {
          productName = (await getProduct(task.productId)).name
        } catch {
          // Task status and results do not depend on optional product display data.
        }
      }
      const taskArticleId = articleResultId(task)
      if (taskArticleId) {
        try {
          article = await getArticle(taskArticleId)
          await clearStaleMediaQuote(ownerId, article)
        } catch {
          // Keep the task visible when its optional article detail cannot be loaded.
        }
      }
      if (task.taskType === 'GEO' && 'reportId' in task.result) {
        try {
          geoReport = await getGeoReport(task.result.reportId)
        } catch {
          // The task result stays visible even if the optional diagnosis detail
          // cannot be loaded. Report ownership is still enforced by Java.
        }
      }
      if (article && articleCanRecommendMedia(article)) {
        try {
          mediaRecommendations = toArticleMediaRecommendationSet(
            await listArticleMediaRecommendations(article.id, 5),
          )
        } catch (error) {
          mediaRecommendationError = {
            message: errorMessage(error),
            conflict: isApiError(error) && error.status === 409,
            code: isApiError(error) ? error.code : undefined,
          }
        }
      }
      if (!isCurrentAuthSession(session) || selectedSessionRef.current !== conversationId) return
      setResourcesByConversation((current) => ({
        ...current,
        [conversationId]: (() => {
          const conversationResources = { ...current[conversationId] }
          if (
            !isSyntheticTaskSourceMessageId(messageId) &&
            !isBackgroundTaskNotificationMessageId(messageId)
          ) {
            for (const [resourceMessageId, resource] of Object.entries(
              conversationResources,
            )) {
              if (
                isSyntheticTaskSourceMessageId(resourceMessageId) &&
                resource.task?.id === taskId
              ) {
                delete conversationResources[resourceMessageId]
              }
            }
          }
          conversationResources[messageId] = {
            ...conversationResources[messageId],
            task,
            article: article ?? conversationResources[messageId]?.article,
            geoReport: geoReport ?? conversationResources[messageId]?.geoReport,
            mediaRecommendations,
            mediaRecommendationError,
            productName,
          }
          return conversationResources
        })(),
      }))
    },
    [ownerId],
  )

  const handleMonitorAdmission = useCallback((conversationId: string, admission: MonitorAdmission) => {
    if (selectedSessionRef.current !== conversationId || admission.status !== 'ADMITTED'
      || !admission.draftId || !admission.taskId
      || !['EXECUTION_PREAUTH', 'USER_CONFIRM'].includes(admission.executionSource ?? '')) return
    // Java's admission truth outranks a stale draft GET. Do not manufacture a
    // completed task: reconcile the actual task through the existing pipeline.
    const { draftId, taskId } = admission
    if (admittedMonitorDraftsRef.current[conversationId]?.[draftId] !== taskId) {
      const next = { ...admittedMonitorDraftsRef.current, [conversationId]: { ...admittedMonitorDraftsRef.current[conversationId], [draftId]: taskId } }
      admittedMonitorDraftsRef.current = next
      setAdmittedMonitorDrafts(next)
    }
    const resources = resourcesByConversationRef.current[conversationId] ?? {}
    const sourceId = Object.entries(resources).find(([, resource]) => resource.draft?.id === draftId)?.[0] ?? `task-event-${taskId}`
    if (resources[sourceId]?.task?.id === taskId) return
    recoveryRef.current = bindRecoveryTaskToSourceMessage(recoveryRef.current, sourceId, taskId)
    storeChatRecoveryState(conversationId, recoveryRef.current)
    void loadTaskResource(conversationId, sourceId, taskId).catch(() => {
      // Admission remains authoritative; its task link and explicit refresh
      // remain available even when the task detail temporarily cannot load.
    })
  }, [loadTaskResource])

  const reconcileActiveTaskResources = useCallback(
    async (conversationId: string) => {
      const links = { ...activeTaskLinksRef.current[conversationId] }
      // Old caches can contain a task stage without a separate recovery link.
      // Reconcile those exact IDs too when the stream ends or focus returns.
      for (const timeline of Object.values(executionTimelinesRef.current[conversationId] ?? {})) {
        if (timeline.taskId && ['RUNNING', 'QUEUED'].includes(timeline.status)) {
          links[timeline.taskId] ??= timeline.messageId
        }
      }
      const activeLinks = Object.entries(links)
      if (activeLinks.length === 0) return
      await Promise.allSettled(
        activeLinks.map(([taskId, messageId]) =>
          loadTaskResource(conversationId, messageId, taskId),
        ),
      )
      await pointAccountQuery.refetch()
    },
    [loadTaskResource, pointAccountQuery],
  )

  useEffect(() => {
    if (!selectedSessionId) return
    const reconcile = () => {
      if (document.visibilityState === 'visible') {
        void reconcileActiveTaskResources(selectedSessionId)
        void refreshPendingDraftResources(selectedSessionId)
      }
    }
    window.addEventListener('focus', reconcile)
    document.addEventListener('visibilitychange', reconcile)
    return () => {
      window.removeEventListener('focus', reconcile)
      document.removeEventListener('visibilitychange', reconcile)
    }
  }, [reconcileActiveTaskResources, refreshPendingDraftResources, selectedSessionId])

  useEffect(() => {
    if (!selectedSessionId) {
      recoveryRef.current = {
        drafts: [],
        tasks: [],
        confirmationAttempts: [],
      }
      return
    }
    const recovery = readChatRecoveryState(selectedSessionId)
    recoveryRef.current = recovery
    for (const link of recovery.drafts) {
      void loadDraftResource(
        selectedSessionId,
        link.messageId,
        link.resourceId,
      ).catch(() => {
        // Recovery storage is only a browser hint. Persisted messages and the
        // server-side active confirmation are authoritative and retry below.
      })
    }
    for (const link of recovery.tasks) {
      void loadTaskResource(
        selectedSessionId,
        link.messageId,
        link.resourceId,
      ).catch((error) => setActionError(errorMessage(error)))
    }
    for (const timeline of Object.values(selectedTimelineRecovery?.timelines ?? {})) {
      if (!timeline.taskId || !['RUNNING', 'QUEUED'].includes(timeline.status)
        || recovery.tasks.some((link) => link.resourceId === timeline.taskId)) continue
      void loadTaskResource(selectedSessionId, timeline.messageId, timeline.taskId)
        .catch((error) => setActionError(errorMessage(error)))
    }
  }, [loadDraftResource, loadTaskResource, selectedSessionId, selectedTimelineRecovery])

  useEffect(() => {
    if (!selectedSessionId) return
    let cancelled = false

    void listConversationTasksForChat(selectedSessionId)
      .then(async (tasks) => {
        if (cancelled || selectedSessionRef.current !== selectedSessionId) return
        let nextRecovery = recoveryRef.current
        const bindings = tasks.map((task) => {
          const sourceMessageId =
            sourceMessageIdForTask(nextRecovery, task.id) ??
            `task-event-${task.id}`
          nextRecovery = upsertRecoveryTask(
            nextRecovery,
            sourceMessageId,
            task.id,
          )
          const notificationMessageId = terminalTask(task)
            ? backgroundTaskNotificationMessageId(task.id)
            : undefined
          if (notificationMessageId) {
            nextRecovery = upsertRecoveryTask(
              nextRecovery,
              notificationMessageId,
              task.id,
            )
          }
          return { task, sourceMessageId, notificationMessageId }
        })

        recoveryRef.current = nextRecovery
        storeChatRecoveryState(selectedSessionId, nextRecovery)
        activeTaskLinksRef.current = {
          ...activeTaskLinksRef.current,
          [selectedSessionId]: Object.fromEntries(
            bindings.flatMap(({ task, sourceMessageId }) =>
              ACTIVE_PAID_TASK_STATUSES.has(task.status)
                ? [[task.id, sourceMessageId]]
                : [],
            ),
          ),
        }
        setResourcesByConversation((current) => {
          const conversationResources = {
            ...current[selectedSessionId],
          }
          for (const binding of bindings) {
            const { task, sourceMessageId, notificationMessageId } = binding
            conversationResources[sourceMessageId] = {
              ...conversationResources[sourceMessageId],
              task,
            }
            if (notificationMessageId) {
              conversationResources[notificationMessageId] = {
                ...conversationResources[notificationMessageId],
                task,
              }
            }
          }
          return {
            ...current,
            [selectedSessionId]: conversationResources,
          }
        })

        await Promise.allSettled(
          bindings.flatMap(({ task, notificationMessageId }) =>
            notificationMessageId
              ? [
                  loadTaskResource(
                    selectedSessionId,
                    notificationMessageId,
                    task.id,
                    task,
                  ),
                ]
              : [],
          ),
        )
      })
      .catch((error) => {
        if (!cancelled) setActionError(errorMessage(error))
      })

    return () => {
      cancelled = true
    }
  }, [loadTaskResource, selectedSessionId])

  useEffect(() => {
    if (!selectedSessionId) return
    let nextRecovery = recoveryRef.current
    const missingNotifications: Array<{ messageId: string; taskId: string }> = []

    for (const [messageId, resource] of Object.entries(messageResources)) {
      const task = resource.task
      const historicalReplayCutoff = selectedSessionId
        ? historicalTaskReplayCutoffRef.current[selectedSessionId]
        : undefined
      const taskUpdatedAt = task ? Date.parse(task.updatedAt) : Number.NaN
      if (
        isBackgroundTaskNotificationMessageId(messageId) ||
        !task ||
        !terminalTask(task) ||
        (historicalReplayCutoff !== undefined &&
          !Number.isNaN(taskUpdatedAt) &&
          taskUpdatedAt < historicalReplayCutoff)
      ) {
        continue
      }
      const notificationMessageId = backgroundTaskNotificationMessageId(task.id)
      const alreadyLinked = nextRecovery.tasks.some(
        (link) =>
          link.messageId === notificationMessageId &&
          link.resourceId === task.id,
      )
      if (alreadyLinked) continue
      nextRecovery = upsertRecoveryTask(
        nextRecovery,
        notificationMessageId,
        task.id,
      )
      missingNotifications.push({
        messageId: notificationMessageId,
        taskId: task.id,
      })
    }

    if (missingNotifications.length === 0) return
    recoveryRef.current = nextRecovery
    storeChatRecoveryState(selectedSessionId, nextRecovery)
    for (const notification of missingNotifications) {
      void loadTaskResource(
        selectedSessionId,
        notification.messageId,
        notification.taskId,
      ).catch((error) => setActionError(errorMessage(error)))
    }
  }, [loadTaskResource, messageResources, selectedSessionId])

  useEffect(() => {
    const draftId = conversation?.activeConfirmationDraftId ?? undefined
    if (!selectedSessionId || !draftId) return
    if (Object.values(messageResources).some(
      (resource) => resource.draft?.id === draftId,
    )) {
      return
    }
    const recoveryKey = `${selectedSessionId}:${draftId}`
    if (activeDraftLoadsInFlightRef.current.has(recoveryKey)) return
    const messageId =
      (messagesQuery.data ?? []).find(
        (message) => embeddedTaskDraftId(message) === draftId,
      )?.id ??
      recoveryRef.current.drafts.find((link) => link.resourceId === draftId)
        ?.messageId ??
      `active-confirmation:${draftId}`
    const next = upsertRecoveryDraft(recoveryRef.current, messageId, draftId)
    recoveryRef.current = next
    storeChatRecoveryState(selectedSessionId, next)
    activeDraftLoadsInFlightRef.current.add(recoveryKey)
    void loadDraftResource(selectedSessionId, messageId, draftId)
      .then(() => {
        activeDraftRetryAttemptsRef.current.delete(recoveryKey)
        const timer = activeDraftRetryTimersRef.current.get(recoveryKey)
        if (timer !== undefined) window.clearTimeout(timer)
        activeDraftRetryTimersRef.current.delete(recoveryKey)
      })
      .catch((error) => {
        const attempt =
          (activeDraftRetryAttemptsRef.current.get(recoveryKey) ?? 0) + 1
        activeDraftRetryAttemptsRef.current.set(recoveryKey, attempt)
        if (attempt >= 3) {
          setActionError(errorMessage(error))
          return
        }
        if (activeDraftRetryTimersRef.current.has(recoveryKey)) return
        const timer = window.setTimeout(() => {
          activeDraftRetryTimersRef.current.delete(recoveryKey)
          setActiveDraftRetryVersion((current) => current + 1)
        }, attempt * 500)
        activeDraftRetryTimersRef.current.set(recoveryKey, timer)
      })
      .finally(() => {
        activeDraftLoadsInFlightRef.current.delete(recoveryKey)
      })
  }, [
    activeDraftRetryVersion,
    conversation?.activeConfirmationDraftId,
    loadDraftResource,
    messageResources,
    messagesQuery.data,
    selectedSessionId,
  ])

  useEffect(() => {
    if (!selectedSessionId) return
    for (const message of messagesQuery.data ?? []) {
      const draftId = embeddedTaskDraftId(message)
      if (!draftId || messageResources[message.id]?.draft) continue
      const next = upsertRecoveryDraft(
        recoveryRef.current,
        message.id,
        draftId,
      )
      recoveryRef.current = next
      storeChatRecoveryState(selectedSessionId, next)
      void loadDraftResource(selectedSessionId, message.id, draftId).catch(
        (error) => {
          if (conversation?.activeConfirmationDraftId !== draftId) {
            setActionError(errorMessage(error))
          }
        },
      )
    }
  }, [
    loadDraftResource,
    messageResources,
    messagesQuery.data,
    conversation?.activeConfirmationDraftId,
    selectedSessionId,
  ])

  useEffect(
    () => () => {
      for (const timer of activeDraftRetryTimersRef.current.values()) {
        window.clearTimeout(timer)
      }
      activeDraftRetryTimersRef.current.clear()
    },
    [],
  )

  const handleStreamEvent = useCallback(
    async (event: ParsedConversationEvent) => {
      if (!selectedSessionId || event.data.conversationId !== selectedSessionId) {
        return
      }
      latestStreamCursorRef.current[selectedSessionId] = { lastEventId: event.id, lastSequence: event.data.sequence }
      if (event.event === 'context.resource') {
        // Server snapshots own choice availability. Resource evidence never
        // replaces the sidebar, creates a task or becomes model context here.
        await messagesQuery.refreshResourceSnapshot()
        return
      }
      const payload = record(event.data.payload) ?? {}
      const articlePreview = record(payload.articlePreview)
      const previewPreparationId = text(articlePreview?.preparationId)
      if (event.event === 'task.progress' && previewPreparationId && /^[1-9]\d*$/.test(previewPreparationId)) {
        void queryClient.invalidateQueries({ queryKey: ['article-full-preview', ownerId, previewPreparationId] })
      }
      const messageId = text(payload.messageId) ?? event.data.messageId ?? undefined
      const taskId = text(payload.taskId) ?? event.data.taskId ?? undefined
      const deliveryKind =
        text(payload.deliveryKind) ?? text(record(event.data)?.deliveryKind)
      const backgroundTaskDelivery = Boolean(
        event.event.startsWith('task.') &&
          taskId &&
          (deliveryKind === 'BACKGROUND_TASK' || !messageId),
      )
      const historicalReplayCutoff =
        historicalTaskReplayCutoffRef.current[selectedSessionId]
      const occurredAt = Date.parse(event.data.occurredAt)
      const historicalBackgroundTaskReplay = Boolean(
        backgroundTaskDelivery &&
          historicalReplayCutoff !== undefined &&
          !Number.isNaN(occurredAt) &&
          occurredAt < historicalReplayCutoff,
      )
      if (historicalBackgroundTaskReplay) return
      const linkedMessageId = taskId
        ? sourceMessageIdForTask(recoveryRef.current, taskId)
        : undefined
      const currentTimelines = executionTimelinesRef.current[selectedSessionId] ?? {}
      const nextTimelines = reconcileTaskExecutionTimelines(reconcileReplyExecutionTimelines(applyConversationEventToTimelines(
        currentTimelines,
        event,
        linkedMessageId,
      ), messagesQuery.data ?? []), Object.values(resourcesByConversationRef.current[selectedSessionId] ?? {})
        .flatMap(({ task }) => task ? [task] : []))
      executionTimelinesRef.current = {
        ...executionTimelinesRef.current,
        [selectedSessionId]: nextTimelines,
      }
      streamShouldReconnectRef.current[selectedSessionId] = Object.values(
        nextTimelines,
      ).some((timeline) => ['RUNNING', 'QUEUED'].includes(timeline.status))
      setExecutionTimelinesByConversation((current) => ({
        ...current,
        [selectedSessionId]: nextTimelines,
      }))
      storeExecutionTimelineRecovery(
        ownerId,
        selectedSessionId,
        { lastEventId: event.id, lastSequence: event.data.sequence },
        nextTimelines,
      )

      if (event.event === 'heartbeat') {
        // Heartbeats only prove that the stream is alive. Domain progress is
        // refreshed by versioned task.* events; focus and reconnect paths keep
        // their one-shot reconciliation for missed-event recovery.
        return
      }

      if (event.event === 'message.start') {
        setActiveRuns((current) => ({ ...current, [selectedSessionId]: true }))
        return
      }
      if (event.event === 'agent.progress') {
        setActiveRuns((current) => ({ ...current, [selectedSessionId]: true }))
        return
      }
      if (event.event === 'message.delta' && messageId) {
        const delta = readReplyDelta(event)
        if (delta) {
          setStreamedTextByConversation((current) => ({
            ...current,
            [selectedSessionId]: appendReplyDelta(current[selectedSessionId] ?? {}, messageId, delta),
          }))
        }
        return
      }
      if (event.event === 'message.completed') {
        setActiveRuns((current) => ({ ...current, [selectedSessionId]: false }))
        await Promise.all([
          messagesQuery.refetch(),
          refreshPendingDraftResources(selectedSessionId),
        ])
        if (text(payload.status) === 'STOPPED') setComposerNotice('回复已停止，已有内容保留；可以编辑输入后重新发送。')
        if (text(payload.status) === 'FAILED') setComposerNotice('这次回复未完成，已有内容保留。你可以修改问题后重试，不会自动重新生成。')
        // The snapshot watermark, not completion alone, retires the delta buffer.
        return
      }
      if (event.event === 'interaction.required') {
        await messagesQuery.refetch()
        return
      }
      if (event.event === 'draft.created' && messageId) {
        const draftId = text(record(payload.draft)?.id)
        if (!draftId) return
        const next = upsertRecoveryDraft(
          recoveryRef.current,
          messageId,
          draftId,
        )
        recoveryRef.current = next
        storeChatRecoveryState(selectedSessionId, next)
        const [createdDraft] = await Promise.all([
          loadDraftResource(selectedSessionId, messageId, draftId),
          pointAccountQuery.refetch(),
          messagesQuery.refetch(),
        ])
        if (createdDraft?.previousDraftId) {
          await refreshPendingDraftResources(selectedSessionId, [createdDraft.previousDraftId])
        }
        return
      }
      if (event.event.startsWith('task.')) {
        if (!taskId) return
        const linkedMessage =
          sourceMessageIdForTask(recoveryRef.current, taskId) ??
          messageId ??
          `task-event-${taskId}`
        let next = upsertRecoveryTask(
          recoveryRef.current,
          linkedMessage,
          taskId,
        )
        const terminalBackgroundEvent =
          backgroundTaskDelivery &&
          (event.event === 'task.completed' || event.event === 'task.failed')
        const notificationMessageId = terminalBackgroundEvent
          ? backgroundTaskNotificationMessageId(taskId)
          : undefined
        if (notificationMessageId) {
          next = upsertRecoveryTask(next, notificationMessageId, taskId)
          setLiveNotificationTaskIdsByConversation((current) => ({
            ...current,
            [selectedSessionId]: new Set([
              ...(current[selectedSessionId] ?? []),
              taskId,
            ]),
          }))
        }
        recoveryRef.current = next
        storeChatRecoveryState(selectedSessionId, next)
        await Promise.all([
          loadTaskResource(selectedSessionId, linkedMessage, taskId),
          ...(notificationMessageId
            ? [
                loadTaskResource(
                  selectedSessionId,
                  notificationMessageId,
                  taskId,
                ),
              ]
            : []),
          pointAccountQuery.refetch(),
        ])
      }
    },
    [
      loadDraftResource,
      loadTaskResource,
      refreshPendingDraftResources,
      messagesQuery,
      pointAccountQuery,
      ownerId,
      selectedSessionId,
      queryClient,
    ],
  )

  const historyHasStreamingMessage = (messagesQuery.data ?? []).some(
    (message) =>
      message.role === 'ASSISTANT' && message.status === 'STREAMING',
  )
  const hasActiveTask = Object.values(messageResources).some(({ task }) =>
    task ? ACTIVE_PAID_TASK_STATUSES.has(task.status) : false,
  )
  const hasActiveTimeline = Object.values(executionTimelines).some((timeline) =>
    ['RUNNING', 'QUEUED'].includes(timeline.status),
  )
  const needsStreamBootstrap = Boolean(
    selectedSessionId &&
      !(
        streamBootstrapComplete[selectedSessionId] ??
        (initialStreamCursor !== null)
      ),
  )
  if (selectedSessionId) {
    streamShouldReconnectRef.current[selectedSessionId] = Boolean(
      activeRun || historyHasStreamingMessage || hasActiveTask || hasActiveTimeline,
    )
  }
  const selectedStreamCursor = selectedSessionId
    ? Object.prototype.hasOwnProperty.call(
        streamResumeCursors,
        selectedSessionId,
      )
      ? streamResumeCursors[selectedSessionId]
      : initialStreamCursor
    : null

  useConversationEventStream({
    conversationId: selectedSessionId,
    enabled: Boolean(
      selectedSessionId &&
        messagesQuery.snapshot &&
        (needsStreamBootstrap ||
          activeRun ||
          historyHasStreamingMessage ||
          hasActiveTask ||
          hasActiveTimeline),
    ),
    onEvent: handleStreamEvent,
    onError: (error) => setActionError(errorMessage(error)),
    browserTrace: selectedSessionId
      ? streamTraces[selectedSessionId]
      : undefined,
    initialCursor: selectedStreamCursor,
    onStreamEnd: (cursor) => {
      if (!selectedSessionId) return false
      void reconcileActiveTaskResources(selectedSessionId)
      setStreamBootstrapComplete((current) => ({
        ...current,
        [selectedSessionId]: true,
      }))
      setStreamResumeCursors((current) => ({
        ...current,
        [selectedSessionId]: cursor ?? null,
      }))
      return streamShouldReconnectRef.current[selectedSessionId] ?? false
    },
  })

  const sessions = useMemo(() => {
    const keyword = sessionSearchValue.trim().toLocaleLowerCase('zh-CN')
    return (conversationsQuery.data ?? [])
      .map(toChatSessionView)
      .filter(
        (item) =>
          !keyword || item.title.toLocaleLowerCase('zh-CN').includes(keyword),
      )
  }, [conversationsQuery.data, sessionSearchValue])

  const spaces = useMemo(
    () =>
      (projects.data ?? []).map((space) => ({
        id: space.id,
        name: space.name,
      })),
    [projects.data],
  )

  const taskStatusSummaries = useMemo(() => {
    const tasksById = new Map<string, PaidTask>()
    for (const { task } of Object.values(messageResources)) {
      if (task) tasksById.set(task.id, task)
    }
    const sortedTasks = [...tasksById.values()].sort((left, right) => {
      const activeDifference =
        Number(ACTIVE_PAID_TASK_STATUSES.has(right.status)) -
        Number(ACTIVE_PAID_TASK_STATUSES.has(left.status))
      if (activeDifference !== 0) return activeDifference
      return Date.parse(right.updatedAt) - Date.parse(left.updatedAt)
    })
    const activeTasks = sortedTasks.filter((task) =>
      ACTIVE_PAID_TASK_STATUSES.has(task.status),
    )
    const recentTerminalTasks = sortedTasks
      .filter((task) => !ACTIVE_PAID_TASK_STATUSES.has(task.status))
      .slice(0, 5)
    return [...activeTasks, ...recentTerminalTasks].map(toChatTaskStatusSummary)
  }, [messageResources])
  const liveNotificationTaskIds = selectedSessionId
    ? liveNotificationTaskIdsByConversation[selectedSessionId] ?? EMPTY_TASK_IDS
    : EMPTY_TASK_IDS

  const messages = useMemo(() => {
    const persisted = messagesQuery.data ?? []
    const replacedDraftIds = new Set(Object.values(messageResources)
      .flatMap(({ draft: item }) => item?.previousDraftId ? [item.previousDraftId] : []))
    const draftConfirmationFor = (resource: MessageResourceState, taskDraft: TaskDraft) => {
      const confirmation = toPaidTaskConfirmation(
        taskDraft,
        pointAccountQuery.data,
        resource.productName,
        confirmingDraftId === taskDraft.id,
        undefined,
        (taskDraft.referenceAssetIds ?? []).map((assetId, index) => ({
          id: assetId,
          name: draftReferenceAssetDetails[assetId]?.name ?? `参考图片 ${index + 1}`,
          thumbnailUrl: draftReferenceAssetDownloads[assetId]?.url,
        })),
      )
      return {
        ...confirmation,
        editDisabledReason: draftEditRestriction(taskDraft.id, conversation?.activeConfirmationDraftId),
        unavailableReason: resource.draftVerificationPending
          ? '正在核对方案状态，请稍候。'
          : resource.draftVerificationError
            ?? (taskDraft.status === 'ABANDONED' && replacedDraftIds.has(taskDraft.id)
              ? '此方案已被新方案替代，不能继续确认。请核对新的方案卡片。'
              : confirmation.unavailableReason),
        rightSideProductOverridden: taskDraft.sourceMode === 'CURRENT_ATTACHMENTS' && Boolean(selectedProductId),
      }
    }
    const generatedAssetFor = (resource?: MessageResourceState) => {
      if (!resource?.task) return undefined
      const assetReference =
        generatedImageAssetReference(resource.task) ??
        generatedVideoAssetReference(resource.task)
      const assetId = assetReference?.assetId
      return assetId
        ? {
            name: generatedAssetDetails[assetId]?.name,
            imageUrl:
              assetReference?.assetType === 'IMAGE'
                ? generatedAssetDownloads[assetId]?.url
                : undefined,
            videoUrl:
              assetReference?.assetType === 'VIDEO'
                ? generatedAssetDownloads[assetId]?.url
                : undefined,
            expiresAt: generatedAssetDownloads[assetId]?.expiresAt,
            creativeSourceMode: resource.draft?.sourceMode ?? undefined,
            selectedAssetIds: currentAttachmentIds,
            imagesByAssetId: Object.fromEntries(generatedImageAssetReferences(resource.task).map(({ assetId: imageId }) => [imageId, {
              name: generatedAssetDetails[imageId]?.name,
              imageUrl: generatedAssetDownloads[imageId]?.url,
              expiresAt: generatedAssetDownloads[imageId]?.expiresAt,
            }])),
          }
        : undefined
    }
    const shownPreparationIds = new Set<string>()
    const persistedViews: ChatMessageView[] = persisted.map((message) => {
      const rawResource = messageResources[message.id]
      // A full preparation owns its stages. Do not turn its TITLE/OUTLINE tasks
      // into legacy per-stage confirmations, even when task events arrive first.
      const preparationId = messageArticlePreparation(message)?.preparationId
      const resource = preparationId || taskArticlePreparationId(rawResource?.task) ? undefined : rawResource
      const articlePreparation = preparationId && !shownPreparationIds.has(preparationId)
        ? articlePreparationViews[preparationId] : undefined
      if (articlePreparation) shownPreparationIds.add(articlePreparation.id)
      const pendingDraft = pendingDraftForDisplay(
        resource?.draft && !admittedMonitorDrafts[selectedSessionId ?? '']?.[resource.draft.id] ? resource.draft : undefined,
        resource?.task,
      )
      const view = toChatMessageView(message, {
        task: resource?.task,
        taskPresentation: terminalTask(resource?.task)
          ? 'SOURCE_STATUS'
          : 'DEFAULT',
        article: resource?.article,
        geoReport: resource?.geoReport,
        mediaRecommendations: resource?.mediaRecommendations,
        mediaRecommendationError: resource?.mediaRecommendationError,
        pointAccount: pointAccountQuery.data,
        productName: resource?.productName,
        generatedAsset: generatedAssetFor(resource),
        paidTaskConfirmation: pendingDraft && resource
          ? draftConfirmationFor(resource, pendingDraft)
          : undefined,
      })
      return {
        ...view,
        articlePreparation,
        clarificationOptions: view.clarificationOptions?.map((option) => {
          const answered = persisted.some((later) => later.role === 'USER' && later.sequence > message.sequence)
          return answered ? { ...option, disabled: true, description: '这条补充问题已处理，请在输入框说明新的需求。' } : option
        }),
        executionTimeline:
          message.role === 'ASSISTANT'
            ? executionTimelines[message.id]
            : undefined,
      }
    })
    const known = new Set(persisted.map((message) => message.id))
    const supplementalViews: ChatMessageView[] = []
    const notificationResources = new Map<
      string,
      { messageId: string; resource: MessageResourceState }
    >()
    for (const [messageId, resource] of Object.entries(messageResources)) {
      if (taskArticlePreparationId(resource.task)) continue
      const pendingDraft = pendingDraftForDisplay(resource.draft && !admittedMonitorDrafts[selectedSessionId ?? '']?.[resource.draft.id] ? resource.draft : undefined, resource.task)
      if (
        resource.task &&
        terminalTask(resource.task) &&
        (isBackgroundTaskNotificationMessageId(messageId) ||
          !known.has(messageId))
      ) {
        const existing = notificationResources.get(resource.task.id)
        if (
          !existing ||
          isBackgroundTaskNotificationMessageId(messageId)
        ) {
          notificationResources.set(resource.task.id, { messageId, resource })
        }
      }
      if (known.has(messageId) || (!resource.task && !pendingDraft)) continue
      if (resource.task && terminalTask(resource.task)) continue
      supplementalViews.push(
        toChatMessageView(
          {
            id: messageId,
            conversationId: selectedSessionId ?? '',
            role: 'ASSISTANT',
            contentType: 'SYSTEM_EVENT',
            content: { text: '' },
            replyToMessageId: null,
            runId: null,
            attachmentAssetIds: [],
            sequence: Number.MAX_SAFE_INTEGER,
            status: 'COMPLETED',
            createdAt: resource.task?.createdAt ?? pendingDraft!.createdAt,
            updatedAt: resource.task?.updatedAt ?? pendingDraft!.createdAt,
          },
          {
            task: resource.task,
            article: resource.article,
            geoReport: resource.geoReport,
            mediaRecommendations: resource.mediaRecommendations,
            mediaRecommendationError: resource.mediaRecommendationError,
            pointAccount: pointAccountQuery.data,
            productName: resource.productName,
            paidTaskConfirmation: pendingDraft
              ? draftConfirmationFor(resource, pendingDraft)
              : undefined,
            generatedAsset: generatedAssetFor(resource),
          },
        ),
      )
    }

    const notificationViews: Array<{
      view: ChatMessageView
      occurredAt: string
    }> = [...notificationResources.values()]
      .flatMap(({ messageId, resource }) => {
        const task = resource.task
        const notification = task
          ? toBackgroundTaskNotification(task)
          : undefined
        if (!task || !notification) return []
        const view = toChatMessageView(
          {
            id: messageId,
            conversationId: selectedSessionId ?? '',
            role: 'ASSISTANT',
            contentType: 'SYSTEM_EVENT',
            content: { text: '' },
            replyToMessageId: null,
            runId: null,
            attachmentAssetIds: [],
            sequence: Number.MAX_SAFE_INTEGER,
            status: 'COMPLETED',
            createdAt: task.updatedAt,
            updatedAt: task.updatedAt,
          },
          {
            task,
            taskPresentation: 'BACKGROUND_NOTIFICATION',
            article: resource.article,
            geoReport: resource.geoReport,
            mediaRecommendations: resource.mediaRecommendations,
            mediaRecommendationError: resource.mediaRecommendationError,
            pointAccount: pointAccountQuery.data,
            productName: resource.productName,
            generatedAsset: generatedAssetFor(resource),
          },
        )
        return [
          {
            view: {
              ...view,
              backgroundTaskNotification: {
                ...notification,
                announce: liveNotificationTaskIds.has(task.id),
              },
            },
            occurredAt: task.updatedAt,
          },
        ]
      })
      .sort(
        (left, right) =>
          Date.parse(left.occurredAt) - Date.parse(right.occurredAt),
      )

    const result = [...persistedViews]
    let insertedNotifications = 0
    for (const notification of notificationViews) {
      const notificationTime = Date.parse(notification.occurredAt)
      const persistedIndex = Number.isNaN(notificationTime)
        ? -1
        : persisted.findIndex((message) => {
            const messageTime = Date.parse(message.createdAt)
            return !Number.isNaN(messageTime) && messageTime > notificationTime
          })
      const insertionIndex =
        (persistedIndex < 0 ? persisted.length : persistedIndex) +
        insertedNotifications
      result.splice(insertionIndex, 0, notification.view)
      insertedNotifications += 1
    }
    result.push(...supplementalViews)
    for (const preparation of Object.values(articlePreparationViews)) {
      if (shownPreparationIds.has(preparation.id)) continue
      result.push({ id: `article-preparation-${preparation.id}`, role: 'ASSISTANT', content: '', articlePreparation: preparation })
    }

    const placed = placeExecutionTimelines(result, persisted, executionTimelines,
      Object.values(messageResources).flatMap((resource) => resource.task ? [resource.task] : []))
    return withStreamedReplyContent(placed, persisted, streamedText, messagesQuery.snapshot?.highWatermark ?? 0)
  }, [
    admittedMonitorDrafts,
    articlePreparationViews,
    conversation?.activeConfirmationDraftId,
    confirmingDraftId,
    currentAttachmentIds,
    draftReferenceAssetDetails,
    draftReferenceAssetDownloads,
    generatedAssetDetails,
    generatedAssetDownloads,
    executionTimelines,
    liveNotificationTaskIds,
    messageResources,
    messagesQuery.data,
    messagesQuery.snapshot?.highWatermark,
    pointAccountQuery.data,
    selectedProductId,
    selectedSessionId,
    streamedText,
  ])

  const isGenerating = activeRun || historyHasStreamingMessage
  const activeArticleWorkflow = useMemo(
    () => findActiveArticleWorkflow(messages),
    [messages],
  )

  const selectConversation = (conversationId: string, preserveReportSource = false) => {
    if (preserveReportSource) geoReportSource.bindCreatedConversation(conversationId)
    else geoReportSource.clear()
    setActionError(undefined)
    setSendError(undefined)
    setSelectedTaskId(undefined)
    setLiveNotificationTaskIdsByConversation((current) => {
      if (!current[conversationId]) return current
      const next = { ...current }
      delete next[conversationId]
      return next
    })
    setSearchParams({ conversation: conversationId }, { replace: preserveReportSource })
    setAttachmentSelectorOpen(false)
  }

  const diagnosticEntry = useGeoDiagnosticEntry({ ownerId,
    scopeKey: `${ownerId}:${selectedSessionId ?? `new:${installationId}:${owningProjectId}`}`,
    conversationId: selectedSessionId, projectId: conversation?.agentIdentity?.projectId ?? owningProjectId,
    enabled: Boolean(ownerId && !launchGuidance && (conversation?.agentIdentity?.agentKey ?? selectedInstallation?.agentKey) === 'geo'),
    onNotice: setComposerNotice,
    onEnsureConversation: async () => {
      if (selectedSessionId) return selectedSessionId
      if (createConversationMutation.isPending || sendMutation.isPending || !newConversationRequest?.installationId || launchGuidance) return undefined
      const session = captureAuthSession(), originLocation = location.key
      const created = await createConversationMutation.mutateAsync({ request: newConversationRequest, browserTrace: createBrowserTraceContext() })
      if (!isCurrentAuthSession(session) || activeLocationKey.current !== originLocation) return undefined
      setDrafts((current) => ({ ...current, [created.id]: current.new ?? '' }))
      selectConversation(created.id)
      return created.id
    },
  })

  const updateCurrentAttachmentIds = (
    conversationKey: string,
    assetIds: readonly string[],
  ) => {
    const uniqueIds = [...new Set(assetIds)].slice(0, 8)
    setAttachmentIdsByConversation((current) => ({
      ...current,
      [conversationKey]: uniqueIds,
    }))
    if (ownerId) {
      storeCurrentAttachmentIds(ownerId, conversationKey, uniqueIds)
    }
  }

  const appendCurrentAttachmentId = (
    conversationKey: string,
    assetId: string,
  ) => {
    setAttachmentIdsByConversation((current) => {
      const existing = current[conversationKey] ??
        (ownerId ? readCurrentAttachmentIds(ownerId, conversationKey) : [])
      const nextIds = [...new Set([...existing, assetId])].slice(0, 8)
      if (ownerId) {
        storeCurrentAttachmentIds(ownerId, conversationKey, nextIds)
      }
      return { ...current, [conversationKey]: nextIds }
    })
  }

  const handleUploadAttachments = async (files: readonly File[]) => {
    const session = captureAuthSession()
    const requestLocationKey = location.key
    const current = () => isCurrentAuthSession(session) && activeLocationKey.current === requestLocationKey
    let storageSpaceId: string
    try { storageSpaceId = await resolveAttachmentSpace() }
    catch (error) { if (current()) setSendError(errorMessage(error)); return }
    if (!current()) return
    const conversationKey = draftKey
    const existingUploads = attachmentUploadsByConversation[conversationKey] ?? []
    const availableSlots = Math.max(
      0,
      8 - currentAttachmentIds.length - existingUploads.length,
    )
    const uploads = files.slice(0, availableSlots).map((file) => ({
      id: crypto.randomUUID(),
      file,
    }))
    if (uploads.length === 0) return
    setSendError(undefined)
    setAttachmentUploadsByConversation((current) => ({
      ...current,
      [conversationKey]: [
        ...(current[conversationKey] ?? []).filter(
          ({ status }) => status === 'FAILED',
        ),
        ...uploads.map(({ id, file }) => ({
          assetId: id,
          name: file.name,
          status: 'UPLOADING' as const,
          progressPercent: 0,
        })),
      ],
    }))

    await Promise.allSettled(uploads.map(async ({ id, file }) => {
      try {
        const asset = await uploadImageMutation.mutateAsync({
          spaceId: storageSpaceId,
          file,
          name: file.name,
          idempotencyKey: crypto.randomUUID(),
          onUploadProgress: (progressPercent) =>
            setAttachmentUploadsByConversation((current) => ({
              ...current,
              [conversationKey]: (current[conversationKey] ?? []).map((upload) =>
                upload.assetId === id ? { ...upload, progressPercent } : upload,
              ),
            })),
        })
        setAttachmentUploadsByConversation((current) => ({
          ...current,
          [conversationKey]: (current[conversationKey] ?? []).filter(
            (upload) => upload.assetId !== id,
          ),
        }))
        appendCurrentAttachmentId(conversationKey, asset.id)
      } catch (error) {
        setAttachmentUploadsByConversation((current) => ({
          ...current,
          [conversationKey]: (current[conversationKey] ?? []).map((upload) =>
            upload.assetId === id
              ? {
                  ...upload,
                  status: 'FAILED',
                  errorMessage: errorMessage(error),
                }
              : upload,
          ),
        }))
      }
    }))
  }

  const handleNewConversation = () => {
    if ((attachmentUploadsByConversation.new ?? []).some(
      ({ status }) => status === 'UPLOADING',
    )) {
      setActionError('请等待本次图片上传完成后再新建对话。')
      return
    }
    geoReportSource.clear()
    setActionError(undefined)
    navigate(appLinks.newAgentTask(conversation?.agentIdentity?.installationId ?? installationId, conversation?.agentIdentity?.projectId ?? owningProjectId))
  }

  const handleSend = async (
    content: string,
    options: SendMessageOptions = {},
  ) => {
    if (
      sendLock.current ||
      createConversationMutation.isPending ||
      sendMutation.isPending ||
      (isGenerating && !options.stoppedRunId)
    ) {
      return
    }
    if (platformStart && launchGuidance) {
      setSendError(launchGuidance)
      return
    }
    const resourceRead = Boolean(options.resourceSelection)
    const monitorAction = Boolean(options.executionIntent)
    if (!resourceRead && !monitorAction && (geoReportSource.loading || geoReportSource.notice)) {
      setSendError('请先核对或移除报告来源，再发送消息。')
      return
    }
    if (!resourceRead && !monitorAction && geoReportSource.source && !validGeoReportSource) {
      setSendError('当前项目与报告来源不一致，请先移除报告来源，再发送消息。')
      return
    }
    const sourceReportId = resourceRead || monitorAction || options.articleContext ? undefined : validGeoReportSource?.reportId
    if (sourceReportId && currentAttachments.length > 0) {
      setSendError('报告来源不能与本次图片附件一起发送。请移除报告来源，或移除所有附件后再发送。')
      return
    }
    const requestAttachments = resourceRead || monitorAction || options.articleContext
      ? []
      : currentAttachments
    const requestProductId = resourceRead || monitorAction ? undefined : options.articleContext
      ? options.articleContext.productId ?? undefined
      : selectedProductId
    const sourceDecision = decideCreativeSource({
      attachments: requestAttachments,
      selectedProductId: requestProductId,
    })
    if (requestAttachments.length > 0 && !sourceDecision.ready) {
      setSendError(sourceDecision.guidance)
      return
    }
    sendLock.current = true
    const requestSession = captureAuthSession()
    const requestLocationKey = location.key
    setSendError(undefined)
    let conversationId =
      options.targetConversationId ?? selectedSessionId ?? autoCreatedConversationRef.current
    let conversationDraftKey = selectedSessionId ?? 'new'
    const browserTrace = createBrowserTraceContext()
    try {
      if (!conversationId) {
        const created = await createConversationMutation.mutateAsync({
          request: newConversationRequest,
          browserTrace,
        })
        if (!isCurrentAuthSession(requestSession)) return
        conversationId = created.id
        conversationDraftKey = created.id
        // A late create response must not replace a task the user switched to.
        // The originally submitted message still belongs to the created identity.
        if (activeLocationKey.current === requestLocationKey) {
          autoCreatedConversationRef.current = created.id
          setDrafts((current) => {
            const next = { ...current, [created.id]: current.new ?? content }
            delete next.new
            return next
          })
          setContextSelections((current) => {
            const provisional = current.new
            if (!provisional) return current
            const next = { ...current, [created.id]: provisional }
            delete next.new
            return next
          })
          if (ownerId) {
            const moved = moveCurrentAttachmentIds(ownerId, 'new', created.id)
            setAttachmentIdsByConversation((current) => {
              const next = { ...current, [created.id]: moved }
              delete next.new
              return next
            })
          }
          selectConversation(created.id, true)
        }
      }

      const targetConversationId = conversationId
      if (!ownerId) return
      // Publish the originating action trace before the accepted mutation's
      // snapshot invalidation can start the stream.
      setStreamTraces((current) => ({ ...current, [targetConversationId]: browserTrace }))
      const requestBody: Omit<SendConversationMessageRequest, 'clientMessageId'> = options.executionIntent ? {
        content,
        executionIntent: options.executionIntent,
        contextSelectionMode: 'INHERIT',
        attachments: [],
      } : options.resourceSelection ? {
        content,
        replyToMessageId: options.replyToMessageId,
        resourceSelection: options.resourceSelection,
        contextSelectionMode: 'INHERIT',
        attachments: [],
      } : {
          content,
          ...(sourceReportId ? { geoReportId: sourceReportId } : {}),
          ...(options.replyToMessageId
            ? { replyToMessageId: options.replyToMessageId }
            : {}),
          ...toConversationContextRequest(
            contextSelection,
            options.articleContext,
          ),
          attachments: sourceDecision.referenceAssetIds.map((assetId) => ({
            assetId,
          })),
      }
      const fingerprint = await messageFingerprint(requestBody)
      if (!isCurrentAuthSession(requestSession)) return
      const previousAttempt = readMessageAttempt(ownerId, targetConversationId)
      let recovered: Awaited<ReturnType<typeof getConversationMessageOperation>> | undefined
      if (previousAttempt) {
        try {
          recovered = await getConversationMessageOperation(targetConversationId, previousAttempt.clientMessageId)
        } catch (error) {
          if (!isApiError(error) || error.status !== 404) throw error
        }
        if (!isCurrentAuthSession(requestSession)) return
        if (previousAttempt.fingerprint !== fingerprint) {
          if (recovered) {
            clearMessageAttempt(ownerId, targetConversationId)
            await messagesQuery.refetch()
            setComposerNotice('上一条消息已受理，已同步结果。你修改后的内容仍未发送，请核对后再发送。')
          } else {
            setSendError('上一条消息的发送结果尚未确认。请保留原内容重试，系统会沿用同一编号，避免重复回复。修改后的内容已保留。')
          }
          return
        }
      }
      const attempt = previousAttempt ?? { clientMessageId: newRequestId(), idempotencyKey: newRequestId(), fingerprint }
      storeMessageAttempt(ownerId, targetConversationId, attempt)
      if (!resourceRead && !monitorAction && !options.articleContext) setPendingMessageInputs((current) => ({ ...current, [targetConversationId]: {
        content, attachmentIds: sourceDecision.referenceAssetIds, context: contextSelection,
      } }))
      const accepted = recovered ?? await sendMutation.mutateAsync({
        conversationId: targetConversationId,
        idempotencyKey: attempt.idempotencyKey,
        request: { ...requestBody, clientMessageId: attempt.clientMessageId },
        browserTrace,
      }).catch((error: unknown) => {
        if (isApiError(error) && [400, 401, 403, 404, 409, 410, 422, 429].includes(error.status)) {
          clearMessageAttempt(ownerId, targetConversationId)
          setPendingMessageInputs((current) => { const next = { ...current }; delete next[targetConversationId]; return next })
        }
        throw error
      })
      if (!isCurrentAuthSession(requestSession)) return
      clearMessageAttempt(ownerId, targetConversationId)
      setPendingMessageInputs((current) => { const next = { ...current }; delete next[targetConversationId]; return next })
      if (recovered) await messagesQuery.refetch()
      if (!isCurrentAuthSession(requestSession)) return
      // The mutation waits for several refreshes. A fast reply can already be
      // terminal in the message snapshot while a slower sidebar refresh delays
      // this older QUEUED acknowledgement. Never resurrect that finished Run.
      const latestSnapshot = queryClient.getQueryData<InfiniteData<{ snapshot?: ConversationSnapshot }>>(
        chatKeysFor(ownerId).messages(targetConversationId),
      )?.pages[0]?.snapshot
      const terminalMessage = latestSnapshot?.messages.find((message) =>
        message.runId === accepted.runId && message.role === 'ASSISTANT' &&
        ['COMPLETED', 'FAILED', 'STOPPED'].includes(message.status),
      )
      const acceptedStatus = terminalMessage
        ? terminalMessage.status as 'COMPLETED' | 'FAILED' | 'STOPPED' : accepted.status
      const snapshotContainsRun = latestSnapshot?.activeRun?.runId === accepted.runId ||
        latestSnapshot?.messages.some((message) => message.runId === accepted.runId)
      const currentTimelines = executionTimelinesRef.current[targetConversationId] ?? {}
      const nextTimelines = acceptReplyExecutionTimeline(
        currentTimelines, { ...accepted, status: acceptedStatus }, latestSnapshot?.messages ?? [],
      )
      executionTimelinesRef.current = {
        ...executionTimelinesRef.current,
        [targetConversationId]: nextTimelines,
      }
      setExecutionTimelinesByConversation((current) => ({
        ...current,
        [targetConversationId]: nextTimelines,
      }))
      const currentCursor = readConversationEventCursor(targetConversationId)
      if (currentCursor) {
        storeExecutionTimelineRecovery(
          ownerId,
          targetConversationId,
          currentCursor,
          nextTimelines,
        )
      }
      setStreamTraces((current) => ({
        ...current,
        [targetConversationId]: browserTrace,
      }))
      if (!resourceRead && !monitorAction && !options.articleContext) {
        setDrafts((current) => ({ ...current, [conversationDraftKey]: current[conversationDraftKey]?.trim() === content ? '' : current[conversationDraftKey] ?? '' }))
        if (ownerId) {
          storeCurrentAttachmentIds(ownerId, targetConversationId, readCurrentAttachmentIds(ownerId, targetConversationId)
            .filter((id) => !sourceDecision.referenceAssetIds.includes(id)))
        }
        setAttachmentIdsByConversation((current) => ({
          ...current,
          [targetConversationId]: (current[targetConversationId] ?? []).filter((id) => !sourceDecision.referenceAssetIds.includes(id)),
        }))
        setAttachmentUploadsByConversation((current) => ({
          ...current,
          [targetConversationId]: (current[targetConversationId] ?? []).filter((attachment) => !sourceDecision.referenceAssetIds.includes(attachment.assetId)),
        }))
      }
      setActiveRuns((current) => ({
        ...current,
        [targetConversationId]: snapshotContainsRun
          ? Boolean(latestSnapshot?.activeRun)
          : !['COMPLETED', 'FAILED', 'STOPPED'].includes(acceptedStatus),
      }))
      if (monitorAction && targetConversationId !== selectedSessionId && activeLocationKey.current === requestLocationKey) {
        selectConversation(targetConversationId)
      }
      return accepted
    } catch (error) {
      if (!isCurrentAuthSession(requestSession)) return
      if (resourceRead && isApiError(error) && [403, 404, 409, 410].includes(error.status)) {
        await messagesQuery.refetch()
        if (!isCurrentAuthSession(requestSession)) return
      }
      setSendError(errorMessage(error))
      if (monitorAction) throw error
    } finally {
      sendLock.current = false
    }
  }

  const handleStop = async () => {
    if (!selectedSessionId || stopMutation.isPending) return undefined
    const expectedRunId = messagesQuery.snapshot?.activeRun?.runId
      ?? Object.values(executionTimelines).find((timeline) => timeline.status === 'RUNNING')?.runId
      ?? (messagesQuery.data ?? []).find((message) => message.status === 'STREAMING')?.runId
    if (!expectedRunId) {
      setActionError('暂时无法确定正在回复的消息，正在同步状态。请稍后再试，未发送内容已保留。')
      await messagesQuery.refetch()
      return undefined
    }
    setActionError(undefined)
    try {
      const stopped = await stopMutation.mutateAsync({ conversationId: selectedSessionId, expectedRunId })
      if (stopped.runId === expectedRunId && ['STOPPED', 'COMPLETED', 'FAILED'].includes(stopped.status)) {
        setActiveRuns((current) => ({ ...current, [selectedSessionId]: false }))
        setComposerNotice('当前回复已停止，已生成的内容保留。后台创作任务不受影响。')
        return expectedRunId
      }
      setComposerNotice('已请求停止，正在等待确认。下一条内容尚未发送，请稍后重试。')
    } catch (error) {
      setActionError(errorMessage(error))
    }
    return undefined
  }

  const confirmDraftResource = async (
    conversationId: string,
    messageId: string,
    taskDraft: TaskDraft,
  ) => {
    const draftId = taskDraft.id
    if (
      !taskDraft?.confirmationToken ||
      taskDraft.status !== 'WAITING_CONFIRMATION' ||
      admittedMonitorDraftsRef.current[conversationId]?.[draftId] ||
      confirmLock.current
    ) {
      return false
    }
    confirmLock.current = draftId
    setConfirmingDraftId(draftId)
    setActionError(undefined)
    try {
      // Verify the card the user actually saw; never accept a new proof or price
      // automatically, and never confirm another currently active draft instead.
      const latest = await loadDraftResource(conversationId, messageId, draftId)
      if (!latest) return false
      if (admittedMonitorDraftsRef.current[conversationId]?.[draftId] || latest.status !== 'WAITING_CONFIRMATION' || Date.parse(latest.expiresAt) <= Date.now()) {
        setComposerNotice('此方案已不能确认，请查看卡片中的当前状态。未提交新的付费任务。')
        return false
      }
      if (latest.id !== taskDraft.id || latest.inputHash !== taskDraft.inputHash
        || latest.pricingVersionId !== taskDraft.pricingVersionId
        || latest.confirmationToken !== taskDraft.confirmationToken
        || latest.quotedPoints !== taskDraft.quotedPoints) {
        setComposerNotice('方案或报价已更新，请重新核对卡片后再确认。本次未提交付费任务。')
        return false
      }
      const confirmationAttempt = reserveConfirmationAttempt(
        recoveryRef.current,
        messageId,
        draftId,
        `${draftId}:${taskDraft.inputHash}:${taskDraft.pricingVersionId}`,
        newRequestId,
      )
      recoveryRef.current = confirmationAttempt.state
      storeChatRecoveryState(conversationId, confirmationAttempt.state)
      const browserTrace = createBrowserTraceContext()
      const accepted = await confirmTaskDraft(
        draftId,
        {
          confirmationToken: taskDraft.confirmationToken,
          inputHash: taskDraft.inputHash,
          pricingVersionId: taskDraft.pricingVersionId,
        },
        confirmationAttempt.idempotencyKey,
        browserTrace,
      )
      const next = bindRecoveryTaskToSourceMessage(
        recoveryRef.current,
        messageId,
        accepted.taskId,
      )
      recoveryRef.current = next
      storeChatRecoveryState(conversationId, next)
      await Promise.all([
        loadDraftResource(conversationId, messageId, draftId),
        loadTaskResource(conversationId, messageId, accepted.taskId),
        pointAccountQuery.refetch(),
        conversationQuery.refetch(),
      ])
      return true
    } catch (error) {
      setActionError(errorMessage(error))
      if (isApiError(error) && [409, 410, 422].includes(error.status)) {
        await loadDraftResource(conversationId, messageId, draftId).catch(
          () => undefined,
        )
      }
      return false
    } finally {
      if (confirmLock.current === draftId) {
        confirmLock.current = undefined
      }
      setConfirmingDraftId(undefined)
    }
  }

  const renameSession = (sessionId: string) => {
    const selected = conversationsQuery.data?.find(({ id }) => id === sessionId)
    if (!selected) return
    setRenamingSessionId(sessionId)
    setRenameValue(selected.title)
  }

  const submitRename = async () => {
    const selected = conversationsQuery.data?.find(
      ({ id }) => id === renamingSessionId,
    )
    if (!selected || !renameValue.trim()) return
    try {
      await updateConversationMutation.mutateAsync({
        conversationId: selected.id,
        request: { version: selected.version, title: renameValue.trim() },
      })
      setRenamingSessionId(undefined)
    } catch (error) {
      setActionError(errorMessage(error))
    }
  }

  const submitDelete = async () => {
    if (!deletingSessionId) return
    try {
      await deleteConversationMutation.mutateAsync(deletingSessionId)
      if (selectedSessionId === deletingSessionId) setSearchParams({})
      setDeletingSessionId(undefined)
    } catch (error) {
      setActionError(errorMessage(error))
    }
  }

  const previewGeneratedImage = async (assetId: string) => {
    const request = ++imagePreviewRequest.current
    const session = captureAuthSession()
    const pending = { assetId, ownerId, conversationId: selectedSessionId, loading: true, error: false }
    setImagePreview(pending)
    try {
      const download = await createAssetDownloadUrl(assetId)
      if (request === imagePreviewRequest.current && isCurrentAuthSession(session) && selectedSessionRef.current === pending.conversationId) {
        setImagePreview({ ...pending, url: download.url, loading: false })
      }
    } catch {
      if (request === imagePreviewRequest.current && isCurrentAuthSession(session) && selectedSessionRef.current === pending.conversationId) {
        setImagePreview({ ...pending, loading: false, error: true })
      }
    }
  }

  const closeImagePreview = () => {
    imagePreviewRequest.current += 1
    setImagePreview(undefined)
  }

  const openGeneratedImage = async (assetId: string) => {
    setActionError(undefined)
    try {
      const download = await createAssetDownloadUrl(assetId)
      if (!openExternalHttps(download.url)) {
        setActionError('资产临时链接无法安全打开。')
      }
    } catch (error) {
      setActionError(errorMessage(error))
    }
  }

  const handleConfirmTask = async (draftId: string) => {
    const entry = Object.entries(messageResources).find(
      ([, resource]) => resource.draft?.id === draftId,
    )
    const taskDraft = entry?.[1].draft
    if (!selectedSessionId || !entry || !taskDraft) return
    await confirmDraftResource(selectedSessionId, entry[0], taskDraft)
  }

  const handleEditTask = async (draftId: string) => {
    const conversationId = selectedSessionId
    const resource = Object.values(messageResources).find((item) => item.draft?.id === draftId)
    const taskDraft = resource?.draft
    const restriction = draftEditRestriction(draftId, conversation?.activeConfirmationDraftId)
    if (!conversationId || !taskDraft || admittedMonitorDraftsRef.current[conversationId]?.[draftId]) return
    if (restriction) {
      setComposerNotice(restriction)
      return
    }
    if (resource.draftVerificationPending || resource.draftVerificationError
      || taskDraft.status !== 'WAITING_CONFIRMATION' || confirmingDraftId
      || Date.parse(taskDraft.expiresAt) <= Date.now()) return
    const session = captureAuthSession()
    const currentInput = drafts[conversationId] ?? ''
    try {
      // The unique candidate may have changed in another tab since this card rendered.
      // This read does not bind an arbitrary historical card or authorize a new Run.
      const [latestConversation, latestDraft] = await Promise.all([
        getConversation(conversationId), getTaskDraft(draftId),
      ])
      if (!isCurrentAuthSession(session) || selectedSessionRef.current !== conversationId) return
      queryClient.setQueryData(chatKeysFor(ownerId).conversation(conversationId), latestConversation)
      const latestRestriction = draftEditRestriction(draftId, latestConversation.activeConfirmationDraftId)
      if (latestRestriction) {
        setComposerNotice(latestRestriction)
        return
      }
      if (admittedMonitorDraftsRef.current[conversationId]?.[draftId] || latestDraft.status !== 'WAITING_CONFIRMATION' || Date.parse(latestDraft.expiresAt) <= Date.now()) {
        setComposerNotice('这份方案已不能修改，请查看当前方案状态。未提交新的任务。')
        return
      }
      const requestText = taskDraftRequestText(latestDraft)
      if (requestText) {
        const editText = requestText.trimStart().startsWith('修改当前待确认方案：')
          ? requestText.trimStart() : `修改当前待确认方案：\n${requestText}`
        setDrafts((current) => (current[conversationId] ?? '') === currentInput
          ? { ...current, [conversationId]: editText } : current)
      }
    } catch {
      if (isCurrentAuthSession(session) && selectedSessionRef.current === conversationId) {
        setComposerNotice('暂时无法核对要修改的方案，请稍后重试。输入内容未变更。')
      }
    }
  }

  const handleSmartSend = async (content: string) => {
    const activeDraftId = conversation?.activeConfirmationDraftId ?? undefined
    if (isBareWorkflowConfirmation(content) && selectedSessionId && activeDraftId) {
      setComposerNotice('文字确认不会开始付费任务。请核对确认卡中的对象、素材和积分，再点击“确认开始”。')
      Array.from(document.querySelectorAll<HTMLElement>('[data-chat-draft-id]'))
        .find((card) => card.dataset.chatDraftId === activeDraftId)
        ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
      return
    }
    setComposerNotice(undefined)
    await handleSend(content)
  }

  const storeChatArticle = (nextArticle: Article) => {
    if (!selectedSessionId) return
    setResourcesByConversation((current) => ({
      ...current,
      [selectedSessionId]: Object.fromEntries(
        Object.entries(current[selectedSessionId] ?? {}).map(
          ([messageId, resource]) => {
            const resourceArticleId =
              resource.article?.id ??
              (resource.task?.taskType === 'ARTICLE' &&
              'articleId' in resource.task.result
                ? resource.task.result.articleId
                : undefined)
            return [
              messageId,
              resourceArticleId === nextArticle.id
                ? {
                    ...resource,
                    article: nextArticle,
                    mediaRecommendations:
                      resource.article?.version === nextArticle.version
                        ? resource.mediaRecommendations
                        : undefined,
                    mediaRecommendationError: undefined,
                  }
                : resource,
            ]
          },
        ),
      ),
    }))
  }

  const storeChatMediaRecommendationState = (
    articleId: string,
    mediaRecommendations?: ArticleMediaRecommendationSetView,
    mediaRecommendationError?: MessageResourceState['mediaRecommendationError'],
  ) => {
    if (!selectedSessionId) return
    setResourcesByConversation((current) => ({
      ...current,
      [selectedSessionId]: Object.fromEntries(
        Object.entries(current[selectedSessionId] ?? {}).map(
          ([messageId, resource]) => {
            const resourceArticleId =
              resource.article?.id ?? articleResultId(resource.task)
            return [
              messageId,
              resourceArticleId === articleId
                ? {
                    ...resource,
                    mediaRecommendations,
                    mediaRecommendationError,
                  }
                : resource,
            ]
          },
        ),
      ),
    }))
  }

  const refreshChatMediaRecommendations = async (articleId: string) => {
    try {
      const latestArticle = await getArticle(articleId)
      await clearStaleMediaQuote(ownerId, latestArticle)
      storeChatArticle(latestArticle)
      if (!articleCanRecommendMedia(latestArticle)) {
        storeChatMediaRecommendationState(articleId)
        return undefined
      }
      const recommendations = await listArticleMediaRecommendations(
        articleId,
        5,
      )
      const view = toArticleMediaRecommendationSet(recommendations)
      storeChatMediaRecommendationState(articleId, view)
      return recommendations
    } catch (error) {
      storeChatMediaRecommendationState(articleId, undefined, {
        message: errorMessage(error),
        conflict: isApiError(error) && error.status === 409,
        code: isApiError(error) ? error.code : undefined,
      })
      throw error
    }
  }

  const findArticleResourceEntry = (
    articleId: string,
    preferredMode?: ArticleStage,
  ) => {
    const entries = Object.entries(messageResources).filter(([, resource]) => {
      const draftParameters = articleTaskParameters(resource.draft)
      return (
        resource.article?.id === articleId ||
        articleResultId(resource.task) === articleId ||
        draftParameters?.articleId === articleId
      )
    })
    return (
      entries.find(([, resource]) => {
        if (!preferredMode || resource.task?.taskType !== 'ARTICLE') return false
        return (
          'articleId' in resource.task.result &&
          resource.task.result.mode === preferredMode
        )
      }) ??
      entries.find(
        ([, resource]) =>
          !preferredMode ||
          articleTaskParameters(resource.draft)?.mode === preferredMode,
      ) ??
      entries.at(-1)
    )
  }

  const prepareChatArticleTaskDraft = async (
    article: Article,
    mode: Extract<ArticleStage, 'TITLE' | 'OUTLINE' | 'BODY'>,
    preferredSourceMode: Extract<ArticleStage, 'TITLE' | 'OUTLINE'>,
  ) => {
    if (!selectedSessionId) {
      throw new Error('文章任务必须绑定到当前对话。')
    }
    const lockKey = `${article.id}:${article.version}:${mode}`
    if (articleDraftLock.current) return
    const sourceEntry = findArticleResourceEntry(
      article.id,
      preferredSourceMode,
    )
    const sourceParameters = Object.values(messageResources)
      .map((resource) => articleTaskParameters(resource.draft))
      .find((parameters) => parameters?.articleId === article.id)
    if (!sourceEntry || !sourceParameters) {
      throw new Error('未能恢复这篇文章的生成参数，请打开文章工作台后重试。')
    }
    articleDraftLock.current = lockKey
    try {
      const targetLength =
        mode === 'BODY' && article.creativePlan
          ? article.creativePlan.targetLength
          : sourceParameters.targetLength
      const form: ArticleTaskFormDraft = {
        mode,
        brief: sourceParameters.brief,
        tone:
          mode === 'BODY' && article.creativePlan
            ? article.creativePlan.tone
            : sourceParameters.tone,
        targetWordCount: targetWordCount(targetLength),
        rewriteInstruction: '',
        rewriteBlockIds: [],
        sourceAssetIds: [...sourceParameters.sourceAssetIds],
      }
      const draft = await createTaskDraft(
        toArticleTaskDraftRequest(
          selectedSessionId,
          toArticleWorkspaceRecord(article),
          form,
        ),
        newRequestId(),
        createBrowserTraceContext(),
      )
      const messageId = `${sourceEntry[0]}:article:${mode.toLowerCase()}:${draft.id}`
      const nextRecovery = upsertRecoveryDraft(
        recoveryRef.current,
        messageId,
        draft.id,
      )
      recoveryRef.current = nextRecovery
      storeChatRecoveryState(selectedSessionId, nextRecovery)
      setResourcesByConversation((current) => ({
        ...current,
        [selectedSessionId]: {
          ...current[selectedSessionId],
          [messageId]: {
            ...current[selectedSessionId]?.[messageId],
            draft,
            article,
          },
        },
      }))
      await pointAccountQuery.refetch()
    } finally {
      if (articleDraftLock.current === lockKey) {
        articleDraftLock.current = undefined
      }
    }
  }

  const handleConfirmChatArticleTitle = async (
    articleId: string,
    input: {
      title: string
      source: 'SELECTED' | 'EDITED' | 'CUSTOM'
      candidateIndex?: number
    },
  ) => {
    setActionError(undefined)
    try {
      const currentArticle = findArticleResourceEntry(articleId, 'TITLE')?.[1].article
      if (!currentArticle) throw new Error('这张标题卡片尚未恢复，请重新打开对话后重试。')
      if (!currentArticle.titleCandidateBatch) {
        throw new Error('文章标题候选状态尚未恢复，请打开文章工作台后重试。')
      }
      const confirmed = await confirmArticleTitle(articleId, {
        version: currentArticle.version,
        titleBatchVersion: currentArticle.titleCandidateBatch.version,
        title: input.title,
        source: input.source,
        candidateIndex: input.candidateIndex ?? null,
      })
      storeChatArticle(confirmed)
      await prepareChatArticleTaskDraft(confirmed, 'OUTLINE', 'TITLE')
    } catch (error) {
      setActionError(errorMessage(error))
    }
  }

  const handleConfirmChatCreativePlan = async (articleId: string) => {
    setActionError(undefined)
    try {
      const currentArticle = findArticleResourceEntry(articleId, 'OUTLINE')?.[1].article
      if (!currentArticle) throw new Error('这张方案卡片尚未恢复，请重新打开对话后重试。')
      if (!currentArticle.creativePlan) {
        throw new Error('文章创作方案尚未恢复，请打开文章工作台后重试。')
      }
      const confirmed = await confirmArticleCreativePlan(articleId, {
        version: currentArticle.version,
        planVersion: currentArticle.creativePlan.version,
      })
      storeChatArticle(confirmed)
      await prepareChatArticleTaskDraft(confirmed, 'BODY', 'OUTLINE')
    } catch (error) {
      setActionError(errorMessage(error))
    }
  }

  const handleConfirmChatArticleBody = async (
    articleId: string,
    input: ArticleBodyConfirmationInput,
  ) => {
    setActionError(undefined)
    try {
      const currentArticle = findArticleResourceEntry(articleId, 'BODY')?.[1].article
      if (!currentArticle) throw new Error('这张正文卡片尚未恢复，请重新打开对话后重试。')
      const confirmed = await confirmArticleBody(articleId, {
        version: currentArticle.version,
        goal: input.goal,
        preferredRegion: input.preferredRegion ?? null,
        maxTotalPoints: input.maxTotalPoints ?? null,
        requiresLink: input.requiresLink,
      })
      storeChatArticle(confirmed)
      await refreshChatMediaRecommendations(articleId)
    } catch (error) {
      setActionError(errorMessage(error))
    }
  }

  const handleRegenerateChatArticle = async (
    articleId: string,
    mode: Extract<ArticleStage, 'TITLE' | 'OUTLINE'>,
  ) => {
    setActionError(undefined)
    try {
      const article = await getArticle(articleId)
      storeChatArticle(article)
      await prepareChatArticleTaskDraft(article, mode, mode)
    } catch (error) {
      setActionError(errorMessage(error))
    }
  }

  const handleReviseChatCreativePlan = async (
    articleId: string,
    instruction: string,
  ) => {
    setActionError(undefined)
    try {
      const entry = findArticleResourceEntry(articleId, 'OUTLINE')
      if (!entry) {
        throw new Error('未能恢复这篇文章的对话上下文，请打开文章工作台后重试。')
      }
      const article = await getArticle(articleId)
      storeChatArticle(article)
      await handleSend(
        `请修改文章（ID：${article.id}，当前版本：V${article.version}）的创作方案，暂时不要生成正文。修改要求：${instruction}`,
        {
          replyToMessageId: entry[0],
          articleContext: {
            spaceId: article.spaceId,
            productId: article.productId ?? null,
          },
        },
      )
    } catch (error) {
      setActionError(errorMessage(error))
    }
  }

  const handleRecommendedChatSubmission = async (
    selection: ArticleRecommendedSubmissionSelection,
  ) => {
    if (
      !ownerId ||
      !selectedSessionId ||
      selection.mediaResourceIds.length === 0 ||
      recommendedSubmitLock.current
    ) {
      return
    }
    const sourceEntry = findArticleResourceEntry(selection.articleId, 'BODY')
    if (!sourceEntry) {
      setActionError('未能恢复这篇文章的对话结果，请打开文章工作台后重试。')
      return
    }

    recommendedSubmitLock.current = selection.articleId
    setRecommendedSubmittingArticleId(selection.articleId)
    setActionError(undefined)
    try {
      const submitted = await submitRecommendedMedia({
        selection,
        recovery: readMediaTaskRecovery(ownerId),
        onRecoveryChange: (next) => storeMediaTaskRecovery(ownerId, next),
      })
      storeChatArticle(submitted.article)
      storeChatMediaRecommendationState(
        selection.articleId,
        toArticleMediaRecommendationSet(submitted.recommendations),
      )
      const mediaMessageId = `${sourceEntry[0]}:media:${selection.articleId}`
      const nextChatRecovery = bindRecoveryTaskToSourceMessage(
        recoveryRef.current,
        mediaMessageId,
        submitted.accepted.taskId,
      )
      recoveryRef.current = nextChatRecovery
      storeChatRecoveryState(selectedSessionId, nextChatRecovery)
      await Promise.all([
        loadTaskResource(
          selectedSessionId,
          mediaMessageId,
          submitted.accepted.taskId,
        ),
        pointAccountQuery.refetch(),
      ])
      if (submitted.accepted.quotedPoints !== selection.expectedPoints) {
        setActionError(
          '投稿任务已创建，但服务端返回的任务积分与界面预期不一致，请立即查看任务详情。',
        )
      }
    } catch (error) {
      const recovery = readMediaTaskRecovery(ownerId)
      if (
        error instanceof RecommendedMediaSubmissionError &&
        error.code === 'MEDIA_TASK_IN_PROGRESS' &&
        recovery.confirmationAttempt?.taskId
      ) {
        const mediaMessageId = `${sourceEntry[0]}:media:${selection.articleId}`
        const next = bindRecoveryTaskToSourceMessage(
          recoveryRef.current,
          mediaMessageId,
          recovery.confirmationAttempt.taskId,
        )
        recoveryRef.current = next
        storeChatRecoveryState(selectedSessionId, next)
        await loadTaskResource(
          selectedSessionId,
          mediaMessageId,
          recovery.confirmationAttempt.taskId,
        ).catch(() => undefined)
      }
      if (
        error instanceof RecommendedMediaSubmissionError ||
        (isApiError(error) && error.status === 409)
      ) {
        await refreshChatMediaRecommendations(selection.articleId).catch(
          () => undefined,
        )
      }
      setActionError(errorMessage(error))
    } finally {
      if (recommendedSubmitLock.current === selection.articleId) {
        recommendedSubmitLock.current = undefined
      }
      setRecommendedSubmittingArticleId(undefined)
    }
  }

  const handlePrepareGeoArticle = async (
    sourceMessageId: string,
    reportId: string,
    expectedSource: { topicId: string; spaceId: string; productId?: string | null; observationId?: string; available?: boolean },
    direction?: string,
  ) => {
    if (!ownerId || !selectedSessionId || articleDraftLock.current || sendLock.current || isGenerating) return
    if (draft.trim() || currentAttachments.length > 0) {
      setComposerNotice('输入内容已保留。请先发送或清空输入，再引用报告写文章。')
      return
    }
    const originConversationId = selectedSessionId
    const requestSession = captureAuthSession()
    const epoch = geoActionEpoch.current
    const stillCurrent = () => isCurrentAuthSession(requestSession)
      && useAuthStore.getState().user?.id === ownerId
      && selectedSessionRef.current === originConversationId && geoActionEpoch.current === epoch
    if (expectedSource.observationId && expectedSource.available !== true) {
      setActionError('这份报告的创作来源暂不可用，请重新读取报告后继续。')
      return
    }
    if (reportProjectId && reportProjectId !== expectedSource.spaceId) {
      setActionError('这份报告属于其他项目，请切换到报告所属项目后再写文章。')
      return
    }
    const lockKey = `geo-article:${reportId}`
    articleDraftLock.current = lockKey
    setPreparingGeoArticleMessageId(sourceMessageId)
    setActionError(undefined)
    try {
      // Re-read this exact report; a frozen reply does not authorize an archived or changed source.
      const report = await getGeoReport(reportId)
      if (!stillCurrent()) return
      if (report.id !== reportId || report.topicId !== expectedSource.topicId
        || report.topic.spaceId !== expectedSource.spaceId
        || (report.topic.productId ?? null) !== (expectedSource.productId ?? null)) {
        setActionError('报告来源已变化，请查看原报告后重新选择。')
        return
      }
      const unavailableReason = geoArticleUnavailableReason(report)
      if (unavailableReason) { setActionError(unavailableReason); return }
      // Selecting the source only reads data and fills the composer. A normal
      // user send, not a hidden draft/grant write, starts any new article work.
      const selected = await geoReportSource.selectReport(reportId, expectedSource.spaceId)
      if (!selected || !isCurrentAuthSession(requestSession) || selectedSessionRef.current !== originConversationId) return
      const request = `根据这份监测报告，结合项目资料写一篇适合发布的完整品牌文章。${direction?.trim() ? `文章方向：${direction.trim()}。` : ''}缺少关键事实时先问我，写完后推荐适合的媒体，投稿前由我确认。`
      setDrafts((current) => current[originConversationId]?.trim() ? current : { ...current, [originConversationId]: request })
      setComposerNotice('报告已引用到当前对话。可以补充文章要求，发送后开始。')
    } catch (error) {
      if (!stillCurrent()) return
      setActionError(errorMessage(error))
    } finally {
      if (articleDraftLock.current === lockKey) articleDraftLock.current = undefined
      setPreparingGeoArticleMessageId((current) => current === sourceMessageId ? undefined : current)
    }
  }

  const handleTaskOutcomeAction = async (
    taskId: string,
    actionType: TaskOutcomeActionType,
  ) => {
    const sourceEntry = Object.entries(messageResources).find(
      ([, resource]) => resource.task?.id === taskId,
    )
    const sourceTask = sourceEntry?.[1].task
    const report = sourceEntry?.[1].geoReport
    if (actionType === 'VIEW_GEO_TREND' || actionType === 'VIEW_GEO_COMPETITORS') {
      if (!report || sourceTask?.taskType !== 'GEO' || !('reportId' in sourceTask.result) || sourceTask.result.reportId !== report.id) {
        setActionError('本次报告范围尚未加载完成，请先查看原报告。')
        return
      }
      navigate(actionType === 'VIEW_GEO_TREND' ? appLinks.geoTopicTrend(report.topicId)
        : appLinks.geoCompetitiveInsights(report.topicId, report.id))
      return
    }
    if (actionType === 'VIEW_COMPLETED_REPORT') {
      const reportId =
        report?.id ??
        (sourceTask?.taskType === 'GEO' && 'reportId' in sourceTask.result
          ? sourceTask.result.reportId
          : undefined)
      if (!reportId) {
        setActionError('监测报告尚未加载完成，请稍后再试。')
        return
      }
      navigate(appLinks.geoReportDetail(reportId))
      return
    }
    if (actionType !== 'CREATE_ARTICLE_FROM_GEO') return
    if (!ownerId || !selectedSessionId || !sourceEntry || !report) {
      setActionError('监测诊断尚未加载完成，请稍后再试。')
      return
    }
    if (sourceTask?.taskType !== 'GEO' || !('reportId' in sourceTask.result) || sourceTask.result.reportId !== report.id) return
    await handlePrepareGeoArticle(sourceEntry[0], report.id, {
      topicId: report.topicId, spaceId: report.topic.spaceId, productId: report.topic.productId,
    })
  }

  const refreshGeneratedVideo = useCallback(async (assetId: string) => {
    const download = await createAssetDownloadUrl(assetId)
    return { videoUrl: download.url, expiresAt: download.expiresAt }
  }, [])

  const workspaceError =
    actionError ??
    (conversationQuery.error ? errorMessage(conversationQuery.error) : undefined) ??
    (messagesQuery.error ? errorMessage(messagesQuery.error) : undefined) ??
    (projects.error ? errorMessage(projects.error) : undefined)

  const periodReportPreview = <PeriodReportPreview report={periodReports.report} loading={periodReports.loading} loadError={periodReports.loadError}
    actionError={periodReports.actionError} retrying={periodReports.retrying} downloading={periodReports.downloading}
    onRefresh={periodReports.refreshReport} onRetrySummary={() => void periodReports.retrySummary()}
    onDownload={() => void periodReports.downloadPdf()} onNewReport={periodReports.openForm} />

  return (
    <UserShell onSelectConversation={selectConversation} onRenameConversation={renameSession} onDeleteConversation={setDeletingSessionId}>
      <div className={`chat-page${(articleCanvasId || periodReports.reportOpen) && screens.xl ? ' chat-page--with-article' : ''}`}>
        <div className="chat-page__main">
        <GeoMascot
            key={`${ownerId}:${selectedSessionId ?? `new:${installationId}:${owningProjectId}`}`}
            enabled={(conversation?.agentIdentity?.agentKey ?? selectedInstallation?.agentKey) === 'geo'}
            messages={messages}
            backgroundWork={conversationTasks.filter((task) => task.taskType !== 'ARTICLE').map((task) => ({ id: task.id, updatedAt: task.updatedAt,
              status: ACTIVE_PAID_TASK_STATUSES.has(task.status) ? 'active' as const : task.status === 'SUCCEEDED' ? 'success' as const : 'other' as const }))}
            busy={isGenerating || sendMutation.isPending && sendMutation.variables?.conversationId === selectedSessionId
              || !selectedSessionId && createConversationMutation.isPending && createConversationMutation.variables?.request?.installationId === installationId
                && createConversationMutation.variables?.request?.projectId === owningProjectId}
            stopping={stopMutation.isPending && stopMutation.variables?.conversationId === selectedSessionId} failed={Boolean(sendError)}
            loading={Boolean(selectedSessionId && messagesQuery.isLoading)}>
        <ChatWorkspace
          renderGeoDiagnosticCard={(reference, messageId) => <Suspense fallback={<div role="status">正在读取诊断卡片…</div>}><GeoDiagnosticPagerContainer key={`${ownerId}:${reference.conversationId}:${reference.diagnosticId}`} reference={reference} messageId={messageId} /></Suspense>}
          onOpenArticlePreparation={openArticleCanvas}
          title={conversation?.title ?? (platformStart && selectedInstallation?.agentKey === 'geo' && !launchGuidance ? '从了解你的品牌开始' : platformStart ? '今天想完成什么？' : '新对话')}
          navigationInShell
          platformHome={platformStart}
          platformWelcomeTitle={creativePlatformWelcome?.title}
          platformWelcomeDescription={creativePlatformWelcome?.description}
          assistantAvatarVariant={(conversation?.agentIdentity?.agentKey ?? selectedInstallation?.agentKey) === 'creative' ? 'creative' : 'default'}
          emptyWelcome={platformStart && selectedInstallation?.agentKey === 'geo' && !launchGuidance
            ? <Suspense fallback={<div role="status">正在准备资料引导…</div>}><GeoOnboarding key={onboardingScope} project={selectedOwningProject}
              disabled={sendMutation.isPending || createConversationMutation.isPending}
              onUpload={() => { if (selectedOwningProject) setKnowledgePanel({ projectId: selectedOwningProject.id, add: 'file', onboardingScope }) }}
              onOpenKnowledge={() => setKnowledgePanel({ projectId: owningProjectId, onboardingScope })}
              onPrefill={prefillOnboarding} /></Suspense> : undefined}
          taskActions={<Space wrap>{ownerId && (conversation?.agentIdentity?.installationId && conversation.agentIdentity.capabilities.includes('geo.monitor')
            || platformStart && selectedInstallation?.enabled && selectedDefinition?.status === 'PUBLISHED' && selectedDefinition.capabilities.includes('geo.monitor') && !launchGuidance)
            ? <GeoMonitorAction key={`${ownerId}:${selectedSessionId ?? `new:${installationId ?? ''}:${owningProjectId ?? ''}`}`} resultOnly
              recoveryScope={selectedSessionId ?? `new:${installationId ?? ''}:${owningProjectId ?? ''}`}
              ownerId={ownerId} conversationId={selectedSessionId} projectId={conversation?.agentIdentity?.projectId ?? owningProjectId}
              restoredRunId={[...(messagesQuery.data ?? [])].reverse().find((item) => item.role === 'USER' && item.runId
                && typeof item.content.executionIntent === 'object' && item.content.executionIntent !== null
                && 'action' in item.content.executionIntent && item.content.executionIntent.action === 'MONITOR_RUN')?.runId ?? undefined}
              disabled={isGenerating || createConversationMutation.isPending || sendMutation.isPending}
              onEnsureConversation={async () => {
                if (selectedSessionId) return selectedSessionId
                if (!platformStart || launchGuidance || !newConversationRequest?.installationId) return undefined
                const session = captureAuthSession()
                const requestLocationKey = location.key
                const created = await createConversationMutation.mutateAsync({ request: newConversationRequest, browserTrace: createBrowserTraceContext() })
                return isCurrentAuthSession(session) && activeLocationKey.current === requestLocationKey ? created.id : undefined
              }}
              onSend={(executionIntent, targetConversationId) => handleSend('执行本次已选专题的单次监测。', { executionIntent, targetConversationId })}
              onAdmission={handleMonitorAdmission}
              onConfigureTarget={() => setKnowledgePanel({ projectId: conversation?.agentIdentity?.projectId ?? owningProjectId })}
              onViewTask={(taskId) => navigate(appLinks.taskDetail(taskId))} /> : null}
            {ownerId && selectedSessionId && conversation?.agentIdentity?.capabilities.includes('article.generate') ? <ArticleFullAction key={`article:${ownerId}:${selectedSessionId}`} ownerId={ownerId} conversationId={selectedSessionId} resultOnly
              projectId={conversation.agentIdentity.projectId ?? undefined} productId={selectedProductId} spaces={spaces} messages={messagesQuery.data ?? []} disabled={isGenerating || sendMutation.isPending || createConversationMutation.isPending || fullArticleInvocation.busy}
              onOpenResult={openArticleCanvas} /> : null}
            {fullArticleInvocation.notice ? <Typography.Text type="secondary">{fullArticleInvocation.notice}</Typography.Text> : null}
            {fullArticleInvocation.pending && !fullArticleInvocation.busy ? <Button size="small" onClick={() => void fullArticleInvocation.resume()}>恢复同一次全文请求</Button> : null}</Space>}
          identityControls={<TaskAgentControls existingIdentity={conversation?.agentIdentity} existingConversation={Boolean(selectedSessionId)} platformStart={platformStart}
            installations={agentInstallations.data ?? []} agents={agentCatalog.data ?? []} projects={projects.data ?? []}
            installationId={installationId} projectId={owningProjectId} loading={launchLoading} busy={createConversationMutation.isPending || sendMutation.isPending}
            error={launchError} guidance={launchGuidance}
            onRetry={() => void Promise.all([agentCatalog.refetch(), agentInstallations.refetch(), projects.refetch()])}
            onInstallationChange={(id) => changeLaunchSelection('installation', id)} onProjectChange={(id) => changeLaunchSelection('project', id)}
            onOpenLibrary={() => { const projectId = conversation?.agentIdentity?.projectId ?? owningProjectId; navigate(APP_PATHS.agents + (projectId ? '?' + new URLSearchParams({ project: projectId }) : '')) }} />}
          activeArticleWorkflow={activeArticleWorkflow}
          sessions={sessions}
          messages={messages}
          recommendations={CHAT_RECOMMENDATIONS.filter((item) => supportsCapability(({ 'monitor-performance': 'geo.read', 'create-images': 'image.generate', 'create-video': 'video.generate', 'write-article': 'article.generate' } as Record<string, string>)[item.id]))}
          preparingGeoArticleMessageId={preparingGeoArticleMessageId}
          geoArticleDirections={geoArticleDirections[selectedSessionId ?? 'new']}
          onGeoArticleDirectionChange={(messageId, value) => {
            const key = selectedSessionId ?? 'new'
            setGeoArticleDirections((current) => ({ ...current, [key]: { ...current[key], [messageId]: value } }))
          }}
          onGeoReportAction={(messageId, action) => {
            const context = messages.find((item) => item.id === messageId && item.role === 'ASSISTANT')?.geoContext
            const report = context?.status === 'READY' ? context.report : null
            if (!report) return
            if (action === 'VIEW_REPORT') { navigate(appLinks.geoReportDetail(report.reportId)); return }
            if (!report.canCreateArticle || report.articleUnavailableReason) return
            if (report.observationId && report.available !== true) return
            void handlePrepareGeoArticle(messageId, report.reportId, report, geoArticleDirections[selectedSessionId ?? 'new']?.[messageId])
          }}
          onOpenGeoTopic={(topicId, configureMonitoring) => navigate(topicId
            ? configureMonitoring ? `${APP_PATHS.geoManualMonitoring}?${new URLSearchParams({ topicId })}` : appLinks.geoTopicTrend(topicId)
            : APP_PATHS.geo)}
          geoReportSourceLabel={validGeoReportSource?.label}
          geoReportSourceLoading={geoReportSource.loading}
          geoReportSourceNotice={geoReportSource.notice}
          onRemoveGeoReportSource={clearGeoReportSource}
          onViewGeoReportSource={validGeoReportSource ? () => navigate(appLinks.geoReportDetail(validGeoReportSource.reportId)) : undefined}
          onCreatePeriodReport={ownerId && (!selectedSessionId || conversation) ? periodReports.openForm : undefined}
          onCreateDiagnosticReport={ownerId && !launchGuidance && (conversation?.agentIdentity?.agentKey ?? selectedInstallation?.agentKey) === 'geo'
            ? () => void diagnosticEntry.open() : undefined}
          onChooseGeoReport={supportsCapability('geo.read') ? () => {
            if (sendLock.current || isGenerating || createConversationMutation.isPending || sendMutation.isPending) {
              setComposerNotice('本轮回复结束后可以为下一条消息选择报告。')
              return
            }
            if (selectedSessionId && !conversation) {
              setComposerNotice('正在加载会话所属项目，请稍后再选择报告。')
              return
            }
            setReportPickerScope(reportPickerKey)
          } : undefined}
          selectedSessionId={selectedSessionId}
          selectedProductId={selectedProductId}
          taskFocusOptions={EMPTY_CHAT_TASK_FOCUS_OPTIONS}
          taskStatusSummaries={taskStatusSummaries}
          selectedTaskId={selectedTaskId}
          sessionSearchValue={sessionSearchValue}
          draft={draft}
          isGenerating={isGenerating}
          sendEnabled={
            !launchGuidance &&
            !createConversationMutation.isPending &&
            !sendMutation.isPending &&
            !isGenerating && !geoReportSource.loading && !geoReportSource.notice
          }
          currentAttachments={currentAttachments}
          onAddProjectDocument={() => setKnowledgePanel({ projectId: conversation?.agentIdentity?.projectId ?? owningProjectId, add: 'document' })}
          onAddProjectExcerpt={() => setKnowledgePanel({ projectId: conversation?.agentIdentity?.projectId ?? owningProjectId, add: 'excerpt' })}
          onOpenProjectKnowledge={() => setKnowledgePanel({ projectId: conversation?.agentIdentity?.projectId ?? owningProjectId })}
          attachmentUploadEnabled={Boolean(attachmentSpaceId || personalCreativeAssetsAllowed && creativeInstallationId)}
          sendFeedback={sendError}
          sessionLoading={conversationsQuery.isLoading}
          sessionError={
            conversationsQuery.error
              ? errorMessage(conversationsQuery.error)
              : undefined
          }
          messagesLoading={messagesQuery.isLoading}
          workspaceError={workspaceError}
          onNewConversation={() => void handleNewConversation()}
          onSessionSearchChange={setSessionSearchValue}
          onSelectSession={selectConversation}
          onRenameSession={renameSession}
          onDeleteSession={setDeletingSessionId}
          onSelectTaskFocus={setSelectedTaskId}
          onRecommendationSelect={(value, submitOnSelect) => {
            if (draft.trim() && draft.trim() !== value.trim()) {
              setComposerNotice('已保留你的输入，请先发送或清空，再使用推荐入口。')
              return
            }
            if (submitOnSelect) {
              if (draft.trim()) {
                setComposerNotice(`已保留你的输入，请先发送或清空输入。推荐目标：${value}`)
                return
              }
              if (currentAttachments.length > 0) {
                setComposerNotice('已保留本次图片，请先发送当前内容或移除图片。')
                return
              }
              void handleSmartSend(value)
              return
            }
            setDrafts((current) => ({ ...current, [draftKey]: value }))
          }}
          onDraftChange={(value) => {
            if (!value.trim()) setOnboardingDraftScope(undefined)
            setDrafts((current) => ({ ...current, [draftKey]: value }))
          }}
          onSend={(value) => void handleSmartSend(value)}
          onStop={() => void handleStop()}
          stopPending={stopMutation.isPending}
          onStopAndSend={(value) => {
            const conversationId = selectedSessionId
            const requestSession = captureAuthSession()
            void handleStop().then((stoppedRunId) => {
              if (stoppedRunId && conversationId === selectedSessionRef.current && isCurrentAuthSession(requestSession)) {
                void handleSend(value, { stoppedRunId })
              }
            })
          }}
          composerNotice={composerNotice}
          onRestorePendingMessage={pendingMessageInputs[draftKey] && sendError ? () => {
            const pending = pendingMessageInputs[draftKey]
            if (!pending) return
            if (pending.context?.businessSpaceId !== selectedBusinessSpaceId || pending.context?.productId !== selectedProductId) clearGeoReportSource()
            setDrafts((current) => ({ ...current, [draftKey]: pending.content }))
            updateCurrentAttachmentIds(draftKey, pending.attachmentIds)
            setContextSelections((current) => {
              const next = { ...current }
              if (pending.context) next[draftKey] = pending.context
              else delete next[draftKey]
              return next
            })
            setSendError(undefined)
            setComposerNotice('已恢复原消息和素材，尚未发送。再次点击发送会先核查受理结果，并沿用原请求编号。')
          } : undefined}
          hasEarlierMessages={messagesQuery.hasNextPage}
          earlierMessagesLoading={messagesQuery.isFetchingNextPage}
          onLoadEarlierMessages={() => void messagesQuery.fetchNextPage()}
          onChooseAttachmentAssets={
            attachmentSpaceId || personalCreativeAssetsAllowed && creativeInstallationId
              ? () => { void chooseAttachmentAssets() }
              : undefined
          }
          onSelectAttachmentFiles={(files) => void handleUploadAttachments(files)}
          onRemoveAttachment={(assetId) => {
            if ((attachmentUploadsByConversation[draftKey] ?? []).some(
              (upload) => upload.assetId === assetId,
            )) {
              setAttachmentUploadsByConversation((current) => ({
                ...current,
                [draftKey]: (current[draftKey] ?? []).filter(
                  (upload) => upload.assetId !== assetId,
                ),
              }))
              return
            }
            updateCurrentAttachmentIds(
              draftKey,
              currentAttachmentIds.filter((id) => id !== assetId),
            )
          }}
          onSelectClarificationOption={(messageId, optionId) => {
            const option = messages
              .find((message) => message.id === messageId)
              ?.clarificationOptions?.find((item) => item.id === optionId)
            if (option && !option.disabled) void handleSend(option.title, { replyToMessageId: messageId })
          }}
          resourceSelectionBusy={isGenerating || sendMutation.isPending}
          onSelectResourceCandidate={(messageId, candidateSetId, choiceId) => {
            const selection = messages.find((message) => message.id === messageId)?.resourceSelection
            const candidate = selection?.candidates.find((item) => item.choiceId === choiceId)
            if (!selectedSessionId || !selection?.available || selection.candidateSetId !== candidateSetId || !candidate) return
            void handleSend(candidate.name, { replyToMessageId: messageId, resourceSelection: { candidateSetId, choiceId } })
          }}
          onEditPaidTaskConfirmation={(cardId) => { void handleEditTask(cardId) }}
          onConfirmPaidTaskConfirmation={(cardId) =>
            void handleConfirmTask(cardId)
          }
          onOpenRecharge={() => navigate(APP_PATHS.pointsBilling)}
          onPrepareReestimate={(cardId) => { void handleEditTask(cardId) }}
          onOpenPaidTaskReferenceAsset={(_, assetId) => void openGeneratedImage(assetId)}
          onAddPaidTaskReferenceAsset={() => undefined}
          onViewTask={(taskId) => navigate(appLinks.taskDetail(taskId))}
          onPreviewArticle={(articleId) =>
            navigate(`${APP_PATHS.articles}?article=${encodeURIComponent(articleId)}`)
          }
          onContinueEditingArticle={(articleId) =>
            navigate(`${APP_PATHS.articles}?article=${encodeURIComponent(articleId)}`)
          }
          onSaveArticleToAssets={() => undefined}
          onConfirmArticleTitle={(articleId, input) =>
            void handleConfirmChatArticleTitle(articleId, input)
          }
          onRegenerateArticleTitles={(articleId) =>
            void handleRegenerateChatArticle(articleId, 'TITLE')
          }
          onReviseArticleCreativePlan={(articleId, instruction) =>
            void handleReviseChatCreativePlan(articleId, instruction)
          }
          onConfirmArticleCreativePlan={(articleId) =>
            void handleConfirmChatCreativePlan(articleId)
          }
          onRegenerateArticleCreativePlan={(articleId) =>
            void handleRegenerateChatArticle(articleId, 'OUTLINE')
          }
          onConfirmArticleBody={(articleId, input) =>
            void handleConfirmChatArticleBody(articleId, input)
          }
          recommendedSubmissionBusyArticleId={
            recommendedSubmittingArticleId
          }
          recommendedSubmissionBlockedArticleIds={
            recommendedSubmissionBlockedArticleIds
          }
          onConfirmRecommendedSubmission={(selection) =>
            void handleRecommendedChatSubmission(selection)
          }
          onRefreshMediaRecommendations={(articleId) => {
            setActionError(undefined)
            void refreshChatMediaRecommendations(articleId).catch((error) =>
              setActionError(errorMessage(error)),
            )
          }}
          onPreviewGeneratedImage={(assetId) =>
            void previewGeneratedImage(assetId)
          }
          onDownloadGeneratedImage={(assetId) =>
            void openGeneratedImage(assetId)
          }
          onSaveGeneratedImageToAssets={(assetId) =>
            navigate(`${APP_PATHS.assets}?asset=${encodeURIComponent(assetId)}`)
          }
          onSelectGeneratedImage={(assetId) => {
            if (!generatedAssetIds.includes(assetId)) return
            if (currentAttachmentIds.includes(assetId)) {
              setComposerNotice('这张图片已加入下一条消息，可在输入框上方移除。')
              return
            }
            if (currentAttachments.length >= 8) {
              setSendError('一次最多使用 8 张图片，请先移除不需要的素材。')
              return
            }
            updateCurrentAttachmentIds(draftKey, [...currentAttachmentIds, assetId])
            setComposerNotice('图片已加入下一条消息。请描述想怎样修改，再点击发送；不会自动生成或修改商品。')
          }}
          onOpenGeneratedVideo={(assetId) => void openGeneratedImage(assetId)}
          onRefreshGeneratedVideo={refreshGeneratedVideo}
          onTaskOutcomeAction={(taskId, actionType) =>
            void handleTaskOutcomeAction(taskId, actionType)
          }
          onToggleHistoricalContext={() => undefined}
          onOpenReferenceAsset={(assetId) => void openGeneratedImage(assetId)}
        />
        </GeoMascot>
        </div>
        {articleCanvasId && !periodReports.reportOpen && screens.xl ? <aside className="chat-article-canvas" aria-label="文章画布">
          <header className="chat-article-canvas__toolbar"><Typography.Text strong>文章</Typography.Text><Button type="text" icon={<CloseOutlined />} aria-label="关闭文章画布" onClick={() => openArticleCanvas(undefined)} /></header>
          <div className="chat-article-canvas__body chat-article-canvas__body--document"><ArticlePreparationContent key={`${articleCanvasScope}:${articleCanvasId}`} ownerId={ownerId} preparationId={articleCanvasId} layout="panel" /></div>
        </aside> : null}
        <Drawer className="chat-article-canvas-drawer chat-article-canvas-drawer--document" title="文章" open={Boolean(articleCanvasId && !periodReports.reportOpen && !screens.xl)} size="100%" destroyOnHidden onClose={() => openArticleCanvas(undefined)}>
          {articleCanvasId && !periodReports.reportOpen && !screens.xl ? <ArticlePreparationContent key={`${articleCanvasScope}:${articleCanvasId}`} ownerId={ownerId} preparationId={articleCanvasId} layout="panel" /> : null}
        </Drawer>
        {periodReports.reportOpen && screens.xl ? <aside className="chat-article-canvas" aria-label="客户报告画布">
          <header className="chat-article-canvas__toolbar"><Typography.Text strong>客户成果报告</Typography.Text><Button type="text" icon={<CloseOutlined />} aria-label="关闭客户报告画布" onClick={periodReports.closeReport} /></header>
          <div className="chat-article-canvas__body">{periodReportPreview}</div>
        </aside> : null}
        <Drawer className="chat-article-canvas-drawer" title="客户成果报告" open={periodReports.reportOpen && !screens.xl} size="100%" destroyOnHidden onClose={periodReports.closeReport}>
          {periodReports.reportOpen && !screens.xl ? periodReportPreview : null}
        </Drawer>
        {periodReports.formOpen ? <PeriodReportForm key={`${ownerId}:${selectedSessionId ?? ''}:${owningProjectId ?? ''}`}
          projects={periodReports.projects} defaultProjectId={conversation?.agentIdentity?.projectId ?? owningProjectId}
          projectsLoading={periodReports.projectsLoading} projectsError={periodReports.projectsError} onRetryProjects={periodReports.retryProjects}
          submitting={periodReports.submitting} error={periodReports.createError} onGenerate={(values) => void periodReports.generate(values)} onClose={periodReports.closeForm}
          history={periodReports.history} historyLoading={periodReports.historyLoading} historyError={periodReports.historyError}
          onProjectChange={periodReports.changeHistoryProject} onOpenSaved={periodReports.openSaved} onRetryHistory={periodReports.refreshHistory} /> : null}
        {reportPickerScope === reportPickerKey ? <GeoReportReferencePicker
          lockedProjectId={reportProjectId}
          selectedReportId={validGeoReportSource?.reportId}
          loading={geoReportSource.loading}
          notice={geoReportSource.notice}
          onClose={() => { geoReportSource.cancelSelection(); setReportPickerScope(undefined) }}
          onSelect={async (reportId, projectId) => {
            const requestLocationKey = location.key
            const attached = await geoReportSource.selectReport(reportId, projectId)
            if (attached && activeLocationKey.current === requestLocationKey) {
              setReportPickerScope(undefined)
              setSendError(undefined)
              setSearchParams((current) => {
                const next = new URLSearchParams(current); next.delete('geoReportId'); return next
              }, { replace: true })
            }
            return attached
          }}
        /> : null}
        <Drawer className="knowledge-library-drawer" title="品牌知识库" open={knowledgePanelVisible} size={960} destroyOnHidden onClose={() => {
          if (document.querySelector('[data-knowledge-busy="true"]')) { setComposerNotice('资料正在保存，请完成后再关闭。'); return }
          if (document.querySelector('[data-knowledge-dirty="true"]')) modal.confirm({ title: '关闭未保存的编辑？', content: '尚未保存的资料或偏好不会提交。聊天输入仍会保留。', okText: '放弃编辑', cancelText: '继续编辑', onOk: () => setKnowledgePanel(undefined) })
          else setKnowledgePanel(undefined)
        }}>
          {knowledgePanel && knowledgePanelVisible ? <Suspense fallback={<Spin />}><KnowledgeLibraryPanel key={`${ownerId ?? ''}:${knowledgePanel.projectId ?? ''}:${knowledgePanel.add ?? ''}`} initialProjectId={knowledgePanel.projectId} add={knowledgePanel.add}
            lockProject={Boolean(knowledgePanel.onboardingScope)}
            onUseDocument={knowledgePanel.onboardingScope ? (document) => {
              if (knowledgePanel.onboardingScope !== onboardingScope || document.projectId !== owningProjectId || document.status !== 'READY') return
              if (prefillOnboarding(`我已将资料《${document.name}》上传到本项目。请结合项目资料中的公司介绍、产品服务、真实优势和目标客户，梳理品牌信息并给我 GEO 优化建议；缺失或矛盾的信息再问我，不要补造事实。先不要启动监测、生成文章或投稿。`)) setKnowledgePanel(undefined)
            } : undefined} /></Suspense> : null}
        </Drawer>
        {attachmentSelectorOpen ? (
          <ImageAssetPickerDialog
            open
            title="选择本次要参考的图片"
            selectedAssetIds={currentAttachmentIds}
            businessSpaceId={attachmentSpaceId}
            maxSelection={8}
            onCancel={() => setAttachmentSelectorOpen(false)}
            onConfirm={(assetIds) => {
              updateCurrentAttachmentIds(draftKey, assetIds)
              setAttachmentSelectorOpen(false)
            }}
          />
        ) : null}
        {activeImagePreview ? (
          <GeneratedImagePreviewDialog
            key={activeImagePreview.assetId}
            name={generatedAssetDetails[activeImagePreview.assetId]?.name ?? '生成图片'}
            url={activeImagePreview.url}
            loading={activeImagePreview.loading}
            error={activeImagePreview.error}
            onRetry={() => void previewGeneratedImage(activeImagePreview.assetId)}
            onClose={closeImagePreview}
          />
        ) : null}
        <Modal
          title="重命名会话"
          open={Boolean(renamingSessionId)}
          okText="保存"
          cancelText="取消"
          confirmLoading={updateConversationMutation.isPending}
          okButtonProps={{ disabled: !renameValue.trim() }}
          onOk={() => void submitRename()}
          onCancel={() => setRenamingSessionId(undefined)}
          destroyOnHidden
        >
          <Input
            aria-label="会话名称"
            value={renameValue}
            maxLength={200}
            onChange={(event) => setRenameValue(event.target.value)}
          />
        </Modal>
        <Modal
          title="删除会话"
          open={Boolean(deletingSessionId)}
          okText="确认删除"
          okButtonProps={{ danger: true }}
          cancelText="取消"
          confirmLoading={deleteConversationMutation.isPending}
          onOk={() => void submitDelete()}
          onCancel={() => setDeletingSessionId(undefined)}
          destroyOnHidden
        >
          仅允许删除没有活动回答或付费任务的免费会话。删除后无法恢复。
        </Modal>
      </div>
    </UserShell>
  )
}

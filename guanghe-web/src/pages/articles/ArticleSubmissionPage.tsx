import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { isApiError } from '../../api/apiError'
import type { ArticleStatus as ApiArticleStatus } from '../../api/articleApi'
import { abandonTaskDraft, getPaidTask, getTaskDraft } from '../../api/conversationApi'
import type { PaidTask } from '../../api/conversationApi'
import { createMediaQuote } from '../../api/mediaApi'
import type { MediaSubmissionStatus as ApiMediaSubmissionStatus } from '../../api/mediaApi'
import { useAllAssets } from '../../features/assets'
import {
  ArticleSubmissionWorkspace,
  articleTaskResult,
  mediaDraftResourceIds,
  mediaKeys,
  mediaSubmissionNeedsPolling,
  mediaSubmissionPollPauseAt,
  mediaSubmissionPollingPaused,
  outlineInputs,
  readArticleTaskRecovery,
  readMediaTaskRecovery,
  storeArticleTaskRecovery,
  storeMediaTaskRecovery,
  toArticleAssetOption,
  toArticleCreativePlanInput,
  toArticleListItem,
  toArticleMediaRecommendationSet,
  toArticlePaidTaskConfirmation,
  toArticleTaskDraftRequest,
  toArticleTaskPresentation,
  toArticleWorkspaceRecord,
  toLiveMediaResource,
  toLiveMediaSubmission,
  toLiveMediaSubmissionDetail,
  toMediaPaidTaskConfirmation,
  toSubmissionSnapshotView,
  RecommendedMediaSubmissionError,
  submitRecommendedMedia,
  useArticle,
  useArticleMediaRecommendations,
  useArticleSnapshot,
  useArticles,
  useCreateArticle,
  useCreateArticleSnapshot,
  useConfirmArticleCreativePlan,
  useConfirmArticleBody,
  useConfirmArticleTitle,
  useDeleteArticle,
  useMediaResource,
  useMediaResources,
  useMediaResourcesByIds,
  useMediaSubmission,
  useMediaSubmissions,
  useReplaceArticleAssets,
  useReplaceArticleOutline,
  useSaveArticleCreativePlan,
  useUpdateArticle,
} from '../../features/articles'
import type {
  ArticleRecommendedSubmissionSelection,
  ArticleBodyConfirmationInput,
  ArticleOutlineDraftItem,
  ArticleCreativePlanDraft,
  ArticleTaskFormDraft,
  ArticleViewError,
  MediaQuoteSelectionView,
  MediaResourceFilterView,
} from '../../features/articles'
import { useAllBusinessSpaceOptions, useAllProductOptions } from '../../features/business-data'
import { usePointAccount } from '../../features/chat/live/chatQueries'
import {
  prepareFormTaskDraft,
  resolveFormTaskDraftConversation,
  submitFormTaskDraftConfirmation,
} from '../../features/task-draft'
import { UserShell } from '../../layouts/UserShell'
import { APP_PATHS, appLinks } from '../../routes/paths'
import { useAuthStore } from '../../stores/authStore'
import {
  EMPTY_ARTICLES,
  EMPTY_ARTICLE_OPTIONS,
  EMPTY_MEDIA_RESOURCES,
  EMPTY_SUBMISSIONS,
} from './pageDefaults'

const ARTICLE_CONVERSATION_TITLE = '文章创作'
const TERMINAL_TASK_STATUSES: readonly PaidTask['status'][] = [
  'SUCCEEDED',
  'PARTIAL',
  'FAILED',
  'SKIPPED',
  'SYSTEM_TERMINATED',
]

function terminalTask(task?: PaidTask): boolean {
  return Boolean(task && TERMINAL_TASK_STATUSES.includes(task.status))
}

function toViewError(
  error: unknown,
  fallback = '文章数据加载失败，请稍后重试。',
): ArticleViewError | null {
  if (!error) return null
  if (isApiError(error)) {
    const submissionMessage: Record<string, string> = {
      MEDIA_IMAGE_UNSUPPORTED:
        '当前媒体的图片发布规则尚未核验，或图片不满足渠道限制。请保留含图稿件和图片，待图文渠道开放后再投稿。',
      MEDIA_LINK_UNSUPPORTED:
        '所选媒体不支持文章中的外链。请移除正文外链，或选择支持外链的媒体后重新报价。',
    }
    return {
      message: submissionMessage[error.code] ?? error.message,
      traceId: error.traceId,
      conflict: error.status === 409,
      code: error.code,
    }
  }
  return { message: fallback }
}

function useArticleLocationState() {
  const [searchParams, setSearchParams] = useSearchParams()
  const selectedArticleId = searchParams.get('article') ?? undefined
  const selectedSnapshotId = searchParams.get('snapshot') ?? undefined
  const selectedMediaResourceId = searchParams.get('mediaResource') ?? undefined
  const selectedSubmissionId = searchParams.get('submission') ?? undefined
  const updateSearchParams = (next: URLSearchParams) => {
    if (next.toString() !== searchParams.toString()) {
      setSearchParams(next, { replace: true })
    }
  }
  const setSelectedArticleId = (articleId?: string) => {
    const next = new URLSearchParams(searchParams)
    if (articleId) next.set('article', articleId)
    else next.delete('article')
    next.delete('snapshot')
    next.delete('mediaResource')
    next.delete('submission')
    updateSearchParams(next)
  }
  const setSelectedSnapshotId = (snapshotId?: string) => {
    const next = new URLSearchParams(searchParams)
    if (snapshotId) next.set('snapshot', snapshotId)
    else next.delete('snapshot')
    updateSearchParams(next)
  }
  const setSelectedMediaResourceId = (resourceId?: string) => {
    const next = new URLSearchParams(searchParams)
    if (resourceId) next.set('mediaResource', resourceId)
    else next.delete('mediaResource')
    updateSearchParams(next)
  }
  const setSelectedSubmissionId = (submissionId?: string) => {
    const next = new URLSearchParams(searchParams)
    if (submissionId) next.set('submission', submissionId)
    else next.delete('submission')
    updateSearchParams(next)
  }
  const setMediaWorkflowSelection = (articleId: string, snapshotId: string) => {
    const next = new URLSearchParams(searchParams)
    next.set('article', articleId)
    next.set('snapshot', snapshotId)
    updateSearchParams(next)
  }
  return {
    selectedArticleId,
    selectedSnapshotId,
    setSelectedArticleId,
    setSelectedSnapshotId,
    selectedMediaResourceId,
    selectedSubmissionId,
    setSelectedMediaResourceId,
    setSelectedSubmissionId,
    setMediaWorkflowSelection,
  }
}

function LiveArticleSubmissionPage({ ownerId }: { ownerId: string }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {
    selectedArticleId,
    selectedSnapshotId,
    setSelectedArticleId,
    setSelectedSnapshotId,
    selectedMediaResourceId,
    selectedSubmissionId,
    setSelectedMediaResourceId,
    setSelectedSubmissionId,
    setMediaWorkflowSelection,
  } = useArticleLocationState()
  const [newArticleDraft, setNewArticleDraft] = useState<{
    spaceId?: string
    productId?: string
    title: string
  }>({ title: '' })
  const [explicitNewArticle, setExplicitNewArticle] = useState(false)
  const [articleFilter, setArticleFilter] = useState<{
    keyword: string
    status?: ApiArticleStatus
    spaceId?: string
  }>({ keyword: '' })
  const [articlePage, setArticlePage] = useState({ current: 1, pageSize: 20 })
  const [actionError, setActionError] = useState<ArticleViewError | null>(null)
  const [recovery, setRecovery] = useState(() => readArticleTaskRecovery(ownerId))
  const [preparing, setPreparing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [mediaRecovery, setMediaRecovery] = useState(() =>
    readMediaTaskRecovery(ownerId),
  )
  const [mediaResourceFilter, setMediaResourceFilter] =
    useState<MediaResourceFilterView>({ search: '' })
  const [mediaResourcePage, setMediaResourcePage] = useState({
    current: 1,
    pageSize: 20,
  })
  const [submissionStatus, setSubmissionStatus] =
    useState<ApiMediaSubmissionStatus>()
  const [submissionPage, setSubmissionPage] = useState({
    current: 1,
    pageSize: 20,
  })
  const [submissionPollingNow, setSubmissionPollingNow] = useState(() => Date.now())
  const [mediaPreparing, setMediaPreparing] = useState(false)
  const [mediaConfirming, setMediaConfirming] = useState(false)
  const [mediaQuoteStale, setMediaQuoteStale] = useState(false)
  const [recommendedSubmitting, setRecommendedSubmitting] = useState(false)
  const [recommendationError, setRecommendationError] =
    useState<ArticleViewError | null>(null)
  const prepareLock = useRef(false)
  const confirmLock = useRef(false)
  const mediaPrepareLock = useRef(false)
  const mediaConfirmLock = useRef(false)
  const workflowMutationLock = useRef(false)
  const recommendedSubmitLock = useRef(false)
  const staleMediaDraftCleanupRef = useRef<string | undefined>(undefined)
  const processedTaskId = useRef<string | undefined>(undefined)
  const refreshedTerminalSubmission = useRef<string | undefined>(undefined)

  const updateRecovery = (next: typeof recovery) => {
    setRecovery(next)
    storeArticleTaskRecovery(ownerId, next)
  }
  const updateMediaRecovery = (next: typeof mediaRecovery) => {
    setMediaRecovery(next)
    storeMediaTaskRecovery(ownerId, next)
  }

  const spacesQuery = useAllBusinessSpaceOptions()
  const articleQuery = useArticle(selectedArticleId)
  const article = articleQuery.data
  const recommendationReady = Boolean(
    article &&
      article.workflowStage === 'READY_TO_SUBMIT' &&
      article.bodyText.trim(),
  )
  const mediaRecommendationsQuery = useArticleMediaRecommendations(
    article?.id,
    article?.version,
    recommendationReady,
  )
  const effectiveSpaceId = article?.spaceId ?? newArticleDraft.spaceId
  const productsQuery = useAllProductOptions(effectiveSpaceId)
  const articlesQuery = useArticles({
    page: articlePage.current,
    size: articlePage.pageSize,
    keyword: articleFilter.keyword.trim() || undefined,
    status: articleFilter.status,
    spaceId: articleFilter.spaceId,
  })
  const snapshotQuery = useArticleSnapshot(
    selectedArticleId,
    selectedSnapshotId,
  )
  const assetsQuery = useAllAssets({
    type: 'IMAGE',
    spaceId: effectiveSpaceId,
  }, Boolean(effectiveSpaceId))
  const mediaResourcesQuery = useMediaResources({
    search: mediaResourceFilter.search.trim() || undefined,
    modelId: mediaResourceFilter.modelId,
    type: mediaResourceFilter.type,
    status: mediaResourceFilter.status,
    minSaleAmountCents: mediaResourceFilter.minSaleAmountCents,
    maxSaleAmountCents: mediaResourceFilter.maxSaleAmountCents,
    page: mediaResourcePage.current,
    size: mediaResourcePage.pageSize,
  })
  const selectedMediaResourceQuery = useMediaResource(selectedMediaResourceId)
  const submissionsQuery = useMediaSubmissions({
    status: submissionStatus,
    page: submissionPage.current,
    size: submissionPage.pageSize,
  }, !selectedSubmissionId)
  const selectedSubmissionQuery = useMediaSubmission(selectedSubmissionId)

  useEffect(() => {
    const pauseAt = mediaSubmissionPollPauseAt(selectedSubmissionQuery.data)
    if (pauseAt === undefined || pauseAt <= submissionPollingNow) return
    const remaining = Math.max(0, pauseAt - Date.now())
    const timer = window.setTimeout(
      () => setSubmissionPollingNow(Date.now()),
      Math.min(remaining, 2_147_483_647),
    )
    return () => window.clearTimeout(timer)
  }, [selectedSubmissionQuery.data, submissionPollingNow])
  const createMutation = useCreateArticle()
  const updateMutation = useUpdateArticle()
  const outlineMutation = useReplaceArticleOutline()
  const assetsMutation = useReplaceArticleAssets()
  const confirmTitleMutation = useConfirmArticleTitle()
  const saveCreativePlanMutation = useSaveArticleCreativePlan()
  const confirmCreativePlanMutation = useConfirmArticleCreativePlan()
  const confirmBodyMutation = useConfirmArticleBody()
  const deleteMutation = useDeleteArticle()
  const snapshotMutation = useCreateArticleSnapshot()

  const draftId = recovery.draftAttempt?.draftId
  const taskId = recovery.confirmationAttempt?.taskId
  const draftQuery = useQuery({
    queryKey: ['article-creation', ownerId ?? null, 'draft', draftId ?? null],
    queryFn: () => getTaskDraft(draftId as string),
    enabled: Boolean(draftId),
  })
  const taskQuery = useQuery({
    queryKey: ['article-creation', ownerId ?? null, 'task', taskId ?? null],
    queryFn: () => getPaidTask(taskId as string),
    enabled: Boolean(taskId),
    refetchInterval: (query) =>
      terminalTask(query.state.data) ? false : 5_000,
  })
  const mediaDraftId = mediaRecovery.draftAttempt?.draftId
  const mediaTaskId = mediaRecovery.confirmationAttempt?.taskId
  const mediaDraftQuery = useQuery({
    queryKey: ['media-submission', ownerId, 'draft', mediaDraftId ?? null],
    queryFn: () => getTaskDraft(mediaDraftId as string),
    enabled: Boolean(mediaDraftId),
  })
  const mediaTaskQuery = useQuery({
    queryKey: ['media-submission', ownerId, 'task', mediaTaskId ?? null],
    queryFn: () => getPaidTask(mediaTaskId as string),
    enabled: Boolean(mediaTaskId),
    refetchInterval: (query) =>
      terminalTask(query.state.data) ? false : 5_000,
  })
  const workflowArticleId =
    draftQuery.data?.taskType === 'ARTICLE' &&
    'articleId' in draftQuery.data.parameters
      ? draftQuery.data.parameters.articleId
      : undefined
  const workflowMatchesArticle = Boolean(
    selectedArticleId && workflowArticleId === selectedArticleId,
  )
  const activeDraft =
    !taskId && workflowMatchesArticle ? draftQuery.data : undefined
  const visibleTask = workflowMatchesArticle ? taskQuery.data : undefined
  const mediaDraftArticleId =
    mediaDraftQuery.data?.taskType === 'MEDIA' &&
    'articleId' in mediaDraftQuery.data.parameters
      ? mediaDraftQuery.data.parameters.articleId
      : undefined
  const mediaWorkflowMatchesArticle = Boolean(
    selectedArticleId && mediaDraftArticleId === selectedArticleId,
  )
  const activeMediaDraft =
    !mediaTaskId && mediaWorkflowMatchesArticle
      ? mediaDraftQuery.data
      : undefined
  const visibleMediaTask = mediaWorkflowMatchesArticle
    ? mediaTaskQuery.data
    : undefined
  const quotedResourceIds = mediaDraftResourceIds(activeMediaDraft)
  const quotedResourceQueries = useMediaResourcesByIds(quotedResourceIds)
  const pointAccountQuery = usePointAccount(
    Boolean(activeDraft || activeMediaDraft || visibleTask || visibleMediaTask),
  )

  useEffect(() => {
    const draft = draftQuery.data
    if (explicitNewArticle || selectedArticleId || !draft || draft.taskType !== 'ARTICLE') return
    if ('articleId' in draft.parameters) {
      setSelectedArticleId(draft.parameters.articleId)
    }
  }, [draftQuery.data, explicitNewArticle, selectedArticleId, setSelectedArticleId])

  useEffect(() => {
    const draft = mediaDraftQuery.data
    if (
      explicitNewArticle ||
      !draft ||
      draft.taskType !== 'MEDIA' ||
      !('articleSnapshotId' in draft.parameters)
    ) {
      return
    }
    if (!selectedArticleId || !selectedSnapshotId) {
      setMediaWorkflowSelection(
        draft.parameters.articleId,
        draft.parameters.articleSnapshotId,
      )
    }
  }, [
    mediaDraftQuery.data,
    explicitNewArticle,
    selectedArticleId,
    selectedSnapshotId,
    setMediaWorkflowSelection,
  ])

  useEffect(() => {
    const task = taskQuery.data
    if (!task || !terminalTask(task) || processedTaskId.current === task.id) return
    processedTaskId.current = task.id
    void queryClient.invalidateQueries({
      queryKey: ['articles', 'owner', ownerId ?? null],
    })
    void queryClient.invalidateQueries({
      queryKey: ['task-center', ownerId ?? null, 'list'],
    })
  }, [taskQuery.data, ownerId, queryClient])

  useEffect(() => {
    const task = mediaTaskQuery.data
    if (!task || !terminalTask(task) || processedTaskId.current === task.id) return
    processedTaskId.current = task.id
    void queryClient.invalidateQueries({
      queryKey: mediaKeys.forOwner(ownerId).submissionLists(),
    })
    void queryClient.invalidateQueries({
      queryKey: ['task-center', ownerId, 'list'],
    })
    void queryClient.invalidateQueries({
      queryKey: ['chat', ownerId, 'point-account'],
    })
  }, [mediaTaskQuery.data, ownerId, queryClient])

  useEffect(() => {
    const submission = selectedSubmissionQuery.data
    if (!submission || mediaSubmissionNeedsPolling(submission.status)) return
    const refreshKey = `${submission.id}:${submission.updatedAt}`
    if (refreshedTerminalSubmission.current === refreshKey) return
    refreshedTerminalSubmission.current = refreshKey
    void queryClient.invalidateQueries({
      queryKey: mediaKeys.forOwner(ownerId).submissionLists(),
    })
  }, [selectedSubmissionQuery.data, ownerId, queryClient])

  const businessSpaces = useMemo(
    () =>
      (spacesQuery.data ?? []).map((space) => ({
        label: space.name,
        value: space.id,
      })),
    [spacesQuery.data],
  )
  const products = useMemo(
    () =>
      (productsQuery.data ?? []).map((product) => ({
        label: product.name,
        value: product.id,
      })),
    [productsQuery.data],
  )
  const articleItems = useMemo(
    () => (articlesQuery.data?.items ?? []).map(toArticleListItem),
    [articlesQuery.data?.items],
  )
  const articleView = article ? toArticleWorkspaceRecord(article) : undefined
  const mediaRecommendations = useMemo(
    () =>
      mediaRecommendationsQuery.data
        ? toArticleMediaRecommendationSet(mediaRecommendationsQuery.data)
        : undefined,
    [mediaRecommendationsQuery.data],
  )
  const createdSnapshot = snapshotMutation.data
  const currentSnapshot =
    snapshotQuery.data ??
    (createdSnapshot?.articleId === selectedArticleId
      ? createdSnapshot
      : undefined)
  const snapshotView = currentSnapshot
    ? toSubmissionSnapshotView(currentSnapshot)
    : undefined
  const mediaResources = useMemo(
    () => (mediaResourcesQuery.data?.items ?? []).map(toLiveMediaResource),
    [mediaResourcesQuery.data?.items],
  )
  const selectedMediaResource = selectedMediaResourceQuery.data
    ? toLiveMediaResource(selectedMediaResourceQuery.data)
    : undefined
  const quotedResources = quotedResourceQueries
    .map((query) => query.data)
    .filter((resource) => resource !== undefined)
    .map(toLiveMediaResource)
  const mediaSubmissions = useMemo(
    () => (submissionsQuery.data?.items ?? []).map(toLiveMediaSubmission),
    [submissionsQuery.data?.items],
  )
  const selectedSubmission = selectedSubmissionQuery.data
    ? toLiveMediaSubmissionDetail(selectedSubmissionQuery.data)
    : undefined
  const quotedSelection: MediaQuoteSelectionView | undefined =
    activeMediaDraft?.taskType === 'MEDIA' &&
    'items' in activeMediaDraft.parameters
      ? {
          mediaResourceIds: activeMediaDraft.parameters.items.map(
            (item) => item.mediaResourceId,
          ),
          remark: activeMediaDraft.parameters.items[0]?.remark ?? '',
        }
      : undefined
  const taskResult = articleTaskResult(visibleTask)
  const taskPresentation = toArticleTaskPresentation(
    visibleTask,
    pointAccountQuery.data,
  )
  const generatedOutline = articleView?.creativePlan
    ? undefined
    : taskResult?.outline.map((item) => ({
        key: item.key,
        level: item.level,
        heading: item.heading,
        brief: item.brief ?? undefined,
      }))
  const mediaTaskPresentation = toArticleTaskPresentation(
    visibleMediaTask,
    pointAccountQuery.data,
  )
  const mediaConfirmation = toMediaPaidTaskConfirmation(
    activeMediaDraft,
    pointAccountQuery.data,
    snapshotView,
    quotedResources,
    mediaConfirming,
    mediaQuoteStale,
    mediaRecommendationsQuery.data?.submissionEnabled === true,
  )

  useEffect(() => {
    if (
      !article ||
      !currentSnapshot ||
      currentSnapshot.articleId !== article.id ||
      currentSnapshot.articleVersion === article.version ||
      activeMediaDraft?.status !== 'WAITING_CONFIRMATION'
    ) {
      return
    }
    const draftSnapshotId =
      activeMediaDraft.taskType === 'MEDIA' &&
      'articleSnapshotId' in activeMediaDraft.parameters
        ? activeMediaDraft.parameters.articleSnapshotId
        : undefined
    if (draftSnapshotId !== currentSnapshot.id) return
    if (staleMediaDraftCleanupRef.current === activeMediaDraft.id) return
    staleMediaDraftCleanupRef.current = activeMediaDraft.id

    setRecommendationError(null)
    setMediaQuoteStale(false)
    setSelectedSnapshotId(undefined)
    void abandonTaskDraft(activeMediaDraft.id)
      .catch(() => undefined)
      .finally(() => {
        setMediaRecovery({})
        storeMediaTaskRecovery(ownerId, {})
      })
  }, [
    activeMediaDraft,
    article,
    currentSnapshot,
    ownerId,
    setSelectedSnapshotId,
  ])
  const mutationBusy = [
    createMutation,
    updateMutation,
    outlineMutation,
    assetsMutation,
    confirmTitleMutation,
    saveCreativePlanMutation,
    confirmCreativePlanMutation,
    deleteMutation,
    snapshotMutation,
  ].some((mutation) => mutation.isPending)
  const busy =
    mutationBusy ||
    preparing ||
    confirming ||
    mediaPreparing ||
    mediaConfirming ||
    recommendedSubmitting
  const viewError =
    actionError ??
    toViewError(
      spacesQuery.error ?? productsQuery.error,
      '经营资料加载失败，请稍后重试。',
    ) ??
    toViewError(articlesQuery.error ?? articleQuery.error) ??
    toViewError(snapshotQuery.error, '投稿快照加载失败，请稍后重试。') ??
    toViewError(assetsQuery.error, '图片资产加载失败，请稍后重试。') ??
    toViewError(draftQuery.error, '文章任务草稿加载失败，请稍后重试。') ??
    toViewError(taskQuery.error, '文章任务状态加载失败，请稍后重试。')
  const mediaViewError =
    actionError ??
    toViewError(mediaResourcesQuery.error, '媒体资源加载失败，请稍后重试。') ??
    toViewError(selectedMediaResourceQuery.error, '媒体资源详情加载失败。') ??
    toViewError(submissionsQuery.error, '投稿记录加载失败，请稍后重试。') ??
    toViewError(selectedSubmissionQuery.error, '投稿详情加载失败。') ??
    toViewError(mediaDraftQuery.error, '媒体投稿报价加载失败。') ??
    toViewError(mediaTaskQuery.error, '媒体投稿任务状态加载失败。') ??
    toViewError(
      quotedResourceQueries.find((query) => query.error)?.error,
      '报价媒体资源校验失败。',
    )
  const mediaRecommendationViewError =
    recommendationError ??
    toViewError(
      mediaRecommendationsQuery.error,
      '推荐媒体加载失败，请稍后重试。',
    )

  const runMutation = async (action: () => Promise<unknown>) => {
    setActionError(null)
    try {
      await action()
    } catch (error) {
      setActionError(toViewError(error))
    }
  }

  const handlePrepareTask = async (
    form: ArticleTaskFormDraft,
    targetArticle = articleView,
  ) => {
    if (!targetArticle || prepareLock.current) return
    prepareLock.current = true
    setPreparing(true)
    setActionError(null)
    try {
      const differentArticleWorkflow = Boolean(
        workflowArticleId && workflowArticleId !== targetArticle.id,
      )
      if (
        differentArticleWorkflow &&
        taskQuery.data &&
        !terminalTask(taskQuery.data)
      ) {
        setActionError({
          message: '另一篇文章仍有任务正在执行，请等待任务结束后再创建新任务。',
          conflict: true,
        })
        return
      }
      if (
        differentArticleWorkflow &&
        draftQuery.data?.status === 'WAITING_CONFIRMATION'
      ) {
        await abandonTaskDraft(draftQuery.data.id)
      }
      const baseRecovery =
        terminalTask(taskQuery.data) || differentArticleWorkflow
          ? { conversationId: recovery.conversationId }
          : recovery
      if (baseRecovery !== recovery) updateRecovery(baseRecovery)
      const resolved = await resolveFormTaskDraftConversation({
        recovery: baseRecovery,
        title: ARTICLE_CONVERSATION_TITLE,
        onRecoveryChange: updateRecovery,
      })
      const request = toArticleTaskDraftRequest(
        resolved.conversationId,
        targetArticle,
        form,
      )
      const prepared = await prepareFormTaskDraft({
        recovery: resolved.recovery,
        request,
        currentDraft: activeDraft,
        onRecoveryChange: updateRecovery,
      })
      queryClient.setQueryData(
        ['article-creation', ownerId, 'draft', prepared.draft.id],
        prepared.draft,
      )
    } catch (error) {
      setActionError(toViewError(error))
    } finally {
      setPreparing(false)
      prepareLock.current = false
    }
  }

  const handleConfirmTask = async (confirmationId: string) => {
    const draft = draftQuery.data
    if (
      !draft ||
      draft.id !== confirmationId ||
      !draft.confirmationToken ||
      confirmLock.current
    ) {
      return
    }
    confirmLock.current = true
    setConfirming(true)
    setActionError(null)
    try {
      await submitFormTaskDraftConfirmation({
        recovery,
        draft,
        onRecoveryChange: updateRecovery,
      })
      await queryClient.invalidateQueries({
        queryKey: ['task-center', ownerId, 'list'],
      })
    } catch (error) {
      setActionError(toViewError(error))
    } finally {
      setConfirming(false)
      confirmLock.current = false
    }
  }

  const handleEditTask = async () => {
    if (!activeDraft || activeDraft.status !== 'WAITING_CONFIRMATION') return
    await runMutation(async () => {
      await abandonTaskDraft(activeDraft.id)
      updateRecovery({ conversationId: recovery.conversationId })
    })
  }

  const handleConfirmTitle = async (
    input: {
      title: string
      source: 'SELECTED' | 'EDITED' | 'CUSTOM'
      candidateIndex?: number
    },
    nextTask: ArticleTaskFormDraft,
  ) => {
    if (
      !article ||
      !article.titleCandidateBatch ||
      workflowMutationLock.current
    ) {
      return
    }
    workflowMutationLock.current = true
    setActionError(null)
    try {
      const confirmed = await confirmTitleMutation.mutateAsync({
        articleId: article.id,
        request: {
          version: article.version,
          titleBatchVersion: article.titleCandidateBatch.version,
          title: input.title,
          source: input.source,
          candidateIndex: input.candidateIndex ?? null,
        },
      })
      await handlePrepareTask(nextTask, toArticleWorkspaceRecord(confirmed))
    } catch (error) {
      setActionError(toViewError(error, '标题确认失败，请稍后重试。'))
    } finally {
      workflowMutationLock.current = false
    }
  }

  const handleSaveCreativePlan = async (plan: ArticleCreativePlanDraft) => {
    if (!article?.creativePlan || workflowMutationLock.current) return
    workflowMutationLock.current = true
    setActionError(null)
    try {
      await saveCreativePlanMutation.mutateAsync({
        articleId: article.id,
        request: {
          version: article.version,
          basePlanVersion: article.creativePlan.version,
          plan: toArticleCreativePlanInput(plan),
        },
      })
    } catch (error) {
      setActionError(toViewError(error, '创作方案保存失败，请稍后重试。'))
    } finally {
      workflowMutationLock.current = false
    }
  }

  const handleConfirmCreativePlan = async (
    plan: ArticleCreativePlanDraft,
    nextTask: ArticleTaskFormDraft,
  ) => {
    if (!article?.creativePlan || workflowMutationLock.current) return
    workflowMutationLock.current = true
    setActionError(null)
    try {
      const editedPlan = toArticleCreativePlanInput(plan)
      const currentPlanView = toArticleWorkspaceRecord(article).creativePlan
      if (!currentPlanView) return
      const persistedPlan = toArticleCreativePlanInput(currentPlanView)
      const articleToConfirm =
        JSON.stringify(editedPlan) === JSON.stringify(persistedPlan)
          ? article
          : await saveCreativePlanMutation.mutateAsync({
              articleId: article.id,
              request: {
                version: article.version,
                basePlanVersion: article.creativePlan.version,
                plan: editedPlan,
              },
            })
      if (!articleToConfirm.creativePlan) return
      const confirmed = await confirmCreativePlanMutation.mutateAsync({
        articleId: articleToConfirm.id,
        request: {
          version: articleToConfirm.version,
          planVersion: articleToConfirm.creativePlan.version,
        },
      })
      await handlePrepareTask(nextTask, toArticleWorkspaceRecord(confirmed))
    } catch (error) {
      setActionError(toViewError(error, '创作方案确认失败，请稍后重试。'))
    } finally {
      workflowMutationLock.current = false
    }
  }

  const handleConfirmBody = async (input: ArticleBodyConfirmationInput) => {
    if (!article || workflowMutationLock.current) return
    workflowMutationLock.current = true
    setActionError(null)
    setRecommendationError(null)
    try {
      await confirmBodyMutation.mutateAsync({
        articleId: article.id,
        request: {
          version: article.version,
          goal: input.goal,
          preferredRegion: input.preferredRegion ?? null,
          maxTotalPoints: input.maxTotalPoints ?? null,
          requiresLink: input.requiresLink,
        },
      })
      await queryClient.invalidateQueries({
        queryKey: [...mediaKeys.forOwner(ownerId).owner, 'recommendations'],
      })
    } catch (error) {
      setActionError(toViewError(error, '正文确认失败，请核对当前版本后重试。'))
    } finally {
      workflowMutationLock.current = false
    }
  }

  const handlePrepareMediaQuote = async (
    selection: MediaQuoteSelectionView,
  ) => {
    if (
      !articleView ||
      !snapshotView ||
      selection.mediaResourceIds.length === 0 ||
      mediaPrepareLock.current
    ) {
      return
    }
    mediaPrepareLock.current = true
    setMediaPreparing(true)
    setMediaQuoteStale(false)
    setActionError(null)
    try {
      const differentArticleWorkflow = Boolean(
        mediaDraftArticleId && mediaDraftArticleId !== articleView.id,
      )
      if (
        differentArticleWorkflow &&
        mediaTaskQuery.data &&
        !terminalTask(mediaTaskQuery.data)
      ) {
        setActionError({
          message: '另一篇文章仍有投稿任务正在执行，请等待任务结束后再提交。',
          conflict: true,
        })
        return
      }
      if (
        differentArticleWorkflow &&
        mediaDraftQuery.data?.status === 'WAITING_CONFIRMATION'
      ) {
        await abandonTaskDraft(mediaDraftQuery.data.id)
      }
      const baseRecovery =
        terminalTask(mediaTaskQuery.data) || differentArticleWorkflow
          ? {}
          : mediaRecovery
      if (baseRecovery !== mediaRecovery) updateMediaRecovery(baseRecovery)
      const request = {
        articleId: articleView.id,
        articleSnapshotId: snapshotView.id,
        items: selection.mediaResourceIds.map((mediaResourceId) => ({
          mediaResourceId,
          remark: selection.remark.trim() || null,
        })),
      }
      const prepared = await prepareFormTaskDraft({
        recovery: baseRecovery,
        request,
        currentDraft: activeMediaDraft,
        onRecoveryChange: updateMediaRecovery,
        createDraft: createMediaQuote,
      })
      queryClient.setQueryData(
        ['media-submission', ownerId, 'draft', prepared.draft.id],
        prepared.draft,
      )
    } catch (error) {
      setActionError(toViewError(error, '媒体投稿报价失败，请稍后重试。'))
    } finally {
      setMediaPreparing(false)
      mediaPrepareLock.current = false
    }
  }

  const handleConfirmMediaQuote = async (draftId: string) => {
    const draft = mediaDraftQuery.data
    if (
      !draft ||
      draft.id !== draftId ||
      draft.taskType !== 'MEDIA' ||
      !draft.confirmationToken ||
      mediaConfirmLock.current ||
      mediaQuoteStale
    ) {
      return
    }
    mediaConfirmLock.current = true
    setMediaConfirming(true)
    setActionError(null)
    try {
      await submitFormTaskDraftConfirmation({
        recovery: mediaRecovery,
        draft,
        onRecoveryChange: updateMediaRecovery,
      })
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['task-center', ownerId, 'list'],
        }),
        queryClient.invalidateQueries({
          queryKey: mediaKeys.forOwner(ownerId).submissionLists(),
        }),
        queryClient.invalidateQueries({
          queryKey: ['chat', ownerId, 'point-account'],
        }),
      ])
    } catch (error) {
      const nextError = toViewError(error, '媒体投稿确认失败，请稍后重试。')
      if (nextError?.code === 'MEDIA_QUOTE_CHANGED') setMediaQuoteStale(true)
      setActionError(nextError)
    } finally {
      setMediaConfirming(false)
      mediaConfirmLock.current = false
    }
  }

  const handleRecommendedSubmission = async (
    selection: ArticleRecommendedSubmissionSelection,
  ) => {
    if (
      !article ||
      selection.articleId !== article.id ||
      selection.mediaResourceIds.length === 0 ||
      recommendedSubmitLock.current
    ) {
      return
    }

    recommendedSubmitLock.current = true
    setRecommendedSubmitting(true)
    setRecommendationError(null)
    setMediaQuoteStale(false)
    try {
      const submitted = await submitRecommendedMedia({
        selection,
        recovery: mediaRecovery,
        currentSnapshot,
        currentDraft: activeMediaDraft,
        currentTask: mediaTaskQuery.data,
        onRecoveryChange: updateMediaRecovery,
        dependencies: {
          loadArticle: async () => {
            const result = await articleQuery.refetch()
            if (result.data) return result.data
            throw result.error ?? new Error('文章刷新失败。')
          },
          loadRecommendations: async () => {
            const result = await mediaRecommendationsQuery.refetch()
            if (result.data) return result.data
            throw result.error ?? new Error('推荐媒体刷新失败。')
          },
          createSnapshot: (articleId, articleVersion) =>
            snapshotMutation.mutateAsync({ articleId, articleVersion }),
        },
      })
      setSelectedSnapshotId(submitted.snapshot.id)
      queryClient.setQueryData(
        ['media-submission', ownerId, 'draft', submitted.draft.id],
        submitted.draft,
      )
      if (submitted.accepted.quotedPoints !== selection.expectedPoints) {
        setRecommendationError({
          message: '投稿任务已创建，但服务端返回的任务积分与界面预期不一致，请立即查看任务详情。',
          code: 'MEDIA_CONFIRMATION_QUOTE_MISMATCH',
          conflict: true,
        })
      }
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['task-center', ownerId, 'list'],
        }),
        queryClient.invalidateQueries({
          queryKey: mediaKeys.forOwner(ownerId).submissionLists(),
        }),
        queryClient.invalidateQueries({
          queryKey: ['chat', ownerId, 'point-account'],
        }),
      ])
    } catch (error) {
      const nextError =
        error instanceof RecommendedMediaSubmissionError
          ? {
              message: error.message,
              code: error.code,
              conflict: error.conflict,
            }
          : toViewError(
              error,
              '推荐媒体投稿失败，尚未确认的任务不会冻结积分。请稍后重试。',
            )
      if (
        nextError?.code === 'MEDIA_QUOTE_CHANGED' ||
        nextError?.code === 'ARTICLE_VERSION_CONFLICT'
      ) {
        setMediaQuoteStale(true)
      }
      if (nextError?.conflict) {
        void articleQuery.refetch()
        void queryClient.invalidateQueries({
          queryKey: [...mediaKeys.forOwner(ownerId).owner, 'recommendations'],
        })
      }
      setRecommendationError(nextError)
    } finally {
      setRecommendedSubmitting(false)
      recommendedSubmitLock.current = false
    }
  }

  const handleEditMediaQuote = async () => {
    if (!activeMediaDraft || activeMediaDraft.status !== 'WAITING_CONFIRMATION') {
      return
    }
    await runMutation(async () => {
      await abandonTaskDraft(activeMediaDraft.id)
      updateMediaRecovery({})
      setMediaQuoteStale(false)
    })
  }

  const retryMedia = () => {
    setActionError(null)
    setRecommendationError(null)
    void mediaResourcesQuery.refetch()
    if (recommendationReady) void mediaRecommendationsQuery.refetch()
    void submissionsQuery.refetch()
    if (selectedMediaResourceId) void selectedMediaResourceQuery.refetch()
    if (selectedSubmissionId) void selectedSubmissionQuery.refetch()
    if (mediaDraftId) void mediaDraftQuery.refetch()
    if (mediaTaskId) void mediaTaskQuery.refetch()
  }

  return (
    <UserShell>
      <ArticleSubmissionWorkspace
        businessSpaces={EMPTY_ARTICLE_OPTIONS}
        products={EMPTY_ARTICLE_OPTIONS}
        articles={EMPTY_ARTICLES}
        mediaResources={EMPTY_MEDIA_RESOURCES}
        submissions={EMPTY_SUBMISSIONS}
        live={{
          businessSpaces,
          products,
          articles: articleItems,
          article: articleView,
          selectedArticleId,
          newArticleDraft,
          onNewArticleDraftChange: setNewArticleDraft,
          assets: (assetsQuery.data ?? []).map(toArticleAssetOption),
          snapshot: snapshotView,
          articleFilter,
          articlePagination: {
            current: articlesQuery.data?.page ?? articlePage.current,
            pageSize: articlesQuery.data?.size ?? articlePage.pageSize,
            total: articlesQuery.data?.total ?? 0,
          },
          loading:
            spacesQuery.isLoading ||
            articlesQuery.isLoading ||
            articleQuery.isLoading,
          busy,
          error: viewError,
          paidTaskConfirmation: toArticlePaidTaskConfirmation(
            activeDraft,
            pointAccountQuery.data,
            confirming,
          ),
          taskReceipt: taskPresentation.receipt,
          taskProgress: taskPresentation.progress,
          taskOutcome: taskPresentation.outcome,
          workflowStage: articleView?.workflowStage,
          titleCandidateBatch: articleView?.titleCandidateBatch,
          creativePlan: articleView?.creativePlan,
          titleCandidates: taskResult?.titleCandidates,
          generatedOutline,
          onSelectArticle: (articleId) => {
            setExplicitNewArticle(articleId === undefined)
            if (!articleId) {
              setNewArticleDraft({ spaceId: article?.spaceId ?? newArticleDraft.spaceId, title: '' })
            }
            setSelectedArticleId(articleId)
          },
          onBusinessSpaceChange: (spaceId) => {
            setNewArticleDraft((current) => current.spaceId === spaceId
              ? current
              : { ...current, spaceId, productId: undefined })
          },
          onArticleFilterChange: (filter) => {
            setArticleFilter({
              keyword: filter.keyword,
              status: filter.status as ApiArticleStatus | undefined,
              spaceId: filter.spaceId,
            })
            setArticlePage((current) => ({ ...current, current: 1 }))
          },
          onArticlePageChange: (current, pageSize) =>
            setArticlePage({ current, pageSize }),
          onCreateArticle: (input) =>
            void runMutation(async () => {
              const created = await createMutation.mutateAsync(input)
              setExplicitNewArticle(false)
              setNewArticleDraft({ spaceId: created.spaceId, title: '' })
              setSelectedArticleId(created.id)
            }),
          onSaveArticle: (input) =>
            article &&
            void runMutation(() =>
              updateMutation.mutateAsync({
                articleId: article.id,
                request: {
                  version: article.version,
                  title: input.title,
                  bodyHtml: input.bodyHtml,
                },
              }),
            ),
          onSaveOutline: (outline) =>
            article &&
            void runMutation(() =>
              outlineMutation.mutateAsync({
                articleId: article.id,
                request: {
                  version: article.version,
                  items: outlineInputs(outline),
                },
              }),
            ),
          onSaveAssets: (assetIds) =>
            article &&
            void runMutation(() =>
              assetsMutation.mutateAsync({
                articleId: article.id,
                request: {
                  version: article.version,
                  items: assetIds.map((assetId, index) => ({
                    assetId,
                    positionKey: `article-end-${index + 1}`,
                    sortOrder: index + 1,
                    altText: null,
                  })),
                },
              }),
            ),
          onDeleteArticle: (articleId) =>
            void runMutation(async () => {
              await deleteMutation.mutateAsync(articleId)
              if (articleId === selectedArticleId) {
                setExplicitNewArticle(true)
                setSelectedArticleId(undefined)
              }
            }),
          onPrepareTask: (draft) => void handlePrepareTask(draft),
          onConfirmTask: (id) => void handleConfirmTask(id),
          onEditTask: () => void handleEditTask(),
          onUseTitleCandidate: (title) =>
            article &&
            void runMutation(() =>
              updateMutation.mutateAsync({
                articleId: article.id,
                request: { version: article.version, title },
              }),
            ),
          onUseGeneratedOutline: (
            outline: readonly ArticleOutlineDraftItem[],
          ) =>
            article &&
            void runMutation(() =>
              outlineMutation.mutateAsync({
                articleId: article.id,
                request: {
                  version: article.version,
                  items: outlineInputs(outline),
                },
              }),
            ),
          onConfirmTitle: (input, nextTask) =>
            void handleConfirmTitle(input, nextTask),
          onRegenerateTitle: (draft) => void handlePrepareTask(draft),
          onSaveCreativePlan: (plan) => void handleSaveCreativePlan(plan),
          onConfirmCreativePlan: (plan, nextTask) =>
            void handleConfirmCreativePlan(plan, nextTask),
          onRegenerateCreativePlan: (draft) => void handlePrepareTask(draft),
          onConfirmBody: (input) => void handleConfirmBody(input),
          onCreateSnapshot: () =>
            article &&
            void runMutation(async () => {
              const snapshot = await snapshotMutation.mutateAsync({
                articleId: article.id,
                articleVersion: article.version,
              })
              setSelectedSnapshotId(snapshot.id)
            }),
          onReloadArticle: () => {
            setActionError(null)
            void articleQuery.refetch()
            void articlesQuery.refetch()
          },
          onViewTask: (id) => navigate(appLinks.taskDetail(id)),
          onTaskAction: (id, type) => {
            if (type === 'VIEW_COMPLETED_REPORT') {
              navigate(appLinks.taskDetail(id))
            }
          },
          onOpenRecharge: () => navigate(APP_PATHS.pointsBilling),
          mediaRecommendations,
          loadingMediaRecommendations:
            mediaRecommendationsQuery.isLoading ||
            mediaRecommendationsQuery.isFetching,
          submittingRecommendedMedia:
            recommendedSubmitting,
          recommendedSubmissionBlocked: Boolean(
            mediaTaskId &&
              !(
                isApiError(mediaTaskQuery.error) &&
                mediaTaskQuery.error.status === 404
              ) &&
              (!mediaTaskQuery.data || !terminalTask(mediaTaskQuery.data)),
          ),
          mediaRecommendationError: mediaRecommendationViewError,
          onConfirmRecommendedSubmission: (selection) =>
            void handleRecommendedSubmission(selection),
          onRefreshMediaRecommendations: () => {
            setRecommendationError(null)
            void mediaRecommendationsQuery.refetch()
          },
          mediaSubmission: {
            resources: mediaResources,
            selectedResource: selectedMediaResource,
            resourceDetailOpen: Boolean(selectedMediaResourceId),
            resourceModels: Array.from(
              new Map(
                mediaResources.map((resource) => [
                  resource.modelId,
                  { label: resource.modelId, value: resource.modelId },
                ]),
              ).values(),
            ),
            resourceTypes: Array.from(
              new Map(
                mediaResources
                  .filter((resource) => resource.resourceType)
                  .map((resource) => [
                    resource.resourceType as string,
                    {
                      label: resource.resourceType as string,
                      value: resource.resourceType as string,
                    },
                  ]),
              ).values(),
            ),
            resourceFilter: mediaResourceFilter,
            resourcePagination: {
              current: mediaResourcesQuery.data?.page ?? mediaResourcePage.current,
              pageSize: mediaResourcesQuery.data?.size ?? mediaResourcePage.pageSize,
              total: mediaResourcesQuery.data?.total ?? 0,
            },
            submissions: mediaSubmissions,
            submissionStatus,
            submissionPagination: {
              current: submissionsQuery.data?.page ?? submissionPage.current,
              pageSize: submissionsQuery.data?.size ?? submissionPage.pageSize,
              total: submissionsQuery.data?.total ?? 0,
            },
            selectedSubmission,
            submissionDetailOpen: Boolean(selectedSubmissionId),
            submissionPollingPaused: mediaSubmissionPollingPaused(
              selectedSubmissionQuery.data,
              submissionPollingNow,
            ),
            quotedSelection,
            loadingResources: mediaResourcesQuery.isLoading,
            loadingResourceDetail: selectedMediaResourceQuery.isLoading,
            loadingSubmissions: submissionsQuery.isLoading,
            refreshingSubmissions: submissionsQuery.isFetching,
            loadingSubmissionDetail: selectedSubmissionQuery.isLoading,
            refreshingSubmission: selectedSubmissionQuery.isFetching,
            preparing: mediaPreparing,
            busy,
            error: mediaViewError,
            paidTaskConfirmation: mediaConfirmation,
            taskReceipt: mediaTaskPresentation.receipt,
            taskProgress: mediaTaskPresentation.progress,
            taskOutcome: mediaTaskPresentation.outcome,
            onCreateSnapshot: () =>
              article &&
              void runMutation(async () => {
                const nextSnapshot = await snapshotMutation.mutateAsync({
                  articleId: article.id,
                  articleVersion: article.version,
                })
                setSelectedSnapshotId(nextSnapshot.id)
              }),
            onResourceFilterChange: (filter) => {
              setMediaResourceFilter(filter)
              setMediaResourcePage((current) => ({ ...current, current: 1 }))
            },
            onResourcePageChange: (current, pageSize) =>
              setMediaResourcePage({ current, pageSize }),
            onOpenResource: setSelectedMediaResourceId,
            onPrepareQuote: (selection) => void handlePrepareMediaQuote(selection),
            onEditQuote: () => void handleEditMediaQuote(),
            onConfirmQuote: (id) => void handleConfirmMediaQuote(id),
            onOpenRecharge: () => navigate(APP_PATHS.pointsBilling),
            onSubmissionStatusChange: (status) => {
              setSubmissionStatus(status)
              setSubmissionPage((current) => ({ ...current, current: 1 }))
            },
            onSubmissionPageChange: (current, pageSize) =>
              setSubmissionPage({ current, pageSize }),
            onRefreshSubmissions: () => {
              void submissionsQuery.refetch()
              if (selectedSubmissionId) void selectedSubmissionQuery.refetch()
            },
            onOpenSubmission: setSelectedSubmissionId,
            onRefreshSubmission: (submissionId) => {
              if (submissionId === selectedSubmissionId) {
                void selectedSubmissionQuery.refetch()
              }
            },
            onViewTask: (id) => navigate(appLinks.taskDetail(id)),
            onTaskAction: (id, type) => {
              if (type === 'VIEW_COMPLETED_REPORT') {
                navigate(appLinks.taskDetail(id))
              }
            },
            onRetry: retryMedia,
          },
        }}
      />
    </UserShell>
  )
}

export function ArticleSubmissionPage() {
  const ownerId = useAuthStore((state) => state.user?.id)

  if (ownerId) {
    return <LiveArticleSubmissionPage key={ownerId} ownerId={ownerId} />
  }

  return (
    <UserShell>
      <ArticleSubmissionWorkspace
        businessSpaces={EMPTY_ARTICLE_OPTIONS}
        products={EMPTY_ARTICLE_OPTIONS}
        articles={EMPTY_ARTICLES}
        mediaResources={EMPTY_MEDIA_RESOURCES}
        submissions={EMPTY_SUBMISSIONS}
      />
    </UserShell>
  )
}

import {
  createArticleSubmissionSnapshot,
  getArticle,
  getArticleSubmissionSnapshot,
} from '../../../api/articleApi'
import { isApiError } from '../../../api/apiError'
import type {
  Article,
  ArticleSubmissionSnapshot,
} from '../../../api/articleApi'
import {
  abandonTaskDraft,
  getPaidTask,
  getTaskDraft,
} from '../../../api/conversationApi'
import type {
  ConfirmTaskResponse,
  PaidTask,
  TaskDraft,
} from '../../../api/conversationApi'
import {
  createMediaQuote,
  listArticleMediaRecommendations,
} from '../../../api/mediaApi'
import type { MediaRecommendationSet } from '../../../api/mediaApi'
import {
  prepareFormTaskDraft,
  submitFormTaskDraftConfirmation,
} from '../../task-draft'
import type { FormTaskDraftRecoveryState } from '../../task-draft'
import type { ArticleRecommendedSubmissionSelection } from '../types'
import { mediaArticleHasImages, mediaArticleHasLinks, mediaResourceSupportsLinks, mediaResourceSupportsImages, mediaArticlePricing, mediaPricingEquals } from './mediaContentPolicy'

const READY_STAGES: readonly Article['workflowStage'][] = [
  'BODY_READY',
  'EDITING',
  'READY_TO_SUBMIT',
]
const TERMINAL_TASK_STATUSES: readonly PaidTask['status'][] = [
  'SUCCEEDED',
  'PARTIAL',
  'FAILED',
  'SKIPPED',
  'SYSTEM_TERMINATED',
]

export class RecommendedMediaSubmissionError extends Error {
  readonly code: string
  readonly conflict: boolean

  constructor(message: string, code: string, conflict = true) {
    super(message)
    this.name = 'RecommendedMediaSubmissionError'
    this.code = code
    this.conflict = conflict
  }
}

interface RecommendedMediaSubmissionDependencies {
  loadArticle: (articleId: string) => Promise<Article>
  loadRecommendations: (articleId: string) => Promise<MediaRecommendationSet>
  createSnapshot: (
    articleId: string,
    articleVersion: number,
  ) => Promise<ArticleSubmissionSnapshot>
  loadSnapshot: (
    articleId: string,
    snapshotId: string,
  ) => Promise<ArticleSubmissionSnapshot>
}

export interface SubmitRecommendedMediaOptions {
  selection: ArticleRecommendedSubmissionSelection
  recovery: FormTaskDraftRecoveryState
  currentSnapshot?: ArticleSubmissionSnapshot
  currentDraft?: TaskDraft
  currentTask?: PaidTask
  onRecoveryChange: (next: FormTaskDraftRecoveryState) => void
  isCurrent?: () => boolean
  dependencies?: Partial<RecommendedMediaSubmissionDependencies>
}

export interface SubmitRecommendedMediaResult {
  accepted: ConfirmTaskResponse
  article: Article
  recommendations: MediaRecommendationSet
  snapshot: ArticleSubmissionSnapshot
  draft: TaskDraft
  recovery: FormTaskDraftRecoveryState
}

function terminalTask(task?: PaidTask): boolean {
  return Boolean(task && TERMINAL_TASK_STATUSES.includes(task.status))
}

function mediaDraftResourceIds(draft?: TaskDraft): string[] {
  return draft?.taskType === 'MEDIA' && 'items' in draft.parameters
    ? draft.parameters.items.map((item) => item.mediaResourceId)
    : []
}

function mediaDraftSnapshotId(draft?: TaskDraft): string | undefined {
  return draft?.taskType === 'MEDIA' &&
    'articleSnapshotId' in draft.parameters
    ? draft.parameters.articleSnapshotId
    : undefined
}

function resourceIsOrderable(
  resource: MediaRecommendationSet['items'][number]['resource'],
  now = Date.now(),
): boolean {
  return (
    resource.status === 'ACTIVE' &&
    Number.isSafeInteger(resource.saleAmountCents) &&
    resource.saleAmountCents >= 0 &&
    Number.isSafeInteger(resource.chargedPoints) &&
    resource.chargedPoints >= 0 &&
    Number.isFinite(Date.parse(resource.quoteExpiresAt)) &&
    Date.parse(resource.quoteExpiresAt) > now
  )
}

function assertArticleReady(
  article: Article,
  selection: ArticleRecommendedSubmissionSelection,
) {
  if (
    article.id !== selection.articleId ||
    article.version !== selection.articleVersion ||
    !READY_STAGES.includes(article.workflowStage) ||
    !article.bodyText.trim()
  ) {
    throw new RecommendedMediaSubmissionError(
      '正文或工作流状态已经变化，已停止投稿。请查看最新正文后再次确认。',
      'ARTICLE_VERSION_CONFLICT',
    )
  }
}

function assertRecommendationsCurrent(
  recommendations: MediaRecommendationSet,
  selection: ArticleRecommendedSubmissionSelection,
): string[] {
  if (
    recommendations.articleId !== selection.articleId ||
    recommendations.articleVersion !== selection.articleVersion
  ) {
    throw new RecommendedMediaSubmissionError(
      '推荐对应的正文版本已经变化，请刷新后再次确认。',
      'ARTICLE_VERSION_CONFLICT',
    )
  }
  const selectedIds = [...new Set(selection.mediaResourceIds)]
  const resourcesById = new Map(
    recommendations.items.map((item) => [item.mediaResourceId, item.resource]),
  )
  const selectedResources = selectedIds.map((id) => resourcesById.get(id))
  const expectedPoints = selectedResources.reduce(
    (total, resource) => total + (resource?.chargedPoints ?? 0),
    0,
  )
  if (
    selectedIds.length === 0 ||
    selectedIds.length !== selection.mediaResourceIds.length ||
    selectedResources.some(
      (resource) => !resource || !resourceIsOrderable(resource),
    ) ||
    !Number.isSafeInteger(expectedPoints) ||
    expectedPoints !== selection.expectedPoints
  ) {
    throw new RecommendedMediaSubmissionError(
      '媒体状态或预计积分已经变化，已停止投稿。请核对刷新后的清单与总积分。',
      'MEDIA_QUOTE_CHANGED',
    )
  }
  if (selection.expectedPricing) {
    const expected = new Map(selection.expectedPricing.map((item) => [item.mediaResourceId, item.pricing]))
    const mismatch = expected.size !== selectedIds.length || selection.expectedPricing.length !== selectedIds.length || selectedResources.some((resource) => {
      if (!resource) return true
      const current = mediaArticlePricing(resource.capabilities.articlePricing)
      const previous = expected.get(resource.id)
      return !current || !previous || !mediaPricingEquals(current, previous) || current.totalPoints !== resource.chargedPoints ||
        current.totalSaleAmountCents !== resource.saleAmountCents
    })
    if (mismatch) throw new RecommendedMediaSubmissionError(
      '本稿图片数量、图片费用或总价已变化，已停止投稿。请刷新推荐并核对费用后再确认。', 'MEDIA_QUOTE_CHANGED')
  }
  return selectedIds
}

async function abandonWaitingDraft(
  draft: TaskDraft | undefined,
  onRecoveryChange: (next: FormTaskDraftRecoveryState) => void,
) {
  if (draft?.status === 'WAITING_CONFIRMATION') {
    await abandonTaskDraft(draft.id)
  }
  onRecoveryChange({})
}

export async function submitRecommendedMedia({
  selection,
  recovery,
  currentSnapshot,
  currentDraft,
  currentTask,
  onRecoveryChange,
  dependencies,
  isCurrent,
}: SubmitRecommendedMediaOptions): Promise<SubmitRecommendedMediaResult> {
  const assertCurrent = () => {
    if (isCurrent && !isCurrent()) throw new RecommendedMediaSubmissionError('登录或页面状态已改变，已停止投稿。请回到原任务核对状态。', 'MEDIA_SCOPE_CHANGED', false)
  }
  assertCurrent()
  const loadArticle = dependencies?.loadArticle ?? getArticle
  const loadRecommendations =
    dependencies?.loadRecommendations ?? listArticleMediaRecommendations
  const createSnapshot =
    dependencies?.createSnapshot ??
    ((articleId: string, articleVersion: number) =>
      createArticleSubmissionSnapshot(articleId, { articleVersion }))
  const loadSnapshot = dependencies?.loadSnapshot ?? getArticleSubmissionSnapshot

  let nextRecovery = recovery
  const persistRecovery = (next: FormTaskDraftRecoveryState) => {
    nextRecovery = next
    onRecoveryChange(next)
  }

  const taskId = recovery.confirmationAttempt?.taskId
  let recoveredTask: PaidTask | undefined
  if (taskId) {
    try {
      recoveredTask =
        currentTask?.id === taskId ? currentTask : await getPaidTask(taskId)
    } catch (error) {
      if (!isApiError(error) || error.status !== 404) throw error
      persistRecovery({})
    }
  }
  if (recoveredTask && !terminalTask(recoveredTask)) {
    throw new RecommendedMediaSubmissionError(
      '已有投稿任务正在执行，请先查看该任务状态，避免重复投稿。',
      'MEDIA_TASK_IN_PROGRESS',
    )
  }
  if (recoveredTask && terminalTask(recoveredTask)) {
    persistRecovery({})
  }

  const article = await loadArticle(selection.articleId)
  assertCurrent()
  assertArticleReady(article, selection)
  const recommendations = await loadRecommendations(selection.articleId)
  assertCurrent()
  const selectedIds = assertRecommendationsCurrent(recommendations, selection)
  if (mediaArticleHasImages({ bodyHtml: article.bodyHtml, assetCount: article.assets.length }) && selectedIds.some((id) => {
    const selected = recommendations.items.find((item) => item.mediaResourceId === id)
    const pricing = mediaArticlePricing(selected?.resource.capabilities.articlePricing)
    return !selected || article.assets.length === 0 || !selection.expectedPricing ||
      !mediaResourceSupportsImages(selected.resource.capabilities, article.assets.length) ||
      !pricing || pricing.imageCount !== article.assets.length || pricing.imageFeeMode === 'NONE'
  })) {
    throw new RecommendedMediaSubmissionError(
      '所选媒体尚未明确本稿图片费用、图片规则未核验或超过数量限制。请保留含图稿件并刷新推荐；本次未创建订单。',
      'MEDIA_IMAGE_UNSUPPORTED',
      false,
    )
  }
  if (mediaArticleHasLinks(article.bodyHtml) && selectedIds.some((id) => {
    const selected = recommendations.items.find((item) => item.mediaResourceId === id)
    return selected && !mediaResourceSupportsLinks(selected.resource.capabilities)
  })) {
    throw new RecommendedMediaSubmissionError(
      '所选媒体不支持文章中的外链。请改选“可带外链”的媒体，或移除正文外链后刷新推荐。',
      'MEDIA_LINK_UNSUPPORTED',
      false,
    )
  }

  const draftId = nextRecovery.draftAttempt?.draftId
  let recoveredDraft: TaskDraft | undefined
  if (draftId) {
    try {
      recoveredDraft =
        currentDraft?.id === draftId ? currentDraft : await getTaskDraft(draftId)
    } catch (error) {
      if (!isApiError(error) || error.status !== 404) throw error
      persistRecovery({})
    }
  }
  if (recoveredDraft?.status === 'CONFIRMED') {
    throw new RecommendedMediaSubmissionError(
      '上一次投稿确认结果仍在恢复中。为避免重复投稿，请先到任务中心核对状态。',
      'MEDIA_CONFIRMATION_RECOVERY_PENDING',
    )
  }

  const recoveredIds = mediaDraftResourceIds(recoveredDraft)
  const recoveredSelectionMatches =
    recoveredDraft?.status === 'WAITING_CONFIRMATION' &&
    recoveredIds.length === selectedIds.length &&
    recoveredIds.every((id, index) => id === selectedIds[index])
  let snapshot =
    currentSnapshot?.articleId === article.id &&
    currentSnapshot.articleVersion === article.version
      ? currentSnapshot
      : undefined
  const recoveredSnapshotId = mediaDraftSnapshotId(recoveredDraft)
  if (!snapshot && recoveredSnapshotId && recoveredSelectionMatches) {
    try {
      const candidate = await loadSnapshot(article.id, recoveredSnapshotId)
      if (candidate.articleVersion === article.version) snapshot = candidate
    } catch (error) {
      if (!isApiError(error) || error.status !== 404) throw error
      // A missing recovery snapshot cannot be reused; the current article
      // version is snapshotted again below.
    }
  }
  assertCurrent()
  if (!snapshot) snapshot = await createSnapshot(article.id, article.version)
  assertCurrent()

  const recoveredDraftMatches =
    recoveredSelectionMatches && recoveredSnapshotId === snapshot.id
  if (recoveredDraft?.status === 'WAITING_CONFIRMATION' && !recoveredDraftMatches) {
    await abandonWaitingDraft(recoveredDraft, persistRecovery)
    recoveredDraft = undefined
  }

  const prepared = await prepareFormTaskDraft({
    recovery: nextRecovery,
    request: {
      articleId: article.id,
      articleSnapshotId: snapshot.id,
      items: selectedIds.map((mediaResourceId) => ({
        mediaResourceId,
        remark: null,
      })),
    },
    currentDraft: recoveredDraftMatches ? recoveredDraft : undefined,
    onRecoveryChange: persistRecovery,
    createDraft: (request, idempotencyKey) => { assertCurrent(); return createMediaQuote(request, idempotencyKey) },
  })
  assertCurrent()

  const beforeConfirmation = await loadArticle(article.id)
  assertCurrent()
  if (beforeConfirmation.version !== selection.articleVersion) {
    await abandonWaitingDraft(prepared.draft, persistRecovery)
    throw new RecommendedMediaSubmissionError(
      '生成最终报价期间正文已经变化，已停止投稿。请查看最新正文后再次确认。',
      'ARTICLE_VERSION_CONFLICT',
    )
  }
  if (prepared.draft.quotedPoints !== selection.expectedPoints) {
    await abandonWaitingDraft(prepared.draft, persistRecovery)
    throw new RecommendedMediaSubmissionError(
      `最终报价为 ${prepared.draft.quotedPoints} 积分，与刚才确认的 ${selection.expectedPoints} 积分不一致，已停止投稿。`,
      'MEDIA_QUOTE_CHANGED',
    )
  }
  if (selection.expectedPricing) {
    const finalItems = prepared.draft.taskType === 'MEDIA' && 'items' in prepared.draft.parameters
      ? prepared.draft.parameters.items : []
    const pricingChanged = finalItems.length !== selection.expectedPricing.length || selection.expectedPricing.some((expected) => {
      const item = finalItems.find((target) => target.mediaResourceId === expected.mediaResourceId)
      const finalPricing = mediaArticlePricing(item && 'pricing' in item ? item.pricing : undefined)
      return !finalPricing || !mediaPricingEquals(expected.pricing, finalPricing)
    })
    if (pricingChanged) {
      await abandonWaitingDraft(prepared.draft, persistRecovery)
      throw new RecommendedMediaSubmissionError('最终报价的图片费用或总价已变化，已停止投稿。请核对最新费用后再次确认。', 'MEDIA_QUOTE_CHANGED')
    }
  }

  const submitted = await submitFormTaskDraftConfirmation({
    recovery: prepared.recovery,
    draft: prepared.draft,
    onRecoveryChange: persistRecovery,
  })
  return {
    accepted: submitted.accepted,
    article: beforeConfirmation,
    recommendations,
    snapshot,
    draft: prepared.draft,
    recovery: submitted.recovery,
  }
}

import type {
  MediaRecommendationSet,
  MediaResource,
  MediaSubmissionDetail,
  MediaSubmissionSummary,
} from '../../../api/mediaApi'
import type {
  PaidTask,
  PointAccount,
  TaskDraft,
} from '../../../api/conversationApi'
import { toPaidTaskConfirmation } from '../../chat/live/chatAdapters'
import type { PaidTaskConfirmationView } from '../../chat/types'
import type {
  ArticleMediaRecommendationSetView,
  LiveMediaResourceView,
  LiveMediaSubmissionDetailView,
  LiveMediaSubmissionListItemView,
  LiveMediaSubmissionStatus,
  SubmissionSnapshotView,
} from '../types'
import { taskFailureMessage } from '../../task-common/taskFailurePresentation'
import { formatArticleDateTime } from './articleAdapters'
import { mediaArticleHasImages, mediaArticleHasLinks, mediaResourceSupportsLinks, mediaResourceSupportsImages, mediaArticlePricing, mediaImageFeeModeLabel } from './mediaContentPolicy'

const SUBMISSION_STATUS: Record<
  LiveMediaSubmissionStatus,
  {
    label: string
    tone: LiveMediaSubmissionListItemView['statusTone']
  }
> = {
  QUEUED: { label: '排队中', tone: 'neutral' },
  SUBMITTING: { label: '提交中', tone: 'info' },
  SUBMIT_UNKNOWN: { label: '正在核对', tone: 'warning' },
  ACCEPTED: { label: '已受理', tone: 'info' },
  PUBLISHED: { label: '已发布', tone: 'success' },
  REJECTED: { label: '已退稿', tone: 'danger' },
  RECONCILING: { label: '结算核对中', tone: 'warning' },
  FAILED: { label: '投稿失败', tone: 'danger' },
}

export const MEDIA_SUBMISSION_STATUS_OPTIONS = Object.entries(SUBMISSION_STATUS).map(
  ([value, presentation]) => ({ value: value as LiveMediaSubmissionStatus, label: presentation.label }),
)

function validNonNegativeInteger(value: number): number | undefined {
  return Number.isSafeInteger(value) && value >= 0 ? value : undefined
}

function validPositiveInteger(value: number): number | undefined {
  return Number.isSafeInteger(value) && value > 0 ? value : undefined
}

export function formatSaleAmount(value: number): string {
  const cents = validNonNegativeInteger(value)
  if (cents === undefined) return '—'
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: 2,
  }).format(cents / 100)
}

function safePublishedUrl(value?: string | null): string | undefined {
  if (!value) return undefined
  try {
    const parsed = new URL(value)
    return (parsed.protocol === 'http:' || parsed.protocol === 'https:') &&
      !parsed.username && !parsed.password
      ? parsed.href
      : undefined
  } catch {
    return undefined
  }
}

export function toLiveMediaResource(
  resource: MediaResource,
  now = Date.now(),
): LiveMediaResourceView {
  const active = resource.status === 'ACTIVE'
  const quoteExpiresAt = Date.parse(resource.quoteExpiresAt)
  const amount = validPositiveInteger(resource.saleAmountCents)
  const points = validPositiveInteger(resource.chargedPoints)
  const priced = amount !== undefined && points !== undefined
  const estimatedDays = validNonNegativeInteger(resource.estimatedDays ?? -1)
  const pricing = mediaArticlePricing(resource.capabilities.articlePricing)
  const articlePricing = pricing?.totalPoints === resource.chargedPoints &&
    pricing.totalSaleAmountCents === resource.saleAmountCents ? pricing : undefined
  return {
    id: resource.id,
    modelId: resource.upstreamModelId,
    name: resource.name,
    status: resource.status,
    resourceType: resource.resourceType ?? undefined,
    channelCategory: resource.channelCategory ?? undefined,
    region: resource.region ?? undefined,
    estimatedTimeLabel:
      estimatedDays === undefined ? undefined : `约 ${estimatedDays} 天`,
    saleAmountLabel: active && priced ? formatSaleAmount(resource.saleAmountCents) : '暂无可用报价',
    chargedPoints: active && priced ? points : undefined,
    quoteExpiresAt: active && priced ? formatArticleDateTime(resource.quoteExpiresAt) : '—',
    syncedAt: formatArticleDateTime(resource.syncedAt),
    supportsLinks: mediaResourceSupportsLinks(resource.capabilities),
    supportsImages: mediaResourceSupportsImages(resource.capabilities),
    maxImageCount: typeof resource.capabilities.maxImageCount === 'number' ? resource.capabilities.maxImageCount : undefined,
    imageFeeMode: typeof resource.capabilities.imageFeeMode === 'string' ? resource.capabilities.imageFeeMode : undefined,
    imagePricingDescription: typeof resource.capabilities.imagePricingDescription === 'string' ? resource.capabilities.imagePricingDescription : undefined,
    articlePricing,
    orderable:
      active &&
      amount !== undefined &&
      points !== undefined &&
      Number.isFinite(quoteExpiresAt) &&
      quoteExpiresAt > now,
  }
}

export function toArticleMediaRecommendationSet(
  recommendations: MediaRecommendationSet,
): ArticleMediaRecommendationSetView {
  return {
    articleId: recommendations.articleId,
    articleVersion: recommendations.articleVersion,
    generatedAt: formatArticleDateTime(recommendations.generatedAt),
    submissionEnabled: recommendations.submissionEnabled,
    strategy: {
      goal: recommendations.strategy.goal,
      preferredRegion: recommendations.strategy.preferredRegion ?? undefined,
      maxTotalPoints: recommendations.strategy.maxTotalPoints ?? undefined,
      requiresLink: recommendations.strategy.requiresLink,
      detectedCategories: recommendations.strategy.detectedCategories,
      candidateCount: recommendations.strategy.candidateCount,
      dataLimitations: recommendations.strategy.dataLimitations,
    },
    items: recommendations.items.map((item) => ({
      rank: item.rank,
      reasonCode: item.reasonCode,
      reason: item.reason,
      score: item.score,
      evidence: item.evidence,
      resource: toLiveMediaResource(item.resource),
    })),
  }
}

export function toLiveMediaSubmission(
  submission: MediaSubmissionSummary,
): LiveMediaSubmissionListItemView {
  const presentation = SUBMISSION_STATUS[submission.status]
  const publishedUrls = [...new Set(
    [submission.publishedUrl, ...(submission.publishedUrls ?? [])]
      .map(safePublishedUrl)
      .filter((url): url is string => Boolean(url)),
  )]
  return {
    id: submission.id,
    taskId: submission.taskId,
    articleId: submission.articleId,
    articleSnapshotId: submission.articleSnapshotId,
    mediaResourceId: submission.mediaResourceId,
    mediaName: submission.mediaName,
    status: submission.status,
    statusLabel: presentation.label,
    statusTone: presentation.tone,
    saleAmountLabel: formatSaleAmount(submission.saleAmountCents),
    chargedPoints: validNonNegativeInteger(submission.chargedPoints),
    publishedUrl: publishedUrls[0],
    publishedUrls,
    errorCode: taskFailureMessage(submission.stableErrorCode, {
      status: submission.status,
    }),
    createdAt: formatArticleDateTime(submission.createdAt),
    updatedAt: formatArticleDateTime(submission.updatedAt),
  }
}

export function toLiveMediaSubmissionDetail(
  submission: MediaSubmissionDetail,
  settlement?: { settledPoints?: number; returnedPoints?: number },
): LiveMediaSubmissionDetailView {
  return {
    ...toLiveMediaSubmission(submission),
    remark: submission.remark ?? undefined,
    settledPoints:
      settlement?.settledPoints === undefined
        ? undefined
        : validNonNegativeInteger(settlement.settledPoints),
    returnedPoints:
      settlement?.returnedPoints === undefined
        ? undefined
        : validNonNegativeInteger(settlement.returnedPoints),
    events: submission.events.map((event) => ({
      id: event.id,
      sequence: event.sequence,
      label: event.eventType,
      statusLabel: SUBMISSION_STATUS[event.toStatus].label,
      occurredAt: formatArticleDateTime(event.occurredAt),
      errorCode: taskFailureMessage(event.stableErrorCode, {
        status: event.toStatus,
      }),
    })),
  }
}

export function mediaDraftResourceIds(draft?: TaskDraft): string[] {
  if (!draft || draft.taskType !== 'MEDIA') return []
  if (!('items' in draft.parameters)) return []
  return draft.parameters.items.map((item) => item.mediaResourceId)
}

export function toMediaPaidTaskConfirmation(
  draft: TaskDraft | undefined,
  pointAccount: PointAccount | undefined,
  snapshot: SubmissionSnapshotView | undefined,
  resources: readonly LiveMediaResourceView[],
  confirming: boolean,
  quoteStale: boolean,
  submissionEnabled: boolean = false,
): PaidTaskConfirmationView | undefined {
  if (!draft || draft.taskType !== 'MEDIA' || !('items' in draft.parameters)) {
    return undefined
  }
  const resourceById = new Map(resources.map((resource) => [resource.id, resource]))
  const targets = draft.parameters.items.map((item) => {
    const resource = resourceById.get(item.mediaResourceId)
    const pricing = mediaArticlePricing('pricing' in item ? item.pricing : undefined)
    return {
      id: item.mediaResourceId,
      name: resource?.name ?? '媒体资源已不可用',
      estimatedTimeLabel: resource?.estimatedTimeLabel,
      saleAmountLabel: pricing ? formatSaleAmount(pricing.totalSaleAmountCents) : resource?.saleAmountLabel,
      chargedPoints: pricing?.totalPoints ?? resource?.chargedPoints,
      imageFeeLabel: pricing ? `${pricing.imageCount} 张 · ${mediaImageFeeModeLabel(pricing.imageFeeMode)} · 图片附加费 ${formatSaleAmount(pricing.imageFeeAmountCents)} / ${pricing.imageFeePoints} 积分` : undefined,
    }
  })
  const resourcesCurrent = draft.parameters.items.every(
    (item) => resourceById.get(item.mediaResourceId)?.orderable,
  )
  const snapshotCurrent = snapshot?.id === draft.parameters.articleSnapshotId
  const hasImages = snapshot ? mediaArticleHasImages({
    bodyHtml: snapshot.bodyHtml,
    assetCount: snapshot.assetCount,
  }) : false
  const imageUnsupported = hasImages && draft.parameters.items.some((item) => {
    const resource = resourceById.get(item.mediaResourceId)
    const pricing = mediaArticlePricing('pricing' in item ? item.pricing : undefined)
    return resource?.supportsImages !== true || (resource.maxImageCount ?? 0) < (snapshot?.assetCount ?? 1) ||
      !pricing || pricing.imageCount !== snapshot?.assetCount || pricing.imageFeeMode === 'NONE'
  })
  const linksIncompatible = Boolean(snapshot && mediaArticleHasLinks(snapshot.bodyHtml) &&
    draft.parameters.items.some((item) => !resourceById.get(item.mediaResourceId)?.supportsLinks))
  const base = toPaidTaskConfirmation(
    draft,
    pointAccount,
    undefined,
    confirming,
  )
  return {
    ...base,
    title: '文章媒体投稿确认',
    detailRows: [
      { label: '媒体数量', value: `${draft.parameters.items.length} 家` },
      { label: '报价有效期', value: formatArticleDateTime(draft.expiresAt) },
      {
        label: '价格说明',
        value: '销售金额与积分均以提交前的最新报价为准',
      },
      { label: '图片数量', value: `${snapshot?.assetCount ?? 0} 张` },
      ...(hasImages ? [{ label: '图片费用', value: imageUnsupported ? '费用或图片规则未核验，不能投稿' : '各媒体图片附加费已计入下方总价，不另外重复计费' }] : []),
    ],
    mediaSubmissionDetails: {
      articleSnapshot: {
        articleId: draft.parameters.articleId,
        title: snapshot?.title ?? '快照信息加载中',
        versionLabel: snapshot?.versionLabel ?? '—',
        generatedAt: snapshot?.createdAt,
      },
      targets,
    },
    warningText: [
      ...draft.warnings,
      ...(!submissionEnabled ? ['投稿执行暂未开放，当前仅可查看报价，不能确认下单'] : []),
      ...(quoteStale
        ? ['媒体报价已变化，请返回修改并主动重新报价']
        : []),
      ...(!resourcesCurrent
        ? ['媒体资源状态或价格有效期已变化，请返回修改并重新报价']
        : []),
      ...(!snapshotCurrent
        ? ['不可变投稿快照尚未恢复，请稍后重试']
        : []),
      ...(imageUnsupported
        ? ['当前快照包含图片，所选渠道的图片规则尚未核验或不满足数量限制；稿件与图片已保留，尚未投稿']
        : []),
      ...(linksIncompatible
        ? ['当前快照含外链，但所选媒体未标记支持外链，请重新选择媒体并报价']
        : []),
    ].join('；') || undefined,
    confirmEnabled:
      submissionEnabled && base.confirmEnabled && resourcesCurrent && snapshotCurrent && !quoteStale &&
      !imageUnsupported && !linksIncompatible,
  }
}

export function mediaTaskResult(task?: PaidTask) {
  if (!task || task.taskType !== 'MEDIA') return undefined
  return 'submissionBatchId' in task.result ? task.result : undefined
}

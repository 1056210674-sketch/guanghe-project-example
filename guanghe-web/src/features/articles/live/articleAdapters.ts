import type {
  Article,
  ArticleCreativePlanInput,
  ArticleOutlineInput,
  ArticleSummary,
  ArticleSubmissionSnapshot,
} from '../../../api/articleApi'
import type { AssetSummary } from '../../../api/assetApi'
import type {
  CreateTaskDraftRequest,
  PaidTask,
  PointAccount,
  TaskDraft,
} from '../../../api/conversationApi'
import {
  toPaidTaskConfirmation,
  toParallelTaskItem,
  toTaskCreatedReceipt,
  toTaskExecutionOutcome,
} from '../../chat/live/chatAdapters'
import type {
  PaidTaskConfirmationView,
  ParallelTaskGroupView,
  TaskCreatedReceiptView,
  TaskExecutionOutcomeGroupView,
} from '../../chat'
import type {
  ArticleAssetOptionView,
  ArticleCreativePlanDraft,
  ArticleListItem,
  ArticleStage,
  ArticleTaskFormDraft,
  ArticleWorkspaceRecordView,
  SubmissionSnapshotView,
} from '../types'

const ARTICLE_STATUS_LABELS: Record<Article['status'], string> = {
  DRAFT: '草稿',
  OUTLINING: '创作方案生成中',
  BODY_QUEUED: '正文排队',
  BODY_GENERATING: '正文生成中',
  COMPLETED: '已完成',
  FAILED: '失败',
  ARCHIVED: '已归档',
}

export function articleBlockIds(bodyHtml: string): string[] {
  if (!bodyHtml.trim()) return []
  const document = new DOMParser().parseFromString(bodyHtml, 'text/html')
  return Array.from(document.querySelectorAll('section[data-article-block-id]'))
    .map((element) => element.getAttribute('data-article-block-id')?.trim())
    .filter((value): value is string => Boolean(value))
}

export function formatArticleDateTime(value: string): string {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime())
    ? value
    : new Intl.DateTimeFormat('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      }).format(parsed)
}

export function toArticleListItem(article: ArticleSummary): ArticleListItem {
  return {
    id: article.id,
    title: article.title || '未命名文章',
    status: article.status,
    statusLabel: ARTICLE_STATUS_LABELS[article.status],
    versionLabel: `V${article.version}`,
    updatedAt: formatArticleDateTime(article.updatedAt),
  }
}

export function toArticleWorkspaceRecord(
  article: Article,
): ArticleWorkspaceRecordView {
  return {
    id: article.id,
    spaceId: article.spaceId,
    productId: article.productId ?? undefined,
    title: article.title,
    bodyHtml: article.bodyHtml,
    bodyText: article.bodyText,
    status: article.status,
    statusLabel: ARTICLE_STATUS_LABELS[article.status],
    version: article.version,
    updatedAt: formatArticleDateTime(article.updatedAt),
    outline: article.outline.map((item) => ({
      key: item.key,
      level: item.level,
      heading: item.heading,
      brief: item.brief ?? undefined,
    })),
    assets: article.assets.map((asset) => ({
      assetId: asset.assetId,
      positionKey: asset.positionKey,
      sortOrder: asset.sortOrder,
      altText: asset.altText ?? undefined,
    })),
    sourceTaskId: article.sourceTaskId ?? undefined,
    workflowStage: article.workflowStage,
    titleCandidateBatch: article.titleCandidateBatch
      ? {
          version: article.titleCandidateBatch.version,
          candidates: article.titleCandidateBatch.candidates,
          generatedAt: formatArticleDateTime(
            article.titleCandidateBatch.generatedAt,
          ),
        }
      : undefined,
    confirmedTitle: article.confirmedTitle
      ? {
          value: article.confirmedTitle.value,
          source: article.confirmedTitle.source,
          titleBatchVersion: article.confirmedTitle.titleBatchVersion,
          candidateIndex: article.confirmedTitle.candidateIndex ?? undefined,
          confirmedAt: formatArticleDateTime(article.confirmedTitle.confirmedAt),
        }
      : undefined,
    creativePlan: article.creativePlan
      ? {
          version: article.creativePlan.version,
          summary: article.creativePlan.summary,
          coreMessage: article.creativePlan.coreMessage,
          targetAudience: article.creativePlan.targetAudience,
          sections: article.creativePlan.sections.map((section) => ({
            key: section.key,
            sequence: section.sequence,
            level: section.level,
            heading: section.heading,
            brief: section.brief,
          })),
          tone: article.creativePlan.tone,
          targetLength: article.creativePlan.targetLength,
          targetPlatform: article.creativePlan.targetPlatform ?? undefined,
          riskNotes: article.creativePlan.riskNotes,
          confirmed: article.creativePlan.confirmed,
          updatedAt: formatArticleDateTime(article.creativePlan.updatedAt),
        }
      : undefined,
  }
}

export function toArticleAssetOption(asset: AssetSummary): ArticleAssetOptionView {
  return {
    id: asset.id,
    name: asset.name,
    status: asset.status,
    contentType: asset.contentType,
    width: asset.width ?? undefined,
    height: asset.height ?? undefined,
  }
}

export function toSubmissionSnapshotView(
  snapshot: ArticleSubmissionSnapshot,
): SubmissionSnapshotView {
  return {
    id: snapshot.id,
    articleId: snapshot.articleId,
    title: snapshot.title,
    versionLabel: `V${snapshot.articleVersion}`,
    createdAt: formatArticleDateTime(snapshot.createdAt),
    bodyHtml: snapshot.bodyHtml,
    bodyText: snapshot.bodyText,
    outlineCount: snapshot.outline.length,
    assetCount: snapshot.assets.length,
  }
}

function targetLengthFromWordCount(value: number) {
  if (value <= 800) return 'SHORT' as const
  if (value >= 1600) return 'LONG' as const
  return 'MEDIUM' as const
}

export function toArticleTaskDraftRequest(
  conversationId: string,
  article: ArticleWorkspaceRecordView,
  form: ArticleTaskFormDraft,
  geoReportId: string | null = null,
): CreateTaskDraftRequest {
  const mode: ArticleStage = form.mode
  return {
    conversationId,
    taskType: 'ARTICLE',
    spaceId: article.spaceId,
    productId: article.productId,
    parameterSchemaVersion: 2,
    parameters: {
      mode,
      articleId: article.id,
      articleVersion: article.version,
      brief: form.brief.trim(),
      selectedTitle: mode === 'TITLE' ? null : article.title || null,
      confirmedOutline:
        mode === 'BODY' || mode === 'REWRITE'
          ? article.outline.map((item, index) => ({
              key: item.key,
              sequence: index + 1,
              level: item.level,
              heading: item.heading,
              brief: item.brief ?? null,
            }))
          : [],
      confirmedPlan:
        mode === 'BODY' || mode === 'REWRITE'
          ? article.creativePlan
            ? {
                summary: article.creativePlan.summary,
                coreMessage: article.creativePlan.coreMessage,
                targetAudience: article.creativePlan.targetAudience,
                sections: article.creativePlan.sections.map((section) => ({
                  key: section.key,
                  sequence: section.sequence,
                  level: section.level,
                  heading: section.heading,
                  brief: section.brief ?? '',
                })),
                tone: article.creativePlan.tone,
                targetLength: article.creativePlan.targetLength,
                targetPlatform: article.creativePlan.targetPlatform ?? null,
                riskNotes: [...article.creativePlan.riskNotes],
              }
            : null
          : null,
      rewriteInstruction:
        mode === 'REWRITE' ? form.rewriteInstruction.trim() : null,
      rewriteBlockIds: mode === 'REWRITE' ? [...form.rewriteBlockIds] : [],
      targetLength: targetLengthFromWordCount(form.targetWordCount),
      tone: form.tone,
      targetPlatform:
        mode === 'BODY' || mode === 'REWRITE'
          ? article.creativePlan?.targetPlatform ?? null
          : null,
      sourceAssetIds: [...form.sourceAssetIds],
      geoReportId,
    },
  }
}

export function articleTaskResult(task?: PaidTask) {
  if (!task || task.taskType !== 'ARTICLE') return undefined
  const result = task.result
  return 'articleId' in result ? result : undefined
}

export function toArticleTaskPresentation(
  task: PaidTask | undefined,
  pointAccount?: PointAccount,
): {
  receipt?: TaskCreatedReceiptView
  progress?: ParallelTaskGroupView
  outcome?: TaskExecutionOutcomeGroupView
} {
  if (!task) return {}
  const terminal = [
    'SUCCEEDED',
    'PARTIAL',
    'FAILED',
    'SKIPPED',
    'SYSTEM_TERMINATED',
  ].includes(task.status)
  return {
    receipt: toTaskCreatedReceipt(task, pointAccount),
    progress: terminal
      ? undefined
      : {
          id: `article-progress-${task.id}`,
          title: '文章任务执行状态',
          tasks: [toParallelTaskItem(task)],
        },
    outcome: terminal
      ? {
          id: `article-outcome-${task.id}`,
          outcomes: [toTaskExecutionOutcome(task)],
        }
      : undefined,
  }
}

export function toArticlePaidTaskConfirmation(
  draft: TaskDraft | undefined,
  pointAccount: PointAccount | undefined,
  confirming: boolean,
): PaidTaskConfirmationView | undefined {
  if (!draft || draft.taskType !== 'ARTICLE') return undefined
  return toPaidTaskConfirmation(draft, pointAccount, undefined, confirming)
}

export function outlineInputs(
  outline: readonly ArticleWorkspaceRecordView['outline'][number][],
): ArticleOutlineInput[] {
  return outline.map((item) => ({
    key: item.key,
    level: item.level,
    heading: item.heading,
    brief: item.brief,
  }))
}

export function toArticleCreativePlanInput(
  plan: ArticleCreativePlanDraft,
): ArticleCreativePlanInput {
  return {
    summary: plan.summary.trim(),
    coreMessage: plan.coreMessage.trim(),
    targetAudience: plan.targetAudience.trim(),
    sections: plan.sections.map((section) => ({
      key: section.key,
      level: section.level,
      heading: section.heading.trim(),
      brief: section.brief?.trim() ?? '',
    })),
    tone: plan.tone,
    targetLength: plan.targetLength,
    targetPlatform: plan.targetPlatform?.trim() || null,
    riskNotes: plan.riskNotes.map((note) => note.trim()).filter(Boolean),
  }
}

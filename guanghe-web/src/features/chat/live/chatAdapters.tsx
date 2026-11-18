import { QuestionCircleOutlined } from '@ant-design/icons'
import type {
  PaidTaskAssetReference,
  PaidVideoTaskAssetReference,
  Conversation,
  ConversationMessage,
  ConversationMessageGeoContext,
  PaidTask,
  PointAccount,
  TaskDraft,
} from '../../../api/conversationApi'
import type { Article } from '../../../api/articleApi'
import type { GeoReport } from '../../../api/geoApi'
import { geoMerchantNarrative } from '../../geo/merchantNarrative'
import { geoArticleUnavailableReason } from '../../geo/geoArticleEligibility'
import { mediaArticleHasImages, mediaArticleHasLinks } from '../../articles/live/mediaContentPolicy'
import type {
  ArticleMediaRecommendationSetView,
  ArticleViewError,
} from '../../articles/types'
import type {
  ChatClarificationOptionView,
  ChatResourceSelectionView,
  ChatBackgroundTaskNotificationView,
  ChatMessageView,
  ChatProductOptionView,
  ChatSessionView,
  ChatTaskStatusSummaryView,
  GeneratedImageResultView,
  GeneratedVideoResultView,
  PaidTaskConfirmationView,
  ChatReferenceAssetView,
  ParallelTaskItemView,
  ParallelTaskStatusTone,
  TaskCreatedReceiptView,
  TaskExecutionOutcomeView,
} from '../types'
import { taskFailureMessage } from '../../task-common/taskFailurePresentation'
import type { CreativeSourceMode } from '../creativeGrounding'
import { readBusinessOutcome } from './businessOutcome'

type UnknownRecord = Record<string, unknown>

export interface ChatMessageEnrichment {
  paidTaskConfirmation?: PaidTaskConfirmationView
  task?: PaidTask
  article?: Article
  geoReport?: GeoReport
  mediaRecommendations?: ArticleMediaRecommendationSetView
  mediaRecommendationError?: ArticleViewError
  pointAccount?: PointAccount
  productName?: string
  generatedAsset?: {
    name?: string
    imageUrl?: string
    videoUrl?: string
    expiresAt?: string
    creativeSourceMode?: CreativeSourceMode
    imagesByAssetId?: Record<string, { name?: string; imageUrl?: string; expiresAt?: string }>
    selectedAssetIds?: readonly string[]
  }
  taskPresentation?: 'DEFAULT' | 'SOURCE_STATUS' | 'BACKGROUND_NOTIFICATION'
}

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}

function optionalHttpsUrl(value: unknown): string | undefined {
  const candidate = optionalString(value)
  if (!candidate) return undefined
  try {
    return new URL(candidate).protocol === 'https:' ? candidate : undefined
  } catch {
    return undefined
  }
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : []
}

export function formatChatDateTime(value?: string | null): string | undefined {
  if (!value) return undefined
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat('zh-CN', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      }).format(date)
}

export function toChatSessionView(conversation: Conversation): ChatSessionView {
  return {
    id: conversation.id,
    title: conversation.title,
    updatedAt: formatChatDateTime(
      conversation.lastMessageAt ?? conversation.updatedAt,
    ),
  }
}

function toClarificationOptions(
  messageId: string,
  interaction: UnknownRecord,
): ChatClarificationOptionView[] {
  return stringArray(interaction.choices).map((choice, index) => ({
    id: `${messageId}:choice:${index}`,
    title: choice,
    description: '选择此项继续对话',
    icon: <QuestionCircleOutlined />,
  }))
}

function toResourceSelection(interaction: UnknownRecord): ChatResourceSelectionView | undefined {
  const validId = (value: unknown) => typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value)
  const validName = (value: unknown): value is string => typeof value === 'string' && Boolean(value.trim()) && value.length <= 200
  if (interaction.interactionType !== 'RESOURCE_SELECTION' || !validId(interaction.candidateSetId)
    || typeof interaction.available !== 'boolean' || !Array.isArray(interaction.candidates)
    || interaction.candidates.length < 1 || interaction.candidates.length > 6) return undefined
  const choices = new Set<string>()
  const candidates: ChatResourceSelectionView['candidates'][number][] = []
  for (const candidate of interaction.candidates) {
    if (!isRecord(candidate) || !validId(candidate.resourceId) || !validName(candidate.name)
      || typeof candidate.choiceId !== 'string' || !candidate.choiceId.trim() || candidate.choiceId.length > 128
      || choices.has(candidate.choiceId) || !['SPACE', 'PRODUCT', 'GEO_TOPIC'].includes(String(candidate.kind))
      || typeof candidate.hasReport !== 'boolean' || typeof candidate.exact !== 'boolean'
      || (candidate.spaceId !== undefined && !validId(candidate.spaceId))
      || (candidate.spaceName !== undefined && !validName(candidate.spaceName))) return undefined
    choices.add(candidate.choiceId)
    candidates.push({
      choiceId: candidate.choiceId,
      name: candidate.name,
      kindLabel: candidate.kind === 'SPACE' ? '经营空间' : candidate.kind === 'PRODUCT' ? '商品' : 'GEO 专题',
      spaceName: candidate.spaceName as string | undefined,
      reportLabel: candidate.kind === 'GEO_TOPIC' ? candidate.hasReport ? '已有监测报告' : '尚无监测报告' : undefined,
    })
  }
  return { candidateSetId: interaction.candidateSetId as string, available: interaction.available, candidates }
}

export function embeddedTaskDraftId(
  message: ConversationMessage,
): string | undefined {
  const taskDraft = isRecord(message.content.taskDraft)
    ? message.content.taskDraft
    : undefined
  return optionalString(taskDraft?.id)
}

function verifiedGeoContext(message: ConversationMessage): ConversationMessageGeoContext | undefined {
  if (message.role !== 'ASSISTANT' || message.status !== 'COMPLETED') return undefined
  const context = message.content.geoContext
  if (!isRecord(context) || !['READY', 'NEEDS_SCOPE', 'NEEDS_TOPIC', 'NO_REPORT', 'UNAVAILABLE'].includes(context.status)
    || !Array.isArray(context.topicChoices)) return undefined
  const validId = (value: unknown) => typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value)
  const report = context.report
  if (context.status === 'READY' && (!isRecord(report) || !validId(report.reportId)
    || !validId(report.topicId) || !validId(report.spaceId)
    || !(report.productId === null || validId(report.productId))
    || !(report.observationId === undefined || validId(report.observationId))
    || !(report.available === undefined || typeof report.available === 'boolean')
    || typeof report.title !== 'string' || typeof report.canCreateArticle !== 'boolean')) return undefined
  return {
    ...context,
    report: context.status === 'READY' ? report : null,
    topicChoices: context.topicChoices.filter((topic) => isRecord(topic) && validId(topic.topicId)
      && validId(topic.spaceId) && (topic.productId === null || validId(topic.productId)) && typeof topic.name === 'string'),
  }
}

export function toChatMessageView(
  message: ConversationMessage,
  enrichment: ChatMessageEnrichment = {},
): ChatMessageView {
  const content = isRecord(message.content) ? message.content : {}
  const interaction = isRecord(content.interaction)
    ? content.interaction
    : undefined
  const task = enrichment.task
  const taskPresentation = enrichment.taskPresentation ?? 'DEFAULT'
  const taskReceipt = task && taskPresentation !== 'BACKGROUND_NOTIFICATION'
    ? toTaskCreatedReceipt(task, enrichment.pointAccount, enrichment.productName)
    : undefined
  const terminal = task
    ? ['SUCCEEDED', 'PARTIAL', 'FAILED', 'SKIPPED', 'SYSTEM_TERMINATED'].includes(
        task.status,
      )
    : false
  const persistedText = optionalString(content.text) ?? ''
  const clarificationQuestion = interaction
    ? optionalString(interaction.question)
    : undefined
  const diagnostic = isRecord(content.geoDiagnostic) ? content.geoDiagnostic : undefined
  const diagnosticStep = diagnostic?.step

  return {
    id: message.id,
    role: message.role === 'USER' ? 'USER' : 'ASSISTANT',
    content: clarificationQuestion ?? persistedText,
    createdAt: formatChatDateTime(message.createdAt),
    referencedGeoReportId: message.role === 'USER' && typeof content.geoReportId === 'string'
      && /^[1-9]\d{0,18}$/.test(content.geoReportId) ? content.geoReportId : undefined,
    geoContext: verifiedGeoContext(message),
    geoDiagnostic: message.role === 'ASSISTANT' && message.status === 'COMPLETED' && message.runId === null
      && typeof diagnostic?.diagnosticId === 'string' && /^[1-9]\d{0,18}$/.test(diagnostic.diagnosticId)
      && typeof diagnosticStep === 'number' && Number.isInteger(diagnosticStep) && diagnosticStep >= 1 && diagnosticStep <= 6
      ? { diagnosticId: diagnostic.diagnosticId, step: diagnosticStep, conversationId: message.conversationId } : undefined,
    businessOutcome: message.role === 'ASSISTANT' && ['COMPLETED', 'FAILED'].includes(message.status)
      ? readBusinessOutcome(content.businessOutcome)
      : undefined,
    clarificationOptions: message.role === 'ASSISTANT' && interaction &&
      (interaction.interactionType === 'CLARIFICATION' || interaction.interactionType === undefined)
      ? toClarificationOptions(message.id, interaction)
      : undefined,
    resourceSelection: message.role === 'ASSISTANT' && interaction
      ? toResourceSelection(interaction) : undefined,
    guidance:
      clarificationQuestion && persistedText && persistedText !== clarificationQuestion
        ? persistedText
        : undefined,
    paidTaskConfirmation: enrichment.paidTaskConfirmation,
    taskCreatedReceipt: taskReceipt,
    parallelTaskGroup:
      task &&
      taskPresentation !== 'BACKGROUND_NOTIFICATION' &&
      (!terminal || taskPresentation === 'SOURCE_STATUS')
        ? {
            id: `task-progress-${task.id}`,
            title: '任务执行状态',
            tasks: [toParallelTaskItem(task, enrichment.productName)],
          }
        : undefined,
    taskExecutionOutcomeGroup:
      task && terminal && taskPresentation !== 'SOURCE_STATUS'
        ? {
            id: `task-outcome-${task.id}`,
            outcomes: [toTaskExecutionOutcome(task, enrichment.geoReport)],
          }
        : undefined,
    generatedImageResultGroup:
      task && terminal && taskPresentation !== 'SOURCE_STATUS'
        ? toGeneratedImageResultGroup(task, enrichment.generatedAsset)
        : undefined,
    generatedVideoResult:
      task && terminal && taskPresentation !== 'SOURCE_STATUS'
        ? toGeneratedVideoResult(task, enrichment.generatedAsset)
        : undefined,
    articleDraftPreview:
      task &&
      terminal &&
      task.status === 'SUCCEEDED' &&
      taskPresentation !== 'SOURCE_STATUS'
        ? toArticleDraftPreview(
            task,
            enrichment.article,
            enrichment.mediaRecommendations,
            enrichment.mediaRecommendationError,
          )
        : undefined,
    articleWorkflowAnchor:
      enrichment.article
        ? {
            articleId: enrichment.article.id,
            title: enrichment.article.title || '未命名文章',
            workflowStage: enrichment.article.workflowStage,
          }
        : undefined,
  }
}

export function toChatProductOption(
  product: {
    id: string
    spaceId: string
    name: string
    category: string | null
    modelSpec: string | null
  },
): ChatProductOptionView {
  return {
    id: product.id,
    businessSpaceId: product.spaceId,
    name: product.name,
    category: product.category ?? undefined,
    modelOrSpecification: product.modelSpec ?? undefined,
  }
}

export function taskTypeLabel(taskType: PaidTask['taskType'] | TaskDraft['taskType']) {
  switch (taskType) {
    case 'VIDEO':
      return '视频创作'
    case 'GEO':
      return 'GEO 监测'
    case 'IMAGE':
      return '图片创作'
    case 'ARTICLE':
      return '文章创作'
    case 'MEDIA':
      return '媒体投稿'
  }
}

function articleTaskRows(draft: TaskDraft) {
  const parameters = draft.parameters
  if (!('targetLength' in parameters)) return []
  const modeLabels = {
    TITLE: '标题生成',
    OUTLINE: '创作方案生成',
    BODY: '正文生成',
    REWRITE: '正文改写',
  } as const
  return [
    { label: '生成阶段', value: modeLabels[parameters.mode] },
    { label: '目标长度', value: parameters.targetLength },
    { label: '表达语气', value: parameters.tone },
    { label: '参考素材', value: `${parameters.sourceAssetIds.length} 项` },
    {
      label: '确认有效期',
      value: formatChatDateTime(draft.expiresAt) ?? draft.expiresAt,
    },
  ]
}

function imageTaskRows(draft: TaskDraft) {
  const parameters = draft.parameters
  if (!('count' in parameters)) return []
  const brief = parameters.visualBrief
  const platformLabels = { GENERAL: '通用电商', PINDUODUO: '拼多多', TAOBAO: '淘宝', JD: '京东', DOUYIN: '抖音', XIAOHONGSHU: '小红书' }
  const placementLabels = { MAIN_IMAGE: '商品主图', DETAIL_IMAGE: '详情图', SOCIAL_COVER: '内容封面', POSTER: '海报' }
  return [
    { label: '执行方式', value: '智能辅助生成' },
    { label: '画面比例', value: parameters.sourceMode === 'REGION_EDIT' ? '保持原图尺寸' : parameters.aspectRatio },
    ...(parameters.scene ? [{ label: '场景', value: parameters.scene }] : []),
    ...(brief ? [
      { label: '视觉用途', value: `${platformLabels[brief.platform]} · ${placementLabels[brief.placement]}` },
      { label: '画面重点', value: brief.visualFocus },
      { label: '构图方案', value: brief.composition },
      { label: '背景设计', value: brief.background },
      { label: '视觉光线', value: brief.lighting },
      { label: '新增文字', value: brief.textPolicy === 'NONE' ? '不新增文字，保留商品原有包装文字' : brief.copyLines.join('；') },
    ] : []),
    ...(parameters.style ? [{ label: '风格', value: parameters.style }] : []),
    ...(parameters.lighting
      ? [{ label: '光线', value: parameters.lighting }]
      : []),
    { label: '生成数量', value: String(parameters.count) },
    { label: '确认有效期', value: formatChatDateTime(draft.expiresAt) ?? draft.expiresAt },
  ]
}

function videoTaskRows(draft: TaskDraft) {
  const parameters = draft.parameters
  if (!('durationSeconds' in parameters)) return []
  return [
    { label: '执行方式', value: '智能辅助生成' },
    { label: '场景', value: parameters.scene },
    ...(parameters.style ? [{ label: '风格', value: parameters.style }] : []),
    { label: '时长', value: `${parameters.durationSeconds} 秒` },
    { label: '画面比例', value: parameters.aspectRatio },
    ...(parameters.cameraMotion
      ? [{ label: '镜头运动', value: parameters.cameraMotion }]
      : []),
    { label: '分辨率', value: parameters.resolution },
    { label: '音频', value: parameters.audio ? '生成音频' : '无音频' },
    { label: '提示词扩展', value: parameters.promptExtend ? '开启' : '关闭' },
    { label: '水印', value: parameters.watermark ? '开启' : '关闭' },
    { label: '确认有效期', value: formatChatDateTime(draft.expiresAt) ?? draft.expiresAt },
  ]
}

function geoTaskRows(draft: TaskDraft) {
  const parameters = draft.parameters
  if ('topic' in parameters && parameters.mode === 'QUESTION_DISCOVERY') {
    return [
      { label: '执行方式', value: '智能生成 GEO 专题' },
      { label: '监测专题', value: parameters.topic },
      ...(parameters.audience
        ? [{ label: '目标用户', value: parameters.audience }]
        : []),
      { label: '监测区域', value: parameters.region },
      { label: '正式问题', value: `${parameters.questions.length} 个` },
      { label: '目标平台', value: `${parameters.platformCodes.length} 个` },
      { label: '采样模式', value: '标准模式' },
      {
        label: '确认有效期',
        value: formatChatDateTime(draft.expiresAt) ?? draft.expiresAt,
      },
    ]
  }
  if (!('topicId' in parameters)) return []
  return [
    { label: '执行方式', value: '手动 GEO 监测' },
    {
      label: '采样模式',
      value: parameters.sampleMode === 'DEEP' ? '深度模式' : '标准模式',
    },
    { label: '监测区域', value: parameters.region },
    { label: '正式问题', value: `${parameters.questionIds.length} 个` },
    { label: '目标平台', value: `${parameters.platformCodes.length} 个` },
    {
      label: '确认有效期',
      value: formatChatDateTime(draft.expiresAt) ?? draft.expiresAt,
    },
  ]
}

const GEO_PLATFORM_NAMES: Readonly<Record<string, string>> = {
  DOUBAO: '豆包',
  DEEPSEEK: 'DeepSeek',
  QWEN: '通义千问',
  WENXIN: '文心一言',
  KIMI: 'Kimi',
  YUANBAO: '腾讯元宝',
  ZHIPU: '智谱',
}

function geoCandidateQuestions(
  draft: TaskDraft,
  fallback?: readonly string[],
) {
  const parameters = draft.parameters
  return 'questions' in parameters ? parameters.questions : fallback
}

function geoPlatformNames(draft: TaskDraft, fallback?: readonly string[]) {
  if (fallback?.length) return fallback
  const parameters = draft.parameters
  if (!('platformCodes' in parameters)) return undefined
  return parameters.platformCodes.map(
    (code) => GEO_PLATFORM_NAMES[code] ?? code,
  )
}

function mediaTaskRows(draft: TaskDraft) {
  const parameters = draft.parameters
  if (!('items' in parameters)) return []
  return [
    { label: '投稿媒体', value: `${parameters.items.length} 家` },
    { label: '文章快照', value: parameters.articleSnapshotId },
    {
      label: '确认有效期',
      value: formatChatDateTime(draft.expiresAt) ?? draft.expiresAt,
    },
  ]
}

export function toPaidTaskConfirmation(
  draft: TaskDraft,
  pointAccount?: PointAccount,
  productName?: string,
  confirming = false,
  geoDetails?: {
    questionNames?: readonly string[]
    platformNames?: readonly string[]
  },
  referenceAssets?: readonly ChatReferenceAssetView[],
): PaidTaskConfirmationView {
  const pointsValid =
    Number.isSafeInteger(draft.quotedPoints) && draft.quotedPoints >= 0
  const balanceValid =
    pointAccount !== undefined &&
    Number.isSafeInteger(pointAccount.availablePoints) &&
    pointAccount.availablePoints >= 0
  const insufficient =
    pointsValid &&
    pointAccount !== undefined &&
    balanceValid &&
    pointAccount.availablePoints < draft.quotedPoints
  const active =
    draft.status === 'WAITING_CONFIRMATION' &&
    Boolean(draft.confirmationToken) &&
    Date.parse(draft.expiresAt) > Date.now()
  const unavailableReason = draft.status === 'ABANDONED'
    ? '此方案已取消，不能继续确认。请查看最新方案，或重新说明需求。'
    : draft.status === 'CONFIRMED' || draft.status === 'ADMITTED'
      ? '此方案已确认，请在任务中心查看进度，不要重复提交。'
      : draft.status === 'EXPIRED' || Date.parse(draft.expiresAt) <= Date.now()
        ? '此方案报价已过期，不能继续确认。请重新说明需求并获取最新报价。'
        : undefined

  return {
    id: draft.id,
    taskType: taskTypeLabel(draft.taskType),
    title: `${taskTypeLabel(draft.taskType)}任务确认`,
    creativeSourceMode: draft.sourceMode ?? undefined,
    productName,
    detailRows:
      draft.taskType === 'MEDIA'
        ? mediaTaskRows(draft)
        : draft.taskType === 'VIDEO'
        ? videoTaskRows(draft)
        : draft.taskType === 'GEO'
          ? geoTaskRows(draft)
          : draft.taskType === 'ARTICLE'
            ? articleTaskRows(draft)
            : imageTaskRows(draft),
    candidateQuestions:
      draft.taskType === 'GEO'
        ? geoCandidateQuestions(draft, geoDetails?.questionNames)
        : undefined,
    platformNames:
      draft.taskType === 'GEO'
        ? geoPlatformNames(draft, geoDetails?.platformNames)
        : undefined,
    pointsCost: draft.quotedPoints,
    pointBalance: balanceValid ? pointAccount.availablePoints : undefined,
    pointsInsufficient: insufficient && !unavailableReason
      ? {
          shortfallPoints: undefined,
          rechargeEnabled: true,
          reestimateEnabled: true,
        }
      : undefined,
    warningText: draft.warnings.length ? draft.warnings.join('；') : undefined,
    unavailableReason,
    confirming,
    confirmEnabled: active && pointsValid && balanceValid && !insufficient,
    referenceAssets,
    referenceAssetEditingEnabled: false,
  }
}

function statusTone(status: PaidTask['status']): ParallelTaskStatusTone {
  const tones: Record<PaidTask['status'], ParallelTaskStatusTone> = {
    QUEUED: 'neutral',
    RUNNING: 'info',
    RETRY_WAIT: 'warning',
    SUCCEEDED: 'success',
    PARTIAL: 'warning',
    FAILED: 'danger',
    SKIPPED: 'neutral',
    SYSTEM_TERMINATED: 'danger',
  }
  return tones[status]
}

const STATUS_LABELS: Record<PaidTask['status'], string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  RETRY_WAIT: '等待重试',
  SUCCEEDED: '已完成',
  PARTIAL: '部分完成',
  FAILED: '失败',
  SKIPPED: '已跳过',
  SYSTEM_TERMINATED: '系统终止',
}

export function toTaskCreatedReceipt(
  task: PaidTask,
  pointAccount?: PointAccount,
  productName?: string,
): TaskCreatedReceiptView {
  const reservationActive = ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(
    task.status,
  )
  return {
    id: `task-receipt-${task.id}`,
    taskId: task.id,
    taskNumber: task.id,
    title: '任务已创建',
    taskType: taskTypeLabel(task.taskType),
    statusLabel: STATUS_LABELS[task.status],
    statusTone: statusTone(task.status),
    productName,
    pointsLabel: reservationActive
      ? '冻结积分'
      : (task.settledPoints ?? 0) > 0
        ? '已结算积分'
        : '实际扣除积分',
    pointsAmount: (reservationActive ? task.frozenPoints : task.settledPoints) ?? undefined,
    pointBalance: pointAccount?.availablePoints,
    createdAt: formatChatDateTime(task.createdAt),
    message: '可以在任务中心查看进度和结果。',
    detailEnabled: true,
  }
}

export function toParallelTaskItem(
  task: PaidTask,
  productName?: string,
): ParallelTaskItemView {
  const progress = task.progress ?? undefined
  const progressDetail = progress
    ? task.taskType === 'IMAGE'
      ? `已处理 ${progress.completedUnits}/${progress.totalUnits} 张图片`
      : task.taskType === 'VIDEO'
        ? `已处理 ${progress.completedUnits}/${progress.totalUnits} 条视频`
        : task.taskType === 'GEO'
          ? `已处理 ${progress.completedUnits}/${progress.totalUnits} 个监测点`
          : `任务进度 ${progress.completedUnits}/${progress.totalUnits}`
    : undefined
  const progressLabel = progress
    ? `${task.stageLabel} · ${progressDetail}`
    : task.stageLabel
  return {
    taskId: task.id,
    taskNumber: task.id,
    taskType: taskTypeLabel(task.taskType),
    statusLabel: STATUS_LABELS[task.status],
    statusTone: statusTone(task.status),
    productName,
    progressLabel,
    progressPercent: progress?.percent,
    timingLabel: formatChatDateTime(task.updatedAt),
    detailEnabled: true,
  }
}

export function toTaskExecutionOutcome(
  task: PaidTask,
  geoReport?: GeoReport,
): TaskExecutionOutcomeView {
  const geoNarrative = geoReport ? geoMerchantNarrative(geoReport) : undefined
  const geoArticleReason = geoReport ? geoArticleUnavailableReason(geoReport) : undefined
  const geoResult = 'runId' in task.result ? task.result : undefined
  const mediaResult = 'submissionBatchId' in task.result ? task.result : undefined
  const articleResult =
    task.taskType === 'ARTICLE' && 'articleId' in task.result
      ? task.result
      : undefined
  const articleStageLabel = articleResult
    ? {
        TITLE: '标题生成',
        OUTLINE: '创作方案生成',
        BODY: '正文生成',
        REWRITE: '局部改写',
      }[articleResult.mode]
    : undefined
  return {
    taskId: task.id,
    taskType:
      articleStageLabel && task.status === 'SUCCEEDED'
        ? `${articleStageLabel}完成`
        : articleStageLabel ?? taskTypeLabel(task.taskType),
    statusLabel: STATUS_LABELS[task.status],
    statusTone: statusTone(task.status),
    completedAt: formatChatDateTime(task.updatedAt),
    summary: geoResult
      ? geoReport
        ? `${geoNarrative?.factualSummary}\n\n${geoNarrative?.summary}`
        : `计划 ${geoResult.expectedUnits} 个采样单元，成功 ${geoResult.succeededUnits} 个。`
      : mediaResult
        ? `投稿 ${mediaResult.total} 家，已发布 ${mediaResult.published} 家，退稿 ${mediaResult.rejected} 家。`
        : articleStageLabel
          ? `${articleStageLabel}只完成当前阶段，请确认后再继续下一步。`
          : generatedImageResultSummary(task),
    failureReason: taskFailureMessage(task.errorCode, { status: task.status }),
    geoDiagnosis: geoResult && geoReport
      ? {
          findings: (
            (geoNarrative?.coverageGaps.length ?? 0) > 0
              ? geoNarrative?.coverageGaps ?? []
              : geoNarrative?.findings ?? []
          ).slice(0, 3),
          recommendations: (geoNarrative?.recommendations ?? [])
            .slice(0, 3)
            .map(({ title, rationale }) => ({ title, rationale })),
          caveat:
            geoReport.status === 'PARTIAL' || geoReport.synthesis.missingSamples.length > 0
              ? '以上判断仅基于本次已完成样本；存在缺失样本的部分需要继续核验。'
              : '以上判断基于本次 GEO 监测样本，不代表全网市场份额或平台保证。',
        }
      : undefined,
    resultItems: geoResult
      ? [
          {
            id: `${task.id}:succeeded`,
            label: `成功 ${geoResult.succeededUnits} 个`,
            statusLabel: '已完成',
            statusTone: 'success',
          },
          {
            id: `${task.id}:failed`,
            label: `失败 ${geoResult.failedUnits} 个`,
            statusLabel: geoResult.failedUnits > 0 ? '存在失败' : '无失败',
            statusTone: geoResult.failedUnits > 0 ? 'danger' : 'neutral',
          },
          {
            id: `${task.id}:missing`,
            label: `缺失 ${geoResult.missingUnits} 个`,
            statusLabel: geoResult.missingUnits > 0 ? '存在缺失' : '无缺失',
            statusTone: geoResult.missingUnits > 0 ? 'warning' : 'neutral',
          },
        ]
      : mediaResult
        ? [
            {
              id: `${task.id}:published`,
              label: `已发布 ${mediaResult.published} 家`,
              statusLabel: '发布结果',
              statusTone: mediaResult.published > 0 ? 'success' : 'neutral',
            },
            {
              id: `${task.id}:rejected`,
              label: `退稿 ${mediaResult.rejected} 家`,
              statusLabel: mediaResult.rejected > 0 ? '存在退稿' : '无退稿',
              statusTone: mediaResult.rejected > 0 ? 'danger' : 'neutral',
            },
          ]
        : undefined,
    settlement: {
      settledPoints: task.settledPoints ?? undefined,
      returnedPoints: task.releasedPoints ?? undefined,
    },
    actions: geoResult
      ? [
          ...(geoReport && geoResult.reportId === geoReport.id
            ? [{ type: 'CREATE_ARTICLE_FROM_GEO' as const, enabled: !geoArticleReason, disabledReason: geoArticleReason }]
            : []),
          { type: 'VIEW_COMPLETED_REPORT', enabled: true },
          ...(geoReport && geoResult.reportId === geoReport.id ? [
            { type: 'VIEW_GEO_TREND' as const, enabled: true },
            { type: 'VIEW_GEO_COMPETITORS' as const, enabled: true },
          ] : []),
        ]
      : undefined,
  }
}

export function toChatTaskStatusSummary(
  task: PaidTask,
): ChatTaskStatusSummaryView {
  return {
    taskId: task.id,
    label: `${taskTypeLabel(task.taskType)} · ${formatChatDateTime(task.createdAt) ?? '最近任务'}`,
    statusLabel: STATUS_LABELS[task.status],
    statusTone: statusTone(task.status),
    focusEnabled: true,
  }
}

export function toBackgroundTaskNotification(
  task: PaidTask,
): ChatBackgroundTaskNotificationView | undefined {
  const terminalTitles: Partial<Record<PaidTask['status'], string>> = {
    SUCCEEDED: `${taskTypeLabel(task.taskType)}已完成`,
    PARTIAL: `${taskTypeLabel(task.taskType)}部分完成`,
    FAILED: `${taskTypeLabel(task.taskType)}未完成`,
    SKIPPED: `${taskTypeLabel(task.taskType)}已跳过`,
    SYSTEM_TERMINATED: `${taskTypeLabel(task.taskType)}已终止`,
  }
  const title = terminalTitles[task.status]
  if (!title) return undefined
  return {
    taskId: task.id,
    label: '后台任务通知',
    title,
    description: '任务已在后台结束；执行期间的其他对话不会影响这份结果。',
  }
}

export function generatedImageAssetReference(
  task: PaidTask,
): PaidTaskAssetReference | undefined {
  return generatedImageAssetReferences(task)[0]
}

export function generatedImageAssetReferences(
  task: PaidTask,
): PaidTaskAssetReference[] {
  const result = task.result
  if (task.taskType !== 'IMAGE' || !('provider' in result) || (result.provider !== 'QWEN_IMAGE' && result.provider !== 'WAN_IMAGE_EDIT')) return []
  const assets = result.assets ?? (result.asset ? [result.asset] : [])
  return assets.filter((asset, index) =>
    asset.assetType === 'IMAGE' && asset.status === 'READY' &&
    assets.findIndex((candidate) => candidate.assetId === asset.assetId) === index,
  )
}

export function generatedImageResultSummary(task: PaidTask): string | undefined {
  const result = task.result
  if (task.taskType !== 'IMAGE' || !('provider' in result) || (result.provider !== 'QWEN_IMAGE' && result.provider !== 'WAN_IMAGE_EDIT')) return undefined
  if (
    result.requestedCount === undefined ||
    result.succeededCount === undefined ||
    result.failedCount === undefined
  ) {
    return undefined
  }
  return `共请求 ${result.requestedCount} 张，已保存 ${result.succeededCount} 张，未完成 ${result.failedCount} 张。`
}

export function generatedVideoAssetReference(
  task: PaidTask,
): PaidVideoTaskAssetReference | undefined {
  const result = task.result
  return 'provider' in result && result.provider === 'WAN_VIDEO'
    ? result.asset
    : undefined
}

function safetyStatusLabel(
  status:
    | PaidTaskAssetReference['safetyStatus']
    | PaidVideoTaskAssetReference['safetyStatus'],
) {
  switch (status) {
    case 'APPROVED': return '内容安全审核通过'
    case 'TEST_BYPASS': return '测试环境已跳过内容安全审核'
    case 'NOT_REVIEWED': return '未进行内容审核'
  }
}

function generatedImages(
  task: PaidTask,
  display: ChatMessageEnrichment['generatedAsset'] = {},
): GeneratedImageResultView[] {
  const assets = generatedImageAssetReferences(task)
  return assets.map((asset, index) => ({
    assetId: asset.assetId,
    candidateLabel: asset.index === undefined ? undefined : `候选 ${asset.index}`,
    name: display.imagesByAssetId?.[asset.assetId]?.name ?? (index === 0 && display?.name
      ? display.name
      : assets.length > 1 ? `生成图片 ${asset.index ?? index + 1}` : '生成图片'),
    imageUrl: optionalHttpsUrl(display.imagesByAssetId?.[asset.assetId]?.imageUrl ?? (index === 0 ? display?.imageUrl : undefined)),
    formatLabel: asset.contentType === 'image/png' ? 'PNG' : asset.contentType,
    dimensionsLabel: `${asset.width} × ${asset.height}`,
    expiresAt: display.imagesByAssetId?.[asset.assetId]?.expiresAt ?? (index === 0 ? display?.expiresAt : undefined),
    safetyStatusLabel: safetyStatusLabel(asset.safetyStatus),
    persistedAsset: true,
    previewEnabled: true,
    downloadEnabled: true,
    saveToAssetsEnabled: true,
    selectEnabled: true,
  }))
}

export function toGeneratedImageResultGroup(
  task: PaidTask,
  display?: ChatMessageEnrichment['generatedAsset'],
) {
  const images = generatedImages(task, display)
  return images.length
    ? {
        id: `generated-images-${task.id}`,
        title: '图片生成结果',
        images,
        selectedAssetIds: display?.selectedAssetIds,
        creativeSourceMode: display?.creativeSourceMode,
        productAssociationEnabled: false,
        productAssociationDisabledReason:
          display?.creativeSourceMode === 'CURRENT_ATTACHMENTS'
            ? '生成结果默认不会关联商品；待关联接口开放后可在这里手动选择商品。'
            : undefined,
      }
    : undefined
}

export function toGeneratedVideoResult(
  task: PaidTask,
  display: ChatMessageEnrichment['generatedAsset'] = {},
): GeneratedVideoResultView | undefined {
  const asset = generatedVideoAssetReference(task)
  if (!asset || asset.assetType !== 'VIDEO') return undefined
  return {
    assetId: asset.assetId,
    name: display?.name ?? '生成视频',
    videoUrl: optionalHttpsUrl(display?.videoUrl),
    contentType: asset.contentType,
    dimensionsLabel: `${asset.width} × ${asset.height}`,
    durationLabel: `${asset.durationSeconds} 秒`,
    expiresAt: display?.expiresAt,
    safetyStatusLabel: safetyStatusLabel(asset.safetyStatus),
    persistedAsset: true,
    previewEnabled: true,
    creativeSourceMode: display?.creativeSourceMode,
    productAssociationEnabled: false,
    productAssociationDisabledReason:
      display?.creativeSourceMode === 'CURRENT_ATTACHMENTS'
        ? '生成结果默认不会关联商品；待关联接口开放后可在这里手动选择商品。'
        : undefined,
  }
}

export function toArticleDraftPreview(
  task: PaidTask,
  article?: Article,
  mediaRecommendations?: ArticleMediaRecommendationSetView,
  mediaRecommendationError?: ArticleViewError,
) {
  if (task.taskType !== 'ARTICLE' || !('articleId' in task.result)) {
    return undefined
  }
  const result = task.result
  if (
    article &&
    ((result.mode === 'TITLE' &&
      article.workflowStage !== 'TITLE_AWAITING_CONFIRMATION') ||
      (result.mode === 'OUTLINE' &&
        article.workflowStage !== 'PLAN_AWAITING_CONFIRMATION'))
  ) {
    return undefined
  }
  const titleCandidates =
    result.mode === 'TITLE' && !result.title
      ? result.titleCandidates.filter((title) => title.trim())
      : []
  return {
    id: result.articleId,
    title: titleCandidates.length
      ? `已生成 ${titleCandidates.length} 个标题候选`
      : article?.title ?? result.title ?? '未命名文章',
    versionLabel: `V${article?.version ?? result.articleVersion}`,
    taskStage: result.mode,
    workflowStage: article?.workflowStage,
    titleCandidates: titleCandidates.length ? titleCandidates : undefined,
    creativePlan:
      result.mode === 'OUTLINE' && article?.creativePlan
        ? {
            version: article.creativePlan.version,
            summary: article.creativePlan.summary,
            coreMessage: article.creativePlan.coreMessage,
            targetAudience: article.creativePlan.targetAudience,
            sections: article.creativePlan.sections,
            tone: article.creativePlan.tone,
            targetLength: article.creativePlan.targetLength,
            targetPlatform: article.creativePlan.targetPlatform ?? undefined,
            riskNotes: article.creativePlan.riskNotes,
          }
        : undefined,
    generatedAt: formatChatDateTime(task.updatedAt),
    excerpt:
      result.mode === 'BODY' || result.mode === 'REWRITE'
        ? article?.bodyText.slice(0, 180)
        : undefined,
    wordCount:
      result.mode === 'BODY' || result.mode === 'REWRITE'
        ? article?.bodyText.length
        : undefined,
    mediaRecommendations:
      result.mode === 'BODY' || result.mode === 'REWRITE'
        ? mediaRecommendations
        : undefined,
    imageCount: article?.assets.length,
    imageUnsupported: article ? mediaArticleHasImages({
      bodyHtml: article.bodyHtml,
      assetCount: article.assets.length,
    }) : false,
    hasExternalLinks: article ? mediaArticleHasLinks(article.bodyHtml) : false,
    mediaRecommendationError:
      result.mode === 'BODY' || result.mode === 'REWRITE'
        ? mediaRecommendationError
        : undefined,
    actionsEnabled: true,
    saveToAssetsEnabled: false,
  }
}

import { isApiError } from '../../api/apiError'
import { geoMerchantNarrative } from '../../features/geo/merchantNarrative'
import type {
  CreateGeoTopicRequest,
  CreateGeoPlanDraftRequest,
  GeoPlan,
  GeoPlanPage,
  GeoPlanSchedule,
  GeoPlatform as GeoPlatformDto,
  GeoReport,
  GeoReportSummary,
  GeoRun,
  GeoRunSummary,
  GeoKeywordInput,
  GeoQuestionInput,
  GeoTopic,
  GeoTopicPage,
  GeoTopicSummary,
  ReplaceGeoCompetitorsRequest,
  ReplaceGeoKeywordsRequest,
  ReplaceGeoQuestionsRequest,
  UpdateGeoTopicRequest,
} from '../../api/geoApi'
import type { Competitor } from '../../api/catalogApi'
import { taskFailureMessage } from '../../features/task-common/taskFailurePresentation'
import { GEO_REPORT_UNAVAILABLE_MESSAGE } from '../../features/geo'
import type {
  GeoCompetitorView,
  GeoKeywordDraftView,
  GeoKeywordView,
  GeoPlatformView,
  GeoPlanDraftView,
  GeoPlanPaginationView,
  GeoPlanScheduleView,
  GeoPlanView,
  GeoQuestionDraftView,
  GeoQuestionView,
  GeoRecentRunView,
  GeoReportDetailView,
  GeoReportListItemView,
  GeoTopicFormValues,
  GeoTopicPaginationView,
  GeoTopicView,
  GeoViewError,
  GeoWeekday,
} from '../../features/geo'

export function toGeoViewError(error: unknown): GeoViewError | null {
  if (!error) return null
  if (isApiError(error)) {
    return { message: error.message, traceId: error.traceId }
  }
  return { message: '请求失败，请稍后重试。' }
}

/**
 * Load failures for one report.
 *
 * 403 and 404 both become the same sentence. They arrive here as different backend messages, and
 * echoing those would turn this page into an existence oracle for other accounts' report ids.
 * Everything else — 5xx, network, 401 — keeps its own message, because collapsing those would hide
 * a retryable outage behind "已不可用".
 */
export function toGeoReportLoadError(error: unknown): GeoViewError | null {
  if (isApiError(error) && (error.status === 403 || error.status === 404)) {
    return { message: GEO_REPORT_UNAVAILABLE_MESSAGE, traceId: error.traceId }
  }
  return toGeoViewError(error)
}

export function toGeoDeleteError(error: unknown): GeoViewError | null {
  if (!error) return null
  if (isApiError(error) && error.status === 409 && error.code === 'RESOURCE_IN_USE') {
    return {
      message: `${error.message} 该专题存在正式配置或审计引用，不能删除。`,
      traceId: error.traceId,
    }
  }
  return toGeoViewError(error)
}

function topicBase(
  topic: GeoTopicSummary | GeoTopic,
  spaceName?: string,
  productName?: string,
): Omit<GeoTopicView, 'keywords' | 'platformCodes'> {
  return {
    id: topic.id,
    objectId: topic.productId ?? topic.spaceId,
    spaceId: topic.spaceId,
    productId: topic.productId ?? undefined,
    spaceName,
    productName,
    name: topic.name,
    region: topic.region ?? undefined,
    version: topic.version,
    updatedAt: topic.updatedAt,
  }
}

export function toGeoTopicSummaryView(
  topic: GeoTopicSummary,
  spaceName?: string,
  productName?: string,
): GeoTopicView {
  return {
    ...topicBase(topic, spaceName, productName),
    keywords: [],
    platformCodes: [],
  }
}

export function toGeoTopicView(
  topic: GeoTopic,
  spaceName?: string,
  productName?: string,
): GeoTopicView {
  return {
    ...topicBase(topic, spaceName, productName),
    keywords: topic.keywords.map((keyword) => keyword.text),
    platformCodes: [],
    questionCount: topic.questions.length,
    competitorSetLabel:
      topic.competitorSetVersion === null
        ? undefined
        : `v${topic.competitorSetVersion}`,
  }
}

export function toGeoTopicPagination(
  page: GeoTopicPage | undefined,
  fallbackPage: number,
  fallbackSize: number,
): GeoTopicPaginationView {
  return {
    current: page?.page ?? fallbackPage,
    pageSize: page?.size ?? fallbackSize,
    total: page?.total ?? 0,
  }
}

export function toGeoTopicFormValues(topic: GeoTopic): GeoTopicFormValues {
  return {
    spaceId: topic.spaceId,
    productId: topic.productId ?? undefined,
    name: topic.name,
    region: topic.region ?? undefined,
  }
}

function optionalText(value?: string): string | undefined {
  const normalized = value?.trim()
  return normalized || undefined
}

export function toCreateGeoTopicRequest(
  values: GeoTopicFormValues,
): CreateGeoTopicRequest {
  return {
    spaceId: values.spaceId,
    productId: values.productId,
    name: values.name.trim(),
    region: optionalText(values.region),
  }
}

export function toUpdateGeoTopicRequest(
  values: GeoTopicFormValues,
  topic: GeoTopic,
): UpdateGeoTopicRequest {
  return {
    version: topic.version,
    productId: values.productId ?? null,
    name: values.name.trim(),
    region: optionalText(values.region) ?? null,
  }
}

export function toGeoKeywordViews(topic: GeoTopic): GeoKeywordView[] {
  return topic.keywords.map((keyword) => ({
    id: keyword.id,
    sequence: keyword.sequence,
    kind: keyword.type,
    text: keyword.text,
  }))
}

export function toGeoQuestionViews(topic: GeoTopic): GeoQuestionView[] {
  return topic.questions.map((question) => ({
    id: question.id,
    sequence: question.sequence,
    text: question.text,
    typeLabel: '品牌发现',
    category: question.category,
    source: question.source,
    enabled: question.enabled,
    status: question.enabled ? 'ENABLED' : 'DISABLED',
    relatedKeywords: [],
    updatedAt: question.updatedAt,
  }))
}

export function toGeoKeywordDrafts(topic: GeoTopic): GeoKeywordDraftView[] {
  return topic.keywords.map((keyword) => ({
    id: keyword.id,
    type: keyword.type,
    text: keyword.text,
  }))
}

export function toGeoQuestionDrafts(topic: GeoTopic): GeoQuestionDraftView[] {
  return topic.questions.map((question) => ({
    id: question.id,
    text: question.text,
    enabled: question.enabled,
  }))
}

export function toReplaceGeoKeywordsRequest(
  version: number,
  drafts: readonly GeoKeywordDraftView[],
): ReplaceGeoKeywordsRequest {
  const keywords: GeoKeywordInput[] = drafts.map((keyword) => ({
    type: keyword.type,
    text: keyword.text.trim(),
  }))
  return { version, keywords }
}

export function toReplaceGeoQuestionsRequest(
  version: number,
  drafts: readonly GeoQuestionDraftView[],
): ReplaceGeoQuestionsRequest {
  const questions: GeoQuestionInput[] = drafts.map((question) => ({
    category: 'BRAND_DISCOVERY',
    source: 'MANUAL',
    text: question.text.trim(),
    enabled: question.enabled,
  }))
  return { version, questions }
}

export function toReplaceGeoCompetitorsRequest(
  version: number,
  competitorIds: readonly string[],
): ReplaceGeoCompetitorsRequest {
  return { version, competitorIds: [...competitorIds] }
}

export function toGeoCompetitorView(competitor: Competitor): GeoCompetitorView {
  return {
    id: competitor.id,
    name: competitor.name,
    aliases: [...competitor.aliases],
    competitorType: competitor.type,
  }
}

export function toGeoPlatformView(platform: GeoPlatformDto): GeoPlatformView {
  return {
    code: platform.code,
    name: platform.name,
    shortLabel: platform.name,
    availability: platform.selectable
      ? 'AVAILABLE'
      : platform.configured
        ? 'MAINTENANCE'
        : 'DISABLED',
    availabilityLabel: platform.liveStatus ?? undefined,
  }
}

export function toGeoPlanScheduleView(
  schedule: GeoPlanSchedule,
): GeoPlanScheduleView {
  switch (schedule.type) {
    case 'HOURLY':
    case 'DAILY':
      return {
        frequency: schedule.type,
        localTime: schedule.localTime,
        timeZone: schedule.timeZone,
      }
    case 'WEEKLY':
      return {
        frequency: 'WEEKLY',
        localTime: schedule.localTime,
        timeZone: schedule.timeZone,
        weekdays: schedule.daysOfWeek.filter(
          (day): day is GeoWeekday => Number.isSafeInteger(day) && day >= 1 && day <= 7,
        ),
      }
    case 'INTERVAL_DAYS':
      return {
        frequency: 'INTERVAL_DAYS',
        localTime: schedule.localTime,
        timeZone: schedule.timeZone,
        intervalDays: schedule.intervalDays,
      }
  }
}

export function toGeoPlanScheduleRequest(
  schedule: GeoPlanScheduleView,
): GeoPlanSchedule {
  switch (schedule.frequency) {
    case 'HOURLY':
    case 'DAILY':
      return {
        type: schedule.frequency,
        localTime: schedule.localTime,
        timeZone: schedule.timeZone,
      }
    case 'WEEKLY':
      return {
        type: 'WEEKLY',
        localTime: schedule.localTime,
        timeZone: schedule.timeZone,
        daysOfWeek: [...schedule.weekdays],
      }
    case 'INTERVAL_DAYS':
      return {
        type: 'INTERVAL_DAYS',
        localTime: schedule.localTime,
        timeZone: schedule.timeZone,
        intervalDays: schedule.intervalDays,
      }
  }
}

export function toGeoPlanView(plan: GeoPlan, topicName?: string): GeoPlanView {
  return {
    id: plan.id,
    topicId: plan.topicId,
    name: plan.name,
    topicName: topicName ?? `专题 ${plan.topicId}`,
    questionIds: [...plan.questionIds],
    platformCodes: [...plan.platformCodes],
    mode: plan.sampleMode,
    schedule: toGeoPlanScheduleView(plan.schedule),
    status: plan.status,
    nextRunAt: plan.nextRunAt ?? undefined,
    lastScheduledSlot: plan.lastScheduledSlot ?? undefined,
    pauseReason: plan.pauseReason ?? undefined,
    estimatedPointsPerRun: plan.estimatedPointsPerRun,
    maxPointsPerRun: plan.maxPointsPerRun,
    pricingVersionId: plan.pricingVersionId,
    version: plan.version,
    createdAt: plan.createdAt,
    updatedAt: plan.updatedAt,
  }
}

export function toGeoPlanDraftView(plan: GeoPlan): GeoPlanDraftView {
  return {
    name: plan.name,
    topicId: plan.topicId,
    questionIds: [...plan.questionIds],
    platformCodes: [...plan.platformCodes],
    mode: plan.sampleMode,
    schedule: toGeoPlanScheduleView(plan.schedule),
    maxPointsPerRun: plan.maxPointsPerRun,
  }
}

export function toCreateGeoPlanDraftRequest(
  draft: GeoPlanDraftView,
): CreateGeoPlanDraftRequest {
  if (!draft.topicId || draft.maxPointsPerRun === undefined) {
    throw new Error('GEO 计划草稿不完整。')
  }
  return {
    topicId: draft.topicId,
    name: draft.name.trim(),
    schedule: toGeoPlanScheduleRequest(draft.schedule),
    questionIds: [...draft.questionIds],
    platformCodes: [...draft.platformCodes] as CreateGeoPlanDraftRequest['platformCodes'],
    sampleMode: draft.mode,
    maxPointsPerRun: draft.maxPointsPerRun,
  }
}

export function toGeoPlanPagination(
  page: GeoPlanPage | undefined,
  fallbackPage: number,
  fallbackSize: number,
): GeoPlanPaginationView {
  return {
    current: page?.page ?? fallbackPage,
    pageSize: page?.size ?? fallbackSize,
    total: page?.total ?? 0,
  }
}

function platformView(
  code: string,
  platforms: readonly GeoPlatformView[],
): GeoPlatformView {
  return platforms.find((platform) => platform.code === code) ?? {
    code,
    name: code,
    availability: 'DISABLED',
  }
}

export function toGeoRecentRunView(
  run: GeoRunSummary,
  report?: GeoReportSummary,
  topicName?: string,
): GeoRecentRunView {
  return {
    id: run.id,
    name: topicName ? `${topicName}监测` : 'GEO 监测任务',
    scopeLabel: `${run.sampleMode === 'DEEP' ? '深度' : '标准'}监测 · ${run.expectedUnits} 个采样单元`,
    status: run.status,
    settledPoints: report?.settledPoints,
    createdAt: run.createdAt,
    reportId: report?.id,
  }
}

export function toGeoCollectionTrend(runs: readonly GeoRunSummary[]) {
  return runs.filter((run) => run.finishedAt && run.expectedUnits > 0)
    .slice().sort((a, b) => Date.parse(a.finishedAt!) - Date.parse(b.finishedAt!))
    .map((run, index) => ({
      label: `${index + 1} · ${new Date(run.finishedAt!).toLocaleString('zh-CN', {
        month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
      })}`,
      value: Math.round(run.succeededUnits / run.expectedUnits * 100),
    }))
}

export function toGeoReportListItemView(
  report: GeoReportSummary,
  topicName?: string,
): GeoReportListItemView {
  const objectName = topicName ?? `专题 ${report.topicId}`
  return {
    id: report.id,
    name: `${objectName} GEO 报告`,
    objectName,
    typeLabel: 'GEO 监测',
    platformCodes: [],
    primaryMetric: `${report.sampleSize} 个样本`,
    generatedAt: report.generatedAt,
    archivedAt: report.archivedAt,
    status: report.status,
    archived: Boolean(report.archivedAt),
  }
}

export function toGeoReportDetailView(
  report: GeoReport,
  run: GeoRun,
  platforms: readonly GeoPlatformView[],
): GeoReportDetailView {
  const narrative = geoMerchantNarrative(report, run)
  // Old deployments/reports may lack this projection. Never parse prose or the topic label as a target.
  const visibility = report.visibility
  const assessed = visibility?.assessmentStatus === 'AVAILABLE'
  const visibilitySamples = new Map((visibility?.samples ?? []).map((sample) => [sample.unitKey, sample]))
  const samples = report.observations.map((observation) => ({
    id: observation.unitKey,
    questionId: observation.questionId,
    question: observation.questionText,
    platform: platformView(observation.platformCode, platforms),
    sampleRound: observation.sampleNo,
    startedAt: observation.startedAt,
    sampledAt: observation.finishedAt,
    executionStatus: observation.status,
    analysisStatus: observation.status === 'SUCCEEDED' && assessed
      ? visibilitySamples.get(observation.unitKey)?.mentioned === true ? 'MENTIONED' as const
        : visibilitySamples.get(observation.unitKey)?.mentioned === false ? 'NOT_MENTIONED' as const : undefined
      : undefined,
    provider: observation.provider,
    model: observation.model,
    latencyMs: observation.latencyMs,
    providerCallCount: observation.providerCallCount,
    usage: {
      inputTokens: observation.usage.inputTokens,
      outputTokens: observation.usage.outputTokens,
      searchRequests: observation.usage.searchRequests,
      monetaryCost: observation.usage.monetaryCost ?? undefined,
      currency: observation.usage.currency ?? undefined,
    },
    originalAnswer: observation.answerText ?? undefined,
    citations: observation.citations.map((citation) => ({
      id: `${observation.unitKey}:citation:${citation.sequence}`,
      title: citation.title ?? citation.domain ?? '引用来源',
      url: citation.url,
      site: citation.domain ?? undefined,
      excerpt: citation.snippet ?? undefined,
      capturedAt: citation.observedAt,
    })),
    failureReason: observation.stableErrorCode === 'GEO_PROVIDER_TIMEOUT'
      ? '平台响应超时，本次没有取得有效回答。已保留其他成功回答；这次失败不代表商品未被提及。'
      : taskFailureMessage(observation.stableErrorCode, {
      status: observation.status,
    }),
  }))

  const platformPerformance = report.platformCodes.map((code) => {
    const metric = visibility?.platforms.find((platform) => platform.platformCode === code)
    const rate = assessed && metric?.mentionRate !== null ? metric?.mentionRate : undefined
    const primaryValue = rate !== undefined ? `${rate.toLocaleString('zh-CN', { maximumFractionDigits: 1 })}%` : '未评估'
    return {
      platform: platformView(code, platforms),
      primaryValue,
      percentage: rate,
      canViewEvidence: report.observations.some((observation) => observation.platformCode === code && observation.status === 'SUCCEEDED'),
      primaryLabel: 'AI 可见度（名称提及率）',
      evidenceLabel: rate !== undefined && metric
        ? `${metric.validSamples} 条有效回答中，${metric.mentionedSamples} 条明确提及`
        : metric?.validSamples ? `已有 ${metric.validSamples} 条有效回答，监测目标尚不能可靠判定` : '本次没有可评估的有效回答',
      unavailableLabel: metric ? `计划 ${metric.expectedSamples} 条 · 失败 ${metric.failedSamples} 条 · 缺失 ${metric.missingSamples} 条` : '历史报告尚未提供目标提及评估',
      secondaryValue: metric ? `${metric.mentionedSamples ?? '—'} / ${metric.validSamples} 条有效回答` : '未提供',
      statusLabel: rate === undefined ? '未评估' : '已评估',
    }
  })

  const missingSamples = [...new Set([
    ...report.synthesis.missingSamples,
    ...report.observations.filter((observation) => observation.status === 'FAILED').map((observation) => observation.unitKey),
  ])].map((unitKey) => {
    const observation = report.observations.find((item) => item.unitKey === unitKey)
    if (observation) return `${platformView(observation.platformCode, platforms).name} · ${observation.questionText} · 第 ${observation.sampleNo} 轮（未取得回答）`
    const [questionId, platformCode, round] = unitKey.split(':')
    const question = report.questions.find((item) => item.id === questionId)
    return question && platformCode && /^\d+$/.test(round ?? '')
      ? `${platformView(platformCode, platforms).name} · ${question.text} · 第 ${round} 轮（未取得回答）`
      : '有回答未取得，可在下方逐条查看平台结果。'
  })

  return {
    id: report.id,
    name: `${visibility?.target?.name ?? report.topic.name} GEO 报告`,
    objectName: visibility?.target?.name ?? '历史报告未保存可核验的监测名称',
    topicName: report.topic.name,
    visibilityAvailable: assessed,
    visibilityNotice: assessed
      ? `以下按本次冻结的${visibility.target?.kind === 'BRAND' ? '品牌' : '商品'}名称“${visibility.target?.name}”及其明确别名，统计有效回答中的字面提及。`
      : visibility?.unavailableReason === 'NO_VALID_ANSWERS'
        ? '本次没有有效回答，暂时无法评估可见度；失败不等于未提及。'
        : visibility?.unavailableReason === 'AMBIGUOUS_TARGET_NAMES'
          ? '监测名称无法可靠区分商品，暂不计算可见度。请完善商品名称后重新监测；原始回答仍可查看。'
          : '这份历史报告未保存可核验的商品或品牌名称，暂不计算可见度。原始回答仍可查看；新监测会保存评估对象。',
    monitoredAt: report.generatedAt,
    sampleCount: report.sampleSize,
    sampleRounds: samples.reduce(
      (maximum, sample) => Math.max(maximum, sample.sampleRound ?? 0),
      0,
    ),
    platformCount: report.platformCodes.length,
    competitorSetVersion:
      run.competitorSetVersion === null
        ? undefined
        : `v${run.competitorSetVersion}`,
    modeLabel: run.sampleMode === 'DEEP' ? '深度监测' : '标准监测',
    status: report.status,
    metrics: platformPerformance.map((platform) => ({ key: platform.platform.code, label: `${platform.platform.name} AI 可见度`, value: platform.primaryValue, description: platform.evidenceLabel })),
    billingMetrics: [
      { key: 'settled-points', label: '结算积分', value: String(report.settledPoints) },
      { key: 'released-points', label: '退回积分', value: String(report.releasedPoints) },
    ],
    trend: [],
    platformPerformance,
    suggestions: narrative.recommendations.map((recommendation) => ({
      // The Java service owns recommendation identity.  Do not reconstruct it
      // from the current array position: ordering can change when the report
      // is reloaded, while the article workflow validates this exact id.
      id: recommendation.id,
      title: recommendation.title,
      description: recommendation.rationale,
      priority:
        recommendation.priority === 'P0'
          ? 'HIGH'
          : recommendation.priority === 'P1'
            ? 'MEDIUM'
            : 'LOW',
    })),
    samples,
    factualSummary: narrative.factualSummary,
    summary: narrative.summary,
    findings: narrative.findings,
    coverageGaps: narrative.coverageGaps,
    citationStructure: narrative.citationStructure,
    competitorSignals: narrative.competitorSignals,
    contentBlueprint: narrative.contentBlueprint,
    missingSamples: [...new Set(missingSamples), ...(report.missingUnits > 0 ? [`另有 ${report.missingUnits} 条计划回答未返回，暂时无法判断是否提及。`] : [])],
    methodologyNotice:
      '名称提及率 = 明确提及目标名称的有效回答数 ÷ 有效回答数。失败和缺失单独列出，不作为未提及；字面提及不等于正面推荐，也不代表平台官方排名或市场份额。',
    // Java's own summariser flag. The view carries it verbatim so the report surface can state that
    // no AI conclusion was formed instead of inferring insufficiency from the content it received.
    methodVersion: report.methodVersion,
    interpretation: report.interpretation,
    sampleScope: {
      planned: report.expectedUnits,
      succeeded: report.succeededUnits,
      failed: report.failedUnits,
      missing: report.missingUnits,
    },
  }
}

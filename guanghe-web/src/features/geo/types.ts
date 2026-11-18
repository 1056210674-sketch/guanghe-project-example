/**
 * Presentation-layer view models for the GEO workspace.
 *
 * These are intentionally not browser API DTOs. Page containers map the
 * generated Java Browser API contract into these presentation-only views.
 */

import type { CompetitorType } from '../business-data/types'
import type { TaskCenterTaskStatus } from '../task-center/types'
import type { GeoReportInterpretation } from '../../api/geoApi'

export type GeoEntityId = string

export interface GeoViewError {
  message: string
  traceId?: string
}

export interface GeoAsyncViewProps {
  loading: boolean
  error: GeoViewError | null
  onRetry?: () => void
}

export type GeoPlatformAvailability =
  | 'AVAILABLE'
  | 'MAINTENANCE'
  | 'DISABLED'

export interface GeoPlatformView {
  code: string
  name: string
  shortLabel?: string
  availability: GeoPlatformAvailability
  availabilityLabel?: string
}

export type GeoMetricTone = 'DEFAULT' | 'POSITIVE' | 'WARNING' | 'NEGATIVE'

export interface GeoMetricView {
  key: string
  label: string
  value: string
  comparison?: string
  description?: string
  tone?: GeoMetricTone
}

export interface GeoTrendPointView {
  label: string
  value: number
  comparisonValue?: number
}

export type GeoRunStatus = TaskCenterTaskStatus

export type GeoPlanStatus = 'ACTIVE' | 'PAUSED'

export type GeoMonitoringMode = 'STANDARD' | 'DEEP'

export interface GeoPlatformPerformanceView {
  platform: GeoPlatformView
  primaryValue: string
  primaryLabel?: string
  secondaryValue?: string
  secondaryLabel?: string
  change?: string
  statusLabel?: string
  percentage?: number
  evidenceLabel?: string
  unavailableLabel?: string
  canViewEvidence?: boolean
}

export interface GeoRecentRunView {
  id: GeoEntityId
  name: string
  scopeLabel?: string
  questionCount?: number
  platformCount?: number
  status: GeoRunStatus
  settledPoints?: number
  createdAt?: string
  reportId?: GeoEntityId
}

export type GeoSuggestionPriority = 'HIGH' | 'MEDIUM' | 'LOW'

export interface GeoSuggestionView {
  id: GeoEntityId
  title: string
  description?: string
  priority: GeoSuggestionPriority
}

export type GeoMonitoringObjectKind = 'BRAND' | 'PRODUCT'

export interface GeoMonitoringObjectView {
  id: GeoEntityId
  name: string
  kind: GeoMonitoringObjectKind
  statusLabel?: string
  referenceCode?: string
}

export interface GeoTopicView {
  id: GeoEntityId
  objectId?: GeoEntityId
  spaceId?: GeoEntityId
  productId?: GeoEntityId
  spaceName?: string
  productName?: string
  name: string
  region?: string
  keywords: readonly string[]
  competitorSetLabel?: string
  platformCodes: readonly string[]
  questionCount?: number
  reportCount?: number
  planCount?: number
  planStatus?: GeoPlanStatus
  scheduleLabel?: string
  lastRunAt?: string
  version?: number
  updatedAt?: string
}

export type GeoKeywordKind = 'CORE' | 'DISTILLED' | 'BRAND' | 'SCENE'
export type GeoKeywordStatus = 'ENABLED' | 'DISABLED' | 'PENDING_CONFIRMATION'

export interface GeoKeywordView {
  id: GeoEntityId
  text: string
  kind: GeoKeywordKind
  status?: GeoKeywordStatus
  sequence?: number
}

export type GeoQuestionSource = 'MANUAL'
export type GeoQuestionStatus = 'ENABLED' | 'DISABLED' | 'PENDING_CONFIRMATION'

export interface GeoQuestionView {
  id: GeoEntityId
  text: string
  typeLabel: string
  source: GeoQuestionSource
  relatedKeywords: readonly string[]
  status: GeoQuestionStatus
  category?: 'BRAND_DISCOVERY'
  enabled?: boolean
  sequence?: number
  updatedAt?: string
}

export interface GeoTopicPaginationView {
  current: number
  pageSize: number
  total: number
}

export interface GeoTopicFormValues {
  spaceId: GeoEntityId
  productId?: GeoEntityId
  name: string
  region?: string
}

export interface GeoKeywordDraftView {
  id: string
  type: GeoKeywordKind
  text: string
}

export interface GeoQuestionDraftView {
  id: string
  text: string
  enabled: boolean
}

export interface GeoQuestionEvidenceView {
  id: GeoEntityId
  questionId: GeoEntityId
  title: string
  url: string
  excerpt?: string
  capturedAt?: string
}

export type GeoQuestionGenerationScope = 'ALL_KEYWORDS' | 'NEW_KEYWORDS'

export interface GeoQuestionGroupOption {
  id: GeoEntityId
  name: string
  questionCount: number
}

export interface GeoSelectOption {
  id: GeoEntityId
  label: string
}

export interface GeoQuoteView {
  statusLabel?: string
  questionCount?: number
  platformCount?: number
  sampleRounds?: number
  estimatedUnits?: number
  estimatedPoints?: number
  availablePoints?: number
  pricingVersion?: string
  settlementNotice?: string
}

export interface GeoManualMonitoringIntent {
  topicId?: GeoEntityId
  questionGroupId?: GeoEntityId
  questionIds: readonly GeoEntityId[]
  platformCodes: readonly string[]
  mode: GeoMonitoringMode
}

export type GeoSampleExecutionStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'RETRY_WAIT'
  | 'SUCCEEDED'
  | 'FAILED'

export type GeoSampleAnalysisStatus =
  | 'PENDING'
  | 'MENTIONED'
  | 'NOT_MENTIONED'

export interface GeoCitationView {
  id: GeoEntityId
  title: string
  url: string
  site?: string
  excerpt?: string
  capturedAt?: string
}

export interface GeoSampleUsageView {
  inputTokens: number
  outputTokens: number
  searchRequests: number
  monetaryCost?: string
  currency?: string
}

export interface GeoAnswerSampleView {
  id: GeoEntityId
  questionId: GeoEntityId
  question: string
  platform: GeoPlatformView
  sampleRound?: number
  startedAt?: string
  sampledAt?: string
  executionStatus: GeoSampleExecutionStatus
  analysisStatus?: GeoSampleAnalysisStatus
  provider?: string
  model?: string
  latencyMs?: number
  providerCallCount?: number
  usage?: GeoSampleUsageView
  isRecommended?: boolean
  recommendationPosition?: string
  sentimentLabel?: string
  originalAnswer?: string
  citations: readonly GeoCitationView[]
  failureReason?: string
  retrySummary?: string
}

export type GeoPlanFrequency = 'HOURLY' | 'DAILY' | 'WEEKLY' | 'INTERVAL_DAYS'
export type GeoWeekday = 1 | 2 | 3 | 4 | 5 | 6 | 7

export type GeoPlanScheduleView =
  | {
      frequency: 'HOURLY' | 'DAILY'
      localTime: string
      timeZone: string
    }
  | {
      frequency: 'WEEKLY'
      weekdays: readonly GeoWeekday[]
      localTime: string
      timeZone: string
    }
  | {
      frequency: 'INTERVAL_DAYS'
      intervalDays: number
      localTime: string
      timeZone: string
    }

export interface GeoPlanView {
  id: GeoEntityId
  topicId: GeoEntityId
  name: string
  topicName: string
  questionIds: readonly GeoEntityId[]
  platformCodes: readonly string[]
  mode: GeoMonitoringMode
  schedule: GeoPlanScheduleView
  status: GeoPlanStatus
  nextRunAt?: string
  lastScheduledSlot?: string
  pauseReason?: string
  estimatedPointsPerRun: number
  maxPointsPerRun: number
  pricingVersionId: GeoEntityId
  version: number
  createdAt: string
  updatedAt: string
}

export interface GeoPlanDraftView {
  name: string
  topicId?: GeoEntityId
  questionIds: readonly GeoEntityId[]
  platformCodes: readonly string[]
  mode: GeoMonitoringMode
  schedule: GeoPlanScheduleView
  maxPointsPerRun?: number
}

export interface GeoPlanAuthorizationView {
  draftId: GeoEntityId
  title: string
  topicName: string
  questionLabels: readonly string[]
  platformNames: readonly string[]
  mode: GeoMonitoringMode
  schedule: GeoPlanScheduleView
  estimatedPointsPerRun: number
  maxPointsPerRun: number
  pricingVersionId: GeoEntityId
  expiresAt: string
  confirmEnabled: boolean
}

export interface GeoPlanPaginationView {
  current: number
  pageSize: number
  total: number
}

export type GeoPlanDraftChange =
  | { field: 'NAME'; value: string }
  | { field: 'TOPIC'; value: GeoEntityId | undefined }
  | { field: 'QUESTIONS'; value: readonly GeoEntityId[] }
  | { field: 'PLATFORMS'; value: readonly string[] }
  | { field: 'MODE'; value: GeoMonitoringMode }
  | { field: 'FREQUENCY'; value: GeoPlanFrequency }
  | { field: 'LOCAL_TIME'; value: string }
  | { field: 'TIME_ZONE'; value: string }
  | { field: 'WEEKDAYS'; value: readonly GeoWeekday[] }
  | { field: 'INTERVAL_DAYS'; value: number | undefined }
  | { field: 'MAX_POINTS_PER_RUN'; value: number | undefined }

export interface GeoSkippedRunView {
  id: GeoEntityId
  planId: GeoEntityId
  occurredAt: string
  reason: string
  detail?: string
}

export type GeoCompetitorType = CompetitorType

export interface GeoCompetitorView {
  id: GeoEntityId
  name: string
  aliases: readonly string[]
  competitorType: GeoCompetitorType
  appearanceCount?: number
}

export interface GeoCompetitorCandidateView {
  id: GeoEntityId
  name: string
  platformCodes: readonly string[]
  evidenceSummary?: string
  appearanceCount?: number
  confidence?: string
}

export interface GeoCompetitorSetVersionView {
  id: GeoEntityId
  label: string
  effectiveNotice?: string
  changeSummary?: string
}

export type GeoReportStatus = 'SUCCEEDED' | 'PARTIAL' | 'FAILED'

export interface GeoReportListItemView {
  id: GeoEntityId
  name: string
  objectName: string
  typeLabel: string
  platformCodes: readonly string[]
  primaryMetric?: string
  generatedAt?: string
  archivedAt?: string | null
  status: GeoReportStatus
  archived: boolean
}

export interface GeoReportFilterView {
  objectId?: GeoEntityId
  type?: string
  status?: GeoReportStatus
  generatedFrom?: string
  generatedTo?: string
  search?: string
}

/**
 * Which summariser Java credits for this report.
 *
 * `geo-deterministic-v1` means no AI synthesis was formed. This says nothing about whether valid
 * answers exist or whether the frozen target was mentioned; visibility is a separate server result.
 */
export type GeoReportMethodVersion =
  | 'geo-synthesis-v1'
  | 'geo-deterministic-v1'

/** The sample counts the conclusion rests on, kept separate from the KPI row that also shows points. */
export interface GeoSampleScopeView {
  planned: number
  succeeded: number
  failed: number
  missing: number
}

export interface GeoReportDetailView {
  id: GeoEntityId
  name: string
  objectName: string
  monitoredAt?: string
  sampleCount?: number
  sampleRounds?: number
  platformCount?: number
  competitorSetVersion?: string
  modeLabel?: string
  status: GeoReportStatus
  metrics: readonly GeoMetricView[]
  trend: readonly GeoTrendPointView[]
  platformPerformance: readonly GeoPlatformPerformanceView[]
  suggestions: readonly GeoSuggestionView[]
  samples: readonly GeoAnswerSampleView[]
  summary?: string
  factualSummary?: string
  findings?: readonly string[]
  coverageGaps?: readonly string[]
  citationStructure?: readonly string[]
  competitorSignals?: readonly string[]
  contentBlueprint?: readonly string[]
  missingSamples?: readonly string[]
  methodologyNotice?: string
  methodVersion?: GeoReportMethodVersion
  sampleScope?: GeoSampleScopeView
  visibilityNotice?: string
  visibilityAvailable?: boolean
  topicName?: string
  billingMetrics?: readonly GeoMetricView[]
  interpretation?: GeoReportInterpretation
}

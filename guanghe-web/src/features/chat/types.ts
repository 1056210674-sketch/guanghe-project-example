import type { ReactNode } from 'react'
import type { ConversationBusinessOutcome, ConversationMessageGeoContext } from '../../api/conversationApi'
import type {
  ArticleWorkflowStage,
  ArticleMediaRecommendationSetView,
  ArticleViewError,
} from '../articles/types'
import type { CreativeSourceMode } from './creativeGrounding'
import type { ExecutionTimeline } from './live/executionTimeline'

/**
 * Presentation-only models for the chat workspace.
 *
 * These types are intentionally independent from Java or Python contracts.
 * Generated API DTOs can be adapted into them after the browser contract is
 * frozen in a later milestone.
 */

export type ChatViewId = string

export type ChatStageStatusTone =
  | 'neutral'
  | 'info'
  | 'success'
  | 'warning'
  | 'danger'

export interface ChatStageView {
  id: ChatViewId
  label: string
  statusLabel: string
  statusTone: ChatStageStatusTone
}

export interface ChatStageTransitionView {
  previousStage: ChatStageView
  currentStage: ChatStageView
}

export interface ChatTaskContextDetailRowView {
  label: string
  value: string
}

export interface ChatReferenceAssetView {
  id: ChatViewId
  name: string
  thumbnailUrl?: string
}

export interface ChatTaskContextView {
  id: ChatViewId
  title: string
  detailRows: readonly ChatTaskContextDetailRowView[]
  referenceAssets?: readonly ChatReferenceAssetView[]
  sourceLabel?: string
}

export interface ChatHistoricalContextView {
  id: ChatViewId
  title: string
  statusLabel: string
  summaryRows: readonly ChatTaskContextDetailRowView[]
  referenceAssets?: readonly ChatReferenceAssetView[]
}

export interface ChatTaskFocusOptionView {
  taskId: ChatViewId
  label: string
  statusLabel?: string
  disabled?: boolean
}

export interface ChatSessionView {
  id: ChatViewId
  title: string
  preview?: string
  updatedAt?: string
}

export interface ActiveArticleWorkflowView {
  articleId: ChatViewId
  title: string
  stageLabel: string
  nextActionLabel: string
}

export interface ArticleWorkflowAnchorView {
  articleId: ChatViewId
  title: string
  workflowStage: ArticleWorkflowStage
}

export type ChatMessageRole = 'USER' | 'ASSISTANT'

export interface ChatArticlePreparationView {
  id: ChatViewId
  /** Real preparation status; fetching a historical record is not running work. */
  status?: 'loading' | 'running' | 'succeeded' | 'failed' | 'unavailable'
  observedAt?: number
  statusLabel: string
  description: string
  tone: ChatStageStatusTone
  loading: boolean
  openLabel: string
  stages: readonly string[]
}

export interface ChatClarificationOptionView {
  id: ChatViewId
  title: string
  description: string
  icon: ReactNode
  disabled?: boolean
}

export interface ChatResourceSelectionView {
  candidateSetId: string
  available: boolean
  candidates: readonly {
    choiceId: string
    name: string
    kindLabel: string
    spaceName?: string
    reportLabel?: string
  }[]
}

export interface ArticleDraftPreviewView {
  id: ChatViewId
  title: string
  versionLabel: string
  taskStage?: 'TITLE' | 'OUTLINE' | 'BODY' | 'REWRITE'
  workflowStage?: ArticleWorkflowStage
  titleCandidates?: readonly string[]
  creativePlan?: {
    version: number
    summary: string
    coreMessage: string
    targetAudience: string
    sections: readonly {
      key: string
      sequence: number
      level: number
      heading: string
      brief: string
    }[]
    tone: string
    targetLength: 'SHORT' | 'MEDIUM' | 'LONG'
    targetPlatform?: string
    riskNotes: readonly string[]
  }
  coverImageUrl?: string
  excerpt?: string
  wordCount?: number
  estimatedReadingMinutes?: number
  generatedAt?: string
  mediaRecommendations?: ArticleMediaRecommendationSetView
  mediaRecommendationError?: ArticleViewError
  imageUnsupported?: boolean
  imageCount?: number
  hasExternalLinks?: boolean
  actionsEnabled: boolean
  saveToAssetsEnabled?: boolean
}

export type ParallelTaskStatusTone =
  | 'neutral'
  | 'info'
  | 'success'
  | 'warning'
  | 'danger'

export interface ChatTaskStatusSummaryView {
  taskId: ChatViewId
  label: string
  statusLabel: string
  statusTone: ParallelTaskStatusTone
  focusEnabled: boolean
}

export interface ParallelTaskItemView {
  taskId: ChatViewId
  taskNumber: string
  taskType: string
  statusLabel: string
  statusTone: ParallelTaskStatusTone
  productName?: string
  productImageUrl?: string
  progressLabel?: string
  progressPercent?: number
  timingLabel?: string
  detailEnabled: boolean
}

export interface ParallelTaskGroupView {
  id: ChatViewId
  title: string
  tasks: readonly ParallelTaskItemView[]
}

export interface TaskOutcomeItemView {
  id: ChatViewId
  label: string
  statusLabel: string
  statusTone: ParallelTaskStatusTone
}

export interface TaskSettlementView {
  settledPoints?: number
  returnedPoints?: number
}

export type TaskOutcomeActionType =
  | 'VIEW_COMPLETED_REPORT'
  | 'VIEW_GEO_TREND'
  | 'VIEW_GEO_COMPETITORS'
  | 'CREATE_ARTICLE_FROM_GEO'
  | 'PREPARE_RETRY_FAILED_ITEMS'
  | 'PREPARE_ADJUST_AND_RETRY'
  | 'PREPARE_RETRY'
  | 'CONTACT_SUPPORT'

export interface TaskOutcomeActionView {
  type: TaskOutcomeActionType
  enabled: boolean
  disabledReason?: string
}

export interface GeoDiagnosisView {
  findings: readonly string[]
  recommendations: readonly {
    title: string
    rationale: string
  }[]
  caveat?: string
}

export interface TaskExecutionOutcomeView {
  taskId: ChatViewId
  taskType: string
  statusLabel: string
  statusTone: ParallelTaskStatusTone
  completedAt?: string
  summary?: string
  geoDiagnosis?: GeoDiagnosisView
  failureReason?: string
  resultItems?: readonly TaskOutcomeItemView[]
  settlement: TaskSettlementView
  actions?: readonly TaskOutcomeActionView[]
}

export interface TaskExecutionOutcomeGroupView {
  id: ChatViewId
  outcomes: readonly TaskExecutionOutcomeView[]
}

export interface ChatBackgroundTaskNotificationView {
  taskId: ChatViewId
  label: string
  title: string
  description: string
  announce?: boolean
}

export interface GeneratedImageResultView {
  assetId: ChatViewId
  name: string
  candidateLabel?: string
  imageUrl?: string
  formatLabel?: string
  dimensionsLabel?: string
  expiresAt?: string
  safetyStatusLabel?: string
  persistedAsset?: boolean
  previewEnabled: boolean
  downloadEnabled: boolean
  saveToAssetsEnabled: boolean
  selectEnabled: boolean
}

export interface GeneratedImageResultGroupView {
  id: ChatViewId
  title: string
  images: readonly GeneratedImageResultView[]
  selectedAssetId?: ChatViewId
  selectedAssetIds?: readonly ChatViewId[]
  creativeSourceMode?: CreativeSourceMode
  productAssociationEnabled?: boolean
  productAssociationDisabledReason?: string
}

export interface GeneratedVideoResultView {
  assetId: ChatViewId
  name: string
  videoUrl?: string
  contentType?: string
  dimensionsLabel?: string
  durationLabel?: string
  expiresAt?: string
  safetyStatusLabel?: string
  persistedAsset: boolean
  previewEnabled: boolean
  creativeSourceMode?: CreativeSourceMode
  productAssociationEnabled?: boolean
  productAssociationDisabledReason?: string
}

export interface GeneratedVideoPlaybackSourceView {
  videoUrl: string
  expiresAt?: string
}

export interface ChatMessageView {
  id: ChatViewId
  role: ChatMessageRole
  content: string
  /** True only while a reply with real received text is still active. */
  outputStreaming?: boolean
  createdAt?: string
  /** Explicit reference persisted on the user's message, never guessed from text. */
  referencedGeoReportId?: string
  /** Java-authored frozen reference, never inferred from Markdown or streamed text. */
  geoContext?: ConversationMessageGeoContext
  geoDiagnostic?: ChatGeoDiagnosticReference
  businessOutcome?: ConversationBusinessOutcome
  backgroundTaskNotification?: ChatBackgroundTaskNotificationView
  clarificationOptions?: readonly ChatClarificationOptionView[]
  resourceSelection?: ChatResourceSelectionView
  guidance?: string
  paidTaskConfirmation?: PaidTaskConfirmationView
  taskCreatedReceipt?: TaskCreatedReceiptView
  articleDraftPreview?: ArticleDraftPreviewView
  articlePreparation?: ChatArticlePreparationView
  articleWorkflowAnchor?: ArticleWorkflowAnchorView
  parallelTaskGroup?: ParallelTaskGroupView
  generatedImageResultGroup?: GeneratedImageResultGroupView
  generatedVideoResult?: GeneratedVideoResultView
  taskExecutionOutcomeGroup?: TaskExecutionOutcomeGroupView
  executionTimeline?: ExecutionTimeline
}

/** Presentation reference verified from Java message content, never inferred from text. */
export interface ChatGeoDiagnosticReference {
  diagnosticId: string
  step: number
  conversationId: string
}

export interface VideoStoryboardSceneView {
  id: ChatViewId
  timeRangeLabel: string
  title: string
  description: string
  previewImageUrl?: string
}

export interface PaidTaskDetailRowView {
  label: string
  value: string
}

export interface ArticleSubmissionSnapshotView {
  articleId: ChatViewId
  title: string
  versionLabel: string
  generatedAt?: string
}

export interface MediaSubmissionTargetView {
  id: ChatViewId
  name: string
  estimatedTimeLabel?: string
  saleAmountLabel?: string
  chargedPoints?: number
  imageFeeLabel?: string
}

export interface ArticleMediaSubmissionDetailsView {
  articleSnapshot: ArticleSubmissionSnapshotView
  targets: readonly MediaSubmissionTargetView[]
}

export interface PointsInsufficientView {
  shortfallPoints: number | undefined
  rechargeEnabled: boolean
  reestimateEnabled: boolean
}

export interface PaidTaskConfirmationView {
  id: ChatViewId
  taskType: string
  title: string
  creativeSourceMode?: CreativeSourceMode
  rightSideProductOverridden?: boolean
  productName?: string
  productImageUrl?: string
  detailRows: readonly PaidTaskDetailRowView[]
  referenceAssets?: readonly ChatReferenceAssetView[]
  referenceAssetEditingEnabled?: boolean
  videoStoryboardScenes?: readonly VideoStoryboardSceneView[]
  mediaSubmissionDetails?: ArticleMediaSubmissionDetailsView
  candidateQuestions?: readonly string[]
  platformNames?: readonly string[]
  pointsCost: number
  pointBalance?: number
  pointsInsufficient?: PointsInsufficientView
  warningText?: string
  unavailableReason?: string
  editDisabledReason?: string
  confirming: boolean
  confirmEnabled: boolean
}

export type TaskCreatedReceiptStatusTone =
  | 'neutral'
  | 'info'
  | 'success'
  | 'warning'
  | 'danger'

export interface TaskCreatedReceiptView {
  id: ChatViewId
  taskId: ChatViewId
  taskNumber: string
  title: string
  taskType: string
  statusLabel: string
  statusTone: TaskCreatedReceiptStatusTone
  productName?: string
  productImageUrl?: string
  version?: string
  pointsLabel?: string
  pointsAmount?: number
  frozenPoints?: number
  pointBalance?: number
  createdAt?: string
  message?: string
  detailEnabled: boolean
}

export interface ChatContextOptionView {
  id: ChatViewId
  name: string
}

export interface ChatProductOptionView {
  id: ChatViewId
  businessSpaceId: ChatViewId
  name: string
  category?: string
  modelOrSpecification?: string
  mainAssetUrl?: string
  completeness?: number
}

export interface ChatRecommendationView {
  id: ChatViewId
  title: string
  description: string
  prompt: string
  submitOnSelect?: boolean
  icon: ReactNode
}

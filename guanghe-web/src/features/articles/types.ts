import type { MediaArticlePricing } from '../../api/mediaApi'

export type ArticleWorkspaceView = 'CREATION' | 'SUBMISSION' | 'ARTICLES'

export type ArticleStatus =
  | 'DRAFT'
  | 'OUTLINING'
  | 'QUEUED'
  | 'GENERATING'
  | 'BODY_QUEUED'
  | 'BODY_GENERATING'
  | 'COMPLETED'
  | 'FAILED'
  | 'ARCHIVED'

export type MediaResourceStatus = 'ACTIVE' | 'PAUSED'

export type SubmissionStatus =
  | 'QUEUED'
  | 'SUBMITTED'
  | 'PROCESSING'
  | 'PUBLISHED'
  | 'REJECTED'
  | 'RECONCILING'

export interface ArticleOption {
  label: string
  value: string
}

export interface ArticleListItem {
  id: string
  title: string
  status: ArticleStatus
  versionLabel: string
  updatedAt: string
  wordCount?: number
  excerpt?: string
  statusLabel?: string
}

export interface MediaResourceItem {
  id: string
  name: string
  type: string
  publicationNote: string
  estimatedTimeLabel: string
  chargedPoints: number
  status: MediaResourceStatus
}

export interface SubmissionListItem {
  id: string
  articleTitle: string
  mediaNames: string[]
  status: SubmissionStatus
  chargedPoints: number
  submittedAt: string
  publishedUrl?: string
}

export interface ArticleCreationDraft {
  businessSpaceId?: string
  productId?: string
  purpose: string
  channel: string
  tone: string
  targetWordCount: number
  keywords: string[]
  title: string
  bodyText: string
}

export interface ArticleSubmissionDraft {
  articleId: string
  mediaIds: string[]
  note: string
}

export type ArticleStage = 'TITLE' | 'OUTLINE' | 'BODY' | 'REWRITE'

export type ArticleWorkflowStage =
  | 'TITLE_INPUT'
  | 'TITLE_GENERATING'
  | 'TITLE_AWAITING_CONFIRMATION'
  | 'PLAN_READY'
  | 'PLAN_GENERATING'
  | 'PLAN_AWAITING_CONFIRMATION'
  | 'BODY_READY_TO_GENERATE'
  | 'BODY_GENERATING'
  | 'BODY_READY'
  | 'EDITING'
  | 'READY_TO_SUBMIT'

export type ArticleTitleSource = 'SELECTED' | 'EDITED' | 'CUSTOM'

export interface ArticleTitleCandidateBatchView {
  version: number
  candidates: readonly string[]
  generatedAt?: string
}

export interface ArticleConfirmedTitleView {
  value: string
  source: ArticleTitleSource
  titleBatchVersion: number
  candidateIndex?: number
  confirmedAt?: string
}

export interface ArticleCreativePlanSectionView extends ArticleOutlineDraftItem {
  sequence: number
}

export interface ArticleCreativePlanView {
  version: number
  summary: string
  coreMessage: string
  targetAudience: string
  sections: readonly ArticleCreativePlanSectionView[]
  tone: string
  targetLength: 'SHORT' | 'MEDIUM' | 'LONG'
  targetPlatform?: string
  riskNotes: readonly string[]
  confirmed: boolean
  updatedAt?: string
}

export interface ArticleCreativePlanDraft {
  summary: string
  coreMessage: string
  targetAudience: string
  sections: readonly ArticleCreativePlanSectionView[]
  tone: string
  targetLength: 'SHORT' | 'MEDIUM' | 'LONG'
  targetPlatform?: string
  riskNotes: readonly string[]
}

export interface ArticleOutlineDraftItem {
  key: string
  level: number
  heading: string
  brief?: string
}

export interface ArticleAssetRelationView {
  assetId: string
  positionKey: string
  sortOrder: number
  altText?: string
}

export interface ArticleWorkspaceRecordView {
  id: string
  spaceId: string
  productId?: string
  title: string
  bodyHtml: string
  bodyText: string
  status: ArticleStatus
  statusLabel: string
  version: number
  updatedAt: string
  outline: readonly ArticleOutlineDraftItem[]
  assets: readonly ArticleAssetRelationView[]
  sourceTaskId?: string
  workflowStage?: ArticleWorkflowStage
  titleCandidateBatch?: ArticleTitleCandidateBatchView
  confirmedTitle?: ArticleConfirmedTitleView
  creativePlan?: ArticleCreativePlanView
}

export interface ArticleAssetOptionView {
  id: string
  name: string
  status: string
  contentType: string
  width?: number
  height?: number
}

export interface ArticleTaskFormDraft {
  mode: ArticleStage
  brief: string
  tone: string
  targetWordCount: number
  rewriteInstruction: string
  rewriteBlockIds: readonly string[]
  sourceAssetIds: readonly string[]
}

export interface SubmissionSnapshotView {
  id: string
  articleId: string
  title: string
  versionLabel: string
  createdAt: string
  bodyHtml: string
  bodyText: string
  outlineCount: number
  assetCount: number
}

export interface ArticleViewError {
  message: string
  traceId?: string
  conflict?: boolean
  code?: string
}

export type LiveMediaResourceStatus = 'ACTIVE' | 'INACTIVE'

export interface LiveMediaResourceView {
  supportsImages?: boolean
  maxImageCount?: number
  imageFeeMode?: string
  imagePricingDescription?: string
  articlePricing?: MediaArticlePricing
  id: string
  modelId: string
  name: string
  status: LiveMediaResourceStatus
  resourceType?: string
  channelCategory?: string
  region?: string
  estimatedTimeLabel?: string
  saleAmountLabel?: string
  chargedPoints?: number
  quoteExpiresAt: string
  syncedAt: string
  orderable: boolean
  supportsLinks: boolean
}

export interface ArticleMediaRecommendationItemView {
  rank: number
  reasonCode:
    | 'INDUSTRY_CATEGORY_MATCH'
    | 'BROAD_REACH'
    | 'COST_EFFECTIVE'
    | 'ACTIVE_RESOURCE_FALLBACK'
  reason: string
  score: number
  evidence: readonly string[]
  resource: LiveMediaResourceView
}

export type ArticleSubmissionGoal =
  | 'BALANCED'
  | 'INDUSTRY_MATCH'
  | 'COST_EFFECTIVE'
  | 'BROAD_REACH'

export interface ArticleBodyConfirmationInput {
  goal: ArticleSubmissionGoal
  preferredRegion?: string
  maxTotalPoints?: number
  requiresLink: boolean
}

export interface ArticleMediaRecommendationSetView {
  articleId: string
  articleVersion: number
  generatedAt: string
  submissionEnabled: boolean
  strategy: {
    goal: ArticleSubmissionGoal
    preferredRegion?: string
    maxTotalPoints?: number
    requiresLink: boolean
    detectedCategories: readonly string[]
    candidateCount: number
    dataLimitations: readonly string[]
  }
  items: readonly ArticleMediaRecommendationItemView[]
}

export interface ArticleRecommendedSubmissionSelection {
  articleId: string
  articleVersion: number
  mediaResourceIds: readonly string[]
  expectedPoints: number
  expectedPricing?: readonly { mediaResourceId: string; pricing: MediaArticlePricing }[]
}

export interface MediaResourceFilterView {
  search: string
  modelId?: string
  type?: string
  status?: LiveMediaResourceStatus
  minSaleAmountCents?: number
  maxSaleAmountCents?: number
}

export type LiveMediaSubmissionStatus =
  | 'QUEUED'
  | 'SUBMITTING'
  | 'SUBMIT_UNKNOWN'
  | 'ACCEPTED'
  | 'PUBLISHED'
  | 'REJECTED'
  | 'RECONCILING'
  | 'FAILED'

export interface LiveMediaSubmissionListItemView {
  id: string
  taskId: string
  articleId: string
  articleSnapshotId: string
  mediaResourceId: string
  mediaName: string
  status: LiveMediaSubmissionStatus
  statusLabel: string
  statusTone: 'neutral' | 'info' | 'success' | 'warning' | 'danger'
  saleAmountLabel: string
  chargedPoints?: number
  publishedUrl?: string
  publishedUrls: readonly string[]
  errorCode?: string
  createdAt: string
  updatedAt: string
}

export interface LiveMediaSubmissionEventView {
  id: string
  sequence: number
  label: string
  statusLabel: string
  occurredAt: string
  errorCode?: string
}

export interface LiveMediaSubmissionDetailView
  extends LiveMediaSubmissionListItemView {
  remark?: string
  events: readonly LiveMediaSubmissionEventView[]
  settledPoints?: number
  returnedPoints?: number
}

export interface MediaQuoteSelectionView {
  mediaResourceIds: readonly string[]
  remark: string
}

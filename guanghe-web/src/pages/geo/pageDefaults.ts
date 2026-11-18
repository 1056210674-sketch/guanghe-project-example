import type {
  GeoAnswerSampleView,
  GeoCompetitorCandidateView,
  GeoCompetitorSetVersionView,
  GeoCompetitorView,
  GeoKeywordView,
  GeoMetricView,
  GeoMonitoringObjectView,
  GeoPlanView,
  GeoPlatformPerformanceView,
  GeoPlatformView,
  GeoQuestionEvidenceView,
  GeoQuestionGroupOption,
  GeoQuestionView,
  GeoRecentRunView,
  GeoReportListItemView,
  GeoSkippedRunView,
  GeoSuggestionView,
  GeoTopicView,
  GeoTrendPointView,
} from '../../features/geo'

// Production defaults stay empty until generated Java browser DTOs can be
// adapted into the GEO presentation views. Test data belongs in test fixtures.
export const EMPTY_GEO_PLATFORMS: readonly GeoPlatformView[] = []
export const EMPTY_GEO_METRICS: readonly GeoMetricView[] = []
export const EMPTY_GEO_TREND: readonly GeoTrendPointView[] = []
export const EMPTY_GEO_PLATFORM_PERFORMANCE: readonly GeoPlatformPerformanceView[] = []
export const EMPTY_GEO_RUNS: readonly GeoRecentRunView[] = []
export const EMPTY_GEO_SUGGESTIONS: readonly GeoSuggestionView[] = []
export const EMPTY_GEO_OBJECTS: readonly GeoMonitoringObjectView[] = []
export const EMPTY_GEO_TOPICS: readonly GeoTopicView[] = []
export const EMPTY_GEO_KEYWORDS: readonly GeoKeywordView[] = []
export const EMPTY_GEO_QUESTIONS: readonly GeoQuestionView[] = []
export const EMPTY_GEO_QUESTION_EVIDENCE: readonly GeoQuestionEvidenceView[] = []
export const EMPTY_GEO_QUESTION_GROUPS: readonly GeoQuestionGroupOption[] = []
export const EMPTY_GEO_PLANS: readonly GeoPlanView[] = []
export const EMPTY_GEO_SKIPPED_RUNS: readonly GeoSkippedRunView[] = []
export const EMPTY_GEO_COMPETITOR_VERSIONS: readonly GeoCompetitorSetVersionView[] = []
export const EMPTY_GEO_COMPETITORS: readonly GeoCompetitorView[] = []
export const EMPTY_GEO_COMPETITOR_CANDIDATES: readonly GeoCompetitorCandidateView[] = []
export const EMPTY_GEO_REPORTS: readonly GeoReportListItemView[] = []
export const EMPTY_GEO_SAMPLES: readonly GeoAnswerSampleView[] = []

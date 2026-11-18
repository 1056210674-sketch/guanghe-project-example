import { useEffect, useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  analyzeGeoCompetitiveInsights,
  confirmGeoCompetitorCandidate,
  getGeoCompetitiveInsights,
  ignoreGeoCompetitorCandidate,
  listGeoCompetitorCandidates,
  listGeoPlans,
  pauseGeoPlan,
  resumeGeoPlan,
  reinterpretGeoReport,
  createGeoTopic,
  deleteGeoTopic,
  getGeoReport,
  getGeoRun,
  getGeoRunProgress,
  getGeoTopic,
  getGeoVisibilityTrend,
  listGeoPlatforms,
  listGeoReports,
  listGeoRuns,
  listGeoTopics,
  replaceGeoTopicCompetitors,
  replaceGeoTopicKeywords,
  replaceGeoTopicQuestions,
  updateGeoTopic,
  updateGeoPlan,
} from '../../api/geoApi'
import { getCompetitor, listCompetitors } from '../../api/catalogApi'
import { useAuthStore } from '../../stores/authStore'
import { catalogKeys } from '../business-data/catalogQueries'
import type {
  Competitor,
  CompetitorSummary,
} from '../../api/catalogApi'
import type {
  ConfirmGeoCompetitorCandidateRequest,
  GeoCompetitiveInsights,
  GeoCompetitiveInsightsQuery,
  GeoCompetitorCandidatesQuery,
  IgnoreGeoCompetitorCandidateRequest,
  CreateGeoTopicRequest,
  GeoPlanId,
  GeoPlanListQuery,
  GeoPlanVersionRequest,
  GeoReportId,
  GeoReport,
  GeoRun,
  GeoReportListQuery,
  GeoRunId,
  GeoRunListQuery,
  GeoTopicId,
  GeoVisibilityTrendQuery,
  GeoTopicListQuery,
  GeoTopicSummary,
  ReplaceGeoCompetitorsRequest,
  ReplaceGeoKeywordsRequest,
  ReplaceGeoQuestionsRequest,
  UpdateGeoTopicRequest,
  UpdateGeoPlanRequest,
} from '../../api/geoApi'

const DEFAULT_PAGE = 1
const DEFAULT_PAGE_SIZE = 20
const OPTIONS_PAGE_SIZE = 100
const geoRootKey = ['geo-foundation'] as const

function createGeoKeys(ownerId?: string | null) {
  const ownerRoot = [...geoRootKey, 'owner', ownerId ?? null] as const
  const topics = () => [...ownerRoot, 'topics'] as const
  const topicLists = () => [...topics(), 'list'] as const
  const plans = () => [...ownerRoot, 'plans'] as const
  const planLists = () => [...plans(), 'list'] as const
  const reportLists = () => [...ownerRoot, 'reports', 'list'] as const
  const competitiveInsights = (reportId?: GeoReportId) => reportId
    ? [...ownerRoot, 'competitive-insights', reportId] as const : [...ownerRoot, 'competitive-insights'] as const
  const competitorCandidates = (topicId?: GeoTopicId) => topicId
    ? [...ownerRoot, 'competitor-candidates', topicId] as const : [...ownerRoot, 'competitor-candidates'] as const

  return {
    ownerRoot,
    competitiveInsights,
    competitiveInsight: (reportId: GeoReportId | undefined, query: GeoCompetitiveInsightsQuery) =>
      [...competitiveInsights(reportId), query.questionScope ?? 'DISCOVERY'] as const,
    competitorCandidates,
    competitorCandidateList: (topicId: GeoTopicId | undefined, query: GeoCompetitorCandidatesQuery) =>
      [...competitorCandidates(topicId), query.status ?? 'PENDING', query.page ?? 1, query.size ?? 20] as const,
    topics,
    topicLists,
    topicList: (query: GeoTopicListQuery = {}) => [
      ...topicLists(),
      query.spaceId ?? null,
      query.productId ?? null,
      query.keyword ?? null,
      query.page ?? DEFAULT_PAGE,
      query.size ?? DEFAULT_PAGE_SIZE,
    ] as const,
    topicOptions: () => [...topics(), 'options'] as const,
    topicDetails: () => [...topics(), 'detail'] as const,
    topicDetail: (topicId?: GeoTopicId) =>
      [...topics(), 'detail', topicId ?? null] as const,
    visibilityTrend: (topicId: GeoTopicId | undefined, query: GeoVisibilityTrendQuery) =>
      [...topics(), 'visibility-trend', topicId ?? null, query.days ?? 30, query.questionScope ?? 'DISCOVERY'] as const,
    competitorOptions: (spaceId?: string) =>
      [...ownerRoot, 'catalog-competitors', spaceId ?? null] as const,
    platforms: () => [...ownerRoot, 'platforms'] as const,
    plans,
    planLists,
    planList: (query: GeoPlanListQuery = {}) => [
      ...planLists(),
      query.status ?? null,
      query.topicId ?? null,
      query.page ?? DEFAULT_PAGE,
      query.size ?? DEFAULT_PAGE_SIZE,
    ] as const,
    runList: (query: GeoRunListQuery = {}) =>
      [...ownerRoot, 'runs', 'list', query] as const,
    runProgress: (runId?: GeoRunId) =>
      [...ownerRoot, 'runs', 'progress', runId ?? null] as const,
    reportLists,
    reportList: (query: GeoReportListQuery = {}) =>
      [...reportLists(), query] as const,
    reportDetail: (reportId?: GeoReportId) =>
      [...ownerRoot, 'reports', 'detail', reportId ?? null] as const,
  }
}

const anonymousGeoKeys = createGeoKeys()

export const geoKeys = {
  all: geoRootKey,
  forOwner: createGeoKeys,
  ...anonymousGeoKeys,
}

function useGeoKeys() {
  const ownerId = useAuthStore((state) => state.user?.id)
  return createGeoKeys(ownerId)
}

async function loadAllGeoTopics() {
  const topics: GeoTopicSummary[] = []
  let page = DEFAULT_PAGE

  while (true) {
    const result = await listGeoTopics({ page, size: OPTIONS_PAGE_SIZE })
    topics.push(...result.items)
    if (!result.hasNext) return topics
    page = result.page + 1
  }
}

async function loadAllCompetitors(spaceId: string): Promise<Competitor[]> {
  const ids: string[] = []
  let page = DEFAULT_PAGE

  while (true) {
    const result = await listCompetitors(spaceId, {
      page,
      size: OPTIONS_PAGE_SIZE,
    })
    const items = Array.isArray(result.items)
      ? result.items as CompetitorSummary[]
      : []
    ids.push(...items.map((item) => item.id))
    if (!result.hasNext) break
    page = result.page + 1
  }

  return Promise.all(ids.map((competitorId) => getCompetitor(competitorId)))
}

async function invalidateTopicLists(
  queryClient: ReturnType<typeof useQueryClient>,
  keys: ReturnType<typeof createGeoKeys>,
) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: keys.topicLists() }),
    queryClient.invalidateQueries({ queryKey: keys.topicOptions() }),
  ])
}

export function useGeoTopics(query: GeoTopicListQuery = {}) {
  const keys = useGeoKeys()
  return useQuery({
    queryKey: keys.topicList(query),
    queryFn: () => listGeoTopics(query),
  })
}

export function useAllGeoTopics() {
  const keys = useGeoKeys()
  return useQuery({
    queryKey: keys.topicOptions(),
    queryFn: loadAllGeoTopics,
  })
}

export function useGeoTopic(topicId?: GeoTopicId) {
  const keys = useGeoKeys()
  return useQuery({
    queryKey: keys.topicDetail(topicId),
    queryFn: () => getGeoTopic(topicId as GeoTopicId),
    enabled: Boolean(topicId),
  })
}

export function useGeoVisibilityTrend(topicId: GeoTopicId | undefined, query: GeoVisibilityTrendQuery) {
  const keys = useGeoKeys()
  return useQuery({
    queryKey: keys.visibilityTrend(topicId, query),
    queryFn: () => getGeoVisibilityTrend(topicId as GeoTopicId, query),
    enabled: Boolean(topicId),
  })
}

export function useGeoCompetitorOptions(spaceId?: string) {
  const keys = useGeoKeys()
  return useQuery({
    queryKey: keys.competitorOptions(spaceId),
    queryFn: () => loadAllCompetitors(spaceId as string),
    enabled: Boolean(spaceId),
  })
}

export function useGeoCompetitiveInsights(reportId: GeoReportId | undefined, query: GeoCompetitiveInsightsQuery) {
  const keys = useGeoKeys()
  const queryClient = useQueryClient()
  return useQuery({
    queryKey: keys.competitiveInsight(reportId, query),
    queryFn: async () => {
      const result = await getGeoCompetitiveInsights(reportId as GeoReportId, query)
      const previous = queryClient.getQueryData<GeoCompetitiveInsights>(keys.competitiveInsight(reportId, query))
      if (result.status === 'SUCCEEDED' && previous?.status !== 'SUCCEEDED') {
        void queryClient.invalidateQueries({ queryKey: keys.competitorCandidates(result.topicId) })
      }
      return result
    },
    enabled: Boolean(reportId),
    refetchInterval: (query) => query.state.error ? false
      : query.state.data?.status === 'QUEUED' || query.state.data?.status === 'RUNNING' ? 3_000 : false,
    refetchIntervalInBackground: false,
  })
}

export function useGeoCompetitorCandidates(topicId: GeoTopicId | undefined, query: GeoCompetitorCandidatesQuery) {
  const keys = useGeoKeys()
  return useQuery({ queryKey: keys.competitorCandidateList(topicId, query),
    queryFn: () => listGeoCompetitorCandidates(topicId as GeoTopicId, query), enabled: Boolean(topicId) })
}

export function useAnalyzeGeoCompetitiveInsights() {
  const keys = useGeoKeys()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: analyzeGeoCompetitiveInsights,
    retry: false,
    onMutate: (reportId) => queryClient.cancelQueries({ queryKey: keys.competitiveInsights(reportId) }),
    onSuccess: (data) => queryClient.setQueryData(keys.competitiveInsight(data.reportId, { questionScope: data.questionScope }), data),
    // An ambiguous POST response is resolved by a persisted-state GET, never a second automatic POST.
    onSettled: (_data, _error, reportId) => queryClient.invalidateQueries({ queryKey: keys.competitiveInsights(reportId) }),
  })
}

export function useConfirmGeoCompetitorCandidate() {
  const keys = useGeoKeys()
  const ownerId = useAuthStore((state) => state.user?.id)
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ candidateId, request }: { candidateId: string; request: ConfirmGeoCompetitorCandidateRequest; topicId: string; spaceId: string }) =>
      confirmGeoCompetitorCandidate(candidateId, request),
    retry: false,
    onSettled: async (_data, _error, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.competitorCandidates(variables.topicId) }),
        queryClient.invalidateQueries({ queryKey: keys.competitiveInsights() }),
        queryClient.invalidateQueries({ queryKey: keys.topicDetail(variables.topicId) }),
        queryClient.invalidateQueries({ queryKey: keys.competitorOptions(variables.spaceId) }),
        queryClient.invalidateQueries({ queryKey: catalogKeys.forOwner(ownerId).competitorListsForSpace(variables.spaceId) }),
      ])
    },
  })
}

export function useIgnoreGeoCompetitorCandidate() {
  const keys = useGeoKeys()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ candidateId, request }: { candidateId: string; request: IgnoreGeoCompetitorCandidateRequest; topicId: string }) =>
      ignoreGeoCompetitorCandidate(candidateId, request),
    retry: false,
    onSettled: async (_data, _error, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.competitorCandidates(variables.topicId) }),
        queryClient.invalidateQueries({ queryKey: keys.competitiveInsights() }),
      ])
    },
  })
}

export function useGeoPlatforms() {
  const keys = useGeoKeys()
  return useQuery({
    queryKey: keys.platforms(),
    queryFn: listGeoPlatforms,
  })
}

export function useGeoPlans(query: GeoPlanListQuery = {}) {
  const keys = useGeoKeys()
  return useQuery({
    queryKey: keys.planList(query),
    queryFn: () => listGeoPlans(query),
  })
}

async function invalidatePlanLists(
  queryClient: ReturnType<typeof useQueryClient>,
  keys: ReturnType<typeof createGeoKeys>,
) {
  await queryClient.invalidateQueries({ queryKey: keys.planLists() })
}

export interface UpdateGeoPlanVariables {
  planId: GeoPlanId
  request: UpdateGeoPlanRequest
}

export function useUpdateGeoPlan() {
  const queryClient = useQueryClient()
  const keys = useGeoKeys()
  return useMutation({
    mutationFn: ({ planId, request }: UpdateGeoPlanVariables) =>
      updateGeoPlan(planId, request),
    onSuccess: () => invalidatePlanLists(queryClient, keys),
  })
}

export interface SetGeoPlanStatusVariables {
  planId: GeoPlanId
  request: GeoPlanVersionRequest
}

export function usePauseGeoPlan() {
  const queryClient = useQueryClient()
  const keys = useGeoKeys()
  return useMutation({
    mutationFn: ({ planId, request }: SetGeoPlanStatusVariables) =>
      pauseGeoPlan(planId, request),
    onSuccess: () => invalidatePlanLists(queryClient, keys),
  })
}

export function useResumeGeoPlan() {
  const queryClient = useQueryClient()
  const keys = useGeoKeys()
  return useMutation({
    mutationFn: ({ planId, request }: SetGeoPlanStatusVariables) =>
      resumeGeoPlan(planId, request),
    onSuccess: () => invalidatePlanLists(queryClient, keys),
  })
}

export function useGeoRuns(query: GeoRunListQuery = {}, enabled = true) {
  const keys = useGeoKeys()
  return useQuery({
    queryKey: keys.runList(query),
    queryFn: () => listGeoRuns(query),
    enabled,
  })
}

/**
 * GEO has a run-specific read projection because the generic paid-task
 * response deliberately omits stage/report details. The hook never infers a
 * state; Java remains the authority and this only polls its projection.
 */
export function useGeoRunProgress(
  runId?: GeoRunId,
  enabled = true,
) {
  const keys = useGeoKeys()
  return useQuery({
    queryKey: keys.runProgress(runId),
    queryFn: () => getGeoRunProgress(runId as GeoRunId),
    enabled: Boolean(runId) && enabled,
    refetchInterval: (query) => {
      if (query.state.data?.terminal) return false
      return query.state.error ? 5_000 : 3_000
    },
    refetchIntervalInBackground: false,
  })
}

export function useGeoReports(query: GeoReportListQuery = {}, enabled = true) {
  const keys = useGeoKeys()
  return useQuery({
    queryKey: keys.reportList(query),
    queryFn: () => listGeoReports(query),
    enabled,
  })
}

export function useGeoReport(reportId?: GeoReportId) {
  const keys = useGeoKeys()
  const queryClient = useQueryClient()
  const refreshedCooldown = useRef<string | null>(null)
  const query = useQuery({
    queryKey: keys.reportDetail(reportId),
    queryFn: async () => {
      const report = await getGeoReport(reportId as GeoReportId)
      const previous = queryClient.getQueryData<{ report: GeoReport; run: GeoRun }>(keys.reportDetail(reportId))
      // Interpretation never changes collection results. Reuse the saved terminal run while polling.
      const run = previous?.run.id === report.runId && previous.run.finishedAt
        ? previous.run
        : await getGeoRun(report.runId)
      return { report, run }
    },
    enabled: Boolean(reportId),
    refetchInterval: (query) => {
      if (query.state.error) return false
      const status = query.state.data?.report.interpretation?.status
      return status === 'QUEUED' || status === 'RUNNING' ? 3_000 : false
    },
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
  })
  const interpretation = query.data?.report.interpretation
  const retryAfter = interpretation?.retryAfter
  const cooldownKey = JSON.stringify([keys.reportDetail(reportId), retryAfter])
  const { refetch, error } = query
  useEffect(() => {
    if (error || interpretation?.status !== 'FAILED'
      || interpretation.failureCode !== 'GEO_INTERPRETATION_CAPACITY_FULL'
      || !retryAfter || refreshedCooldown.current === cooldownKey) return
    const deadline = Date.parse(retryAfter)
    if (!Number.isFinite(deadline)) return
    // Capacity refusal used no model budget. Read the server's eligibility once
    // after cooldown, even after a page reload; never turn this into an auto POST.
    const timer = window.setTimeout(() => {
      refreshedCooldown.current = cooldownKey
      void refetch()
    }, Math.max(0, deadline - Date.now()) + 100)
    return () => window.clearTimeout(timer)
  }, [cooldownKey, error, interpretation?.failureCode, interpretation?.status, refetch, retryAfter])
  return query
}

export function useReinterpretGeoReport() {
  const queryClient = useQueryClient()
  const keys = useGeoKeys()
  return useMutation({
    mutationFn: (reportId: GeoReportId) => reinterpretGeoReport(reportId),
    retry: false,
    onMutate: (reportId) => queryClient.cancelQueries({ queryKey: keys.reportDetail(reportId) }),
    onSuccess: (interpretation, reportId) => {
      queryClient.setQueryData<{ report: GeoReport; run: GeoRun }>(keys.reportDetail(reportId), (previous) => previous
        ? { ...previous, report: { ...previous.report, interpretation } }
        : previous)
    },
    // A lost POST response is ambiguous: read the persisted job, never automatically submit again.
    onSettled: async (_result, _error, reportId) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.reportDetail(reportId) }),
        queryClient.invalidateQueries({ queryKey: keys.reportLists() }),
      ])
    },
  })
}

export function useCreateGeoTopic() {
  const queryClient = useQueryClient()
  const keys = useGeoKeys()

  return useMutation({
    mutationFn: (request: CreateGeoTopicRequest) => createGeoTopic(request),
    onSuccess: async (topic) => {
      queryClient.setQueryData(keys.topicDetail(topic.id), topic)
      await invalidateTopicLists(queryClient, keys)
    },
  })
}

export interface UpdateGeoTopicVariables {
  topicId: GeoTopicId
  request: UpdateGeoTopicRequest
}

export function useUpdateGeoTopic() {
  const queryClient = useQueryClient()
  const keys = useGeoKeys()

  return useMutation({
    mutationFn: ({ topicId, request }: UpdateGeoTopicVariables) =>
      updateGeoTopic(topicId, request),
    onSuccess: async (topic) => {
      queryClient.setQueryData(keys.topicDetail(topic.id), topic)
      await invalidateTopicLists(queryClient, keys)
    },
  })
}

export function useDeleteGeoTopic() {
  const queryClient = useQueryClient()
  const keys = useGeoKeys()

  return useMutation({
    mutationFn: (topicId: GeoTopicId) => deleteGeoTopic(topicId),
    onSuccess: async (_, topicId) => {
      queryClient.removeQueries({ queryKey: keys.topicDetail(topicId) })
      await invalidateTopicLists(queryClient, keys)
    },
  })
}

export interface ReplaceGeoKeywordsVariables {
  topicId: GeoTopicId
  request: ReplaceGeoKeywordsRequest
}

export function useReplaceGeoKeywords() {
  const queryClient = useQueryClient()
  const keys = useGeoKeys()

  return useMutation({
    mutationFn: ({ topicId, request }: ReplaceGeoKeywordsVariables) =>
      replaceGeoTopicKeywords(topicId, request),
    onSuccess: async (topic) => {
      queryClient.setQueryData(keys.topicDetail(topic.id), topic)
      await queryClient.invalidateQueries({ queryKey: keys.topicLists() })
    },
  })
}

export interface ReplaceGeoQuestionsVariables {
  topicId: GeoTopicId
  request: ReplaceGeoQuestionsRequest
}

export function useReplaceGeoQuestions() {
  const queryClient = useQueryClient()
  const keys = useGeoKeys()

  return useMutation({
    mutationFn: ({ topicId, request }: ReplaceGeoQuestionsVariables) =>
      replaceGeoTopicQuestions(topicId, request),
    onSuccess: (topic) => {
      queryClient.setQueryData(keys.topicDetail(topic.id), topic)
    },
  })
}

export interface ReplaceGeoCompetitorsVariables {
  topicId: GeoTopicId
  request: ReplaceGeoCompetitorsRequest
}

export function useReplaceGeoCompetitors() {
  const queryClient = useQueryClient()
  const keys = useGeoKeys()

  return useMutation({
    mutationFn: ({ topicId, request }: ReplaceGeoCompetitorsVariables) =>
      replaceGeoTopicCompetitors(topicId, request),
    onSuccess: (topic) => {
      queryClient.setQueryData(keys.topicDetail(topic.id), topic)
    },
  })
}

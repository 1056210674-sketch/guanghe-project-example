import { App as AntApp, Button } from 'antd'
import { useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { ApiError } from '../../api/apiError'
import type { ConfirmGeoCompetitorCandidateRequest, GeoCompetitorCandidate } from '../../api/geoApi'
import { GeoCompetitorManager, useAllGeoTopics, useAnalyzeGeoCompetitiveInsights, useConfirmGeoCompetitorCandidate, useGeoCompetitiveInsights, useGeoCompetitorCandidates, useGeoCompetitorOptions, useGeoPlatforms, useGeoReports, useGeoTopic, useIgnoreGeoCompetitorCandidate, useReplaceGeoCompetitors } from '../../features/geo'
import { GeoCandidateConfirmation, GeoCompetitiveWorkbench } from '../../features/geo/components/GeoCompetitiveWorkbench'
import { GeoStateFrame } from '../../features/geo/components/GeoStateFrame'
import { formatGeoDateTime } from '../../features/geo/presentation'
import { APP_PATHS, appLinks } from '../../routes/paths'
import { toGeoCompetitorView, toGeoPlatformView, toGeoViewError, toReplaceGeoCompetitorsRequest } from './viewAdapters'

function pageNumber(raw: string | null) {
  const value = Number(raw)
  return Number.isSafeInteger(value) && value > 0 ? value : 1
}

function competitiveError(error: unknown, fallback: string) {
  if (!error) return undefined
  if (error instanceof ApiError) {
    if (error.status === 403 || error.status === 404) return '记录不存在或已不可用，请重新选择专题或报告。'
    if (error.status === 409) return '资料或候选状态已变化，已重新读取最新状态；请核对后再确认。'
    if (error.status === 429) return '当前分析额度或并发已达上限，请稍后刷新状态；不会自动重复提交。'
    if (error.status === 503) return '分析服务暂不可用，原始报告不受影响，请稍后刷新状态。'
    if (error.status === 422) return '当前资料不满足操作条件，请核对关联竞品和专题集合数量后再试。'
  }
  return fallback
}

export function GeoCompetitorsSection() {
  const navigate = useNavigate()
  const { message } = AntApp.useApp()
  const [params, setParams] = useSearchParams()
  const update = (values: Record<string, string | number | undefined>) => {
    const next = new URLSearchParams(params)
    Object.entries(values).forEach(([key, value]) => value === undefined ? next.delete(key) : next.set(key, String(value)))
    setParams(next, { replace: true })
  }
  const topicsQuery = useAllGeoTopics()
  const spaceId = params.get('spaceId')
  const topics = (topicsQuery.data ?? []).filter((topic) => !spaceId || topic.spaceId === spaceId)
  const selectedTopicId = params.get('topicId') ?? topics[0]?.id
  const topicQuery = useGeoTopic(selectedTopicId)
  const topic = topicQuery.data?.id === selectedTopicId ? topicQuery.data : undefined
  const reportPage = pageNumber(params.get('reportPage'))
  const reportsQuery = useGeoReports({ topicId: selectedTopicId, archived: false, page: reportPage, size: 20 }, Boolean(selectedTopicId))
  const reports = (reportsQuery.data?.items ?? []).filter((report) => report.topicId === selectedTopicId)
    .sort((left, right) => Date.parse(right.generatedAt) - Date.parse(left.generatedAt))
  const selectedReportId = params.get('reportId') ?? reports[0]?.id
  const questionScope = params.get('questionScope') === 'BRAND_VALIDATION' ? 'BRAND_VALIDATION' : 'DISCOVERY'
  const candidateStatus = params.get('candidateStatus') === 'IGNORED' ? 'IGNORED' : params.get('candidateStatus') === 'CONFIRMED' ? 'CONFIRMED' : 'PENDING'
  const candidatePage = pageNumber(params.get('candidatePage'))
  const insightsQuery = useGeoCompetitiveInsights(topic ? selectedReportId : undefined, { questionScope })
  const candidatesQuery = useGeoCompetitorCandidates(topic?.id, { status: candidateStatus, page: candidatePage, size: 20 })
  const competitorsQuery = useGeoCompetitorOptions(topic?.spaceId)
  const platformsQuery = useGeoPlatforms()
  const replaceMutation = useReplaceGeoCompetitors()
  const analyzeMutation = useAnalyzeGeoCompetitiveInsights()
  const confirmMutation = useConfirmGeoCompetitorCandidate()
  const ignoreMutation = useIgnoreGeoCompetitorCandidate()
  const mutationLock = useRef(false)
  const [confirmTarget, setConfirmTarget] = useState<GeoCompetitorCandidate>()
  const [confirmationError, setConfirmationError] = useState<string>()
  const mismatchedReport = Boolean(insightsQuery.data && (insightsQuery.data.topicId !== selectedTopicId
    || insightsQuery.data.reportId !== selectedReportId || insightsQuery.data.questionScope !== questionScope))
  const insights = insightsQuery.data && !mismatchedReport && insightsQuery.data.reportId === selectedReportId && insightsQuery.data.questionScope === questionScope ? insightsQuery.data : undefined
  const scopedCandidates = candidatesQuery.data ? { ...candidatesQuery.data, items: candidatesQuery.data.items.filter((item) => item.topicId === selectedTopicId && item.status === candidateStatus) } : undefined
  const competitorViews = (competitorsQuery.data ?? []).map(toGeoCompetitorView)
  const reportOptions = reports.map((report) => ({ id: report.id, label: `${formatGeoDateTime(report.generatedAt)} · ${report.sampleSize} 份样本${report.status === 'PARTIAL' ? ' · 部分完成' : ''}` }))
  if (selectedReportId && !reportOptions.some((report) => report.id === selectedReportId)) {
    reportOptions.unshift({ id: selectedReportId, label: '指定的历史报告' })
  }
  const refresh = () => {
    void topicsQuery.refetch()
    if (selectedTopicId) { void topicQuery.refetch(); void reportsQuery.refetch() }
    if (topic) { void candidatesQuery.refetch(); void competitorsQuery.refetch() }
    if (topic && selectedReportId) void insightsQuery.refetch()
  }
  const confirmCandidate = async (values: Pick<ConfirmGeoCompetitorCandidateRequest, 'existingCompetitorId' | 'type' | 'addToTopic'>) => {
    if (mutationLock.current || !confirmTarget || !topic) return
    if (topicQuery.isError || candidatesQuery.isError || competitorsQuery.isError) {
      setConfirmationError('尚未取得最新资料，请关闭弹窗并刷新后再确认。')
      return
    }
    const candidate = scopedCandidates?.items.find((item) => item.id === confirmTarget.id)
    if (!candidate || candidate.status !== 'PENDING') {
      setConfirmationError('该候选已不在待确认列表，请关闭弹窗并核对最新状态。')
      return
    }
    mutationLock.current = true
    setConfirmationError(undefined)
    try {
      await confirmMutation.mutateAsync({ candidateId: candidate.id, topicId: topic.id, spaceId: topic.spaceId,
        request: { ...values, version: candidate.version, topicVersion: topic.version } })
      setConfirmTarget(undefined)
      void message.success(values.addToTopic ? '竞品已确认并纳入专题，仅影响后续监测；已有自动计划未更改。' : '已保存为正式竞品资料，未更改专题集合或自动计划。')
    } catch (error) {
      setConfirmationError(competitiveError(error, '保存结果暂无法确认，已刷新候选状态，请核对后再操作。'))
    } finally { mutationLock.current = false }
  }
  return <GeoStateFrame label="竞品洞察工作台" loading={topicsQuery.isPending} error={toGeoViewError(topicsQuery.error)}
    onRetry={refresh} empty={!topics.length} emptyTitle="当前范围还没有 GEO 专题" emptyDescription="先建立专题并完成监测，再从真实回答中发现竞品。"
    emptyAction={<Button onClick={() => navigate(APP_PATHS.geoTopics)}>管理监测专题</Button>}>
    <GeoCompetitiveWorkbench key={selectedTopicId} topics={topics.map((item) => ({ id: item.id, label: item.name }))}
      selectedTopicId={selectedTopicId} selectedReportId={selectedReportId} reports={reportOptions}
      reportLoading={reportsQuery.isPending} reportError={competitiveError(topicQuery.error ?? reportsQuery.error, '专题或报告暂时无法加载，请重试。')}
      reportPage={reportPage} reportTotal={reportsQuery.data?.total ?? 0} platforms={(platformsQuery.data ?? []).map(toGeoPlatformView)}
      questionScope={questionScope} insights={insights} insightsLoading={Boolean(topic && selectedReportId) && insightsQuery.isPending}
      insightsError={mismatchedReport ? '返回的报告范围与当前选择不一致，请重新选择报告或刷新状态。' : competitiveError(insightsQuery.error, '竞品洞察暂时无法读取，请刷新状态。')}
      analyzing={analyzeMutation.isPending && analyzeMutation.variables === selectedReportId}
      analyzeError={analyzeMutation.variables === selectedReportId ? competitiveError(analyzeMutation.error, '提交结果暂无法确认，已查询最新分析状态，请勿重复点击。') : undefined}
      candidates={scopedCandidates} candidatesLoading={Boolean(topic) && candidatesQuery.isPending}
      candidatesError={competitiveError(candidatesQuery.error, '候选池暂时无法读取，请重试。')}
      candidateStatus={candidateStatus} candidatePage={candidatePage}
      confirmationReady={Boolean(topic) && !topicQuery.isError && !candidatesQuery.isError && competitorsQuery.isSuccess}
      ignoringId={ignoreMutation.isPending ? ignoreMutation.variables?.candidateId : undefined}
      candidateActionError={ignoreMutation.variables?.topicId === selectedTopicId ? competitiveError(ignoreMutation.error, '操作结果暂无法确认，已刷新候选状态，请核对后再操作。') : undefined}
      onTopicChange={(topicId) => { setConfirmTarget(undefined); replaceMutation.reset(); update({ topicId, reportId: undefined, reportPage: undefined, candidatePage: undefined }) }}
      onReportChange={(reportId) => update({ reportId })} onReportPageChange={(page) => update({ reportPage: page, reportId: undefined })}
      onScopeChange={(scope) => update({ questionScope: scope })} onRefresh={refresh}
      onAnalyze={() => {
        if (mutationLock.current || !selectedReportId || !insights || insightsQuery.isError || !insights.retryAllowed
          || (insights.status !== 'NOT_ANALYZED' && insights.status !== 'FAILED')) return
        mutationLock.current = true
        analyzeMutation.mutate(selectedReportId, { onSettled: () => { mutationLock.current = false } })
      }}
      onCandidateStatusChange={(status) => update({ candidateStatus: status, candidatePage: undefined })}
      onCandidatePageChange={(page) => update({ candidatePage: page })}
      onConfirmCandidate={(candidate) => { confirmMutation.reset(); setConfirmationError(undefined); setConfirmTarget(candidate) }}
      onIgnoreCandidate={(candidate) => {
        if (mutationLock.current || candidate.status !== 'PENDING') return
        mutationLock.current = true
        ignoreMutation.mutate({ candidateId: candidate.id, topicId: candidate.topicId, request: { version: candidate.version } }, {
          onSuccess: () => { void message.success('已忽略，后续发现不会自动恢复为待确认。') },
          onSettled: () => { mutationLock.current = false },
        })
      }}
      onViewEvidence={(reportId, unitKey) => navigate(`${appLinks.geoReportDetail(reportId)}?${new URLSearchParams({ sampleId: unitKey })}`)}
      onViewReport={() => { if (selectedReportId) navigate(appLinks.geoReportDetail(selectedReportId)) }}
      onManageCatalog={() => navigate(APP_PATHS.competitors)}
      formalManager={<GeoCompetitorManager embedded
        key={topic ? `${topic.id}:${topic.competitorSetVersion ?? 'none'}:${topic.competitorIds.join(',')}` : 'no-topic'}
        loading={Boolean(selectedTopicId && topicQuery.isPending) || Boolean(topic && competitorsQuery.isPending)}
        error={toGeoViewError(topicQuery.error ?? competitorsQuery.error)} onRetry={refresh}
        topics={topics.map((item) => ({ id: item.id, label: item.name }))} selectedTopicId={selectedTopicId}
        topicName={topic?.name} currentVersion={topic?.competitorSetVersion ?? undefined} competitors={competitorViews}
        selectedCompetitorIds={topic?.competitorIds ?? []} saving={replaceMutation.isPending} saveError={toGeoViewError(replaceMutation.error)}
        onSave={(ids) => { if (topic) replaceMutation.mutate({ topicId: topic.id, request: toReplaceGeoCompetitorsRequest(topic.version, ids) },
          { onSuccess: () => { void message.success('正式集合已保存，已有自动计划的授权范围未更改。') } }) }} />} />
    {confirmTarget && confirmTarget.topicId === selectedTopicId ? <GeoCandidateConfirmation key={confirmTarget.id} candidate={confirmTarget}
      competitors={competitorViews} submitting={confirmMutation.isPending} error={confirmationError}
      onCancel={() => { if (!confirmMutation.isPending) setConfirmTarget(undefined) }} onSubmit={(values) => { void confirmCandidate(values) }} /> : null}
  </GeoStateFrame>
}

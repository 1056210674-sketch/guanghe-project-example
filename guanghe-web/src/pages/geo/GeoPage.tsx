import { App as AntApp, Tabs, Typography } from 'antd'
import { useQueryClient } from '@tanstack/react-query'
import { lazy, Suspense, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { archiveGeoReport, getGeoReport, getGeoRun, restoreGeoReport } from '../../api/geoApi'
import { PageLoading } from '../../components/GlobalState'
import {
  buildGeoReportMarkdown,
  geoReportDownloadFilename,
  GeoArticleWorkflowConflictError,
  GeoOverview,
  GeoQuestionLibrary,
  GeoReportLibrary,
  GeoTopicFormDialog,
  GeoTopicManager,
  geoKeys,
  prepareGeoArticleWorkflow,
  useAllGeoTopics,
  useCreateGeoTopic,
  useDeleteGeoTopic,
  useGeoPlatforms,
  useGeoReport,
  useGeoReports,
  useGeoRuns,
  useGeoTopic,
  useGeoTopics,
  useGeoVisibilityTrend,
  useReplaceGeoKeywords,
  useReplaceGeoQuestions,
  useUpdateGeoTopic,
} from '../../features/geo'
import { GeoLongitudinalMonitoring } from '../../features/geo/components/GeoLongitudinalMonitoring'
import { downloadTextFile } from '../../features/shared/browserTransfer'
import type {
  GeoKeywordDraftView,
  GeoQuestionDraftView,
  GeoTopicFormValues,
} from '../../features/geo'
import {
  useAllBusinessSpaceOptions,
  useAllProductOptions,
} from '../../features/business-data'
import { DeleteConfirmation } from '../../features/business-data/components/DeleteConfirmation'
import { UserShell } from '../../layouts/UserShell'
import { APP_PATHS, appLinks } from '../../routes/paths'
import { useAuthStore } from '../../stores/authStore'
import '../../styles/geo.css'
import {
  EMPTY_GEO_METRICS,
  EMPTY_GEO_PLATFORM_PERFORMANCE,
  EMPTY_GEO_SUGGESTIONS,
} from './pageDefaults'
import {
  toCreateGeoTopicRequest,
  toGeoDeleteError,
  toGeoKeywordDrafts,
  toGeoQuestionDrafts,
  toGeoPlatformView,
  toGeoRecentRunView,
  toGeoReportDetailView,
  toGeoReportListItemView,
  toGeoTopicFormValues,
  toGeoTopicPagination,
  toGeoTopicSummaryView,
  toGeoTopicView,
  toGeoViewError,
  toReplaceGeoKeywordsRequest,
  toReplaceGeoQuestionsRequest,
  toUpdateGeoTopicRequest,
} from './viewAdapters'

const GeoManualMonitoringSection = lazy(() =>
  import('./GeoManualMonitoringSection').then((module) => ({
    default: module.GeoManualMonitoringSection,
  })),
)
const GeoPlansSection = lazy(() =>
  import('./GeoPlansSection').then((module) => ({
    default: module.GeoPlansSection,
  })),
)
const GeoCompetitorsSection = lazy(() =>
  import('./GeoCompetitorsSection').then((module) => ({ default: module.GeoCompetitorsSection })),
)
const GeoDiagnosticReportLibrary = lazy(() => import('./GeoDiagnosticReportLibrary').then((module) => ({ default: module.GeoDiagnosticReportLibrary })))

type GeoPageSection =
  | 'OVERVIEW'
  | 'TOPICS'
  | 'QUESTIONS'
  | 'MANUAL'
  | 'QUICK'
  | 'PLANS'
  | 'COMPETITORS'
  | 'REPORTS'

const DEFAULT_PAGE = 1
const DEFAULT_PAGE_SIZE = 20
const OPTIONS_PAGE_SIZE = 100

const GEO_SECTION_ITEMS: Array<{ key: GeoPageSection; label: string }> = [
  { key: 'OVERVIEW', label: '监测概览' },
  { key: 'TOPICS', label: '专题管理' },
  { key: 'QUESTIONS', label: '问题库' },
  { key: 'MANUAL', label: '手动监测' },
  { key: 'QUICK', label: '快速监测' },
  { key: 'PLANS', label: '自动计划' },
  { key: 'COMPETITORS', label: '竞品洞察' },
  { key: 'REPORTS', label: '历史报告' },
]

const GEO_SECTION_PATHS: Record<GeoPageSection, string> = {
  OVERVIEW: APP_PATHS.geo,
  TOPICS: APP_PATHS.geoTopics,
  QUESTIONS: APP_PATHS.geoQuestions,
  MANUAL: APP_PATHS.geoManualMonitoring,
  QUICK: APP_PATHS.geoDiagnostics,
  PLANS: APP_PATHS.geoPlans,
  COMPETITORS: APP_PATHS.geoCompetitors,
  REPORTS: APP_PATHS.geoReports,
}

function positiveInteger(value: string | null, fallback: number) {
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback
}

function firstError(...errors: unknown[]) {
  return errors.find(Boolean)
}

function useGeoUrlParams() {
  const [params, setParams] = useSearchParams()

  const update = (values: Record<string, string | number | undefined>) => {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(values)) {
      if (value === undefined || value === '') next.delete(key)
      else next.set(key, String(value))
    }
    setParams(next, { replace: true })
  }

  return { params, update }
}

function GeoOverviewSection() {
  const { message } = AntApp.useApp()
  const navigate = useNavigate()
  const ownerId = useAuthStore((state) => state.user?.id)
  const articlePrepareLock = useRef(false)
  const [preparingContentDraft, setPreparingContentDraft] = useState(false)
  const { params, update } = useGeoUrlParams()
  const topicsQuery = useAllGeoTopics()
  const selectedTopicId = params.get('topicId') ?? topicsQuery.data?.[0]?.id
  const days = params.get('days') === '7' ? 7 : params.get('days') === '90' ? 90 : 30
  const questionScope = params.get('questionScope') === 'BRAND_VALIDATION' ? 'BRAND_VALIDATION' : 'DISCOVERY'
  const trendQuery = useGeoVisibilityTrend(selectedTopicId, { days, questionScope })
  const runsQuery = useGeoRuns({ topicId: selectedTopicId, page: 1, size: 5 }, Boolean(selectedTopicId))
  const reportsQuery = useGeoReports({ topicId: selectedTopicId, archived: false, page: 1, size: 5 }, Boolean(selectedTopicId))
  const platformsQuery = useGeoPlatforms()
  const latestReportSummary = useMemo(
    () => [...(reportsQuery.data?.items ?? [])].filter((report) => report.topicId === selectedTopicId).sort(
      (left, right) =>
        Date.parse(right.generatedAt) - Date.parse(left.generatedAt),
    )[0],
    [reportsQuery.data?.items, selectedTopicId],
  )
  const latestReportQuery = useGeoReport(latestReportSummary?.id)
  const platforms = (platformsQuery.data ?? []).map(toGeoPlatformView)
  const latestReportView = latestReportQuery.data
    ? toGeoReportDetailView(
        latestReportQuery.data.report,
        latestReportQuery.data.run,
        platforms,
      )
    : undefined
  const reportsByRunId = new Map(
    (reportsQuery.data?.items ?? []).map((report) => [report.runId, report]),
  )
  const metrics = latestReportView?.metrics ?? EMPTY_GEO_METRICS
  const recentRuns = (runsQuery.data?.items ?? []).filter((run) => run.topicId === selectedTopicId).map((run) =>
    toGeoRecentRunView(run, reportsByRunId.get(run.id)),
  )

  const handlePrepareContentDraft = async (suggestionId?: string) => {
    const sourceReport = latestReportQuery.data?.report
    if (articlePrepareLock.current) return
    if (!ownerId) {
      void message.error('登录状态已失效，请重新登录后继续。')
      return
    }
    if (!sourceReport) {
      void message.error('最新监测报告尚未加载完成，请稍后重试。')
      return
    }

    articlePrepareLock.current = true
    setPreparingContentDraft(true)
    try {
      const prepared = await prepareGeoArticleWorkflow({
        ownerId,
        reportId: sourceReport.id,
        suggestionId,
      })
      void message.success(
        prepared.reused
          ? '这份报告已有待确认的文章草稿，已为你继续打开。'
          : '文章标题草稿已准备好，请检查内容和预计积分后再确认。',
      )
      navigate(appLinks.articleDetail(prepared.articleId))
    } catch (error) {
      if (error instanceof GeoArticleWorkflowConflictError) {
        void message.warning(error.message)
        if (error.existing) navigate(appLinks.articleDetail(error.existing.articleId))
      } else {
        void message.error(toGeoViewError(error)?.message ?? '文章草稿准备失败，请稍后重试。')
      }
    } finally {
      articlePrepareLock.current = false
      setPreparingContentDraft(false)
    }
  }

  return (
    <GeoOverview
      loading={
        topicsQuery.isPending ||
        (Boolean(selectedTopicId) && (runsQuery.isPending || reportsQuery.isPending)) ||
        platformsQuery.isPending ||
        (Boolean(latestReportSummary) && latestReportQuery.isPending)
      }
      error={toGeoViewError(firstError(
        topicsQuery.error,
        runsQuery.error,
        reportsQuery.error,
        platformsQuery.error,
        latestReportQuery.error,
      ))}
      metrics={metrics}
      trend={[]}
      topics={(topicsQuery.data ?? []).map((topic) => ({ id: topic.id, label: topic.name }))}
      selectedTopicId={selectedTopicId}
      longitudinal={selectedTopicId ? <GeoLongitudinalMonitoring key={selectedTopicId}
        data={trendQuery.data?.topicId === selectedTopicId ? trendQuery.data : undefined}
        loading={trendQuery.isPending} error={toGeoViewError(trendQuery.error)} platforms={platforms}
        days={days} questionScope={questionScope} onDaysChange={(value) => update({ days: value })}
        onScopeChange={(value) => update({ questionScope: value })} onRetry={() => { void trendQuery.refetch() }}
        onViewReport={(reportId, sampleId) => navigate(`${appLinks.geoReportDetail(reportId)}${sampleId ? `?sampleId=${encodeURIComponent(sampleId)}` : ''}`)}
        onManageQuestions={() => navigate(`${APP_PATHS.geoQuestions}?topicId=${encodeURIComponent(selectedTopicId)}`)}
        onManagePlan={() => navigate(`${APP_PATHS.geoPlans}?topicId=${encodeURIComponent(selectedTopicId)}`)} /> : undefined}
      onTopicChange={(topicId) => update({ topicId })}
      reportId={latestReportView?.id}
      reportTime={latestReportView?.monitoredAt}
      visibilityNotice={latestReportView?.visibilityNotice}
      platformPerformance={
        latestReportView?.platformPerformance ?? EMPTY_GEO_PLATFORM_PERFORMANCE
      }
      recentRuns={recentRuns}
      suggestions={latestReportView?.suggestions ?? EMPTY_GEO_SUGGESTIONS}
      objectLabel={latestReportView?.objectName}
      preparingContentDraft={preparingContentDraft}
      onRetry={() => {
        void topicsQuery.refetch()
        void runsQuery.refetch()
        void reportsQuery.refetch()
        void platformsQuery.refetch()
        if (latestReportSummary) void latestReportQuery.refetch()
      }}
      onCreateMonitoring={() => navigate(selectedTopicId ? `${APP_PATHS.geoManualMonitoring}?topicId=${encodeURIComponent(selectedTopicId)}` : APP_PATHS.geoManualMonitoring)}
      onViewReport={(reportId, platformCode) => navigate(`${appLinks.geoReportDetail(reportId)}${platformCode ? `?platform=${encodeURIComponent(platformCode)}` : ''}`)}
      onPrepareContentDraft={latestReportView?.methodVersion === 'geo-synthesis-v1'
        ? (suggestionId) => void handlePrepareContentDraft(suggestionId)
        : undefined}
    />
  )
}

function GeoTopicsSection() {
  const { message } = AntApp.useApp()
  const navigate = useNavigate()
  const { params, update } = useGeoUrlParams()
  const selectedSpaceId = params.get('spaceId') ?? undefined
  const selectedProductId = params.get('productId') ?? undefined
  const selectedTopicId = params.get('topicId') ?? undefined
  const keyword = params.get('keyword') ?? ''
  const page = positiveInteger(params.get('page'), DEFAULT_PAGE)
  const pageSize = Math.min(
    positiveInteger(params.get('size'), DEFAULT_PAGE_SIZE),
    OPTIONS_PAGE_SIZE,
  )

  const spacesQuery = useAllBusinessSpaceOptions()
  const productsQuery = useAllProductOptions(selectedSpaceId)
  const topicsQuery = useGeoTopics({
    spaceId: selectedSpaceId,
    productId: selectedProductId,
    keyword: keyword.trim() || undefined,
    page,
    size: pageSize,
  })
  const selectedTopicQuery = useGeoTopic(selectedTopicId)
  const selectedTopicProductsQuery = useAllProductOptions(selectedTopicQuery.data?.spaceId)

  const createMutation = useCreateGeoTopic()
  const updateMutation = useUpdateGeoTopic()
  const deleteMutation = useDeleteGeoTopic()
  const [editorMode, setEditorMode] = useState<'create' | 'edit'>()
  const [editingTopicId, setEditingTopicId] = useState<string>()
  const [formSpaceId, setFormSpaceId] = useState<string>()
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; name: string }>()
  const editingTopicQuery = useGeoTopic(editingTopicId)
  const effectiveFormSpaceId = editorMode === 'edit'
    ? editingTopicQuery.data?.spaceId
    : formSpaceId
  const formProductsQuery = useAllProductOptions(effectiveFormSpaceId)

  const spaces = spacesQuery.data ?? []
  const products = productsQuery.data ?? []
  const formProducts = formProductsQuery.data ?? []
  const selectedProducts = selectedTopicProductsQuery.data ?? []
  const spaceNames = new Map(spaces.map((space) => [space.id, space.name]))
  const productNames = new Map(products.map((product) => [product.id, product.name]))
  const selectedProductNames = new Map(selectedProducts.map((product) => [product.id, product.name]))
  const topicRows = (topicsQuery.data?.items ?? []).map((topic) =>
    toGeoTopicSummaryView(
      topic,
      spaceNames.get(topic.spaceId),
      topic.productId ? productNames.get(topic.productId) : undefined,
    ),
  )
  const selectedTopic = selectedTopicQuery.data
    ? toGeoTopicView(
        selectedTopicQuery.data,
        spaceNames.get(selectedTopicQuery.data.spaceId),
        selectedTopicQuery.data.productId
          ? selectedProductNames.get(selectedTopicQuery.data.productId)
          : undefined,
      )
    : undefined
  const editingTopic = editingTopicQuery.data

  const closeEditor = (force = false) => {
    if (!force && (createMutation.isPending || updateMutation.isPending)) return
    setEditorMode(undefined)
    setEditingTopicId(undefined)
    setFormSpaceId(undefined)
    createMutation.reset()
    updateMutation.reset()
  }

  const openCreate = () => {
    createMutation.reset()
    setEditingTopicId(undefined)
    setFormSpaceId(selectedSpaceId ?? spaces[0]?.id)
    setEditorMode('create')
  }

  const openEdit = (topicId: string) => {
    updateMutation.reset()
    setEditingTopicId(topicId)
    setEditorMode('edit')
  }

  const handleSubmit = (values: GeoTopicFormValues) => {
    if (editorMode === 'create') {
      createMutation.mutate(toCreateGeoTopicRequest(values), {
        onSuccess: (topic) => {
          message.success('GEO 专题已创建')
          closeEditor(true)
          update({ topicId: topic.id, spaceId: topic.spaceId, productId: topic.productId ?? undefined, page: 1 })
        },
      })
      return
    }
    if (!editingTopicId || !editingTopic) return
    updateMutation.mutate(
      { topicId: editingTopicId, request: toUpdateGeoTopicRequest(values, editingTopic) },
      {
        onSuccess: (topic) => {
          message.success('GEO 专题已更新')
          closeEditor(true)
          update({ topicId: topic.id, spaceId: topic.spaceId, productId: topic.productId ?? undefined, page: 1 })
        },
      },
    )
  }

  const queryError = firstError(
    spacesQuery.error,
    productsQuery.error,
    topicsQuery.error,
    selectedTopicQuery.error,
    selectedTopicProductsQuery.error,
    editingTopicQuery.error,
  )

  return (
    <>
      <GeoTopicManager
        loading={spacesQuery.isPending || topicsQuery.isPending}
        error={toGeoViewError(queryError)}
        onRetry={() => {
          void spacesQuery.refetch()
          void topicsQuery.refetch()
        }}
        spaces={spaces.map((space) => ({ id: space.id, label: space.name }))}
        products={products.map((product) => ({ id: product.id, label: product.name }))}
        selectedSpaceId={selectedSpaceId}
        selectedProductId={selectedProductId}
        keyword={keyword}
        topics={topicRows}
        selectedTopic={selectedTopic}
        pagination={toGeoTopicPagination(topicsQuery.data, page, pageSize)}
        onSpaceChange={(spaceId) => update({ spaceId, productId: undefined, topicId: undefined, page: 1 })}
        onProductChange={(productId) => update({ productId, topicId: undefined, page: 1 })}
        onKeywordChange={(value) => update({ keyword: value.trimStart(), topicId: undefined, page: 1 })}
        onCreateTopic={openCreate}
        onSelectTopic={(topicId) => update({ topicId })}
        onEditTopicIntent={openEdit}
        onOpenQuestionLibrary={(topicId) => navigate(`${APP_PATHS.geoQuestions}?topicId=${encodeURIComponent(topicId)}`)}
        onOpenCompetitorSet={(topicId) => navigate(`${APP_PATHS.geoCompetitors}?topicId=${encodeURIComponent(topicId)}`)}
        onDeleteTopicIntent={(topicId) => {
          const topic = topicRows.find((item) => item.id === topicId)
          if (topic) {
            deleteMutation.reset()
            setDeleteTarget({ id: topic.id, name: topic.name })
          }
        }}
        onPageChange={(nextPage, nextSize) => update({ page: nextSize === pageSize ? nextPage : 1, size: nextSize, topicId: undefined })}
      />

      {(editorMode === 'create' || editingTopic) ? (
        <GeoTopicFormDialog
          key={editorMode === 'edit' ? `edit:${editingTopic?.id}:${editingTopic?.version}` : 'create'}
          open
          mode={editorMode ?? 'create'}
          spaces={spaces.map((space) => ({ id: space.id, label: space.name }))}
          products={formProducts.map((product) => ({ id: product.id, label: product.name }))}
          productsLoading={Boolean(effectiveFormSpaceId) && formProductsQuery.isPending}
          initialValues={
            editorMode === 'edit' && editingTopic
              ? toGeoTopicFormValues(editingTopic)
              : effectiveFormSpaceId
                ? { spaceId: effectiveFormSpaceId, name: '' }
                : undefined
          }
          submitting={createMutation.isPending || updateMutation.isPending}
          error={toGeoViewError(createMutation.error ?? updateMutation.error)}
          onSpaceChange={setFormSpaceId}
          onCancel={closeEditor}
          onSubmit={handleSubmit}
        />
      ) : null}

      <DeleteConfirmation
        open={Boolean(deleteTarget)}
        entityLabel="GEO 专题"
        entityName={deleteTarget?.name}
        impact="服务端会检查正式配置和审计引用；存在引用时不会删除。"
        confirming={deleteMutation.isPending}
        error={toGeoDeleteError(deleteMutation.error)}
        onCancel={() => {
          if (!deleteMutation.isPending) {
            setDeleteTarget(undefined)
            deleteMutation.reset()
          }
        }}
        onConfirm={() => {
          if (!deleteTarget) return
          deleteMutation.mutate(deleteTarget.id, {
            onSuccess: () => {
              message.success('GEO 专题已删除')
              if (selectedTopicId === deleteTarget.id) update({ topicId: undefined })
              setDeleteTarget(undefined)
            },
          })
        }}
      />
    </>
  )
}

function GeoQuestionsSection() {
  const { message } = AntApp.useApp()
  const { params, update } = useGeoUrlParams()
  const selectedTopicId = params.get('topicId') ?? undefined
  const topicsQuery = useAllGeoTopics()
  const topicQuery = useGeoTopic(selectedTopicId)
  const keywordsMutation = useReplaceGeoKeywords()
  const questionsMutation = useReplaceGeoQuestions()
  const topic = topicQuery.data

  const saveKeywords = (keywords: readonly GeoKeywordDraftView[]) => {
    if (!topic) return
    keywordsMutation.mutate(
      { topicId: topic.id, request: toReplaceGeoKeywordsRequest(topic.version, keywords) },
      { onSuccess: () => message.success('正式关键词已保存') },
    )
  }

  const saveQuestions = (questions: readonly GeoQuestionDraftView[]) => {
    if (!topic) return
    questionsMutation.mutate(
      { topicId: topic.id, request: toReplaceGeoQuestionsRequest(topic.version, questions) },
      { onSuccess: () => message.success('正式问题已保存') },
    )
  }

  return (
    <GeoQuestionLibrary
      loading={topicsQuery.isPending || Boolean(selectedTopicId && topicQuery.isPending)}
      error={toGeoViewError(firstError(topicsQuery.error, topicQuery.error))}
      onRetry={() => {
        void topicsQuery.refetch()
        if (selectedTopicId) void topicQuery.refetch()
      }}
      topics={(topicsQuery.data ?? []).map((item) => ({ id: item.id, label: item.name }))}
      selectedTopicId={selectedTopicId}
      topicName={topic?.name}
      keywords={topic ? toGeoKeywordDrafts(topic) : []}
      questions={topic ? toGeoQuestionDrafts(topic) : []}
      keywordsSaving={keywordsMutation.isPending}
      questionsSaving={questionsMutation.isPending}
      keywordsError={toGeoViewError(keywordsMutation.error)}
      questionsError={toGeoViewError(questionsMutation.error)}
      onTopicChange={(topicId) => {
        keywordsMutation.reset()
        questionsMutation.reset()
        update({ topicId })
      }}
      onSaveKeywords={saveKeywords}
      onSaveQuestions={saveQuestions}
    />
  )
}

function GeoReportsSection() {
  const [params, setParams] = useSearchParams()
  const kind = params.get('reportKind') === 'diagnostic' ? 'diagnostic' : 'monitoring'
  return <Tabs activeKey={kind} onChange={(key) => { const next = new URLSearchParams(params); next.set('reportKind', key); setParams(next, { replace: true }) }} items={[
    { key: 'monitoring', label: '监测报告', children: <GeoMonitoringReportsSection /> },
    { key: 'diagnostic', label: '诊断报告', children: <Suspense fallback={<PageLoading label="正在加载诊断报告库…" />}><GeoDiagnosticReportLibrary /></Suspense> },
  ]} destroyOnHidden />
}

function GeoMonitoringReportsSection() {
  const navigate = useNavigate()
  const { message } = AntApp.useApp()
  const queryClient = useQueryClient()
  const ownerId = useAuthStore((state) => state.user?.id)
  const exportLockRef = useRef<string | undefined>(undefined)
  const [exportingReportId, setExportingReportId] = useState<string>()
  const archiveLockRef = useRef(false)
  const [archivingReportId, setArchivingReportId] = useState<string>()
  const [restoringReportId, setRestoringReportId] = useState<string>()
  const [archiveOpen, setArchiveOpen] = useState(false)
  const [archivePage, setArchivePage] = useState(1)
  const [archivePageSize, setArchivePageSize] = useState(20)
  const { params, update } = useGeoUrlParams()
  const topicId = params.get('topicId') ?? undefined
  const page = positiveInteger(params.get('page'), DEFAULT_PAGE)
  const pageSize = Math.min(
    positiveInteger(params.get('size'), DEFAULT_PAGE_SIZE),
    OPTIONS_PAGE_SIZE,
  )
  const topicsQuery = useAllGeoTopics()
  const platformsQuery = useGeoPlatforms()
  const reportsQuery = useGeoReports({ topicId, archived: false, page, size: pageSize })
  const archivedReportsQuery = useGeoReports({ archived: true, page: archivePage, size: archivePageSize })
  const topicNames = new Map(
    (topicsQuery.data ?? []).map((topic) => [topic.id, topic.name]),
  )
  const platforms = (platformsQuery.data ?? []).map(toGeoPlatformView)

  const handleExportReport = async (reportId: string) => {
    if (exportLockRef.current) return
    exportLockRef.current = reportId
    setExportingReportId(reportId)

    try {
      const report = await getGeoReport(reportId)
      const run = await getGeoRun(report.runId)
      const reportView = toGeoReportDetailView(report, run, platforms)
      downloadTextFile({
        content: buildGeoReportMarkdown(reportView),
        filename: geoReportDownloadFilename(reportView),
        contentType: 'text/markdown;charset=utf-8',
      })
      void message.success('GEO 完整报告已导出')
    } catch (error) {
      const viewError = toGeoViewError(error)
      void message.error(viewError?.message ?? '报告导出失败，请稍后重试。')
    } finally {
      exportLockRef.current = undefined
      setExportingReportId(undefined)
    }
  }

  const changeArchive = async (reportId: string, archived: boolean) => {
    if (archiveLockRef.current) return
    archiveLockRef.current = true
    if (archived) setArchivingReportId(reportId)
    else setRestoringReportId(reportId)
    try {
      if (archived) await archiveGeoReport(reportId)
      else await restoreGeoReport(reportId)
      await queryClient.invalidateQueries({ queryKey: geoKeys.forOwner(ownerId).reportLists() })
      await queryClient.invalidateQueries({ queryKey: geoKeys.forOwner(ownerId).reportDetail(reportId) })
      if (archived && reportsQuery.data?.items.length === 1 && page > 1) update({ page: page - 1 })
      if (!archived && archivedReportsQuery.data?.items.length === 1 && archivePage > 1) setArchivePage(archivePage - 1)
      void message.success(archived ? '报告已归档，证据仍可查看。' : '报告已恢复到报告库。')
    } catch (error) {
      void message.error(toGeoViewError(error)?.message ?? '报告状态更新失败，请稍后重试。')
    } finally {
      archiveLockRef.current = false
      setArchivingReportId(undefined)
      setRestoringReportId(undefined)
    }
  }

  return (
    <GeoReportLibrary
      loading={
        topicsQuery.isPending ||
        platformsQuery.isPending ||
        reportsQuery.isPending || archivedReportsQuery.isPending
      }
      error={toGeoViewError(
        firstError(topicsQuery.error, platformsQuery.error, reportsQuery.error, archivedReportsQuery.error),
      )}
      onRetry={() => {
        void topicsQuery.refetch()
        void platformsQuery.refetch()
        void reportsQuery.refetch()
        void archivedReportsQuery.refetch()
      }}
      reports={(reportsQuery.data?.items ?? []).map((report) =>
        toGeoReportListItemView(report, topicNames.get(report.topicId)),
      )}
      archivedReports={(archivedReportsQuery.data?.items ?? []).map((report) =>
        toGeoReportListItemView(report, topicNames.get(report.topicId)),
      )}
      platforms={platforms}
      objectOptions={(topicsQuery.data ?? []).map((topic) => ({
        id: topic.id,
        label: topic.name,
      }))}
      typeOptions={[]}
      filters={{ objectId: topicId }}
      enabledFilters={['OBJECT']}
      archiveOpen={archiveOpen}
      onOpenArchive={() => setArchiveOpen(true)}
      onCloseArchive={() => setArchiveOpen(false)}
      pagination={{
        current: reportsQuery.data?.page ?? page,
        pageSize: reportsQuery.data?.size ?? pageSize,
        total: reportsQuery.data?.total ?? 0,
      }}
      archivePagination={{
        current: archivedReportsQuery.data?.page ?? archivePage,
        pageSize: archivedReportsQuery.data?.size ?? archivePageSize,
        total: archivedReportsQuery.data?.total ?? 0,
      }}
      onArchivePageChange={(nextPage, nextPageSize) => {
        setArchivePage(nextPageSize === archivePageSize ? nextPage : 1)
        setArchivePageSize(nextPageSize)
      }}
      onFiltersChange={(filters) => update({
        topicId: filters.objectId,
        page: 1,
      })}
      onResetFilters={() => update({ topicId: undefined, page: 1 })}
      onPageChange={(nextPage, nextPageSize) => update({
        page: nextPageSize === pageSize ? nextPage : 1,
        size: nextPageSize,
      })}
      onViewReport={(reportId) => navigate(appLinks.geoReportDetail(reportId))}
      exportingReportId={exportingReportId}
      archivingReportId={archivingReportId}
      restoringReportId={restoringReportId}
      onArchiveReportIntent={(reportId) => { void changeArchive(reportId, true) }}
      onRestoreReportIntent={(reportId) => { void changeArchive(reportId, false) }}
      onExportReportIntent={(reportId) => {
        void handleExportReport(reportId)
      }}
    />
  )
}

interface GeoWorkspacePageProps {
  initialSection: GeoPageSection
}

function GeoWorkspacePage({ initialSection }: GeoWorkspacePageProps) {
  const navigate = useNavigate()
  const plansOnly = initialSection === 'PLANS'

  return (
    <UserShell>
      <div className="geo-page">
        <div className="geo-workspace-heading">
          <Typography.Text type="secondary">
            {plansOnly
              ? '管理定时监测计划，查看执行任务和报告。创建计划不会立即执行监测。'
              : 'GEO 基础资料、快速/手动监测、自动计划、运行记录与报告均以页面最新数据为准；正式任务仅在用户确认报价后创建。'}
          </Typography.Text>
        </div>
        {!plansOnly ? <Tabs
          className="geo-workspace-tabs"
          activeKey={initialSection}
          items={GEO_SECTION_ITEMS}
          onChange={(key) => navigate(GEO_SECTION_PATHS[key as GeoPageSection])}
        /> : null}
        <Suspense fallback={<PageLoading label="正在加载 GEO 工作区…" />}>
          {renderSection(initialSection)}
        </Suspense>
      </div>
    </UserShell>
  )
}

export function GeoPage() {
  return <GeoWorkspacePage initialSection="OVERVIEW" />
}
export function GeoTopicsPage() {
  return <GeoWorkspacePage initialSection="TOPICS" />
}
export function GeoQuestionsPage() {
  return <GeoWorkspacePage initialSection="QUESTIONS" />
}
export function GeoManualMonitoringPage() {
  return <GeoWorkspacePage initialSection="MANUAL" />
}
export function GeoQuickMonitoringPage() {
  return <GeoWorkspacePage initialSection="QUICK" />
}
export function GeoPlansPage() {
  return <GeoWorkspacePage initialSection="PLANS" />
}
export function GeoCompetitorsPage() {
  return <GeoWorkspacePage initialSection="COMPETITORS" />
}
export function GeoReportsPage() {
  return <GeoWorkspacePage initialSection="REPORTS" />
}

function renderSection(section: GeoPageSection) {
  switch (section) {
    case 'OVERVIEW':
      return <GeoOverviewSection />
    case 'TOPICS':
      return <GeoTopicsSection />
    case 'QUESTIONS':
      return <GeoQuestionsSection />
    case 'COMPETITORS':
      return <GeoCompetitorsSection />
    case 'MANUAL':
      return <GeoManualMonitoringSection />
    case 'QUICK':
      return <GeoManualMonitoringSection experience="QUICK" />
    case 'PLANS':
      return <GeoPlansSection />
    case 'REPORTS':
      return <GeoReportsSection />
  }
}

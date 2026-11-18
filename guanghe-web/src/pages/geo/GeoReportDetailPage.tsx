import { App as AntApp } from 'antd'
import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  buildGeoEvidenceSummary,
  GeoReportDetail,
  useGeoPlatforms,
  useGeoReport,
  useReinterpretGeoReport,
} from '../../features/geo'
import { isApiError } from '../../api/apiError'
import { exportGeoReport } from '../../api/geoApi'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import { useAgentInstallations } from '../../features/agent-platform/queries'
import {
  copyPlainText,
  downloadBlobFile,
} from '../../features/shared/browserTransfer'
import { openExternalHttps } from '../../features/shared/openExternalHttps'
import { UserShell } from '../../layouts/UserShell'
import { APP_PATHS, appLinks } from '../../routes/paths'
import '../../styles/geo.css'
import {
  toGeoPlatformView,
  toGeoReportDetailView,
  toGeoReportLoadError,
  toGeoViewError,
} from './viewAdapters'

function firstError(...errors: unknown[]) {
  return errors.find(Boolean)
}

export function GeoReportDetailPage() {
  const { message } = AntApp.useApp()
  const navigate = useNavigate()
  const { reportId } = useParams<{ reportId: string }>()
  const [params, setParams] = useSearchParams()
  const downloadScope = useRef(reportId)
  useEffect(() => { downloadScope.current = reportId; return () => { downloadScope.current = undefined } }, [reportId])
  const downloadLock = useRef(false)
  const [downloading, setDownloading] = useState<'md' | 'pdf'>()
  const interpretationLock = useRef(false)
  const [interpretationError, setInterpretationError] = useState<{ reportId: string; message: string }>()
  const interpretationMutation = useReinterpretGeoReport()
  const reportQuery = useGeoReport(reportId)
  const platformsQuery = useGeoPlatforms()
  const installations = useAgentInstallations()
  const platforms = (platformsQuery.data ?? []).map(toGeoPlatformView)
  const report = reportQuery.data
    ? toGeoReportDetailView(
        reportQuery.data.report,
        reportQuery.data.run,
        platforms,
      )
    : undefined
  const requestedSampleId = params.get('sampleId')
  const selectedSampleId = report?.samples.find((sample) => sample.id === requestedSampleId)?.id
    ?? report?.samples.find((sample) => sample.platform.code === params.get('platform') && sample.executionStatus === 'SUCCEEDED')?.id
    ?? report?.samples.find((sample) => sample.executionStatus === 'SUCCEEDED')?.id
    ?? report?.samples[0]?.id
  // Report errors get the existence-collapsing mapping; the platform catalogue keeps its own, since a
  // platform outage says nothing about whether this report exists.
  const reportError = toGeoReportLoadError(reportQuery.error)
  const accessUnavailable = isApiError(reportQuery.error) && [401, 403, 404].includes(reportQuery.error.status)
  const refreshFailed = Boolean(reportQuery.data && reportQuery.error && !accessUnavailable)
  const handleExport = async (format: 'md' | 'pdf') => {
    if (!report || refreshFailed || downloadLock.current) return
    const id = report.id
    const session = captureAuthSession()
    downloadLock.current = true; setDownloading(format)
    try {
      const blob = await exportGeoReport(id, format)
      if (downloadScope.current !== id || !isCurrentAuthSession(session)) return
      downloadBlobFile(blob, `geo-report-${id}.${format}`)
      void message.success('报告已导出')
    } catch {
      if (downloadScope.current === id && isCurrentAuthSession(session)) void message.error('报告导出失败，请稍后重试。')
    } finally { downloadLock.current = false; if (downloadScope.current === id && isCurrentAuthSession(session)) setDownloading(undefined) }
  }

  const handleGenerateInterpretation = async () => {
    if (!report || interpretationLock.current || refreshFailed || report.methodVersion === 'geo-synthesis-v1'
      || !report.interpretation?.canRetry || ['QUEUED', 'RUNNING'].includes(report.interpretation.status)) return
    interpretationLock.current = true
    setInterpretationError(undefined)
    try {
      await interpretationMutation.mutateAsync(report.id)
    } catch (error) {
      const safeMessage = isApiError(error) && error.status === 429
        ? '操作较频繁，请稍后再试。不会重复采集或扣积分。'
        : isApiError(error) && error.status === 409
          ? '报告状态已变化，已重新查询最新状态。'
          : '暂未确认解读请求的处理结果，已重新查询状态。请勿连续提交。'
      setInterpretationError({ reportId: report.id, message: safeMessage })
    } finally {
      interpretationLock.current = false
    }
  }

  const handlePrepareArticleDraft = (
    requestedReportId: string,
    suggestionId?: string,
  ) => {
    const sourceReport = reportQuery.data?.report
    if (!sourceReport || sourceReport.id !== requestedReportId) {
      void message.error('监测报告尚未加载完成，请稍后重试。')
      return
    }

    const search = new URLSearchParams({ geoReportId: sourceReport.id, articleGoal: 'true', project: sourceReport.topic.spaceId })
    const installedGeo = installations.data?.find((item) => item.enabled && item.agentKey === 'geo')
    if (installedGeo) search.set('installation', installedGeo.id)
    if (suggestionId) search.set('suggestionId', suggestionId)
    navigate(`${APP_PATHS.userHome}?${search}`)
  }

  return (
    <UserShell>
      <div className="geo-page">
        <GeoReportDetail
          loading={reportQuery.isPending || platformsQuery.isPending}
          error={accessUnavailable || !report
            ? reportError ?? toGeoViewError(firstError(reportQuery.error, platformsQuery.error))
            : null}
          onRetry={() => {
            void reportQuery.refetch()
            void platformsQuery.refetch()
          }}
          report={report}
          refreshFailed={refreshFailed}
          onGenerateInterpretation={() => void handleGenerateInterpretation()}
          generatingInterpretation={interpretationMutation.isPending && interpretationMutation.variables === report?.id}
          interpretationRequestError={interpretationError?.reportId === report?.id ? interpretationError?.message : undefined}
          selectedSampleId={selectedSampleId}
          onAskAi={refreshFailed ? undefined : () => {
            const source = reportQuery.data?.report
            if (source && !refreshFailed) navigate(appLinks.geoReportChat(source.id))
          }}
          onOpenCompetitiveInsights={() => {
            const source = reportQuery.data?.report
            if (source) navigate(`${APP_PATHS.geoCompetitors}?${new URLSearchParams({ topicId: source.topicId, reportId: source.id })}`)
          }}
          onReviewProduct={() => {
            const topic = reportQuery.data?.report.topic
            navigate(topic?.productId ? appLinks.productEdit(topic.productId)
              : topic?.spaceId ? appLinks.businessSpaceEdit(topic.spaceId) : APP_PATHS.businessData)
          }}
          onReconfigureMonitoring={() => {
            const topicId = reportQuery.data?.report.topicId
            navigate(topicId ? `${APP_PATHS.geoManualMonitoring}?topicId=${encodeURIComponent(topicId)}` : APP_PATHS.geoManualMonitoring)
          }}
          onPrepareRetrySample={(sampleId) => {
            const sample = report?.samples.find((item) => item.id === sampleId)
            const topicId = reportQuery.data?.report.topicId
            if (!sample || !topicId) return
            const search = new URLSearchParams({ topicId, platformCode: sample.platform.code })
            navigate(`${APP_PATHS.geoManualMonitoring}?${search.toString()}`)
          }}
          onBack={() => navigate(APP_PATHS.geoReports)}
          onExportReportIntent={downloading ? undefined : () => void handleExport('md')}
          onExportPdfIntent={() => void handleExport('pdf')}
          exportingPdf={downloading === 'pdf'}
          onSelectSample={(sampleId) => {
            const next = new URLSearchParams(params)
            next.set('sampleId', sampleId)
            next.delete('platform')
            setParams(next, { replace: true })
          }}
          onCopyEvidenceIntent={(sampleId) => {
            const sample = report?.samples.find((item) => item.id === sampleId)
            if (!report || !sample) {
              void message.error('未找到这条回答样本，请刷新后重试。')
              return
            }
            void copyPlainText(buildGeoEvidenceSummary(report, sample))
              .then(() => message.success('证据摘要已复制'))
              .catch(() => message.error('浏览器未允许复制，请检查剪贴板权限。'))
          }}
          onOpenCitationIntent={(sampleId, citationId) => {
            const citation = report?.samples
              .find((sample) => sample.id === sampleId)
              ?.citations.find((item) => item.id === citationId)
            if (citation) openExternalHttps(citation.url)
          }}
          preparingArticleDraft={false}
          onPrepareArticleDraft={(requestedReportId, suggestionId) => {
            void handlePrepareArticleDraft(requestedReportId, suggestionId)
          }}
        />
      </div>
    </UserShell>
  )
}

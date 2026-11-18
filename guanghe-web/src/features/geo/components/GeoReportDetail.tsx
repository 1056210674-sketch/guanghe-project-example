import { ArrowLeftOutlined, CopyOutlined, DownloadOutlined, FileImageOutlined, FileTextOutlined, LinkOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Space, Table, Tag, Typography } from 'antd'
import { lazy, Suspense } from 'react'
import { GEO_REPORT_UNAVAILABLE_MESSAGE, formatGeoCount, formatGeoDateTime } from '../presentation'
import type { GeoAnswerSampleView, GeoAsyncViewProps, GeoReportDetailView, GeoSuggestionView } from '../types'
import { GeoPlatformBadge } from './GeoPlatformBadge'
import { GeoPlatformVisibility } from './GeoPlatformVisibility'
import { GeoStateFrame } from './GeoStateFrame'
import { GeoStatusTag } from './GeoStatusTag'
import { GeoInterpretationStatus } from './GeoInterpretationStatus'
import { remarkGeoReportReadability } from '../reportReadability'

const MarkdownMessageContent = lazy(() => import('../../chat/components/MarkdownMessageContent')
  .then((module) => ({ default: module.MarkdownMessageContent })))

function GeoMarkdownContent({ content, readable = false }: { content: string; readable?: boolean }) {
  return <Suspense fallback={<Typography.Paragraph>{content}</Typography.Paragraph>}>
    <MarkdownMessageContent content={content} remarkPlugins={readable ? [remarkGeoReportReadability] : undefined} />
  </Suspense>
}

export interface GeoReportDetailProps extends GeoAsyncViewProps {
  report?: GeoReportDetailView
  selectedSampleId?: string
  onBack?: () => void
  onExportReportIntent?: (reportId: string) => void
  onExportPdfIntent?: (reportId: string) => void
  exportingPdf?: boolean
  onSelectSample?: (sampleId: string) => void
  onPrepareRetrySample?: (sampleId: string) => void
  onReviewProduct?: () => void
  onReconfigureMonitoring?: () => void
  onOpenCompetitiveInsights?: () => void
  onAskAi?: () => void
  onCopyEvidenceIntent?: (sampleId: string) => void
  onOpenCitationIntent?: (sampleId: string, citationId: string) => void
  onPrepareArticleDraft?: (reportId: string, suggestionId?: string) => void
  onPrepareImageDraft?: (reportId: string, suggestionId?: string) => void
  preparingArticleDraft?: boolean
  onGenerateInterpretation?: () => void
  generatingInterpretation?: boolean
  interpretationRequestError?: string
  refreshFailed?: boolean
}

function mentionLabel(sample: GeoAnswerSampleView) {
  if (sample.executionStatus !== 'SUCCEEDED') return '未取得回答'
  return sample.analysisStatus === 'MENTIONED' ? '已提及' : sample.analysisStatus === 'NOT_MENTIONED' ? '未提及' : '未评估'
}

export function GeoReportDetail({
  loading,
  error,
  onRetry,
  report,
  selectedSampleId,
  onBack,
  onExportReportIntent,
  onExportPdfIntent,
  exportingPdf = false,
  onSelectSample,
  onPrepareRetrySample,
  onReviewProduct,
  onReconfigureMonitoring,
  onOpenCompetitiveInsights,
  onAskAi,
  onCopyEvidenceIntent,
  onOpenCitationIntent,
  onPrepareArticleDraft,
  onPrepareImageDraft,
  preparingArticleDraft = false,
  onGenerateInterpretation,
  generatingInterpretation = false,
  interpretationRequestError,
  refreshFailed = false,
}: GeoReportDetailProps) {
  const selectedSample = report?.samples.find((sample) => sample.id === selectedSampleId)
    ?? report?.samples.find((sample) => sample.executionStatus === 'SUCCEEDED') ?? report?.samples[0]
  // Summary availability and valid evidence are independent; never infer visibility from either.
  const deterministic = report?.methodVersion === 'geo-deterministic-v1'
  const analyzed = report?.methodVersion !== 'geo-deterministic-v1'
  return <GeoStateFrame label="GEO 报告详情" loading={loading} error={error} onRetry={onRetry} empty={!report}
    emptyTitle={GEO_REPORT_UNAVAILABLE_MESSAGE} emptyDescription="请返回报告库选择一份仍可访问的报告。"
    emptyAction={<Button icon={<ArrowLeftOutlined />} disabled={!onBack} onClick={onBack}>返回报告库</Button>}>
    {report ? <section className="geo-report-detail" aria-labelledby="geo-report-title">
      <Button className="geo-back-button" type="text" icon={<ArrowLeftOutlined />} disabled={!onBack} onClick={onBack}>返回报告库</Button>
      <header className="geo-page-heading">
        <div><Typography.Title id="geo-report-title" level={1}>{report.name}</Typography.Title>
          <Space size={[16, 4]} wrap>
            <Typography.Text>监测对象：{report.objectName}</Typography.Text>
            <Typography.Text type="secondary">{formatGeoDateTime(report.monitoredAt)}（北京时间）</Typography.Text>
            <Typography.Text type="secondary">{formatGeoCount(report.platformCount)} 个平台</Typography.Text>
          </Space>
        </div>
        <Space wrap><GeoStatusTag status={report.status} />
          {onOpenCompetitiveInsights ? <Button onClick={onOpenCompetitiveInsights}>查看本次竞品洞察</Button> : null}
          {onAskAi ? <Button onClick={onAskAi}>带这份报告问 AI</Button> : null}
          <Button icon={<DownloadOutlined />} disabled={!onExportReportIntent} onClick={() => onExportReportIntent?.(report.id)}>导出完整报告</Button>
          {onExportPdfIntent ? <Button icon={<DownloadOutlined />} loading={exportingPdf} disabled={refreshFailed || exportingPdf} onClick={() => onExportPdfIntent(report.id)}>导出 PDF</Button> : null}
        </Space>
      </header>
      {refreshFailed ? <Alert type="warning" showIcon title="暂时无法更新报告状态，已保留上次加载的结果"
        description="请刷新状态后再操作。刷新只查询处理结果，不会再次提交解读或采集任务。"
        action={<Button onClick={onRetry}>刷新状态</Button>} /> : null}
      <Card title="各平台 AI 可见度" className="geo-panel-card">
        {report.factualSummary ? <Typography.Paragraph strong>{report.factualSummary}</Typography.Paragraph> : null}
        <Typography.Paragraph className="geo-visibility-notice">{report.visibilityNotice ?? '这份报告没有返回可核验的目标提及指标，暂不评估可见度；原始回答仍可查看。'}</Typography.Paragraph>
        <GeoPlatformVisibility platforms={report.platformPerformance} onSelectPlatform={onSelectSample ? (platformCode) => {
          const sample = report.samples.find((item) => item.platform.code === platformCode && item.executionStatus === 'SUCCEEDED')
          if (sample) onSelectSample(sample.id)
        } : undefined} />
        <Typography.Paragraph type="secondary" className="geo-field-help">可见度 = 提及监测对象的有效回答数 ÷ 有效回答数；失败与缺失单独统计。</Typography.Paragraph>
      </Card>
      <section className="geo-report-group" aria-labelledby="geo-report-conclusion-title">
        <Typography.Title level={2} id="geo-report-conclusion-title" className="geo-report-group__title">监测结论</Typography.Title>
        <GeoInterpretationStatus report={report} requesting={generatingInterpretation}
          requestError={interpretationRequestError} refreshFailed={refreshFailed}
          onGenerate={onGenerateInterpretation} onRefresh={onRetry} />
        {!deterministic ? <>
          <Card title="智能解读" className="geo-panel-card geo-report-narrative-card">
            <div className="geo-report-narrative">
              <GeoMarkdownContent content={report.summary ?? '暂无报告摘要。'} readable />
            </div>
          </Card>
          {report.findings?.length || report.coverageGaps?.length || report.competitorSignals?.length ? <div className="geo-report-observations">
            {report.findings?.length ? <GeoReportTextList title="回答中的观察" items={report.findings} /> : null}
            {report.coverageGaps?.length ? <GeoReportTextList title="采样与证据限制" items={report.coverageGaps} /> : null}
            {report.competitorSignals?.length ? <GeoReportTextList title="竞品提及信号（非市场份额）" items={report.competitorSignals} /> : null}
          </div> : null}
        </> : null}
      </section>
      <Card title="平台问题回答与引用证据" className="geo-panel-card geo-evidence-detail-card">
        <div className="geo-evidence-detail-card__layout">
          <div className="geo-sample-table">
            <Table<GeoAnswerSampleView> rowKey="id" dataSource={report.samples} pagination={false} scroll={{ x: 440 }}
              locale={{ emptyText: '本次没有保存的回答。' }}
              rowClassName={(sample) => sample.id === selectedSample?.id ? 'geo-table-row--selected' : sample.executionStatus === 'FAILED' ? 'geo-table-row--failed' : ''}
              onRow={(sample) => ({ onClick: () => onSelectSample?.(sample.id) })}
              columns={[
                { title: '问题', dataIndex: 'question', render: (question: string, sample) => <Button className="geo-question-link" type="link" onClick={() => onSelectSample?.(sample.id)}>{question}</Button> },
                { title: '平台', dataIndex: 'platform', width: 130, render: (platform: GeoAnswerSampleView['platform']) => <GeoPlatformBadge platform={platform} compact /> },
                { title: '目标提及', key: 'mention', width: 108, render: (_, sample) => <Tag color={sample.analysisStatus === 'MENTIONED' ? 'green' : undefined}>{mentionLabel(sample)}</Tag> },
              ]} />
          </div>
          <div className="geo-sample-detail">
            {selectedSample ? <>
              <div className="geo-sample-detail__heading">
                <div><Tag color={selectedSample.analysisStatus === 'MENTIONED' ? 'green' : undefined}>{mentionLabel(selectedSample)}</Tag>
                  <Typography.Title level={3}>{selectedSample.question}</Typography.Title></div>
                <GeoPlatformBadge platform={selectedSample.platform} compact />
              </div>
              <Space size={[12, 4]} wrap><Typography.Text type="secondary">第 {formatGeoCount(selectedSample.sampleRound)} 轮</Typography.Text>
                <Typography.Text type="secondary">{formatGeoDateTime(selectedSample.sampledAt)}</Typography.Text></Space>
              {selectedSample.executionStatus === 'FAILED' ? <Alert type="warning" showIcon title="本次未取得回答，不能判断是否提及"
                description={selectedSample.failureReason ?? '平台暂时没有返回有效回答。'} action={<Button size="small" icon={<ReloadOutlined />} disabled={!onPrepareRetrySample}
                  onClick={() => onPrepareRetrySample?.(selectedSample.id)}>重新配置监测</Button>} />
                : <>
                  <Card size="small" title="原始回答" className="geo-answer-card"><GeoMarkdownContent content={selectedSample.originalAnswer ?? '暂无可展示的原始回答。'} /></Card>
                  <div className="geo-citation-heading"><Typography.Title level={4}>引用来源（{selectedSample.citations.length}）</Typography.Title>
                    <Button type="link" icon={<CopyOutlined />} disabled={!onCopyEvidenceIntent} onClick={() => onCopyEvidenceIntent?.(selectedSample.id)}>复制证据摘要</Button></div>
                  {selectedSample.citations.length === 0 ? <Typography.Paragraph type="secondary" className="geo-compact-note">此回答未附可核验链接；这不等于没有提及商品。</Typography.Paragraph>
                    : <div className="geo-citation-list">{selectedSample.citations.map((citation) => <article key={citation.id} className="geo-citation-item">
                      <Typography.Text strong>{citation.title}</Typography.Text><Typography.Text type="secondary">{citation.url}</Typography.Text>
                      <Button type="link" size="small" icon={<LinkOutlined />} disabled={!onOpenCitationIntent} onClick={() => onOpenCitationIntent?.(selectedSample.id, citation.id)}>查看来源网页{citation.site ? ` · ${citation.site}` : ''}</Button>
                      {citation.excerpt ? <Typography.Paragraph type="secondary">{citation.excerpt}</Typography.Paragraph> : null}
                      <Typography.Text type="secondary">抓取时间：{formatGeoDateTime(citation.capturedAt)}</Typography.Text>
                    </article>)}</div>}
                </>}
            </> : <Typography.Paragraph type="secondary">选择一条问题，查看平台回答。</Typography.Paragraph>}
          </div>
        </div>
      </Card>
      {analyzed && (report.suggestions.length > 0 || report.contentBlueprint?.length) ? <section className="geo-report-group" aria-labelledby="geo-report-next-steps-title">
        <Typography.Title level={2} id="geo-report-next-steps-title" className="geo-report-group__title">下一步建议</Typography.Title>
        <div className="geo-report-summary-grid">
          {report.suggestions.length > 0 ? <GeoSuggestionPanel reportId={report.id} suggestions={report.suggestions} onPrepareArticleDraft={onPrepareArticleDraft} onPrepareImageDraft={onPrepareImageDraft} preparingArticleDraft={preparingArticleDraft} /> : null}
          {report.contentBlueprint?.length ? <GeoReportTextList title="内容优化蓝图" items={report.contentBlueprint} /> : null}
        </div>
      </section> : null}
      <Card title="核对资料与后续监测" className="geo-panel-card">
        <Typography.Paragraph>对照原始回答核对商品公开名称与别名，在问题库中补充实际消费场景。新增问题需确认后再调整监测计划，新监测会先报价；本次历史报告不会改写。</Typography.Paragraph>
        {analyzed && report.suggestions.length === 0 ? <Typography.Paragraph type="secondary">这份报告尚无证据充分的内容创作建议；可先完善商品事实和问题范围。</Typography.Paragraph> : null}
        <Space wrap>{onReviewProduct ? <Button onClick={onReviewProduct}>核对商品资料</Button> : null}
        {onReconfigureMonitoring || (onPrepareRetrySample && report.samples.length > 0) ? <Button icon={<ReloadOutlined />} onClick={() => {
          if (onReconfigureMonitoring) onReconfigureMonitoring()
          else if (onPrepareRetrySample) onPrepareRetrySample((report.samples.find((sample) => sample.executionStatus === 'FAILED') ?? report.samples[0]).id)
        }}>重新配置监测</Button> : null}
        </Space>
      </Card>
      <details className="geo-report-technical">
        <summary>查看采集范围、未取得的回答与积分明细</summary>
        <section aria-label="证据范围" className="geo-report-summary-grid">
          <Card title="样本范围" className="geo-panel-card">
            {report.sampleScope ? (
              <ul className="geo-report-text-list">
                <li>计划回答：{report.sampleScope.planned}</li>
                <li>有效回答：{report.sampleScope.succeeded}</li>
                <li>采集失败：{report.sampleScope.failed}</li>
                <li>未返回：{report.sampleScope.missing}</li>
              </ul>
            ) : <Typography.Text type="secondary">暂无样本范围数据。</Typography.Text>}
          </Card>
          {report.missingSamples?.length ? <GeoReportTextList title="未取得的回答" items={report.missingSamples} /> : null}
          {report.citationStructure?.length ? <GeoReportTextList title="引用网站概况" items={report.citationStructure} /> : null}
          <Card title="积分与监测范围" className="geo-panel-card">
            <ul className="geo-report-text-list">{(report.billingMetrics ?? []).map((metric) => <li key={metric.key}>{metric.label}：{metric.value}</li>)}
              <li>模式：{report.modeLabel ?? '—'}</li><li>采样轮次：{formatGeoCount(report.sampleRounds)}</li>
              <li>专题：{report.topicName ?? report.name}</li><li>竞品集合：{report.competitorSetVersion ?? '未设置'}</li></ul>
          </Card>
        </section>
        <Typography.Paragraph type="secondary">{report.methodologyNotice ?? '仅根据本次已取得的有效回答评估名称提及；失败与缺失回答无法用于判断。'}</Typography.Paragraph>
      </details>
    </section> : null}
  </GeoStateFrame>
}

function GeoReportTextList({ title, items = [] }: { title: string; items?: readonly string[] }) {
  return (
    <Card title={title} className="geo-panel-card">
      <ul className="geo-report-text-list">
        {items.map((item, index) => <li key={index}>{item}</li>)}
      </ul>
    </Card>
  )
}

function GeoSuggestionPanel({ reportId, suggestions, onPrepareArticleDraft, onPrepareImageDraft, preparingArticleDraft = false }: {
  reportId: string; suggestions: readonly GeoSuggestionView[]
  onPrepareArticleDraft?: (reportId: string, suggestionId?: string) => void
  onPrepareImageDraft?: (reportId: string, suggestionId?: string) => void
  preparingArticleDraft?: boolean
}) {
  return <Card title="建议优先动作" className="geo-panel-card"><div className="geo-suggestion-list">
    {suggestions.map((suggestion) => <article key={suggestion.id} className="geo-suggestion-item">
      <Typography.Text strong>{suggestion.title}</Typography.Text>
      {suggestion.description ? <Typography.Paragraph type="secondary">{suggestion.description}</Typography.Paragraph> : null}
      <Space wrap>
        {onPrepareArticleDraft ? <Button size="small" icon={<FileTextOutlined />} loading={preparingArticleDraft} disabled={preparingArticleDraft} onClick={() => onPrepareArticleDraft(reportId, suggestion.id)}>准备文章草稿</Button> : null}
        {onPrepareImageDraft ? <Button size="small" icon={<FileImageOutlined />} onClick={() => onPrepareImageDraft(reportId, suggestion.id)}>构思图片方案</Button> : null}
      </Space>
    </article>)}
  </div><Typography.Paragraph type="secondary" className="geo-field-help">准备方案不会直接执行创作，仍需检查内容与报价后单独确认。</Typography.Paragraph></Card>
}

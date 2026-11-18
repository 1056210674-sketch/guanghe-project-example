import { Alert, Button, Pagination, Space, Spin } from 'antd'
import type { GeoDiagnosticObservationsPage, GeoDiagnosticReport } from '../../api/geoDiagnosticApi'
import { DiagnosticDeliveryProgress } from './DiagnosticDeliveryProgress'
import { DiagnosticVisibilityChart } from './DiagnosticVisibilityChart'
import { DiagnosticActionPlan, DiagnosticCompetitorComparison, DiagnosticEvidence, DiagnosticFindings, DiagnosticQuestionMatrix, DiagnosticReportSection } from './DiagnosticReportSections'
import { diagnosticIntentLabel, diagnosticPlatformName, diagnosticPublicUrl } from './reportPresentation'
import { DiagnosticReferenceReport } from './DiagnosticReferenceReport'
import '../../styles/geo-diagnostics.css'
import '../../styles/geo-diagnostic-report.css'
import '../../styles/geo-diagnostic-reference.css'

export interface GeoDiagnosticReportViewProps {
  report: GeoDiagnosticReport
  downloading: boolean
  retrying: boolean
  actionError?: string
  observationsOpen: boolean
  observations?: GeoDiagnosticObservationsPage
  observationsLoading: boolean
  observationsError?: string
  observationsPage: number
  onDownload: () => void
  onRetry: () => void
  onRefresh: () => void
  onChat: () => void
  onObservations: () => void
  onObservationsPage: (page: number) => void
  onRefreshObservations: () => void
}

/** Every conclusion, hit and chart comes from this immutable Java report snapshot. */
export function GeoDiagnosticReportView(props: GeoDiagnosticReportViewProps) {
  const { report } = props
  if (!report.hasNoData && report.analysis?.strategy) return <DiagnosticReferenceReport {...props} />
  const analysis = report.hasNoData ? null : report.analysis
  const website = diagnosticPublicUrl(report.profile.website)
  const discoveryQuestions = [...new Map(report.samples.filter((item) => item.category === 'BRAND_DISCOVERY').map((item) => [item.questionId, item.questionText])).entries()]
  const reputationQuestions = new Set(report.samples.filter((item) => item.category === 'BRAND_REPUTATION').map((item) => item.questionId))
  const snapshot = report.snapshotAt ? new Date(report.snapshotAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) : null
  const showProgress = report.status !== 'READY' || report.hasNoData
  return <article className="geo-diagnostic-report geo-diagnostic-report--consulting" aria-label="详细诊断报告">
    <header className="geo-diagnostic-report__cover">
      <div className="geo-diagnostic-report__eyebrow"><span>光禾AI智能体框架 · GEO 洞察</span><span>报告第 {report.reportVersion} 版</span></div>
      <p className="geo-diagnostic-report__cover-label">品牌现状诊断 / BRAND DIAGNOSTIC</p>
      <h1>{analysis?.title || `${report.profile.brandName} · 品牌诊断报告`}</h1>
      <p className="geo-diagnostic-report__cover-meta">{report.profile.region || '全国'} · {report.platformCodes.map(diagnosticPlatformName).join(' / ')}{snapshot ? ` · ${snapshot}（北京时间）` : ''}</p>
      {analysis ? <div className="geo-diagnostic-report__judgment" aria-label="核心判断"><span className="geo-diagnostic-report__kicker">本次诊断的核心判断</span><p>{analysis.summary}</p></div> : null}
      <div className="geo-diagnostic-report__cover-bottom"><span>{report.status === 'READY' && !report.hasNoData ? '本版报告已交付' : '诊断数据与报告交付'}</span><Space wrap><Button className="geo-diagnostic-report__download" disabled={report.status !== 'READY' || !report.pdf || props.retrying} loading={props.downloading} onClick={props.onDownload}>下载本版 PDF</Button><Button onClick={props.onChat}>回到聊天继续优化</Button>{!showProgress ? <Button onClick={props.onRefresh} disabled={props.retrying}>刷新报告</Button> : null}</Space></div>
    </header>

    {showProgress ? <DiagnosticDeliveryProgress status={report.status} progress={report.status === 'READY' ? 100 : undefined} noData={report.hasNoData} partial={report.samplingStatus === 'PARTIAL'} canRetry={report.canRetry} retrying={props.retrying} error={props.actionError} onRetry={props.onRetry} onRefresh={props.onRefresh} /> : props.actionError ? <Alert type="error" title={props.actionError} /> : null}

    <DiagnosticReportSection number="01" title="企业画像与诊断范围" label="本次诊断对象与品牌口径">
      <div className="geo-diagnostic-report__profile"><div><p className="geo-diagnostic-report__kicker">确认的品牌信息</p><h3>{report.profile.brandName}</h3><p className="geo-diagnostic-report__text">{report.profile.introduction || '本次未填写品牌介绍。'}</p></div><dl><div><dt>对外品牌词</dt><dd>{report.profile.brandTerms.join('、') || report.profile.brandName}</dd></div><div><dt>诊断地域</dt><dd>{report.profile.region || '全国'}</dd></div><div><dt>官网</dt><dd>{website ? <a href={website} target="_blank" rel="noopener noreferrer">{report.profile.website}</a> : '未提供'}</dd></div><div><dt>问题构成</dt><dd>自然发现 {discoveryQuestions.length} 题 · 品牌口碑 {reputationQuestions.size} 题</dd></div></dl></div>
      <section className="geo-diagnostic-report__metrics" aria-label="实际采样覆盖"><div><span>计划采样</span><strong>{report.metrics.expectedSamples}</strong></div><div><span>有效回答</span><strong>{report.metrics.validSamples}</strong></div><div><span>失败回答</span><strong>{report.metrics.failedSamples}</strong></div><div><span>缺失 / 尚未采集</span><strong>{report.metrics.missingSamples}</strong></div></section>
      <p className="geo-diagnostic-report__accounting">报价 {report.quotedPoints} 积分 · 已结算 {report.settledPoints} 积分 · 已释放 {report.releasedPoints} 积分</p>
    </DiagnosticReportSection>

    <DiagnosticReportSection number="02" title="自然可见度：客户能否发现你" label="自然可见度与逐问命中">
      {report.metrics.discovery.length ? <DiagnosticVisibilityChart rows={report.metrics.discovery.map((row) => ({ label: diagnosticPlatformName(row.platformCode), values: row.mentions.filter((entry) => entry.brandId === 'OWN').map((entry) => ({ name: entry.name, rate: row.validSamples === 0 ? null : entry.mentionRate, count: entry.mentionedSamples, target: true })), valid: row.validSamples, expected: row.expectedSamples, failed: row.failedSamples, missing: row.missingSamples }))} /> : <p className="geo-diagnostic-report__muted">尚无发现型问题的有效采样数据。</p>}
      <DiagnosticQuestionMatrix report={report} />
      {analysis ? <DiagnosticFindings findings={analysis.findings.filter((finding) => finding.section === 'DISCOVERY')} report={report} /> : null}
    </DiagnosticReportSection>

    <DiagnosticReportSection number="03" title="同批竞品：差距出现在哪里">
      <DiagnosticCompetitorComparison report={report} />
      {analysis ? <DiagnosticFindings findings={analysis.findings.filter((finding) => finding.section === 'COMPETITION')} report={report} /> : null}
    </DiagnosticReportSection>

    <DiagnosticReportSection number="04" title="AI 如何理解与评价品牌" label="品牌口碑与认知">
      <p className="geo-diagnostic-report__muted">以下为明确点名品牌的口碑问题，不计入自然可见度。</p>
      <div className="geo-diagnostic-report__table-scroll" tabIndex={0} role="region" aria-label="品牌口碑采样统计"><table className="geo-diagnostic-report__table"><thead><tr><th scope="col">平台</th><th scope="col">有效 / 计划</th><th scope="col">识别目标品牌</th><th scope="col">失败</th><th scope="col">缺失</th></tr></thead><tbody>{report.metrics.reputation.map((row) => <tr key={row.platformCode}><th scope="row">{diagnosticPlatformName(row.platformCode)}</th><td>{row.validSamples} / {row.expectedSamples}</td><td>{row.validSamples ? `${row.recognizedSamples} 次` : '无有效样本'}</td><td>{row.failedSamples}</td><td>{row.missingSamples}</td></tr>)}</tbody></table></div>
      {analysis ? <DiagnosticFindings findings={analysis.findings.filter((finding) => ['REPUTATION', 'PUBLIC_RESEARCH'].includes(finding.section))} report={report} /> : null}
    </DiagnosticReportSection>

    {analysis ? <>
      <DiagnosticReportSection number="05" title="问题意图与内容机会" label="内容选题蓝图">
        <div className="geo-diagnostic-report__topics">{analysis.contentTopics.map((item, index) => <article key={index}><span className="geo-diagnostic-report__kicker">选题 {String(index + 1).padStart(2, '0')}{item.intentLayer ? ` · ${diagnosticIntentLabel(item.intentLayer)}` : ''}</span><h3>{item.title}</h3><p className="geo-diagnostic-report__topic-question">{item.audienceQuestion}</p><p className="geo-diagnostic-report__text">{item.angle}</p>{item.coveredBrandTerms.length ? <p className="geo-diagnostic-report__muted">品牌词：{item.coveredBrandTerms.join('、')}</p> : null}<DiagnosticEvidence rows={item.basisEvidence} report={report} /></article>)}</div>
      </DiagnosticReportSection>
      <DiagnosticReportSection number="06" title="从诊断到行动" label="后续优化建议"><DiagnosticActionPlan analysis={analysis} report={report} /></DiagnosticReportSection>
    </> : null}

    <DiagnosticReportSection number="A" title="证据与完整回答附录" label="诊断证据附录">
      <details className="geo-diagnostic-report__appendix"><summary>样本摘录与失败范围 <span>{report.samples.length} 条记录</span></summary><p className="geo-diagnostic-report__muted">以下为分析摘录，完整原文通过下方分页读取。</p><div className="geo-diagnostic-report__samples">{report.samples.map((sample) => <details key={sample.unitKey}><summary>{diagnosticPlatformName(sample.platformCode)} · {sample.category === 'BRAND_DISCOVERY' ? '可见度' : '口碑'} · {sample.questionText} <span className="geo-diagnostic-report__sample-status">{sample.status === 'SUCCEEDED' ? '有效' : sample.status === 'FAILED' ? '失败' : '缺失'}</span></summary>{sample.answerExcerpt ? <p className="geo-diagnostic-report__text">{sample.answerExcerpt}{sample.answerTruncated ? '\n（此处为摘录，完整回答见附录）' : ''}</p> : <p>未获取有效回答{sample.stableErrorCode ? ` · ${sample.stableErrorCode}` : ''}</p>}</details>)}</div></details>
      <div className="geo-diagnostic-report__appendix"><Button onClick={props.onObservations}>{props.observationsOpen ? '收起完整回答' : '展开完整采样回答'}</Button>{props.observationsOpen ? <>
        {props.observationsError ? <Alert type="error" title={props.observationsError} action={<Button onClick={props.onRefreshObservations}>重新加载完整回答</Button>} /> : props.observationsLoading ? <div role="status"><Spin size="small" /> 正在读取完整采样回答…</div> : <div className="geo-diagnostic-report__observations">{props.observations?.items.map((sample) => <details key={sample.unitKey}><summary>{diagnosticPlatformName(sample.platformCode)} · {sample.questionText}</summary><p className="geo-diagnostic-report__answer">{sample.answerText ?? '本次未获取有效回答。'}</p>{sample.citations.filter((source) => diagnosticPublicUrl(source.url)).map((source, index) => <p key={index}><a href={diagnosticPublicUrl(source.url)} target="_blank" rel="noopener noreferrer">{source.title || source.url}</a></p>)}</details>)}</div>}
        <Pagination current={props.observationsPage} pageSize={10} total={props.observations?.total ?? 0} showSizeChanger={false} onChange={props.onObservationsPage} />
      </> : null}</div>
      <details className="geo-diagnostic-report__appendix"><summary>公开资料来源 <span>{report.sources.length} 项</span></summary><ol className="geo-diagnostic-report__sources">{report.sources.map((source) => <li key={source.id}>{diagnosticPublicUrl(source.url) ? <a href={diagnosticPublicUrl(source.url)} target="_blank" rel="noopener noreferrer">{source.title || source.url}</a> : <span>{source.title}</span>}{source.snippet ? <p>{source.snippet}</p> : null}</li>)}</ol></details>
    </DiagnosticReportSection>
    <footer className="geo-diagnostic-report__footer"><strong>光禾AI智能体框架</strong><span>{report.profile.brandName} · GEO 现状诊断 · 第 {report.reportVersion} 版</span></footer>
  </article>
}

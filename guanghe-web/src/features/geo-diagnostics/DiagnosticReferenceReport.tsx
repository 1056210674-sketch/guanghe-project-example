import type { ReactNode } from 'react'
import { Alert, Button, Pagination, Space, Spin } from 'antd'
import type { GeoDiagnosticReport } from '../../api/geoDiagnosticApi'
import type { GeoDiagnosticReportViewProps } from './GeoDiagnosticReportView'
import { DiagnosticDeliveryProgress } from './DiagnosticDeliveryProgress'
import { DiagnosticEvidence } from './DiagnosticReportSections'
import { diagnosticBatchBaselines, diagnosticLayerMeasure, diagnosticSampleOutcome } from './diagnosticReportMetrics'
import { diagnosticIntentLabel, diagnosticPlatformName, diagnosticPublicUrl, diagnosticRate } from './reportPresentation'

type Analysis = NonNullable<GeoDiagnosticReport['analysis']>
type Recommendation = Analysis['recommendations'][number]
type Sample = GeoDiagnosticReport['samples'][number]

function Page({ number, title, subtitle, label, children, report }: { number: string; title: string; subtitle?: string; label?: string; children: ReactNode; report: GeoDiagnosticReport }) {
  return <section className={`diagnostic-reference__page diagnostic-reference__page--${number}`} aria-label={label ?? title} data-report-section={number}>
    <header className="diagnostic-reference__heading"><p>GEO 现状诊断 / {number}</p><h2>{title}</h2>{subtitle ? <div>{subtitle}</div> : null}</header>
    <div className="diagnostic-reference__body">{children}</div>
    <footer className="diagnostic-reference__page-footer"><span>{report.profile.brandName} · GEO 现状诊断</span><span>光禾AI智能体框架 · 第 {report.reportVersion} 版</span><b>{number}</b></footer>
  </section>
}

function Note({ title, children, dark = false }: { title?: string; children: ReactNode; dark?: boolean }) {
  return <div className={`diagnostic-reference__note${dark ? ' diagnostic-reference__note--dark' : ''}`}>{title ? <strong>{title}</strong> : null}<div>{children}</div></div>
}

function SourceLinks({ ids, report }: { ids: string[]; report: GeoDiagnosticReport }) {
  const sources = report.sources.filter((source) => ids.includes(source.id))
  return sources.length ? <ul className="diagnostic-reference__source-links">{sources.map((source) => <li key={source.id}>{diagnosticPublicUrl(source.url) ? <a href={diagnosticPublicUrl(source.url)} target="_blank" rel="noopener noreferrer">{source.title || source.url}</a> : source.title}</li>)}</ul> : null
}

function Outcome({ samples, brandId }: { samples: Sample[]; brandId?: string }) {
  const value = diagnosticSampleOutcome(samples, brandId)
  return <span className={`geo-diagnostic-report__outcome geo-diagnostic-report__outcome--${value.state}`}>{value.label}</span>
}

function SampleEvidenceTable({ report }: { report: GeoDiagnosticReport }) {
  const questions = [...new Map(report.samples.map((sample) => [`${sample.category}:${sample.questionId}`, sample])).values()]
  return <div className="geo-diagnostic-report__table-scroll" tabIndex={0} role="region" aria-label="逐题实际回答与命中">
    <table className="geo-diagnostic-report__table diagnostic-reference__sample-table"><thead><tr><th scope="col">本批实际问题</th><th scope="col">实际回答摘录</th><th scope="col">目标品牌提及 / 引用</th></tr></thead><tbody>{questions.map((question, index) => {
      const samples = report.samples.filter((sample) => sample.questionId === question.questionId && sample.category === question.category)
      const valid = samples.filter((sample): sample is Sample & { answerExcerpt: string } => sample.status === 'SUCCEEDED' && Boolean(sample.answerExcerpt))
      return <tr key={`${question.category}:${question.questionId}`}><th scope="row"><small>{question.category === 'BRAND_DISCOVERY' ? '自然发现' : '点名口碑'}</small><span className="geo-diagnostic-report__question-number">Q{index + 1}</span>{question.questionText}</th><td>{valid.length ? valid.slice(0, 2).map((sample) => <div className="diagnostic-reference__sample-quote" key={sample.unitKey}><b>{diagnosticPlatformName(sample.platformCode)}</b><blockquote>{sample.answerExcerpt.slice(0, 260)}{sample.answerExcerpt.length > 260 || sample.answerTruncated ? '…' : ''}</blockquote></div>) : <p>本题未取得有效回答。</p>}{valid.length > 2 ? <small>其余 {valid.length - 2} 条有效回答可在证据附录查看。</small> : null}</td><td><div className="diagnostic-reference__sample-outcomes">{report.platformCodes.map((platform) => <div key={platform}><span>{diagnosticPlatformName(platform)}</span><Outcome samples={samples.filter((sample) => sample.platformCode === platform)} /></div>)}</div><SourceLinks ids={[...new Set(samples.filter((sample) => sample.status === 'SUCCEEDED').flatMap((sample) => sample.sourceIds))]} report={report} /></td></tr>
    })}</tbody></table>
  </div>
}

function ComparisonMatrix({ report }: { report: GeoDiagnosticReport }) {
  const measured = [...new Map(report.metrics.discovery.flatMap((row) => row.mentions.map((brand) => [brand.brandId, { id: brand.brandId, name: brand.name }]))).values()]
  const competitors = [...new Map([...(report.competitors ?? []).map((brand) => [brand.id, { id: brand.id, name: brand.name }] as const), ...measured.filter((brand) => brand.id !== 'OWN').map((brand) => [brand.id, brand] as const)]).values()]
  const brands = [{ id: 'OWN', name: report.profile.brandName }, ...competitors]
  const samples = report.samples.filter((sample) => sample.category === 'BRAND_DISCOVERY')
  const questions = [...new Map(samples.map((sample) => [sample.questionId, sample.questionText])).entries()]
  return <div className="geo-diagnostic-report__table-scroll" tabIndex={0} role="region" aria-label="同批竞品对比表"><table className="geo-diagnostic-report__table diagnostic-reference__comparison"><thead><tr><th scope="col">比较范围</th>{brands.map((brand) => <th scope="col" key={brand.id} className={brand.id === 'OWN' ? 'diagnostic-reference__own' : undefined}>{brand.name}{brand.id === 'OWN' ? <small>诊断品牌</small> : null}</th>)}</tr></thead><tbody>{report.platformCodes.map((platform) => {
    const row = report.metrics.discovery.find((item) => item.platformCode === platform)
    return <tr key={platform}><th scope="row">{diagnosticPlatformName(platform)}<small>自然发现 · 本批有效回答</small></th>{brands.map((brand) => { const mention = row?.mentions.find((item) => item.brandId === brand.id); return <td key={brand.id} className={brand.id === 'OWN' ? 'diagnostic-reference__own' : undefined}>{!row || !mention ? '未采集' : !row.validSamples ? '无有效样本' : <><strong>{diagnosticRate(mention.mentionRate)}</strong><small>{mention.mentionedSamples} / {row.validSamples} 条提及</small></>}</td> })}</tr>
  })}{questions.map(([id, question], index) => <tr key={id}><th scope="row"><span className="geo-diagnostic-report__question-number">Q{index + 1}</span>{question}</th>{brands.map((brand) => <td key={brand.id} className={brand.id === 'OWN' ? 'diagnostic-reference__own' : undefined}><Outcome samples={samples.filter((sample) => sample.questionId === id)} brandId={brand.id} /></td>)}</tr>)}</tbody></table></div>
}

function Action({ item, index, report }: { item: Recommendation; index: number; report: GeoDiagnosticReport }) {
  return <article className="diagnostic-reference__action" id={`diagnostic-action-${index}`}>
    <header><strong>{item.priority}</strong><span>{String(index + 1).padStart(2, '0')}</span>{item.timeframe ? <small>{item.timeframe}</small> : null}</header>
    <div className="diagnostic-reference__action-main"><h3>{item.title}</h3>{item.businessImpact ? <p><b>业务影响</b>{item.businessImpact}</p> : null}<p><b>建议动作</b>{item.action}</p>{item.currentState ? <p className="diagnostic-reference__secondary"><b>本次观察</b>{item.currentState}</p> : null}<DiagnosticEvidence rows={item.basisEvidence} report={report} /></div>
    {item.deliverable || item.acceptance || item.ownerRole ? <div className="diagnostic-reference__action-delivery">{item.deliverable ? <div><b>建议交付</b><p>{item.deliverable}</p></div> : null}{item.acceptance ? <div><b>完成标志</b><p>{item.acceptance}</p></div> : null}{item.ownerRole ? <small>建议负责：{item.ownerRole}</small> : null}</div> : null}
  </article>
}

function Appendix(props: GeoDiagnosticReportViewProps) {
  const { report } = props
  return <section className="diagnostic-reference__appendix" aria-label="诊断证据附录"><h2>证据与完整回答附录</h2>
    <details className="geo-diagnostic-report__appendix"><summary>样本摘录与失败范围 <span>{report.samples.length} 条记录</span></summary><div className="geo-diagnostic-report__samples">{report.samples.map((sample) => <details key={sample.unitKey}><summary>{diagnosticPlatformName(sample.platformCode)} · {sample.category === 'BRAND_DISCOVERY' ? '自然发现' : '点名口碑'} · {sample.questionText} <span>{sample.status === 'SUCCEEDED' ? '有效' : sample.status === 'FAILED' ? '失败' : '缺失'}</span></summary>{sample.answerExcerpt ? <p className="geo-diagnostic-report__text">{sample.answerExcerpt}{sample.answerTruncated ? '\n（摘录，完整回答见下方）' : ''}</p> : <p>未获取有效回答{sample.stableErrorCode ? ` · ${sample.stableErrorCode}` : ''}</p>}<SourceLinks ids={sample.sourceIds} report={report} /></details>)}</div></details>
    <div className="geo-diagnostic-report__appendix"><Button onClick={props.onObservations}>{props.observationsOpen ? '收起完整回答' : '展开完整采样回答'}</Button>{props.observationsOpen ? <>{props.observationsError ? <Alert type="error" title={props.observationsError} action={<Button onClick={props.onRefreshObservations}>重新加载完整回答</Button>} /> : props.observationsLoading ? <div role="status"><Spin size="small" /> 正在读取完整采样回答…</div> : <div className="geo-diagnostic-report__observations">{props.observations?.items.map((sample) => <details key={sample.unitKey}><summary>{diagnosticPlatformName(sample.platformCode)} · {sample.questionText}</summary><p className="geo-diagnostic-report__answer">{sample.answerText ?? '本次未获取有效回答。'}</p>{sample.citations.filter((source) => diagnosticPublicUrl(source.url)).map((source, index) => <p key={index}><a href={diagnosticPublicUrl(source.url)} target="_blank" rel="noopener noreferrer">{source.title || source.url}</a></p>)}</details>)}</div>}<Pagination current={props.observationsPage} pageSize={10} total={props.observations?.total ?? 0} showSizeChanger={false} onChange={props.onObservationsPage} /></> : null}</div>
    <details className="geo-diagnostic-report__appendix"><summary>公开资料来源 <span>{report.sources.length} 项</span></summary><ol className="geo-diagnostic-report__sources">{report.sources.map((source) => <li key={source.id}>{diagnosticPublicUrl(source.url) ? <a href={diagnosticPublicUrl(source.url)} target="_blank" rel="noopener noreferrer">{source.title || source.url}</a> : source.title}{source.snippet ? <p>{source.snippet}</p> : null}</li>)}</ol></details>
  </section>
}

/** The sixteen-section consulting layout reads only the frozen Java report. */
export function DiagnosticReferenceReport(props: GeoDiagnosticReportViewProps) {
  const { report } = props
  const analysis = report.analysis
  const strategy = analysis?.strategy
  if (!analysis || !strategy) return null
  const baseline = diagnosticBatchBaselines(report)
  const website = diagnosticPublicUrl(report.profile.website)
  const snapshot = report.snapshotAt ? new Date(report.snapshotAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) : '尚未记录'
  const discoveryQuestions = [...new Map(report.samples.filter((sample) => sample.category === 'BRAND_DISCOVERY').map((sample) => [sample.questionId, sample.questionText])).entries()]
  const reputationQuestions = new Set(report.samples.filter((sample) => sample.category === 'BRAND_REPUTATION').map((sample) => sample.questionId))
  const actions = analysis.recommendations.map((item, index) => ({ item, index }))
  const actionPriorities = ['P0', 'P1', 'P2'].filter((priority) => actions.some(({ item }) => item.priority === priority))
  const omittedPriorities = ['P0', 'P1', 'P2'].filter((priority) => !actionPriorities.includes(priority))
  const omittedPriorityNote = omittedPriorities.length ? <p className="diagnostic-reference__secondary">本版未单列 {omittedPriorities.join('、')} 行动，以下仅展示已形成的建议。</p> : null
  const limitations = [...new Set([...report.limitations, ...analysis.limitations])]
  const judgments = analysis.findings.slice(0, 3)
  const categories = [{ code: 'DISCOVERY', title: '自然发现', description: '用户不点名时，是否进入答案' }, { code: 'REPUTATION', title: '点名认知', description: '用户已经知道你时，AI 如何描述' }, { code: 'COMPETITION', title: '同类选择', description: '同一批问题下，其他品牌如何出现' }, { code: 'PUBLIC_RESEARCH', title: '信源支撑', description: '公开资料如何支持这次判断' }]
  const page = { report }
  return <article className="geo-diagnostic-report geo-diagnostic-report--consulting geo-diagnostic-report--reference" aria-label="详细诊断报告">
    <Page {...page} number="01" title={analysis.title || `${report.profile.brandName} GEO 现状诊断报告`} subtitle="从 AI 的真实回答出发，判断品牌现状与下一步行动" label="报告封面摘要">
      <div className="diagnostic-reference__cover-summary"><span>本次诊断摘要</span><p>{analysis.summary}</p></div>
      <div className="diagnostic-reference__kpis" aria-label="实际采样覆盖"><div><strong>{report.platformCodes.length}</strong><span>本次采样平台</span></div><div><strong>{report.metrics.validSamples}<small> / {report.metrics.expectedSamples}</small></strong><span>有效 / 计划回答</span></div><div><strong>{diagnosticRate(baseline.discoveryRate)}</strong><span>自然提及率</span></div><div><strong>{baseline.sourceCount}</strong><span>报告信源 · URL 去重</span></div></div>
      <div className="diagnostic-reference__cover-meta">{report.profile.region || '全国'} · {snapshot}（北京时间）<br />{report.platformCodes.map(diagnosticPlatformName).join(' / ')}</div>
      <div className="geo-diagnostic-report__cover-bottom"><span>{report.status === 'READY' ? '本版报告已交付' : '报告交付中'} · 结算 {report.settledPoints} 积分 · 释放 {report.releasedPoints} 积分</span><Space wrap><Button className="geo-diagnostic-report__download" disabled={report.status !== 'READY' || !report.pdf || props.retrying} loading={props.downloading} onClick={props.onDownload}>下载本版 PDF</Button><Button onClick={props.onChat}>回到聊天继续优化</Button><Button onClick={props.onRefresh} disabled={props.retrying}>刷新报告</Button></Space></div>
    </Page>
    {report.status !== 'READY' ? <DiagnosticDeliveryProgress status={report.status} noData={report.hasNoData} partial={report.samplingStatus === 'PARTIAL'} canRetry={report.canRetry} retrying={props.retrying} error={props.actionError} onRetry={props.onRetry} onRefresh={props.onRefresh} /> : props.actionError ? <Alert type="error" title={props.actionError} /> : null}

    <Page {...page} number="02" title="诊断方法与采样范围" subtitle="资料、回答、证据分开记录，再形成行动建议">
      <div className="diagnostic-reference__method-grid"><article><span>01 / 品牌口径</span><h3>以确认资料确定诊断对象</h3><p>使用本次冻结的品牌名称、对外品牌词、官网与地域。所选同类品牌仅用于同批对照。</p><p>公开资料为身份核对和解读提供线索，保留实际获取到的来源。</p><footer>输出：品牌画像与可回查来源</footer></article><article><span>02 / 真实采样</span><h3>把自然发现与点名口碑分开</h3><p>自然发现问题不点名自有品牌或选定竞品；口碑问题明确点名目标品牌。</p><p>按本批问题与平台采样，保留有效回答、失败与缺失记录。</p><footer>输出：逐问回答与品牌命中记录</footer></article><article><span>03 / 证据解读</span><h3>由本批证据推导优化动作</h3><p>自然提及率按有效发现型回答计算；点名后的再次提及不等于正面评价。</p><p>结合回答与来源解释现状，形成可交付、可复测的行动清单。</p><footer>输出：建议优先级、选题与路线图</footer></article></div>
      <Note title="本次实际范围">自然发现 {discoveryQuestions.length} 题 + 点名口碑 {reputationQuestions.size} 题 · {report.platformCodes.length} 个平台 · 有效 {report.metrics.validSamples} 条 / 计划 {report.metrics.expectedSamples} 条 · 失败 {report.metrics.failedSamples} 条 · 缺失 {report.metrics.missingSamples} 条</Note>
    </Page>

    <Page {...page} number="03" title="品牌基础画像" subtitle="本次诊断采用的品牌身份、业务信息与公开入口">
      <div className="diagnostic-reference__profile-layout"><div className="geo-diagnostic-report__table-scroll"><table className="geo-diagnostic-report__table diagnostic-reference__profile-table"><thead><tr><th scope="col">维度</th><th scope="col">本次确认口径</th></tr></thead><tbody><tr><th scope="row">品牌名称</th><td>{report.profile.brandName}</td></tr><tr><th scope="row">品牌介绍</th><td>{report.profile.introduction || '本次未填写'}</td></tr><tr><th scope="row">目标诊断地域</th><td>{report.profile.region || '全国'}</td></tr><tr><th scope="row">对外名称 / 别名</th><td>{report.profile.brandTerms.join('、') || report.profile.brandName}</td></tr><tr><th scope="row">官网</th><td>{website ? <a href={website} target="_blank" rel="noopener noreferrer">{report.profile.website}</a> : '本次未提供'}</td></tr><tr><th scope="row">同批对照品牌</th><td>{report.competitors?.map((brand) => brand.name).join('、') || '本次未纳入'}</td></tr></tbody></table></div><aside className="diagnostic-reference__profile-aside"><div><b>资料使用边界</b><p>以上资料来自本次诊断确认快照，报告中的观察与建议对应这一版本。</p></div><div><b>品牌名称口径</b><p>{report.profile.brandTerms.join(' / ') || report.profile.brandName}</p></div><div><b>公开入口</b><p>{website ? <a href={website} target="_blank" rel="noopener noreferrer">{report.profile.website}</a> : '未提供官网，其他实际来源保留在下方。'}</p></div><div><b>本批资料引用</b><SourceLinks ids={report.sources.slice(0, 3).map((source) => source.id)} report={report} />{!report.sources.length ? <p>本批没有可回查来源。</p> : null}</div></aside></div>
    </Page>

    <Page {...page} number="04" title="诊断核心结论" label="核心判断">
      <div className="diagnostic-reference__thesis"><h3>{strategy.coreJudgment.headline}</h3><p>{strategy.coreJudgment.description}</p></div>
      <div className="diagnostic-reference__judgment-grid">{judgments.map((finding, index) => <article key={index}><span>观察 {String(index + 1).padStart(2, '0')}</span><h3>{finding.heading}</h3><p>{finding.description}</p>{finding.evidence[0]?.quote ? <blockquote>{finding.evidence[0].quote}</blockquote> : null}<DiagnosticEvidence rows={finding.evidence} report={report} /></article>)}</div>
      <Note title="对业务的意义">{strategy.cognitionFindings[0]?.businessImpact || strategy.coreJudgment.description}</Note><div aria-label="核心结论证据"><DiagnosticEvidence rows={strategy.coreJudgment.evidence} report={report} /></div>
    </Page>

    <Page {...page} number="05" title="真实问答与品牌出现情况" subtitle="顾客问题、回答原文与命中记录放在一起核对">
      <SampleEvidenceTable report={report} /><Note title="逐题核对">本次整理自然发现 {discoveryQuestions.length} 题、点名口碑 {reputationQuestions.size} 题的实际回答。可沿问题查看品牌出现位置，并在附录展开完整原文与来源。</Note>
    </Page>

    <Page {...page} number="06" title="AI 认知中的关键观察" subtitle="不只看品牌有没有出现，也看它被怎样理解">
      <div className="diagnostic-reference__cognition-rows">{strategy.cognitionFindings.map((finding, index) => <article key={index}><header><span>{String(index + 1).padStart(2, '0')}</span><h3>{finding.title}</h3></header><div><p>{finding.observation}</p><p className="diagnostic-reference__secondary"><b>业务影响：</b>{finding.businessImpact}</p><DiagnosticEvidence rows={finding.evidence} report={report} /></div></article>)}</div>{!strategy.cognitionFindings.length ? <p>本版未形成单独的认知解读，已采样回答保留在证据附录。</p> : null}
      <Note title="点名认知的当前结果">本批有效口碑回答 {baseline.reputationValid} 条，目标品牌再次提及 {baseline.reputationHits} 条。优先核对上面的具体描述与业务影响，再补充对应的公开表达。</Note>
    </Page>

    <Page {...page} number="07" title="五类问题意图与内容承接" subtitle="区分品牌、品类、场景、知识问答和选择决策">
      <div className="diagnostic-reference__split"><div className="diagnostic-reference__intent-rows">{['BRAND', 'CATEGORY', 'SCENARIO', 'QUESTION', 'DECISION'].map((code) => { const layer = strategy.keywordLayers.find((item) => item.layer === code); const samples = report.samples.filter((sample) => layer?.questionIds.includes(sample.questionId)); const measure = diagnosticLayerMeasure(samples); const questions = [...new Map(samples.map((sample) => [sample.questionId, sample.questionText])).values()]; return <article key={code}><header><h3>{diagnosticIntentLabel(code)}</h3><span>{!layer ? '未归类' : !measure.valid ? '无有效样本' : measure.unknown ? `${measure.unknown} 条未记录命中` : `${measure.hits} / ${measure.valid} 条提及`}</span></header><div>{layer ? <><p>{layer.assessment}</p>{questions.length ? <p className="diagnostic-reference__secondary">本批问题：{questions.join('；')}</p> : null}<p><b>内容承接：</b>{layer.nextAction}</p><DiagnosticEvidence rows={layer.evidence} report={report} /></> : <p>本版没有该意图的归类记录，不以 0 表示覆盖表现。</p>}</div></article> })}</div><aside className="diagnostic-reference__dark"><span>自然发现基线</span><strong className="diagnostic-reference__big-number">{diagnosticRate(baseline.discoveryRate)}</strong><p>{baseline.discoveryHits} / {baseline.discoveryValid} 条有效发现型回答提及品牌。</p><hr /><h3>{strategy.coreJudgment.headline}</h3><p>左侧按实际归类问题显示命中；含点名问题的意图层不作为自然可见度指标。</p></aside></div>
    </Page>

    <Page {...page} number="08" title="同批竞品对照" subtitle="使用相同问题与平台，对照真实品牌提及">
      <ComparisonMatrix report={report} /><Note title="本次比较结论">{analysis.findings.filter((finding) => finding.section === 'COMPETITION').map((finding) => <div key={finding.heading}><p>{finding.description}</p><DiagnosticEvidence rows={finding.evidence} report={report} /></div>)}{!analysis.findings.some((finding) => finding.section === 'COMPETITION') ? <p>本批纳入 {baseline.competitorCount} 个同类品牌，合计提及 {baseline.competitorMentions} 次。可沿上表定位品牌出现的具体问题，再查看对应回答。</p> : null}</Note>
    </Page>

    <Page {...page} number="09" title="四个视角理解品牌现状" subtitle="沿自然发现、点名认知、同类选择与信源四条线索组织判断">
      <div className="diagnostic-reference__split">
        <div className="diagnostic-reference__quadrants">{categories.map((category) => {
          const findings = analysis.findings.filter((finding) => finding.section === category.code)
          return <article key={category.code}><span>{category.description}</span><h3>{category.title}</h3>{findings.length ? findings.map((finding) => <div key={finding.heading}><h4>{finding.heading}</h4><p>{finding.description}</p><DiagnosticEvidence rows={finding.evidence} report={report} /></div>) : category.code === 'PUBLIC_RESEARCH' ? <><p>本批未单列公开调研解读。已保留 {baseline.sourceCount} 项报告信源，包含准备阶段来源。</p><SourceLinks ids={report.sources.slice(0, 3).map((source) => source.id)} report={report} /></> : <p>本批尚未形成这一视角的独立结论。</p>}</article>
        })}</div>
        <aside className="diagnostic-reference__dark"><span>核心解读</span><h3>{strategy.coreJudgment.headline}</h3><p>{strategy.coreJudgment.description}</p><DiagnosticEvidence rows={strategy.coreJudgment.evidence} report={report} /></aside>
      </div>
    </Page>

    <div className="diagnostic-reference__priority-pages" role="region" aria-label="后续优化建议">
      {actionPriorities.includes('P0') ? <Page {...page} number="10" title="P0：优先启动的行动" subtitle="优先解决本批证据中直接影响业务判断的问题"><div className="diagnostic-reference__actions diagnostic-reference__actions--p0">{actions.filter(({ item }) => item.priority === 'P0').map(({ item, index }) => <Action key={index} item={item} index={index} report={report} />)}</div>{actionPriorities[0] === 'P0' ? omittedPriorityNote : null}</Page> : null}
      {actionPriorities.includes('P1') ? <Page {...page} number="11" title="P1：形成可持续的信息承接" subtitle="把建议落到具体内容、交付物与完成标志"><div className="diagnostic-reference__actions diagnostic-reference__actions--p1">{actions.filter(({ item }) => item.priority === 'P1').map(({ item, index }) => <Action key={index} item={item} index={index} report={report} />)}</div>{actionPriorities[0] === 'P1' ? omittedPriorityNote : null}</Page> : null}
      {actionPriorities.includes('P2') ? <Page {...page} number="12" title="P2：持续积累与复测" subtitle="后续迭代与前面的优先行动衔接"><div className="diagnostic-reference__split"><div className="diagnostic-reference__actions diagnostic-reference__actions--p2">{actions.filter(({ item }) => item.priority === 'P2').map(({ item, index }) => <Action key={index} item={item} index={index} report={report} />)}</div><aside className="diagnostic-reference__dark"><span>建议推进顺序</span>{strategy.roadmap.map((stage, index) => <div className="diagnostic-reference__mini-stage" key={index}><b>{String(index + 1).padStart(2, '0')} · {stage.stage}</b><p>{stage.objective}</p></div>)}{!strategy.roadmap.length ? <p>先按前述优先级逐项落地，再沿用同口径复测。</p> : null}</aside></div>{actionPriorities[0] === 'P2' ? omittedPriorityNote : null}</Page> : null}
      {!actionPriorities.length ? <p className="diagnostic-reference__secondary">本版未形成具体的优先行动，已采样回答和分析仍保留在报告中。</p> : null}
    </div>

    <Page {...page} number="13" title="六项可复核的诊断基线" subtitle="保留本次基线，按同口径复测与逐项验收">
      <div className="geo-diagnostic-report__table-scroll" tabIndex={0} role="region" aria-label="六项诊断基线"><table className="geo-diagnostic-report__table diagnostic-reference__baseline"><thead><tr><th scope="col">指标 / 当前基线</th><th scope="col">后续检查</th><th scope="col">测量口径</th></tr></thead><tbody>{baseline.rows.map((row) => <tr key={row.label}><th scope="row">{row.label}<strong>{row.value}</strong><small>{row.detail}</small></th><td>{row.next}</td><td>{row.method}</td></tr>)}</tbody></table></div><Note title="可比性的前提">复测时保留问题、平台、品牌词与地域口径，同时检查有效样本覆盖。单批结果用于描述当时的回答表现，不直接归因于优化效果。</Note>
    </Page>

    <Page {...page} number="14" title="内容机会与选题蓝图" subtitle="从真实顾客问题出发，把已有证据组织成可使用的内容" label="内容选题蓝图">
      <div className="diagnostic-reference__topics">{analysis.contentTopics.map((topic, index) => <article key={index}><span>{String(index + 1).padStart(2, '0')}{topic.intentLayer ? ` / ${diagnosticIntentLabel(topic.intentLayer)}` : ''}</span><h3>{topic.title}</h3><dl><div><dt>顾客会怎么问</dt><dd>{topic.audienceQuestion}</dd></div><div><dt>建议内容角度</dt><dd>{topic.angle}</dd></div><div><dt>覆盖品牌词</dt><dd>{topic.coveredBrandTerms.join('、') || '按已确认品牌口径使用'}</dd></div></dl><div className="diagnostic-reference__topic-evidence"><b>引证依据</b>{topic.basisEvidence[0]?.quote ? <blockquote>{topic.basisEvidence[0].quote}</blockquote> : <p>本项未附独立引文。</p>}<DiagnosticEvidence rows={topic.basisEvidence} report={report} /></div></article>)}</div>{analysis.contentTopics.length ? <Note dark title="选题执行准备">本次已形成 {analysis.contentTopics.length} 个选题，可先从「{analysis.contentTopics[0].title}」开始，围绕对应的顾客问题整理品牌事实与引文，再按建议角度完成内容。</Note> : <p>本版尚未形成内容选题。</p>}
    </Page>

    <Page {...page} number="15" title="从诊断到执行的路线图" subtitle="每个阶段连接已有建议，交付与验收一起推进" label="建议实施路线图">
      <ol className="diagnostic-reference__timeline">{strategy.roadmap.map((stage, index) => <li key={index}><span>{String(index + 1).padStart(2, '0')}</span><h3>{stage.stage}</h3><p>{stage.objective}</p><div>{stage.recommendationIndexes.map((actionIndex) => { const action = analysis.recommendations[actionIndex]; return action ? <article key={actionIndex}><a href={`#diagnostic-action-${actionIndex}`}>{action.priority} · {action.title}</a>{action.deliverable ? <p><b>交付：</b>{action.deliverable}</p> : null}{action.acceptance ? <p><b>验收：</b>{action.acceptance}</p> : null}</article> : null })}</div></li>)}</ol>{!strategy.roadmap.length ? <p>本版没有单独的阶段路线图，可从前述优先行动开始。</p> : null}
      <div className="diagnostic-reference__execution-notes"><div><b>验证方式</b><p>对照建议中的完成标志，保留页面、内容与复测记录。</p></div><div><b>实施依赖</b><p>需使用可公开、可核对的品牌事实；负责人按实际分工确认。</p></div><div><b>判断变化</b><p>同口径复测，同时检查采样覆盖与具体回答。</p></div></div><Note dark title="执行节奏">先推进{strategy.roadmap[0]?.stage || '前述优先行动'}，每完成一项交付，再用对应问题核对新的回答表现。</Note>
    </Page>

    <Page {...page} number="16" title="行动交付清单与研究边界" subtitle="把报告变成可追踪的下一步，而不是一组泛泛建议">
      <div className="diagnostic-reference__split"><div className="diagnostic-reference__checklist">{actions.filter(({ item }) => item.deliverable).map(({ item, index }) => <article key={index}><span>{item.priority}</span><div><h3>{item.deliverable}</h3>{item.acceptance ? <p>{item.acceptance}</p> : <p>{item.action}</p>}{item.ownerRole || item.timeframe ? <small>{[item.ownerRole && `建议负责：${item.ownerRole}`, item.timeframe].filter(Boolean).join(' · ')}</small> : null}</div></article>)}{!actions.some(({ item }) => item.deliverable) ? <p>本版建议未单独定义交付物，可按前述行动与完成标志执行。</p> : null}<p className="diagnostic-reference__secondary">以上是建议交付，不是对客户现有资料缺失的认定。</p></div><aside className="diagnostic-reference__dark"><span>本报告实际范围</span><h3>{report.profile.region || '全国'} · {report.platformCodes.length} 个平台</h3><p>{report.platformCodes.map(diagnosticPlatformName).join(' / ')}</p><p>自然发现 {discoveryQuestions.length} 题，点名口碑 {reputationQuestions.size} 题。有效 {report.metrics.validSamples} / 计划 {report.metrics.expectedSamples} 条。</p><p>失败 {report.metrics.failedSamples} 条，缺失 {report.metrics.missingSamples} 条。</p>{limitations.length ? <><hr /><b>本批研究边界</b><ul>{limitations.map((item, index) => <li key={index}>{item}</li>)}</ul></> : null}</aside></div><Note title="下一步">{actionPriorities[0] ? `从 ${actionPriorities[0]} 行动开始，围绕本次确认的品牌资料与真实回答完成交付，再回到相同问题检查变化。` : '本版尚未形成行动建议，可先查看本次确认资料与真实回答。'}</Note>
    </Page>
    <Appendix {...props} />
  </article>
}

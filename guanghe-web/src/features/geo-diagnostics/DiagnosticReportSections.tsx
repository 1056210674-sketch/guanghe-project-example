import type { ReactNode } from 'react'
import type { GeoDiagnosticReport } from '../../api/geoDiagnosticApi'
import { diagnosticPlatformName, diagnosticPublicUrl, diagnosticRate } from './reportPresentation'

type Analysis = NonNullable<GeoDiagnosticReport['analysis']>
type Evidence = Analysis['findings'][number]['evidence']
type Sample = GeoDiagnosticReport['samples'][number]

export function DiagnosticReportSection({ number, title, label = title, children, className = '' }: { number: string; title: string; label?: string; children: ReactNode; className?: string }) {
  return <section className={`geo-diagnostic-report__section ${className}`} aria-label={label}>
    <header className="geo-diagnostic-report__section-heading"><span>{number}</span><h2>{title}</h2></header>
    {children}
  </section>
}

export function DiagnosticEvidence({ rows, report }: { rows: Evidence; report: GeoDiagnosticReport }) {
  if (!rows.length) return null
  return <details className="geo-diagnostic-report__evidence"><summary>查看 {rows.length} 条判断依据</summary><ol>{rows.map((item, index) => {
    const source = report.sources.find((entry) => entry.id === item.sourceId)
    const sample = report.samples.find((entry) => entry.unitKey === item.unitKey)
    const url = diagnosticPublicUrl(source?.url)
    return <li key={index}><blockquote>{item.quote}</blockquote>{source ? url ? <a href={url} target="_blank" rel="noopener noreferrer">公开资料：{source.title}</a> : <span>公开资料：{source.title}</span> : sample ? <span>{diagnosticPlatformName(sample.platformCode)} · {sample.questionText}</span> : null}</li>
  })}</ol></details>
}

function sampleOutcome(samples: Sample[]) {
  if (!samples.length) return { label: '未采集', state: 'missing' }
  const valid = samples.filter((sample) => sample.status === 'SUCCEEDED')
  if (!valid.length) return { label: samples.some((sample) => sample.status === 'FAILED') ? '采样失败' : '未采集', state: 'missing' }
  // A historic snapshot without matches is unknown, not a checked negative.
  if (valid.some((sample) => sample.matchedBrandIds == null)) return { label: '未记录命中', state: 'unknown' }
  const hits = valid.filter((sample) => sample.matchedBrandIds?.includes('OWN')).length
  if (valid.length === 1) return { label: hits ? '已提及' : '未提及', state: hits ? 'hit' : 'miss' }
  return { label: `${hits}/${valid.length} 提及`, state: hits ? 'hit' : 'miss' }
}

export function DiagnosticQuestionMatrix({ report }: { report: GeoDiagnosticReport }) {
  const discovery = report.samples.filter((sample) => sample.category === 'BRAND_DISCOVERY')
  const questions = [...new Map(discovery.map((sample) => [sample.questionId, sample.questionText])).entries()]
  if (!questions.length) return <p className="geo-diagnostic-report__muted">尚无发现型问题的采样记录。</p>
  return <div className="geo-diagnostic-report__table-scroll" tabIndex={0} role="region" aria-label="逐问自然提及矩阵">
    <table className="geo-diagnostic-report__table geo-diagnostic-report__matrix"><caption>逐问核对：顾客这样问时，是否出现 {report.profile.brandName}</caption>
      <thead><tr><th scope="col">顾客问题</th>{report.platformCodes.map((platform) => <th scope="col" key={platform}>{diagnosticPlatformName(platform)}</th>)}</tr></thead>
      <tbody>{questions.map(([id, text], index) => <tr key={id}><th scope="row"><span className="geo-diagnostic-report__question-number">Q{index + 1}</span>{text}</th>{report.platformCodes.map((platform) => {
        const outcome = sampleOutcome(discovery.filter((sample) => sample.questionId === id && sample.platformCode === platform))
        return <td key={platform}><span className={`geo-diagnostic-report__outcome geo-diagnostic-report__outcome--${outcome.state}`}>{outcome.label}</span></td>
      })}</tr>)}</tbody>
    </table>
  </div>
}

export function DiagnosticCompetitorComparison({ report }: { report: GeoDiagnosticReport }) {
  const metrics = report.metrics.discovery
  const measured = [...new Map(metrics.flatMap((platform) => platform.mentions.map((brand) => [brand.brandId, brand] as const))).values()]
  const competitors = report.competitors ?? []
  const rows = measured.map((brand) => ({ id: brand.brandId, name: brand.name, reason: competitors.find((item) => item.id === brand.brandId)?.reason }))
  for (const competitor of competitors) if (!rows.some((row) => row.id === competitor.id)) rows.push({ id: competitor.id, name: competitor.name, reason: competitor.reason })
  if (!rows.some((row) => row.id !== 'OWN')) return <p className="geo-diagnostic-report__muted">本次未纳入可比较的同类品牌。</p>
  return <div className="geo-diagnostic-report__table-scroll" tabIndex={0} role="region" aria-label="同批竞品对比表">
    <table className="geo-diagnostic-report__table"><caption>相同发现型问题、相同采样批次的品牌提及</caption><thead><tr><th scope="col">品牌 / 对照关系</th>{report.platformCodes.map((platform) => <th key={platform} scope="col">{diagnosticPlatformName(platform)}</th>)}</tr></thead>
      <tbody>{rows.map((brand) => <tr key={brand.id} className={brand.id === 'OWN' ? 'geo-diagnostic-report__own-brand' : undefined}><th scope="row">{brand.name}{brand.id === 'OWN' ? <small>本次诊断品牌</small> : brand.reason ? <small>{brand.reason}</small> : null}</th>{report.platformCodes.map((platform) => {
        const row = metrics.find((item) => item.platformCode === platform)
        const mention = row?.mentions.find((item) => item.brandId === brand.id)
        return <td key={platform}>{!row || !mention ? '未采集' : row.validSamples === 0 ? '无有效样本' : <><strong>{diagnosticRate(mention.mentionRate)}</strong><small>{mention.mentionedSamples} / {row.validSamples} 次提及</small></>}</td>
      })}</tr>)}</tbody>
    </table>
  </div>
}

export function DiagnosticFindings({ findings, report }: { findings: Analysis['findings']; report: GeoDiagnosticReport }) {
  return <div className="geo-diagnostic-report__findings">{findings.map((finding, index) => <div className="geo-diagnostic-report__finding" key={index}><h3>{finding.heading}</h3><p className="geo-diagnostic-report__text">{finding.description}</p><DiagnosticEvidence rows={finding.evidence} report={report} /></div>)}</div>
}

export function DiagnosticActionPlan({ analysis, report }: { analysis: Analysis; report: GeoDiagnosticReport }) {
  return <div className="geo-diagnostic-report__actions">{analysis.recommendations.map((item, index) => <article className="geo-diagnostic-report__action" id={`diagnostic-action-${index}`} key={index}>
    <header><span className={`geo-diagnostic-report__priority geo-diagnostic-report__priority--${item.priority.toLowerCase()}`}>{item.priority}</span><h3>{item.title}</h3><span className="geo-diagnostic-report__action-number">{String(index + 1).padStart(2, '0')}</span></header>
    {item.currentState || item.businessImpact ? <div className="geo-diagnostic-report__action-context">{item.currentState ? <div><span>当前观察</span><p>{item.currentState}</p></div> : null}{item.businessImpact ? <div><span>业务影响</span><p>{item.businessImpact}</p></div> : null}</div> : null}
    <div className="geo-diagnostic-report__action-do"><strong>具体动作</strong><p className="geo-diagnostic-report__text">{item.action}</p></div>
    {item.deliverable || item.acceptance ? <dl className="geo-diagnostic-report__action-output">{item.deliverable ? <div><dt>交付物</dt><dd>{item.deliverable}</dd></div> : null}{item.acceptance ? <div><dt>完成标志</dt><dd>{item.acceptance}</dd></div> : null}</dl> : null}
    {item.ownerRole || item.timeframe ? <p className="geo-diagnostic-report__muted">{[item.ownerRole && `建议负责：${item.ownerRole}`, item.timeframe && `建议阶段：${item.timeframe}`].filter(Boolean).join(' · ')}</p> : null}
    <DiagnosticEvidence rows={item.basisEvidence} report={report} />
  </article>)}</div>
}

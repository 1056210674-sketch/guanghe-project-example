import { Typography } from 'antd'
import { diagnosticRate, type DiagnosticChartRow } from './reportPresentation'

/** Each platform has its own denominator; no cross-platform or failure-as-zero average. */
export function DiagnosticVisibilityChart({ rows }: { rows: readonly DiagnosticChartRow[] }) {
  return <figure className="geo-diagnostic-report__chart" aria-label="分平台自然可见度对比">
    <figcaption>自然可见度：有效发现型回答中，各品牌被明确提及的比例</figcaption>
    <Typography.Paragraph type="secondary">每个平台单独统计。无有效回答时不计算比例；失败和缺失回答不计入分母。口碑问题不混入自然可见度。</Typography.Paragraph>
    {rows.map((row) => <section key={row.label} className="geo-diagnostic-report__chart-platform" aria-label={`${row.label}可见度`}>
      <header><strong>{row.label}</strong><span>有效 {row.valid}/{row.expected} · 失败 {row.failed} · 缺失 {row.missing}</span></header>
      {row.values.map((value) => <div key={value.name} className="geo-diagnostic-report__bar-row">
        <span>{value.name}</span>
        <svg viewBox="0 0 100 10" preserveAspectRatio="none" role="img" aria-label={`${value.name} ${diagnosticRate(value.rate)}，${value.count} 次提及`}>
          <rect width="100" height="10" rx="2" className="geo-diagnostic-report__bar-track" />
          {value.rate !== null ? <rect width={Math.min(100, Math.max(0, value.rate * 100))} height="10" rx="2" className={value.target ? 'geo-diagnostic-report__bar-target' : 'geo-diagnostic-report__bar-competitor'} /> : null}
        </svg>
        <span>{diagnosticRate(value.rate)}</span>
      </div>)}
    </section>)}
  </figure>
}

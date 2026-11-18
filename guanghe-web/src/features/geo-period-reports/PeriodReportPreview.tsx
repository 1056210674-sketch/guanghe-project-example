import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Empty, Space, Spin, Tag, Typography } from 'antd'
import { Link } from 'react-router-dom'
import type { GeoPeriodReport, GeoPeriodReportPlatform } from '../../api/geoPeriodReportApi'
import { MarkdownMessageContent } from '../chat/components/MarkdownMessageContent'
import { appLinks } from '../../routes/paths'

const count = (value?: number | null) => typeof value === 'number' && Number.isFinite(value) ? value.toLocaleString('zh-CN') : '—'
const rate = (value?: number | null) => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100 ? `${value.toFixed(1)}%` : '—'
const delta = (value?: number | null) => typeof value === 'number' && Number.isFinite(value) ? `${value > 0 ? '+' : ''}${value.toFixed(1)} 个百分点` : '不作比较'
const observed = (value?: string | null) => value && Number.isFinite(Date.parse(value))
  ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)) : '未记录'
const pointRate = (value?: number | null): value is number => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100
const platformName = (code: string) => ({ DOUBAO: '豆包', DEEPSEEK: 'DeepSeek', QWEN: '千问', WENXIN: '文心', KIMI: 'Kimi', YUANBAO: '元宝', ZHIPU: '智谱' } as Record<string, string>)[code] ?? code
const reasonLabel = (reason?: string | null) => reason ? ({ MISSING_TARGET_SNAPSHOT: '历史记录缺少监测名称', AMBIGUOUS_TARGET_NAMES: '监测名称无法可靠区分',
  MISSING_QUESTION_METADATA: '历史记录缺少问题版本', MISSING_SAMPLING_METADATA: '历史记录缺少采样方式', TARGET_CHANGED: '监测对象发生变化',
  REGION_CHANGED: '监测地区发生变化', SAMPLING_CHANGED: '采样方式发生变化', QUESTION_CHANGED: '问题版本发生变化', PLATFORM_NOT_SAMPLED: '有一期未采样此平台',
  NO_COMMON_VALID_SAMPLES: '没有共同有效样本', BASELINE_UNAVAILABLE: '没有可用基线', NO_BASELINE: '没有可用基线', EVIDENCE_LIMIT: '样本超出本次处理范围',
  PERIOD_TRUNCATED: '周期批次超出展示上限，不计算整个周期的首尾变化', NOT_COMPARABLE: '采样口径不可比', SINGLE_OBSERVATION: '仅有一次有效观察',
} as Record<string, string>)[reason] ?? '数据口径不足，暂不作比较' : undefined
function publicationLink(value: string): string | undefined {
  try { const url = new URL(value); return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : undefined } catch { return undefined }
}

/** Only backend-approved comparable points connect. Different question scopes are never averaged. */
function PlatformTrend({ platform }: { platform: GeoPeriodReportPlatform }) {
  const points = platform.points ?? []
  const x = (index: number) => points.length < 2 ? 310 : 44 + (index * 512) / (points.length - 1)
  const y = (value: number) => 150 - value * 1.2
  return <div className="geo-period-report__section">
    <h3>{platformName(platform.platformCode)}</h3>
    {points.some((point) => pointRate(point.mentionRate)) ? <svg className="geo-period-report__chart" viewBox="0 0 600 190" role="img" aria-label={`${platform.platformCode} 品牌提及率，各点仅在采样可比时连线`}>
      <title>{platform.platformCode} 品牌提及率</title>
      <desc>纵轴为百分比，横轴按监测批次排列。断线表示样本或采集口径不可比，详细数值见下方采样明细。</desc>
      {[0, 50, 100].map((value) => <g key={value}><line x1="44" x2="568" y1={y(value)} y2={y(value)} stroke="#e7e9ed" /><text x="34" y={y(value) + 4} textAnchor="end" fill="#727781" fontSize="11">{value}%</text></g>)}
      {points.map((point, index) => {
        const previous = points[index - 1]
        if (!point.connectToPrevious || !pointRate(point.mentionRate) || !previous || !pointRate(previous.mentionRate)) return null
        return <line key={`${point.reportId}-${index}-line`} x1={x(index - 1)} x2={x(index)} y1={y(previous.mentionRate)} y2={y(point.mentionRate)} stroke="#d6453d" strokeWidth="2" />
      })}
      {points.map((point, index) => pointRate(point.mentionRate) ? <circle key={`${point.reportId}-${index}`} cx={x(index)} cy={y(point.mentionRate)} r="4" fill="#d6453d">
        <title>{observed(point.observedAt)}：{rate(point.mentionRate)}，有效样本 {point.validSamples}</title>
      </circle> : null)}
      {points.map((point, index) => index === 0 || index === points.length - 1 || index === Math.floor((points.length - 1) / 2)
        ? <text key={`${point.reportId}-${index}-date`} x={x(index)} y="176" textAnchor="middle" fill="#727781" fontSize="10">{observed(point.observedAt)}</text> : null)}
    </svg> : <Typography.Paragraph type="secondary">没有足够的有效样本绘制提及率，未将失败或缺失样本当作零值。</Typography.Paragraph>}
    <p className="geo-period-report__note">各点为该批次有效样本提及率。断线不代表下降；跨批次比较以共同样本与采集口径核验结果为准。</p>
    <details><summary>查看采样明细</summary><div className="geo-period-report__table-wrap"><table>
      <thead><tr><th>监测完成时间</th><th>提及率</th><th>有效 / 失败 / 缺失</th><th>说明</th></tr></thead>
      <tbody>{points.map((point, index) => <tr key={`${point.reportId}-${index}`}>
        <td>{point.reportId ? <Link to={appLinks.geoReportDetail(point.reportId)}>{observed(point.observedAt)}</Link> : observed(point.observedAt)}</td><td>{rate(point.mentionRate)}</td>
        <td>{count(point.validSamples)} / {count(point.failedSamples)} / {count(point.missingSamples)}</td>
        <td>{reasonLabel(point.comparisonReason) || (point.connectToPrevious ? '可与上一批次连接' : '单独观察，不连线')}</td>
      </tr>)}</tbody>
    </table></div></details>
  </div>
}

interface Props {
  report?: GeoPeriodReport
  loading: boolean
  loadError?: string
  actionError?: string
  retrying: boolean
  downloading: boolean
  onRefresh: () => void
  onRetrySummary: () => void
  onDownload: () => void
  onNewReport: () => void
}

export function PeriodReportPreview({ report, loading, loadError, actionError, retrying, downloading,
  onRefresh, onRetrySummary, onDownload, onNewReport }: Props) {
  if (loadError) return <Alert type="error" showIcon title={loadError} action={<Button onClick={onRefresh}>重新读取</Button>} />
  if (loading || !report) return <div className="geo-period-report__empty" role="status"><Spin /> <Typography.Text>正在读取已保存的报告…</Typography.Text></div>
  const running = ['QUEUED', 'RUNNING'].includes(report.status)
  const downloadable = ['READY', 'SUMMARY_UNAVAILABLE'].includes(report.status)
  const metrics = report.metrics
  const labels: [string, number | null | undefined][] = [
    ['监测执行', metrics?.monitorRuns], ['有效采样', metrics?.validSamples], ['新建文章', metrics?.createdArticles], ['发布成功篇次', metrics?.publishedPlacements],
  ]
  return <article className="geo-period-report" aria-label="客户项目周期成果报告">
    <div className="geo-period-report__actions"><Tag color={report.status === 'READY' ? 'success' : report.status === 'SUMMARY_UNAVAILABLE' ? 'warning' : 'processing'}>
      {report.status === 'READY' ? '报告已生成' : report.status === 'SUMMARY_UNAVAILABLE' ? '数据已准备，总结生成失败' : '报告生成中'}
    </Tag><Space wrap><Button size="small" onClick={onRefresh} disabled={retrying || downloading}>刷新报告</Button><Button size="small" onClick={onNewReport}>新建报告</Button><Button size="small" aria-label="导出 PDF" icon={<DownloadOutlined />} disabled={!downloadable || retrying} loading={downloading} onClick={onDownload}>导出 PDF</Button></Space></div>
    <header className="geo-period-report__heading"><p>{report.projectName} · 客户成果报告</p><h1>{report.title}</h1>
      <p>{report.startDate} — {report.endDate}（北京时间）</p><p>数据快照：{observed(report.snapshotAt)} · 版本 {report.version} · 已保存，可刷新后继续查看</p>
      {report.focus ? <p>补充重点：{report.focus}</p> : null}
    </header>
    {running ? <Alert type="info" showIcon icon={<Spin size="small" />} title={report.status === 'QUEUED' ? '报告正在排队' : '正在整理报告总结'} description="正在使用已冻结的数据快照生成报告，不会重新监测或投稿。" /> : null}
    {report.status === 'SUMMARY_UNAVAILABLE' ? <Alert type="warning" showIcon title="数据已准备，总结生成失败"
      description={report.canRetrySummary ? '下方统计与图表仍可查看和导出。重试仅重新生成总结，不重新采集数据。' : '下方统计与图表仍可查看和导出，本报告已达到总结重试次数上限。'}
      action={report.canRetrySummary ? <Button aria-label="重试总结" loading={retrying} disabled={downloading} icon={<ReloadOutlined />} onClick={onRetrySummary}>重试总结</Button> : undefined} /> : null}
    {actionError ? <Alert type="error" showIcon title={actionError} /> : null}
    {report.empty ? <Empty className="geo-period-report__empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description="所选周期暂无监测、文章或投稿记录。报告会如实保留空数据，不会自动启动任务。" /> : null}
    {metrics ? <><dl className="geo-period-report__metrics">{labels.map(([label, value]) => <div className="geo-period-report__metric" key={label}><dt>{label}</dt><dd>{count(value)}</dd></div>)}</dl>
      <p className="geo-period-report__note">监测：完成 {count(metrics.completedMonitorRuns)} · 部分完成 {count(metrics.partialMonitorRuns)} · 失败 {count(metrics.failedMonitorRuns)} · 执行中 {count(metrics.runningMonitorRuns)}。<br />
        采样：预计 {count(metrics.expectedSamples)} · 有效 {count(metrics.validSamples)} · 失败 {count(metrics.failedSamples)} · 缺失 {count(metrics.missingSamples)}。<br />
        发布：文章 {count(metrics.publishedArticles)} 篇 · 成功篇次 {count(metrics.publishedPlacements)} · 已返回回链 {count(metrics.publishedLinks)} 条 · 投稿处理中 {count(metrics.pendingSubmissions)} · 投稿失败 {count(metrics.failedSubmissions)}。</p></> : null}
    {report.status === 'READY' && report.summaryMarkdown ? <section className="geo-period-report__section"><h2>周期总结</h2><MarkdownMessageContent content={report.summaryMarkdown} /></section> : null}
    {(report.topics ?? []).map((topic) => <section className="geo-period-report__section" key={topic.topicId}>
      <h2>{topic.topicName}</h2><p className="geo-period-report__note">{topic.questionScope === 'DISCOVERY' ? '发现型问题：问题中未直接包含目标品牌名称。' : '按本专题已保存问题口径统计。'}
        {topic.baselineKind === 'PRE_PERIOD' ? '基线为周期前最近一次有效监测。' : topic.baselineKind === 'PERIOD_FIRST' ? '周期前无可用基线，采用周期内首次有效监测作为基线。' : '没有可用基线，不计算提升。'}</p>
      <div className="geo-period-report__table-wrap"><table><thead><tr><th>平台</th><th>基线提及率</th><th>期末提及率</th><th>变化</th><th>共同样本</th></tr></thead>
        <tbody>{topic.platforms.map((platform) => <tr key={platform.platformCode}><td>{platformName(platform.platformCode)}</td><td>{rate(platform.baselineMentionRate)}<br /><small>{observed(platform.baselineObservedAt)}</small></td><td>{rate(platform.endMentionRate)}<br /><small>{observed(platform.endObservedAt)}</small></td><td>{delta(platform.deltaPercentagePoints)}</td><td>{count(platform.commonSamples)}</td></tr>)}</tbody>
      </table></div>
      {topic.platforms.map((platform) => <div key={platform.platformCode}><PlatformTrend platform={platform} />
        {platform.comparisonReason ? <p className="geo-period-report__note">比较说明：{reasonLabel(platform.comparisonReason)}</p> : null}</div>)}
    </section>)}
    <section className="geo-period-report__section"><h2>内容成果</h2><p className="geo-period-report__note">按周期内新建文章统计，包含草稿，不等于已完成成稿或已发布。</p>{report.articles?.length ? <ul className="geo-period-report__sources">
      {report.articles.map((item) => <li key={item.id}><Link to={appLinks.articleDetail(item.id)}>{item.title}</Link> · 版本 {item.version} · {observed(item.createdAt)}</li>)}
    </ul> : <Typography.Text type="secondary">所选周期暂无新建文章。</Typography.Text>}</section>
    <section className="geo-period-report__section"><h2>投稿与回链</h2>{report.publications?.length ? <div className="geo-period-report__table-wrap"><table>
      <thead><tr><th>文章 / 媒体</th><th>状态</th><th>回链</th></tr></thead><tbody>{report.publications.map((item) => <tr key={item.id}>
        <td>{item.articleTitle}<br /><Typography.Text type="secondary">{item.mediaName}</Typography.Text></td>
        <td>{({ PUBLISHED: '已发布', SUCCEEDED: '已发布', FAILED: '失败', REJECTED: '退稿', SUBMITTED: '已提交', SUBMITTING: '提交中', PENDING: '处理中', QUEUED: '排队中', ACCEPTED: '媒体已受理', RECONCILING: '核对回执中', SUBMIT_UNKNOWN: '提交结果待核实' } as Record<string, string>)[item.status] ?? '状态待核实'}<br />{item.confirmedPublishedAt ? observed(item.confirmedPublishedAt) : ''}</td>
        <td>{item.publishedUrls?.length ? item.publishedUrls.map((url, index) => {
          const href = publicationLink(url)
          return href ? <div key={url}><a href={href} target="_blank" rel="noopener noreferrer">查看回链 {index + 1}</a></div> : <span key={url}>回链暂不可打开</span>
        }) : '暂无回链'}</td>
      </tr>)}</tbody>
    </table></div> : <Typography.Text type="secondary">所选周期暂无投稿成果。</Typography.Text>}</section>
    {report.limitations?.length ? <section className="geo-period-report__section"><h2>统计口径与数据限制</h2><ul className="geo-period-report__sources">{report.limitations.map((value, index) => <li key={index}>{value}</li>)}</ul></section> : null}
  </article>
}

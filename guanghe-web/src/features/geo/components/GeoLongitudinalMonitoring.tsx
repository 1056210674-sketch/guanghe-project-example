import { useState } from 'react'
import { Alert, Button, Card, Empty, Segmented, Select, Space, Spin, Table, Tag, Typography } from 'antd'
import type { GeoVisibilityTrend, GeoVisibilityTrendPoint } from '../../../api/geoApi'
import type { GeoPlatformView, GeoViewError } from '../types'
import { formatGeoDateTime } from '../presentation'
import { GeoPlatformBadge } from './GeoPlatformBadge'

const platformStyles: Record<string, { color: string; dash: string; shape: number }> = {
  DOUBAO: { color: '#2877bf', dash: '', shape: 0 }, DEEPSEEK: { color: '#7950ba', dash: '8 3', shape: 1 },
  QWEN: { color: '#b24c14', dash: '3 3', shape: 2 }, WENXIN: { color: '#168077', dash: '10 3 2 3', shape: 0 },
  KIMI: { color: '#be416b', dash: '12 4', shape: 1 }, YUANBAO: { color: '#527b17', dash: '2 4', shape: 2 },
  ZHIPU: { color: '#806323', dash: '6 2 2 2', shape: 0 },
}
const reasons: Record<NonNullable<GeoVisibilityTrendPoint['comparisonReason']>, string> = {
  MISSING_TARGET_SNAPSHOT: '历史记录缺少监测名称', AMBIGUOUS_TARGET_NAMES: '监测名称无法可靠区分',
  MISSING_QUESTION_METADATA: '历史记录缺少问题版本', MISSING_SAMPLING_METADATA: '历史记录缺少采样方式',
  TARGET_CHANGED: '商品识别范围发生变化', REGION_CHANGED: '监测地区发生变化', SAMPLING_CHANGED: '平台采样方式发生变化',
  QUESTION_CHANGED: '问题版本发生变化', PLATFORM_NOT_SAMPLED: '两期中有一期未采样', NO_COMMON_VALID_SAMPLES: '两期没有共同有效样本',
  BASELINE_UNAVAILABLE: '持续跟踪题组尚不可用', EVIDENCE_LIMIT: '历史样本超出本次查询范围',
}
const changes = { NEWLY_MENTIONED: '本轮新出现提及', CONTINUED_MENTION: '两期均有提及', NO_LONGER_MENTIONED: '本次未再提及', NEVER_MENTIONED: '两期均未提及', NEW_EXPLORATION: '首次探索', NOT_COMPARABLE: '暂不可比较' }
const rate = (value: number | null) => value !== null && Number.isFinite(value) ? `${value.toLocaleString('zh-CN', { maximumFractionDigits: 1 })}%` : '未评估'
const validRate = (value: number | null): value is number => value !== null && Number.isFinite(value) && value >= 0 && value <= 100
const sampleRatio = (mentioned: number | null, valid: number) => valid > 0 && mentioned !== null ? `${mentioned}/${valid}` : '未评估'
const pointKey = (platform: string, point: GeoVisibilityTrendPoint) => `${platform}:${point.runId}`

function comparisonReason(reason: keyof typeof reasons, missingPeriod?: GeoVisibilityTrendPoint['missingPeriod']) {
  if (reason !== 'PLATFORM_NOT_SAMPLED') return reasons[reason]
  return missingPeriod === 'PREVIOUS' ? '上期未监测该平台'
    : missingPeriod === 'CURRENT' ? '本期未监测该平台'
      : missingPeriod === 'BOTH' ? '两期均未监测该平台'
        : reasons.PLATFORM_NOT_SAMPLED
}

export interface GeoLongitudinalMonitoringProps {
  data?: GeoVisibilityTrend
  platforms: readonly GeoPlatformView[]
  loading: boolean
  error: GeoViewError | null
  days: 7 | 30 | 90
  questionScope: GeoVisibilityTrend['questionScope']
  onDaysChange: (days: 7 | 30 | 90) => void
  onScopeChange: (scope: GeoVisibilityTrend['questionScope']) => void
  onRetry?: () => void
  onViewReport: (reportId: string, sampleId?: string) => void
  onManageQuestions: () => void
  onManagePlan: () => void
}

export function GeoLongitudinalMonitoring({ data, platforms, loading, error, days, questionScope,
  onDaysChange, onScopeChange, onRetry, onViewReport, onManageQuestions, onManagePlan }: GeoLongitudinalMonitoringProps) {
  const [selectedCodes, setSelectedCodes] = useState<string[]>()
  const [selection, setSelection] = useState<string>()
  const codes = selectedCodes ?? data?.platforms.slice(0, 3).map((platform) => platform.platformCode) ?? []
  const visible = data?.platforms.filter((platform) => codes.includes(platform.platformCode)) ?? []
  const platformOf = (code: string): GeoPlatformView => platforms.find((item) => item.code === code) ?? { code, name: code, availability: 'AVAILABLE' }
  const points = visible.flatMap((platform) => platform.points.map((point) => ({ ...point, platformCode: platform.platformCode, key: pointKey(platform.platformCode, point) })))
    .sort((a, b) => Date.parse(a.observedAt) - Date.parse(b.observedAt))
  const selected = points.find((point) => point.key === selection) ?? points.at(-1)
  const selectedPlatform = data?.platforms.find((platform) => platform.platformCode === selected?.platformCode)
  const times = points.map((point) => Date.parse(point.observedAt)).filter(Number.isFinite)
  const from = data && Number.isFinite(Date.parse(data.from)) ? Date.parse(data.from) : Math.min(...times)
  const to = data && Number.isFinite(Date.parse(data.to)) ? Date.parse(data.to) : Math.max(...times)
  const x = (timestamp: string) => 60 + Math.max(0, Math.min(1, (Date.parse(timestamp) - from) / Math.max(1, to - from))) * 860
  const y = (value: number) => 250 - value * 2.15
  const dates = [...new Set(points.map((point) => point.observedAt))]
  const chartAvailable = points.some((point) => validRate(point.baselineMentionRate))
  const selectPoint = (key: string) => setSelection(key)
  return <section className="geo-longitudinal" aria-label="持续监测与问题变化">
    <Card title="持续跟踪的问题 · 平台趋势" className="geo-panel-card">
      <div className="geo-trend-toolbar">
        <Segmented aria-label="趋势时间范围" value={days} options={[{ label: '近 7 天', value: 7 }, { label: '近 30 天', value: 30 }, { label: '近 90 天', value: 90 }]} onChange={(value) => onDaysChange(value as 7 | 30 | 90)} />
        <Select aria-label="监测题型" value={questionScope} options={[{ label: '自然发现（不点名商品）', value: 'DISCOVERY' }, { label: '品牌验证（问题已点名）', value: 'BRAND_VALIDATION' }]} onChange={onScopeChange} />
      </div>
      <Typography.Paragraph type="secondary">只追踪最初确认的问题版本与采样轮次。新增探索单独展示；有效样本或识别范围不一致时断开曲线，不把缺失回答补成 0%。</Typography.Paragraph>
      {error ? <Alert type="warning" showIcon title="暂时无法加载持续监测记录" description={error.message} action={<Button onClick={onRetry}>重试</Button>} /> : loading ? <Spin aria-label="加载趋势" /> : !data || data.platforms.length === 0 ? <Empty description="这个时间范围还没有监测记录，可扩大时间范围或完成一次监测。" /> : <>
        {data.historyTruncated ? <Alert type="info" showIcon title="当前展示有限范围的历史记录" description="较早批次或部分样本超出查询范围；无法核验的比较会单独标注，不能据此判断长期趋势。" /> : null}
        <div className="geo-trend-legend" aria-label="筛选趋势平台">{data.platforms.map((platform) => {
          const style = platformStyles[platform.platformCode]
          return <button key={platform.platformCode} type="button" aria-pressed={codes.includes(platform.platformCode)}
            onClick={() => setSelectedCodes(codes.includes(platform.platformCode) ? codes.filter((code) => code !== platform.platformCode) : [...codes, platform.platformCode])}>
            <svg width="28" height="12" aria-hidden="true"><line x1="0" y1="6" x2="28" y2="6" stroke={style.color} strokeWidth="3" strokeDasharray={style.dash} /></svg>
            <GeoPlatformBadge platform={platformOf(platform.platformCode)} compact />
          </button>
        })}</div>
        {points.length > 0 ? <>
          <div className="geo-trend-chart-wrap">
            <svg className="geo-trend-chart" viewBox="0 0 960 310" role="group" aria-label="平台可见度时间图，纵轴0到100%，点可用键盘选择">
              <title>持续跟踪问题的名称提及率</title>
              <desc>横轴为真实采集时间。实线或平台线型只连接服务端确认可比的观测；空心点表示样本覆盖不完整，叉号表示未评估。下方有完整文字表格。</desc>
              {[0, 25, 50, 75, 100].map((value) => <g key={value}><line x1="60" x2="920" y1={y(value)} y2={y(value)} className="geo-trend-grid" /><text x="50" y={y(value) + 4} textAnchor="end">{value}%</text></g>)}
              {[from, from + (to - from) / 2, to].map((time, index) => <text key={index} x={60 + index * 430} y="297" textAnchor={index === 0 ? 'start' : index === 2 ? 'end' : 'middle'}>{new Date(time).toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai', month: 'numeric', day: 'numeric' })}</text>)}
              {visible.map((platform) => {
                const style = platformStyles[platform.platformCode]
                return <g key={platform.platformCode}>{platform.points.map((point, index) => {
                  const previous = platform.points[index - 1]
                  const key = pointKey(platform.platformCode, point)
                  const assessed = validRate(point.baselineMentionRate)
                  const px = x(point.observedAt); const py = assessed ? y(point.baselineMentionRate as number) : 271
                  const label = `${platformOf(platform.platformCode).name}，${formatGeoDateTime(point.observedAt)}，持续跟踪可见度${rate(point.baselineMentionRate)}，${point.baselineValidSamples}/${point.baselineExpectedSamples}条有效回答`
                  const fullCoverage = point.baselineValidSamples === point.baselineExpectedSamples
                  return <g key={key}>
                    {assessed && previous && validRate(previous.baselineMentionRate) && point.connectToPrevious ? <line data-testid="geo-comparable-line" x1={x(previous.observedAt)} x2={px} y1={y(previous.baselineMentionRate)} y2={py} stroke={style.color} strokeWidth="2.5" strokeDasharray={style.dash} /> : null}
                    <g role="button" tabIndex={0} aria-label={label} aria-pressed={selected?.key === key} className="geo-trend-point" onClick={() => selectPoint(key)} onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); selectPoint(key) } }}>
                      <title>{label}</title>
                      <circle cx={px} cy={py} r="11" fill="transparent" stroke={selected?.key === key ? style.color : 'transparent'} />
                      {!assessed ? <path d={`M ${px - 4} ${py - 4} l 8 8 m -8 0 l 8 -8`} stroke={style.color} strokeWidth="2" />
                        : style.shape === 0 ? <circle data-testid="geo-trend-value" cx={px} cy={py} r="5" stroke={style.color} strokeWidth="2" fill={fullCoverage ? style.color : 'var(--zxh-color-surface)'} />
                          : <rect data-testid="geo-trend-value" x={px - 4.5} y={py - 4.5} width="9" height="9" transform={style.shape === 2 ? `rotate(45 ${px} ${py})` : undefined} stroke={style.color} strokeWidth="2" fill={fullCoverage ? style.color : 'var(--zxh-color-surface)'} />}
                    </g>
                  </g>
                })}</g>
              })}
            </svg>
          </div>
          <Typography.Paragraph type="secondary">{dates.length === 1 ? '只有一次监测，继续监测后形成趋势。' : !chartAvailable ? '当前记录没有可评估的持续跟踪点，未取得回答不代表可见度为零。' : '横轴保留实际时间间隔；空心点表示覆盖不完整，叉号表示未评估。不能连接的点只代表当次观察。'}</Typography.Paragraph>
          {selected ? <div className="geo-trend-selection" aria-live="polite">
            <Space wrap><GeoPlatformBadge platform={platformOf(selected.platformCode)} compact /><Typography.Text strong>{selected.target?.name ?? '历史记录缺少监测名称'}</Typography.Text><Typography.Text>{formatGeoDateTime(selected.observedAt)}（北京时间）</Typography.Text></Space>
            <Typography.Paragraph>持续跟踪：{sampleRatio(selected.baselineMentionedSamples, selected.baselineValidSamples)} 条有效回答提及，名称提及率 {rate(selected.baselineMentionRate)}；有效回答覆盖 {selected.baselineValidSamples}/{selected.baselineExpectedSamples}。本轮全部问题：{selected.questionCount} 题，其中新增探索 {selected.explorationQuestionCount} 题；全部问题可见度 {rate(selected.mentionRate)}。</Typography.Paragraph>
            <Typography.Paragraph type="secondary">比较题组：{selectedPlatform?.baselineQuestionCount ?? selected.baselineQuestionCount} 个持续跟踪问题，起始监测时间 {formatGeoDateTime(selectedPlatform?.baselineObservedAt ?? undefined)}。问题版本变化后不会作为原题增长。</Typography.Paragraph>
            <Typography.Paragraph type="secondary">地区：{selected.region ?? '未限定'} · 失败 {selected.failedSamples} 条 · 缺失 {selected.missingSamples} 条。{selected.comparisonStatus === 'FIRST_OBSERVATION' ? '首次观察，尚无上期对照。' : selected.comparisonReason ? `暂不可比较：${comparisonReason(selected.comparisonReason, selected.missingPeriod)}。` : !selected.connectToPrevious ? '两期有效样本集合不同，图中原始观测点不直接连线。' : '相邻观测的题组、有效样本与采样方式一致。'}</Typography.Paragraph>
            {selected.reportId ? <Button onClick={() => onViewReport(selected.reportId as string)}>查看本次原始报告</Button> : <Typography.Text type="secondary">本次未形成报告，采集缺口已保留。</Typography.Text>}
          </div> : null}
          <details className="geo-trend-data"><summary>查看全部趋势数据（文字表格）</summary>
            <Table rowKey="key" dataSource={points} pagination={false} scroll={{ x: 850 }} columns={[
              { title: '采集时间（北京时间）', dataIndex: 'observedAt', render: (value: string) => formatGeoDateTime(value) },
              { title: '平台', dataIndex: 'platformCode', render: (code: string) => platformOf(code).name },
              { title: '持续跟踪可见度', dataIndex: 'baselineMentionRate', render: rate },
              { title: '提及 / 有效 / 计划回答', key: 'coverage', render: (_, point) => `${point.baselineMentionedSamples ?? '—'} / ${point.baselineValidSamples} / ${point.baselineExpectedSamples}` },
              { title: '本轮全部问题可见度', dataIndex: 'mentionRate', render: rate },
              { title: '比较条件', key: 'status', render: (_, point) => point.comparisonReason ? comparisonReason(point.comparisonReason, point.missingPeriod) : point.connectToPrevious ? '相邻点可比' : point.comparisonStatus === 'FIRST_OBSERVATION' ? '首次观察' : '共同有效样本另行比较' },
              { title: '操作', key: 'action', render: (_, point) => <Button type="link" onClick={() => selectPoint(point.key)}>查看问题变化</Button> },
            ]} />
          </details>
        </> : <Empty description={codes.length === 0 ? '选择至少一个有记录的平台查看趋势。' : '所选平台在这个时间范围还没有监测记录，可扩大时间范围查看历史。'} />}
      </>}
    </Card>
    {selected && !loading && !error ? <Card title="两期问题变化" className="geo-panel-card geo-table-card">
      <div className="geo-table-card__content">
        <Typography.Paragraph>{platformOf(selected.platformCode).name} · {formatGeoDateTime(selected.observedAt)}。与紧邻的上期监测对照；每行频次仅描述本轮观察，不代表长期占位或优化因果。</Typography.Paragraph>
        {selected.comparison ? <Alert type="info" showIcon title={`相邻两期共同有效样本：${rate(selected.comparison.previousMentionRate)} → ${rate(selected.comparison.currentMentionRate)}（${selected.comparison.deltaPercentagePoints > 0 ? '+' : ''}${selected.comparison.deltaPercentagePoints.toLocaleString('zh-CN', { maximumFractionDigits: 1 })} 个百分点）`}
          description={`按相邻两期共同的 ${selected.comparison.commonQuestions} 个问题、${selected.comparison.commonSamples} 条同版本有效样本比较，也包含两期都已监测的探索问题。本期新加的问题不计入环比；这组值与上方固定题组曲线分开统计。`} /> : <Typography.Paragraph type="secondary">相邻两期暂无可比较的共同有效样本；首次探索和数据不足在下方逐题标注。</Typography.Paragraph>}
      </div>
      <Table rowKey={(row) => `${row.questionId}:${row.questionVersion}`} dataSource={selected.questionChanges} pagination={{ pageSize: 10, hideOnSinglePage: true }} scroll={{ x: 820 }} locale={{ emptyText: '本次没有可展示的问题级对照。' }} columns={[
        { title: '问题', dataIndex: 'questionText', render: (text: string, row) => <><Typography.Text>{text}</Typography.Text><br /><Typography.Text type="secondary">{row.inBaseline ? '持续跟踪' : '探索问题'}</Typography.Text></> },
        { title: '观察变化', dataIndex: 'change', render: (change: keyof typeof changes, row) => <><Tag color={change === 'NEWLY_MENTIONED' ? 'green' : change === 'NO_LONGER_MENTIONED' ? 'orange' : undefined}>{changes[change]}</Tag>{row.reason ? <div>{comparisonReason(row.reason, row.missingPeriod)}</div> : null}</> },
        { title: '上期 → 本期（提及/有效）', key: 'frequency', render: (_, row) => `${sampleRatio(row.previousMentionedSamples, row.previousValidSamples)} → ${sampleRatio(row.currentMentionedSamples, row.currentValidSamples)}` },
        { title: '原始回答与引用', key: 'evidence', render: (_, row) => <Space wrap>
          {row.previousReportId && row.previousUnitKeys.length > 0 ? <Button type="link" onClick={() => onViewReport(row.previousReportId as string, row.previousUnitKeys[0])}>上期回答</Button> : <Typography.Text type="secondary">上期无有效回答</Typography.Text>}
          {row.currentReportId && row.currentUnitKeys.length > 0 ? <Button type="link" onClick={() => onViewReport(row.currentReportId as string, row.currentUnitKeys[0])}>本期回答</Button> : <Typography.Text type="secondary">本期无有效回答</Typography.Text>}
        </Space> },
      ]} />
    </Card> : null}
    {data && !loading && !error ? <Card title="问题范围的历史变化" className="geo-panel-card geo-table-card">
      <div className="geo-table-card__content">
        <Typography.Paragraph type="secondary">根据各次已保存的监测范围对照。“加入”也包含停用后重新纳入的问题，不等于首次发现；扩题不会改写旧报告或自动扩大已授权计划。</Typography.Paragraph>
      </div>
      <Table rowKey="runId" dataSource={[...data.history].reverse()} pagination={{ pageSize: 5, hideOnSinglePage: true }} scroll={{ x: 650 }} locale={{ emptyText: '暂无题库变化记录。' }} columns={[
        { title: '监测时间', dataIndex: 'observedAt', render: (value: string) => formatGeoDateTime(value) },
        { title: '本轮问题', dataIndex: 'questionCount' },
        { title: '范围变化', key: 'changes', render: (_, row) => row.changeStatus === 'FIRST_OBSERVATION' ? '首次记录' : row.changeStatus === 'UNAVAILABLE' ? '历史范围暂不可核验' : row.changeStatus === 'UNCHANGED' ? '范围未变' : `加入 ${row.addedQuestionCount} · 移出 ${row.removedQuestionCount} · 修改 ${row.changedQuestionCount}` },
        { title: '追溯', key: 'report', render: (_, row) => row.reportId ? <Button type="link" onClick={() => onViewReport(row.reportId as string)}>原始报告</Button> : '未形成报告' },
      ]} />
      <div className="geo-table-card__footer">
        <Space wrap><Button onClick={onManageQuestions}>补充或管理问题</Button><Button onClick={onManagePlan}>核对自动计划与预算</Button></Space>
        <Typography.Paragraph type="secondary" className="geo-field-help">新增问题由你确认；调整自动计划前核对范围与预计积分。这里不会自动生成问题或发起付费监测。</Typography.Paragraph>
      </div>
    </Card> : null}
  </section>
}

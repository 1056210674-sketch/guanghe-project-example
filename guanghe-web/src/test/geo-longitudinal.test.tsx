import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { GeoVisibilityTrend, GeoVisibilityTrendPoint } from '../api/geoApi'
import { GeoLongitudinalMonitoring } from '../features/geo/components/GeoLongitudinalMonitoring'
import { geoPlatformFixtures } from './fixtures/geo'

function point(overrides: Partial<GeoVisibilityTrendPoint> = {}): GeoVisibilityTrendPoint {
  return { reportId: '9001', runId: '8001', observedAt: '2026-09-20T10:00:00Z', target: { kind: 'PRODUCT', id: '1001', name: '轻行杯', aliases: [] }, region: '上海',
    questionCount: 2, baselineQuestionCount: 2, explorationQuestionCount: 0, expectedSamples: 2, validSamples: 2, mentionedSamples: 1, mentionRate: 50,
    baselineExpectedSamples: 2, baselineValidSamples: 2, baselineMentionedSamples: 1, baselineMentionRate: 50, failedSamples: 0, missingSamples: 0,
    evidenceTruncated: false, comparisonStatus: 'FIRST_OBSERVATION', comparisonReason: null, connectToPrevious: false, comparison: null, questionChanges: [], ...overrides }
}
function history(points: GeoVisibilityTrendPoint[]): GeoVisibilityTrend {
  return { topicId: '5001', days: 30, questionScope: 'DISCOVERY', methodVersion: 'explicit-name-mention-v1', from: '2026-09-01T00:00:00Z', to: '2026-09-30T00:00:00Z', historyTruncated: false, pointLimit: 100, baselineScanLimit: 100,
    platforms: [{ platformCode: 'DOUBAO', baselineReportId: '9001', baselineObservedAt: points[0]?.observedAt ?? null, baselineQuestionCount: 2, baselineSampleCount: 2, points }],
    history: points.map((item, index) => ({ reportId: item.reportId, runId: item.runId, observedAt: item.observedAt, questionCount: item.questionCount, addedQuestionCount: index ? 1 : 0, removedQuestionCount: 0, changedQuestionCount: 0, changeStatus: index ? 'CHANGED' : 'FIRST_OBSERVATION' })) }
}
function show(data: GeoVisibilityTrend) {
  const props = { data, platforms: geoPlatformFixtures, loading: false, error: null, days: 30 as const, questionScope: 'DISCOVERY' as const,
    onDaysChange: vi.fn(), onScopeChange: vi.fn(), onViewReport: vi.fn(), onManageQuestions: vi.fn(), onManagePlan: vi.fn() }
  render(<GeoLongitudinalMonitoring {...props} />)
  return props
}

describe('持续监测的真实时间与可比口径', () => {
  it.each([
    ['PREVIOUS', '上期未监测该平台'],
    ['CURRENT', '本期未监测该平台'],
    ['BOTH', '两期均未监测该平台'],
    [undefined, '两期中有一期未采样'],
  ] as const)('缺平台期间 %s 同步展示图表说明与逐题比较', (missingPeriod, expected) => {
    show(history([point({ comparisonStatus: 'NOT_COMPARABLE', comparisonReason: 'PLATFORM_NOT_SAMPLED', missingPeriod,
      questionChanges: [{ questionId: '6001', questionVersion: 'v1', questionText: '保温杯怎么选？', inBaseline: true, change: 'NOT_COMPARABLE', reason: 'PLATFORM_NOT_SAMPLED', missingPeriod,
        previousReportId: '9000', currentReportId: '9001', previousMentionedSamples: null, previousValidSamples: 0, currentMentionedSamples: 0, currentValidSamples: 1, previousUnitKeys: [], currentUnitKeys: ['6001:DOUBAO:1'] }] })]))
    expect(screen.getByText(`暂不可比较：${expected}。`, { exact: false })).toBeInTheDocument()
    expect(screen.getAllByText(expected)).toHaveLength(2)
    expect(screen.queryByText('本轮未监测该平台')).not.toBeInTheDocument()
  })
  it('单次采集只画一个点，使用0–100轴；键盘、平台图例与时间范围可操作', () => {
    const props = show(history([point()]))
    expect(screen.getByText('只有一次监测，继续监测后形成趋势。')).toBeInTheDocument()
    expect(screen.queryByTestId('geo-comparable-line')).not.toBeInTheDocument()
    expect(screen.getAllByTestId('geo-trend-value')).toHaveLength(1)
    expect(within(screen.getByRole('group', { name: /平台可见度时间图/ })).getByText('100%')).toBeInTheDocument()
    const marker = screen.getByRole('button', { name: /豆包，.*持续跟踪可见度50%/ })
    fireEvent.keyDown(marker, { key: 'Enter' }); expect(marker).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByText('近 7 天')); expect(props.onDaysChange).toHaveBeenCalledWith(7)
    fireEvent.click(screen.getByRole('button', { name: '豆包' }))
    expect(screen.queryByTestId('geo-trend-value')).not.toBeInTheDocument()
    expect(screen.getByText('选择至少一个有记录的平台查看趋势。')).toBeInTheDocument()
  })

  it('只连接服务端确认可比点；全失败保留缺口，之后的点不跨缺口连线', () => {
    show(history([point(), point({ runId: '8002', reportId: '9002', observedAt: '2026-09-21T10:00:00Z', baselineMentionRate: 100, baselineMentionedSamples: 2, comparisonStatus: 'COMPARABLE', connectToPrevious: true }),
      point({ runId: '8003', reportId: null, observedAt: '2026-09-22T10:00:00Z', validSamples: 0, baselineValidSamples: 0, baselineMentionedSamples: null, baselineMentionRate: null, mentionedSamples: null, mentionRate: null, failedSamples: 2, comparisonStatus: 'NOT_COMPARABLE', comparisonReason: 'NO_COMMON_VALID_SAMPLES' }),
      point({ runId: '8004', reportId: '9004', observedAt: '2026-09-26T10:00:00Z', comparisonStatus: 'NOT_COMPARABLE', comparisonReason: 'MISSING_SAMPLING_METADATA' })]))
    expect(screen.getAllByTestId('geo-comparable-line')).toHaveLength(1)
    expect(screen.getAllByTestId('geo-trend-value')).toHaveLength(3)
    expect(screen.getByRole('button', { name: /豆包，.*持续跟踪可见度未评估，0\/2/ })).toBeInTheDocument()
    expect(screen.getByText(/暂不可比较：历史记录缺少采样方式/)).toBeInTheDocument()
    // Exact x spacing: Sep 20→21 is shorter than Sep 21→26; no categorical spacing or invented daily points.
    const markers = screen.getAllByTestId('geo-trend-value')
    const xs = markers.map((marker) => Number(marker.getAttribute('cx')))
    expect(xs[2] - xs[1]).toBeGreaterThan(4 * (xs[1] - xs[0]))
  })

  it('共同样本比较独立呈现，新增探索不冒充增长，双期链接携带原始样本', () => {
    const props = show(history([point(), point({ runId: '8002', reportId: '9002', observedAt: '2026-09-23T10:00:00Z', questionCount: 3, explorationQuestionCount: 1,
      baselineMentionRate: 0, baselineMentionedSamples: 0, baselineValidSamples: 1, mentionRate: 33.3, comparisonStatus: 'COMPARABLE', connectToPrevious: false,
      comparison: { previousReportId: '9001', currentReportId: '9002', commonQuestions: 1, commonSamples: 1, previousMentionedSamples: 0, currentMentionedSamples: 1, previousMentionRate: 0, currentMentionRate: 100, deltaPercentagePoints: 100 },
      questionChanges: [{ questionId: '6001', questionVersion: 'v1', questionText: '通勤杯怎么选？', inBaseline: true, change: 'NEWLY_MENTIONED', reason: null, previousReportId: '9001', currentReportId: '9002', previousMentionedSamples: 0, previousValidSamples: 1, currentMentionedSamples: 1, currentValidSamples: 1, previousUnitKeys: ['6001:DOUBAO:1'], currentUnitKeys: ['6001:DOUBAO:1'] },
        { questionId: '6002', questionVersion: 'v1', questionText: '出游怎么选？', inBaseline: false, change: 'NEW_EXPLORATION', reason: null, previousReportId: null, currentReportId: '9002', previousMentionedSamples: null, previousValidSamples: 0, currentMentionedSamples: 0, currentValidSamples: 1, previousUnitKeys: [], currentUnitKeys: ['6002:DOUBAO:1'] }] })]))
    expect(screen.queryByTestId('geo-comparable-line')).not.toBeInTheDocument()
    expect(screen.getByText('相邻两期共同有效样本：0% → 100%（+100 个百分点）')).toBeInTheDocument()
    expect(screen.getByText('首次探索')).toBeInTheDocument()
    expect(screen.getByText('未评估 → 0/1')).toBeInTheDocument()
    fireEvent.click(screen.getByText('上期回答'))
    expect(props.onViewReport).toHaveBeenCalledWith('9001', '6001:DOUBAO:1')
    fireEvent.click(screen.getAllByText('本期回答')[0])
    expect(props.onViewReport).toHaveBeenCalledWith('9002', '6001:DOUBAO:1')
    fireEvent.click(screen.getByText('补充或管理问题')); expect(props.onManageQuestions).toHaveBeenCalledOnce()
  })

  it('无数据和完整采样失败都有明确状态，不绘制伪造零值', () => {
    const data = history([]); data.platforms = []
    show(data)
    expect(screen.getByText('这个时间范围还没有监测记录，可扩大时间范围或完成一次监测。')).toBeInTheDocument()
    expect(screen.queryByTestId('geo-trend-value')).not.toBeInTheDocument()
  })

  it('平台有基准但所选范围无记录时提示扩大时间，不误报为尚未选择平台', () => {
    show(history([]))
    expect(screen.getByText('所选平台在这个时间范围还没有监测记录，可扩大时间范围查看历史。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '豆包' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('只有报告但没有该问题样本时，不把链接导向其他问题的回答', () => {
    show(history([point({ questionChanges: [{ questionId: '6001', questionVersion: 'v1', questionText: '本轮未取得的回答', inBaseline: true, change: 'NOT_COMPARABLE', reason: 'NO_COMMON_VALID_SAMPLES', previousReportId: '9000', currentReportId: '9001', previousMentionedSamples: null, previousValidSamples: 0, currentMentionedSamples: null, currentValidSamples: 0, previousUnitKeys: [], currentUnitKeys: [] }] })]))
    expect(screen.getByText('上期无有效回答')).toBeInTheDocument()
    expect(screen.getByText('本期无有效回答')).toBeInTheDocument()
    expect(screen.queryByText('上期回答')).not.toBeInTheDocument()
    expect(screen.queryByText('本期回答')).not.toBeInTheDocument()
  })

  it('固定基准组无有效样本时，探索题仍可独立环比，但不伪造折线', () => {
    show(history([point({ baselineValidSamples: 0, baselineMentionedSamples: null, baselineMentionRate: null,
      comparisonStatus: 'COMPARABLE', connectToPrevious: false,
      comparison: { previousReportId: '9000', currentReportId: '9001', commonQuestions: 1, commonSamples: 1, previousMentionedSamples: 0, currentMentionedSamples: 1, previousMentionRate: 0, currentMentionRate: 100, deltaPercentagePoints: 100 },
      questionChanges: [{ questionId: '6002', questionVersion: 'v1', questionText: '已有两期的探索题', inBaseline: false, change: 'NEWLY_MENTIONED', reason: null, previousReportId: '9000', currentReportId: '9001', previousMentionedSamples: 0, previousValidSamples: 1, currentMentionedSamples: 1, currentValidSamples: 1, previousUnitKeys: ['6002:DOUBAO:1'], currentUnitKeys: ['6002:DOUBAO:1'] }] })]))
    expect(screen.getByText('相邻两期共同有效样本：0% → 100%（+100 个百分点）')).toBeInTheDocument()
    expect(screen.getByText(/本期新加的问题不计入环比/)).toBeInTheDocument()
    expect(screen.queryByTestId('geo-comparable-line')).not.toBeInTheDocument()
    expect(screen.queryByTestId('geo-trend-value')).not.toBeInTheDocument()
    expect(screen.getByText('本轮新出现提及')).toBeInTheDocument()
    expect(screen.getByText('0/1 → 1/1')).toBeInTheDocument()
  })

  it('没有共同有效样本时不把范围扩大解释成增长', () => {
    show(history([point({ comparisonStatus: 'NOT_COMPARABLE', comparisonReason: 'NO_COMMON_VALID_SAMPLES' })]))
    expect(screen.getByText('相邻两期暂无可比较的共同有效样本；首次探索和数据不足在下方逐题标注。')).toBeInTheDocument()
    expect(screen.queryByText(/相邻两期共同有效样本：/)).not.toBeInTheDocument()
  })
})

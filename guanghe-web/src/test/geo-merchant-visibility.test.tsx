import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { GeoReport } from '../api/geoApi'
import { buildGeoReportMarkdown, GeoOverview, GeoReportDetail } from '../features/geo'
import { toGeoReportDetailView } from '../pages/geo/viewAdapters'
import { geoPlatformFixtures, geoReportDto, geoRunDto } from './fixtures/geo'

function fixture(): GeoReport {
  const report = geoReportDto('geo-deterministic-v1')
  return { ...report, status: 'SUCCEEDED', visibility: {
    methodVersion: 'explicit-name-mention-v1', assessmentStatus: 'AVAILABLE', unavailableReason: null,
    target: { kind: 'PRODUCT', id: 'product-a', name: '轻行保温杯', aliases: [] },
    platforms: [{ platformCode: 'DOUBAO', expectedSamples: 4, validSamples: 2, mentionedSamples: 0, failedSamples: 1, missingSamples: 1, mentionRate: 0 }],
    samples: [{ unitKey: 'unit-1', mentioned: false, matchedTerms: [] }],
  } }
}
function view(report = fixture()) { return toGeoReportDetailView(report, geoRunDto, geoPlatformFixtures) }

describe('商家可见度与真实证据', () => {
  it('0%来自服务端有效回答提及率，不用采集率、不重算分母、不改变实际状态', () => {
    const result = view()
    expect(result.objectName).toBe('轻行保温杯')
    expect(result.status).toBe('SUCCEEDED')
    expect(result.platformPerformance[0]).toMatchObject({ primaryValue: '0%', percentage: 0, evidenceLabel: '2 条有效回答中，0 条明确提及', unavailableLabel: '计划 4 条 · 失败 1 条 · 缺失 1 条' })
    expect(result.samples[0].analysisStatus).toBe('NOT_MENTIONED')
    expect(buildGeoReportMarkdown(result)).toContain('报告状态：已完成')
    expect(buildGeoReportMarkdown(result)).toContain('| 豆包 | 0% | 0 / 2 条有效回答 | 已评估 |')
  })

  it('未冻结目标或没有有效回答均显示未评估，失败样本从不变成未提及', () => {
    for (const status of ['UNAVAILABLE_TARGET', 'NO_VALID_SAMPLES'] as const) {
      const report = fixture()
      report.visibility = { ...report.visibility, assessmentStatus: status, target: status === 'UNAVAILABLE_TARGET' ? null : report.visibility.target,
        unavailableReason: status === 'UNAVAILABLE_TARGET' ? 'MISSING_TARGET_SNAPSHOT' : 'NO_VALID_ANSWERS' }
      const result = view(report)
      expect(result.platformPerformance[0].primaryValue).toBe('未评估')
      expect(result.platformPerformance[0].percentage).toBeUndefined()
      expect(result.samples[0].analysisStatus).toBeUndefined()
      expect(result.findings).toEqual([])
    }
    const report = fixture()
    report.observations[0] = { ...report.observations[0], status: 'FAILED' }
    expect(view(report).samples[0].analysisStatus).toBeUndefined()
  })

  it('缺失unitKey变为问题、平台、轮次，不向界面和导出泄漏内部标识', () => {
    const report = fixture()
    const key = '6001:DOUBAO:2'
    report.synthesis.missingSamples = [key, '99999999999999999:OTHER:1']
    report.missingUnits = 2
    const result = view(report)
    expect(result.missingSamples).toContain('豆包 · 测试问题 · 第 2 轮（未取得回答）')
    expect(result.missingSamples).toContain('另有 2 条计划回答未返回，暂时无法判断是否提及。')
    const markdown = buildGeoReportMarkdown(result)
    expect(markdown).not.toContain(key)
    expect(markdown).not.toContain('99999999999999999')
  })

  it('默认展示成功回答；无智能总结仅一次说明；失败切换后没有虚假的引用空栏', async () => {
    const result = view()
    result.samples = [{ ...result.samples[0], id: 'failed', executionStatus: 'FAILED', analysisStatus: undefined, failureReason: '平台响应超时' }, ...result.samples]
    const { rerender } = render(<GeoReportDetail loading={false} error={null} report={result} />)
    expect(await screen.findByText('测试平台回答，包含品牌事实。')).toBeInTheDocument()
    expect(screen.getAllByText('已有回答可用于生成智能解读')).toHaveLength(1)
    expect(screen.queryByText('当前证据不足，暂不形成 AI 结论')).not.toBeInTheDocument()
    rerender(<GeoReportDetail loading={false} error={null} report={result} selectedSampleId="failed" />)
    expect(screen.getByText('本次未取得回答，不能判断是否提及')).toBeInTheDocument()
    expect(screen.queryByText(/引用来源（/)).not.toBeInTheDocument()
    expect(screen.queryByText('暂无引用证据')).not.toBeInTheDocument()
  })

  it('概览平台0%仍可打开本平台证据，不展示采集趋势', () => {
    const onViewReport = vi.fn()
    const result = view()
    render(<GeoOverview loading={false} error={null} metrics={result.metrics} trend={[]} platformPerformance={result.platformPerformance}
      recentRuns={[]} suggestions={[]} topics={[{ id: 't1', label: '杯子' }, { id: 't2', label: '咖啡' }]} selectedTopicId="t1"
      reportId={result.id} objectLabel={result.objectName} onViewReport={onViewReport} />)
    fireEvent.click(screen.getByRole('button', { name: '查看豆包的回答' }))
    expect(onViewReport).toHaveBeenCalledWith(result.id, 'DOUBAO')
    expect(screen.queryByText('最近任务采集成功率')).not.toBeInTheDocument()
    expect(screen.getByText('0%')).toBeInTheDocument()
  })
})

import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { geoMerchantNarrative } from '../features/geo/merchantNarrative'
import { GeoReportDetail } from '../features/geo/components/GeoReportDetail'
import { buildGeoReportMarkdown } from '../features/geo/reportTransfer'
import { toGeoReportDetailView } from '../pages/geo/viewAdapters'
import { geoReportDto, geoRunDto } from './fixtures/geo'

function reportFixture() {
  const report = geoReportDto('geo-synthesis-v1', {
    summary: 'PRODUCT「轻行保温杯-20260909020516」计划6个样本，问题 90990880852279296 与 90990880856473601 因 GEO_PROVIDER_TIMEOUT 失败，targetMentioned 为 null。Java 按 explicit-name-mention-v1 核验的结果均为 false。recommendations 与 contentBlueprint 留空。omittedCitationCount=8。competitorSetVersion 为 null、competitors 为空。售价 6001 元，型号 X-6001；引用 https://example.com/6001?q=false。',
    recommendations: [],
  })
  report.questions = [
    { id: '90990880852279296', category: 'BRAND_DISCOVERY', text: '适合通勤的保温杯？' },
    { id: '90990880856473601', category: 'BRAND_DISCOVERY', text: '杯子怎么选？' },
  ]
  report.visibility = { ...report.visibility, assessmentStatus: 'AVAILABLE', unavailableReason: null,
    target: { kind: 'PRODUCT', id: '6001', name: '轻行保温杯-20260909020516', aliases: ['X-6001'] },
    platforms: [{ platformCode: 'DOUBAO', expectedSamples: 6, validSamples: 4, mentionedSamples: 0, failedSamples: 2, missingSamples: 0, mentionRate: 0 }],
  }
  report.observations[0].answerText = '原始回答：Java targetMentioned=false，90990880852279296。'
  return report
}

describe('商家叙述投影', () => {
  it('只映射已知内部上下文，保留商品数字后缀、价格、型号、URL和原始回答', () => {
    const report = reportFixture()
    const before = JSON.stringify(report)
    const narrative = geoMerchantNarrative(report)
    expect(narrative.factualSummary).toContain('4 条有效回答中，尚未观察到')
    expect(narrative.summary).not.toMatch(/Java|targetMentioned|false(?!。)|90990880852279296|GEO_PROVIDER_TIMEOUT|contentBlueprint|omittedCitationCount|competitorSetVersion/)
    for (const text of ['轻行保温杯-20260909020516', '6001 元', 'X-6001', 'https://example.com/6001?q=false', '适合通勤的保温杯？', '杯子怎么选？']) expect(narrative.summary).toContain(text)
    expect(JSON.stringify(report)).toBe(before)
    const view = toGeoReportDetailView(report, geoRunDto, [])
    expect(view.samples[0].originalAnswer).toBe(report.observations[0].answerText)
    const markdown = buildGeoReportMarkdown(view)
    expect(markdown).toContain(narrative.factualSummary)
    expect(markdown).toContain(report.observations[0].answerText)
  })

  it('零提及提供资料与监测入口，诊断建议不会变成文章或图片按钮', () => {
    const report = reportFixture()
    report.synthesis.recommendations = [{ id: '9001:recommendation:2', priority: 'P1', title: '补齐长尾问题', rationale: '把问题纳入下一轮监测。' }]
    const review = vi.fn(); const retry = vi.fn()
    render(<GeoReportDetail loading={false} error={null} report={toGeoReportDetailView(report, geoRunDto, [])}
      onReviewProduct={review} onPrepareRetrySample={retry} onPrepareArticleDraft={vi.fn()} onPrepareImageDraft={vi.fn()} />)
    expect(screen.queryByRole('button', { name: '准备文章草稿' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '构思图片方案' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '核对商品资料' }))
    fireEvent.click(screen.getByRole('button', { name: /重新配置监测/ }))
    expect(review).toHaveBeenCalledOnce(); expect(retry).toHaveBeenCalledOnce()
  })

  it('全失败不展示零可见度；规则统计不会呈现为AI智能解读', () => {
    const report = reportFixture()
    report.methodVersion = 'geo-deterministic-v1'; report.succeededUnits = 0
    report.visibility.assessmentStatus = 'NO_VALID_SAMPLES'
    const narrative = geoMerchantNarrative(report)
    expect(narrative.factualSummary).toContain('暂时无法判断')
    expect(narrative.summary).toContain('尚未形成智能解读')
    expect(narrative.recommendations).toEqual([])
    expect(narrative.findings).toEqual([])
  })

  it('即使可评估标记存在，分母为零仍明确无法判断而非零提及', () => {
    const report = reportFixture()
    report.visibility.platforms[0].validSamples = 0
    expect(geoMerchantNarrative(report).factualSummary).toContain('未取得有效回答，暂时无法判断')
    expect(geoMerchantNarrative(report).factualSummary).not.toContain('尚未观察到')
  })
})

import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  buildGeoEvidenceSummary,
  buildGeoReportMarkdown,
  GeoReportDetail,
  GeoReportLibrary,
  geoReportDownloadFilename,
} from '../features/geo'
import type { GeoReportDetailView } from '../features/geo'
import { geoPartialReportFixture } from './fixtures/geo'

const reportWithEvidence: GeoReportDetailView = {
  ...geoPartialReportFixture,
  monitoredAt: '2026/09/20 10:30',
  findings: ['品牌在测试平台被稳定提及'],
  coverageGaps: ['缺少场景词覆盖'],
  suggestions: [
    {
      id: 'suggestion-1',
      title: '补充场景内容',
      description: '围绕通勤场景发布可核验内容。',
      priority: 'HIGH',
    },
  ],
  samples: [
    {
      ...geoPartialReportFixture.samples[0],
      startedAt: '2026-09-20 10:30:00',
      provider: 'test-provider',
      model: 'test-model-v1',
      latencyMs: 1250,
      providerCallCount: 2,
      usage: {
        inputTokens: 100,
        outputTokens: 200,
        searchRequests: 1,
        monetaryCost: '0.0123',
        currency: 'CNY',
      },
      originalAnswer: '测试平台回答，包含品牌事实。',
      citations: [
        {
          id: 'citation-1',
          title: '测试引用来源',
          url: 'https://example.com/evidence',
          site: 'example.com',
          excerpt: '用于核验的测试摘录',
          capturedAt: '2026-09-20 10:31',
        },
      ],
    },
    geoPartialReportFixture.samples[1],
  ],
}

describe('GEO 报告带出能力', () => {
  it('带报告问 AI 只触发页面导航意图，不触发创作或导出', () => {
    const onAskAi = vi.fn()
    const onPrepareArticleDraft = vi.fn()
    const onExportReportIntent = vi.fn()
    render(<GeoReportDetail loading={false} error={null} report={reportWithEvidence}
      onAskAi={onAskAi} onPrepareArticleDraft={onPrepareArticleDraft} onExportReportIntent={onExportReportIntent} />)
    fireEvent.click(screen.getByRole('button', { name: '带这份报告问 AI' }))
    expect(onAskAi).toHaveBeenCalledOnce()
    expect(onPrepareArticleDraft).not.toHaveBeenCalled()
    expect(onExportReportIntent).not.toHaveBeenCalled()
  })

  it('降级报告导出只能带出证据，不保留缓存中的分析结论或创作建议', () => {
    const markdown = buildGeoReportMarkdown({ ...reportWithEvidence, methodVersion: 'geo-deterministic-v1' })
    expect(markdown).toContain('尚未形成智能解读')
    expect(markdown).toContain('测试平台回答，包含品牌事实。')
    expect(markdown).not.toContain('补充场景内容')
    expect(markdown).not.toContain('品牌在测试平台被稳定提及')
    expect(markdown).not.toContain('本次未识别到额外覆盖缺口')
  })
  it('完整报告保留指标、建议、成功样本、失败原因与引用证据', () => {
    const markdown = buildGeoReportMarkdown(reportWithEvidence)

    expect(markdown).toContain('# 测试部分完成报告')
    expect(markdown).toContain('报告状态：部分完成')
    expect(markdown).toContain('测试品牌可见度')
    expect(markdown).toContain('[高优先级] 补充场景内容')
    expect(markdown).toContain('测试平台回答，包含品牌事实。')
    expect(markdown).toContain('执行提供方：test-provider')
    expect(markdown).toContain('模型：test-model-v1')
    expect(markdown).toContain('输入 100 tokens / 输出 200 tokens / 搜索 1 次')
    expect(markdown).toContain('上游成本：0.0123 CNY')
    expect(markdown).toContain('测试平台未返回有效回答')
    expect(markdown).toContain('https://example.com/evidence')
    expect(markdown).toContain('失败和缺失回答单独保留，不计作未提及')
  })

  it('证据摘要只复制当前样本并明确监测范围', () => {
    const sample = reportWithEvidence.samples[0]
    const summary = buildGeoEvidenceSummary(reportWithEvidence, sample)

    expect(summary).toContain('问题：测试成功问题')
    expect(summary).toContain('平台：豆包')
    expect(summary).toContain('测试引用来源')
    expect(summary).not.toContain('测试失败问题')
    expect(summary).toContain('仅代表本报告的监测时间、平台范围和样本范围')
  })

  it('导出文件名移除 Windows 非法字符并保留 Markdown 扩展名', () => {
    expect(geoReportDownloadFilename(reportWithEvidence)).toBe(
      '测试监测对象-GEO报告-2026-09-20 10-30.md',
    )
  })

  it('报告详情将导出和证据复制动作交给页面容器', () => {
    const onExportReportIntent = vi.fn()
    const onCopyEvidenceIntent = vi.fn()

    render(
      <GeoReportDetail
        loading={false}
        error={null}
        report={reportWithEvidence}
        selectedSampleId={reportWithEvidence.samples[0].id}
        onExportReportIntent={onExportReportIntent}
        onCopyEvidenceIntent={onCopyEvidenceIntent}
      />,
    )

    fireEvent.click(
      screen.getByRole('button', { name: /导出完整报告/ }),
    )
    fireEvent.click(
      screen.getByRole('button', { name: /复制证据摘要/ }),
    )

    expect(onExportReportIntent).toHaveBeenCalledWith(reportWithEvidence.id)
    expect(onCopyEvidenceIntent).toHaveBeenCalledWith(
      reportWithEvidence.samples[0].id,
    )
  })

  it('报告建议可交给页面容器准备文章草稿', () => {
    const onPrepareArticleDraft = vi.fn()

    render(
      <GeoReportDetail
        loading={false}
        error={null}
        report={reportWithEvidence}
        selectedSampleId={reportWithEvidence.samples[0].id}
        onPrepareArticleDraft={onPrepareArticleDraft}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: /准备文章草稿/ }))

    expect(onPrepareArticleDraft).toHaveBeenCalledWith(
      reportWithEvidence.id,
      'suggestion-1',
    )
  })

  it('报告建议的图片入口只提交意图，不在报告详情直接创建付费草稿', () => {
    const onPrepareImageDraft = vi.fn()
    render(<GeoReportDetail loading={false} error={null} report={reportWithEvidence}
      selectedSampleId={reportWithEvidence.samples[0].id} onPrepareImageDraft={onPrepareImageDraft} />)
    fireEvent.click(screen.getByRole('button', { name: /构思图片方案/ }))
    expect(onPrepareImageDraft).toHaveBeenCalledWith(reportWithEvidence.id, 'suggestion-1')
  })

  it('报告库允许直接导出并展示当前导出项的忙碌状态', () => {
    const onExportReportIntent = vi.fn()
    const reportLibrary = (exportingReportId?: string) => (
      <GeoReportLibrary
        loading={false}
        error={null}
        reports={[
          {
            id: reportWithEvidence.id,
            name: reportWithEvidence.name,
            objectName: reportWithEvidence.objectName,
            typeLabel: 'GEO 监测',
            platformCodes: ['DOUBAO'],
            primaryMetric: '2 个样本',
            generatedAt: reportWithEvidence.monitoredAt,
            status: reportWithEvidence.status,
            archived: false,
          },
        ]}
        archivedReports={[]}
        platforms={[
          { code: 'DOUBAO', name: '豆包', availability: 'AVAILABLE' },
        ]}
        objectOptions={[]}
        typeOptions={[]}
        filters={{}}
        archiveOpen={false}
        exportingReportId={exportingReportId}
        onExportReportIntent={onExportReportIntent}
      />
    )

    const { rerender } = render(reportLibrary(reportWithEvidence.id))

    expect(screen.getByRole('button', { name: /导出/ })).toHaveClass(
      'ant-btn-loading',
    )

    rerender(reportLibrary())
    fireEvent.click(screen.getByRole('button', { name: /导出/ }))
    expect(onExportReportIntent).toHaveBeenCalledWith(reportWithEvidence.id)
  })
})

/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { render, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { GeoReportDetail } from '../features/geo/components/GeoReportDetail'
import { MarkdownMessageContent } from '../features/chat/components/MarkdownMessageContent'
import { toGeoReportDetailView } from '../pages/geo/viewAdapters'
import { geoReportDto, geoRunDto } from './fixtures/geo'

const longSummary = '本次监测对象为轻行保温杯，按已保存的有效回答核验名称提及，失败样本不能计入未提及。'.repeat(9)

describe('GEO 智能解读阅读布局', () => {
  it('摘要独立展示、保留全文分段，观察与限制放在下方而不是挤压正文', async () => {
    const report = geoReportDto('geo-synthesis-v1', { summary: longSummary })
    const original = JSON.stringify(report)
    const view = toGeoReportDetailView(report, geoRunDto, [])
    const { container } = render(<GeoReportDetail loading={false} error={null} report={view} />)
    await waitFor(() => expect(container.querySelectorAll('.geo-report-narrative .chat-message-markdown > p').length).toBeGreaterThan(1))
    const narrative = container.querySelector('.geo-report-narrative-card')!
    const paragraphs = container.querySelectorAll('.geo-report-narrative .chat-message-markdown > p')
    expect(Array.from(paragraphs, (paragraph) => paragraph.textContent).join('')).toBe(view.summary)
    expect(narrative.parentElement).toHaveClass('geo-report-group')
    expect(narrative.nextElementSibling).toHaveClass('geo-report-observations')
    expect(JSON.stringify(report)).toBe(original)
  })

  it('原始回答和 AI 对话不自动分段，避免改变证据及其他入口的默认呈现', async () => {
    const report = geoReportDto('geo-synthesis-v1', { summary: longSummary })
    const view = toGeoReportDetailView(report, geoRunDto, [])
    const sample = view.samples.find((item) => item.executionStatus === 'SUCCEEDED')!
    const { container } = render(<>
      <GeoReportDetail loading={false} error={null} report={{ ...view, samples: [{ ...sample, originalAnswer: longSummary }] }} />
      <div data-testid="unchanged-chat"><MarkdownMessageContent content={longSummary} /></div>
    </>)
    await waitFor(() => expect(container.querySelector('.geo-answer-card .chat-message-markdown')).toBeInTheDocument())
    expect(container.querySelectorAll('.geo-answer-card .chat-message-markdown > p')).toHaveLength(1)
    expect(container.querySelector('.geo-answer-card .chat-message-markdown')?.textContent).toBe(longSummary)
    expect(container.querySelectorAll('[data-testid="unchanged-chat"] p')).toHaveLength(1)
  })

  it('含 Markdown 的摘要保留标题、列表、表格和安全链接，不执行 HTML 或加载远程图片', async () => {
    const content = '## 观察\n\n**谨慎解读**，仅描述本批结果。\n\n- 目标未提及\n- 样本不足\n\n| 平台 | 有效 |\n| --- | --- |\n| 豆包 | 1 |\n\n[来源](https://example.com/evidence)\n\n<img src="https://example.com/tracker" onerror="alert(1)">\n\n![远程图](https://example.com/image.png)\n\n[危险](javascript:alert(1))'
    const view = toGeoReportDetailView(geoReportDto('geo-synthesis-v1', { summary: content }), geoRunDto, [])
    const { container } = render(<GeoReportDetail loading={false} error={null} report={view} />)
    await waitFor(() => expect(container.querySelector('.geo-report-narrative h2')).toHaveTextContent('观察'))
    const narrative = container.querySelector('.geo-report-narrative')!
    expect(narrative.querySelectorAll('ul > li')).toHaveLength(2)
    expect(narrative.querySelector('strong')).toHaveTextContent('谨慎解读')
    expect(narrative.querySelector('table')).toBeInTheDocument()
    expect(narrative.querySelector('a[href="https://example.com/evidence"]')).toHaveAttribute('rel', 'noopener noreferrer')
    expect(narrative.querySelector('a[href^="javascript:"]')).toBeNull()
    expect(narrative.querySelector('img')).toBeNull()
  })
})

// Opt-in actual ordinary-user API data. No fixture data or credentials enter the bundle.
const livePath = process.env.GEO_REPORT_LAYOUT_SAMPLE
it.skipIf(!livePath)('真实已保存报告经实际适配器与页面组件渲染，全文不丢失且不是单段文字墙', async () => {
  const input = JSON.parse(readFileSync(livePath!, 'utf8'))
  const before = JSON.stringify(input)
  const view = toGeoReportDetailView(input.report, input.run, [])
  const { container } = render(<GeoReportDetail loading={false} error={null} report={view} />)
  await waitFor(() => expect(container.querySelectorAll('.geo-report-narrative .chat-message-markdown > p').length).toBeGreaterThan(2))
  const paragraphs = container.querySelectorAll('.geo-report-narrative .chat-message-markdown > p')
  expect(Array.from(paragraphs, (paragraph) => paragraph.textContent).join('')).toBe(view.summary)
  expect(container.querySelectorAll('.geo-report-narrative button')).toHaveLength(0)
  expect(container.querySelectorAll('.geo-report-narrative [hidden]')).toHaveLength(0)
  expect(JSON.stringify(input)).toBe(before)
  console.info(JSON.stringify({ reportId: view.id, renderedParagraphs: paragraphs.length,
    summaryCharacters: view.summary?.length, fullTextPreserved: true, environment: 'jsdom-not-browser-visual' }))
})

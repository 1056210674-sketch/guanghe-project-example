import { fireEvent, render, screen, within } from '@testing-library/react'
import { ConfigProvider } from 'antd'
import type { ComponentProps, ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { GeoCreativePlanResponse } from '../api/geoApi'
import type { KnowledgeDocumentDetail } from '../api/knowledgeApi'
import { KnowledgeWorkspace } from '../features/agent-platform/KnowledgeWorkspace'
import { GeoVisualPlanPanel } from '../features/creative-modes/GeoVisualPlanPanel'

const renderPanel = (content: ReactNode) => render(<ConfigProvider theme={{ token: { motion: false } }}>{content}</ConfigProvider>)
const document: KnowledgeDocumentDetail = {
  id: '501', projectId: '201', name: '品牌资料', sourceType: 'LINK_PASTED_TEXT', status: 'READY',
  version: 1, sha256: 'a'.repeat(64), contentType: 'text/plain', sizeBytes: 100,
  paragraphCount: 1, pageCount: null, sourceUrl: 'https://example.com/about', failureCode: null,
  createdAt: '2026-10-14T01:00:00Z', updatedAt: '2026-10-14T01:00:00Z',
  paragraphs: [{ index: 0, page: null, text: '品牌提供门店现磨咖啡。' }], readLimitApplied: true,
}
const knowledgeProps = (detail = document): ComponentProps<typeof KnowledgeWorkspace> => ({
  projectName: '咖啡品牌', projectAvailable: true, loading: false, documents: [detail], detail,
  detailLoading: false, memories: [], personalMemoriesLoading: false, projectMemoriesLoading: false,
  busy: false, onReload: vi.fn(), onSelectDocument: vi.fn(), onCreateDocument: vi.fn(),
  onEditDocument: vi.fn(), onDeleteDocument: vi.fn(),
})
const plan: GeoCreativePlanResponse = {
  reportId: '301', recommendationId: null,
  plan: {
    version: 'geo-visual-plan-v1', taskType: 'IMAGE', reportId: '301', recommendationId: null,
    opportunity: '展示通勤场景', audience: '通勤人群', message: '便于随身携带', scene: '早晨的地铁入口',
    visualFocus: '杯身与手部特写', shotBeats: [], evidenceKeys: ['sample-1'], proof: 'test-proof',
    limitations: ['方案仅用于参考，请再次核实商品资料。'],
  },
  evidence: [{ key: 'sample-1', platformCode: 'DOUBAO', questionText: '通勤带什么咖啡方便？', answerExcerpt: '便携杯装咖啡适合通勤携带。' }],
}
const creativeProps = (): ComponentProps<typeof GeoVisualPlanPanel> => ({
  taskType: 'IMAGE', userDirection: '', onDirectionChange: vi.fn(), onGenerate: vi.fn(),
  canGenerate: true, loading: false, plan,
})

describe('GEO 辅助面板减少资料声明噪声', () => {
  it('节选使用短标签并保留来源和正文，不再显示声明告警', () => {
    renderPanel(<KnowledgeWorkspace {...knowledgeProps()} />)
    expect(screen.getByText('内容节选')).toBeInTheDocument()
    expect(screen.getByText('摘录来源：https://example.com/about')).toBeInTheDocument()
    expect(screen.getByText('品牌提供门店现磨咖啡。')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/不代表已|有界节选/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /修订文本/ })).toBeDisabled()
  })

  it('保留真正的解析失败原因和修订入口', () => {
    const props = knowledgeProps({ ...document, status: 'FAILED', paragraphs: [], readLimitApplied: false, failureCode: 'KNOWLEDGE_PDF_NO_EXTRACTABLE_TEXT' })
    renderPanel(<KnowledgeWorkspace {...props} />)
    const alert = screen.getByRole('alert')
    expect(within(alert).getByText('资料解析失败')).toBeInTheDocument()
    expect(alert).toHaveTextContent('扫描 PDF 暂不支持')
    fireEvent.click(screen.getByRole('button', { name: /修订文本/ }))
    expect(props.onEditDocument).toHaveBeenCalledWith(props.detail)
  })

  it('创意方案展示实际方案和监测依据，不展示泛泛边界声明', () => {
    const props = creativeProps()
    renderPanel(<GeoVisualPlanPanel {...props} />)
    expect(screen.getByText('展示通勤场景')).toBeInTheDocument()
    expect(screen.getByText('杯身与手部特写')).toBeInTheDocument()
    expect(screen.getByText('便携杯装咖啡适合通勤携带。')).toBeInTheDocument()
    expect(screen.queryByText('证据范围与创作边界')).not.toBeInTheDocument()
    expect(screen.queryByText(plan.plan.limitations[0])).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重新生成创意方案' }))
    expect(props.onGenerate).toHaveBeenCalledOnce()
  })

  it('仍显示真实生成错误和缺少监测摘要的状态', () => {
    renderPanel(<GeoVisualPlanPanel {...creativeProps()} error="创意方案生成失败，请重试。" plan={{ ...plan, evidence: [] }} />)
    expect(screen.getByRole('alert')).toHaveTextContent('创意方案生成失败，请重试。')
    expect(screen.getByText('暂无监测回答摘要。')).toBeInTheDocument()
  })

  it('视频镜头节奏不受文案清理影响', () => {
    renderPanel(<GeoVisualPlanPanel {...creativeProps()} taskType="VIDEO" plan={{ ...plan, plan: { ...plan.plan, taskType: 'VIDEO', shotBeats: ['展示携杯出门', '镜头跟随进入地铁'] } }} />)
    expect(screen.getByText('展示携杯出门')).toBeInTheDocument()
    expect(screen.getByText('镜头跟随进入地铁')).toBeInTheDocument()
    expect(screen.queryByText('画面重点')).not.toBeInTheDocument()
  })
})

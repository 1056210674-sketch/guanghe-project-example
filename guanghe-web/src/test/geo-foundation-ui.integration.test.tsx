import { fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import {
  GeoCompetitorManager,
  GeoOverview,
  GeoQuestionLibrary,
} from '../features/geo'
import type { GeoViewError } from '../features/geo'

const topicOption = { id: 'topic-1', label: '测试专题' }

function buttonByLabel(label: string) {
  const button = document.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)
  expect(button).not.toBeNull()
  return button as HTMLButtonElement
}

describe('GEO Foundation 编辑草稿', () => {
  it('概览空态的新建监测入口触发真实导航回调', () => {
    const onCreateMonitoring = vi.fn()
    render(
      <GeoOverview
        loading={false}
        error={null}
        metrics={[]}
        trend={[]}
        platformPerformance={[]}
        recentRuns={[]}
        suggestions={[]}
        onCreateMonitoring={onCreateMonitoring}
      />,
    )

    const button = screen.getByRole('button', { name: /新建监测/ })
    expect(button).toBeEnabled()
    fireEvent.click(button)
    expect(onCreateMonitoring).toHaveBeenCalledTimes(1)
  })

  it('关键词按用户调整后的顺序整体保存', () => {
    const onSave = vi.fn()
    render(
      <GeoQuestionLibrary
        loading={false}
        error={null}
        topics={[topicOption]}
        selectedTopicId={topicOption.id}
        topicName={topicOption.label}
        keywords={[
          { id: 'keyword-1', type: 'CORE', text: '核心词' },
          { id: 'keyword-2', type: 'SCENE', text: '场景词' },
        ]}
        questions={[]}
        onSaveKeywords={onSave}
      />,
    )

    fireEvent.click(buttonByLabel('下移关键词：核心词'))
    fireEvent.click(buttonByLabel('整体保存关键词'))

    expect(onSave).toHaveBeenCalledWith([
      { id: 'keyword-2', type: 'SCENE', text: '场景词' },
      { id: 'keyword-1', type: 'CORE', text: '核心词' },
    ])
  })

  it('关键词保存失败时保留本地草稿，隐藏后端 traceId', () => {
    function Harness() {
      const [error, setError] = useState<GeoViewError | null>(null)
      return (
        <GeoQuestionLibrary
          loading={false}
          error={null}
          topics={[topicOption]}
          selectedTopicId={topicOption.id}
          keywords={[]}
          questions={[]}
          keywordsError={error}
          onSaveKeywords={() => setError({
            message: '专题版本冲突',
            traceId: '0123456789abcdef0123456789abcdef',
          })}
        />
      )
    }

    render(<Harness />)
    fireEvent.change(screen.getByPlaceholderText('输入关键词'), { target: { value: '保留的草稿' } })
    fireEvent.click(buttonByLabel('加入关键词草稿'))
    fireEvent.click(buttonByLabel('整体保存关键词'))

    expect(screen.getByText('保留的草稿')).toBeInTheDocument()
    expect(screen.getByText('专题版本冲突')).toBeInTheDocument()
    expect(screen.queryByText(/0123456789abcdef/)).not.toBeInTheDocument()
  })

  it('问题顺序、启停与内容仅通过整体保存回调提交', () => {
    const onSave = vi.fn()
    render(
      <GeoQuestionLibrary
        loading={false}
        error={null}
        topics={[topicOption]}
        selectedTopicId={topicOption.id}
        keywords={[]}
        questions={[
          { id: 'question-1', text: '问题甲', enabled: true },
          { id: 'question-2', text: '问题乙', enabled: true },
        ]}
        onSaveQuestions={onSave}
      />,
    )

    fireEvent.click(buttonByLabel('下移问题：问题甲'))
    fireEvent.click(buttonByLabel('停用问题：问题甲'))
    fireEvent.click(buttonByLabel('整体保存问题'))

    expect(onSave).toHaveBeenCalledWith([
      { id: 'question-2', text: '问题乙', enabled: true },
      { id: 'question-1', text: '问题甲', enabled: false },
    ])
    expect(screen.getAllByText('手工新增').length).toBeGreaterThan(0)
    expect(screen.queryByText('AI生成问题')).not.toBeInTheDocument()
  })
})

describe('GEO 正式竞品集合', () => {
  it('严格多选并按用户选择顺序整体保存', async () => {
    const onSave = vi.fn()
    render(
      <GeoCompetitorManager
        loading={false}
        error={null}
        topics={[topicOption]}
        selectedTopicId={topicOption.id}
        topicName={topicOption.label}
        currentVersion={3}
        competitors={[
          { id: 'competitor-1', name: '竞品甲', aliases: ['甲别名'], competitorType: 'DIRECT' },
          { id: 'competitor-2', name: '竞品乙', aliases: ['乙别名'], competitorType: 'INDIRECT' },
        ]}
        selectedCompetitorIds={[]}
        onSave={onSave}
      />,
    )

    const secondRow = screen.getByText('竞品乙').closest('tr')
    const firstRow = screen.getByText('竞品甲').closest('tr')
    expect(secondRow).not.toBeNull()
    expect(firstRow).not.toBeNull()
    fireEvent.click(within(secondRow as HTMLElement).getByRole('checkbox'))
    fireEvent.click(within(firstRow as HTMLElement).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '整体保存竞品集合' }))

    expect(onSave).toHaveBeenCalledWith(['competitor-2', 'competitor-1'])
    expect(screen.getByText('集合版本：v3')).toBeInTheDocument()
    expect(screen.getByText('别名：乙别名')).toBeInTheDocument()
  })

  it('没有专题或候选竞品时展示真实空态', () => {
    const { rerender } = render(
      <GeoCompetitorManager
        loading={false}
        error={null}
        topics={[]}
        competitors={[]}
        selectedCompetitorIds={[]}
      />,
    )
    expect(screen.getByText('还没有 GEO 专题')).toBeInTheDocument()

    rerender(
      <GeoCompetitorManager
        loading={false}
        error={null}
        topics={[topicOption]}
        selectedTopicId={topicOption.id}
        competitors={[]}
        selectedCompetitorIds={[]}
      />,
    )
    expect(screen.getByText('当前经营空间还没有可选竞品')).toBeInTheDocument()
  })
})

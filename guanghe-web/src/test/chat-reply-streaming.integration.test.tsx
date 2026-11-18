import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { ConversationMessage } from '../api/conversationApi'
import { ChatMessageStream, type ChatMessageStreamProps } from '../features/chat/components/ChatMessageStream'
import { toChatMessageView } from '../features/chat/live/chatAdapters'
import type { ParsedConversationEvent } from '../features/chat/live/conversationStream'
import { applyConversationEventToTimelines, type ExecutionTimelineMap } from '../features/chat/live/executionTimeline'
import { placeExecutionTimelines } from '../features/chat/live/messageTimelinePlacement'
import { appendReplyDelta, pruneReplyDeltas, readReplyDelta, withStreamedReplyContent, type StreamedReplies } from '../features/chat/live/streamedReply'

const callbacks: Omit<ChatMessageStreamProps, 'messages'> = {
  onSelectClarificationOption: vi.fn(), onEditPaidTaskConfirmation: vi.fn(), onConfirmPaidTaskConfirmation: vi.fn(),
  onOpenRecharge: vi.fn(), onPrepareReestimate: vi.fn(), onOpenPaidTaskReferenceAsset: vi.fn(), onAddPaidTaskReferenceAsset: vi.fn(),
  onViewTask: vi.fn(), onPreviewArticle: vi.fn(), onContinueEditingArticle: vi.fn(), onSaveArticleToAssets: vi.fn(),
  onPreviewGeneratedImage: vi.fn(), onDownloadGeneratedImage: vi.fn(), onSaveGeneratedImageToAssets: vi.fn(),
  onSelectGeneratedImage: vi.fn(), onTaskOutcomeAction: vi.fn(),
}
function event(sequence: number, name: ParsedConversationEvent['event'], payload: Record<string, unknown>): ParsedConversationEvent {
  return { id: String(sequence), event: name, data: { eventId: String(sequence), sequence,
    conversationId: 'c1', messageId: 'a1', taskId: null, occurredAt: `2026-10-10T08:00:${String(sequence).padStart(2, '0')}Z`,
    payload: { messageId: 'a1', runId: 'r1', ...payload } } }
}
function progress(sequence: number, stageCode: string, status = 'RUNNING', ordinal = sequence) {
  return event(sequence, 'agent.progress', { stageCode, status, ordinal, elapsedMs: sequence * 100,
    current: null, total: null, reasoning_content: 'PRIVATE_REASONING', displayText: 'PRIVATE_PROMPT' })
}
function snapshot(text: string, status: ConversationMessage['status'] = 'STREAMING'): ConversationMessage[] {
  return [{ id: 'a1', conversationId: 'c1', role: 'ASSISTANT', contentType: 'TEXT', content: { text },
    status, replyToMessageId: null, runId: 'r1', sequence: 1, attachmentAssetIds: [],
    createdAt: '2026-10-10T08:00:00Z', updatedAt: '2026-10-10T08:00:00Z' }]
}
function scenario(initial: ConversationMessage[] = []) {
  let timelines: ExecutionTimelineMap = {}
  let streamed: StreamedReplies = {}
  let persisted = initial
  let watermark = 0
  const views = () => withStreamedReplyContent(placeExecutionTimelines(
    persisted.map((message) => ({ ...toChatMessageView(message), executionTimeline: timelines[message.id] })),
    persisted, timelines, []), persisted, streamed, watermark)
  const tree = () => <MemoryRouter><ChatMessageStream {...callbacks} messages={views()} /></MemoryRouter>
  const mounted = render(tree())
  return {
    send(next: ParsedConversationEvent) {
      const delta = readReplyDelta(next)
      if (delta) streamed = appendReplyDelta(streamed, 'a1', delta)
      timelines = applyConversationEventToTimelines(timelines, next)
      mounted.rerender(tree())
    },
    refresh(messages: ConversationMessage[], sequence: number) {
      persisted = messages
      watermark = sequence
      streamed = pruneReplyDeltas(streamed, sequence)
      mounted.rerender(tree())
    },
    container: mounted.container,
  }
}

describe('聊天真实阶段与正文渐进输出', () => {
  it('新回复快于快照也立即显示真实Markdown片段；过程可展开且不泄露原始思考', () => {
    const run = scenario()
    run.send(progress(1, 'PLANNING_WORKFLOW'))
    expect(screen.queryByText('正文输出中')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /展开执行动态/ }))
    expect(screen.getByText('思考与处理过程')).toBeVisible()
    expect(screen.getByRole('listitem')).toHaveTextContent('正在思考与分析')
    run.send(progress(2, 'PLANNING_WORKFLOW', 'SUCCEEDED', 1))
    run.send(progress(3, 'COMPOSING_RESPONSE'))
    run.send(event(4, 'message.delta', { delta: '## 优化方案\n\n先完善' }))
    expect(screen.getByRole('heading', { name: '优化方案' })).toBeVisible()
    expect(screen.getByText('先完善')).toBeVisible()
    expect(screen.getByText('正文输出中')).toBeVisible()
    expect(screen.getByRole('button', { name: /收起执行动态/ })).toHaveTextContent('正在输出回复')
    run.send(event(5, 'message.delta', { delta: '**品牌档案**。' }))
    expect(screen.getByText('品牌档案').tagName).toBe('STRONG')
    expect(screen.getAllByText('正文输出中')).toHaveLength(1)
    expect(run.container.textContent).not.toMatch(/PRIVATE_REASONING|PRIVATE_PROMPT|reasoning_content/)
    run.send(event(6, 'message.completed', { status: 'COMPLETED', businessOutcome: { schemaVersion: 1, status: 'ANSWERED' } }))
    expect(screen.queryByText('正文输出中')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '优化方案' })).toBeVisible()
    expect(screen.getByRole('button', { name: /收起执行动态/ })).toHaveTextContent('本次回复已完成')
  })

  it('空替换清除临时说明；延迟快照不能复活旧文，接着收到的新文独立显示', () => {
    const run = scenario(snapshot('临时判断：无需工具。'))
    run.send(progress(1, 'COMPOSING_RESPONSE'))
    run.send(event(2, 'message.delta', { delta: '', replace: true }))
    expect(screen.queryByText('临时判断：无需工具。')).not.toBeInTheDocument()
    run.send(progress(3, 'SEARCHING_WEB'))
    run.refresh(snapshot('临时判断：无需工具。'), 1)
    expect(screen.queryByText('临时判断：无需工具。')).not.toBeInTheDocument()
    expect(screen.queryByText('正文输出中')).not.toBeInTheDocument()
    run.send(progress(4, 'SEARCHING_WEB', 'SUCCEEDED', 3))
    run.send(event(5, 'message.delta', { delta: '核对后的结论。' }))
    expect(screen.getByText('核对后的结论。')).toBeVisible()
    expect(run.container.textContent).not.toContain('临时判断')
    run.refresh(snapshot('核对后的结论。', 'COMPLETED'), 6)
    run.send(event(6, 'message.completed', { status: 'COMPLETED' }))
    expect(screen.getAllByText('核对后的结论。')).toHaveLength(1)
  })

  it('按事件序列去重而不是文字猜测；快照后的相同文字应合法追加', () => {
    const run = scenario(snapshot('好'))
    run.send(progress(1, 'COMPOSING_RESPONSE'))
    const delta = event(2, 'message.delta', { delta: '好' })
    run.send(delta)
    run.send(delta)
    expect(screen.getByText('好好')).toBeVisible()
    expect(screen.queryByText('好好好')).not.toBeInTheDocument()
    run.refresh(snapshot('好好'), 2)
    expect(screen.getAllByText('好好')).toHaveLength(1)
    run.send(event(3, 'message.delta', { delta: '，继续。' }))
    expect(screen.getByText('好好，继续。')).toBeVisible()
  })

  it.each(['STOPPED', 'FAILED'] as const)('%s 保留已收到正文、不继续显示输出动画、不显示成功', (status) => {
    const run = scenario()
    run.send(event(1, 'message.delta', { delta: '已输出的部分。' }))
    expect(screen.getByText('正文输出中')).toBeVisible()
    run.send(event(2, 'message.completed', { status }))
    expect(screen.getByText('已输出的部分。')).toBeVisible()
    expect(screen.queryByText('正文输出中')).not.toBeInTheDocument()
    expect(run.container.querySelector('.anticon-loading')).toBeNull()
    expect(screen.getByRole('button', { name: /展开执行动态/ })).toHaveTextContent(status === 'STOPPED' ? '本次回复已停止' : '本次回复未完成')
  })

  it('重复联网是各次真实操作，步骤失败后继续分析不把旧失败伪装为成功', () => {
    const run = scenario()
    run.send(progress(1, 'SEARCHING_WEB'))
    fireEvent.click(screen.getByRole('button', { name: /展开执行动态/ }))
    run.send(progress(2, 'SEARCHING_WEB', 'FAILED', 1))
    run.send(progress(3, 'PLANNING_WORKFLOW'))
    run.send(progress(4, 'SEARCHING_WEB'))
    const list = screen.getByRole('list')
    expect(within(list).getAllByRole('listitem')).toHaveLength(3)
    expect(within(list).getByText('联网查找资料未完成')).toBeVisible()
    expect(within(list).getByText('正在联网查找资料')).toBeVisible()
    expect(run.container.querySelectorAll('li .anticon-loading')).toHaveLength(1)
  })
})

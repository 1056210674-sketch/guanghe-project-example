import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConversationMessage, PaidTask } from '../api/conversationApi'
import { ChatMessageStream } from '../features/chat/components/ChatMessageStream'
import type { ChatMessageStreamProps } from '../features/chat/components/ChatMessageStream'
import type { ChatMessageView } from '../features/chat/types'
import { toChatMessageView } from '../features/chat/live/chatAdapters'
import { placeExecutionTimelines } from '../features/chat/live/messageTimelinePlacement'
import { readExecutionTimelineRecovery, storeExecutionTimelineRecovery } from '../features/chat/live/executionTimeline'
import type { ExecutionTimeline } from '../features/chat/live/executionTimeline'

const task = { id: 'task-old', taskType: 'GEO', createdAt: '2026-10-10T03:55:20Z' } satisfies Pick<PaidTask, 'id' | 'taskType' | 'createdAt'>
function message(id: string, role: 'USER' | 'ASSISTANT', time: string, runId: string | null, text: string): ConversationMessage {
  return { id, conversationId: 'conversation-1', role, contentType: 'TEXT', content: { text },
    replyToMessageId: null, runId, attachmentAssetIds: [], sequence: Number(id), status: 'COMPLETED',
    createdAt: `2026-10-10T${time}Z`, updatedAt: `2026-10-10T${time}Z` }
}
const persisted: ConversationMessage[] = [
  message('1', 'USER', '03:55:00', 'old-run', '开始一次广州 GEO 监测'),
  message('2', 'ASSISTANT', '03:55:15', 'old-run', '监测已受理'),
  message('3', 'USER', '04:06:14', 'new-run', '是的，是我们的域名。信息准确。'),
  { ...message('4', 'ASSISTANT', '04:06:25', 'new-run', '这次回复未完成。'), status: 'FAILED' as const,
    content: { text: '这次回复未完成。', businessOutcome: { schemaVersion: 1, status: 'FAILED' } } },
]
const oldTimeline: ExecutionTimeline = {
  id: 'task-event-old', messageId: 'task-event-old', taskId: task.id,
  startedAt: task.createdAt, updatedAt: '2026-10-10T04:13:00Z', elapsedMs: 126000,
  status: 'COMPLETED', steps: [{ id: 'task-completed', code: 'TASK_COMPLETED', status: 'SUCCEEDED', sequence: 122, elapsedMs: 126000 }],
}
const newTimeline: ExecutionTimeline = {
  id: '4', messageId: '4', runId: 'new-run', startedAt: '2026-10-10T04:06:14Z',
  updatedAt: '2026-10-10T04:06:25Z', elapsedMs: 11000, status: 'FAILED', steps: [],
}
const views = () => persisted.map((item) => ({ ...toChatMessageView(item), executionTimeline: item.id === '4' ? newTimeline : undefined }))
const callbacks: Omit<ChatMessageStreamProps, 'messages'> = {
  onSelectClarificationOption: vi.fn(), onEditPaidTaskConfirmation: vi.fn(), onConfirmPaidTaskConfirmation: vi.fn(),
  onOpenRecharge: vi.fn(), onPrepareReestimate: vi.fn(), onOpenPaidTaskReferenceAsset: vi.fn(), onAddPaidTaskReferenceAsset: vi.fn(),
  onViewTask: vi.fn(), onPreviewArticle: vi.fn(), onContinueEditingArticle: vi.fn(), onSaveArticleToAssets: vi.fn(),
  onPreviewGeneratedImage: vi.fn(), onDownloadGeneratedImage: vi.fn(), onSaveGeneratedImageToAssets: vi.fn(),
  onSelectGeneratedImage: vi.fn(), onTaskOutcomeAction: vi.fn(),
}
beforeEach(() => localStorage.clear())

describe('历史任务动态在聊天中的归属与展示', () => {
  it('旧监测成功归回原始时间位置；当前失败仍失败，展开记录不会改变任务或发消息', () => {
    const placed = placeExecutionTimelines(views(), persisted, { old: oldTimeline, new: newTimeline }, [task])
    expect(placed.map((view) => view.id)).toEqual(['1', '2', 'execution-timeline-task-event-old', '3', '4'])
    render(<MemoryRouter><ChatMessageStream {...callbacks} messages={placed} /></MemoryRouter>)
    const oldStatus = screen.getByRole('button', { name: '展开执行动态：后台任务记录 · GEO 监测已完成' })
    const failedStatus = screen.getByRole('button', { name: '展开执行动态：本次回复未完成' })
    expect(oldStatus.compareDocumentPosition(screen.getByText('是的，是我们的域名。信息准确。')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(failedStatus).toHaveTextContent('11s')
    expect(oldStatus).toHaveTextContent('126s')
    expect(oldStatus.closest('article')).toHaveAttribute('data-chat-task-id', task.id)
    fireEvent.click(oldStatus)
    expect(within(oldStatus.closest('article')!).getByRole('listitem')).toHaveTextContent('任务已完成')
    expect(failedStatus.closest('section')).toHaveClass('chat-execution-timeline--failed')
    for (const callback of Object.values(callbacks)) expect(callback).not.toHaveBeenCalled()
  })

  it('有明确 replyToMessageId 时把用户消息上的时间线绑到所属助手回复', () => {
    const withReply = persisted.map((item) => item.id === '2' ? { ...item, replyToMessageId: '1' } : item)
    const placed = placeExecutionTimelines(views(), withReply, { old: { ...oldTimeline, messageId: '1' } }, [task])
    expect(placed.map((view) => view.id)).toEqual(['1', '2', '3', '4'])
    expect(placed[1].executionTimeline).toMatchObject({ taskId: task.id, taskType: 'GEO' })
    expect(placed[2].executionTimeline).toBeUndefined()
  })

  it('只有相同 runId 才允许临时消息迁移，已有回复失败不会被任务成功覆盖', () => {
    const oldReplyFailure = { ...newTimeline, id: '2', messageId: '2', runId: 'old-run' }
    const withOldFailure = views().map((view) => view.id === '2' ? { ...view, executionTimeline: oldReplyFailure } : view)
    const placed = placeExecutionTimelines(withOldFailure, persisted, { old: { ...oldTimeline, runId: 'old-run' } }, [task])
    expect(placed[1].executionTimeline?.status).toBe('FAILED')
    expect(placed[2].executionTimeline).toMatchObject({ status: 'COMPLETED', taskType: 'GEO' })
    expect(placed[3].id).toBe('3')
  })

  it('任务类型未知不猜 GEO；无关联记录按开始时间而非结束时间归位', () => {
    const placed = placeExecutionTimelines(views(), persisted, { old: oldTimeline }, [])
    expect(placed[2].executionTimeline?.taskType).toBeUndefined()
    expect(placed[2].executionTimeline?.recordLabel).toBe('后台任务记录')
    render(<MemoryRouter><ChatMessageStream {...callbacks} messages={placed} /></MemoryRouter>)
    expect(screen.getByRole('button', { name: '展开执行动态：后台任务记录 · 任务已完成' })).toBeVisible()
    expect(screen.queryByText(/GEO 监测已完成/)).not.toBeInTheDocument()
  })

  it('历史消息没有缓存时间线时也保留服务端失败结论，任务成功另列', () => {
    const withoutCachedFailure = views().map((view) => ({ ...view, executionTimeline: undefined }))
    const placed = placeExecutionTimelines(withoutCachedFailure, persisted,
      { task: { ...oldTimeline, messageId: '4' } }, [task])
    expect(placed[3].businessOutcome?.status).toBe('FAILED')
    expect(placed[3].executionTimeline).toBeUndefined()
    expect(placed[4].executionTimeline?.status).toBe('COMPLETED')
    render(<MemoryRouter><ChatMessageStream {...callbacks} messages={placed} /></MemoryRouter>)
    expect(screen.getByText('本次回复未完成')).toBeVisible()
    expect(screen.getByRole('button', { name: '展开执行动态：GEO 监测已完成' })).toBeVisible()
  })

  it('刷新恢复旧缓存后仍归位，用服务器任务类型补充标签，不改缓存身份', () => {
    storeExecutionTimelineRecovery('user-1', 'conversation-1', { lastEventId: '122', lastSequence: 122 }, { old: oldTimeline, new: newTimeline })
    const recovered = readExecutionTimelineRecovery('user-1', 'conversation-1')!
    const placed = placeExecutionTimelines(views(), persisted, recovered.timelines, [task])
    expect(placed[2].executionTimeline).toMatchObject({ taskType: 'GEO', recordLabel: '后台任务记录' })
    expect(recovered.timelines[oldTimeline.id]).not.toHaveProperty('taskType')
    expect(placed.at(-1)?.id).toBe('4')
  })

  it('同一任务已有准确来源卡时复用该卡，不因后来的同品牌回复而移位', () => {
    const sourceViews = views().map((view) => view.id === '2' ? { ...view,
      parallelTaskGroup: { id: 'group-old', title: '任务执行状态', tasks: [{ taskId: task.id, taskNumber: 'GEO-1', title: 'GEO 监测', taskType: 'GEO 监测', statusLabel: '已完成', statusTone: 'success' as const, detailEnabled: true }] },
    } : view)
    const placed = placeExecutionTimelines(sourceViews, persisted, { old: oldTimeline }, [task])
    expect(placed).toHaveLength(4)
    expect(placed[1].executionTimeline?.taskId).toBe(task.id)
    expect(placed[3].executionTimeline?.status).toBe('FAILED')
  })

  it('完整文章准备只显示聚合卡，不重复显示三个内部阶段任务', () => {
    const preparationId = '601'
    const stageTasks = ['title', 'outline', 'body'].map((stage, index) => ({
      id: `article-${stage}`,
      taskType: 'ARTICLE' as const,
      articlePreparationId: preparationId,
      createdAt: `2026-10-10T04:31:0${index}Z`,
    }))
    const stageTimelines = Object.fromEntries(stageTasks.map((stageTask, index) => [stageTask.id, {
      ...oldTimeline,
      id: `event-${stageTask.id}`,
      messageId: `event-${stageTask.id}`,
      taskId: stageTask.id,
      startedAt: stageTask.createdAt,
      updatedAt: '2026-10-10T04:31:30Z',
      elapsedMs: [4000, 8000, 29000][index],
    }]))
    const independentTask = {
      id: 'article-independent',
      taskType: 'ARTICLE' as const,
      createdAt: '2026-10-10T04:32:00Z',
    }
    const independentTimeline = {
      ...oldTimeline,
      id: 'event-article-independent',
      messageId: 'event-article-independent',
      taskId: independentTask.id,
      startedAt: independentTask.createdAt,
      updatedAt: '2026-10-10T04:32:12Z',
      elapsedMs: 12000,
    }
    const preparationView: ChatMessageView = {
      id: `article-preparation-${preparationId}`,
      role: 'ASSISTANT',
      content: '',
      articlePreparation: {
        id: preparationId,
        status: 'succeeded',
        statusLabel: '完整文章已准备',
        description: '文章已生成。',
        tone: 'success',
        loading: false,
        openLabel: '查看文章',
        stages: ['标题已完成', '结构已完成', '正文已完成'],
      },
    }

    const placed = placeExecutionTimelines(
      [...views(), preparationView],
      persisted,
      { ...stageTimelines, independent: independentTimeline },
      [...stageTasks, independentTask],
    )

    expect(placed.filter((view) => stageTasks.some((task) => task.id === view.executionTimeline?.taskId))).toHaveLength(0)
    render(<MemoryRouter><ChatMessageStream {...callbacks} messages={placed} /></MemoryRouter>)
    expect(screen.getByRole('region', { name: '完整文章准备记录' })).toBeVisible()
    const independentStatus = screen.getByRole('button', { name: '展开执行动态：后台任务记录 · 文章创作已完成' })
    expect(independentStatus.closest('article')).toHaveAttribute('data-chat-task-id', independentTask.id)
  })

  it.each(['acknowledgement-first', 'assistant-first'])('恢复双 ID 缓存时只显示同一次回复的一条进度：%s', (order) => {
    const requestId = '103377043437125632'
    const replyId = '103377044842217472'
    const currentMessages = persisted.slice(0, 2).map((item) => item.role === 'ASSISTANT'
      ? { ...item, id: replyId, content: { text: '' }, status: 'STREAMING' as const } : { ...item, id: requestId })
    const acknowledgement: ExecutionTimeline = {
      ...newTimeline, id: requestId, messageId: requestId, runId: 'old-run',
      startedAt: '2026-10-10T03:55:00Z', updatedAt: '2026-10-10T03:55:07Z',
      elapsedMs: 0, status: 'RUNNING', steps: [],
    }
    const assistant: ExecutionTimeline = {
      ...acknowledgement, id: replyId, messageId: replyId,
      updatedAt: '2026-10-10T03:55:06Z', elapsedMs: 6000,
      steps: [{ id: 'thinking', code: 'PLANNING_WORKFLOW', status: 'RUNNING', sequence: 2, elapsedMs: 6000 }],
    }
    const cached = order === 'acknowledgement-first'
      ? { [requestId]: acknowledgement, [replyId]: assistant } : { [replyId]: assistant, [requestId]: acknowledgement }
    storeExecutionTimelineRecovery('user-1', 'conversation-1', { lastEventId: '2', lastSequence: 2 }, cached)
    const recovered = readExecutionTimelineRecovery('user-1', 'conversation-1')!
    const sourceViews = currentMessages.map((item) => ({ ...toChatMessageView(item),
      executionTimeline: item.id === replyId ? assistant : undefined,
    }))
    const placed = placeExecutionTimelines(sourceViews, currentMessages, recovered.timelines, [])

    expect(placed.map((view) => view.id)).toEqual([requestId, replyId])
    expect(placed[1].executionTimeline).toMatchObject({ id: replyId, messageId: replyId, runId: 'old-run', elapsedMs: 6000 })
    expect(placed[1].executionTimeline?.steps).toHaveLength(1)
    render(<MemoryRouter><ChatMessageStream {...callbacks} messages={placed} /></MemoryRouter>)
    expect(screen.getAllByRole('button', { name: /展开执行动态/ })).toHaveLength(1)
    expect(screen.getAllByRole('article', { name: '光禾AI智能体框架消息' })).toHaveLength(1)
    expect(screen.queryByText('正在处理你的消息')).not.toBeInTheDocument()
  })

  it('移除已经生成的重复空回复容器，同时保留同 run 的独立后台任务', () => {
    const acknowledgement: ExecutionTimeline = { ...newTimeline, id: '1', messageId: '1', runId: 'old-run',
      startedAt: '2026-10-10T03:55:00Z', updatedAt: '2026-10-10T03:55:07Z', status: 'RUNNING' }
    const assistant: ExecutionTimeline = { ...acknowledgement, id: '2', messageId: '2', status: 'COMPLETED' }
    const sourceViews: ChatMessageView[] = persisted.slice(0, 2).map((item) => ({ ...toChatMessageView(item),
      executionTimeline: item.id === '2' ? assistant : undefined,
    }))
    sourceViews.push({ id: 'execution-timeline-1', role: 'ASSISTANT', content: '', executionTimeline: acknowledgement })
    const placed = placeExecutionTimelines(sourceViews, persisted.slice(0, 2), {
      acknowledgement, assistant, task: { ...oldTimeline, runId: 'old-run' },
    }, [task])

    expect(placed.map((view) => view.id)).toEqual(['1', '2', 'execution-timeline-task-event-old'])
    expect(placed.filter((view) => view.executionTimeline && !view.executionTimeline.taskId)).toHaveLength(1)
    expect(placed.at(-1)?.executionTimeline).toMatchObject({ taskId: task.id, status: 'COMPLETED' })
    render(<MemoryRouter><ChatMessageStream {...callbacks} messages={placed} /></MemoryRouter>)
    expect(screen.getAllByRole('button', { name: /展开执行动态/ })).toHaveLength(2)
    expect(screen.getAllByRole('article', { name: '光禾AI智能体框架消息' })).toHaveLength(2)
  })

  it('真实回复已有失败结论时复用其进度框，不再独立显示旧受理进度', () => {
    const acknowledgement: ExecutionTimeline = { ...newTimeline, id: '3', messageId: '3', status: 'RUNNING', steps: [] }
    const sourceViews = views().map((view) => ({ ...view, executionTimeline: undefined }))
    const placed = placeExecutionTimelines(sourceViews, persisted, { acknowledgement, assistant: newTimeline }, [])

    expect(placed.map((view) => view.id)).toEqual(['1', '2', '3', '4'])
    expect(placed[3].executionTimeline).toMatchObject({ id: '4', messageId: '4', status: 'FAILED' })
    expect(placed[3].businessOutcome?.status).toBe('FAILED')
    render(<MemoryRouter><ChatMessageStream {...callbacks} messages={placed} /></MemoryRouter>)
    expect(screen.getAllByRole('button', { name: '展开执行动态：本次回复未完成' })).toHaveLength(1)
    expect(screen.getAllByRole('article', { name: '光禾AI智能体框架消息' })).toHaveLength(2)
    expect(screen.queryByText('正在处理你的消息')).not.toBeInTheDocument()
  })
})

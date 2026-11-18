import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { ChatExecutionTimeline } from '../features/chat/components/ChatExecutionTimeline'
import {
  applyConversationEventToTimelines,
  createPendingExecutionTimeline,
  executionTimelineStepLabel,
  readExecutionTimelineRecovery,
  recoverableTimelineCursor,
  storeExecutionTimelineRecovery,
} from '../features/chat/live/executionTimeline'
import type { ParsedConversationEvent } from '../features/chat/live/conversationStream'
import type { ExecutionTimelineMap } from '../features/chat/live/executionTimeline'

const ownerId = 'user-1'
const conversationId = 'conversation-1'
const occurredAt = '2026-09-14T10:00:00Z'

function event(
  sequence: number,
  eventName: ParsedConversationEvent['event'],
  payload: Record<string, unknown>,
  messageId = 'message-1',
): ParsedConversationEvent {
  return {
    id: String(sequence),
    event: eventName,
    data: {
      eventId: `event-${sequence}`,
      sequence,
      occurredAt,
      conversationId,
      messageId,
      taskId: null,
      payload,
    },
  }
}

beforeEach(() => window.localStorage.clear())

describe('用户执行动态模型', () => {
  it.each([
    ['ANSWERED', '本次回复已完成'],
    ['NEEDS_INPUT', '请补充信息或选择对象'],
    ['DRAFT_READY', '报价草稿已准备，等待确认'],
    ['PARTIAL', '本次回复部分完成'],
    ['FAILED', '本次回复未完成'],
  ] as const)('技术完成 %s 的实时终态与缓存重放一致', (status, label) => {
    const timelines = applyConversationEventToTimelines({}, event(2, 'message.completed', {
      status: 'COMPLETED', businessOutcome: { schemaVersion: 1, status },
    }))
    expect(timelines['message-1'].businessOutcome?.status).toBe(status)
    const mounted = render(<ChatExecutionTimeline timeline={timelines['message-1']} />)
    expect(screen.getAllByText(`${label} · 0s`)).toHaveLength(2)
    storeExecutionTimelineRecovery(ownerId, conversationId, { lastEventId: '2', lastSequence: 2 }, timelines)
    const restored = readExecutionTimelineRecovery(ownerId, conversationId)!.timelines['message-1']
    mounted.rerender(<ChatExecutionTimeline timeline={restored} />)
    expect(screen.getAllByText(`${label} · 0s`)).toHaveLength(2)
    expect(screen.queryByText('请求已开始处理')).not.toBeInTheDocument()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
    if (status !== 'ANSWERED') expect(screen.queryByText(/本次回复已完成/)).not.toBeInTheDocument()
  })

  it('最终业务失败取代旧成功步骤的播报，但保留真实执行记录', () => {
    let timelines = applyConversationEventToTimelines({}, event(1, 'agent.progress', {
      runId: 'run-1', messageId: 'message-1', stageCode: 'LOADING_PRODUCT_CONTEXT',
      status: 'SUCCEEDED', ordinal: 2, elapsedMs: 500, current: null, total: null,
    }))
    timelines = applyConversationEventToTimelines(timelines, event(2, 'message.completed', {
      status: 'COMPLETED', businessOutcome: { schemaVersion: 1, status: 'FAILED' },
    }))
    render(<ChatExecutionTimeline timeline={timelines['message-1']} />)
    expect(screen.getAllByText(/本次回复未完成 ·/)).toHaveLength(2)
    expect(screen.getByRole('listitem')).toHaveTextContent('已读取商品资料')
    expect(screen.queryByText('请求已开始处理')).not.toBeInTheDocument()
    expect(screen.queryByText('该步骤未完成')).not.toBeInTheDocument()
  })

  it('服务端终态覆盖 RUNNING 恢复缓存后不再计时或补造执行中步骤', () => {
    const cached = createPendingExecutionTimeline({ messageId: 'message-1', runId: 'run-1', startedAt: occurredAt })
    storeExecutionTimelineRecovery(ownerId, conversationId, { lastEventId: '1', lastSequence: 1 }, { 'message-1': cached })
    const restored = readExecutionTimelineRecovery(ownerId, conversationId)!.timelines['message-1']
    expect(restored.status).toBe('RUNNING')
    render(<ChatExecutionTimeline timeline={{ ...restored, businessOutcome: { schemaVersion: 1, status: 'FAILED' } }} />)
    expect(screen.getAllByText('本次回复未完成 · 0s')).toHaveLength(2)
    expect(screen.queryByText('请求已开始处理')).not.toBeInTheDocument()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })

  it('没有业务结果的历史终态只显示已结束，不补造需求完成', () => {
    const timeline = applyConversationEventToTimelines({}, event(1, 'message.completed', { status: 'COMPLETED' }))['message-1']
    render(<ChatExecutionTimeline timeline={timeline} />)
    expect(screen.getAllByText('本次回复已结束 · 0s')).toHaveLength(2)
    expect(screen.queryByText('本次回复已完成 · 0s')).not.toBeInTheDocument()
  })
  it.each([
    ['RUNNING', '正在核对参考素材'],
    ['SUCCEEDED', '已核对参考素材'],
  ] as const)('参考素材 %s 阶段只表示授权素材核对，不声称已看图', (status, label) => {
    const timelines = applyConversationEventToTimelines({}, event(1, 'agent.progress', {
      runId: 'run-1',
      messageId: 'message-1',
      stageCode: 'LOADING_REFERENCE_ASSETS',
      status,
      ordinal: 3,
      elapsedMs: 500,
      current: null,
      total: null,
    }))

    const timeline = timelines['message-1']
    expect(executionTimelineStepLabel(timeline.steps[0])).toBe(label)
    render(<ChatExecutionTimeline timeline={timeline} />)
    expect(screen.getByRole('listitem')).toHaveTextContent(label)
    expect(screen.queryByText(/读取参考图片/)).not.toBeInTheDocument()
  })

  it('用同一 runId 将发送受理的临时时间线迁移到权威响应消息，避免重复动态', () => {
    const runId = '2d521b70-c46e-468e-b619-3cff005549ae'
    const pending = {
      'user-message-1': createPendingExecutionTimeline({
        messageId: 'user-message-1',
        runId,
        startedAt: occurredAt,
      }),
    }
    const started = applyConversationEventToTimelines(
      pending,
      event(1, 'message.start', { runId }, 'assistant-message-1'),
    )

    expect(Object.keys(started)).toEqual(['assistant-message-1'])
    expect(started['assistant-message-1']).toMatchObject({
      id: 'assistant-message-1',
      messageId: 'assistant-message-1',
      runId,
    })
  })

  it('只接受白名单 agent.progress，并按阶段更新而不展示服务端自由文本', () => {
    const running = event(1, 'agent.progress', {
      runId: 'run-1',
      messageId: 'message-1',
      stageCode: 'LOADING_PRODUCT_CONTEXT',
      status: 'RUNNING',
      ordinal: 2,
      elapsedMs: 824,
      current: null,
      total: null,
      displayText: '泄露的自由文本',
    })
    const succeeded = event(2, 'agent.progress', {
      runId: 'run-1',
      messageId: 'message-1',
      stageCode: 'LOADING_PRODUCT_CONTEXT',
      status: 'SUCCEEDED',
      ordinal: 2,
      elapsedMs: 1_200,
      current: null,
      total: null,
    })

    const afterRunning = applyConversationEventToTimelines({}, running)
    const afterSucceeded = applyConversationEventToTimelines(
      afterRunning,
      succeeded,
    )
    const timeline = afterSucceeded['message-1']

    expect(timeline.steps).toHaveLength(1)
    expect(timeline.steps[0]).toMatchObject({
      code: 'LOADING_PRODUCT_CONTEXT',
      status: 'SUCCEEDED',
      elapsedMs: 1_200,
    })
    expect(executionTimelineStepLabel(timeline.steps[0])).toBe(
      '已读取商品资料',
    )
    expect(JSON.stringify(timeline)).not.toContain('泄露的自由文本')

    const unknown = event(3, 'agent.progress', {
      runId: 'run-1',
      messageId: 'message-1',
      stageCode: 'MODEL_RAW_REASONING',
      status: 'RUNNING',
      ordinal: 3,
      elapsedMs: 1_500,
    })
    expect(applyConversationEventToTimelines(afterSucceeded, unknown)).toBe(
      afterSucceeded,
    )

    const mismatchedMessage = event(4, 'agent.progress', {
      runId: 'run-1', messageId: 'other-message',
      stageCode: 'COMPOSING_RESPONSE', status: 'RUNNING',
      ordinal: 3, elapsedMs: 1_500, current: null, total: null,
    })
    expect(
      applyConversationEventToTimelines(afterSucceeded, mismatchedMessage),
    ).toBe(afterSucceeded)
  })

  it('把草稿等待确认与付费任务阶段接入同一时间线且不直出未知 stageCode', () => {
    let timelines = applyConversationEventToTimelines(
      {},
      event(1, 'message.start', { runId: 'run-1' }),
    )
    timelines = applyConversationEventToTimelines(
      timelines,
      event(2, 'draft.created', { draft: { id: 'draft-1' } }),
    )
    expect(timelines['message-1'].status).toBe('WAITING_CONFIRMATION')
    expect(executionTimelineStepLabel(timelines['message-1'].steps.at(-1)!)).toBe(
      '任务草稿已准备，等待确认',
    )

    timelines = applyConversationEventToTimelines(
      timelines,
      event(3, 'task.progress', {
        taskId: 'task-1',
        stageCode: 'INTERNAL_PROVIDER_XYZ',
      }),
      'message-1',
    )
    expect(executionTimelineStepLabel(timelines['message-1'].steps.at(-1)!)).toBe(
      '任务正在执行',
    )
    expect(JSON.stringify(timelines)).not.toContain('INTERNAL_PROVIDER_XYZ')

    timelines = applyConversationEventToTimelines(
      timelines,
      event(4, 'task.completed', { taskId: 'task-1' }),
      'message-1',
    )
    expect(timelines['message-1'].status).toBe('COMPLETED')
  })
})

describe('执行动态刷新恢复', () => {
  it('执行中缓存与游标一致时续传，不一致时从头回放', () => {
    const cursor = { lastEventId: '2', lastSequence: 2 }
    let timelines: ExecutionTimelineMap = {
      'message-1': createPendingExecutionTimeline({
        messageId: 'message-1',
        runId: 'run-1',
        startedAt: occurredAt,
      }),
    }
    timelines = applyConversationEventToTimelines(
      timelines,
      event(2, 'agent.progress', {
        runId: 'run-1',
        messageId: 'message-1',
        stageCode: 'UNDERSTANDING_REQUEST',
        status: 'RUNNING',
        ordinal: 1,
        elapsedMs: 500,
        current: null,
        total: null,
      }),
    )
    storeExecutionTimelineRecovery(ownerId, conversationId, cursor, timelines)

    const restored = readExecutionTimelineRecovery(ownerId, conversationId)
    expect(restored?.timelines['message-1'].status).toBe('RUNNING')
    expect(recoverableTimelineCursor(restored, cursor)).toEqual(cursor)
    expect(
      recoverableTimelineCursor(restored, {
        lastEventId: '3',
        lastSequence: 3,
      }),
    ).toBeNull()
  })

  it('已完成会话刷新后恢复折叠时间线', () => {
    let timelines = applyConversationEventToTimelines(
      {},
      event(1, 'message.start', { runId: 'run-1' }),
    )
    timelines = applyConversationEventToTimelines(
      timelines,
      event(2, 'message.completed', { status: 'COMPLETED' }),
    )
    storeExecutionTimelineRecovery(
      ownerId,
      conversationId,
      { lastEventId: '2', lastSequence: 2 },
      timelines,
    )

    const restored = readExecutionTimelineRecovery(ownerId, conversationId)
    const timeline = restored!.timelines['message-1']
    expect(timeline.status).toBe('COMPLETED')

    render(<ChatExecutionTimeline timeline={timeline} />)
    const toggle = screen.getByRole('button', { name: '展开执行动态' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByLabelText('执行动态')).toHaveAttribute(
      'aria-live',
      'polite',
    )
  })
})

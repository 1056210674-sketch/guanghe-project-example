import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import type { PaidTask, PaidTaskEvent } from '../api/conversationApi'
import { ChatExecutionTimeline } from '../features/chat/components/ChatExecutionTimeline'
import { toBackgroundTaskNotification, toParallelTaskItem, toTaskExecutionOutcome } from '../features/chat/live/chatAdapters'
import type { ParsedConversationEvent } from '../features/chat/live/conversationStream'
import {
  applyConversationEventToTimelines,
  readExecutionTimelineRecovery,
  storeExecutionTimelineRecovery,
} from '../features/chat/live/executionTimeline'
import { toTaskCenterTimelineEvent } from '../features/task-center/live/taskCenterAdapters'

function event(sequence: number, name: ParsedConversationEvent['event'], payload: Record<string, unknown>): ParsedConversationEvent {
  return {
    id: String(sequence), event: name,
    data: { eventId: String(sequence), sequence, conversationId: 'conversation-1',
      messageId: 'message-1', taskId: null, occurredAt: '2026-10-09T06:00:00Z', payload },
  }
}

function search(sequence: number, status: 'RUNNING' | 'SUCCEEDED' | 'FAILED') {
  return event(sequence, 'agent.progress', { messageId: 'message-1', runId: 'run-1',
    stageCode: 'SEARCHING_WEB', status, ordinal: 2, elapsedMs: sequence * 100,
    current: null, total: null, displayText: 'INTERNAL_RAW_TEXT' })
}

const partialTask: PaidTask = {
  id: 'task-1', taskType: 'GEO', status: 'PARTIAL', stageCode: 'PARTIAL_COMPLETED',
  stageLabel: '部分完成', spaceId: 'project-1', quotedPoints: 4, settledPoints: 3,
  frozenPoints: 0, releasedPoints: 1, progress: { completedUnits: 4, totalUnits: 4, percent: 100 },
  result: { runId: 'run-1', reportId: 'report-1', status: 'PARTIAL', expectedUnits: 4,
    succeededUnits: 3, failedUnits: 1, missingUnits: 0, settledPoints: 3, releasedPoints: 1 },
  createdAt: '2026-10-09T06:00:00Z', updatedAt: '2026-10-09T06:01:00Z',
}

beforeEach(() => window.localStorage.clear())

describe('联网搜索与失败监测项补采的执行过程', () => {
  it('真实搜索开始后显示联网查找；结果返回不等于整次回复已完成，终态刷新保留搜索记录', () => {
    let timelines = applyConversationEventToTimelines({}, search(1, 'RUNNING'))
    const mounted = render(<ChatExecutionTimeline timeline={timelines['message-1']} />)
    fireEvent.click(screen.getByRole('button', { name: /展开执行动态：正在联网查找资料/ }))
    expect(screen.getByRole('listitem')).toHaveTextContent('正在联网查找资料')
    expect(screen.queryByText(/INTERNAL_RAW_TEXT|%/)).not.toBeInTheDocument()

    timelines = applyConversationEventToTimelines(timelines, search(2, 'SUCCEEDED'))
    mounted.rerender(<ChatExecutionTimeline timeline={timelines['message-1']} />)
    expect(timelines['message-1'].status).toBe('RUNNING')
    expect(screen.getByRole('listitem')).toHaveTextContent('已完成联网查找资料')
    expect(screen.queryByText('本次回复已完成')).not.toBeInTheDocument()
    timelines = applyConversationEventToTimelines(timelines, event(3, 'message.completed', {
      status: 'COMPLETED', businessOutcome: { schemaVersion: 1, status: 'ANSWERED' },
    }))
    storeExecutionTimelineRecovery('user-1', 'conversation-1', { lastEventId: '3', lastSequence: 3 }, timelines)
    const recovered = readExecutionTimelineRecovery('user-1', 'conversation-1')!.timelines['message-1']
    mounted.rerender(<ChatExecutionTimeline timeline={recovered} />)
    expect(screen.getByRole('status')).toHaveTextContent('本次回复已完成')
    expect(screen.getByRole('listitem')).toHaveTextContent('已完成联网查找资料')
  })

  it('搜索失败明确失败步骤，不展示返回的自由文本或声称取得资料', () => {
    const timelines = applyConversationEventToTimelines({}, search(1, 'FAILED'))
    render(<ChatExecutionTimeline timeline={timelines['message-1']} />)
    fireEvent.click(screen.getByRole('button', { name: /展开执行动态：本次回复未完成/ }))
    expect(screen.getByRole('listitem')).toHaveTextContent('联网查找资料未完成')
    expect(screen.queryByText(/已完成联网|INTERNAL_RAW_TEXT/)).not.toBeInTheDocument()
  })

  it('排队、采集、仅失败项补采和部分完成按真实事件变化，报告存在也不染成成功', () => {
    let timelines = applyConversationEventToTimelines({}, event(1, 'task.queued', { taskId: 'task-1', status: 'QUEUED' }))
    const mounted = render(<ChatExecutionTimeline timeline={timelines['message-1']} />)
    expect(screen.getByRole('status')).toHaveTextContent('任务已排队')
    expect(screen.queryByText('任务已完成')).not.toBeInTheDocument()
    timelines = applyConversationEventToTimelines(timelines, event(2, 'task.progress', {
      taskId: 'task-1', status: 'RUNNING', stageCode: 'GEO_SAMPLING', completedUnits: 2, expectedUnits: 4,
    }))
    mounted.rerender(<ChatExecutionTimeline timeline={timelines['message-1']} />)
    expect(screen.getByRole('status')).toHaveTextContent('正在采集平台回答，2/4')
    timelines = applyConversationEventToTimelines(timelines, event(3, 'task.progress', {
      taskId: 'task-1', status: 'RUNNING', stageCode: 'GEO_RETRYING', completedUnits: 3, expectedUnits: 4,
    }))
    mounted.rerender(<ChatExecutionTimeline timeline={timelines['message-1']} />)
    fireEvent.click(screen.getByRole('button', { name: /展开执行动态：正在补采失败的监测项，3\/4/ }))
    expect(screen.getAllByRole('listitem').at(-1)).toHaveTextContent('正在补采失败的监测项3/4')
    expect(screen.queryByText(/100%|重新运行全部/)).not.toBeInTheDocument()
    timelines = applyConversationEventToTimelines(timelines, event(4, 'task.completed', {
      taskId: 'task-1', status: 'PARTIAL', stageCode: 'PARTIAL_COMPLETED', reportId: 'report-1', resultRef: 'geo-report:report-1',
    }))
    storeExecutionTimelineRecovery('user-1', 'conversation-1', { lastEventId: '4', lastSequence: 4 }, timelines)
    const recovered = readExecutionTimelineRecovery('user-1', 'conversation-1')!.timelines['message-1']
    mounted.rerender(<ChatExecutionTimeline timeline={recovered} />)
    expect(recovered.status).toBe('PARTIAL')
    expect(screen.getByRole('status')).toHaveTextContent('任务部分完成')
    expect(screen.getByLabelText('执行动态')).toHaveClass('chat-execution-timeline--partial')
    expect(screen.getAllByRole('listitem').at(-1)).not.toHaveClass('chat-execution-timeline__step--succeeded')
    expect(screen.queryByText('任务已完成')).not.toBeInTheDocument()
    expect(mounted.container.querySelector('.anticon-loading')).toBeNull()
  })

  it.each([{ completedUnits: 3 }, { completedUnits: 5, expectedUnits: 4 }, { completedUnits: -1, expectedUnits: 4 }])(
    '没有有效成对采样计数时不猜数字、百分比或补采轮数：%j', (counts) => {
      const timelines = applyConversationEventToTimelines({}, event(1, 'task.progress', {
        taskId: 'task-1', status: 'RUNNING', stageCode: 'GEO_RETRYING', ...counts,
      }))
      render(<ChatExecutionTimeline timeline={timelines['message-1']} />)
      expect(screen.getByRole('status').textContent).toBe('正在补采失败的监测项')
      expect(screen.queryByText(/\d\/\d|%|第.*轮/)).not.toBeInTheDocument()
    },
  )

  it('同一部分完成任务在后台通知、任务结果和任务详情中口径一致', () => {
    expect(toBackgroundTaskNotification(partialTask)).toMatchObject({ title: 'GEO 监测部分完成' })
    expect(toBackgroundTaskNotification(partialTask)?.description).not.toContain('任务已在后台完成')
    expect(toParallelTaskItem(partialTask).statusLabel).toBe('部分完成')
    const outcome = toTaskExecutionOutcome(partialTask)
    expect(outcome.statusLabel).toBe('部分完成')
    expect(outcome.resultItems).toEqual(expect.arrayContaining([expect.objectContaining({ label: '失败 1 个' })]))
    expect(outcome.actions).toContainEqual({ type: 'VIEW_COMPLETED_REPORT', enabled: true })
    const terminal = { id: 'event-1', sequence: 1, eventType: 'task.completed', status: 'PARTIAL',
      stageCode: 'COMPLETED', occurredAt: partialTask.updatedAt } satisfies PaidTaskEvent
    expect(toTaskCenterTimelineEvent(terminal).label).toBe('任务部分完成')
    expect(toTaskCenterTimelineEvent({ ...terminal, eventType: 'task.progress', status: 'RUNNING', stageCode: 'GEO_RETRYING' }).label)
      .toBe('正在补采失败的监测项')
  })
})

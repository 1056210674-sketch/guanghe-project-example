import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { PaidTask } from '../api/conversationApi'
import { ChatExecutionTimeline } from '../features/chat/components/ChatExecutionTimeline'
import { applyConversationEventToTimelines, reconcileTaskExecutionTimelines } from '../features/chat/live/executionTimeline'
import type { ExecutionTimelineMap } from '../features/chat/live/executionTimeline'

const startedAt = '2026-10-22T08:15:23Z'
const finishedAt = '2026-10-22T08:16:11Z'
const cached: ExecutionTimelineMap = {
  old: { id: 'old', messageId: 'task-event-61', taskId: '61',
    startedAt, updatedAt: '2026-10-22T08:16:00Z', elapsedMs: 37000,
    status: 'RUNNING', steps: [{ id: 'saving', code: 'TASK_ASSET_SAVING',
      status: 'RUNNING', sequence: 1, elapsedMs: 37000 }] },
  reply: { id: 'reply', messageId: 'reply', runId: 'run-1',
    startedAt, updatedAt: startedAt, elapsedMs: 0, status: 'FAILED', steps: [] },
}
const snapshot = (status: PaidTask['status']) => ({ id: '61', status, createdAt: startedAt, updatedAt: finishedAt })

describe('任务终态快照恢复执行动态', () => {
  it.each([
    ['SUCCEEDED', 'COMPLETED', '任务已完成'],
    ['PARTIAL', 'PARTIAL', '任务部分完成'],
    ['FAILED', 'FAILED', '任务未完成'],
    ['SKIPPED', 'FAILED', '任务未完成'],
    ['SYSTEM_TERMINATED', 'FAILED', '任务未完成'],
  ] as const)('错过终态 SSE 后用 %s 快照结束保存状态和计时', (taskStatus, status, label) => {
    const recovered = reconcileTaskExecutionTimelines(cached, [snapshot(taskStatus)])
    expect(recovered.old).toMatchObject({ status, startedAt, updatedAt: finishedAt, elapsedMs: 48000 })
    expect(recovered.old.steps).toBe(cached.old.steps)
    expect(recovered.reply).toBe(cached.reply)
    expect(cached.old.status).toBe('RUNNING')
    expect(reconcileTaskExecutionTimelines(recovered, [snapshot(taskStatus)])).toBe(recovered)
    render(<ChatExecutionTimeline timeline={recovered.old} />)
    const button = screen.getByRole('button', { name: `展开执行动态：${label}` })
    expect(button).toHaveTextContent('48s')
    expect(screen.queryByText('正在保存到资产中心')).not.toBeInTheDocument()
  })

  it('旧活动快照或其他任务不制造完成，重复资源中的活动记录也不能掩盖终态', () => {
    expect(reconcileTaskExecutionTimelines(cached, [snapshot('RUNNING')])).toBe(cached)
    expect(reconcileTaskExecutionTimelines(cached, [{ ...snapshot('SUCCEEDED'), id: 'other' }])).toBe(cached)
    expect(reconcileTaskExecutionTimelines(cached, [snapshot('SUCCEEDED'), snapshot('RUNNING')]).old.status).toBe('COMPLETED')
  })

  it('终态快照覆盖迟到的保存进度，不重新开始转圈或累计小时级耗时', () => {
    const completed = reconcileTaskExecutionTimelines(cached, [snapshot('SUCCEEDED')])
    const lateProgress = applyConversationEventToTimelines(completed, {
      id: '2', event: 'task.progress', data: {
        eventId: 'event-2', sequence: 2, occurredAt: '2026-10-22T09:15:23Z',
        conversationId: '1', messageId: 'old', taskId: '61',
        payload: { taskId: '61', stageCode: 'ASSET_INGESTION_PENDING' },
      },
    })
    const recovered = reconcileTaskExecutionTimelines(lateProgress, [snapshot('SUCCEEDED')])
    expect(recovered.old).toMatchObject({ status: 'COMPLETED', elapsedMs: 48000, updatedAt: finishedAt })
  })

  it.each(['newest-first', 'newest-last'])('来源卡与通知卡终态版本冲突时取更新时间最新的任务快照：%s', (order) => {
    const newest = snapshot('SUCCEEDED')
    const older = { ...snapshot('PARTIAL'), updatedAt: '2026-10-22T08:16:00Z' }
    const tasks = order === 'newest-first' ? [newest, older] : [older, newest]
    expect(reconcileTaskExecutionTimelines(cached, tasks).old)
      .toMatchObject({ status: 'COMPLETED', updatedAt: finishedAt, elapsedMs: 48000 })
  })
})

import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import type { ConversationMessage } from '../api/conversationApi'
import { ChatExecutionTimeline } from '../features/chat/components/ChatExecutionTimeline'
import { createPendingExecutionTimeline } from '../features/chat/live/executionTimeline'
import { acceptReplyExecutionTimeline, reconcileReplyExecutionTimelines } from '../features/chat/live/replyTimelineState'

const start = '2026-10-13T06:23:27Z'
const end = '2026-10-13T06:24:00Z'
const accepted = { messageId: 'user-1', runId: 'run-1', status: 'QUEUED' as const }
const reply: ConversationMessage = { id: 'assistant-1', conversationId: 'chat-1', role: 'ASSISTANT',
  contentType: 'TEXT', content: { text: '资料已保存', businessOutcome: { schemaVersion: 1, status: 'ANSWERED' } },
  replyToMessageId: null, runId: accepted.runId, sequence: 2, attachmentAssetIds: [],
  status: 'COMPLETED', createdAt: start, updatedAt: end }

it('消息快照先出现时受理回执绑定真实回复，并显示已完成而不是另建等待框', () => {
  const streaming = { ...reply, status: 'STREAMING' as const, content: { text: '' } }
  const state = acceptReplyExecutionTimeline({}, accepted, [streaming])
  expect(Object.keys(state)).toEqual([reply.id])
  expect(state[reply.id].startedAt).toBe(start)
  const completed = reconcileReplyExecutionTimelines(state, [reply])
  render(<ChatExecutionTimeline timeline={completed[reply.id]} />)
  expect(screen.getByRole('button', { name: '展开执行动态：本次回复已完成' })).toHaveTextContent('33s')
  expect(screen.queryByRole('button', { name: /正在处理/ })).not.toBeInTheDocument()
})

it('迟到的排队回执不会复活已结束回复，即使尚未拿到下一份快照', () => {
  const completed = { ...createPendingExecutionTimeline({ messageId: reply.id, runId: accepted.runId, startedAt: start }),
    status: 'FAILED' as const, updatedAt: end, elapsedMs: 33000 }
  const state = acceptReplyExecutionTimeline({ [reply.id]: completed }, accepted)
  expect(Object.keys(state)).toEqual([reply.id])
  render(<ChatExecutionTimeline timeline={state[reply.id]} />)
  expect(screen.getByRole('button', { name: '展开执行动态：本次回复未完成' })).toHaveTextContent('33s')
  expect(screen.queryByRole('button', { name: /正在处理/ })).not.toBeInTheDocument()
})

it('旧快照不能覆盖更新的终态，但服务器终态能收掉空回执占位', () => {
  const newerFailure = { ...createPendingExecutionTimeline({ messageId: reply.id, runId: accepted.runId, startedAt: start }),
    status: 'FAILED' as const, updatedAt: '2026-10-13T06:24:01Z', elapsedMs: 34000 }
  expect(reconcileReplyExecutionTimelines({ [reply.id]: newerFailure }, [reply])[reply.id].status).toBe('FAILED')
  const latePlaceholder = createPendingExecutionTimeline({ messageId: accepted.messageId, runId: accepted.runId, startedAt: '2026-10-13T06:24:02Z' })
  expect(reconcileReplyExecutionTimelines({ [accepted.messageId]: latePlaceholder }, [reply])[reply.id]).toMatchObject({
    status: 'COMPLETED', startedAt: start, updatedAt: end, elapsedMs: 33000,
  })
})

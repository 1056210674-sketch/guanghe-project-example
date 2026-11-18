import type { ConversationMessage, MessageAccepted } from '../../../api/conversationApi'
import { readBusinessOutcome } from './businessOutcome'
import { createPendingExecutionTimeline, type ExecutionTimeline, type ExecutionTimelineMap } from './executionTimeline'

const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'STOPPED'])

function hasRecordedProgress(timeline: ExecutionTimeline) {
  return Boolean(timeline.steps.length || timeline.businessOutcome || TERMINAL_STATUSES.has(timeline.status))
}

function responseForRun(messages: readonly ConversationMessage[], runId: string) {
  const replies = messages.filter((message) => message.role === 'ASSISTANT' && message.runId === runId)
  return replies.length === 1 ? replies[0] : undefined
}

/** Receipts identify the request; streamed events identify its reply. Keep one
 * reply timeline per run, including duplicates stored by older browser builds.
 * A paid task has its own lifecycle and must never be folded into this reply.
 */
export function reconcileReplyExecutionTimelines(
  current: ExecutionTimelineMap,
  messages: readonly ConversationMessage[] = [],
): ExecutionTimelineMap {
  const result: ExecutionTimelineMap = {}
  const repliesByRun = new Map<string, ExecutionTimeline[]>()
  for (const [key, timeline] of Object.entries(current)) {
    if (timeline.taskId || !timeline.runId) result[key] = timeline
    else repliesByRun.set(timeline.runId, [...(repliesByRun.get(timeline.runId) ?? []), timeline])
  }
  for (const [runId, candidates] of repliesByRun) {
    const response = responseForRun(messages, runId)
    // A later empty receipt is not newer progress. Prefer records with actual
    // stages or an outcome, then their most recent server event.
    const ordered = [...candidates].sort((left, right) =>
      Number(hasRecordedProgress(right)) - Number(hasRecordedProgress(left))
      || Date.parse(right.updatedAt) - Date.parse(left.updatedAt)
      || Number(right.messageId === response?.id) - Number(left.messageId === response?.id))
    const selected = ordered[0]
    const stepsById = new Map<string, ExecutionTimeline['steps'][number]>()
    for (const candidate of candidates) for (const step of candidate.steps) {
      const previous = stepsById.get(step.id)
      if (!previous || step.sequence > previous.sequence) stepsById.set(step.id, step)
    }
    const messageId = response?.id ?? selected.messageId
    const terminal = response && TERMINAL_STATUSES.has(response.status)
      && (!hasRecordedProgress(selected) || Date.parse(response.updatedAt) >= Date.parse(selected.updatedAt)) ? response : undefined
    const startedAt = candidates.reduce((earliest, item) => Date.parse(item.startedAt) < Date.parse(earliest) ? item.startedAt : earliest,
      response && Date.parse(response.createdAt) < Date.parse(selected.startedAt) ? response.createdAt : selected.startedAt)
    const endedAt = terminal?.updatedAt ?? selected.updatedAt
    const businessOutcome = terminal ? readBusinessOutcome(terminal.content.businessOutcome) : undefined
    const id = result[messageId]?.taskId ? `reply-timeline-${runId}` : messageId
    result[id] = {
      ...selected,
      id,
      messageId,
      startedAt,
      updatedAt: endedAt,
      elapsedMs: Math.max(...candidates.map((item) => item.elapsedMs), terminal ? Date.parse(endedAt) - Date.parse(startedAt) : 0),
      status: terminal ? terminal.status as 'COMPLETED' | 'FAILED' | 'STOPPED' : selected.status,
      businessOutcome: businessOutcome ?? ordered.find((item) => item.businessOutcome)?.businessOutcome,
      steps: [...stepsById.values()].sort((left, right) => left.sequence - right.sequence),
    }
  }
  return result
}

/** The acknowledgement can arrive after SSE progress or even the final reply. */
export function acceptReplyExecutionTimeline(
  current: ExecutionTimelineMap,
  accepted: MessageAccepted,
  messages: readonly ConversationMessage[] = [],
): ExecutionTimelineMap {
  const reconciled = reconcileReplyExecutionTimelines(current, messages)
  const existing = Object.entries(reconciled).find(([, item]) => !item.taskId && item.runId === accepted.runId)
  if (existing) {
    const [key, timeline] = existing
    if (TERMINAL_STATUSES.has(accepted.status) && !TERMINAL_STATUSES.has(timeline.status)) {
      return { ...reconciled, [key]: { ...timeline, status: accepted.status as 'COMPLETED' | 'FAILED' | 'STOPPED' } }
    }
    return reconciled
  }
  const response = responseForRun(messages, accepted.runId)
  const timeline = createPendingExecutionTimeline({
    messageId: response?.id ?? accepted.messageId,
    runId: accepted.runId,
    startedAt: response?.createdAt,
  })
  if (TERMINAL_STATUSES.has(accepted.status)) timeline.status = accepted.status as 'COMPLETED' | 'FAILED' | 'STOPPED'
  const key = reconciled[timeline.messageId]?.taskId ? `reply-timeline-${accepted.runId}` : timeline.messageId
  return reconcileReplyExecutionTimelines({ ...reconciled, [key]: timeline }, messages)
}

import type { ConversationMessage, PaidTask } from '../../../api/conversationApi'
import type { ChatMessageView } from '../types'
import { formatChatDateTime } from './chatAdapters'
import type { ExecutionTimeline, ExecutionTimelineMap } from './executionTimeline'
import { reconcileReplyExecutionTimelines } from './replyTimelineState'

type TaskIdentity = Pick<
  PaidTask,
  'id' | 'taskType' | 'createdAt' | 'articlePreparationId'
>

function hasTask(message: ChatMessageView, taskId: string): boolean {
  return message.taskCreatedReceipt?.taskId === taskId
    || message.backgroundTaskNotification?.taskId === taskId
    || Boolean(message.parallelTaskGroup?.tasks.some((task) => task.taskId === taskId))
    || Boolean(message.taskExecutionOutcomeGroup?.outcomes.some((task) => task.taskId === taskId))
}

/** Bind only server identities; never infer a task's owner from reply text or recency. */
function sourceMessageId(
  timeline: ExecutionTimeline,
  persisted: readonly ConversationMessage[],
  views: readonly ChatMessageView[],
): string | undefined {
  const direct = persisted.find((message) => message.id === timeline.messageId)
  if (direct?.role === 'ASSISTANT') return direct.id
  if (direct?.role === 'USER') {
    const replies = persisted.filter((message) => message.role === 'ASSISTANT'
      && (message.replyToMessageId === direct.id
        || (timeline.runId && message.runId === timeline.runId)))
    return replies.length === 1 ? replies[0].id : direct.id
  }
  if (timeline.runId) {
    const sameRun = persisted.filter((message) => message.role === 'ASSISTANT'
      && message.runId === timeline.runId)
    if (sameRun.length === 1) return sameRun[0].id
  }
  if (timeline.taskId) {
    return views.find((view) => !view.backgroundTaskNotification && hasTask(view, timeline.taskId!))?.id
  }
  return undefined
}

export function placeExecutionTimelines(
  views: readonly ChatMessageView[],
  persisted: readonly ConversationMessage[],
  timelines: ExecutionTimelineMap,
  tasks: readonly TaskIdentity[],
): ChatMessageView[] {
  const tasksById = new Map(tasks.map((task) => [task.id, task]))
  const preparationStageTaskIds = new Set(
    tasks
      .filter(
        (task) =>
          task.taskType === 'ARTICLE' && Boolean(task.articlePreparationId),
      )
      .map((task) => task.id),
  )
  const isPreparationStageTimeline = (timeline: ExecutionTimeline) =>
    Boolean(
      timeline.taskId && preparationStageTaskIds.has(timeline.taskId),
    )
  const timelineInputs = Object.fromEntries(
    Object.entries(timelines).filter(
      ([, timeline]) => !isPreparationStageTimeline(timeline),
    ),
  )
  for (const [index, view] of views.entries()) {
    const timeline = view.executionTimeline
    if (!timeline || isPreparationStageTimeline(timeline)) continue
    if (timeline.taskId && Object.values(timelines).some((existing) => existing.id === timeline.id)) continue
    timelineInputs[`view:${index}:${view.id}`] = timeline
  }
  const reconciled = reconcileReplyExecutionTimelines(timelineInputs, persisted)
  const decorate = (timeline: ExecutionTimeline): ExecutionTimeline => ({
    ...timeline,
    taskType: timeline.taskId ? tasksById.get(timeline.taskId)?.taskType : undefined,
  })
  // Rebuild reply placement from the reconciled Run identity. Cached views may
  // still carry a USER acknowledgement timeline beside the authoritative reply.
  const result = views
    .filter((view) => !(view.executionTimeline
      && view.id === `execution-timeline-${view.executionTimeline.id}`
      && !view.content
      && (!view.executionTimeline.taskId
        || isPreparationStageTimeline(view.executionTimeline))))
    .map((view) => view.executionTimeline
      ? { ...view, executionTimeline: view.executionTimeline.taskId
        && !isPreparationStageTimeline(view.executionTimeline)
        ? decorate(view.executionTimeline) : undefined }
      : view)
  // Reserve the real reply's card before placing independent task records.
  // A task can start earlier than a recovered reply event without owning it.
  for (const timeline of Object.values(reconciled)) {
    if (timeline.taskId) continue
    const sourceId = sourceMessageId(timeline, persisted, result)
    const sourceIndex = sourceId ? result.findIndex((view) => view.id === sourceId) : -1
    const source = result[sourceIndex]
    if (source?.role === 'ASSISTANT' && !source.executionTimeline) {
      result[sourceIndex] = { ...source, executionTimeline: decorate(timeline) }
    }
  }
  const attached = new Set(result.flatMap((view) => view.executionTimeline ? [view.executionTimeline.id] : []))
  const startedAt = (timeline: ExecutionTimeline) => (timeline.taskId ? tasksById.get(timeline.taskId)?.createdAt : undefined)
    ?? timeline.startedAt

  for (const timeline of Object.values(reconciled).sort((left, right) => Date.parse(startedAt(left)) - Date.parse(startedAt(right)))) {
    if (attached.has(timeline.id)) continue
    const sourceId = sourceMessageId(timeline, persisted, result)
    const sourceIndex = sourceId ? result.findIndex((view) => view.id === sourceId) : -1
    const source = result[sourceIndex]
    const decorated = decorate(timeline)
    // A task and an assistant reply are independent outcomes. Never overwrite
    // an existing reply timeline (especially a failure) with a task's success.
    if (source?.role === 'ASSISTANT' && !source.executionTimeline && (!timeline.taskId || !source.businessOutcome)) {
      result[sourceIndex] = { ...source, executionTimeline: decorated }
    } else {
      const sourcePersistedIndex = persisted.findIndex((message) => message.id === sourceId)
      const firstLater = sourcePersistedIndex >= 0
        ? persisted[sourcePersistedIndex + 1]
        : persisted.find((message) => Date.parse(message.createdAt) > Date.parse(startedAt(timeline)))
      const firstLaterIndex = firstLater ? result.findIndex((view) => view.id === firstLater.id) : -1
      const insertionIndex = sourceIndex >= 0 && sourcePersistedIndex < 0
        ? sourceIndex + 1 : firstLaterIndex >= 0 ? firstLaterIndex : result.length
      result.splice(insertionIndex, 0, {
        id: `execution-timeline-${timeline.id}`,
        role: 'ASSISTANT',
        content: '',
        createdAt: formatChatDateTime(startedAt(timeline)),
        executionTimeline: {
          ...decorated,
          recordLabel: sourceId ? undefined : timeline.taskId ? '后台任务记录' : '回复过程记录',
        },
      })
    }
    attached.add(timeline.id)
  }
  return result
}

import type { ConversationMessage } from '../../../api/conversationApi'
import type { ChatMessageView } from '../types'
import type { ParsedConversationEvent } from './conversationStream'

/** Browser-only display buffer. Sequence is relative to the authoritative snapshot. */
export interface ReplyDelta {
  sequence: number
  text: string
  replace: boolean
}
export type StreamedReplies = Record<string, readonly ReplyDelta[]>

export function readReplyDelta(event: ParsedConversationEvent): ReplyDelta | undefined {
  if (event.event !== 'message.delta') return undefined
  const payload = event.data.payload
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return undefined
  const value = payload as Record<string, unknown>
  if (typeof value.delta !== 'string' || (value.replace !== undefined && typeof value.replace !== 'boolean')) return undefined
  if (!value.delta && value.replace !== true) return undefined
  return { sequence: event.data.sequence, text: value.delta, replace: value.replace === true }
}

export function appendReplyDelta(current: StreamedReplies, messageId: string, delta: ReplyDelta): StreamedReplies {
  const previous = current[messageId] ?? []
  if (previous.some((item) => item.sequence >= delta.sequence)) return current
  return { ...current, [messageId]: [...previous, delta] }
}

export function pruneReplyDeltas(current: StreamedReplies, highWatermark: number): StreamedReplies {
  return Object.fromEntries(Object.entries(current).flatMap(([id, deltas]) => {
    const remaining = deltas.filter((delta) => delta.sequence > highWatermark)
    return remaining.length ? [[id, remaining]] : []
  }))
}

export function withStreamedReplyContent(
  views: readonly ChatMessageView[],
  persisted: readonly ConversationMessage[],
  streamed: StreamedReplies,
  highWatermark: number,
): ChatMessageView[] {
  const persistedById = new Map(persisted.map((message) => [message.id, message]))
  return views.map((view) => {
    if (view.role !== 'ASSISTANT' || view.executionTimeline?.taskId) return view
    const messageId = persistedById.has(view.id) ? view.id : view.executionTimeline?.messageId ?? view.id
    const source = persistedById.get(messageId)
    if (source?.role === 'USER') return view
    const deltas = (streamed[messageId] ?? []).filter((delta) => delta.sequence > highWatermark)
    // Events after the snapshot are suffixes, not a complete prefix replay.
    // An empty replacement must suppress the old snapshot until it catches up.
    const content = deltas.reduce((text, delta) => delta.replace ? delta.text : text + delta.text, view.content)
    const terminalSnapshot = source && ['COMPLETED', 'FAILED', 'STOPPED'].includes(source.status) && deltas.length === 0
    const active = !terminalSnapshot && (view.executionTimeline
      ? ['RUNNING', 'QUEUED'].includes(view.executionTimeline.status) && !view.businessOutcome
      : source?.status === 'STREAMING')
    return { ...view, content, outputStreaming: Boolean(active && content) }
  })
}

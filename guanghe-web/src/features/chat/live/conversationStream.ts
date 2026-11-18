import type { ConversationEvent } from '../../../api/conversationApi'
import { ApiError } from '../../../api/apiError'

export const CONVERSATION_EVENT_TYPES = [
  'message.start',
  'agent.progress',
  'message.delta',
  'message.completed',
  'interaction.required',
  'context.resource',
  'draft.created',
  'task.queued',
  'task.progress',
  'task.completed',
  'task.failed',
  'heartbeat',
] as const

export type ConversationEventType =
  (typeof CONVERSATION_EVENT_TYPES)[number]

export interface ParsedConversationEvent {
  id: string
  event: ConversationEventType
  data: ConversationEvent
}

export interface ConversationEventCursor {
  lastEventId: string
  lastSequence: number
}

export function mergePersistedAndStreamedContent(
  persisted: string,
  streamed?: string,
) {
  if (!streamed) return persisted
  if (persisted.startsWith(streamed)) return persisted
  if (streamed.startsWith(persisted)) return streamed
  return `${persisted}${streamed}`
}

interface SseFrame {
  id?: string
  event?: string
  data?: string
}

const EVENT_SET = new Set<string>(CONVERSATION_EVENT_TYPES)
const CURSOR_PREFIX = 'zhixiaohe.chat.event-cursor.'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parseFrame(block: string): SseFrame | null {
  const frame: SseFrame = {}
  const data: string[] = []
  for (const line of block.split('\n')) {
    if (!line || line.startsWith(':')) {
      continue
    }
    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    let value = separator < 0 ? '' : line.slice(separator + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }
    if (field === 'id') frame.id = value
    if (field === 'event') frame.event = value
    if (field === 'data') data.push(value)
  }
  if (data.length > 0) frame.data = data.join('\n')
  return frame.data === undefined ? null : frame
}

export async function* parseSseFrames(
  stream: ReadableStream<Uint8Array>,
): AsyncGenerator<SseFrame> {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value, { stream: !done }).replace(/\r\n?/g, '\n')
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const frame = parseFrame(buffer.slice(0, boundary))
        buffer = buffer.slice(boundary + 2)
        if (frame) yield frame
        boundary = buffer.indexOf('\n\n')
      }
      if (done) break
    }
    const frame = parseFrame(buffer)
    if (frame) yield frame
  } finally {
    try {
      await reader.cancel()
    } catch {
      // A completed or remotely closed stream may already be cancelled.
    }
    reader.releaseLock()
  }
}

export function decodeConversationEvent(
  frame: SseFrame,
  conversationId: string,
): ParsedConversationEvent | null {
  if (!frame.id || !frame.event || !EVENT_SET.has(frame.event) || !frame.data) {
    return null
  }

  let value: unknown
  try {
    value = JSON.parse(frame.data) as unknown
  } catch {
    return null
  }
  if (
    !isRecord(value) ||
    value.conversationId !== conversationId ||
    typeof value.eventId !== 'string' ||
    !Number.isSafeInteger(value.sequence) ||
    (value.sequence as number) < 1 ||
    typeof value.occurredAt !== 'string' ||
    !isRecord(value.payload)
  ) {
    return null
  }

  return {
    id: frame.id,
    event: frame.event as ConversationEventType,
    data: (frame.event === 'context.resource'
      ? { ...value, payload: Object.fromEntries(Object.entries(value.payload).filter(([key, field]) =>
          ['kind', 'success', 'errorCode', 'purpose', 'candidateSetId', 'choiceId', 'status', 'observationId',
            'resourceId', 'name', 'reportId', 'spaceId', 'productId', 'runId'].includes(key)
          && (typeof field === 'string' || typeof field === 'boolean'),
        )) }
      : value) as ConversationEvent,
  }
}

export function acceptConversationEvent(
  cursor: ConversationEventCursor | undefined,
  event: ParsedConversationEvent,
): ConversationEventCursor | null {
  if (cursor && event.data.sequence <= cursor.lastSequence) {
    return null
  }
  if (cursor && event.data.sequence !== cursor.lastSequence + 1) {
    throw new ApiError({
      status: 0,
      code: 'SSE_SEQUENCE_GAP',
      message: '实时消息序列不连续，正在恢复连接。',
    })
  }
  return { lastEventId: event.id, lastSequence: event.data.sequence }
}

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}

export function readConversationEventCursor(
  conversationId: string,
): ConversationEventCursor | undefined {
  try {
    const raw = storage()?.getItem(`${CURSOR_PREFIX}${conversationId}`)
    if (!raw) return undefined
    const value = JSON.parse(raw) as unknown
    if (
      !isRecord(value) ||
      typeof value.lastEventId !== 'string' ||
      !Number.isSafeInteger(value.lastSequence) ||
      (value.lastSequence as number) < 1
    ) {
      return undefined
    }
    return value as unknown as ConversationEventCursor
  } catch {
    return undefined
  }
}

export function storeConversationEventCursor(
  conversationId: string,
  cursor: ConversationEventCursor,
): void {
  try {
    storage()?.setItem(`${CURSOR_PREFIX}${conversationId}`, JSON.stringify(cursor))
  } catch {
    // Cursor persistence is best-effort. Server-side messages remain authoritative.
  }
}

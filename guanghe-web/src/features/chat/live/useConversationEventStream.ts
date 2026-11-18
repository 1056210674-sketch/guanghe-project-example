import { useEffect, useRef } from 'react'
import { ApiError, isApiError } from '../../../api/apiError'
import { openConversationEventStream } from '../../../api/conversationApi'
import type { BrowserTraceContext } from '../../../api/browserTrace'
import {
  acceptConversationEvent,
  decodeConversationEvent,
  parseSseFrames,
  readConversationEventCursor,
  storeConversationEventCursor,
} from './conversationStream'
import type {
  ConversationEventCursor,
  ParsedConversationEvent,
} from './conversationStream'

export interface ConsumeConversationStreamOptions {
  conversationId: string
  response: Response
  cursor?: ConversationEventCursor
  onEvent: (event: ParsedConversationEvent) => void | Promise<void>
  onCursor?: (cursor: ConversationEventCursor) => void
}

export async function consumeConversationStream({
  conversationId,
  response,
  cursor,
  onEvent,
  onCursor,
}: ConsumeConversationStreamOptions): Promise<ConversationEventCursor | undefined> {
  if (!response.body) {
    throw new ApiError({
      status: response.status,
      code: 'EMPTY_SSE_STREAM',
      message: '实时消息连接没有返回可读取的数据流。',
    })
  }

  let currentCursor = cursor
  for await (const frame of parseSseFrames(response.body)) {
    const event = decodeConversationEvent(frame, conversationId)
    if (!event) continue
    const acceptedCursor = acceptConversationEvent(currentCursor, event)
    if (!acceptedCursor) continue
    await onEvent(event)
    currentCursor = acceptedCursor
    onCursor?.(acceptedCursor)
  }
  return currentCursor
}

function waitForReconnect(signal: AbortSignal, delay: number) {
  return new Promise<void>((resolve) => {
    if (signal.aborted) {
      resolve()
      return
    }
    const timer = window.setTimeout(resolve, delay)
    signal.addEventListener(
      'abort',
      () => {
        window.clearTimeout(timer)
        resolve()
      },
      { once: true },
    )
  })
}

function shouldStopReconnecting(error: unknown) {
  return (
    isApiError(error) &&
    [401, 403, 404, 410, 422].includes(error.status)
  )
}

export function useConversationEventStream({
  conversationId,
  enabled,
  onEvent,
  onError,
  browserTrace,
  initialCursor,
  onStreamEnd,
}: {
  conversationId?: string
  enabled: boolean
  onEvent: (event: ParsedConversationEvent) => void | Promise<void>
  onError: (error: unknown) => void
  browserTrace?: BrowserTraceContext
  initialCursor?: ConversationEventCursor | null
  onStreamEnd?: (
    cursor: ConversationEventCursor | undefined,
  ) => boolean | void
}) {
  const onEventRef = useRef(onEvent)
  const onErrorRef = useRef(onError)
  const onStreamEndRef = useRef(onStreamEnd)

  useEffect(() => {
    onEventRef.current = onEvent
    onErrorRef.current = onError
    onStreamEndRef.current = onStreamEnd
  }, [onError, onEvent, onStreamEnd])

  useEffect(() => {
    if (!conversationId || !enabled) return
    const controller = new AbortController()

    const run = async () => {
      let cursor = initialCursor === null
        ? undefined
        : initialCursor ?? readConversationEventCursor(conversationId)
      let reconnectDelay = 300

      while (!controller.signal.aborted) {
        try {
          const response = await openConversationEventStream(
            conversationId,
            cursor?.lastEventId,
            controller.signal,
            browserTrace,
          )
          const previousSequence = cursor?.lastSequence
          const nextCursor = await consumeConversationStream({
            conversationId,
            response,
            cursor,
            onEvent: (event) => {
              if (!controller.signal.aborted) return onEventRef.current(event)
            },
            onCursor: (nextCursor) => {
              if (controller.signal.aborted) return
              // Advance during consumption, not only after a clean EOF. Otherwise
              // a broken transport replays already-applied text on reconnect.
              cursor = nextCursor
              storeConversationEventCursor(conversationId, nextCursor)
            },
          })
          cursor = nextCursor
          if (onStreamEndRef.current?.(nextCursor) === false) return
          reconnectDelay =
            nextCursor?.lastSequence !== previousSequence
              ? 300
              : Math.min(reconnectDelay * 2, 5_000)
        } catch (error) {
          if (controller.signal.aborted) return
          onErrorRef.current(error)
          if (shouldStopReconnecting(error)) return
          reconnectDelay = Math.min(reconnectDelay * 2, 5_000)
        }
        await waitForReconnect(controller.signal, reconnectDelay)
      }
    }

    void run()
    return () => controller.abort()
  }, [browserTrace, conversationId, enabled, initialCursor])
}

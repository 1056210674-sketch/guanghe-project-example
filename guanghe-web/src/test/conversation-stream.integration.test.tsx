import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const openStreamMock = vi.hoisted(() => vi.fn())

vi.mock('../api/conversationApi', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/conversationApi')>()
  return { ...original, openConversationEventStream: openStreamMock }
})

import { ApiError } from '../api/apiError'
import {
  acceptConversationEvent,
  decodeConversationEvent,
  mergePersistedAndStreamedContent,
  parseSseFrames,
  readConversationEventCursor,
  storeConversationEventCursor,
} from '../features/chat/live/conversationStream'
import type {
  ConversationEventCursor,
  ParsedConversationEvent,
} from '../features/chat/live/conversationStream'
import {
  consumeConversationStream,
  useConversationEventStream,
} from '../features/chat/live/useConversationEventStream'
import {
  readChatRecoveryState,
  reserveConfirmationAttempt,
  storeChatRecoveryState,
  upsertRecoveryDraft,
  upsertRecoveryTask,
} from '../features/chat/live/chatRecovery'

const conversationId = '9007199254740993'

function eventBlock(
  sequence: number,
  event: string,
  payload: Record<string, unknown> = {},
) {
  return `id: ${sequence}\nevent: ${event}\ndata: ${JSON.stringify({
    eventId: String(1000 + sequence),
    sequence,
    occurredAt: '2026-09-04T10:00:00Z',
    conversationId,
    messageId: null,
    taskId: null,
    payload,
  })}\n\n`
}

function streamResponse(...chunks: string[]) {
  const encoder = new TextEncoder()
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)))
        controller.close()
      },
    }),
    { headers: { 'Content-Type': 'text/event-stream' } },
  )
}

beforeEach(() => {
  openStreamMock.mockReset()
  window.localStorage.clear()
})

describe('Browser SSE parser and ordering', () => {
  it('跨 chunk 解析 SSE id、event 和多行 data', async () => {
    const raw = eventBlock(1, 'message.start', { messageId: '21' })
    const frames = []
    for await (const frame of parseSseFrames(
      streamResponse(raw.slice(0, 19), raw.slice(19)).body!,
    )) {
      frames.push(frame)
    }

    expect(frames).toHaveLength(1)
    expect(frames[0]).toMatchObject({ id: '1', event: 'message.start' })
    expect(decodeConversationEvent(frames[0], conversationId)?.data.sequence).toBe(1)
  })

  it.each([
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
  ])('识别受支持事件 %s', async (eventName) => {
    const response = streamResponse(eventBlock(1, eventName))
    const frames = []
    for await (const frame of parseSseFrames(response.body!)) frames.push(frame)
    expect(decodeConversationEvent(frames[0], conversationId)?.event).toBe(eventName)
  })

  it('多个 delta 严格按 sequence 拼接，重复事件不会重复追加', async () => {
    const response = streamResponse(
      eventBlock(1, 'message.delta', { messageId: '21', delta: '你好' }),
      eventBlock(1, 'message.delta', { messageId: '21', delta: '你好' }),
      eventBlock(2, 'message.delta', { messageId: '21', delta: ' ' }),
      eventBlock(3, 'message.delta', { messageId: '21', delta: '世界' }),
    )
    let content = ''
    const received: number[] = []
    const cursor = await consumeConversationStream({
      conversationId,
      response,
      onEvent(event) {
        received.push(event.data.sequence)
        content += String(event.data.payload.delta ?? '')
      },
    })

    expect(content).toBe('你好 世界')
    expect(received).toEqual([1, 2, 3])
    expect(cursor).toEqual({ lastEventId: '3', lastSequence: 3 })
  })

  it('资源事件的未知元数据不泄漏、不阻断游标；后续消息仍按序消费', async () => {
    const received: ParsedConversationEvent[] = []
    const cursor = await consumeConversationStream({
      conversationId,
      response: streamResponse(
        eventBlock(1, 'context.resource', { kind: 'READ', success: true, candidateSetId: '901', choiceId: 'c1',
          result: { fullReport: 'private model context' }, inputHash: 'private', unknownVersion: { next: true } }),
        eventBlock(2, 'context.resource', { newProtocolMetadata: 'opaque' }),
        eventBlock(3, 'message.delta', { messageId: '21', delta: '已查到资料' }),
      ),
      onEvent(event) { received.push(event) },
    })
    expect(received.map((event) => event.data.sequence)).toEqual([1, 2, 3])
    expect(received[0].data.payload).toEqual({ kind: 'READ', success: true, candidateSetId: '901', choiceId: 'c1' })
    expect(received[1].data.payload).toEqual({})
    expect(received[2].data.payload.delta).toBe('已查到资料')
    expect(cursor).toEqual({ lastEventId: '3', lastSequence: 3 })
  })

  it('诊断 READ 与普通监测 READ 用途和资源引用独立，后台读取不阻断正文流', async () => {
    const runId = 'a1069390-47b3-4468-a38d-6c129a9a4413'
    const diagnosticRead = {
      kind: 'READ', success: true, runId, status: 'UNIQUE',
      purpose: 'GEO_DIAGNOSTIC_REPORT', observationId: '2001',
      resourceId: '1001', spaceId: '1001', name: '当前项目诊断报告',
    }
    const monitorRead = {
      kind: 'READ', success: true, runId, status: 'UNIQUE',
      purpose: 'LATEST_GEO_REPORT', observationId: '2002',
      resourceId: '1001', spaceId: '1001', reportId: '3001',
    }
    const received: ParsedConversationEvent[] = []
    // Java projects its durable record into EventResponse. eventType is carried
    // by the SSE event: line, not duplicated in the Browser JSON envelope.
    const first = eventBlock(1, 'context.resource', {
      ...diagnosticRead, result: { fullReport: 'internal evidence only' },
      inputHash: 'internal input identity', scopeFingerprint: 'internal scope',
    })
    const cursor = await consumeConversationStream({
      conversationId,
      response: streamResponse(
        first.slice(0, 31), first.slice(31),
        eventBlock(2, 'context.resource', monitorRead),
        eventBlock(3, 'message.delta', { messageId: '21', delta: '我会根据诊断证据继续优化。' }),
      ),
      onEvent(event) { received.push(event) },
    })

    expect(received.map((event) => event.data.sequence)).toEqual([1, 2, 3])
    expect(received[0].data.payload).toEqual(diagnosticRead)
    expect(received[0].data.payload).not.toHaveProperty('reportId')
    expect(received[1].data.payload).toEqual(monitorRead)
    expect(received[2].data.payload.delta).toBe('我会根据诊断证据继续优化。')
    expect(cursor).toEqual({ lastEventId: '3', lastSequence: 3 })
  })

  it('发现 sequence 缺口时拒绝越序事件', () => {
    const cursor: ConversationEventCursor = { lastEventId: '3', lastSequence: 3 }
    const event: ParsedConversationEvent = {
      id: '5',
      event: 'heartbeat',
      data: {
        eventId: '1005',
        sequence: 5,
        occurredAt: '2026-09-04T10:00:00Z',
        conversationId,
        messageId: null,
        taskId: null,
        payload: {},
      },
    }
    expect(() => acceptConversationEvent(cursor, event)).toThrowError(ApiError)
  })

  it('页面刷新可恢复 Last-Event-ID、sequence、草稿和任务关联', () => {
    storeConversationEventCursor(conversationId, {
      lastEventId: '12',
      lastSequence: 12,
    })
    let recovery = upsertRecoveryDraft(
      readChatRecoveryState(conversationId),
      'message-1',
      'draft-1',
    )
    recovery = upsertRecoveryTask(recovery, 'message-1', 'task-1')
    storeChatRecoveryState(conversationId, recovery)

    expect(readConversationEventCursor(conversationId)).toEqual({
      lastEventId: '12',
      lastSequence: 12,
    })
    expect(readChatRecoveryState(conversationId)).toEqual({
      drafts: [],
      tasks: [{ messageId: 'message-1', resourceId: 'task-1' }],
      confirmationAttempts: [],
    })
  })

  it('从头回放旧 delta 时不重复已经持久化的正文', () => {
    expect(mergePersistedAndStreamedContent('你好世界', '你好')).toBe('你好世界')
    expect(mergePersistedAndStreamedContent('你好', '你好世界')).toBe('你好世界')
  })

  it('同一草稿确认在网络重试和刷新后复用幂等键，草稿内容变化后换新键', () => {
    const createKey = vi
      .fn<() => string>()
      .mockReturnValueOnce('confirm-key-1')
      .mockReturnValueOnce('confirm-key-2')
    const initial = reserveConfirmationAttempt(
      readChatRecoveryState(conversationId),
      'message-1',
      'draft-1',
      'draft-1:input-hash-1:pricing-1',
      createKey,
    )
    storeChatRecoveryState(conversationId, initial.state)

    const retried = reserveConfirmationAttempt(
      readChatRecoveryState(conversationId),
      'message-1',
      'draft-1',
      'draft-1:input-hash-1:pricing-1',
      createKey,
    )
    const revised = reserveConfirmationAttempt(
      retried.state,
      'message-1',
      'draft-1',
      'draft-1:input-hash-2:pricing-2',
      createKey,
    )

    expect(retried.idempotencyKey).toBe('confirm-key-1')
    expect(revised.idempotencyKey).toBe('confirm-key-2')
    expect(createKey).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(retried.state)).not.toContain('confirmationToken')
  })
})

describe('Browser SSE lifecycle', () => {
  it('读到部分文字后网络中断，重连从已应用游标继续且重复帧不追加两次', async () => {
    const onEvent = vi.fn()
    const onError = vi.fn()
    const partial = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(eventBlock(1, 'message.delta', { messageId: '21', delta: '前半段' })))
        window.setTimeout(() => controller.error(new TypeError('connection dropped')), 0)
      },
    })
    openStreamMock.mockResolvedValueOnce(new Response(partial)).mockResolvedValueOnce(streamResponse(
      eventBlock(1, 'message.delta', { messageId: '21', delta: '前半段' }) +
      eventBlock(2, 'message.delta', { messageId: '21', delta: '后半段' }),
    ))
    const { unmount } = renderHook(() => useConversationEventStream({
      conversationId, enabled: true, initialCursor: null, onEvent, onError, onStreamEnd: () => false,
    }))
    await waitFor(() => expect(openStreamMock).toHaveBeenCalledTimes(2))
    expect(openStreamMock.mock.calls[1][1]).toBe('1')
    await waitFor(() => expect(onEvent).toHaveBeenCalledTimes(2))
    expect(onEvent.mock.calls.map(([event]) => event.data.payload.delta).join('')).toBe('前半段后半段')
    expect(onError).toHaveBeenCalledTimes(1)
    unmount()
  })
  it('一次性历史回放结束后可停止空闲重连', async () => {
    const onStreamEnd = vi.fn(() => false)
    openStreamMock.mockResolvedValue(
      streamResponse(eventBlock(1, 'message.completed', { messageId: '21' })),
    )
    const { unmount } = renderHook(() =>
      useConversationEventStream({
        conversationId,
        enabled: true,
        initialCursor: null,
        onEvent: vi.fn(),
        onError: vi.fn(),
        onStreamEnd,
      }),
    )

    await waitFor(() => expect(onStreamEnd).toHaveBeenCalledTimes(1))
    await act(() => new Promise((resolve) => window.setTimeout(resolve, 400)))
    expect(openStreamMock).toHaveBeenCalledTimes(1)
    unmount()
  })

  it('流关闭后使用已有游标重连，并将它作为 Last-Event-ID 传回', async () => {
    openStreamMock
      .mockResolvedValueOnce(streamResponse(eventBlock(1, 'heartbeat')))
      .mockImplementationOnce(
        (_id: string, _lastEventId: string | undefined, signal: AbortSignal) =>
          new Promise<Response>((_resolve, reject) => {
            signal.addEventListener(
              'abort',
              () => reject(new DOMException('aborted', 'AbortError')),
              { once: true },
            )
          }),
      )
    const { unmount } = renderHook(() =>
      useConversationEventStream({
        conversationId,
        enabled: true,
        onEvent: vi.fn(),
        onError: vi.fn(),
      }),
    )

    await waitFor(() => expect(openStreamMock).toHaveBeenCalledTimes(2))
    expect(openStreamMock.mock.calls[1][1]).toBe('1')
    unmount()
  })

  it('切换会话会中止旧 fetch 流，卸载时关闭当前流', async () => {
    const aborted: string[] = []
    openStreamMock.mockImplementation(
      (id: string, _lastEventId: string | undefined, signal: AbortSignal) =>
        new Promise<Response>((_resolve, reject) => {
          signal.addEventListener('abort', () => {
            aborted.push(id)
            reject(new DOMException('aborted', 'AbortError'))
          })
        }),
    )
    const onEvent = vi.fn()
    const onError = vi.fn()
    const { rerender, unmount } = renderHook(
      ({ id }) =>
        useConversationEventStream({
          conversationId: id,
          enabled: true,
          onEvent,
          onError,
        }),
      { initialProps: { id: 'conversation-a' } },
    )
    await waitFor(() => expect(openStreamMock).toHaveBeenCalledTimes(1))

    rerender({ id: 'conversation-b' })
    await waitFor(() => expect(aborted).toContain('conversation-a'))
    await waitFor(() => expect(openStreamMock).toHaveBeenCalledTimes(2))

    act(() => unmount())
    expect(aborted).toContain('conversation-b')
    expect(onError).not.toHaveBeenCalled()
  })
})

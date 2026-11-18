import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  confirmTaskDraft,
  createConversation,
  createTaskDraft,
  deleteConversation,
  getPaidTask,
  getPointAccount,
  getTaskDraft,
  listAllConversationMessages,
  listAllConversations,
  listPaidTaskEvents,
  listPaidTasks,
  openConversationEventStream,
  sendConversationMessage,
  stopConversationStream,
  updateConversation,
} from '../api/conversationApi'
import { storeAuthToken, readStoredAuthToken } from '../auth/tokenStorage'

const traceId = '0123456789abcdef0123456789abcdef'
const conversation = {
  id: '9007199254740993',
  title: '真实会话',
  selectedSpaceId: null,
  selectedProductId: null,
  version: 3,
  lastMessageAt: null,
  createdAt: '2026-09-04T10:00:00Z',
  updatedAt: '2026-09-04T10:00:00Z',
}

function success(data: unknown, status = 200) {
  return new Response(
    JSON.stringify({ code: 'OK', message: 'success', data, traceId }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function failure(status: number) {
  return new Response(
    JSON.stringify({
      code: `ERROR_${status}`,
      message: `后端错误 ${status}`,
      details: {},
      traceId,
    }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

type FetchMock = ReturnType<typeof vi.fn<typeof fetch>>

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  storeAuthToken({
    accessToken: 'browser-test-token',
    expiresAt: '2099-01-01T00:00:00Z',
  })
})

describe('Conversation Browser API', () => {
  it('会话列表完整翻页且 Snowflake ID 始终保留为字符串', async () => {
    const fetchMock: FetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        success({ items: [conversation], page: 1, size: 100, total: 2, hasNext: true }),
      )
      .mockResolvedValueOnce(
        success({
          items: [{ ...conversation, id: '9007199254740994' }],
          page: 2,
          size: 100,
          total: 2,
          hasNext: false,
        }),
      )
    vi.stubGlobal('fetch', fetchMock)

    const result = await listAllConversations()

    expect(result.map(({ id }) => id)).toEqual([
      '9007199254740993',
      '9007199254740994',
    ])
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/conversations?page=1&size=100',
      '/api/v1/conversations?page=2&size=100',
    ])
  })

  it('创建、重命名和删除使用真实方法、version 与包络', async () => {
    const fetchMock: FetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(success(conversation, 201))
      .mockResolvedValueOnce(success({ ...conversation, title: '新标题', version: 4 }))
      .mockResolvedValueOnce(success(null))
    vi.stubGlobal('fetch', fetchMock)

    await createConversation({})
    await updateConversation(conversation.id, { version: 3, title: '新标题' })
    await deleteConversation(conversation.id)

    expect(fetchMock.mock.calls.map(([, init]) => init?.method)).toEqual([
      'POST',
      'PATCH',
      'DELETE',
    ])
    expect(JSON.parse(String(fetchMock.mock.calls[1][1]?.body))).toEqual({
      version: 3,
      title: '新标题',
    })
  })

  it('消息历史按 cursor 加载且保持服务端升序', async () => {
    const first = {
      id: '11',
      conversationId: conversation.id,
      role: 'USER',
      contentType: 'TEXT',
      content: { text: '第一条' },
      replyToMessageId: null,
      runId: null,
      sequence: 1,
      status: 'COMPLETED',
      createdAt: conversation.createdAt,
      updatedAt: conversation.updatedAt,
    }
    const second = { ...first, id: '12', sequence: 2, content: { text: '第二条' } }
    const fetchMock: FetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(success({ items: [first], nextCursor: '1' }))
      .mockResolvedValueOnce(success({ items: [second], nextCursor: null }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await listAllConversationMessages(conversation.id)

    expect(result.map(({ id }) => id)).toEqual(['11', '12'])
    expect(fetchMock.mock.calls[1][0]).toBe(
      `/api/v1/conversations/${conversation.id}/messages?limit=100&cursor=1`,
    )
  })

  it('发送消息携带独立 clientMessageId、Idempotency-Key 和上下文', async () => {
    const fetchMock: FetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(success({ messageId: '13', runId: crypto.randomUUID(), status: 'QUEUED' }, 202))
    vi.stubGlobal('fetch', fetchMock)
    const clientMessageId = crypto.randomUUID()
    const idempotencyKey = crypto.randomUUID()

    await sendConversationMessage(
      conversation.id,
      {
        clientMessageId,
        content: '开始生成',
        selectedSpaceId: '21',
        selectedProductId: '31',
        attachments: [{ assetId: '71' }],
      },
      idempotencyKey,
    )

    const init = fetchMock.mock.calls[0][1]
    const headers = init?.headers as Headers
    expect(headers.get('Idempotency-Key')).toBe(idempotencyKey)
    expect(headers.get('Authorization')).toBe('Bearer browser-test-token')
    expect(JSON.parse(String(init?.body))).toMatchObject({
      selectedSpaceId: '21',
      selectedProductId: '31',
      attachments: [{ assetId: '71' }],
    })
    expect(JSON.parse(String(init?.body))).toMatchObject({ clientMessageId })
  })

  it('停止免费回答使用真实 stop action，不携带取消付费任务字段', async () => {
    const fetchMock: FetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(success({ runId: crypto.randomUUID(), accepted: true, status: 'STOP_REQUESTED' }, 202))
    vi.stubGlobal('fetch', fetchMock)

    const expectedRunId = crypto.randomUUID()
    await stopConversationStream(conversation.id, expectedRunId)

    expect(fetchMock.mock.calls[0][0]).toBe(
      `/api/v1/conversations/${conversation.id}/stream-actions/stop`,
    )
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({ expectedRunId })
  })

  it('SSE 使用 fetch Bearer 与 Last-Event-ID，不使用 EventSource', async () => {
    const fetchMock: FetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(''))
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()

    await openConversationEventStream(conversation.id, '17', controller.signal)

    const headers = fetchMock.mock.calls[0][1]?.headers as Headers
    expect(headers.get('Authorization')).toBe('Bearer browser-test-token')
    expect(headers.get('Accept')).toBe('text/event-stream')
    expect(headers.get('Last-Event-ID')).toBe('17')
    expect(fetchMock.mock.calls[0][1]?.signal).toBe(controller.signal)
  })

  it('草稿、确认、任务和积分账户均使用 Browser OpenAPI 路径', async () => {
    const taskDraft = {
      id: '41',
      taskType: 'IMAGE',
      status: 'WAITING_CONFIRMATION',
      spaceId: '21',
      productId: '31',
      parameters: {
        requestText: '生成图片',
        mode: 'AGENT_ASSISTED',
        agentSummary: '图片方案',
        sourceMode: 'CATALOG_PRODUCT' as const,
        referenceAssetIds: ['81'],
        aspectRatio: '1:1',
        count: 1,
      },
      warnings: [],
      inputHash: 'a'.repeat(64),
      pricingVersionId: '51',
      quotedPoints: 10,
      confirmationToken: 'opaque-token',
      expiresAt: '2099-01-01T00:00:00Z',
      createdAt: conversation.createdAt,
    }
    const accepted = {
      taskId: '61',
      status: 'QUEUED',
      quotedPoints: 10,
      frozenPoints: 10,
      createdAt: conversation.createdAt,
    }
    const task = {
      id: '61',
      taskType: 'IMAGE',
      status: 'QUEUED',
      stageCode: 'QUEUED',
      stageLabel: '等待执行',
      spaceId: '21',
      productId: '31',
      quotedPoints: 10,
      settledPoints: 0,
      frozenPoints: 10,
      result: {},
      errorCode: null,
      createdAt: conversation.createdAt,
      updatedAt: conversation.updatedAt,
    }
    const account = {
      userId: '71',
      availablePoints: 90,
      frozenPoints: 10,
      version: 2,
    }
    const fetchMock: FetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(success(taskDraft))
      .mockResolvedValueOnce(success(accepted, 202))
      .mockResolvedValueOnce(success(task))
      .mockResolvedValueOnce(success(account))
    vi.stubGlobal('fetch', fetchMock)

    await getTaskDraft('41')
    await confirmTaskDraft(
      '41',
      {
        confirmationToken: taskDraft.confirmationToken,
        inputHash: taskDraft.inputHash,
        pricingVersionId: taskDraft.pricingVersionId,
      },
      crypto.randomUUID(),
    )
    await getPaidTask('61')
    await getPointAccount()

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/task-drafts/41',
      '/api/v1/task-drafts/41/confirm',
      '/api/v1/tasks/61',
      '/api/v1/billing/point-account',
    ])
  })

  it('同一次确认的网络重试复用明确的 Idempotency-Key', async () => {
    const accepted = {
      taskId: '61',
      status: 'QUEUED' as const,
      quotedPoints: 10,
      frozenPoints: 10,
      createdAt: conversation.createdAt,
    }
    const fetchMock: FetchMock = vi
      .fn<typeof fetch>()
      .mockImplementation(async () => success(accepted, 202))
    vi.stubGlobal('fetch', fetchMock)
    const request = {
      confirmationToken: 'opaque-token',
      inputHash: 'a'.repeat(64),
      pricingVersionId: '51',
    }

    const idempotencyKey = crypto.randomUUID()
    const first = await confirmTaskDraft('41', request, idempotencyKey)
    const second = await confirmTaskDraft('41', request, idempotencyKey)

    expect(first.taskId).toBe('61')
    expect(second.taskId).toBe('61')
    const keys = fetchMock.mock.calls.map(([, init]) =>
      (init?.headers as Headers).get('Idempotency-Key'),
    )
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
    expect(keys).toEqual([idempotencyKey, idempotencyKey])
  })

  it('VIDEO 草稿、任务列表和事件只使用生成契约中的 Browser 路径', async () => {
    const draft = {
      id: '41',
      draftSource: 'FORM',
      taskType: 'VIDEO',
      parameterSchemaVersion: 1,
      status: 'WAITING_CONFIRMATION',
      spaceId: '21',
      productId: '31',
      sourceMode: 'CATALOG_PRODUCT',
      referenceAssetIds: ['81'],
      parameters: {
        requestText: '晨光中的咖啡机',
        mode: 'AGENT_ASSISTED',
        agentSummary: '晨光中的咖啡机',
        sourceMode: 'CATALOG_PRODUCT' as const,
        referenceAssetIds: ['81'],
        scene: '晨光中的咖啡机',
        durationSeconds: 5,
        aspectRatio: '16:9',
        resolution: '720P',
        audio: false,
        seed: -1,
        promptExtend: true,
        watermark: false,
      },
      warnings: [],
      inputHash: 'a'.repeat(64),
      pricingVersionId: '51',
      quotedPoints: 10,
      confirmationToken: 'opaque-token',
      expiresAt: '2099-01-01T00:00:00Z',
      createdAt: conversation.createdAt,
    }
    const task = {
      id: '61', taskType: 'VIDEO', status: 'RUNNING',
      stageCode: 'PROVIDER_GENERATING', stageLabel: '视频生成中',
      spaceId: '21', productId: '31', quotedPoints: 10,
      settledPoints: 0, frozenPoints: 10, result: {}, errorCode: null,
      createdAt: conversation.createdAt, updatedAt: conversation.updatedAt,
    }
    const events = [{
      id: '71', sequence: 1, eventType: 'task.progress', status: 'RUNNING',
      stageCode: 'PROVIDER_GENERATING', errorCode: null,
      occurredAt: conversation.updatedAt,
    }]
    const fetchMock: FetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(success(draft, 201))
      .mockResolvedValueOnce(success({
        items: [task], page: 1, size: 20, total: 1, hasNext: false,
      }))
      .mockResolvedValueOnce(success(events))
    vi.stubGlobal('fetch', fetchMock)
    const request: Parameters<typeof createTaskDraft>[0] = {
      conversationId: conversation.id,
      taskType: 'VIDEO' as const,
      spaceId: '21',
      productId: '31',
      parameterSchemaVersion: 1 as const,
      parameters: {
        ...draft.parameters,
        mode: 'AGENT_ASSISTED',
        aspectRatio: '16:9',
        resolution: '720P',
      },
    }
    const idempotencyKey = crypto.randomUUID()

    await createTaskDraft(request, idempotencyKey)
    await listPaidTasks({
      taskType: 'VIDEO',
      status: 'RUNNING',
      conversationId: conversation.id,
      page: 1,
      size: 20,
    })
    await listPaidTaskEvents('61')

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/task-drafts',
      `/api/v1/tasks?taskType=VIDEO&status=RUNNING&conversationId=${conversation.id}&page=1&size=20`,
      '/api/v1/tasks/61/events',
    ])
    const createInit = fetchMock.mock.calls[0][1]
    expect((createInit?.headers as Headers).get('Idempotency-Key')).toBe(
      idempotencyKey,
    )
    expect(JSON.parse(String(createInit?.body))).toEqual(request)
  })

  it.each([401, 403, 404, 409, 410, 422, 429, 503])(
    '保留 HTTP %i 的后端 message 和 traceId',
    async (status) => {
      vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(failure(status)))
      await expect(createConversation({})).rejects.toMatchObject({
        status,
        message: `后端错误 ${status}`,
        traceId,
      })
      if (status === 401) expect(readStoredAuthToken()).toBeNull()
    },
  )
})

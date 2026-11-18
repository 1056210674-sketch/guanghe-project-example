import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PaidTask, SendConversationMessageRequest } from '../api/conversationApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { storeConversationEventCursor } from '../features/chat/live/conversationStream'
import { storeChatRecoveryState } from '../features/chat/live/chatRecovery'
import {
  applyConversationEventToTimelines,
  createPendingExecutionTimeline,
  readExecutionTimelineRecovery,
  storeExecutionTimelineRecovery,
} from '../features/chat/live/executionTimeline'
import { APP_PATHS } from '../routes/paths'
import { useAuthStore } from '../stores/authStore'
import { renderApp } from './renderApp'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const now = '2026-09-04T10:00:00Z'
const streamingRunId = '2d521b70-c46e-468e-b619-3cff005549ae'
let snapshotHighWatermark = 0
let snapshotActiveRun: { messageId: string; runId: string; status: string } | null = null

function ok(data: unknown, status = 200) {
  if (data && typeof data === 'object' && 'messageId' in data && 'runId' in data && 'status' in data && data.status === 'QUEUED') {
    snapshotActiveRun = data as { messageId: string; runId: string; status: string }
  }
  // Message fixtures now feed the bounded server snapshot, not the old
  // unbounded message-list endpoint. This helper keeps the fixtures concise.
  if (data && typeof data === 'object' && 'nextCursor' in data && 'items' in data) {
    const items = data.items as ReturnType<typeof message>[]
    const streaming = items.find((item) => item.status === 'STREAMING')
    data = { messages: items, nextBeforeSequence: data.nextCursor, highWatermark: snapshotHighWatermark,
      activeRun: streaming ? { messageId: streaming.id, runId: streaming.runId, status: 'RUNNING' } : snapshotActiveRun }
  }
  return new Response(
    JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function apiFailure(status: number, message: string) {
  return new Response(
    JSON.stringify({ code: `ERROR_${status}`, message, details: {}, traceId: testTraceId }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function conversation(id = '1', title = '真实会话') {
  return {
    id,
    title,
    selectedSpaceId: null,
    selectedProductId: null,
    version: 1,
    lastMessageAt: null,
    createdAt: now,
    updatedAt: now,
  }
}

function message(id: string, role: 'USER' | 'ASSISTANT', content: Record<string, unknown>) {
  return {
    id,
    conversationId: '1',
    role,
    contentType: 'TEXT',
    content,
    replyToMessageId: null,
    runId: streamingRunId,
    sequence: Number(id),
    status: 'COMPLETED',
    createdAt: now,
    updatedAt: now,
  }
}

function sseCompleted(sequence: number) {
  snapshotActiveRun = null
  return sseResponse(sequence, 'message.completed', {
    messageId: '99',
    status: 'COMPLETED',
  }, '99')
}

function sseResponse(
  sequence: number,
  event: string,
  payload: Record<string, unknown>,
  messageId: string | null = null,
  occurredAt: string = now,
) {
  const body = `id: ${sequence}\nevent: ${event}\ndata: ${JSON.stringify({
    eventId: String(100 + sequence),
    sequence,
    occurredAt,
    conversationId: '1',
    messageId,
    taskId: null,
    payload,
  })}\n\n`
  return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
}

function baseRouteResponse(url: string) {
  if (url.includes('/message-operations/')) return apiFailure(404, '尚未受理')
  if (url.startsWith('/api/v1/tasks?conversationId=')) {
    return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
  }
  if (url.startsWith('/api/v1/spaces?')) {
    return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
  }
  if (url === '/api/v1/billing/point-account') {
    return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
  }
  throw new Error(`Unexpected Browser API request: ${url}`)
}

beforeEach(async () => {
  await import('../pages/chat/ChatPage')
  snapshotHighWatermark = 0
  snapshotActiveRun = null
  vi.unstubAllGlobals()
  window.localStorage.clear()
  window.sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('Live ChatPage conversation flow', () => {
  it('生成图卡选中第二张后发送原文和该资产 ID，受理后清除本次附件且不重新上传', async () => {
    const current = conversation()
    const requirement = '再添加一个大狗，这只小狗靠着那只大狗'
    const nextRunId = '00000000-0000-4000-8000-000000000082'
    const assets = ['81', '82'].map((assetId) => ({
      assetId, assetType: 'IMAGE' as const, status: 'READY' as const,
      contentType: 'image/png' as const, sizeBytes: 1000, sha256: 'a'.repeat(64),
      width: 1024, height: 1024, safetyStatus: 'APPROVED' as const,
    }))
    const completedTask: PaidTask = {
      id: '61', taskType: 'IMAGE', status: 'SUCCEEDED', stageCode: 'COMPLETED',
      stageLabel: '图片已保存到资产中心', spaceId: '21', productId: null,
      quotedPoints: 20, settledPoints: 20, frozenPoints: 0, progress: null,
      errorCode: null, createdAt: now, updatedAt: now,
      result: { provider: 'QWEN_IMAGE', model: 'qwen-image-3.0', providerCostReported: false,
        assets, requestedCount: 2, succeededCount: 2, failedCount: 0 },
    }
    const history = [message('2', 'ASSISTANT', { text: '小狗图片已生成。' })]
    const requests: SendConversationMessageRequest[] = []
    const uploadRequest = vi.spyOn(XMLHttpRequest.prototype, 'send').mockImplementation(() => {
      throw new Error('引用生成图不应重新上传文件')
    })
    let acceptMessage!: () => void
    const pendingAcceptance = new Promise<Response>((resolve) => {
      acceptMessage = () => resolve(ok({ messageId: '3', runId: nextRunId, status: 'QUEUED' }, 202))
    })
    storeChatRecoveryState('1', {
      drafts: [], tasks: [{ messageId: '2', resourceId: completedTask.id }], confirmationAttempts: [],
    })
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
      if (url === '/api/v1/conversations/1') return ok(current)
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
        return ok({ messages: history, nextBeforeSequence: null, highWatermark: 0, activeRun: null })
      }
      if (url === '/api/v1/tasks/61') return ok(completedTask)
      const asset = assets.find(({ assetId }) => url === `/api/v1/assets/${assetId}`)
      if (asset) return ok({ ...asset, id: asset.assetId, name: asset.assetId === '82' ? '选中的小狗.png' : '另一张生成图.png',
        sourceType: 'GENERATED', spaceId: '21', primaryProductId: null, productIds: [],
        sourceTaskId: completedTask.id, createdAt: now, updatedAt: now })
      const download = assets.find(({ assetId }) => url === `/api/v1/assets/${assetId}/download-url`)
      if (download) return ok({ url: `https://assets.example/${download.assetId}.png`, expiresAt: '2099-01-01T00:00:00Z' })
      if (url === '/api/v1/conversations/1/messages' && init?.method === 'POST') {
        const body = JSON.parse(String(init.body)) as SendConversationMessageRequest
        requests.push(body)
        history.push({ ...message('3', 'USER', { text: body.content }), runId: nextRunId },
          { ...message('4', 'ASSISTANT', { text: '已接收本次创作要求。' }), runId: nextRunId })
        return pendingAcceptance
      }
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([`${APP_PATHS.chat}?conversation=1`])

    const gallery = await screen.findByLabelText('图片生成结果：图片生成结果', {}, { timeout: 5_000 })
    await within(gallery).findByAltText('选中的小狗.png生成图片')
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    for (const previewButton of [
      within(gallery).getByLabelText('预览选中的小狗.png', { selector: 'button' }),
      within(gallery).getAllByLabelText('预览', { selector: 'button' })[1],
    ]) {
      fireEvent.click(previewButton)
      const title = await screen.findByText('图片预览', { selector: '.ant-modal-title' })
      const dialog = title.closest<HTMLElement>('[role="dialog"]')!
      expect(dialog).toBeInTheDocument()
      expect(await within(dialog).findByAltText('选中的小狗.png原图')).toHaveAttribute('src', 'https://assets.example/82.png')
      expect(open).not.toHaveBeenCalled()
      fireEvent.click(within(dialog).getByLabelText(/close/i, { selector: 'button' }))
      await waitFor(() => expect(screen.queryByText('图片预览', { selector: '.ant-modal-title' })).not.toBeInTheDocument())
    }
    open.mockRestore()
    const selectButton = within(gallery).getAllByLabelText('用这张继续创作')[1]
    fireEvent.click(selectButton)
    const attachments = await screen.findByLabelText('本次图片附件')
    expect(await within(attachments).findByText('可使用')).toBeInTheDocument()
    expect(attachments.children).toHaveLength(1)
    expect(within(attachments).getByAltText('选中的小狗.png缩略图')).toHaveAttribute('src', 'https://assets.example/82.png')
    expect(selectButton).toHaveAttribute('aria-label', '已加入下一条素材')
    expect(selectButton).toHaveAttribute('aria-pressed', 'true')
    expect(requests).toHaveLength(0)

    const input = screen.getByLabelText('消息输入')
    fireEvent.change(input, { target: { value: requirement } })
    fireEvent.click(screen.getByLabelText('发送'))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0].content).toBe(requirement)
    expect(requests[0].attachments).toEqual([{ assetId: '82' }])
    expect(screen.getByLabelText('本次图片附件')).toBeInTheDocument()

    acceptMessage()
    await waitFor(() => expect(screen.queryByLabelText('本次图片附件')).not.toBeInTheDocument())
    expect(input).toHaveValue('')
    expect(uploadRequest).not.toHaveBeenCalled()
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST').map(([url]) => String(url)))
      .toEqual(['/api/v1/conversations/1/messages'])
  })

  it('后台保存终态事件丢失时按任务快照修复旧缓存，结束计时且停止重连', async () => {
    const current = conversation()
    const task = { id: '61', taskType: 'IMAGE', status: 'SUCCEEDED', stageCode: 'COMPLETED',
      stageLabel: '图片已保存到资产中心', spaceId: '21', productId: null,
      quotedPoints: 20, settledPoints: 20, frozenPoints: 0, progress: null, result: {},
      createdAt: now, updatedAt: '2026-09-04T10:00:48Z' }
    const cursor = { lastEventId: '1', lastSequence: 1 }
    snapshotHighWatermark = 2
    storeExecutionTimelineRecovery(userFixture.id, '1', cursor, {
      cached: { id: 'cached', messageId: 'task-event-61', taskId: '61',
        startedAt: now, updatedAt: now, elapsedMs: 0, status: 'RUNNING',
        steps: [{ id: 'saving', code: 'TASK_ASSET_SAVING', status: 'RUNNING', sequence: 1, elapsedMs: 0 }] },
    })
    let eventStreamReads = 0
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
      if (url === '/api/v1/conversations/1') return ok(current)
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) return ok({ items: [message('2', 'ASSISTANT', { text: '图片已生成。' })], nextCursor: null })
      // The timeline itself is the only recovery hint; no separate task link or list result exists.
      if (url === '/api/v1/tasks/61') return ok(task)
      if (url === '/api/v1/conversations/1/events') {
        eventStreamReads += 1
        return new Response('', { headers: { 'Content-Type': 'text/event-stream' } })
      }
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([`${APP_PATHS.chat}?conversation=1`])

    const status = await screen.findByRole('button', { name: /展开执行动态：.*图片创作已完成/ })
    expect(status).toHaveTextContent('48s')
    expect(screen.queryByRole('button', { name: /展开执行动态：.*正在保存到资产中心/ })).not.toBeInTheDocument()
    await waitFor(() => expect(readExecutionTimelineRecovery(userFixture.id, '1')?.timelines.cached)
      .toMatchObject({ status: 'COMPLETED', elapsedMs: 48000 }))
    const completedReads = eventStreamReads
    await new Promise((resolve) => window.setTimeout(resolve, 650))
    expect(eventStreamReads).toBe(completedReads)
    expect(status).toHaveTextContent('48s')
  })

  it('无 conversation 参数时可在登录后选择经营空间和商品作为下一次会话上下文', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) {
        return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
      }
      if (url === '/api/v1/spaces?page=1&size=100') {
        return ok({
          items: [{
            id: '21', name: '抖店', brandName: '测试品牌', productCount: 1,
            competitorCount: 0, version: 1, createdAt: now, updatedAt: now,
          }],
          page: 1, size: 100, total: 1, hasNext: false,
        })
      }
      if (url === '/api/v1/spaces/21/products?page=1&size=100') {
        return ok({
          items: [{
            id: '31', spaceId: '21', name: '美式咖啡', category: '咖啡',
            modelSpec: '现制饮品', aliasCount: 0, version: 1,
            createdAt: now, updatedAt: now,
          }],
          page: 1, size: 100, total: 1, hasNext: false,
        })
      }
      throw new Error(`Unexpected Browser API request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([APP_PATHS.chat])

    await screen.findByText('暂无任务', {}, { timeout: 5_000 })
    fireEvent.mouseDown(
      screen.getByRole('combobox', { name: '选择经营空间' }),
    )
    await user.click(
      await screen.findByText('抖店', {
        selector: '.ant-select-item-option-content',
      }),
    )
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/spaces/21/products?page=1&size=100',
        expect.anything(),
      )
    })

    await user.click(screen.getByRole('button', { name: '选择商品' }))
    await user.click(
      await screen.findByRole('radio', { name: /^美式咖啡/ }),
    )
    await user.click(screen.getByRole('button', { name: '确认选择' }))

    expect(screen.getByRole('button', { name: '更换商品' })).toBeVisible()
    const contextPanel = screen.getByRole('complementary', {
      name: '本次对话上下文',
    })
    expect(within(contextPanel).getByText('抖店', { selector: 'strong' })).toBeVisible()
    expect(
      within(contextPanel).getByText('美式咖啡', { selector: 'strong' }),
    ).toBeVisible()
  }, 10_000)

  it('无 conversation 参数时首次发送只创建一个会话并更新页面后发送消息', async () => {
    const user = userEvent.setup()
    const conversations = [] as ReturnType<typeof conversation>[]
    const messages: ReturnType<typeof message>[] = []
    const requests: Array<{ clientMessageId: string; idempotencyKey: string }> = []
    const firstActionTraceparents: string[] = []
    let eventSequence = 0
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) {
        return ok({ items: conversations, page: 1, size: 100, total: conversations.length, hasNext: false })
      }
      if (url === '/api/v1/conversations' && init?.method === 'POST') {
        firstActionTraceparents.push(
          (init.headers as Headers).get('traceparent') ?? '',
        )
        const created = conversation('1', '新会话')
        conversations.push(created)
        return ok(created, 201)
      }
      if (url === '/api/v1/conversations/1' && init?.method === 'GET') {
        return ok(conversations[0])
      }
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
        return ok({ items: messages, nextCursor: null })
      }
      if (url === '/api/v1/conversations/1/messages' && init?.method === 'POST') {
        const body = JSON.parse(String(init.body)) as { clientMessageId: string; content: string }
        requests.push({
          clientMessageId: body.clientMessageId,
          idempotencyKey: (init.headers as Headers).get('Idempotency-Key') ?? '',
        })
        if (requests.length === 1) {
          firstActionTraceparents.push(
            (init.headers as Headers).get('traceparent') ?? '',
          )
          conversations[0] = {
            ...conversations[0],
            title: '第一条真实消息',
            version: 2,
            updatedAt: '2026-09-04T10:00:01Z',
          }
        }
        messages.push(message(String(messages.length + 1), 'USER', { text: body.content }))
        eventSequence += 1
        return ok({ messageId: messages.at(-1)!.id, runId: crypto.randomUUID(), status: 'QUEUED' }, 202)
      }
      if (url === '/api/v1/conversations/1/events') {
        if (firstActionTraceparents.length === 2) {
          firstActionTraceparents.push(
            (init?.headers as Headers).get('traceparent') ?? '',
          )
        }
        return sseCompleted(eventSequence)
      }
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    await import('../pages/chat/ChatPage')
    renderApp([APP_PATHS.chat])

    await screen.findByText('暂无任务')
    const input = screen.getByRole('textbox', { name: '消息输入' })
    await user.click(input)
    await user.paste('第一条真实消息')
    expect(screen.getByRole('button', { name: '发送' })).toBeEnabled()
    await user.dblClick(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(
      await screen.findByRole('heading', { name: '第一条真实消息' }),
    ).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.filter(
        ([url, init]) =>
          url === '/api/v1/conversations' && init?.method === 'POST',
      ),
    ).toHaveLength(1)
    await waitFor(() => expect(input).toHaveValue(''))
    await waitFor(() => expect(firstActionTraceparents).toHaveLength(3))
    const traceparts = firstActionTraceparents.map((value) => value.split('-'))
    expect(new Set(traceparts.map((parts) => parts[1])).size).toBe(1)
    expect(new Set(traceparts.map((parts) => parts[2])).size).toBe(3)

    await user.click(input)
    await user.paste('第二条真实消息')
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '发送' })).toBeEnabled(),
    )
    await user.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(requests).toHaveLength(2))

    expect(new Set(requests.map(({ clientMessageId }) => clientMessageId)).size).toBe(2)
    expect(new Set(requests.map(({ idempotencyKey }) => idempotencyKey)).size).toBe(2)
    requests.forEach(({ clientMessageId, idempotencyKey }) => {
      expect(clientMessageId).toMatch(/^[0-9a-f-]{36}$/)
      expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
      expect(clientMessageId).not.toBe(idempotencyKey)
    })
    await waitFor(() => expect(screen.getAllByLabelText('用户消息')).toHaveLength(2))
  })

  it('首次创建成功但发送失败时保留草稿，重试复用已创建会话', async () => {
    const user = userEvent.setup()
    const conversations = [] as ReturnType<typeof conversation>[]
    const messages: ReturnType<typeof message>[] = []
    let createCalls = 0
    let sendCalls = 0
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) {
        return ok({
          items: conversations,
          page: 1,
          size: 100,
          total: conversations.length,
          hasNext: false,
        })
      }
      if (url === '/api/v1/conversations' && init?.method === 'POST') {
        createCalls += 1
        const created = conversation()
        conversations.push(created)
        return ok(created, 201)
      }
      if (url === '/api/v1/conversations/1' && init?.method === 'GET') {
        return ok(conversations[0])
      }
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
        return ok({ items: messages, nextCursor: null })
      }
      if (url === '/api/v1/conversations/1/messages' && init?.method === 'POST') {
        sendCalls += 1
        if (sendCalls === 1) {
          return apiFailure(503, '消息服务暂不可用')
        }
        const body = JSON.parse(String(init.body)) as { content: string }
        messages.push(message('1', 'USER', { text: body.content }))
        return ok(
          { messageId: '1', runId: crypto.randomUUID(), status: 'QUEUED' },
          202,
        )
      }
      if (url === '/api/v1/conversations/1/events') {
        return sseCompleted(1)
      }
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([APP_PATHS.chat])

    const input = await screen.findByRole('textbox', { name: '消息输入' })
    await user.click(input)
    await user.paste('创建后仍需保留的草稿')
    await user.click(screen.getByRole('button', { name: '发送' }))

    expect(await screen.findByRole('heading', { name: '真实会话' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue('创建后仍需保留的草稿'))
    expect(await screen.findByText(/消息服务暂不可用/)).not.toHaveTextContent(
      testTraceId,
    )

    await user.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(sendCalls).toBe(2))
    await waitFor(() => expect(input).toHaveValue(''))
    expect(createCalls).toBe(1)
  })

  it('首次创建失败时保留草稿、错误和新对话地址状态', async () => {
    const user = userEvent.setup()
    let createCalls = 0
    let messageCalls = 0
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(async (input, init) => {
        const url = String(input)
        if (url.startsWith('/api/v1/conversations?page=')) {
          return ok({ items: [], page: 1, size: 100, total: 0, hasNext: false })
        }
        if (url === '/api/v1/conversations' && init?.method === 'POST') {
          createCalls += 1
          return apiFailure(503, '会话服务暂不可用')
        }
        if (url.includes('/messages') && init?.method === 'POST') {
          messageCalls += 1
        }
        return baseRouteResponse(url)
      }),
    )
    renderApp([APP_PATHS.chat])

    const input = await screen.findByRole('textbox', { name: '消息输入' })
    await user.click(input)
    await user.paste('不能丢失的首次草稿')
    await user.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => expect(input).toHaveValue('不能丢失的首次草稿'))
    expect(await screen.findByText(/会话服务暂不可用/)).not.toHaveTextContent(
      testTraceId,
    )
    expect(screen.getByRole('heading', { name: '新对话' })).toBeInTheDocument()
    expect(createCalls).toBe(1)
    expect(messageCalls).toBe(0)
  })

  it('503 发送失败保留草稿并显示后端 message 与 traceId', async () => {
    const user = userEvent.setup()
    const current = conversation()
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(async (input, init) => {
        const url = String(input)
        if (url.startsWith('/api/v1/conversations?page=')) {
          return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
        }
        if (url === '/api/v1/conversations/1' && init?.method === 'GET') return ok(current)
        if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
          return ok({ items: [], nextCursor: null })
        }
        if (url === '/api/v1/conversations/1/messages' && init?.method === 'POST') {
          return apiFailure(503, 'Provider 暂不可用')
        }
        return baseRouteResponse(url)
      }),
    )
    renderApp([`${APP_PATHS.chat}?conversation=1`])
    const input = await screen.findByRole('textbox', { name: '消息输入' })
    await user.click(input)
    await user.paste('必须保留的草稿')
    await user.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => expect(input).toHaveValue('必须保留的草稿'))
    expect(await screen.findByText(/Provider 暂不可用/)).not.toHaveTextContent(testTraceId)
  })

  it('重命名提交 version；删除 409 时保留会话且不展示 traceId', async () => {
    const user = userEvent.setup()
    let current = conversation()
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) {
        return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
      }
      if (url === '/api/v1/conversations/1' && init?.method === 'GET') return ok(current)
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
        return ok({ items: [], nextCursor: null })
      }
      if (url === '/api/v1/conversations/1' && init?.method === 'PATCH') {
        const body = JSON.parse(String(init.body)) as { version: number; title: string }
        expect(body).toEqual({ version: 1, title: '已重命名' })
        current = { ...current, title: body.title, version: 2 }
        return ok(current)
      }
      if (url === '/api/v1/conversations/1' && init?.method === 'DELETE') {
        return apiFailure(409, '会话存在活动任务，不能删除')
      }
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([`${APP_PATHS.chat}?conversation=1`])
    await screen.findByRole('heading', { name: '真实会话' })

    await user.click(screen.getByRole('button', { name: '管理会话：真实会话' }))
    await user.click(await screen.findByRole('menuitem', { name: /重命名/ }))
    const renameInput = screen.getByRole('textbox', { name: '会话名称' })
    await user.clear(renameInput)
    await user.type(renameInput, '已重命名')
    await user.click(screen.getByRole('button', { name: /保\s*存/ }))
    expect(await screen.findByRole('heading', { name: '已重命名' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '管理会话：已重命名' }))
    await user.click(await screen.findByRole('menuitem', { name: /删除对话/ }))
    await user.click(screen.getByRole('button', { name: /确认\s*删除/ }))
    expect(await screen.findByText(/会话存在活动任务，不能删除/)).not.toHaveTextContent(testTraceId)
    expect(screen.getByRole('heading', { name: '已重命名' })).toBeInTheDocument()
  })

  it('停止当前免费回答调用 stop action，页面不存在付费任务取消按钮', async () => {
    const user = userEvent.setup()
    const current = conversation()
    let stopped = false
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) {
        return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
      }
      if (url === '/api/v1/conversations/1' && init?.method === 'GET') return ok(current)
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
        return ok({
          items: [
            {
              ...message('2', 'ASSISTANT', { text: '正在回答' }),
              status: stopped ? 'STOPPED' : 'STREAMING',
            },
          ],
          nextCursor: null,
        })
      }
      if (url === '/api/v1/conversations/1/events') return new Response('')
      if (
        url === '/api/v1/conversations/1/stream-actions/stop' &&
        init?.method === 'POST'
      ) {
        stopped = true
        expect(JSON.parse(String(init.body))).toEqual({ expectedRunId: streamingRunId })
        return ok({ runId: streamingRunId, accepted: true, status: 'STOPPED' }, 202)
      }
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([`${APP_PATHS.chat}?conversation=1`])

    await user.click(await screen.findByRole('button', { name: '停止当前回复' }))
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          ([url, init]) =>
            url === '/api/v1/conversations/1/stream-actions/stop' &&
            init?.method === 'POST',
        ),
      ).toBe(true),
    )
    expect(screen.queryByRole('button', { name: /取消任务/ })).not.toBeInTheDocument()
  })

  it('刷新时先读包含残文的服务端快照，并携带快照高水位 Last-Event-ID', async () => {
    snapshotHighWatermark = 7
    const current = conversation()
    let messageReads = 0
    const cursor = { lastEventId: '7', lastSequence: 7 }
    storeConversationEventCursor('1', cursor)
    storeExecutionTimelineRecovery(userFixture.id, '1', cursor, {
      '2': createPendingExecutionTimeline({
        messageId: '2',
        runId: streamingRunId,
        startedAt: now,
      }),
    })
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) {
        return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
      }
      if (url === '/api/v1/conversations/1' && init?.method === 'GET') return ok(current)
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
        messageReads += 1
        snapshotHighWatermark = messageReads === 1 ? 7 : 9
        return ok({
          items: [
            {
              ...message('2', 'ASSISTANT', {
                text: messageReads === 1 ? '正在' : '正在生成',
              }),
              status: messageReads === 1 ? 'STREAMING' : 'COMPLETED',
            },
          ],
          nextCursor: null,
        })
      }
      if (url === '/api/v1/conversations/1/events') {
        const headers = init?.headers as Headers
        expect(headers.get('Last-Event-ID')).toBe('7')
        const delta = sseResponse(8, 'message.delta', {
          messageId: '2',
          delta: '生成',
        }, '2')
        const completed = sseResponse(9, 'message.completed', {
          messageId: '2',
          status: 'COMPLETED',
        }, '2')
        return new Response(
          `${await delta.text()}${await completed.text()}`,
          { headers: { 'Content-Type': 'text/event-stream' } },
        )
      }
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([`${APP_PATHS.chat}?conversation=1`])

    expect(await screen.findByText('正在生成')).toBeInTheDocument()
    await waitFor(() => expect(messageReads).toBeGreaterThanOrEqual(2))
    expect(
      await screen.findByRole('button', { name: /^展开执行动态：/ }),
    ).toHaveAttribute('aria-expanded', 'false')
  })

  it('真实页面渐进输出、空替换和慢快照不会复活临时正文；完成后无重复', async () => {
    const current = conversation()
    snapshotHighWatermark = 1
    let snapshotText = '旧'
    let snapshotStatus = 'STREAMING'
    let streamReads = 0
    let controller: ReadableStreamDefaultController<Uint8Array> | undefined
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
      if (url === '/api/v1/conversations/1' && init?.method === 'GET') return ok(current)
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) return ok({
        items: [{ ...message('2', 'ASSISTANT', { text: snapshotText }), status: snapshotStatus }], nextCursor: null,
      })
      if (url === '/api/v1/conversations/1/events') {
        streamReads += 1
        expect((init?.headers as Headers).get('Last-Event-ID')).toBe('1')
        return new Response(new ReadableStream<Uint8Array>({ start(value) { controller = value } }),
          { headers: { 'Content-Type': 'text/event-stream' } })
      }
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([`${APP_PATHS.chat}?conversation=1`])
    await screen.findByText('旧')
    await waitFor(() => expect(controller).toBeDefined())
    const push = async (sequence: number, kind: string, payload: Record<string, unknown>) => {
      const frame = await sseResponse(sequence, kind, { messageId: '2', runId: streamingRunId, ...payload }, '2').text()
      await act(async () => { controller!.enqueue(new TextEncoder().encode(frame)) })
    }
    await push(2, 'agent.progress', { stageCode: 'COMPOSING_RESPONSE', status: 'RUNNING', ordinal: 1,
      elapsedMs: 100, current: null, total: null })
    await push(3, 'message.delta', { delta: '正文' })
    expect(await screen.findByText('旧正文')).toBeVisible()
    expect(screen.getByText('正文输出中')).toBeVisible()
    await push(4, 'message.delta', { delta: '', replace: true })
    expect(screen.queryByText('旧正文')).not.toBeInTheDocument()
    expect(screen.queryByText('旧')).not.toBeInTheDocument()
    snapshotHighWatermark = 3
    snapshotText = '旧正文'
    await push(5, 'context.resource', { kind: 'REPORT', success: true })
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/snapshot?')).length).toBeGreaterThan(1))
    expect(screen.queryByText('旧正文')).not.toBeInTheDocument()
    await push(6, 'message.delta', { delta: '新正文' })
    expect(await screen.findByText('新正文')).toBeVisible()
    snapshotHighWatermark = 7
    snapshotText = '新正文'
    snapshotStatus = 'COMPLETED'
    await push(7, 'message.completed', { status: 'COMPLETED', businessOutcome: { schemaVersion: 1, status: 'ANSWERED' } })
    await waitFor(() => expect(screen.queryByText('正文输出中')).not.toBeInTheDocument())
    expect(screen.getAllByText('新正文')).toHaveLength(1)
    expect(streamReads).toBe(1)
    expect(screen.queryByText(/旧正文|新正文新正文/)).not.toBeInTheDocument()
  })

  it('服务端快照已完成时不被旧运行缓存误导而空转连接', async () => {
    const current = conversation()
    const cursor = { lastEventId: '7', lastSequence: 7 }
    const runId = '2d521b70-c46e-468e-b619-3cff005549ae'
    storeConversationEventCursor('1', cursor)
    storeExecutionTimelineRecovery(userFixture.id, '1', cursor, {
      '2': createPendingExecutionTimeline({
        messageId: '2', runId, startedAt: now,
      }),
    })
    let eventStreamReads = 0
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) {
        return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
      }
      if (url === '/api/v1/conversations/1' && init?.method === 'GET') return ok(current)
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
        return ok({
          items: [message('2', 'ASSISTANT', { text: '最终回复' })],
          nextCursor: null,
        })
      }
      if (url === '/api/v1/conversations/1/events') {
        eventStreamReads += 1
        expect((init?.headers as Headers).get('Last-Event-ID')).toBe('7')
        return sseResponse(8, 'message.completed', {
          messageId: '2', status: 'COMPLETED',
        }, '2')
      }
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([`${APP_PATHS.chat}?conversation=1`])

    expect(await screen.findByText('最终回复')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '停止当前回复' })).not.toBeInTheDocument()
    expect(eventStreamReads).toBe(0)
  })

  it('刷新已完成会话从安全缓存恢复折叠动态且不空转连接', async () => {
    const current = conversation()
    const cursor = { lastEventId: '2', lastSequence: 2 }
    storeConversationEventCursor('1', cursor)
    let timelines = applyConversationEventToTimelines(
      {},
      {
        id: '1',
        event: 'message.start',
        data: {
          eventId: '101', sequence: 1, occurredAt: now,
          conversationId: '1', messageId: '2', taskId: null,
          payload: { runId: crypto.randomUUID() },
        },
      },
    )
    timelines = applyConversationEventToTimelines(
      timelines,
      {
        id: '2',
        event: 'message.completed',
        data: {
          eventId: '102', sequence: 2, occurredAt: now,
          conversationId: '1', messageId: '2', taskId: null,
          payload: { status: 'COMPLETED' },
        },
      },
    )
    storeExecutionTimelineRecovery(userFixture.id, '1', cursor, timelines)

    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) {
        return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
      }
      if (url === '/api/v1/conversations/1' && init?.method === 'GET') {
        return ok(current)
      }
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
        return ok({
          items: [message('2', 'ASSISTANT', { text: '最终回复' })],
          nextCursor: null,
        })
      }
      if (url === '/api/v1/conversations/1/events') {
        throw new Error('已完成且缓存完整的会话不应空转 SSE')
      }
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([`${APP_PATHS.chat}?conversation=1`])

    expect(await screen.findByText('最终回复')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '展开执行动态' })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
    expect(
      fetchMock.mock.calls.some(([url]) =>
        String(url).endsWith('/api/v1/conversations/1/events'),
      ),
    ).toBe(false)
  })

  it('服务端失败业务结果覆盖旧技术完成缓存，不重开 SSE 或伪装成功', async () => {
    const current = conversation()
    const cursor = { lastEventId: '2', lastSequence: 2 }
    storeExecutionTimelineRecovery(userFixture.id, '1', cursor, applyConversationEventToTimelines({}, {
      id: '2', event: 'message.completed', data: {
        eventId: '102', sequence: 2, occurredAt: now, conversationId: '1', messageId: '2', taskId: null,
        payload: { status: 'COMPLETED' },
      },
    }))
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
      if (url === '/api/v1/conversations/1' && init?.method === 'GET') return ok(current)
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) return ok({
        items: [message('2', 'ASSISTANT', { text: '暂时无法完成这次查询，请重试。', businessOutcome: { schemaVersion: 1, status: 'FAILED', reasonCode: 'READONLY_SYNTHESIS_UNAVAILABLE', failureStage: 'SYNTHESIS' } })], nextCursor: null,
      })
      if (url === '/api/v1/conversations/1/events') throw new Error('终态快照不应重开 SSE')
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([`${APP_PATHS.chat}?conversation=1`])
    expect(await screen.findByText('暂时无法完成这次查询，请重试。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '收起执行动态' })).toHaveTextContent(/本次回复未完成/)
    expect(screen.queryByText(/本次回复已完成|本次回复已结束|READONLY_SYNTHESIS_UNAVAILABLE/)).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/events'))).toBe(false)
  })

  it('无动态缓存时直接读服务端快照，不回放已完成正文也不永久重连', async () => {
    const current = conversation()
    const runId = '2d521b70-c46e-468e-b619-3cff005549ae'
    let eventStreamReads = 0
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) {
        return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
      }
      if (url === '/api/v1/conversations/1' && init?.method === 'GET') {
        return ok(current)
      }
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
        return ok({
          items: [message('2', 'ASSISTANT', { text: '最终回复' })],
          nextCursor: null,
        })
      }
      if (url === '/api/v1/conversations/1/events') {
        eventStreamReads += 1
        const started = sseResponse(1, 'message.start', {
          messageId: '2', runId, status: 'RUNNING',
        }, '2')
        const progress = sseResponse(2, 'agent.progress', {
          messageId: '2', runId,
          stageCode: 'COMPOSING_RESPONSE', status: 'SUCCEEDED',
          ordinal: 1, elapsedMs: 500, current: null, total: null,
        }, '2')
        const delta = sseResponse(3, 'message.delta', {
          messageId: '2', delta: '最终',
        }, '2')
        const completed = sseResponse(4, 'message.completed', {
          messageId: '2', status: 'COMPLETED',
        }, '2')
        return new Response(
          `${await started.text()}${await progress.text()}${await delta.text()}${await completed.text()}`,
          { headers: { 'Content-Type': 'text/event-stream' } },
        )
      }
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([`${APP_PATHS.chat}?conversation=1`])

    expect(await screen.findByText('最终回复')).toBeInTheDocument()
    await new Promise((resolve) => window.setTimeout(resolve, 700))
    expect(eventStreamReads).toBe(0)
    expect(screen.getAllByText('最终回复')).toHaveLength(1)
  })

  it('任务确认期间双击只请求一次，完成后不再显示确认入口', async () => {
    const user = userEvent.setup()
    const current = conversation()
    const taskDraft = {
      id: '41',
      draftSource: 'CHAT',
      taskType: 'IMAGE',
      parameterSchemaVersion: 1,
      status: 'WAITING_CONFIRMATION',
      spaceId: '21',
      productId: null,
      sourceMode: 'CATALOG_PRODUCT',
      referenceAssetIds: [],
      parameters: {
        requestText: '生成真实图片',
        mode: 'AGENT_ASSISTED',
        agentSummary: '图片方案',
        sourceMode: 'CATALOG_PRODUCT',
        referenceAssetIds: [],
        aspectRatio: '1:1',
        count: 1,
      },
      warnings: [],
      inputHash: 'a'.repeat(64),
      pricingVersionId: '51',
      quotedPoints: 20,
      confirmationToken: 'opaque-confirmation-token',
      expiresAt: '2099-01-01T00:00:00Z',
      createdAt: now,
    }
    const accepted = {
      taskId: '61',
      status: 'QUEUED',
      quotedPoints: 20,
      frozenPoints: 20,
      createdAt: now,
    }
    const completedTask = {
      id: '61',
      taskType: 'IMAGE',
      status: 'SUCCEEDED',
      stageCode: 'SUCCEEDED',
      stageLabel: '图片生成完成',
      spaceId: '21',
      productId: null,
      quotedPoints: 20,
      settledPoints: 20,
      frozenPoints: 0,
      result: {},
      errorCode: null,
      createdAt: now,
      updatedAt: now,
    }
    let resolveConfirm!: (response: Response) => void
    const pendingConfirm = new Promise<Response>((resolve) => {
      resolveConfirm = resolve
    })
    const confirmationKeys: string[] = []
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) {
        return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
      }
      if (url === '/api/v1/conversations/1' && init?.method === 'GET') return ok(current)
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
        return ok({
          items: [message('2', 'ASSISTANT', { text: '请确认方案', taskDraft: { id: '41' } })],
          nextCursor: null,
        })
      }
      if (url === '/api/v1/task-drafts/41') return ok(taskDraft)
      if (url === '/api/v1/task-drafts/41/confirm' && init?.method === 'POST') {
        confirmationKeys.push(
          (init.headers as Headers).get('Idempotency-Key') ?? '',
        )
        return pendingConfirm
      }
      if (url === '/api/v1/tasks/61') return ok(completedTask)
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([`${APP_PATHS.chat}?conversation=1`])

    const confirmButton = await screen.findByRole('button', {
      name: /确认开始/,
    }, { timeout: 5_000 })
    await user.dblClick(confirmButton)
    await waitFor(() => expect(confirmationKeys).toHaveLength(1))
    resolveConfirm(ok(accepted, 202))

    await screen.findByText('任务已创建')
    expect(screen.queryByRole('button', { name: /确认开始/ })).not.toBeInTheDocument()
    expect(confirmationKeys[0]).toMatch(/^[0-9a-f-]{36}$/)
  }, 10_000)

  it('活动确认草稿首次恢复失败时自动兜底重试且只展示一张确认卡', async () => {
    const current = {
      ...conversation(),
      activeConfirmationDraftId: '41',
    }
    const taskDraft = {
      id: '41',
      draftSource: 'CHAT',
      taskType: 'IMAGE',
      parameterSchemaVersion: 1,
      status: 'WAITING_CONFIRMATION',
      spaceId: '21',
      productId: null,
      sourceMode: 'CATALOG_PRODUCT',
      referenceAssetIds: [],
      parameters: {
        requestText: '生成真实图片',
        mode: 'AGENT_ASSISTED',
        agentSummary: '图片方案',
        sourceMode: 'CATALOG_PRODUCT',
        referenceAssetIds: [],
        aspectRatio: '1:1',
        count: 1,
      },
      warnings: [],
      inputHash: 'a'.repeat(64),
      pricingVersionId: '51',
      quotedPoints: 20,
      confirmationToken: 'opaque-confirmation-token',
      expiresAt: '2099-01-01T00:00:00Z',
      createdAt: now,
    }
    storeChatRecoveryState('1', {
      drafts: [{ messageId: 'stale-recovery-message', resourceId: '41' }],
      tasks: [],
      confirmationAttempts: [],
    })
    let draftReads = 0
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) {
        return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
      }
      if (url === '/api/v1/conversations/1') return ok(current)
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
        return ok({ items: [], nextCursor: null })
      }
      if (url === '/api/v1/task-drafts/41') {
        draftReads += 1
        return draftReads === 1
          ? apiFailure(503, '草稿服务暂不可用')
          : ok(taskDraft)
      }
      if (url === '/api/v1/conversations/1/events') {
        return sseCompleted(1)
      }
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([`${APP_PATHS.chat}?conversation=1`])

    expect(
      await screen.findByRole('button', { name: /确认开始/ }, { timeout: 4_000 }),
    ).toBeInTheDocument()
    expect(draftReads).toBeGreaterThanOrEqual(2)
    expect(screen.getAllByLabelText(/付费任务确认卡/)).toHaveLength(1)
    expect(screen.queryByText(/草稿服务暂不可用/)).not.toBeInTheDocument()
  }, 10_000)

  it('后台 GEO 终态保留原任务卡，并在后续聊天之后独立投递且按 taskId 去重', async () => {
    const current = conversation()
    const task = {
      id: '61',
      taskType: 'GEO',
      status: 'SUCCEEDED',
      stageCode: 'SUCCEEDED',
      stageLabel: '监测完成',
      spaceId: '21',
      productId: null,
      quotedPoints: 30,
      settledPoints: 24,
      frozenPoints: 0,
      progress: { percent: 100, completedUnits: 6, totalUnits: 6 },
      result: {
        runId: '71',
        reportId: '81',
        status: 'SUCCEEDED',
        expectedUnits: 6,
        succeededUnits: 6,
        failedUnits: 0,
        missingUnits: 0,
        settledPoints: 24,
        releasedPoints: 6,
      },
      errorCode: null,
      createdAt: '2026-09-04T10:00:30Z',
      updatedAt: '2026-09-04T10:04:00Z',
    }
    const history = [
      {
        ...message('2', 'ASSISTANT', { text: '监测任务已经提交。' }),
        createdAt: '2026-09-04T10:00:00Z',
        updatedAt: '2026-09-04T10:00:00Z',
      },
      {
        ...message('3', 'USER', { text: '监测时顺便聊聊新品。' }),
        createdAt: '2026-09-04T10:02:00Z',
        updatedAt: '2026-09-04T10:02:00Z',
      },
      {
        ...message('4', 'ASSISTANT', { text: '当然可以继续聊天。' }),
        createdAt: '2026-09-04T10:03:00Z',
        updatedAt: '2026-09-04T10:03:00Z',
      },
    ]
    storeChatRecoveryState('1', {
      drafts: [],
      tasks: [{ messageId: '2', resourceId: task.id }],
      confirmationAttempts: [],
    })
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) {
        return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
      }
      if (url === '/api/v1/conversations/1') return ok(current)
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
        return ok({ items: history, nextCursor: null })
      }
      if (url.startsWith('/api/v1/tasks?conversationId=1')) {
        return ok({ items: [task], page: 1, size: 100, total: 1, hasNext: false })
      }
      if (url === '/api/v1/tasks/61') return ok(task)
      if (url === '/api/v1/geo/reports/81') {
        return apiFailure(404, '报告详情稍后可用')
      }
      if (url === '/api/v1/conversations/1/events') {
        return sseResponse(1, 'task.completed', {
          taskId: task.id,
          status: task.status,
          deliveryKind: 'BACKGROUND_TASK',
        }, null, new Date().toISOString())
      }
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([`${APP_PATHS.chat}?conversation=1`])

    const notification = await screen.findByLabelText('后台任务通知：GEO 监测已完成')
    expect(screen.getAllByLabelText('后台任务通知：GEO 监测已完成')).toHaveLength(1)
    expect(notification).toHaveTextContent(
      '执行期间的其他对话不会影响这份结果',
    )
    expect(
      within(notification).getByLabelText('任务执行结果与积分结算'),
    ).toBeInTheDocument()

    const sourceMessage = screen
      .getByText('监测任务已经提交。')
      .closest('article')
    expect(sourceMessage).not.toBeNull()
    expect(
      within(sourceMessage as HTMLElement).getByLabelText(
        '并行任务组：任务执行状态',
      ),
    ).toHaveTextContent('已完成')
    expect(
      within(sourceMessage as HTMLElement).queryByLabelText(
        '任务执行结果与积分结算',
      ),
    ).not.toBeInTheDocument()

    const latestReply = screen.getByText('当然可以继续聊天。').closest('article')
    expect(latestReply).not.toBeNull()
    expect(
      (latestReply as HTMLElement).compareDocumentPosition(notification) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0)
    expect(screen.getByRole('textbox', { name: '消息输入' })).toBeEnabled()
    expect(
      within(screen.getByRole('region', { name: '任务状态总览' })).getByRole(
        'button',
        { name: /GEO 监测.*已完成/ },
      ),
    ).toBeEnabled()

    await waitFor(() => {
      const recovery = JSON.parse(
        window.localStorage.getItem('zhixiaohe.chat.recovery.1') ?? '{}',
      ) as { tasks?: Array<{ messageId: string; resourceId: string }> }
      expect(
        recovery.tasks?.filter(
          (link) =>
            link.resourceId === task.id &&
            link.messageId === `background-task-notification:${task.id}`,
        ),
      ).toHaveLength(1)
    })
  })

  it('本地恢复记录缺失但游标存在时，从会话任务列表权威重建后台结果', async () => {
    const current = conversation()
    const task = {
      id: '62',
      taskType: 'GEO',
      status: 'PARTIAL',
      stageCode: 'PARTIAL',
      stageLabel: '监测部分完成',
      spaceId: '21',
      productId: null,
      quotedPoints: 30,
      settledPoints: 18,
      frozenPoints: 0,
      progress: { percent: 100, completedUnits: 5, totalUnits: 6 },
      result: {
        runId: '72',
        reportId: '82',
        status: 'PARTIAL',
        expectedUnits: 6,
        succeededUnits: 5,
        failedUnits: 1,
        missingUnits: 0,
        settledPoints: 18,
        releasedPoints: 12,
      },
      errorCode: null,
      createdAt: '2026-09-04T09:58:00Z',
      updatedAt: '2026-09-04T10:01:00Z',
    }
    const cursor = { lastEventId: '9', lastSequence: 9 }
    storeConversationEventCursor('1', cursor)
    storeExecutionTimelineRecovery(userFixture.id, '1', cursor, {})
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url.startsWith('/api/v1/conversations?page=')) {
        return ok({ items: [current], page: 1, size: 100, total: 1, hasNext: false })
      }
      if (url === '/api/v1/conversations/1') return ok(current)
      if (url.startsWith('/api/v1/conversations/1/snapshot?')) {
        return ok({
          items: [
            {
              ...message('3', 'USER', { text: '继续聊当前问题。' }),
              createdAt: '2026-09-04T10:00:00Z',
              updatedAt: '2026-09-04T10:00:00Z',
            },
          ],
          nextCursor: null,
        })
      }
      if (url.startsWith('/api/v1/tasks?conversationId=1')) {
        return ok({ items: [task], page: 1, size: 100, total: 1, hasNext: false })
      }
      if (url === '/api/v1/conversations/1/events') {
        throw new Error('终态任务已由权威列表恢复，不应依赖旧游标重放')
      }
      return baseRouteResponse(url)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderApp([`${APP_PATHS.chat}?conversation=1`])

    const notification = await screen.findByLabelText(
      '后台任务通知：GEO 监测部分完成',
      {},
      { timeout: 5_000 },
    )
    expect(notification).toHaveTextContent('成功 5 个')
    expect(
      screen.queryByRole('status', {
        name: '后台任务通知：GEO 监测部分完成',
      }),
    ).not.toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some(([url]) =>
        String(url).startsWith('/api/v1/tasks?conversationId=1'),
      ),
    ).toBe(true)
    expect(
      fetchMock.mock.calls.some(
        ([url]) => String(url) === '/api/v1/conversations/1/events',
      ),
    ).toBe(false)

    const recovery = JSON.parse(
      window.localStorage.getItem('zhixiaohe.chat.recovery.1') ?? '{}',
    ) as { tasks?: Array<{ messageId: string; resourceId: string }> }
    expect(recovery.tasks).toEqual(
      expect.arrayContaining([
        { messageId: `task-event-${task.id}`, resourceId: task.id },
        {
          messageId: `background-task-notification:${task.id}`,
          resourceId: task.id,
        },
      ]),
    )
  })

})

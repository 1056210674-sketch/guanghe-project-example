import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiRequest, apiStreamRequest } from './client'
import type { BrowserTraceContext } from './browserTrace'
import { captureAuthSession } from '../auth/tokenStorage'

export type Conversation = components['schemas']['Conversation']
export type ConversationMessage = components['schemas']['ConversationMessage']
export type ConversationMessageGeoContext = components['schemas']['ConversationMessageGeoContext']
export type ConversationBusinessOutcome = components['schemas']['ConversationBusinessOutcome']
export type ConversationResourceSelectionRequest = components['schemas']['ConversationResourceSelectionRequest']
export type ConversationEvent = components['schemas']['ConversationEvent']
export type AgentProgressPayload = components['schemas']['AgentProgressPayload']
export type CreateConversationRequest =
  components['schemas']['CreateConversationRequest']
export type UpdateConversationRequest =
  components['schemas']['UpdateConversationRequest']
export type SendConversationMessageRequest =
  components['schemas']['SendConversationMessageRequest']
export type MessageAccepted = components['schemas']['MessageAccepted']
export type ConversationSnapshot = components['schemas']['ConversationSnapshot']
export type ConversationMessagePage = components['schemas']['ConversationMessagePage']
export type StopAccepted = components['schemas']['StopAccepted']
export type TaskDraft = components['schemas']['TaskDraft']
export type CreateTaskDraftRequest =
  components['schemas']['CreateTaskDraftRequest']
export type ReviseTaskDraftRequest =
  components['schemas']['ReviseTaskDraftRequest']
export type ConfirmTaskDraftRequest =
  components['schemas']['ConfirmTaskDraftRequest']
export type ConfirmTaskResponse = components['schemas']['ConfirmTaskResponse']
export type PaidTask = components['schemas']['PaidTask']
export type PaidTaskPage = components['schemas']['PaidTaskPage']
export type PaidTaskEvent = components['schemas']['PaidTaskEvent']
export type PaidTaskAssetReference =
  components['schemas']['PaidTaskAssetReference']
export type PaidVideoTaskAssetReference =
  components['schemas']['PaidVideoTaskAssetReference']
export type PaidTaskResultAssetReference =
  | PaidTaskAssetReference
  | PaidVideoTaskAssetReference
export type PointAccount = components['schemas']['PointAccount']

export type ConversationListQuery = NonNullable<
  operations['listConversations']['parameters']['query']
>
export type ConversationMessageListQuery = NonNullable<
  operations['listConversationMessages']['parameters']['query']
>
export type PaidTaskListQuery = NonNullable<
  operations['listPaidTasks']['parameters']['query']
>

type ConversationPageApiResponse =
  components['schemas']['ConversationPageApiResponse']
type ConversationApiResponse =
  components['schemas']['ConversationApiResponse']
type ConversationMessagePageApiResponse =
  components['schemas']['ConversationMessagePageApiResponse']
type MessageAcceptedApiResponse =
  components['schemas']['MessageAcceptedApiResponse']
type StopAcceptedApiResponse =
  components['schemas']['StopAcceptedApiResponse']
type TaskDraftApiResponse = components['schemas']['TaskDraftApiResponse']
type ConfirmTaskApiResponse = components['schemas']['ConfirmTaskApiResponse']
type PaidTaskApiResponse = components['schemas']['PaidTaskApiResponse']
type PaidTaskPageApiResponse = components['schemas']['PaidTaskPageApiResponse']
type PaidTaskEventsApiResponse =
  components['schemas']['PaidTaskEventsApiResponse']
type PointAccountApiResponse = components['schemas']['PointAccountApiResponse']
type EmptySuccessApiResponse = components['schemas']['EmptySuccessApiResponse']

const PAGE_SIZE = 100

function encodedId(id: string) {
  return encodeURIComponent(id)
}

function requireData<T>(data: T | undefined, resource: string): T {
  if (data === undefined) {
    throw new ApiError({
      status: 200,
      code: 'INVALID_RESPONSE',
      message: `${resource}返回了无法识别的数据。`,
    })
  }
  return data
}

export async function listAllConversations(): Promise<Conversation[]> {
  const session = captureAuthSession()
  const conversations: Conversation[] = []
  let page = 1

  while (true) {
    const search = new URLSearchParams({
      page: String(page),
      size: String(PAGE_SIZE),
    })
    const response = await apiRequest<ConversationPageApiResponse>(
      `/api/v1/conversations?${search.toString()}`,
      { method: 'GET', auth: 'required', session },
    )
    conversations.push(...(response.data.items ?? []))
    if (!response.data.hasNext) {
      return conversations
    }
    page = response.data.page + 1
  }
}

export async function createConversation(
  request: CreateConversationRequest = {},
  browserTrace?: BrowserTraceContext,
): Promise<Conversation> {
  const response = await apiRequest<ConversationApiResponse>(
    '/api/v1/conversations',
    { method: 'POST', auth: 'required', body: request, browserTrace },
  )
  return response.data
}

export async function getConversation(
  conversationId: string,
): Promise<Conversation> {
  const response = await apiRequest<ConversationApiResponse>(
    `/api/v1/conversations/${encodedId(conversationId)}`,
    { method: 'GET', auth: 'required' },
  )
  return response.data
}

export async function updateConversation(
  conversationId: string,
  request: UpdateConversationRequest,
): Promise<Conversation> {
  const response = await apiRequest<ConversationApiResponse>(
    `/api/v1/conversations/${encodedId(conversationId)}`,
    { method: 'PATCH', auth: 'required', body: request },
  )
  return response.data
}

export async function deleteConversation(
  conversationId: string,
): Promise<void> {
  await apiRequest<EmptySuccessApiResponse>(
    `/api/v1/conversations/${encodedId(conversationId)}`,
    { method: 'DELETE', auth: 'required' },
  )
}

export async function listAllConversationMessages(
  conversationId: string,
): Promise<ConversationMessage[]> {
  const session = captureAuthSession()
  const messages: ConversationMessage[] = []
  let cursor: string | undefined

  while (true) {
    const search = new URLSearchParams({ limit: String(PAGE_SIZE) })
    if (cursor !== undefined) {
      search.set('cursor', cursor)
    }
    const response = await apiRequest<ConversationMessagePageApiResponse>(
      `/api/v1/conversations/${encodedId(conversationId)}/messages?${search.toString()}`,
      { method: 'GET', auth: 'required', session },
    )
    messages.push(...response.data.items)
    if (response.data.nextCursor === null) {
      return messages
    }
    cursor = response.data.nextCursor
  }
}

/** One bounded page. New chat views start from a snapshot, then explicitly load older pages. */
export async function listConversationMessages(
  conversationId: string,
  query: ConversationMessageListQuery = {},
): Promise<ConversationMessagePage> {
  const search = new URLSearchParams({ limit: String(query.limit ?? 50) })
  if (query.beforeSequence !== undefined) search.set('beforeSequence', String(query.beforeSequence))
  if (query.cursor !== undefined) search.set('cursor', String(query.cursor))
  const response = await apiRequest<ConversationMessagePageApiResponse>(
    `/api/v1/conversations/${encodedId(conversationId)}/messages?${search.toString()}`,
    { method: 'GET', auth: 'required' },
  )
  return response.data
}

export async function getConversationSnapshot(
  conversationId: string,
  limit = 50,
): Promise<ConversationSnapshot> {
  const response = await apiRequest<components['schemas']['ConversationSnapshotApiResponse']>(
    `/api/v1/conversations/${encodedId(conversationId)}/snapshot?limit=${limit}`,
    { method: 'GET', auth: 'required' },
  )
  return response.data
}

export async function getConversationMessageOperation(
  conversationId: string,
  clientMessageId: string,
): Promise<MessageAccepted> {
  const response = await apiRequest<MessageAcceptedApiResponse>(
    `/api/v1/conversations/${encodedId(conversationId)}/message-operations/${encodedId(clientMessageId)}`,
    { method: 'GET', auth: 'required' },
  )
  return response.data
}

export async function sendConversationMessage(
  conversationId: string,
  request: SendConversationMessageRequest,
  idempotencyKey: string,
  browserTrace?: BrowserTraceContext,
): Promise<MessageAccepted> {
  const response = await apiRequest<MessageAcceptedApiResponse>(
    `/api/v1/conversations/${encodedId(conversationId)}/messages`,
    {
      method: 'POST',
      auth: 'required',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: request,
      browserTrace,
    },
  )
  return response.data
}

export async function stopConversationStream(
  conversationId: string,
  expectedRunId: string,
): Promise<StopAccepted> {
  const response = await apiRequest<StopAcceptedApiResponse>(
    `/api/v1/conversations/${encodedId(conversationId)}/stream-actions/stop`,
    { method: 'POST', auth: 'required', body: { expectedRunId } },
  )
  return response.data
}

export function openConversationEventStream(
  conversationId: string,
  lastEventId: string | undefined,
  signal: AbortSignal,
  browserTrace?: BrowserTraceContext,
): Promise<Response> {
  const headers = new Headers()
  if (lastEventId !== undefined) {
    headers.set('Last-Event-ID', lastEventId)
  }
  return apiStreamRequest(
    `/api/v1/conversations/${encodedId(conversationId)}/events`,
    { method: 'GET', auth: 'required', headers, signal, browserTrace },
  )
}

export async function getTaskDraft(draftId: string): Promise<TaskDraft> {
  const response = await apiRequest<TaskDraftApiResponse>(
    `/api/v1/task-drafts/${encodedId(draftId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '任务草稿')
}

export async function createTaskDraft(
  request: CreateTaskDraftRequest,
  idempotencyKey: string,
  browserTrace?: BrowserTraceContext,
): Promise<TaskDraft> {
  const response = await apiRequest<TaskDraftApiResponse>('/api/v1/task-drafts', {
    method: 'POST',
    auth: 'required',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: request,
    browserTrace,
  })
  return requireData(response.data, '任务草稿')
}

export async function reviseTaskDraft(
  draftId: string,
  request: ReviseTaskDraftRequest,
  idempotencyKey: string,
  browserTrace?: BrowserTraceContext,
): Promise<TaskDraft> {
  const response = await apiRequest<TaskDraftApiResponse>(
    `/api/v1/task-drafts/${encodedId(draftId)}/revisions`,
    {
      method: 'POST',
      auth: 'required',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: request,
      browserTrace,
    },
  )
  return requireData(response.data, '任务草稿修订')
}

export async function abandonTaskDraft(draftId: string): Promise<TaskDraft> {
  const response = await apiRequest<TaskDraftApiResponse>(
    `/api/v1/task-drafts/${encodedId(draftId)}/abandon`,
    { method: 'POST', auth: 'required' },
  )
  return requireData(response.data, '任务草稿')
}

export async function confirmTaskDraft(
  draftId: string,
  request: ConfirmTaskDraftRequest,
  idempotencyKey: string,
  browserTrace?: BrowserTraceContext,
): Promise<ConfirmTaskResponse> {
  const response = await apiRequest<ConfirmTaskApiResponse>(
    `/api/v1/task-drafts/${encodedId(draftId)}/confirm`,
    {
      method: 'POST',
      auth: 'required',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: request,
      browserTrace,
    },
  )
  return requireData(response.data, '任务确认')
}

export async function getPaidTask(taskId: string): Promise<PaidTask> {
  const response = await apiRequest<PaidTaskApiResponse>(
    `/api/v1/tasks/${encodedId(taskId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '任务')
}

function paidTaskListPath(query: PaidTaskListQuery): string {
  const search = new URLSearchParams()
  if (query.taskType !== undefined) search.set('taskType', query.taskType)
  if (query.status !== undefined) search.set('status', query.status)
  if (query.conversationId !== undefined) {
    search.set('conversationId', query.conversationId)
  }
  if (query.spaceId !== undefined) search.set('spaceId', query.spaceId)
  if (query.productId !== undefined) search.set('productId', query.productId)
  if (query.page !== undefined) search.set('page', String(query.page))
  if (query.size !== undefined) search.set('size', String(query.size))
  const queryString = search.toString()
  return queryString ? `/api/v1/tasks?${queryString}` : '/api/v1/tasks'
}

export async function listPaidTasks(
  query: PaidTaskListQuery = {},
): Promise<PaidTaskPage> {
  const response = await apiRequest<PaidTaskPageApiResponse>(
    paidTaskListPath(query),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '任务列表')
}

export async function listPaidTaskEvents(
  taskId: string,
): Promise<PaidTaskEvent[]> {
  const response = await apiRequest<PaidTaskEventsApiResponse>(
    `/api/v1/tasks/${encodedId(taskId)}/events`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '任务事件')
}

export async function getPointAccount(): Promise<PointAccount> {
  const response = await apiRequest<PointAccountApiResponse>(
    '/api/v1/billing/point-account',
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '积分账户')
}

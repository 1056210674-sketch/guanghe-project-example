import { useCallback, useMemo } from 'react'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type InfiniteData } from '@tanstack/react-query'
import {
  createConversation,
  deleteConversation,
  getConversation,
  getPointAccount,
  getConversationSnapshot,
  listConversationMessages,
  listAllConversations,
  sendConversationMessage,
  stopConversationStream,
  updateConversation,
} from '../../../api/conversationApi'
import { useAuthStore } from '../../../stores/authStore'
import { captureAuthSession, isCurrentAuthSession } from '../../../auth/tokenStorage'
import type {
  ConversationMessagePage,
  ConversationSnapshot,
  CreateConversationRequest,
  SendConversationMessageRequest,
  UpdateConversationRequest,
} from '../../../api/conversationApi'
import type { BrowserTraceContext } from '../../../api/browserTrace'

const chatRootKey = ['chat'] as const

function keysFor(ownerId?: string | null) {
  const owner = [...chatRootKey, ownerId ?? null] as const
  return {
    owner,
    conversations: [...owner, 'conversations'] as const,
    conversation: (id?: string) => [...owner, 'conversation', id ?? null] as const,
    messages: (id?: string) => [...owner, 'messages', id ?? null] as const,
  }
}

function useChatKeys() {
  return keysFor(useAuthStore((state) => state.user?.id))
}

export function useConversations(enabled = true) {
  const keys = useChatKeys()
  const ownerId = useAuthStore((state) => state.user?.id)
  return useQuery({ queryKey: keys.conversations, queryFn: listAllConversations, enabled: enabled && Boolean(ownerId) })
}

export function useConversation(conversationId?: string) {
  const keys = useChatKeys()
  return useQuery({
    queryKey: keys.conversation(conversationId),
    queryFn: () => getConversation(conversationId as string),
    enabled: Boolean(conversationId),
  })
}

export function useConversationMessages(conversationId?: string) {
  const keys = useChatKeys()
  const ownerId = useAuthStore((state) => state.user?.id)
  const queryClient = useQueryClient()
  const query = useInfiniteQuery({
    queryKey: keys.messages(conversationId),
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) => {
      if (pageParam === undefined) {
        const snapshot = await getConversationSnapshot(conversationId as string, 50)
        return { items: snapshot.messages, nextCursor: snapshot.nextBeforeSequence, snapshot }
      }
      const beforeSequence = Number(pageParam)
      if (!Number.isSafeInteger(beforeSequence) || beforeSequence < 1) throw new Error('历史消息游标无效，请重新打开对话。')
      const page = await listConversationMessages(conversationId as string, { beforeSequence, limit: 50 })
      return { ...page, snapshot: undefined }
    },
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled: Boolean(conversationId),
  })
  const data = useMemo(() => query.data
    // The newest snapshot is authoritative if a historical page overlaps it.
    ? [...new Map([...query.data.pages].reverse().flatMap((page) => page.items).map((message) => [message.id, message])).values()]
      .sort((left, right) => left.sequence - right.sequence)
    : undefined, [query.data])
  const refreshResourceSnapshot = useCallback(async () => {
    if (!conversationId || !ownerId) return
    const session = captureAuthSession()
    const queryKey = keysFor(ownerId).messages(conversationId)
    const stillCurrent = () => isCurrentAuthSession(session) && useAuthStore.getState().user?.id === ownerId
    // Cancel an older refresh so it cannot overwrite a newly committed choice.
    await queryClient.cancelQueries({ queryKey, exact: true })
    if (!stillCurrent()) return
    const snapshot = await getConversationSnapshot(conversationId, 50)
    if (!stillCurrent()) return
    queryClient.setQueryData<InfiniteData<ConversationMessagePage & { snapshot?: ConversationSnapshot }, string | undefined>>(queryKey, (current) => {
      if (!current) return undefined
      const first = current.pages[0]
      if (!first || (first.snapshot?.highWatermark ?? 0) > snapshot.highWatermark) return current
      // Preserve already loaded messages and their oldest paging boundary. A
      // resource event only refreshes latest state, not every historical page.
      const items = [...new Map([...first.items, ...snapshot.messages].map((message) => [message.id, message])).values()]
        .sort((left, right) => left.sequence - right.sequence)
      return { ...current, pages: [{ ...first, items, snapshot }, ...current.pages.slice(1)] }
    })
  }, [conversationId, ownerId, queryClient])
  return { ...query, data, snapshot: query.data?.pages[0]?.snapshot, refreshResourceSnapshot }
}

export function usePointAccount(enabled: boolean) {
  const keys = useChatKeys()
  return useQuery({
    queryKey: [...keys.owner, 'point-account'],
    queryFn: getPointAccount,
    enabled,
  })
}

export function useCreateConversation() {
  const queryClient = useQueryClient()
  const keys = useChatKeys()
  return useMutation({
    mutationFn: ({
      request = {},
      browserTrace,
    }: {
      request?: CreateConversationRequest
      browserTrace?: BrowserTraceContext
    } = {}) => createConversation(request, browserTrace),
    onSuccess: async (conversation) => {
      queryClient.setQueryData(keys.conversation(conversation.id), conversation)
      await queryClient.invalidateQueries({ queryKey: keys.conversations })
    },
  })
}

export function useUpdateConversation() {
  const queryClient = useQueryClient()
  const keys = useChatKeys()
  return useMutation({
    mutationFn: ({
      conversationId,
      request,
    }: {
      conversationId: string
      request: UpdateConversationRequest
    }) => updateConversation(conversationId, request),
    onSuccess: async (conversation) => {
      queryClient.setQueryData(keys.conversation(conversation.id), conversation)
      await queryClient.invalidateQueries({ queryKey: keys.conversations })
    },
  })
}

export function useDeleteConversation() {
  const queryClient = useQueryClient()
  const keys = useChatKeys()
  return useMutation({
    mutationFn: deleteConversation,
    onSuccess: async (_, conversationId) => {
      queryClient.removeQueries({ queryKey: keys.conversation(conversationId) })
      queryClient.removeQueries({ queryKey: keys.messages(conversationId) })
      await queryClient.invalidateQueries({ queryKey: keys.conversations })
    },
  })
}

export function useSendConversationMessage() {
  const queryClient = useQueryClient()
  const keys = useChatKeys()
  return useMutation({
    mutationFn: ({
      conversationId,
      request,
      idempotencyKey,
      browserTrace,
    }: {
      conversationId: string
      request: SendConversationMessageRequest
      idempotencyKey: string
      browserTrace?: BrowserTraceContext
    }) =>
      sendConversationMessage(
        conversationId,
        request,
        idempotencyKey,
        browserTrace,
      ),
    onSuccess: async (_, { conversationId }) => {
      const snapshotRefresh = queryClient.invalidateQueries({ queryKey: keys.messages(conversationId) })
      // Reconcile the accepted run with its snapshot, without letting auxiliary
      // metadata refreshes keep an otherwise completed send pending.
      void queryClient.invalidateQueries({ queryKey: keys.conversations })
      void queryClient.invalidateQueries({ queryKey: keys.conversation(conversationId) })
      await snapshotRefresh
    },
  })
}

export function useStopConversationStream() {
  const queryClient = useQueryClient()
  const keys = useChatKeys()
  return useMutation({
    mutationFn: ({ conversationId, expectedRunId }: { conversationId: string; expectedRunId: string }) => stopConversationStream(conversationId, expectedRunId),
    onSuccess: async (_, { conversationId }) => {
      await queryClient.invalidateQueries({ queryKey: keys.messages(conversationId) })
    },
  })
}

export { keysFor as chatKeysFor }

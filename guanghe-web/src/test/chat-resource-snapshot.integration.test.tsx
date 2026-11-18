import { QueryClientProvider, type InfiniteData } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { PropsWithChildren } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConversationMessage, ConversationMessagePage, ConversationSnapshot } from '../api/conversationApi'
import { createQueryClient } from '../app/queryClient'
import { storeAuthToken } from '../auth/tokenStorage'
import { chatKeysFor, useConversationMessages } from '../features/chat/live/chatQueries'
import { useAuthStore } from '../stores/authStore'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'

const now = '2026-10-05T08:00:00Z'
type CachedMessages = InfiniteData<ConversationMessagePage & { snapshot?: ConversationSnapshot }, string | undefined>
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data }), { headers: { 'Content-Type': 'application/json' } })
function message(id: string, text: string, available?: boolean): ConversationMessage {
  return { id, conversationId: '1', role: 'ASSISTANT', contentType: 'TEXT',
    content: { text, ...(available === undefined ? {} : { interaction: {
      interactionType: 'RESOURCE_SELECTION', intent: 'GENERAL', question: '选一个商品', missingFields: [], choices: ['咖啡'],
      candidateSetId: '901', available, candidates: [{ choiceId: 'c1', kind: 'PRODUCT', resourceId: '31', name: '咖啡', hasReport: false, exact: true }],
    } }) }, replyToMessageId: null, runId: null, attachmentAssetIds: [], sequence: Number(id), status: 'COMPLETED', createdAt: now, updatedAt: now }
}
function mountMessages() {
  const queryClient = createQueryClient()
  const wrapper = ({ children }: PropsWithChildren) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  return { queryClient, ...renderHook(() => useConversationMessages('1'), { wrapper }) }
}
function resourceAvailable(value?: ConversationMessage) {
  const interaction = value?.content.interaction
  return interaction?.interactionType === 'RESOURCE_SELECTION' ? interaction.available : undefined
}

beforeEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
  sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('资源事件的有界快照刷新', () => {
  it('只查一次最新快照，保留历史消息和分页边界，重叠消息以新快照为准', async () => {
    const reads: string[] = []
    let refreshed = false
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      reads.push(url)
      if (url.includes('/snapshot?')) return ok({
        messages: refreshed ? [message('50', '选择已消费', false), message('60', '新读取的回答')] : [message('50', '选择可用', true), message('51', '此前已经加载的回复')],
        nextBeforeSequence: refreshed ? '60' : '50', highWatermark: refreshed ? 101 : 100, activeRun: null,
      })
      if (url.includes('beforeSequence=50')) return ok({ items: [message('1', '更早的消息'), message('50', '旧历史重复项', true)], nextCursor: '1' })
      if (url.includes('beforeSequence=1')) return ok({ items: [], nextCursor: null })
      throw new Error(`Unexpected request: ${url}`)
    }))
    const mounted = mountMessages()
    await waitFor(() => expect(mounted.result.current.data).toHaveLength(2))
    await act(async () => { await mounted.result.current.fetchNextPage() })
    expect(mounted.result.current.data?.find(({ id }) => id === '50')?.content.text).toBe('选择可用')
    refreshed = true
    const before = reads.length
    await act(async () => { await mounted.result.current.refreshResourceSnapshot() })
    expect(reads.slice(before)).toEqual(['/api/v1/conversations/1/snapshot?limit=50'])
    expect(mounted.result.current.data?.map(({ id }) => id)).toEqual(['1', '50', '51', '60'])
    expect(resourceAvailable(mounted.result.current.data?.find(({ id }) => id === '50'))).toBe(false)
    expect(mounted.result.current.snapshot?.highWatermark).toBe(101)
    const cached = mounted.queryClient.getQueryData<CachedMessages>(chatKeysFor(userFixture.id).messages('1'))
    expect(cached?.pageParams).toEqual([undefined, '50'])
    expect(cached?.pages[0].nextCursor).toBe('50')
    expect(cached?.pages[1].nextCursor).toBe('1')
    await act(async () => { await mounted.result.current.fetchNextPage() })
    expect(reads.at(-1)).toContain('beforeSequence=1')
  })

  it('迟到旧快照不能把已验证消费的卡片重新开启', async () => {
    let resolveRefresh!: (value: Response) => void
    const delayed = new Promise<Response>((resolve) => { resolveRefresh = resolve })
    let snapshotReads = 0
    const snapshot = { messages: [message('50', '选择可用', true)], nextBeforeSequence: '50', highWatermark: 100, activeRun: null }
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => ++snapshotReads === 1 ? ok(snapshot) : delayed))
    const mounted = mountMessages()
    await waitFor(() => expect(mounted.result.current.data).toHaveLength(1))
    const refreshing = mounted.result.current.refreshResourceSnapshot()
    await waitFor(() => expect(snapshotReads).toBe(2))
    act(() => mounted.queryClient.setQueryData<CachedMessages>(chatKeysFor(userFixture.id).messages('1'), (current) => current && ({
      ...current, pages: [{ ...current.pages[0], items: [message('50', '已验证消费', false)],
        snapshot: { ...snapshot, messages: [message('50', '已验证消费', false)], highWatermark: 102 } }],
    })))
    await act(async () => { resolveRefresh(ok(snapshot)); await refreshing })
    expect(mounted.queryClient.getQueryData<CachedMessages>(chatKeysFor(userFixture.id).messages('1'))?.pages[0].snapshot?.highWatermark).toBe(102)
    await waitFor(() => expect(mounted.result.current.snapshot?.highWatermark).toBe(102))
    expect(resourceAvailable(mounted.result.current.data?.[0])).toBe(false)
  })

  it('换账号后的迟到资源快照不写入旧缓存或新用户缓存', async () => {
    let resolveRefresh!: (value: Response) => void
    const delayed = new Promise<Response>((resolve) => { resolveRefresh = resolve })
    let snapshotReads = 0
    const snapshot = { messages: [message('50', '原账号消息', true)], nextBeforeSequence: null, highWatermark: 100, activeRun: null }
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (_input, init) => {
      if ((init?.headers as Headers).get('Authorization')?.includes('other-test-owner')) return ok({ ...snapshot, messages: [], highWatermark: 0 })
      return ++snapshotReads === 1 ? ok(snapshot) : delayed
    }))
    const mounted = mountMessages()
    await waitFor(() => expect(mounted.result.current.data).toHaveLength(1))
    const refreshing = mounted.result.current.refreshResourceSnapshot()
    const rejected = expect(refreshing).rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
    await waitFor(() => expect(snapshotReads).toBe(2))
    act(() => {
      storeAuthToken({ accessToken: 'other-test-owner', expiresAt: testExpiresAt })
      useAuthStore.setState({ status: 'authenticated', user: { ...userFixture, id: '1009' }, error: null })
    })
    await act(async () => { resolveRefresh(ok({ ...snapshot, messages: [message('50', '不能回写的旧消息', false)], highWatermark: 101 })); await rejected })
    await waitFor(() => expect(mounted.result.current.data).toEqual([]))
    expect(mounted.queryClient.getQueryData<CachedMessages>(chatKeysFor(userFixture.id).messages('1'))?.pages[0].snapshot?.highWatermark).toBe(100)
    expect(mounted.queryClient.getQueryData<CachedMessages>(chatKeysFor('1009').messages('1'))?.pages[0].items).toEqual([])
  })
})

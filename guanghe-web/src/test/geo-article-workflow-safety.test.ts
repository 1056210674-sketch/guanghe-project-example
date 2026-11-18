import { beforeEach, describe, expect, it, vi } from 'vitest'
import { storeAuthToken } from '../auth/tokenStorage'
import { abandonGeoArticleSourceDraft, prepareGeoArticleWorkflow } from '../features/geo/geoArticleWorkflow'
import { readArticleTaskRecovery } from '../features/articles/live/articleRecovery'
import { testAccessToken, testExpiresAt, testTraceId } from './fixtures/auth'

const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: testTraceId }), { headers: { 'Content-Type': 'application/json' } })
const workflow = { workflowId: '92', reportId: '90', articleId: '91', draftId: '92', conversationId: '1', reused: false, draft: { id: '92' } }

beforeEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
})

describe('GEO 文章草稿恢复边界', () => {
  it('方向与冻结读取引用进入幂等身份，相同输入重试沿用键，变更不复用旧请求', async () => {
    const writes: Array<{ body: unknown; key: string | null }> = []
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (_path, init) => {
      if (init?.method === 'POST') {
        writes.push({ body: JSON.parse(String(init.body)), key: new Headers(init.headers).get('Idempotency-Key') })
        return ok(workflow)
      }
      return ok({ id: '92', status: 'WAITING_CONFIRMATION', expiresAt: '2099-01-01T00:00:00Z' })
    }))
    const input = { ownerId: '7', reportId: '90', conversationId: '1', observationId: '81', direction: '  面向新顾客介绍使用场景  ' }
    await prepareGeoArticleWorkflow(input)
    await prepareGeoArticleWorkflow(input)
    await prepareGeoArticleWorkflow({ ...input, direction: '介绍办公室选购思路' })
    await prepareGeoArticleWorkflow({ ...input, direction: '介绍办公室选购思路', observationId: '82' })
    expect(writes[0].body).toEqual({ conversationId: '1', suggestionId: null, observationId: '81', direction: '面向新顾客介绍使用场景' })
    expect(writes[1]).toEqual(writes[0])
    expect(new Set(writes.map(({ key }) => key)).size).toBe(3)
  })

  it('超长方向在提交前拒绝，不创建草稿或改写恢复槽', async () => {
    const fetchMock = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', fetchMock)
    await expect(prepareGeoArticleWorkflow({ ownerId: '7', reportId: '90', direction: '字'.repeat(801) })).rejects.toThrow('800 字以内')
    expect(fetchMock).not.toHaveBeenCalled()
    expect(readArticleTaskRecovery('7')).toEqual({})
  })

  it('放弃回执丢失后只接受 GET 已证实的终态，不猜测旧确认是否失效', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (path, init) => {
      calls.push(`${init?.method ?? 'GET'} ${String(path)}`)
      if (init?.method === 'POST') throw new TypeError('acknowledgement lost')
      return ok({ id: '92', status: 'ABANDONED' })
    }))
    expect(await abandonGeoArticleSourceDraft('92', () => true)).toMatchObject({ id: '92', status: 'ABANDONED' })
    expect(calls).toEqual(['POST /api/v1/task-drafts/92/abandon', 'GET /api/v1/task-drafts/92'])
  })

  it.each(['WAITING_CONFIRMATION', 'CONFIRMED'])('放弃结果仍是 %s 时停止，不声明原稿已被替换', async (status) => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (_path, init) => {
      if (init?.method === 'POST') throw new TypeError('acknowledgement lost')
      return ok({ id: '92', status })
    }))
    await expect(abandonGeoArticleSourceDraft('92', () => true)).rejects.toThrow('尚未确认原方案已失效')
  })

  it('离开当前资料后不再发起放弃请求', async () => {
    const fetchMock = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', fetchMock)
    await expect(abandonGeoArticleSourceDraft('92', () => false)).rejects.toThrow('已切换')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('查询上次草稿期间离开，不继续 POST 或改写恢复槽', async () => {
    let active = true
    let release!: (response: Response) => void
    const fetchMock = vi.fn<typeof fetch>(async (_path, init) => init?.method === 'POST'
      ? ok(workflow) : new Promise<Response>((resolve) => { release = resolve }))
    vi.stubGlobal('fetch', fetchMock)
    const input = { ownerId: '7', reportId: '90', conversationId: '1', isCurrent: () => active }
    await prepareGeoArticleWorkflow(input)
    const previous = readArticleTaskRecovery('7')
    const attempt = prepareGeoArticleWorkflow(input)
    const rejected = expect(attempt).rejects.toThrow('本次资料或会话已切换')
    active = false
    release(ok({ id: '92', status: 'WAITING_CONFIRMATION', expiresAt: '2099-01-01T00:00:00Z' }))
    await rejected
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    expect(readArticleTaskRecovery('7')).toEqual(previous)
  })
})

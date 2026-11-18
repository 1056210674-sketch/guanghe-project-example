import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/apiError'
import {
  createBrowserRequestTrace,
  createBrowserTraceContext,
} from '../api/browserTrace'
import { apiRequest, apiStreamRequest } from '../api/client'

const TRACEPARENT = /^00-([0-9a-f]{32})-([0-9a-f]{16})-(00|01)$/

function success(data: unknown = null) {
  return new Response(
    JSON.stringify({
      code: 'OK',
      message: 'success',
      data,
      traceId: 'a'.repeat(32),
    }),
    { status: 200 },
  )
}

function requestHeaders(fetchMock: ReturnType<typeof vi.fn>, index = 0) {
  return fetchMock.mock.calls[index]?.[1]?.headers as Headers
}

beforeEach(() => {
  vi.unstubAllGlobals()
})

describe('W3C Browser root trace context', () => {
  it('生成非零小写 trace-id、span-id 和合法采样标志', () => {
    const sampled = createBrowserRequestTrace(
      createBrowserTraceContext({ sampled: true }),
    )
    const unsampled = createBrowserRequestTrace(
      createBrowserTraceContext({ sampled: false }),
    )

    for (const request of [sampled, unsampled]) {
      const match = request.traceparent.match(TRACEPARENT)
      expect(match).not.toBeNull()
      expect(match?.[1]).not.toBe('0'.repeat(32))
      expect(match?.[2]).not.toBe('0'.repeat(16))
    }
    expect(sampled.traceparent.endsWith('-01')).toBe(true)
    expect(unsampled.traceparent.endsWith('-00')).toBe(true)
  })

  it('同一操作共享 trace-id，但每个 HTTP 请求使用不同 span-id', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async () => success())
    vi.stubGlobal('fetch', fetchMock)
    const context = createBrowserTraceContext({ sampled: true })

    await apiRequest('/api/v1/public/registration-config', {
      method: 'GET',
      browserTrace: context,
    })
    await apiRequest('/api/v1/public/registration-config', {
      method: 'GET',
      browserTrace: context,
    })

    const first = requestHeaders(fetchMock, 0).get('traceparent') ?? ''
    const second = requestHeaders(fetchMock, 1).get('traceparent') ?? ''
    expect(first.match(TRACEPARENT)?.[1]).toBe(context.traceId)
    expect(second.match(TRACEPARENT)?.[1]).toBe(context.traceId)
    expect(first.match(TRACEPARENT)?.[2]).not.toBe(
      second.match(TRACEPARENT)?.[2],
    )
  })

  it('普通独立请求使用不同根 trace', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async () => success())
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/api/v1/public/registration-config')
    await apiRequest('/api/v1/public/registration-config')

    const first = requestHeaders(fetchMock, 0).get('traceparent') ?? ''
    const second = requestHeaders(fetchMock, 1).get('traceparent') ?? ''
    expect(first).toMatch(TRACEPARENT)
    expect(second).toMatch(TRACEPARENT)
    expect(first.match(TRACEPARENT)?.[1]).not.toBe(
      second.match(TRACEPARENT)?.[1],
    )
  })

  it('调用方不能覆盖 traceparent 或注入 tracestate', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(success())
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/api/v1/public/registration-config', {
      headers: {
        traceparent: `00-${'b'.repeat(32)}-${'c'.repeat(16)}-01`,
        tracestate: 'vendor=untrusted',
      },
    })

    const headers = requestHeaders(fetchMock)
    expect(headers.get('traceparent')).toMatch(TRACEPARENT)
    expect(headers.get('traceparent')).not.toContain('b'.repeat(32))
    expect(headers.has('tracestate')).toBe(false)
  })

  it('JSON 与 SSE 请求都携带 traceparent，并保留幂等键', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(success())
      .mockResolvedValueOnce(new Response('', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const idempotencyKey = crypto.randomUUID()

    await apiRequest('/api/v1/task-drafts', {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: {},
    })
    await apiStreamRequest('/api/v1/conversations/1/events')

    expect(requestHeaders(fetchMock, 0).get('traceparent')).toMatch(TRACEPARENT)
    expect(requestHeaders(fetchMock, 1).get('traceparent')).toMatch(TRACEPARENT)
    expect(requestHeaders(fetchMock, 0).get('Idempotency-Key')).toBe(
      idempotencyKey,
    )
  })

  it('无响应网络错误只记录 clientTraceId，不伪装服务端 traceId', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockRejectedValue(new TypeError('offline')),
    )

    const error = await apiRequest('/api/v1/public/registration-config').catch(
      (caught: unknown) => caught,
    )

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      code: 'NETWORK_ERROR',
      traceId: undefined,
    })
    expect((error as ApiError).clientTraceId).toMatch(/^[0-9a-f]{32}$/)
  })

  it('有响应错误始终保留 Java traceId，不用浏览器 trace 覆盖', async () => {
    const serverTraceId = 'd'.repeat(32)
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: 'VALIDATION_ERROR',
            message: '参数错误',
            details: {},
            traceId: serverTraceId,
          }),
          { status: 422 },
        ),
      ),
    )

    const error = await apiRequest('/api/v1/public/registration-config', {
      browserTrace: createBrowserTraceContext({ sampled: true }),
    }).catch((caught: unknown) => caught)

    expect(error).toMatchObject({
      status: 422,
      traceId: serverTraceId,
      clientTraceId: undefined,
    })
    expect(requestHeaders(vi.mocked(fetch), 0).get('traceparent')).toMatch(TRACEPARENT)
  })

})

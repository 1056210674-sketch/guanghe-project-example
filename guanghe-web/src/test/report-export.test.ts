import { beforeEach, describe, expect, it, vi } from 'vitest'
import { exportGeoReport } from '../api/geoApi'
import { captureAuthSession, storeAuthToken } from '../auth/tokenStorage'
import { testAccessToken, testExpiresAt, testTraceId } from './fixtures/auth'

beforeEach(() => { vi.unstubAllGlobals(); localStorage.clear(); storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt }) })
describe('完整报告真实导出接口', () => {
  it.each([['md', 'text/markdown;charset=UTF-8', '# 小行星监测\n没有有效回答不视为未提及'], ['pdf', 'application/pdf', '%PDF-1.7\nreport']] as const)('%s 下载同一保存报告，不另做分析', async (format, contentType, body) => {
    const fetch = vi.fn(async () => new Response(body, { headers: { 'Content-Type': contentType } })); vi.stubGlobal('fetch', fetch)
    const file = await exportGeoReport('501', format)
    expect(await file.text()).toBe(body)
    expect(fetch.mock.calls).toHaveLength(1)
    const [url, init] = (fetch.mock.calls[0] as unknown as Parameters<typeof globalThis.fetch>)
    expect(url).toBe(`/api/v1/geo/reports/501/export?format=${format}`)
    expect(init?.method).toBe('GET'); expect(new Headers(init?.headers).get('Authorization')).toBe(`Bearer ${testAccessToken}`)
  })
  it('服务错误、空文件和错误格式不变成成功下载', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ code: 'DENIED', message: '没有权限', traceId: testTraceId }), { status: 403, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response('', { headers: { 'Content-Type': 'application/pdf' } }))
      .mockResolvedValueOnce(new Response('<html>登录页</html>', { headers: { 'Content-Type': 'text/html' } }))
    vi.stubGlobal('fetch', fetch)
    await expect(exportGeoReport('501', 'pdf')).rejects.toMatchObject({ status: 403 })
    await expect(exportGeoReport('501', 'pdf')).rejects.toMatchObject({ code: 'EMPTY_DOWNLOAD' })
    await expect(exportGeoReport('501', 'pdf')).rejects.toMatchObject({ code: 'INVALID_DOWNLOAD' })
  })
  it('下载中换登录态不交付前一个账号的文件', async () => {
    const old = captureAuthSession(); vi.stubGlobal('fetch', vi.fn(async () => { storeAuthToken({ accessToken: 'a-new-test-login', expiresAt: testExpiresAt }); return new Response('%PDF-1.7', { headers: { 'Content-Type': 'application/pdf' } }) }))
    await expect(exportGeoReport('501', 'pdf')).rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' }); expect(captureAuthSession().revision).not.toBe(old.revision)
  })
})

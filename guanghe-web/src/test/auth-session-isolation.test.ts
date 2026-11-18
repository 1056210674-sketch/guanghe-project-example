import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest, apiStreamRequest } from '../api/client'
import { restoreAuthSession } from '../auth/authSession'
import { signOutAndRedirectToLogin } from '../auth/logoutNavigation'
import { captureAuthSession, clearStoredAuthToken, isCurrentAuthSession, readStoredAuthToken, storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { adminFixture, apiErrorResponse, jsonResponse, meResponse, testExpiresAt, userFixture } from './fixtures/auth'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function loginAs(accessToken: string) {
  storeAuthToken({ accessToken, expiresAt: testExpiresAt })
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  clearStoredAuthToken()
  useAuthStore.getState().setAnonymous()
  loginAs('account-a-test-only')
})

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('authenticated response ownership', () => {
  it('does not send a payload prepared by A after the browser changes account before fetch', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const body = { toJSON() { loginAs('account-b-test-only'); return { content: 'account A draft' } } }
    await expect(apiRequest('/api/v1/conversations/test/messages', { method: 'POST', auth: 'required', body }))
      .rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
    expect(fetchMock).not.toHaveBeenCalled()
    expect(readStoredAuthToken()?.accessToken).toBe('account-b-test-only')
  })

  it.each([200, 401])('discards account A late HTTP %i without expiring account B', async (status) => {
    const pending = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(pending.promise))
    const request = apiRequest('/api/v1/auth/me', { auth: 'required' })
    const assertion = expect(request).rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
    loginAs('account-b-test-only')
    pending.resolve(status === 200 ? jsonResponse(meResponse(adminFixture)) : apiErrorResponse(401, 'expired'))
    await assertion
    expect(readStoredAuthToken()?.accessToken).toBe('account-b-test-only')
  })

  it('checks identity again after a slow response body', async () => {
    const response = jsonResponse(meResponse(adminFixture))
    const body = deferred<string>()
    const started = deferred<void>()
    vi.spyOn(response, 'text').mockImplementation(() => { started.resolve(); return body.promise })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    const request = apiRequest('/api/v1/auth/me', { auth: 'required' })
    const assertion = expect(request).rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
    await started.promise
    loginAs('account-b-test-only')
    body.resolve(JSON.stringify(meResponse(adminFixture)))
    await assertion
  })

  it('does not restore an old user over a new login', async () => {
    const pending = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(pending.promise))
    const restoring = restoreAuthSession()
    loginAs('account-b-test-only')
    useAuthStore.getState().setAuthenticated(userFixture)
    pending.resolve(jsonResponse(meResponse(adminFixture)))
    expect(await restoring).toBeNull()
    expect(useAuthStore.getState().user).toEqual(userFixture)
  })

  it('does not let a slow logout clear or redirect a newer login', async () => {
    const pending = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(pending.promise))
    const replace = vi.fn()
    const exiting = signOutAndRedirectToLogin(replace)
    loginAs('account-b-test-only')
    useAuthStore.getState().setAuthenticated(userFixture)
    pending.resolve(jsonResponse(meResponse(adminFixture)))
    await exiting
    expect(readStoredAuthToken()?.accessToken).toBe('account-b-test-only')
    expect(replace).not.toHaveBeenCalled()
  })

  it('fences logout/login even when a token is reissued unchanged', () => {
    const identity = captureAuthSession()
    clearStoredAuthToken()
    loginAs('account-a-test-only')
    expect(isCurrentAuthSession(identity)).toBe(false)
  })

  it('drops buffered SSE text as soon as the account changes', async () => {
    const cancelled = vi.fn()
    const upstream = new ReadableStream<Uint8Array>({
      start(controller) { controller.enqueue(new TextEncoder().encode('private account A text')) },
      cancel: cancelled,
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(upstream)))
    const response = await apiStreamRequest('/api/v1/conversations/test/events', { auth: 'required' })
    // Allow the wrapper to buffer a chunk before anyone consumes it.
    await Promise.resolve()
    loginAs('account-b-test-only')
    await expect(response.body!.getReader().read()).rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
    expect(cancelled).toHaveBeenCalledOnce()
  })

  it('prevents a paginated operation from continuing with a newer account token', async () => {
    const session = captureAuthSession()
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(meResponse(userFixture)))
    vi.stubGlobal('fetch', fetchMock)
    await apiRequest('/api/v1/conversations?page=1', { auth: 'required', session })
    loginAs('account-b-test-only')
    await expect(apiRequest('/api/v1/conversations?page=2', { auth: 'required', session }))
      .rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('still accepts the current user response and expires a current invalid token', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(jsonResponse(meResponse(userFixture)))
      .mockResolvedValueOnce(apiErrorResponse(401, 'expired')))
    await expect(apiRequest('/api/v1/auth/me', { auth: 'required' })).resolves.toEqual(meResponse(userFixture))
    await expect(apiRequest('/api/v1/auth/me', { auth: 'required' })).rejects.toMatchObject({ status: 401 })
    expect(readStoredAuthToken()).toBeNull()
  })
})

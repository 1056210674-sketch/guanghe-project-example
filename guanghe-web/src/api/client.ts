import { ApiError } from './apiError'
import { resolveApiUrl } from './config'
import {
  clearStoredAuthToken,
  isStoredAuthTokenExpired,
  captureAuthSession,
  isCurrentAuthSession,
  subscribeToAuthSessionChanges,
  type AuthSessionSnapshot,
} from '../auth/tokenStorage'
import { signalSessionExpired } from '../auth/sessionEvents'
import {
  createBrowserRequestTrace,
  type BrowserTraceContext,
} from './browserTrace'

type AuthMode = 'none' | 'required'
type UnauthorizedMode = 'expire-session' | 'propagate'

export interface ApiRequestOptions
  extends Omit<RequestInit, 'body' | 'headers'> {
  auth?: AuthMode
  body?: unknown
  headers?: HeadersInit
  unauthorized?: UnauthorizedMode
  browserTrace?: BrowserTraceContext
  /** Keep one identity across paginated/chained requests; never use a new login. */
  session?: AuthSessionSnapshot
}

export interface ApiStreamRequestOptions
  extends Omit<ApiRequestOptions, 'body'> {
  headers?: HeadersInit
}

export interface ApiMultipartUploadOptions {
  formData: FormData
  headers?: HeadersInit
  browserTrace?: BrowserTraceContext
  onUploadProgress?: (percent: number) => void
}

interface ErrorPayload {
  code?: unknown
  message?: unknown
  details?: unknown
  traceId?: unknown
}

interface SuccessPayload {
  code: 'OK'
  message: 'success'
  data: unknown
  traceId: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isSuccessPayload(value: unknown): value is SuccessPayload {
  return (
    isRecord(value) &&
    value.code === 'OK' &&
    value.message === 'success' &&
    'data' in value &&
    typeof value.traceId === 'string' &&
    /^[0-9a-f]{32}$/.test(value.traceId)
  )
}

async function parseJsonSafely(response: Response): Promise<unknown> {
  const body = await response.text()
  if (!body) {
    return undefined
  }

  try {
    return JSON.parse(body) as unknown
  } catch {
    return undefined
  }
}

function toApiError(response: { status: number }, payload: unknown): ApiError {
  const errorPayload: ErrorPayload = isRecord(payload) ? payload : {}
  const details = isRecord(errorPayload.details) ? errorPayload.details : {}

  return new ApiError({
    status: response.status,
    code:
      typeof errorPayload.code === 'string'
        ? errorPayload.code
        : `HTTP_${response.status}`,
    message:
      typeof errorPayload.message === 'string'
        ? errorPayload.message
        : '请求失败，请稍后重试。',
    details,
    traceId:
      typeof errorPayload.traceId === 'string'
        ? errorPayload.traceId
        : undefined,
  })
}

function requireSessionAccessToken(session: AuthSessionSnapshot): string {
  assertSessionCurrent(session)
  if (!session.accessToken || !session.expiresAt) {
    throw new ApiError({
      status: 401,
      code: 'AUTH_REQUIRED',
      message: '请先登录。',
    })
  }

  if (isStoredAuthTokenExpired({ accessToken: session.accessToken, expiresAt: session.expiresAt })) {
    assertSessionCurrent(session)
    clearStoredAuthToken()
    signalSessionExpired('local-expiry')
    throw new ApiError({
      status: 401,
      code: 'AUTH_SESSION_EXPIRED',
      message: '登录状态已过期，请重新登录。',
    })
  }

  return session.accessToken
}

function assertSessionCurrent(session?: AuthSessionSnapshot): void {
  if (session && !isCurrentAuthSession(session)) {
    throw new ApiError({
      status: 0,
      code: 'AUTH_SESSION_CHANGED',
      message: '登录状态已变更，请重新操作。',
    })
  }
}

async function requestResponse(
  path: string,
  {
    auth = 'none',
    body,
    headers: initialHeaders,
    unauthorized = 'expire-session',
    browserTrace,
    session = auth === 'required' ? captureAuthSession() : undefined,
    ...requestInit
  }: ApiRequestOptions,
  accept: string,
): Promise<Response> {
  assertSessionCurrent(session)
  const headers = new Headers(initialHeaders)
  headers.delete('Authorization')
  headers.delete('traceparent')
  headers.delete('tracestate')
  for (const headerName of headers.keys()) {
    if (headerName.toLowerCase().startsWith('x-internal-')) {
      throw new Error('Internal service headers are not allowed in browser requests.')
    }
  }
  headers.set('Accept', accept)
  const requestTrace = createBrowserRequestTrace(browserTrace)
  headers.set('traceparent', requestTrace.traceparent)

  if (body !== undefined) {
    headers.set('Content-Type', 'application/json')
  }

  if (auth === 'required') {
    headers.set('Authorization', `Bearer ${requireSessionAccessToken(session!)}`)
  }

  let response: Response
  try {
    const url = resolveApiUrl(path)
    const serializedBody = body === undefined ? undefined : JSON.stringify(body)
    assertSessionCurrent(session)
    response = await fetch(url, {
      ...requestInit,
      body: serializedBody,
      headers,
    })
  } catch (error) {
    assertSessionCurrent(session)
    if (error instanceof ApiError) {
      throw error
    }

    throw new ApiError({
      status: 0,
      code: 'NETWORK_ERROR',
      message: '网络连接失败，请检查网络后重试。',
      clientTraceId: requestTrace.context.traceId,
    })
  }

  assertSessionCurrent(session)

  if (!response.ok) {
    const payload = await parseJsonSafely(response)
    assertSessionCurrent(session)
    if (auth === 'required' && response.status === 401 && unauthorized === 'expire-session') {
      clearStoredAuthToken()
      signalSessionExpired('unauthorized')
    }

    throw toApiError(response, payload)
  }

  return response
}

export async function apiRequest<TResponse>(
  path: string,
  {
    auth = 'none',
    body,
    headers: initialHeaders,
    unauthorized = 'expire-session',
    browserTrace,
    session = auth === 'required' ? captureAuthSession() : undefined,
    ...requestInit
  }: ApiRequestOptions = {},
): Promise<TResponse> {
  const response = await requestResponse(
    path,
    {
      auth,
      body,
      headers: initialHeaders,
      unauthorized,
      browserTrace,
      session,
      ...requestInit,
    },
    'application/json',
  )
  const payload = await parseJsonSafely(response)
  assertSessionCurrent(session)

  if (!isSuccessPayload(payload)) {
    throw new ApiError({
      status: response.status,
      code: 'INVALID_RESPONSE',
      message: '服务返回了无法识别的响应。',
    })
  }

  return payload as TResponse
}

export async function apiStreamRequest(
  path: string,
  options: ApiStreamRequestOptions = {},
): Promise<Response> {
  const session = options.session ?? (options.auth === 'required' ? captureAuthSession() : undefined)
  const response = await requestResponse(path, { ...options, session }, 'text/event-stream')
  assertSessionCurrent(session)
  if (!response.body || !session) return response
  const reader = response.body.getReader()
  let closed = false
  let unsubscribe = () => {}
  const finish = () => {
    closed = true
    unsubscribe()
  }
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      unsubscribe = subscribeToAuthSessionChanges(() => {
        if (closed || isCurrentAuthSession(session)) return
        finish()
        // Erroring the wrapper also discards already-buffered chunks, unlike
        // cancelling only the upstream reader after an account switch.
        try { assertSessionCurrent(session) } catch (error) { controller.error(error) }
        void reader.cancel().catch(() => undefined)
      })
    },
    async pull(controller) {
      if (closed) return
      try {
        assertSessionCurrent(session)
        const result = await reader.read()
        if (closed) return
        assertSessionCurrent(session)
        if (result.done) {
          finish()
          controller.close()
        } else {
          controller.enqueue(result.value)
        }
      } catch (error) {
        if (closed) return
        finish()
        void reader.cancel().catch(() => undefined)
        controller.error(error)
      }
    },
    async cancel(reason) {
      finish()
      await reader.cancel(reason)
    },
  })
  return new Response(body, { status: response.status, statusText: response.statusText, headers: response.headers })
}

/** Authenticated download uses the same session, errors and Java-only URL policy. */
export async function apiDownloadRequest(
  path: string,
  contentType: string,
  options: ApiRequestOptions = {},
): Promise<Blob> {
  const session = options.session ?? captureAuthSession()
  const response = await requestResponse(path, { ...options, method: 'GET', auth: 'required', session }, contentType)
  if (!response.headers.get('Content-Type')?.toLowerCase().startsWith(contentType.toLowerCase())) {
    throw new ApiError({ status: response.status, code: 'INVALID_DOWNLOAD', message: '下载文件的格式不正确，请重试。' })
  }
  const blob = await response.blob()
  assertSessionCurrent(session)
  if (blob.size === 0) throw new ApiError({ status: response.status, code: 'EMPTY_DOWNLOAD', message: '下载文件为空，请重试。' })
  return blob
}

export function apiMultipartUpload<TResponse>(
  path: string,
  {
    formData,
    headers: initialHeaders,
    browserTrace,
    onUploadProgress,
  }: ApiMultipartUploadOptions,
): Promise<TResponse> {
  const headers = new Headers(initialHeaders)
  headers.delete('Authorization')
  headers.delete('Content-Type')
  headers.delete('traceparent')
  headers.delete('tracestate')
  for (const headerName of headers.keys()) {
    if (headerName.toLowerCase().startsWith('x-internal-')) {
      throw new Error('Internal service headers are not allowed in browser requests.')
    }
  }
  const requestTrace = createBrowserRequestTrace(browserTrace)
  const session = captureAuthSession()
  const accessToken = requireSessionAccessToken(session)

  return new Promise<TResponse>((resolve, reject) => {
    const request = new XMLHttpRequest()
    request.open('POST', resolveApiUrl(path))
    request.responseType = 'text'
    request.setRequestHeader('Accept', 'application/json')
    request.setRequestHeader('Authorization', `Bearer ${accessToken}`)
    request.setRequestHeader('traceparent', requestTrace.traceparent)
    headers.forEach((value, name) => request.setRequestHeader(name, value))

    request.upload.onprogress = (event) => {
      if (!isCurrentAuthSession(session)) return
      if (!event.lengthComputable || event.total <= 0) return
      onUploadProgress?.(
        Math.max(0, Math.min(100, Math.round((event.loaded / event.total) * 100))),
      )
    }
    request.onerror = () => {
      if (!isCurrentAuthSession(session)) {
        try { assertSessionCurrent(session) } catch (error) { reject(error) }
        return
      }
      reject(new ApiError({
        status: 0,
        code: 'NETWORK_ERROR',
        message: '网络连接失败，请检查网络后重试。',
        clientTraceId: requestTrace.context.traceId,
      }))
    }
    request.onload = () => {
      try { assertSessionCurrent(session) } catch (error) { reject(error); return }
      let payload: unknown
      try {
        payload = request.responseText
          ? JSON.parse(request.responseText) as unknown
          : undefined
      } catch {
        payload = undefined
      }
      if (request.status < 200 || request.status >= 300) {
        if (request.status === 401) {
          clearStoredAuthToken()
          signalSessionExpired('unauthorized')
        }
        reject(toApiError(request, payload))
        return
      }
      if (!isSuccessPayload(payload)) {
        reject(new ApiError({
          status: request.status,
          code: 'INVALID_RESPONSE',
          message: '服务返回了无法识别的响应。',
        }))
        return
      }
      onUploadProgress?.(100)
      resolve(payload as TResponse)
    }
    assertSessionCurrent(session)
    request.send(formData)
  })
}

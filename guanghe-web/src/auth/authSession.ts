import {
  getCurrentUser,
  completeFirstLogin as requestCompleteFirstLogin,
  login as requestLogin,
  logout as requestLogout,
  type AuthUser,
  type LoginRequest,
  type CompleteFirstLoginRequest,
} from '../api/authApi'
import { ApiError, isApiError } from '../api/apiError'
import { useAuthStore } from '../stores/authStore'
import {
  clearStoredAuthToken,
  isStoredAuthTokenExpired,
  readStoredAuthToken,
  storeAuthToken,
  captureAuthSession,
  invalidateAuthSession,
  isCurrentAuthSession,
  isAuthStorageKey,
  subscribeToAuthSessionChanges,
  type AuthSessionSnapshot,
} from './tokenStorage'
import { subscribeToSessionExpired } from './sessionEvents'
import {
  clearFirstLoginActivation,
  setFirstLoginActivation,
  type FirstLoginActivationContext,
} from './activationSession'

let restoreInFlight: {
  identity: AuthSessionSnapshot
  promise: Promise<AuthUser | null>
} | null = null

function sessionChangedError(): ApiError {
  return new ApiError({ status: 0, code: 'AUTH_SESSION_CHANGED', message: '登录状态已变更，请重新操作。' })
}

function asApiError(error: unknown): ApiError {
  if (isApiError(error)) {
    return error
  }

  return new ApiError({
    status: 0,
    code: 'NETWORK_ERROR',
    message: '网络连接失败，请检查网络后重试。',
  })
}

export function installAuthSessionListener(onIdentityChange?: () => void): () => void {
  let observedIdentity = captureAuthSession()
  const unsubscribeIdentity = subscribeToAuthSessionChanges(() => {
    observedIdentity = captureAuthSession()
  })
  const unsubscribe = subscribeToSessionExpired(() => {
    useAuthStore.getState().setExpired()
  })
  // Read current storage rather than trusting possibly queued, stale event values.
  const onStorage = (event: StorageEvent) => {
    if (!isAuthStorageKey(event.key)) return
    // Several storage events can belong to the same login. Restore only the
    // latest identity once, not one request per key that was written.
    if (isCurrentAuthSession(observedIdentity)) return
    invalidateAuthSession()
    clearFirstLoginActivation()
    onIdentityChange?.()
    useAuthStore.getState().setAnonymous()
    void restoreAuthSession()
  }
  window.addEventListener('storage', onStorage)
  return () => {
    unsubscribe()
    unsubscribeIdentity()
    window.removeEventListener('storage', onStorage)
  }
}

export function restoreAuthSession(): Promise<AuthUser | null> {
  const currentState = useAuthStore.getState()
  if (currentState.status === 'authenticated' && currentState.user) {
    return Promise.resolve(currentState.user)
  }

  if (restoreInFlight && isCurrentAuthSession(restoreInFlight.identity)) {
    return restoreInFlight.promise
  }

  const identity = captureAuthSession()
  const promise = (async () => {
    const storedToken = readStoredAuthToken()
    if (!storedToken) {
      useAuthStore.getState().setAnonymous()
      return null
    }

    if (isStoredAuthTokenExpired(storedToken)) {
      clearStoredAuthToken()
      useAuthStore.getState().setExpired()
      return null
    }

    useAuthStore.getState().setRestoring()

    try {
      const user = await getCurrentUser()
      if (!isCurrentAuthSession(identity)) return null
      useAuthStore.getState().setAuthenticated(user)
      return user
    } catch (error) {
      if (!isCurrentAuthSession(identity)) return null
      const apiError = asApiError(error)
      if (apiError.status === 401) {
        clearStoredAuthToken()
        useAuthStore.getState().setExpired()
      } else {
        useAuthStore.getState().setError(apiError)
      }
      return null
    }
  })().finally(() => {
    if (restoreInFlight?.identity === identity) restoreInFlight = null
  })

  restoreInFlight = { identity, promise }
  return promise
}

export type SignInResult =
  | { kind: 'authenticated'; user: AuthUser }
  | { kind: 'activation'; context: FirstLoginActivationContext }

export async function signIn(request: LoginRequest): Promise<SignInResult> {
  invalidateAuthSession()
  const identity = captureAuthSession()
  useAuthStore.getState().setAuthenticating()

  try {
    const response = await requestLogin(request)
    if (!isCurrentAuthSession(identity)) throw sessionChangedError()
    if (response.tokenType === 'Activation') {
      clearStoredAuthToken()
      const context = setFirstLoginActivation(response)
      useAuthStore.getState().setAnonymous()
      return { kind: 'activation', context }
    }
    clearFirstLoginActivation()
    try {
      storeAuthToken({
        accessToken: response.accessToken,
        expiresAt: response.expiresAt,
      })
    } catch {
      // The synchronous storage write itself may invalidate this identity.
      useAuthStore.getState().setAnonymous()
      throw new ApiError({ status: 0, code: 'AUTH_STORAGE_UNAVAILABLE', message: '无法保存登录状态，请允许浏览器使用本站存储后重试。' })
    }
    useAuthStore.getState().setAuthenticated(response.user)
    return { kind: 'authenticated', user: response.user }
  } catch (error) {
    if (isCurrentAuthSession(identity)) useAuthStore.getState().setAnonymous()
    throw asApiError(error)
  }
}

export async function finishFirstLogin(
  request: CompleteFirstLoginRequest,
): Promise<AuthUser> {
  const identity = captureAuthSession()
  const response = await requestCompleteFirstLogin(request)
  if (!isCurrentAuthSession(identity)) throw sessionChangedError()
  if (response.tokenType !== 'Bearer') {
    clearFirstLoginActivation()
    throw new ApiError({
      status: 200,
      code: 'INVALID_RESPONSE',
      message: '服务返回了无法识别的登录状态。',
    })
  }
  storeAuthToken({ accessToken: response.accessToken, expiresAt: response.expiresAt })
  clearFirstLoginActivation()
  useAuthStore.getState().setAuthenticated(response.user)
  return response.user
}

export async function signOut(): Promise<void> {
  invalidateAuthSession()
  const identity = captureAuthSession()
  try {
    if (readStoredAuthToken()) {
      await requestLogout()
    }
  } finally {
    if (isCurrentAuthSession(identity)) {
      clearFirstLoginActivation()
      clearStoredAuthToken()
      useAuthStore.getState().setAnonymous()
    }
  }
}

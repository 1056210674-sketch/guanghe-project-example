import type { FirstLoginResponse } from '../api/authApi'

export interface FirstLoginActivationContext {
  activationToken: string
  expiresAt: string
  user: FirstLoginResponse['user']
  terms: FirstLoginResponse['terms']
  privacy: FirstLoginResponse['privacy']
  returnTarget?: string
}

const ACTIVATION_STORAGE_KEY = 'zhixiaohe.first-login-activation'
let activationContext: FirstLoginActivationContext | null = null

function sessionStorageOrNull(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage
  } catch {
    return null
  }
}

function isInternalTarget(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    value.startsWith('/') &&
    !value.startsWith('//') &&
    !value.includes('\\')
  )
}

function isActivationContext(value: unknown): value is FirstLoginActivationContext {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<FirstLoginActivationContext>
  const expiresAt = Date.parse(candidate.expiresAt ?? '')
  return (
    typeof candidate.activationToken === 'string' &&
    candidate.activationToken.length > 0 &&
    Number.isFinite(expiresAt) &&
    expiresAt > Date.now() &&
    Boolean(candidate.user && typeof candidate.user === 'object') &&
    Boolean(candidate.terms && typeof candidate.terms === 'object') &&
    Boolean(candidate.privacy && typeof candidate.privacy === 'object') &&
    (candidate.returnTarget === undefined ||
      isInternalTarget(candidate.returnTarget))
  )
}

function persistActivationContext(context: FirstLoginActivationContext): void {
  try {
    sessionStorageOrNull()?.setItem(ACTIVATION_STORAGE_KEY, JSON.stringify(context))
  } catch {
    // Memory fallback still keeps the current tab usable when storage is blocked.
  }
}

export function setFirstLoginActivation(response: FirstLoginResponse): FirstLoginActivationContext {
  activationContext = {
    activationToken: response.activationToken,
    expiresAt: response.expiresAt,
    user: response.user,
    terms: response.terms,
    privacy: response.privacy,
  }
  persistActivationContext(activationContext)
  return activationContext
}

export function readFirstLoginActivation(): FirstLoginActivationContext | null {
  const storage = sessionStorageOrNull()
  let serialized: string | null = null
  try {
    serialized = storage?.getItem(ACTIVATION_STORAGE_KEY) ?? null
  } catch {
    // Memory fallback still works when browser storage access is blocked.
  }
  if (serialized) {
    try {
      const stored: unknown = JSON.parse(serialized)
      if (isActivationContext(stored)) {
        activationContext = stored
        return stored
      }
    } catch {
      // Invalid storage is cleared below.
    }
    try {
      storage?.removeItem(ACTIVATION_STORAGE_KEY)
    } catch {
      // Storage cleanup is best effort.
    }
    activationContext = null
    return null
  }

  if (activationContext && !isActivationContext(activationContext)) {
    activationContext = null
  }
  return activationContext
}

export function setFirstLoginReturnTarget(target: string): void {
  if (!isInternalTarget(target)) return
  const context = readFirstLoginActivation()
  if (!context) return
  activationContext = { ...context, returnTarget: target }
  persistActivationContext(activationContext)
}

export function clearFirstLoginActivation(): void {
  activationContext = null
  try {
    sessionStorageOrNull()?.removeItem(ACTIVATION_STORAGE_KEY)
  } catch {
    // Storage cleanup is best effort.
  }
}

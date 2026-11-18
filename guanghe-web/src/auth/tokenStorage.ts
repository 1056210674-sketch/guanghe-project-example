export interface StoredAuthToken {
  accessToken: string
  expiresAt: string
}

/** Opaque request ownership. Never serialize this object or include it in logs. */
export interface AuthSessionSnapshot {
  readonly generation: number
  readonly accessToken: string | null
  readonly expiresAt: string | null
  readonly revision: string | null
}

const ACCESS_TOKEN_KEY = 'zhixiaohe.auth.access_token'
const EXPIRES_AT_KEY = 'zhixiaohe.auth.expires_at'
const REVISION_KEY = 'zhixiaohe.auth.revision'
export const AUTH_SESSION_CHANGED_EVENT = 'zhixiaohe:auth-session-changed'
let generation = 0

export function invalidateAuthSession(): void {
  generation += 1
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(AUTH_SESSION_CHANGED_EVENT))
  }
}

function readRevision(): string | null {
  try {
    return getStorage()?.getItem(REVISION_KEY) ?? null
  } catch {
    return null
  }
}

export function captureAuthSession(): AuthSessionSnapshot {
  const token = readStoredAuthToken()
  return {
    generation,
    accessToken: token?.accessToken ?? null,
    expiresAt: token?.expiresAt ?? null,
    revision: readRevision(),
  }
}

export function isCurrentAuthSession(snapshot: AuthSessionSnapshot): boolean {
  const current = captureAuthSession()
  return snapshot.generation === current.generation &&
    snapshot.accessToken === current.accessToken &&
    snapshot.expiresAt === current.expiresAt &&
    snapshot.revision === current.revision
}

export function isAuthStorageKey(key: string | null): boolean {
  return key === null || [ACCESS_TOKEN_KEY, EXPIRES_AT_KEY, REVISION_KEY].includes(key)
}

export function subscribeToAuthSessionChanges(listener: () => void): () => void {
  if (typeof window === 'undefined') return () => undefined
  window.addEventListener(AUTH_SESSION_CHANGED_EVENT, listener)
  return () => window.removeEventListener(AUTH_SESSION_CHANGED_EVENT, listener)
}

function getStorage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}

export function readStoredAuthToken(): StoredAuthToken | null {
  const storage = getStorage()
  if (!storage) {
    return null
  }

  try {
    const accessToken = storage.getItem(ACCESS_TOKEN_KEY)
    const expiresAt = storage.getItem(EXPIRES_AT_KEY)

    if (!accessToken?.trim() || !expiresAt?.trim()) {
      return null
    }

    return { accessToken, expiresAt }
  } catch {
    return null
  }
}

export function storeAuthToken(token: StoredAuthToken): void {
  const storage = getStorage()
  if (!storage) {
    throw new Error('Browser storage is unavailable.')
  }

  try {
    storage.setItem(ACCESS_TOKEN_KEY, token.accessToken)
    storage.setItem(EXPIRES_AT_KEY, token.expiresAt)
    storage.setItem(REVISION_KEY, crypto.randomUUID())
    invalidateAuthSession()
  } catch (error) {
    clearStoredAuthToken()
    throw error
  }
}

export function clearStoredAuthToken(): void {
  const storage = getStorage()
  if (!storage) {
    invalidateAuthSession()
    return
  }

  try {
    storage.removeItem(ACCESS_TOKEN_KEY)
    storage.removeItem(EXPIRES_AT_KEY)
    // A persistent revision also fences requests across a logout/login ABA race.
    storage.setItem(REVISION_KEY, crypto.randomUUID())
  } catch {
    // Storage can be unavailable in privacy-restricted contexts. There is no
    // safe recovery action and no secret is emitted to a log.
  }
  invalidateAuthSession()
}

export function isStoredAuthTokenExpired(
  token: StoredAuthToken,
  now = Date.now(),
): boolean {
  const expiresAt = Date.parse(token.expiresAt)
  return !Number.isFinite(expiresAt) || expiresAt <= now
}

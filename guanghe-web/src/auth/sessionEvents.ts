export const AUTH_SESSION_EXPIRED_EVENT = 'zhixiaohe:auth-session-expired'

export type SessionExpiryReason = 'local-expiry' | 'unauthorized'

export interface SessionExpiredEventDetail {
  reason: SessionExpiryReason
}

export function signalSessionExpired(reason: SessionExpiryReason): void {
  if (typeof window === 'undefined') {
    return
  }

  window.dispatchEvent(
    new CustomEvent<SessionExpiredEventDetail>(AUTH_SESSION_EXPIRED_EVENT, {
      detail: { reason },
    }),
  )
}

export function subscribeToSessionExpired(
  listener: (detail: SessionExpiredEventDetail) => void,
): () => void {
  if (typeof window === 'undefined') {
    return () => undefined
  }

  const handleEvent = (event: Event) => {
    listener((event as CustomEvent<SessionExpiredEventDetail>).detail)
  }

  window.addEventListener(AUTH_SESSION_EXPIRED_EVENT, handleEvent)
  return () => window.removeEventListener(AUTH_SESSION_EXPIRED_EVENT, handleEvent)
}

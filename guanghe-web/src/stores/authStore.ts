import { create } from 'zustand'
import type { AuthUser } from '../api/authApi'
import type { ApiError } from '../api/apiError'

export type AuthStatus =
  | 'unknown'
  | 'restoring'
  | 'authenticating'
  | 'authenticated'
  | 'anonymous'
  | 'expired'
  | 'error'

interface AuthState {
  status: AuthStatus
  user: AuthUser | null
  error: ApiError | null
  setRestoring: () => void
  setAuthenticating: () => void
  setAuthenticated: (user: AuthUser) => void
  setAnonymous: () => void
  setExpired: () => void
  setError: (error: ApiError) => void
}

export const useAuthStore = create<AuthState>((set) => ({
  status: 'unknown',
  user: null,
  error: null,
  setRestoring: () => set({ status: 'restoring', user: null, error: null }),
  setAuthenticating: () => set({ status: 'authenticating', user: null, error: null }),
  setAuthenticated: (user) =>
    set({ status: 'authenticated', user, error: null }),
  setAnonymous: () => set({ status: 'anonymous', user: null, error: null }),
  setExpired: () => set({ status: 'expired', user: null, error: null }),
  setError: (error) => set({ status: 'error', user: null, error }),
}))

/** Only an authenticated operator may see a diagnostic trace in the UI. */
export function canViewDiagnosticTrace(state: AuthState): boolean {
  return state.status === 'authenticated' && state.user?.role === 'ADMIN'
}

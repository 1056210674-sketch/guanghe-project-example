import { APP_PATHS } from '../routes/paths'
import { signOut } from './authSession'
import { readStoredAuthToken } from './tokenStorage'
import { useAuthStore } from '../stores/authStore'

/** Leave the current document so logout always opens the current deployed login page. */
export async function signOutAndRedirectToLogin(
  replace: (path: string) => void = (path) => window.location.replace(path),
): Promise<void> {
  try {
    await signOut()
  } catch {
    // signOut clears the local session in finally; server errors do not block exit.
  } finally {
    // A slow logout from A must not navigate away from a newer login for B.
    if (!readStoredAuthToken() && useAuthStore.getState().status !== 'authenticating') {
      replace(APP_PATHS.login)
    }
  }
}

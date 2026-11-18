import type { AuthUser } from '../api/authApi'
import { APP_PATHS } from './paths'

export type AuthRole = AuthUser['role']

export function roleHome(
  role: AuthRole,
): typeof APP_PATHS.userHome | typeof APP_PATHS.adminHome {
  return role === 'ADMIN' ? APP_PATHS.adminHome : APP_PATHS.userHome
}

function isInternalPath(value: string): boolean {
  return value.startsWith('/') && !value.startsWith('//') && !value.includes('\\')
}

/**
 * Resolves the protected destination captured by RouteGuards after login.
 * Never trusts an arbitrary location-state value as an external redirect and
 * never lets one role silently land in the other role's workspace.
 */
export function postLoginTarget(role: AuthRole, requested?: string): string {
  if (!requested || !isInternalPath(requested)) return roleHome(role)

  const userTarget = requested === APP_PATHS.userHome || requested.startsWith(`${APP_PATHS.userHome}/`)
  const adminTarget = requested === APP_PATHS.adminHome || requested.startsWith(`${APP_PATHS.adminHome}/`)

  if ((role === 'USER' && userTarget) || (role === 'ADMIN' && adminTarget)) {
    return requested
  }
  if (userTarget || adminTarget) return APP_PATHS.forbidden
  return roleHome(role)
}

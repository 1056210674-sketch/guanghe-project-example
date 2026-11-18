import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { PageLoading } from '../components/GlobalState'
import { useAuthStore } from '../stores/authStore'
import type { AuthRole } from './routePolicy'
import { roleHome } from './routePolicy'
import { APP_PATHS } from './paths'

export function PublicOnlyRoute() {
  const status = useAuthStore((state) => state.status)
  const user = useAuthStore((state) => state.user)

  if (status === 'authenticated' && user) {
    return <Navigate to={roleHome(user.role)} replace />
  }

  if (status === 'unknown' || status === 'restoring') {
    return <PageLoading label="正在检查登录状态…" />
  }

  return <Outlet />
}

export function ProtectedRoute({ role }: { role: AuthRole }) {
  const location = useLocation()
  const status = useAuthStore((state) => state.status)
  const user = useAuthStore((state) => state.user)
  const error = useAuthStore((state) => state.error)
  const target = `${location.pathname}${location.search}${location.hash}`

  if (status === 'unknown' || status === 'restoring') {
    return <PageLoading label="正在验证访问权限…" />
  }

  if (status === 'expired') {
    return (
      <Navigate
        to={APP_PATHS.login}
        replace
        state={{ from: target, reason: 'expired' }}
      />
    )
  }

  if (status === 'anonymous') {
    return <Navigate to={APP_PATHS.login} replace state={{ from: target }} />
  }

  if (status === 'error' && error?.status === 403) {
    return <Navigate to={APP_PATHS.forbidden} replace />
  }

  if (status !== 'authenticated' || !user) {
    return <Navigate to={APP_PATHS.login} replace state={{ from: target }} />
  }

  if (user.role !== role) {
    return <Navigate to={APP_PATHS.forbidden} replace />
  }

  return <Outlet />
}

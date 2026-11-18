import { useNavigate } from 'react-router-dom'
import { ForbiddenState } from '../components/GlobalState'
import { AdminShell } from '../layouts/AdminShell'
import { UserShell } from '../layouts/UserShell'
import { APP_PATHS } from '../routes/paths'
import { useAuthStore } from '../stores/authStore'

export function ForbiddenPage() {
  const navigate = useNavigate()
  const user = useAuthStore((state) => state.user)
  const home =
    user?.role === 'ADMIN' ? APP_PATHS.adminHome : APP_PATHS.userHome
  const state = <ForbiddenState onBack={() => navigate(home, { replace: true })} />

  if (user?.role === 'ADMIN') {
    return <AdminShell>{state}</AdminShell>
  }

  if (user?.role === 'USER') {
    return <UserShell>{state}</UserShell>
  }

  return <main className="standalone-state">{state}</main>
}

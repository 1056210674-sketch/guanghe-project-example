import { Navigate, useLocation } from 'react-router-dom'
import { APP_PATHS } from '../routes/paths'

/** Keep old workbench bookmarks usable without exposing a second task launcher. */
export function UserHomePage() {
  const location = useLocation()
  return <Navigate to={{ pathname: APP_PATHS.userHome, search: location.search, hash: location.hash }} replace />
}

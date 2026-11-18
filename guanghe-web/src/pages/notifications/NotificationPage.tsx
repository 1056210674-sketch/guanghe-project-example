import { NotificationCenter } from '../../features/notifications/components'
import { UserShell } from '../../layouts/UserShell'

// The notification centre is backed by the commercial API: the list, the unread count and the
// capability probe are all real contract calls. Nothing on this page comes from preview data.
export function NotificationPage() {
  return (
    <UserShell>
      <NotificationCenter />
    </UserShell>
  )
}

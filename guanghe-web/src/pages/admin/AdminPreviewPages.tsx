import { AdminTaskServiceWorkspace } from '../../features/admin-tasks'
import { AdminUserManagementWorkspace } from '../../features/admin-users'
import { AdminShell } from '../../layouts/AdminShell'
import {
  PREVIEW_ADMIN_SERVICES,
  PREVIEW_ADMIN_TASKS,
  PREVIEW_ADMIN_USERS,
} from './previewData'

export function AdminUserManagementPreviewPage() {
  return (
    <AdminShell>
      <AdminUserManagementWorkspace users={PREVIEW_ADMIN_USERS} preview />
    </AdminShell>
  )
}

export function AdminTaskServicePreviewPage() {
  return (
    <AdminShell>
      <AdminTaskServiceWorkspace
        tasks={PREVIEW_ADMIN_TASKS}
        services={PREVIEW_ADMIN_SERVICES}
        preview
      />
    </AdminShell>
  )
}

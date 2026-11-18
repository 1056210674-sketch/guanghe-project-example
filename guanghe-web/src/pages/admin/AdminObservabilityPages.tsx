import { AdminAuditConsole } from '../../features/admin-billing/audit'
import { AdminAgentVersionsConsole } from '../../features/admin-observability/AdminAgentVersionsConsole'
import { AdminShell } from '../../layouts/AdminShell'

// 操作日志 is wired to the audit API and is read-only: the trail is append-only and the contract
// exposes no way to change it.
export function AdminAuditPage() {
  return (
    <AdminShell>
      <AdminAuditConsole />
    </AdminShell>
  )
}

// Exact current runtime evidence plus historically observed task/workflow bundles. The console is
// read-only and deliberately contains no Prompt publication, rollback, evaluation or deployment
// action.
export function AdminAgentVersionsPage() {
  return (
    <AdminShell>
      <AdminAgentVersionsConsole />
    </AdminShell>
  )
}

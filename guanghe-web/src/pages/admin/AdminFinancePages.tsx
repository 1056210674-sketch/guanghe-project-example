import { AdminLedgerConsole } from '../../features/admin-billing/ledger'
import { AdminRechargeOrdersConsole } from '../../features/admin-billing/orders'
import { AdminPricingConsole } from '../../features/admin-billing/pricing'
import { AdminShell } from '../../layouts/AdminShell'

// Production pages. Each console owns its own requests against the commercial admin API and renders
// the server's answer: an unconfigured capability is shown as unconfigured, an empty list as an
// empty state, and a disabled control always carries the reason it is disabled. The DEV preview
// pages in `AdminFinancePreviewPages.tsx` are separate modules and keep their labelled samples.
export function AdminPricingPage() {
  return (
    <AdminShell>
      <AdminPricingConsole />
    </AdminShell>
  )
}

export function AdminLedgerPage() {
  return (
    <AdminShell>
      <AdminLedgerConsole />
    </AdminShell>
  )
}

export function AdminRechargeOrdersPage() {
  return (
    <AdminShell>
      <AdminRechargeOrdersConsole />
    </AdminShell>
  )
}

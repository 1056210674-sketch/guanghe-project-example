import { AdminInvoiceConsole } from '../../features/admin-billing/invoices'
import { AdminMediaConsole } from '../../features/admin-publishing/AdminMediaConsole'
import { AdminShell } from '../../layouts/AdminShell'

// 媒体与投稿 is wired to the Java operator contract. It is read-only by design: the entries that
// would sync resources, replay callbacks or re-poll the provider stay disabled with their reason
// stated, because every one of them writes local state or calls the provider for real.
export function AdminMediaPage() {
  return (
    <AdminShell>
      <AdminMediaConsole />
    </AdminShell>
  )
}

// 发票管理 is wired to the invoice control plane. Delivery remains unimplemented on the server, and
// the console states that plainly instead of implying a document was sent.
export function AdminInvoicePage() {
  return (
    <AdminShell>
      <AdminInvoiceConsole />
    </AdminShell>
  )
}

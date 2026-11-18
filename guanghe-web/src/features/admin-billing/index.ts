// The six production admin consoles of the commercial backoffice.
//
// Only the console components are re-exported here: the domains carry same-named helpers and view
// types (error views, formatters, adapters), so import a domain subpath directly — e.g.
// `features/admin-billing/pricing` — when you need anything beyond the console itself.
export { AdminPricingConsole } from './pricing'
export { AdminLedgerConsole } from './ledger'
export { AdminRechargeOrdersConsole } from './orders'
export { AdminInvoiceConsole } from './invoices'
export { AdminFeedbackConsole } from './feedback'
export { AdminAuditConsole } from './audit'

import type { LedgerRowView, PricingVersionView } from './types'

// Serialize explicit fields, not object identity. String conversion keeps invalid
// numeric sentinels distinct (JSON alone would collapse NaN/Infinity to null).
const numberKey = (value: number | undefined) => value === undefined ? null : String(value)

export function pricingFingerprint(version: PricingVersionView | undefined): string {
  if (!version) return 'null'
  const rows = version.rows.map(row => JSON.stringify([
    row.id, numberKey(row.points), row.formula ?? null, row.unit, row.name,
  ])).sort()
  return JSON.stringify([version.id, version.effectiveAt, version.operator, rows])
}

export function ledgerFingerprint(row: LedgerRowView | undefined): string | undefined {
  if (!row) return undefined
  return JSON.stringify([
    row.id, row.account, row.status, row.business, numberKey(row.points),
    numberKey(row.before), numberKey(row.after), row.reference ?? null,
    row.taskId ?? null, numberKey(row.amountCents), row.occurredAt,
  ])
}

// Local confirmation view model, never an API request.
export interface CompensationConfirmation {
  ledgerId: string
  account: string
  points: number
  reason: string
  ledgerFingerprint: string
}

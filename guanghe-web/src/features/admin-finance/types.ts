// Presentation models only. Map Java-generated response types in page containers
// when the admin contracts become available; these are not transport DTOs.
export interface PricingRowView {
  id: string
  name: string
  unit: string
  points?: number
  formula?: string
}

export interface PricingVersionView {
  id: string
  effectiveAt: string
  operator: string
  rows: readonly PricingRowView[]
}

export interface LedgerRowView {
  id: string
  account: string
  business: string
  points: number
  before: number
  after: number
  reference?: string
  taskId?: string
  amountCents?: number
  occurredAt: string
  status: 'SUCCESS' | 'PENDING' | 'EXCEPTION'
  note?: string
  traceId?: string
  events?: readonly { title: string; time: string; detail: string }[]
}

export interface LedgerSummaryView {
  recharge: number
  consumption: number
  released: number
  compensation: number
  discrepancy: number
}

export function formatPoints(value: number | undefined): string {
  return value !== undefined && Number.isSafeInteger(value)
    ? value.toLocaleString('en-US') : '—'
}

export function formatCents(value: number | undefined): string {
  if (value === undefined || !Number.isSafeInteger(value) || value < 0) return '—'
  const cents = BigInt(value)
  return `¥${cents / 100n}.${String(cents % 100n).padStart(2, '0')}`
}

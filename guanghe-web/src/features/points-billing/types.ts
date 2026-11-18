export type PointsLedgerType =
  | 'RECHARGE'
  | 'CONSUMPTION'
  | 'FREEZE'
  | 'UNFREEZE'
  | 'REFUND'
  | 'GIFT'
  | 'ADJUSTMENT'

export type PointsLedgerStatus = 'COMPLETED' | 'PENDING' | 'FAILED'

/**
 * View models for the balance and ledger surfaces.
 *
 * These are deliberately not the transport DTOs: `toPointsLedgerEntry` and the point account adapter
 * map the generated response types onto them, so a server-side rename fails at compile time instead
 * of rendering an empty column. The recharge, invoice, notification and feedback surfaces keep their
 * own models in `src/features/billing`.
 */
export interface PointsBalanceSummary {
  availablePoints?: number
  frozenPoints?: number
  totalEarnedPoints?: number
  totalConsumedPoints?: number
  expiringPoints?: number
  expiresAt?: string
}

export interface PointsLedgerEntry {
  id: string
  type: PointsLedgerType
  title: string
  description?: string
  pointsChange: number
  balanceAfter?: number
  occurredAt: string
  status: PointsLedgerStatus
  referenceNo?: string
}

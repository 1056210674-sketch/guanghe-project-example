import type { PointLedgerEntryDto } from '../../../api/pointLedgerApi'
import type { PointsLedgerEntry, PointsLedgerType } from '../types'

export function pointLedgerType(entryType: PointLedgerEntryDto['entryType']): PointsLedgerType {
  if (entryType === 'ADMIN_INITIAL_GRANT') return 'GIFT'
  if (entryType === 'RECHARGE') return 'RECHARGE'
  if (entryType.endsWith('_FREEZE')) return 'FREEZE'
  if (entryType.endsWith('_RELEASE')) return 'UNFREEZE'
  if (entryType === 'WORKFLOW_ALLOCATE') return 'ADJUSTMENT'
  if (entryType.endsWith('_SETTLE')) return 'CONSUMPTION'
  return 'ADJUSTMENT'
}

export function toPointsLedgerEntry(entry: PointLedgerEntryDto): PointsLedgerEntry {
  const balanceUnchanged =
    entry.availableDelta === 0 && entry.frozenDelta === 0
  const type = balanceUnchanged
    ? 'ADJUSTMENT'
    : pointLedgerType(entry.entryType)
  const positive = entry.availableDelta > 0 || type === 'GIFT' || type === 'UNFREEZE'
  const neutral = balanceUnchanged || entry.amountPoints === 0
  return {
    id: entry.id,
    type,
    title: entry.title,
    description: entry.businessType === 'TASK'
      ? '任务积分变动'
      : entry.businessType === 'WORKFLOW'
        ? '组合计划积分变动'
        : entry.businessType === 'RECHARGE'
          ? '充值积分入账'
          : entry.businessType === 'ADMIN_ADJUSTMENT'
            ? '人工积分调整'
            : '账户初始化',
    pointsChange: neutral
      ? 0
      : positive
        ? Math.abs(entry.amountPoints)
        : -Math.abs(entry.amountPoints),
    balanceAfter: entry.availableAfter,
    occurredAt: entry.createdAt,
    status: 'COMPLETED',
    referenceNo: entry.businessId,
  }
}

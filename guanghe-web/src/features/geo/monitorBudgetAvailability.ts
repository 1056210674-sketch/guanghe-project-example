import type { BudgetPolicy } from '../../api/monitorExecutionApi'

const unavailable = { available: false, notice: '自动监测尚未开放，可先核对范围和费用。' }
function validDay(value: string | null): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
    && Number.isFinite(Date.parse(`${value}T00:00:00Z`))
    && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value
}

/** Display/admission-request guard only. The browser clock never grants execution permission. */
export function monitorBudgetAvailability(policy: BudgetPolicy | undefined, now: number): { available: boolean; notice?: string } {
  if (!policy || !Number.isFinite(now) || !validDay(policy.earliestTrustedShanghaiDate)
    || policy.configured && !validDay(policy.effectiveShanghaiDate)) return unavailable
  const floor = [policy.earliestTrustedShanghaiDate, policy.effectiveShanghaiDate].filter(validDay).sort().at(-1)!
  const today = new Date(now + 8 * 60 * 60 * 1000).toISOString().slice(0, 10)
  if (floor > today) return { available: false, notice: `自动监测将在 ${floor} 开放，可先核对范围和费用。` }
  return { available: true }
}

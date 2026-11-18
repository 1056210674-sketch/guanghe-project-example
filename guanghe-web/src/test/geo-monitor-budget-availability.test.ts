import { describe, expect, it } from 'vitest'
import type { BudgetPolicy } from '../api/monitorExecutionApi'
import { monitorBudgetAvailability } from '../features/geo/monitorBudgetAvailability'

const policy: BudgetPolicy = { configured: true, version: 1, enabled: true, maxPointsPerAction: 10, maxPointsPerDay: 30,
  timezone: 'Asia/Shanghai', earliestTrustedShanghaiDate: '2026-10-08', effectiveShanghaiDate: '2026-10-08' }
describe('监测开放日期仅作 UI 提示，不替代服务器准入', () => {
  it('北京时间可信日午夜前禁用，午夜当天可继续服务器核验', () => {
    expect(monitorBudgetAvailability(policy, Date.parse('2026-10-07T15:59:59Z'))).toEqual({ available: false, notice: '自动监测将在 2026-10-08 开放，可先核对范围和费用。' })
    expect(monitorBudgetAvailability(policy, Date.parse('2026-10-07T16:00:00Z'))).toEqual({ available: true })
  })
  it('effective晚于可信日取较晚日期，不提前开放', () => {
    expect(monitorBudgetAvailability({ ...policy, effectiveShanghaiDate: '2026-10-09' }, Date.parse('2026-10-08T08:00:00Z')).notice).toContain('2026-10-09')
  })
  it('可信日缺失、非法或已配置有效日缺失时不发送必然拒绝的 Run', () => {
    const now = Date.parse('2026-10-08T08:00:00Z')
    expect(monitorBudgetAvailability(undefined, now).available).toBe(false)
    expect(monitorBudgetAvailability({ ...policy, earliestTrustedShanghaiDate: null }, now).available).toBe(false)
    expect(monitorBudgetAvailability({ ...policy, earliestTrustedShanghaiDate: '2026-02-30' }, now).available).toBe(false)
    expect(monitorBudgetAvailability({ ...policy, effectiveShanghaiDate: null }, now).available).toBe(false)
  })
  it('首次配置无effective时，可信日到达才允许显式保存；保存响应仍需重新检查', () => {
    const initial = { ...policy, configured: false, enabled: false, version: 0, effectiveShanghaiDate: null, maxPointsPerAction: null, maxPointsPerDay: null }
    expect(monitorBudgetAvailability(initial, Date.parse('2026-10-07T15:59:59Z')).available).toBe(false)
    expect(monitorBudgetAvailability(initial, Date.parse('2026-10-07T16:00:00Z')).available).toBe(true)
  })
})

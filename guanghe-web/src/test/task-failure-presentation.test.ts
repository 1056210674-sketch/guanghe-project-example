import { describe, expect, it } from 'vitest'
import { taskFailureMessage } from '../features/task-common/taskFailurePresentation'

describe('任务失败信息用户化', () => {
  it('明确处于等待重试时隐藏内部错误码且不保证重试一定成功', () => {
    const message = taskFailureMessage('WORKER_LEASE_EXPIRED', {
      status: 'RETRY_WAIT',
    })

    expect(message).toBe(
      '执行暂时中断，任务尚未完成。请稍后刷新状态；若长时间没有更新，请联系支持，暂勿重复发起。',
    )
    expect(message).not.toMatch(/WORKER|LEASE|EXPIRED/)
    expect(message).not.toContain('自动重试')
  })

  it('终态失败不承诺系统仍会自动重试', () => {
    const message = taskFailureMessage('PROVIDER_UNAVAILABLE', {
      status: 'FAILED',
    })

    expect(message).toBe('执行服务暂时异常，本次任务未完成，请稍后重新发起。')
    expect(message).not.toContain('自动重试')
  })

  it('不识别的错误码使用通用提示，不回显原码', () => {
    const message = taskFailureMessage('INTERNAL_FUTURE_CODE')

    expect(message).toBe(
      '任务执行未完成，请稍后重试；如重复失败，请联系支持。',
    )
    expect(message).not.toContain('INTERNAL_FUTURE_CODE')
  })

  it('媒体退稿与内容安全拒绝分开说明', () => {
    expect(taskFailureMessage('MEDIA_ORDER_REJECTED')).toContain(
      '媒体未受理本次投稿',
    )
    expect(taskFailureMessage('CONTENT_REJECTED')).toContain(
      '内容未通过安全检查',
    )
  })

  it('普通积分结算异常不误报为余额不足', () => {
    expect(taskFailureMessage('POINT_SETTLEMENT_PENDING')).toContain(
      '积分结算暂未完成',
    )
    expect(taskFailureMessage('INSUFFICIENT_POINTS')).toContain(
      '可用积分不足',
    )
  })

  it('没有错误码时不制造失败信息', () => {
    expect(taskFailureMessage(null)).toBeUndefined()
  })
})

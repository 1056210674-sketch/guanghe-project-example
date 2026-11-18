import type { MonitorAdmission, MonitorWaitReason } from '../../api/monitorExecutionApi'

const reasons: Record<MonitorWaitReason, string> = {
  AUTOMATIC_MONITOR_DISABLED: '本次自动监测尚未开放。', BUDGET_NOT_CONFIGURED: '请先设置执行额度。',
  BUDGET_DISABLED: '执行额度已停用，请重新核对。', BUDGET_NOT_EFFECTIVE: '执行额度尚未生效，请核对生效日期。',
  ACCOUNT_UNAVAILABLE: '当前账户不可执行监测。', AGENT_UNAVAILABLE: '当前智能体不可执行监测。', PROJECT_UNAVAILABLE: '当前项目不可访问。',
  GRANT_NOT_FOUND: '请先授权本次范围。', GRANT_CHANGED: '授权已改变，请重新核对。', GRANT_REVOKED: '本次授权已撤销。', GRANT_EXPIRED: '本次授权已过期。',
  TOPIC_UNAVAILABLE: '所选专题已不可访问。', MONITOR_TARGET_MISSING: '请先完善专题的监测对象。', MONITOR_REGION_MISSING: '请先完善专题地区。',
  RANGE_CHANGED: '监测范围已改变，请重新核对费用和授权。', INPUT_CHANGED: '专题资料已更新，请重新核对费用和授权。', PRICE_CHANGED: '价格已更新，请重新核对本次费用。',
  DRAFT_EXPIRED: '执行准备已过期，请重新核对后发起。', RUN_FAILED: '本次回复未完成，没有自动受理监测。', RUN_STOPPED: '本次回复已停止，没有自动受理监测。',
  NO_MATCHING_PROPOSAL: '本次没有生成匹配的监测方案，未自动受理。', PROPOSAL_RANGE_MISMATCH: '生成方案与授权范围不一致，未自动受理。',
  SINGLE_ACTION_LIMIT: '本次费用超过每次执行额度。', DAY_LIMIT: '本次费用超过今日剩余准入额度。', INSUFFICIENT_POINTS: '账户可用积分不足。',
}
export function monitorWaitMessage(reason: MonitorWaitReason | null): string {
  return reason && reasons[reason] || '暂时无法受理，请重新核对设置。'
}
export function monitorAdmissionView(value: MonitorAdmission) {
  if (value.status === 'ADMITTED' && value.taskId && ['EXECUTION_PREAUTH', 'USER_CONFIRM'].includes(value.executionSource ?? '')) return {
    title: '监测已受理', description: '任务已进入执行队列，结果请在任务中查看。此状态不代表监测已完成。', tone: 'success' as const, taskId: value.taskId,
  }
  if (value.status === 'WAIT_INPUT') return { title: '本次未自动受理', description: monitorWaitMessage(value.reasonCode), tone: 'warning' as const }
  if (value.status === 'WAITING_PROPOSAL' || value.status === 'PENDING') return {
    title: '正在准备本次监测', description: '等待回复完成及额度核对，尚未受理付费任务。不要重复发起。', tone: 'info' as const,
  }
  return { title: '尚未确认监测受理结果', description: '请刷新受理状态，不要重复发起。', tone: 'warning' as const }
}

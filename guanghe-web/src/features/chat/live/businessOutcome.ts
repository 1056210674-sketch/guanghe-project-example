import type { ConversationBusinessOutcome } from '../../../api/conversationApi'

export function readBusinessOutcome(value: unknown): ConversationBusinessOutcome | undefined {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return undefined
  const data = value as Record<string, unknown>
  if (data.schemaVersion !== 1 || typeof data.status !== 'string' || !['ANSWERED', 'NEEDS_INPUT', 'DRAFT_READY', 'PARTIAL', 'FAILED'].includes(data.status)) return undefined
  if (data.failureStage !== undefined && (typeof data.failureStage !== 'string' || !['LOOKUP', 'PLAN', 'TOOL', 'SYNTHESIS', 'PROTOCOL'].includes(data.failureStage))) return undefined
  if (data.reasonCode !== undefined && (typeof data.reasonCode !== 'string' || !/^[A-Z][A-Z0-9_]{0,127}$/.test(data.reasonCode))) return undefined
  return {
    schemaVersion: 1,
    status: data.status as ConversationBusinessOutcome['status'],
    ...(data.reasonCode ? { reasonCode: data.reasonCode as ConversationBusinessOutcome['reasonCode'] } : {}),
    ...(data.failureStage ? { failureStage: data.failureStage as ConversationBusinessOutcome['failureStage'] } : {}),
  }
}

export function businessOutcomeLabel(status: ConversationBusinessOutcome['status']) {
  return {
    ANSWERED: '本次回复已完成',
    NEEDS_INPUT: '请补充信息或选择对象',
    DRAFT_READY: '报价草稿已准备，等待确认',
    PARTIAL: '本次回复部分完成',
    FAILED: '本次回复未完成',
  }[status]
}

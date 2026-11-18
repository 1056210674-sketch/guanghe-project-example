// Redacted presentation models only, not API DTOs. Future page containers map
// Java Browser OpenAPI types; never pass raw audit payloads or Prompt contents.
export type AuditResult = 'SUCCESS' | 'FAILED' | 'PARTIAL'
export const auditResults: Record<AuditResult, string> = { SUCCESS: '成功', FAILED: '失败', PARTIAL: '部分成功' }
export interface AuditLogView {
  id: string; administrator: string; action: string; objectType: string; objectId: string
  summary: string; result: AuditResult; ipMasked: string; occurredAt: string; traceId: string; reason: string
  changes: readonly { field: string; before: string; after: string }[]
  confirmation?: { administrator: string; time: string; result: string }
  taskId?: string; orderId?: string; userIdMasked?: string
  events: readonly { id: string; time: string; summary: string }[]
}
// Explicit field allowlist: never stringify arbitrary server objects.
export function redactedChangesJson(log: AuditLogView) {
  return JSON.stringify(log.changes.map(change => ({ field: change.field, before: change.before, after: change.after })), null, 2)
}

import type {
  OperationalAuditEventDto,
  OperationalAuditEventListQuery,
} from '../../../api/adminBillingApi'

/**
 * View models for the 操作日志 (operational audit) console.
 *
 * The audit trail is append-only and readable: everything here is derived from the generated
 * `OperationalAuditEvent` contract, and the console never writes to it.
 */

export type AuditActorType = OperationalAuditEventDto['actorType']
export type AuditOutcome = OperationalAuditEventDto['outcome']
export type AuditListQuery = OperationalAuditEventListQuery

/** `ALL` is the explicit 全部 choice: it is a filter sentinel, never a value sent to the server. */
export const AUDIT_FILTER_ALL = 'ALL'

export interface AdminAuditFiltersView {
  actorType?: AuditActorType | typeof AUDIT_FILTER_ALL
  targetType?: string | typeof AUDIT_FILTER_ALL
  targetId?: string
  eventType?: string | typeof AUDIT_FILTER_ALL
}

function filterValue(value: string | undefined): string | undefined {
  const trimmed = value?.trim()
  return trimmed && trimmed !== AUDIT_FILTER_ALL ? trimmed : undefined
}

export interface AdminAuditEventRowView {
  id: string
  actorType: AuditActorType
  actorUserId?: string
  actorRef?: string
  targetType: string
  targetId?: string
  eventType: string
  outcome: AuditOutcome
  traceId: string
  /** Server-side redacted metadata, capped by the database at 16 KiB. Arbitrary text. */
  details: string
  createdAt: string
}

export const AUDIT_ACTOR_TYPE_LABELS: Record<AuditActorType, string> = {
  USER: '用户',
  ADMIN: '管理员',
  SYSTEM: '系统',
  PAYMENT_PROVIDER: '支付渠道',
  MAIL_PROVIDER: '邮件渠道',
}

export const AUDIT_OUTCOME_META: Record<AuditOutcome, { label: string; color: string }> = {
  SUCCESS: { label: '成功', color: 'success' },
  FAILURE: { label: '失败', color: 'error' },
  PENDING: { label: '处理中', color: 'processing' },
}

function optionalText(value: string | null | undefined): string | undefined {
  return typeof value === 'string' && value.trim().length > 0 ? value : undefined
}

export function toAdminAuditEventRow(dto: OperationalAuditEventDto): AdminAuditEventRowView {
  return {
    id: dto.id,
    actorType: dto.actorType,
    actorUserId: optionalText(dto.actorUserId),
    actorRef: optionalText(dto.actorRef),
    targetType: dto.targetType,
    targetId: optionalText(dto.targetId),
    eventType: dto.eventType,
    outcome: dto.outcome,
    traceId: dto.traceId,
    details: dto.details,
    createdAt: dto.createdAt,
  }
}

export function toAdminAuditQuery(
  filters: AdminAuditFiltersView,
  page: number,
  size: number,
): AuditListQuery {
  const actorType = filterValue(filters.actorType)
  return {
    actorType: actorType ? (actorType as AuditActorType) : undefined,
    targetType: filterValue(filters.targetType),
    targetId: filterValue(filters.targetId),
    eventType: filterValue(filters.eventType),
    page,
    size,
  }
}

/**
 * `details` is a bounded JSON string written by the server. It is shown as text: pretty-printed when
 * it parses as JSON, otherwise exactly as it was recorded.
 */
export function auditDetailsText(details: string): string {
  const trimmed = details.trim()
  if (trimmed.length === 0) return ''
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return details
  try {
    return JSON.stringify(JSON.parse(trimmed) as unknown, null, 2)
  } catch {
    return details
  }
}

/**
 * The event catalogue is whatever the response actually contains, plus 全部. No value is offered
 * that the contract did not publish and the server did not send.
 */
export function auditFilterOptions(
  items: readonly AdminAuditEventRowView[],
  field: 'actorType' | 'targetType' | 'eventType',
): readonly string[] {
  const values = new Set<string>()
  for (const item of items) {
    const value = item[field]
    if (typeof value === 'string' && value.trim().length > 0) values.add(value)
  }
  return [...values].sort((left, right) => left.localeCompare(right))
}

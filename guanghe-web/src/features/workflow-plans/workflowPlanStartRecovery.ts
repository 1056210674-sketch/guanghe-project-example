import { fingerprintJsonInput, reuseOrCreateInputAttempt } from '../task-draft/formTaskDraftLifecycle'

export interface WorkflowPlanStartRecoveryState {
  planId: string
  planVersion: number
  fingerprint: string
  idempotencyKey: string
}

const PREFIX = 'zhixiaohe.workflow-plan-start.recovery.v1.'

function storage(): Storage | null {
  try {
    return typeof globalThis.localStorage === 'undefined' ? null : globalThis.localStorage
  } catch {
    return null
  }
}

function key(ownerId: string) {
  return `${PREFIX}${encodeURIComponent(ownerId)}`
}

export function readWorkflowPlanStartRecovery(ownerId?: string | null): WorkflowPlanStartRecoveryState | undefined {
  if (!ownerId) return undefined
  try {
    const raw = storage()?.getItem(key(ownerId))
    if (!raw) return undefined
    const parsed = JSON.parse(raw) as Record<string, unknown>
    const planId = typeof parsed.planId === 'string' && /^[1-9]\d{0,18}$/.test(parsed.planId)
      ? parsed.planId : undefined
    const fingerprint = typeof parsed.fingerprint === 'string' && /^[0-9a-f]{64}$/.test(parsed.fingerprint)
      ? parsed.fingerprint : undefined
    const idempotencyKey = typeof parsed.idempotencyKey === 'string'
      && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(parsed.idempotencyKey)
      ? parsed.idempotencyKey : undefined
    if (!planId || !fingerprint || !idempotencyKey || !Number.isSafeInteger(parsed.planVersion) || Number(parsed.planVersion) < 1) {
      return undefined
    }
    return { planId, planVersion: Number(parsed.planVersion), fingerprint, idempotencyKey }
  } catch {
    return undefined
  }
}

export function writeWorkflowPlanStartRecovery(ownerId: string | null | undefined, state: WorkflowPlanStartRecoveryState) {
  if (!ownerId) return
  try {
    storage()?.setItem(key(ownerId), JSON.stringify(state))
  } catch {
    // Java remains authoritative when browser recovery storage is unavailable.
  }
}

export function clearWorkflowPlanStartRecovery(ownerId?: string | null) {
  if (!ownerId) return
  try {
    storage()?.removeItem(key(ownerId))
  } catch {
    // A stale safe idempotency record is harmless and remains owner-isolated.
  }
}

export async function prepareWorkflowPlanStartAttempt(
  ownerId: string | null | undefined,
  planId: string,
  planVersion: number,
): Promise<WorkflowPlanStartRecoveryState> {
  const fingerprint = await fingerprintJsonInput({ planId, planVersion })
  const previous = readWorkflowPlanStartRecovery(ownerId)
  const sameInput = previous?.planId === planId && previous.planVersion === planVersion
    ? previous
    : undefined
  const attempt = reuseOrCreateInputAttempt(sameInput, fingerprint)
  const state = { planId, planVersion, ...attempt }
  writeWorkflowPlanStartRecovery(ownerId, state)
  return state
}

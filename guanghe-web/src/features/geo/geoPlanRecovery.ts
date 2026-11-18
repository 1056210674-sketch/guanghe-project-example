import type { InputIdempotencyAttempt } from '../task-draft/formTaskDraftLifecycle'
import type { GeoPlanDraftView, GeoPlanScheduleView, GeoWeekday } from './types'

export interface GeoPlanConfirmationAttempt {
  draftId: string
  inputHash: string
  pricingVersionId: string
  idempotencyKey: string
}

export interface GeoPlanRecoveryState {
  editorTarget?: string
  formDraft?: GeoPlanDraftView
  draftAttempt?: InputIdempotencyAttempt
  confirmationAttempt?: GeoPlanConfirmationAttempt
}

interface StoredGeoPlanRecovery {
  schemaVersion: 1
  ownerId: string
  state: GeoPlanRecoveryState
}

const STORAGE_PREFIX = 'zhixiaohe.geo-plan.recovery.v1.'

function safeOpaqueString(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 &&
      !/^(?:https?:|data:|blob:)/i.test(value)
    ? value
    : undefined
}

function readSchedule(value: unknown): GeoPlanScheduleView | undefined {
  if (!value || typeof value !== 'object') return undefined
  const record = value as Record<string, unknown>
  const localTime = safeOpaqueString(record.localTime)
  const timeZone = safeOpaqueString(record.timeZone)
  if (!localTime || !timeZone) return undefined
  if (record.frequency === 'HOURLY') return { frequency: 'HOURLY', localTime, timeZone }
  if (record.frequency === 'DAILY') return { frequency: 'DAILY', localTime, timeZone }
  if (record.frequency === 'WEEKLY' && Array.isArray(record.weekdays)) {
    const weekdays = record.weekdays.filter(
      (day): day is GeoWeekday => typeof day === 'number' && Number.isSafeInteger(day) && day >= 1 && day <= 7,
    )
    return { frequency: 'WEEKLY', localTime, timeZone, weekdays }
  }
  if (record.frequency === 'INTERVAL_DAYS' && typeof record.intervalDays === 'number') {
    return { frequency: 'INTERVAL_DAYS', localTime, timeZone, intervalDays: record.intervalDays }
  }
  return undefined
}

function readFormDraft(value: unknown): GeoPlanDraftView | undefined {
  if (!value || typeof value !== 'object') return undefined
  const record = value as Record<string, unknown>
  const name = typeof record.name === 'string' ? record.name : undefined
  const schedule = readSchedule(record.schedule)
  const questionIds = Array.isArray(record.questionIds)
    ? record.questionIds.map(safeOpaqueString).filter((id): id is string => Boolean(id)) : undefined
  const platformCodes = Array.isArray(record.platformCodes)
    ? record.platformCodes.map(safeOpaqueString).filter((code): code is string => Boolean(code)) : undefined
  const mode = record.mode === 'STANDARD' || record.mode === 'DEEP' ? record.mode : undefined
  if (name === undefined || !schedule || !questionIds || !platformCodes || !mode) return undefined
  return {
    name,
    topicId: safeOpaqueString(record.topicId),
    questionIds,
    platformCodes,
    mode,
    schedule,
    maxPointsPerRun: typeof record.maxPointsPerRun === 'number' ? record.maxPointsPerRun : undefined,
  }
}

function readState(value: unknown): GeoPlanRecoveryState {
  if (!value || typeof value !== 'object') return {}
  const record = value as Record<string, unknown>
  const draft = record.draftAttempt as Record<string, unknown> | undefined
  const confirmation = record.confirmationAttempt as Record<string, unknown> | undefined
  const fingerprint = safeOpaqueString(draft?.fingerprint)
  const draftIdempotencyKey = safeOpaqueString(draft?.idempotencyKey)
  const draftId = safeOpaqueString(confirmation?.draftId)
  const inputHash = safeOpaqueString(confirmation?.inputHash)
  const pricingVersionId = safeOpaqueString(confirmation?.pricingVersionId)
  const confirmationIdempotencyKey = safeOpaqueString(confirmation?.idempotencyKey)

  return {
    editorTarget: safeOpaqueString(record.editorTarget),
    formDraft: readFormDraft(record.formDraft),
    draftAttempt: fingerprint && draftIdempotencyKey
      ? { fingerprint, idempotencyKey: draftIdempotencyKey }
      : undefined,
    confirmationAttempt:
      draftId && inputHash && pricingVersionId && confirmationIdempotencyKey
        ? {
            draftId,
            inputHash,
            pricingVersionId,
            idempotencyKey: confirmationIdempotencyKey,
          }
        : undefined,
  }
}

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null
  }
}

function key(ownerId: string) {
  return `${STORAGE_PREFIX}${encodeURIComponent(ownerId)}`
}

export const geoPlanRecoveryStore = {
  read(ownerId?: string | null): GeoPlanRecoveryState {
    if (!ownerId) return {}
    const target = storage()
    if (!target) return {}
    try {
      const parsed = JSON.parse(target.getItem(key(ownerId)) ?? 'null') as unknown
      if (!parsed || typeof parsed !== 'object') return {}
      const envelope = parsed as Partial<StoredGeoPlanRecovery>
      return envelope.schemaVersion === 1 && envelope.ownerId === ownerId
        ? readState(envelope.state)
        : {}
    } catch {
      return {}
    }
  },
  write(ownerId: string | null | undefined, state: GeoPlanRecoveryState): void {
    if (!ownerId) return
    const target = storage()
    if (!target) return
    const envelope: StoredGeoPlanRecovery = {
      schemaVersion: 1,
      ownerId,
      state: readState(state),
    }
    try {
      target.setItem(key(ownerId), JSON.stringify(envelope))
    } catch {
      // Java remains authoritative when browser recovery storage is unavailable.
    }
  },
  clear(ownerId?: string | null): void {
    if (!ownerId) return
    try {
      storage()?.removeItem(key(ownerId))
    } catch {
      // Nothing else is required when local recovery storage is unavailable.
    }
  },
  keyForOwner: key,
}

import { isApiError } from '../../api/apiError'
import {
  confirmTaskDraft,
  createConversation,
  createTaskDraft,
  getConversation,
  getTaskDraft,
  listAllConversations,
  reviseTaskDraft,
} from '../../api/conversationApi'
import type {
  ConfirmTaskResponse,
  CreateTaskDraftRequest,
  ReviseTaskDraftRequest,
  TaskDraft,
} from '../../api/conversationApi'

export interface FormTaskDraftAttempt {
  fingerprint: string
  idempotencyKey: string
  draftId?: string
  revisionOfDraftId?: string
}

export interface FormTaskDraftConfirmationAttempt {
  draftId: string
  inputHash: string
  pricingVersionId: string
  idempotencyKey: string
  taskId?: string
}

export interface FormTaskDraftRecoveryState {
  conversationId?: string
  draftAttempt?: FormTaskDraftAttempt
  confirmationAttempt?: FormTaskDraftConfirmationAttempt
}

export interface InputIdempotencyAttempt {
  fingerprint: string
  idempotencyKey: string
}

interface StoredRecoveryEnvelope {
  schemaVersion: 1
  ownerId: string
  flow: string
  state: FormTaskDraftRecoveryState
}

interface RecoveryStoreOptions {
  flow: string
  legacyStoragePrefixes?: readonly string[]
}

interface ResolveConversationOptions {
  recovery: FormTaskDraftRecoveryState
  title: string
  onRecoveryChange: (state: FormTaskDraftRecoveryState) => void
}

interface PrepareDraftOptions<TRequest = CreateTaskDraftRequest> {
  recovery: FormTaskDraftRecoveryState
  request: TRequest
  currentDraft?: TaskDraft
  onRecoveryChange: (state: FormTaskDraftRecoveryState) => void
  createDraft?: (request: TRequest, idempotencyKey: string) => Promise<TaskDraft>
  reviseDraft?: (
    draftId: string,
    request: TRequest,
    idempotencyKey: string,
  ) => Promise<TaskDraft>
}

interface ConfirmDraftOptions {
  recovery: FormTaskDraftRecoveryState
  draft: TaskDraft
  onRecoveryChange: (state: FormTaskDraftRecoveryState) => void
}

const RECOVERY_SCHEMA_VERSION = 1 as const
const STORAGE_PREFIX = 'zhixiaohe.form-task-draft.recovery.'
const RECOVERABLE_DRAFT_STATUSES = new Set<TaskDraft['status']>([
  'WAITING_CONFIRMATION',
  'CONFIRMED',
])

function storage(): Storage | null {
  try {
    return typeof globalThis.localStorage === 'undefined'
      ? null
      : globalThis.localStorage
  } catch {
    return null
  }
}

function safeOpaqueString(value: unknown): string | undefined {
  if (typeof value !== 'string' || value.length === 0) return undefined
  if (/^(?:https?:|data:|blob:)/i.test(value)) return undefined
  return value
}

function readDraftAttempt(value: unknown): FormTaskDraftAttempt | undefined {
  if (!value || typeof value !== 'object') return undefined
  const record = value as Record<string, unknown>
  const fingerprint = safeOpaqueString(record.fingerprint)
  const idempotencyKey = safeOpaqueString(record.idempotencyKey)
  if (!fingerprint || !idempotencyKey) return undefined
  return {
    fingerprint,
    idempotencyKey,
    draftId: safeOpaqueString(record.draftId),
    revisionOfDraftId: safeOpaqueString(record.revisionOfDraftId),
  }
}

function readConfirmationAttempt(
  value: unknown,
): FormTaskDraftConfirmationAttempt | undefined {
  if (!value || typeof value !== 'object') return undefined
  const record = value as Record<string, unknown>
  const draftId = safeOpaqueString(record.draftId)
  const inputHash = safeOpaqueString(record.inputHash)
  const pricingVersionId = safeOpaqueString(record.pricingVersionId)
  const idempotencyKey = safeOpaqueString(record.idempotencyKey)
  if (!draftId || !inputHash || !pricingVersionId || !idempotencyKey) {
    return undefined
  }
  return {
    draftId,
    inputHash,
    pricingVersionId,
    idempotencyKey,
    taskId: safeOpaqueString(record.taskId),
  }
}

function readRecoveryState(value: unknown): FormTaskDraftRecoveryState | undefined {
  if (!value || typeof value !== 'object') return undefined
  const record = value as Record<string, unknown>
  return {
    conversationId: safeOpaqueString(record.conversationId),
    draftAttempt: readDraftAttempt(record.draftAttempt),
    confirmationAttempt: readConfirmationAttempt(record.confirmationAttempt),
  }
}

function storageKey(flow: string, ownerId: string): string {
  return `${STORAGE_PREFIX}${encodeURIComponent(flow)}.${encodeURIComponent(ownerId)}`
}

function parseJson(raw: string | null): unknown {
  if (!raw) return undefined
  try {
    return JSON.parse(raw) as unknown
  } catch {
    return undefined
  }
}

export function createFormTaskDraftRecoveryStore({
  flow,
  legacyStoragePrefixes = [],
}: RecoveryStoreOptions) {
  const read = (ownerId?: string | null): FormTaskDraftRecoveryState => {
    if (!ownerId) return {}
    const target = storage()
    if (!target) return {}

    const persisted = parseJson(target.getItem(storageKey(flow, ownerId)))
    if (persisted && typeof persisted === 'object') {
      const envelope = persisted as Partial<StoredRecoveryEnvelope>
      if (
        envelope.schemaVersion === RECOVERY_SCHEMA_VERSION &&
        envelope.ownerId === ownerId &&
        envelope.flow === flow
      ) {
        return readRecoveryState(envelope.state) ?? {}
      }
    }

    for (const prefix of legacyStoragePrefixes) {
      const legacyKey = `${prefix}${ownerId}`
      const legacy = readRecoveryState(parseJson(target.getItem(legacyKey)))
      if (!legacy) continue
      write(ownerId, legacy)
      try {
        target.removeItem(legacyKey)
      } catch {
        // A migrated copy already exists under the versioned, flow-isolated key.
      }
      return legacy
    }
    return {}
  }

  const write = (
    ownerId: string | null | undefined,
    state: FormTaskDraftRecoveryState,
  ): void => {
    if (!ownerId) return
    const target = storage()
    if (!target) return
    const envelope: StoredRecoveryEnvelope = {
      schemaVersion: RECOVERY_SCHEMA_VERSION,
      ownerId,
      flow,
      state: readRecoveryState(state) ?? {},
    }
    try {
      target.setItem(storageKey(flow, ownerId), JSON.stringify(envelope))
      for (const prefix of legacyStoragePrefixes) {
        target.removeItem(`${prefix}${ownerId}`)
      }
    } catch {
      // Java remains authoritative when browser recovery storage is unavailable.
    }
  }

  return {
    read,
    write,
    keyForOwner: (ownerId: string) => storageKey(flow, ownerId),
  }
}

export async function fingerprintJsonInput(value: unknown): Promise<string> {
  const payload = new TextEncoder().encode(JSON.stringify(value))
  const digest = await crypto.subtle.digest('SHA-256', payload)
  return Array.from(new Uint8Array(digest), (value) =>
    value.toString(16).padStart(2, '0'),
  ).join('')
}

export async function fingerprintFormTaskDraftInput(
  request: CreateTaskDraftRequest,
): Promise<string> {
  return fingerprintJsonInput(request)
}

export function createFormTaskDraftIdempotencyKey(): string {
  return crypto.randomUUID()
}

export function reuseOrCreateInputAttempt(
  previous: InputIdempotencyAttempt | undefined,
  fingerprint: string,
): InputIdempotencyAttempt {
  return previous?.fingerprint === fingerprint
    ? previous
    : {
        fingerprint,
        idempotencyKey: createFormTaskDraftIdempotencyKey(),
      }
}

export function toFormTaskDraftRevisionRequest(
  request: CreateTaskDraftRequest,
): ReviseTaskDraftRequest {
  return {
    spaceId: request.spaceId,
    productId: request.productId,
    parameterSchemaVersion: request.parameterSchemaVersion,
    parameters: request.parameters,
  }
}

export async function resolveFormTaskDraftConversation({
  recovery,
  title,
  onRecoveryChange,
}: ResolveConversationOptions): Promise<{
  conversationId: string
  recovery: FormTaskDraftRecoveryState
}> {
  let conversationId: string | undefined
  if (recovery.conversationId) {
    try {
      conversationId = (await getConversation(recovery.conversationId)).id
    } catch (error) {
      if (!isApiError(error) || error.status !== 404) throw error
    }
  }

  if (!conversationId) {
    const existing = (await listAllConversations()).find(
      (conversation) => conversation.title === title,
    )
    conversationId = existing?.id
  }
  if (!conversationId) {
    conversationId = (await createConversation({ title })).id
  }

  const nextRecovery = { ...recovery, conversationId }
  if (conversationId !== recovery.conversationId) {
    onRecoveryChange(nextRecovery)
  }
  return { conversationId, recovery: nextRecovery }
}

export async function prepareFormTaskDraft<TRequest = CreateTaskDraftRequest>({
  recovery,
  request,
  currentDraft,
  onRecoveryChange,
  createDraft,
  reviseDraft,
}: PrepareDraftOptions<TRequest>): Promise<{
  draft: TaskDraft
  recovery: FormTaskDraftRecoveryState
  reusedExistingDraft: boolean
}> {
  const fingerprint = await fingerprintJsonInput(request)
  const previousAttempt = recovery.draftAttempt
  let reusePreviousAttempt = previousAttempt?.fingerprint === fingerprint

  if (reusePreviousAttempt && previousAttempt?.draftId) {
    try {
      const existingDraft = await getTaskDraft(previousAttempt.draftId)
      if (RECOVERABLE_DRAFT_STATUSES.has(existingDraft.status)) {
        return {
          draft: existingDraft,
          recovery,
          reusedExistingDraft: true,
        }
      }
    } catch (error) {
      if (!isApiError(error) || error.status !== 404) throw error
    }
    reusePreviousAttempt = false
  }

  const supportsRevision = reviseDraft !== undefined || createDraft === undefined
  const revisionOfDraftId = supportsRevision
    ? reusePreviousAttempt && previousAttempt
      ? previousAttempt.revisionOfDraftId
      : currentDraft?.draftSource === 'FORM' &&
          currentDraft.status === 'WAITING_CONFIRMATION'
        ? currentDraft.id
        : undefined
    : undefined
  const { idempotencyKey } = reuseOrCreateInputAttempt(
    reusePreviousAttempt ? previousAttempt : undefined,
    fingerprint,
  )
  const pendingRecovery: FormTaskDraftRecoveryState = {
    conversationId: recovery.conversationId,
    draftAttempt: {
      fingerprint,
      idempotencyKey,
      revisionOfDraftId,
    },
  }
  onRecoveryChange(pendingRecovery)

  let draft: TaskDraft
  if (revisionOfDraftId) {
    draft = reviseDraft
      ? await reviseDraft(revisionOfDraftId, request, idempotencyKey)
      : await reviseTaskDraft(
          revisionOfDraftId,
          toFormTaskDraftRevisionRequest(request as CreateTaskDraftRequest),
          idempotencyKey,
        )
  } else {
    draft = createDraft
      ? await createDraft(request, idempotencyKey)
      : await createTaskDraft(request as CreateTaskDraftRequest, idempotencyKey)
  }
  const completedRecovery: FormTaskDraftRecoveryState = {
    conversationId: recovery.conversationId,
    draftAttempt: {
      fingerprint,
      idempotencyKey,
      draftId: draft.id,
    },
  }
  onRecoveryChange(completedRecovery)
  return { draft, recovery: completedRecovery, reusedExistingDraft: false }
}

export async function submitFormTaskDraftConfirmation({
  recovery,
  draft,
  onRecoveryChange,
}: ConfirmDraftOptions): Promise<{
  accepted: ConfirmTaskResponse
  recovery: FormTaskDraftRecoveryState
}> {
  if (!draft.confirmationToken) {
    throw new Error('任务草稿缺少确认令牌。')
  }
  const previousAttempt = recovery.confirmationAttempt
  const sameAttempt =
    previousAttempt?.draftId === draft.id &&
    previousAttempt.inputHash === draft.inputHash &&
    previousAttempt.pricingVersionId === draft.pricingVersionId
  const attempt: FormTaskDraftConfirmationAttempt = {
    draftId: draft.id,
    inputHash: draft.inputHash,
    pricingVersionId: draft.pricingVersionId,
    idempotencyKey: sameAttempt
      ? previousAttempt.idempotencyKey
      : createFormTaskDraftIdempotencyKey(),
  }
  const pendingRecovery = { ...recovery, confirmationAttempt: attempt }
  onRecoveryChange(pendingRecovery)

  const accepted = await confirmTaskDraft(
    draft.id,
    {
      confirmationToken: draft.confirmationToken,
      inputHash: draft.inputHash,
      pricingVersionId: draft.pricingVersionId,
    },
    attempt.idempotencyKey,
  )
  const completedRecovery = {
    ...recovery,
    confirmationAttempt: { ...attempt, taskId: accepted.taskId },
  }
  onRecoveryChange(completedRecovery)
  return { accepted, recovery: completedRecovery }
}

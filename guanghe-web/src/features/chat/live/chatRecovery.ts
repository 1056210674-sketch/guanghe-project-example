interface ChatRecoveryLink {
  messageId: string
  resourceId: string
}

interface ChatConfirmationAttempt {
  messageId: string
  draftId: string
  requestFingerprint: string
  idempotencyKey: string
}

export interface ChatRecoveryState {
  drafts: ChatRecoveryLink[]
  tasks: ChatRecoveryLink[]
  confirmationAttempts: ChatConfirmationAttempt[]
}

const RECOVERY_PREFIX = 'zhixiaohe.chat.recovery.'
const EMPTY_RECOVERY: ChatRecoveryState = {
  drafts: [],
  tasks: [],
  confirmationAttempts: [],
}

function isLink(value: unknown): value is ChatRecoveryLink {
  return (
    typeof value === 'object' &&
    value !== null &&
    'messageId' in value &&
    typeof value.messageId === 'string' &&
    'resourceId' in value &&
    typeof value.resourceId === 'string'
  )
}

function isConfirmationAttempt(value: unknown): value is ChatConfirmationAttempt {
  return (
    typeof value === 'object' &&
    value !== null &&
    'messageId' in value &&
    typeof value.messageId === 'string' &&
    'draftId' in value &&
    typeof value.draftId === 'string' &&
    'requestFingerprint' in value &&
    typeof value.requestFingerprint === 'string' &&
    'idempotencyKey' in value &&
    typeof value.idempotencyKey === 'string' &&
    value.idempotencyKey.length > 0
  )
}

function getStorage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}

export function readChatRecoveryState(conversationId: string): ChatRecoveryState {
  try {
    const raw = getStorage()?.getItem(`${RECOVERY_PREFIX}${conversationId}`)
    if (!raw) return EMPTY_RECOVERY
    const value = JSON.parse(raw) as unknown
    if (typeof value !== 'object' || value === null) return EMPTY_RECOVERY
    const drafts = 'drafts' in value && Array.isArray(value.drafts)
      ? value.drafts.filter(isLink)
      : []
    const tasks = 'tasks' in value && Array.isArray(value.tasks)
      ? value.tasks.filter(isLink)
      : []
    const confirmationAttempts =
      'confirmationAttempts' in value && Array.isArray(value.confirmationAttempts)
        ? value.confirmationAttempts.filter(isConfirmationAttempt)
        : []
    return { drafts, tasks, confirmationAttempts }
  } catch {
    return EMPTY_RECOVERY
  }
}

function uniqueLinks(links: ChatRecoveryLink[]) {
  return Array.from(
    new Map(links.map((link) => [link.messageId, link])).values(),
  )
}

function uniqueConfirmationAttempts(attempts: ChatConfirmationAttempt[]) {
  return Array.from(
    new Map(attempts.map((attempt) => [attempt.messageId, attempt])).values(),
  )
}

export function storeChatRecoveryState(
  conversationId: string,
  state: ChatRecoveryState,
): void {
  try {
    getStorage()?.setItem(
      `${RECOVERY_PREFIX}${conversationId}`,
      JSON.stringify({
        drafts: uniqueLinks(state.drafts),
        tasks: uniqueLinks(state.tasks),
        confirmationAttempts: uniqueConfirmationAttempts(
          state.confirmationAttempts,
        ),
      }),
    )
  } catch {
    // Server messages stay authoritative when browser storage is unavailable.
  }
}

export function upsertRecoveryDraft(
  state: ChatRecoveryState,
  messageId: string,
  draftId: string,
): ChatRecoveryState {
  const previousDraft = state.drafts.find(
    (link) => link.messageId === messageId,
  )?.resourceId
  return {
    ...state,
    drafts: uniqueLinks([
      ...state.drafts,
      { messageId, resourceId: draftId },
    ]),
    confirmationAttempts:
      previousDraft && previousDraft !== draftId
        ? state.confirmationAttempts.filter(
            (attempt) => attempt.messageId !== messageId,
          )
        : state.confirmationAttempts,
  }
}

export function upsertRecoveryTask(
  state: ChatRecoveryState,
  messageId: string,
  taskId: string,
): ChatRecoveryState {
  return {
    drafts: state.drafts.filter((link) => link.messageId !== messageId),
    tasks: uniqueLinks([
      ...state.tasks,
      { messageId, resourceId: taskId },
    ]),
    confirmationAttempts: state.confirmationAttempts.filter(
      (attempt) => attempt.messageId !== messageId,
    ),
  }
}

export function reserveConfirmationAttempt(
  state: ChatRecoveryState,
  messageId: string,
  draftId: string,
  requestFingerprint: string,
  createIdempotencyKey: () => string,
): { state: ChatRecoveryState; idempotencyKey: string } {
  const existing = state.confirmationAttempts.find(
    (attempt) =>
      attempt.messageId === messageId &&
      attempt.draftId === draftId &&
      attempt.requestFingerprint === requestFingerprint,
  )
  if (existing) {
    return { state, idempotencyKey: existing.idempotencyKey }
  }

  const attempt: ChatConfirmationAttempt = {
    messageId,
    draftId,
    requestFingerprint,
    idempotencyKey: createIdempotencyKey(),
  }
  return {
    state: {
      ...state,
      confirmationAttempts: uniqueConfirmationAttempts([
        ...state.confirmationAttempts,
        attempt,
      ]),
    },
    idempotencyKey: attempt.idempotencyKey,
  }
}

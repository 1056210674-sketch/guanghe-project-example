import type { SendConversationMessageRequest } from '../../../api/conversationApi'

export interface MessageAttempt {
  clientMessageId: string
  idempotencyKey: string
  fingerprint: string
}

const prefix = 'zhixiaohe.chat.pending-message.v1.'
const volatile = new Map<string, MessageAttempt>()
const failedWrites = new Set<string>()
const keyFor = (ownerId: string, conversationId: string) => `${prefix}${ownerId}.${conversationId}`

// Persist only opaque operation identity and a one-way payload digest. Message
// text, product details and signed image URLs never enter this recovery record.
export async function messageFingerprint(request: Omit<SendConversationMessageRequest, 'clientMessageId'>): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(request)))
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

export function readMessageAttempt(ownerId: string, conversationId: string): MessageAttempt | undefined {
  const key = keyFor(ownerId, conversationId)
  // A readable store can still reject writes (for example, quota exhaustion).
  // In that case its absent or older record must not replace the current action.
  if (failedWrites.has(key)) return volatile.get(key)
  try {
    const value = JSON.parse(sessionStorage.getItem(key) ?? 'null') as Partial<MessageAttempt> | null
    if (value && typeof value.clientMessageId === 'string' && typeof value.idempotencyKey === 'string'
      && typeof value.fingerprint === 'string') return value as MessageAttempt
    volatile.delete(key)
    return undefined
  } catch { /* Storage may be unavailable; retain same-page recovery. */ }
  return volatile.get(key)
}

export function storeMessageAttempt(ownerId: string, conversationId: string, attempt: MessageAttempt): void {
  const key = keyFor(ownerId, conversationId)
  volatile.set(key, attempt)
  try {
    sessionStorage.setItem(key, JSON.stringify(attempt))
    failedWrites.delete(key)
  } catch {
    failedWrites.add(key)
  }
}

export function clearMessageAttempt(ownerId: string, conversationId: string): void {
  const key = keyFor(ownerId, conversationId)
  volatile.delete(key)
  failedWrites.delete(key)
  try { sessionStorage.removeItem(key) } catch { /* In-memory fallback. */ }
}

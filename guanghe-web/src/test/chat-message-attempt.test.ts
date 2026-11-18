import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearMessageAttempt, readMessageAttempt, storeMessageAttempt } from '../features/chat/live/messageAttempt'

const owner = 'storage-recovery-user'
const conversation = 'storage-recovery-conversation'
const original = { clientMessageId: 'original-message', idempotencyKey: 'original-key', fingerprint: 'original-digest' }
const replacement = { clientMessageId: 'next-message', idempotencyKey: 'next-key', fingerprint: 'next-digest' }

afterEach(() => {
  vi.restoreAllMocks()
  clearMessageAttempt(owner, conversation)
  sessionStorage.clear()
})

describe('message operation identity when browser storage rejects writes', () => {
  it('retains the same in-memory operation when setItem fails but getItem returns null', () => {
    sessionStorage.clear()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('Full', 'QuotaExceededError') })

    storeMessageAttempt(owner, conversation, original)

    expect(sessionStorage.getItem(`zhixiaohe.chat.pending-message.v1.${owner}.${conversation}`)).toBeNull()
    expect(readMessageAttempt(owner, conversation)).toEqual(original)
    expect(readMessageAttempt(owner, conversation)).toEqual(original)
  })

  it('prefers the current in-memory operation over an older readable storage value', () => {
    storeMessageAttempt(owner, conversation, original)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('Full', 'QuotaExceededError') })

    storeMessageAttempt(owner, conversation, replacement)

    expect(readMessageAttempt(owner, conversation)).toEqual(replacement)
  })

  it('stops using the fallback after persistence succeeds, so a later storage clear is respected', () => {
    const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('Full', 'QuotaExceededError') })
    storeMessageAttempt(owner, conversation, original)
    write.mockRestore()
    storeMessageAttempt(owner, conversation, replacement)

    sessionStorage.clear()

    expect(readMessageAttempt(owner, conversation)).toBeUndefined()
  })
})

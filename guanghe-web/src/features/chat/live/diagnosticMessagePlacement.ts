import type { ChatMessageView } from '../types'

const diagnosticEnvelopeFields = new Set(['id', 'role', 'content', 'createdAt', 'geoDiagnostic'])

/** Only Java's dedicated, completed diagnostic messages may share a visual slot. */
export function geoDiagnosticMessageKey(message: ChatMessageView): string | undefined {
  if (message.role !== 'ASSISTANT' || !message.geoDiagnostic || message.content.trim()) return undefined
  // Keep mixed messages intact, including any new payload type introduced later.
  if (Object.entries(message).some(([field, value]) => !diagnosticEnvelopeFields.has(field)
    && value !== undefined && value !== null && !(field === 'outputStreaming' && value === false))) return undefined
  return `geo-diagnostic:${JSON.stringify([message.geoDiagnostic.conversationId, message.geoDiagnostic.diagnosticId])}`
}

/** Retain the first stable message anchor; later snapshots are read by its diagnostic container. */
export function placeGeoDiagnosticMessages(messages: readonly ChatMessageView[]): readonly ChatMessageView[] {
  const seen = new Set<string>()
  return messages.filter((message) => {
    const key = geoDiagnosticMessageKey(message)
    if (!key) return true
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

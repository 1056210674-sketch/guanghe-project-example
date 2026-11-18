export function safeAgreementUrl(value: string): string | undefined {
  const trimmed = value.trim()
  if (!trimmed) return undefined
  if (trimmed.startsWith('/') && !trimmed.startsWith('//')) return trimmed
  try {
    const url = new URL(trimmed)
    if (url.protocol !== 'https:' || url.username || url.password) return undefined
    return url.toString()
  } catch {
    return undefined
  }
}

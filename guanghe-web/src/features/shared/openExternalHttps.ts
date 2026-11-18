export function openExternalHttps(url: string): boolean {
  let parsedUrl: URL

  try {
    parsedUrl = new URL(url)
  } catch {
    return false
  }

  if (parsedUrl.protocol !== 'https:') {
    return false
  }

  window.open(parsedUrl.href, '_blank', 'noopener,noreferrer')
  return true
}

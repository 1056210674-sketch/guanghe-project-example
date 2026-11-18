const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? ''

/**
 * The browser uses the current origin by default. A base URL is useful for
 * isolated local checks, but callers still pass only Java `/api/v1/**` paths.
 */
export const API_BASE_URL = configuredApiBaseUrl.replace(/\/+$/, '')

export function resolveApiUrl(path: string): string {
  if (!path.startsWith('/api/v1/')) {
    throw new Error('Only Java /api/v1/** browser endpoints are allowed.')
  }

  return `${API_BASE_URL}${path}`
}

import { useQuery } from '@tanstack/react-query'
import { getGeoDiagnostic, type GeoDiagnostic } from '../../api/geoDiagnosticApi'
import { ApiError } from '../../api/apiError'
import { captureAuthSession } from '../../auth/tokenStorage'

interface DiagnosticScope {
  diagnosticId: string
  conversationId: string
  projectId?: string
}

const businessId = (value: unknown): value is string => typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value)

/** Scope comes from the authenticated, owner-keyed caller, never from a cached response. */
export function isScopedGeoDiagnostic(value: unknown, scope: DiagnosticScope): value is GeoDiagnostic {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const record = value as Partial<GeoDiagnostic>
  return businessId(record.id) && record.id === scope.diagnosticId
    && businessId(record.conversationId) && record.conversationId === scope.conversationId
    && businessId(record.projectId) && (!scope.projectId || record.projectId === scope.projectId)
    && typeof record.version === 'number' && Number.isSafeInteger(record.version) && record.version > 0
}

export function requireScopedGeoDiagnostic(value: unknown, scope: DiagnosticScope): GeoDiagnostic {
  if (!isScopedGeoDiagnostic(value, scope)) throw new ApiError({ status: 403, code: 'FORBIDDEN', message: '这个诊断记录不属于当前聊天或项目。' })
  return value
}

/** Fence at the cache write itself, not at GET completion, so a late read cannot undo an acknowledged action. */
export function mergeGeoDiagnostic(previous: unknown, incoming: unknown, scope: DiagnosticScope): GeoDiagnostic {
  const next = requireScopedGeoDiagnostic(incoming, scope)
  if (!isScopedGeoDiagnostic(previous, scope)) return next
  if (previous.projectId !== next.projectId) throw new ApiError({ status: 403, code: 'FORBIDDEN', message: '这个诊断记录的项目不匹配。' })
  return previous.version > next.version ? previous : next
}

const ownerSession = (owner?: string) => {
  const session = captureAuthSession()
  // The opaque revision/generation are not credentials. A new login cannot reuse an old session's private cache.
  return ['geo-diagnostics', owner ?? null, session.revision, session.generation] as const
}
export const diagnosticKeys = {
  detail: (owner?: string, id?: string) => [...ownerSession(owner), 'detail', id ?? null] as const,
  list: (owner?: string, conversationId?: string) => [...ownerSession(owner), 'list', conversationId ?? null] as const,
  cardHead: (owner?: string, id?: string) => [...ownerSession(owner), 'card-head', id ?? null] as const,
  projectList: (owner?: string, projectId?: string, page = 1) => [...ownerSession(owner), 'project-list', projectId ?? null, page] as const,
  report: (owner?: string, id?: string) => [...ownerSession(owner), 'report', id ?? null] as const,
  observations: (owner?: string, id?: string, version?: number, page = 1) => [...ownerSession(owner), 'observations', id ?? null, version ?? null, page] as const,
  receipt: (owner?: string, id?: string) => [...ownerSession(owner), 'submit-receipt', id ?? null] as const,
  action: (owner?: string, id?: string) => [...ownerSession(owner), 'action', id ?? null] as const,
  reportAction: (owner?: string, id?: string) => [...ownerSession(owner), 'report-action', id ?? null] as const,
  platforms: (owner?: string) => [...ownerSession(owner), 'platforms'] as const,
}

export const diagnosticDeliveryActive = (status?: string) => Boolean(status && !['READY', 'ANALYSIS_FAILED', 'PDF_FAILED'].includes(status))

export function useGeoDiagnostic(ownerId: string | undefined, diagnosticId: string, conversationId: string, step: number, messageId: string, polling: 'current-card' | 'deck' | 'none' = 'current-card') {
  const scope = { diagnosticId, conversationId }
  return useQuery({
    queryKey: diagnosticKeys.detail(ownerId, diagnosticId),
    queryFn: async () => requireScopedGeoDiagnostic(await getGeoDiagnostic(diagnosticId, captureAuthSession()), scope),
    structuralSharing: (previous, incoming) => mergeGeoDiagnostic(previous, incoming, scope),
    enabled: Boolean(ownerId && diagnosticId && conversationId),
    retry: false,
    refetchInterval: (query) => polling !== 'none' && !query.state.error && query.state.data
      && (polling === 'deck' || query.state.data.step === step && query.state.data.cards.filter((card) => card.step === step).at(-1)?.messageId === messageId)
      && (['QUEUED', 'RUNNING'].includes(query.state.data.generation?.status ?? '') || diagnosticDeliveryActive(query.state.data.delivery?.status)) ? 2500 : false,
    refetchIntervalInBackground: false,
  })
}

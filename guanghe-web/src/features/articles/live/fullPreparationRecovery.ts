import type { ArticleAuthorizationCreate, ArticleFullStartRequest } from '../../../api/articleFullApi'

export interface FullPreparationAttempt {
  key: string
  sourceRunId: string
  geoReportId?: string
  authorization: ArticleAuthorizationCreate
  request?: Omit<ArticleFullStartRequest, 'requestText'>
}
interface Saved { revision: string | null; attempt?: FullPreparationAttempt; preparationId?: string }
const keyFor = (owner: string, conversation: string) => `zhixiaohe.article-full.v1.${owner}.${conversation}`
const memory = new Map<string, Saved>()
const validId = (value: unknown): value is string => typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value)
const object = (value: unknown): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value))
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9-]{36}$/i.test(value)

/** Recovery stores IDs and bounded authority only, never chat text or document bodies. Java owns all admission checks. */
export function readFullPreparationRecovery(owner: string, conversation: string, revision: string | null): Saved | undefined {
  const key = keyFor(owner, conversation)
  let raw: unknown = memory.get(key)
  try { const value = sessionStorage.getItem(key); if (value && value.length < 8192) raw = JSON.parse(value) } catch { /* In-memory recovery remains available. */ }
  if (!object(raw) || !revision || raw.revision !== revision) return
  if (validId(raw.preparationId)) return { revision, preparationId: raw.preparationId }
  const a = raw.attempt
  if (!object(a) || !uuid(a.key) || !uuid(a.sourceRunId) || !object(a.authorization)) return
  if (a.geoReportId !== undefined && !validId(a.geoReportId)) return
  const g = a.authorization
  if (!validId(g.spaceId) || !(g.productId == null || validId(g.productId)) || !Number.isSafeInteger(g.maxPoints) || Number(g.maxPoints) < 1 || Number(g.maxPoints) > 100000 || typeof g.expiresAt !== 'string' || !Number.isFinite(Date.parse(g.expiresAt))) return
  const attempt: FullPreparationAttempt = { key: a.key, sourceRunId: a.sourceRunId, ...(a.geoReportId ? { geoReportId: a.geoReportId as string } : {}), authorization: { spaceId: g.spaceId, productId: g.productId as string | null | undefined, maxPoints: Number(g.maxPoints), expiresAt: g.expiresAt } }
  if (object(a.request) && validId(a.request.grantId) && Number.isSafeInteger(a.request.grantVersion) && Number(a.request.grantVersion) >= 1 && a.request.sourceRunId === a.sourceRunId && a.request.spaceId === g.spaceId && (a.request.productId ?? null) === (g.productId ?? null) && (a.request.geoReportId ?? null) === (a.geoReportId ?? null)) {
    attempt.request = { sourceRunId: a.sourceRunId, spaceId: g.spaceId, productId: g.productId as string | null | undefined, ...(attempt.geoReportId ? { geoReportId: attempt.geoReportId } : {}), grantId: a.request.grantId, grantVersion: Number(a.request.grantVersion) }
  }
  return { revision, attempt }
}
export function storeFullPreparationRecovery(owner: string, conversation: string, value: Saved) {
  const key = keyFor(owner, conversation)
  memory.set(key, value)
  try { sessionStorage.setItem(key, JSON.stringify(value)) } catch { /* Same-page retries remain idempotent. */ }
}
export function clearFullPreparationRecovery(owner: string, conversation: string) {
  const key = keyFor(owner, conversation)
  memory.delete(key)
  try { sessionStorage.removeItem(key) } catch { /* Does not revoke server authority. */ }
}

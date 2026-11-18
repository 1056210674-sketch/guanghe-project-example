import { useEffect, useRef, useState } from 'react'
import { isApiError } from '../../api/apiError'
import { captureAuthSession, isCurrentAuthSession, subscribeToAuthSessionChanges } from '../../auth/tokenStorage'
import { clearFullPreparationRecovery, readFullPreparationRecovery, storeFullPreparationRecovery, type FullPreparationAttempt } from '../../features/articles/live/fullPreparationRecovery'
import { invokeFullArticle } from './fullArticleInvocation'
import type { ArticleFullMode } from './ArticleFullModeControl'

interface Options { ownerId?: string; conversationId?: string; isScopeCurrent: (id: string) => boolean; onResult: (id: string) => void }
const expiry = () => new Date(Date.now() + 15 * 60 * 1000).toISOString()
export function useFullArticleInvocation(options: Options) {
  const alive = useRef(true)
  const lock = useRef(false)
  const epoch = useRef(0)
  const [pending, setPending] = useState<{ conversationId: string; attempt: FullPreparationAttempt }>()
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string>()
  const [resultId, setResultId] = useState<string>()
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  useEffect(() => {
    const reset = () => { epoch.current += 1; lock.current = false; setBusy(false); setPending(undefined); setNotice(undefined); setResultId(undefined) }
    reset()
    return subscribeToAuthSessionChanges(reset)
  }, [options.ownerId])
  async function run(conversationId: string, attempt: FullPreparationAttempt) {
    if (!options.ownerId || lock.current) return
    const owner = options.ownerId
    const session = captureAuthSession()
    const startedEpoch = epoch.current
    const current = () => alive.current && epoch.current === startedEpoch && isCurrentAuthSession(session) && options.isScopeCurrent(conversationId)
    if (!current()) return
    lock.current = true; setBusy(true); setNotice('正在核对本次需求，完成后会继续准备全文。不会发布文章。')
    setPending({ conversationId, attempt }); storeFullPreparationRecovery(owner, conversationId, { revision: session.revision, attempt })
    try {
      const prepared = await invokeFullArticle({ ownerId: owner, conversationId, session, attempt, current, onPending: (next) => { if (current()) setPending({ conversationId, attempt: next }) } })
      if (!current()) return
      storeFullPreparationRecovery(owner, conversationId, { revision: session.revision, preparationId: prepared.id })
      setPending(undefined); setResultId(prepared.id); setNotice(undefined); options.onResult(prepared.id)
    } catch (error) {
      if (!current()) return
      const uncertain = isApiError(error) && ![400, 401, 403, 404, 409, 410, 422, 429].includes(error.status)
      if (!uncertain) { clearFullPreparationRecovery(owner, conversationId); setPending(undefined) }
      setNotice(uncertain ? '文章受理结果尚未确定，请恢复同一次请求，不会另建重复文章。' : error instanceof Error ? error.message : '未完成本次文章请求，请稍后重试。')
    } finally { if (epoch.current === startedEpoch) { lock.current = false; if (alive.current && isCurrentAuthSession(session)) setBusy(false) } }
  }
  const ownerId = options.ownerId
  const conversationId = options.conversationId
  // Recovery is a continuation of a saved explicit USER invocation, not an unsolicited new Run.
  useEffect(() => {
    if (!ownerId || !conversationId) return
    const saved = readFullPreparationRecovery(ownerId, conversationId, captureAuthSession().revision)
    if (saved?.attempt) void run(conversationId, saved.attempt)
    // A completed result is accessed through the explicit toolbar result link, not auto-navigation.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ownerId, conversationId])
  const visiblePending = pending?.conversationId === conversationId ? pending : undefined
  return { busy, pending: visiblePending, notice: pending && pending.conversationId !== conversationId ? undefined : notice, resultId,
    invoke: (id: string, runId: string, mode: ArticleFullMode, geoReportId?: string) => run(id, { key: crypto.randomUUID(), sourceRunId: runId, ...(geoReportId ? { geoReportId } : {}), authorization: { spaceId: mode.spaceId, productId: mode.productId ?? null, maxPoints: mode.maxPoints, expiresAt: expiry() } }),
    resume: () => pending ? run(pending.conversationId, pending.attempt) : Promise.resolve(),
    openResult: () => { const saved = ownerId && conversationId ? readFullPreparationRecovery(ownerId, conversationId, captureAuthSession().revision) : undefined; if (resultId ?? saved?.preparationId) options.onResult((resultId ?? saved?.preparationId)!) },
  }
}

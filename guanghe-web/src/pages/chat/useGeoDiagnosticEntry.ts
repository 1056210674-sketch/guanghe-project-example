import { useEffect, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { createGeoDiagnostic, listGeoDiagnostics } from '../../api/geoDiagnosticApi'
import { isApiError } from '../../api/apiError'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import { chatKeysFor } from '../../features/chat/live/chatQueries'
import { diagnosticKeys, mergeGeoDiagnostic, requireScopedGeoDiagnostic } from '../../features/geo-diagnostics/queries'

interface Options {
  ownerId?: string
  scopeKey: string
  conversationId?: string
  projectId?: string
  enabled: boolean
  onEnsureConversation: () => Promise<string | undefined>
  onNotice: (text: string) => void
}

/** Explicit fixed entry; a diagnostic never creates a normal chat Run. */
export function useGeoDiagnosticEntry(options: Options) {
  const client = useQueryClient()
  const active = useRef(options)
  const lock = useRef(false)
  useEffect(() => {
    active.current = options
    return () => { active.current = { ...options, enabled: false, scopeKey: '', conversationId: undefined } }
  }, [options])

  async function open() {
    if (lock.current || !options.enabled || !options.ownerId) return
    if (!options.projectId) { options.onNotice('请先选择客户项目，再准备这个品牌的诊断报告。'); return }
    const session = captureAuthSession()
    const scope = options.scopeKey
    const owner = options.ownerId
    lock.current = true
    let targetId: string | undefined
    const current = () => isCurrentAuthSession(session) && active.current.ownerId === owner
      && (active.current.scopeKey === scope || Boolean(targetId && active.current.conversationId === targetId))
    try {
      targetId = await options.onEnsureConversation()
      if (!targetId || !current()) return
      const existing = await listGeoDiagnostics(targetId, session)
      if (!current()) return
      const latest = existing.items.map((record) => requireScopedGeoDiagnostic(record, {
        diagnosticId: record.id, conversationId: targetId!, projectId: options.projectId,
      })).sort((a, b) => BigInt(a.id) < BigInt(b.id) ? -1 : BigInt(a.id) > BigInt(b.id) ? 1 : 0).at(-1)
      const storageKey = `zxh.diagnostic-create.${owner}.${targetId}`
      let requestId: string | undefined
      try { requestId = sessionStorage.getItem(storageKey) ?? undefined } catch { /* Request identity also survives in this invocation. */ }
      if (!requestId || !/^[0-9a-f-]{36}$/i.test(requestId)) requestId = crypto.randomUUID()
      try { sessionStorage.setItem(storageKey, requestId) } catch { /* No credentials or customer data are stored. */ }
      const diagnostic = latest ?? await createGeoDiagnostic({ conversationId: targetId, requestId }, session)
      if (!current()) return
      const diagnosticScope = { diagnosticId: diagnostic.id, conversationId: targetId, projectId: options.projectId }
      requireScopedGeoDiagnostic(diagnostic, diagnosticScope)
      client.setQueryData(diagnosticKeys.detail(owner, diagnostic.id), (previous: unknown) => mergeGeoDiagnostic(previous, diagnostic, diagnosticScope))
      await client.invalidateQueries({ queryKey: chatKeysFor(owner).messages(targetId), exact: true })
      if (!current()) return
      try { sessionStorage.removeItem(storageKey) } catch { /* A received result is sufficient to recover from Java. */ }
      options.onNotice(latest?.delivery ? '已恢复这个聊天的诊断后台进度，可查看报告或显式新建另一份诊断。' : latest ? '已恢复这个聊天的诊断卡片，可继续填写。' : '诊断卡片已准备，请填写品牌名称。')
    } catch (error) {
      if (current()) options.onNotice(isApiError(error) && [401, 403, 404].includes(error.status)
        ? '这个项目或聊天暂时不可用于诊断，请重新打开。' : '诊断卡片暂时无法打开，输入内容已保留，请重试。')
    } finally { lock.current = false }
  }

  return { open }
}

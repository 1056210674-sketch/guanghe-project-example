import { useCallback, useEffect, useRef, useState } from 'react'
import { isApiError } from '../../api/apiError'
import { saveCanvas, type CanvasDocument, type CanvasView } from '../../api/canvasApi'

export function canvasError(error: unknown) { return isApiError(error) ? error.message : error instanceof Error ? error.message : '操作失败，请重试。' }
type Edit = { name: string; document: CanvasDocument }
export function useCanvasDocument(initial: CanvasView, ownerId: string) {
  const [edit, setEdit] = useState<Edit>({ name: initial.name, document: initial.document })
  const current = useRef(edit)
  const server = useRef(initial)
  const saved = useRef(JSON.stringify(edit))
  const pending = useRef<Promise<void> | null>(null)
  const mounted = useRef(true)
  const history = useRef<{ past: Edit[]; future: Edit[] }>({ past: [], future: [] })
  const [status, setStatus] = useState('已保存')
  const [error, setError] = useState<string>()
  const [conflict, setConflict] = useState(false)
  const blocked = useRef(false)
  const key = `zhixiaohe.canvas.${ownerId}.${initial.id}`
  const [recovery, setRecovery] = useState<Edit | undefined>(() => {
    try { const data = JSON.parse(localStorage.getItem(key) ?? 'null'); return data?.edit && Array.isArray(data.edit.document?.nodes) && Array.isArray(data.edit.document?.edges) ? data.edit as Edit : undefined } catch { return undefined }
  })
  const update = useCallback((next: Edit, record = true) => {
    if (JSON.stringify(next) === JSON.stringify(current.current)) return
    if (record) { history.current.past = [...history.current.past.slice(-39), current.current]; history.current.future = [] }
    current.current = next; setEdit(next); setStatus('未保存')
    try { localStorage.setItem(key, JSON.stringify({ baseVersion: server.current.version, edit: next })) } catch { /* server save remains authoritative */ }
  }, [key])
  const flush = useCallback(async () => {
    if (pending.current) await pending.current
    if (blocked.current) throw new Error('画布已在其他窗口更新。当前编辑已保留，请下载草稿后重新加载。')
    const run = async () => {
      while (saved.current !== JSON.stringify(current.current)) {
        const snapshot = current.current
        if (mounted.current) { setStatus('保存中'); setError(undefined) }
        try {
          const response = await saveCanvas(initial.id, { version: server.current.version, ...snapshot })
          server.current = response; saved.current = JSON.stringify(snapshot)
          try {
            if (saved.current === JSON.stringify(current.current)) localStorage.removeItem(key)
            else localStorage.setItem(key, JSON.stringify({ baseVersion: response.version, edit: current.current }))
          } catch { /* Server save succeeded; a full local cache must not misreport it. */ }
        } catch (failure) {
          if (isApiError(failure) && failure.status === 409) { blocked.current = true; if (mounted.current) setConflict(true) }
          if (mounted.current) { setStatus('保存失败'); setError(canvasError(failure)) }
          throw failure
        }
      }
      if (mounted.current) setStatus('已保存')
    }
    const attempt = run(); pending.current = attempt
    try { await attempt } finally { if (pending.current === attempt) pending.current = null }
    return server.current
  }, [initial.id, key])
  useEffect(() => { const timer = setTimeout(() => { void flush().catch(() => {}) }, 800); return () => clearTimeout(timer) }, [edit, flush])
  useEffect(() => {
    mounted.current = true
    const beforeUnload = (e: BeforeUnloadEvent) => { if (saved.current !== JSON.stringify(current.current)) e.preventDefault() }
    window.addEventListener('beforeunload', beforeUnload)
    return () => { mounted.current = false; window.removeEventListener('beforeunload', beforeUnload); void flush().catch(() => {}) }
  }, [flush])
  const undo = () => { const previous = history.current.past.pop(); if (previous) { history.current.future.push(current.current); update(previous, false) } }
  const redo = () => { const next = history.current.future.pop(); if (next) { history.current.past.push(current.current); update(next, false) } }
  return { ...edit, status, error, conflict, update, flush, undo, redo, recovery, recover: () => { if (recovery) update(recovery); setRecovery(undefined) }, version: () => server.current.version, read: () => current.current }
}

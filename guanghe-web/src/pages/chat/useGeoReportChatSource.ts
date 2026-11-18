import { useCallback, useEffect, useRef, useState } from 'react'
import { getGeoReport, getGeoTopic } from '../../api/geoApi'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import { formatGeoDateTime } from '../../features/geo/presentation'

export const GEO_REPORT_QUESTION = '请分析一下这份监测报告。'
export const GEO_VISIBILITY_GOAL = '分析当前商品的AI可见度：优先解读已有报告；没有报告时先拟监测问题、推荐可用平台并给出报价，等我确认后再监测。'

interface ReportSource {
  reportId: string
  topicId: string
  label: string
  spaceId: string
  productId?: string
  ownerId: string
  conversationKey: string
  createdConversationId?: string
}

/** Only a validated reference is retained; answers, citations and synthesis never enter the composer. */
export function useGeoReportChatSource({ ownerId, conversationId, projectId, requestedReportId, onLoaded }: {
  ownerId?: string
  conversationId?: string
  projectId?: string
  requestedReportId: string | null
  onLoaded: (source: { spaceId: string; productId?: string }, origin: 'link' | 'picker') => void
}) {
  const [source, setSource] = useState<ReportSource>()
  const [notice, setNotice] = useState<string>()
  const [loading, setLoading] = useState(false)
  const generation = useRef(0)
  const onLoadedRef = useRef(onLoaded)
  useEffect(() => { onLoadedRef.current = onLoaded }, [onLoaded])

  const clear = useCallback(() => {
    generation.current += 1
    setSource(undefined)
    setNotice(undefined)
    setLoading(false)
  }, [])
  const scopeMatches = source && source.ownerId === ownerId
    && (!projectId || source.spaceId === projectId)
    && (source.conversationKey === (conversationId ?? 'new') || Boolean(source.createdConversationId && source.createdConversationId === conversationId))
  // Do not let an old reference reappear when returning to another conversation.
  if (source && !scopeMatches) setSource(undefined)
  else if (source?.createdConversationId && source.createdConversationId === conversationId) {
    setSource({ ...source, conversationKey: source.createdConversationId, createdConversationId: undefined })
  }

  const loadReport = useCallback(async (reportId: string, expectedProjectId: string | undefined, origin: 'link' | 'picker') => {
    if (!ownerId) return false
    const requestGeneration = ++generation.current
    const session = captureAuthSession()
    setLoading(true)
    setNotice(undefined)
    try {
      if (!/^[1-9]\d{0,18}$/.test(reportId) || (origin === 'picker' && !expectedProjectId)) throw new Error('Invalid reference')
      const report = await getGeoReport(reportId)
      if (requestGeneration !== generation.current || !isCurrentAuthSession(session)) return false
      const topic = await getGeoTopic(report.topicId)
      if (requestGeneration !== generation.current || !isCurrentAuthSession(session)) return false
      if (report.id !== reportId || report.topic.id !== report.topicId || topic.id !== report.topicId
        || topic.spaceId !== report.topic.spaceId || report.archivedAt || !['SUCCEEDED', 'PARTIAL'].includes(report.status)
        || (expectedProjectId && report.topic.spaceId !== expectedProjectId)
        || (projectId && report.topic.spaceId !== projectId)) throw new Error('Unavailable reference')
      const next: ReportSource = {
        reportId: report.id, topicId: report.topicId,
        label: `${report.topic.name} · ${formatGeoDateTime(report.generatedAt)}`,
        // The saved report snapshot, not today's edited topic, owns the product scope.
        spaceId: report.topic.spaceId, productId: report.topic.productId ?? undefined,
        ownerId, conversationKey: conversationId ?? 'new',
      }
      onLoadedRef.current(next, origin)
      setSource(next)
      return true
    } catch {
      if (requestGeneration === generation.current && isCurrentAuthSession(session)) {
        setNotice('这份报告已归档、不可访问或暂时无法读取，请重新选择或移除引用。')
      }
      return false
    } finally {
      if (requestGeneration === generation.current) setLoading(false)
    }
  }, [conversationId, ownerId, projectId])

  useEffect(() => {
    generation.current += 1
    // eslint-disable-next-line react-hooks/set-state-in-effect -- Reconcile URL/auth/project lifecycle and cancel late report requests.
    setLoading(false)
    setNotice(undefined)
    const invalidate = () => { generation.current += 1 }
    if (!ownerId || !requestedReportId) return invalidate
    setSource(undefined)
    if (conversationId) {
      setNotice('请从输入框的加号重新选择报告，或移除来源后继续当前会话。')
      return invalidate
    }
    void loadReport(requestedReportId, projectId, 'link')
    return invalidate
  }, [conversationId, loadReport, ownerId, projectId, requestedReportId])

  return {
    source: scopeMatches && !source?.createdConversationId ? source : undefined,
    notice, loading, clear,
    selectReport: (reportId: string, expectedProjectId: string) => loadReport(reportId, expectedProjectId, 'picker'),
    cancelSelection: () => { generation.current += 1; setLoading(false) },
    // First send creates a conversation, but remains the same explicit user operation.
    bindCreatedConversation: (id: string) => setSource((current) => current && current.ownerId === ownerId && current.conversationKey === 'new'
      ? { ...current, createdConversationId: id } : undefined),
  }
}

import { FileTextOutlined } from '@ant-design/icons'
import { Alert, Button, InputNumber, Modal, Select, Typography } from 'antd'
import { useEffect, useRef, useState } from 'react'
import type { ConversationMessage } from '../../api/conversationApi'
import { invokeFullArticle } from './fullArticleInvocation'
import { isApiError } from '../../api/apiError'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import { clearFullPreparationRecovery, readFullPreparationRecovery, storeFullPreparationRecovery, type FullPreparationAttempt } from '../../features/articles/live/fullPreparationRecovery'

interface Props {
  ownerId: string
  conversationId: string
  projectId?: string
  productId?: string
  spaces: readonly { id: string; name: string }[]
  messages: readonly ConversationMessage[]
  disabled: boolean
  resultOnly?: boolean
  onOpenResult: (id: string) => void
}
const userText = (message?: ConversationMessage) => typeof message?.content.text === 'string' ? message.content.text.trim() : ''
const articleAuthorizationExpiry = () => new Date(Date.now() + 15 * 60 * 1000).toISOString()

/** A user click authorizes one bounded article. Model replies never call this write path. */
export function ArticleFullAction(props: Props) {
  const [session] = useState(captureAuthSession)
  const [recovery] = useState(() => readFullPreparationRecovery(props.ownerId, props.conversationId, session.revision))
  const [attempt, setAttempt] = useState<FullPreparationAttempt | undefined>(recovery?.attempt)
  const [resultId, setResultId] = useState(recovery?.preparationId)
  const [open, setOpen] = useState(false)
  const [spaceId, setSpaceId] = useState(props.projectId)
  const [maxPoints, setMaxPoints] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const lock = useRef(false)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const current = () => alive.current && isCurrentAuthSession(session)
  const latestUser = [...props.messages].reverse().find((item) => item.role === 'USER' && item.runId && userText(item))
  const source = attempt ? props.messages.find((item) => item.role === 'USER' && item.runId === attempt.sourceRunId) : latestUser
  const rawReportId = source?.content.geoReportId
  const sourceGeoContext = props.messages.find((item) => item.role === 'ASSISTANT' && item.runId === source?.runId)?.content.geoContext
  // Legacy responses expose Java-frozen GEO context, not arbitrary model text or the newest report.
  const originalReportId = typeof rawReportId === 'string' && /^[1-9]\d{0,18}$/.test(rawReportId) ? rawReportId
    : sourceGeoContext?.selectionSource === 'EXPLICIT_REPORT' ? sourceGeoContext.report?.reportId : undefined
  const runCompleted = source?.runId && props.messages.some((item) => item.role === 'ASSISTANT' && item.runId === source.runId && item.status === 'COMPLETED')
  async function start() {
    if (lock.current || props.disabled || !current() || !source?.runId || !runCompleted || !userText(source) || userText(source).length > 4000) return
    if (!attempt && (!spaceId || !maxPoints || !Number.isSafeInteger(maxPoints) || maxPoints < 1 || maxPoints > 100000)) return
    lock.current = true; setBusy(true); setError(undefined)
    const next = attempt ?? { key: crypto.randomUUID(), sourceRunId: source.runId, ...(originalReportId ? { geoReportId: originalReportId } : {}), authorization: { spaceId: spaceId!, productId: props.productId ?? null, maxPoints: maxPoints!, expiresAt: articleAuthorizationExpiry() } }
    const persist = () => { storeFullPreparationRecovery(props.ownerId, props.conversationId, { revision: session.revision, attempt: next }); if (current()) setAttempt(next) }
    persist()
    try {
      const preparation = await invokeFullArticle({ ownerId: props.ownerId, conversationId: props.conversationId, session, attempt: next, current, onPending: (value) => { if (current()) setAttempt(value) } })
      if (!current()) return
      storeFullPreparationRecovery(props.ownerId, props.conversationId, { revision: session.revision, preparationId: preparation.id })
      setResultId(preparation.id); setAttempt(undefined); setOpen(false)
      props.onOpenResult(preparation.id)
    } catch (problem) {
      if (!current()) return
      if (isApiError(problem) && [400, 403, 404, 409, 410, 422, 429].includes(problem.status)) {
        clearFullPreparationRecovery(props.ownerId, props.conversationId); setAttempt(undefined)
        setError(problem.message)
      } else setError('受理结果尚未确定。请重试同一次请求，不会另建重复文章；也可先到任务中心核对。')
    } finally { lock.current = false; if (current()) setBusy(false) }
  }
  return <>
    {!props.resultOnly ? <Button size="small" icon={<FileTextOutlined />} disabled={props.disabled} onClick={() => { setError(undefined); setOpen(true) }}>准备完整文章</Button> : null}
    {resultId ? <Button size="small" type="link" onClick={() => props.onOpenResult(resultId)}>查看文章准备结果</Button> : null}
    <Modal title="准备一篇完整文章" open={open} onCancel={() => { if (!busy) setOpen(false) }} okText={attempt ? '重试同一次请求' : '开始准备全文'} cancelText="取消" confirmLoading={busy}
      okButtonProps={{ disabled: props.disabled || !runCompleted || !source || (!attempt && (!spaceId || !maxPoints)) }} onOk={() => void start()}>
      {!runCompleted ? <Alert type="info" title="先在对话中说明这篇文章的目标，等回复结束后再开始。" /> : <Typography.Paragraph className="article-full-source">本次目标：{userText(source)}</Typography.Paragraph>}
      {props.projectId ? <Typography.Paragraph>资料所属项目：{props.spaces.find((item) => item.id === props.projectId)?.name ?? '当前项目'}</Typography.Paragraph> : <Select aria-label="文章所属项目" placeholder="选择文章所属项目" value={spaceId} disabled={busy || Boolean(attempt)} options={props.spaces.map((item) => ({ value: item.id, label: item.name }))} onChange={setSpaceId} style={{ width: '100%', marginBottom: 16 }} />}
      <Typography.Paragraph>单篇创作积分上限</Typography.Paragraph><InputNumber aria-label="单篇创作积分上限" min={1} max={100000} precision={0} value={attempt?.authorization.maxPoints ?? maxPoints} disabled={busy || Boolean(attempt)} onChange={setMaxPoints} />
      <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>系统连续完成标题、结构和正文，实际费用以受理报价为准，超过上限不会开始。这里只准备文章，媒体发布仍需最后确认。</Typography.Paragraph>
      {attempt && !source ? <Alert type="warning" title="正在恢复原消息。请加载该任务的较早消息后重试，不能换成另一条消息执行。" /> : null}
      {error ? <Alert type="error" showIcon title={error} /> : null}
    </Modal>
  </>
}

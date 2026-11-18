import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { ChatMessageView } from '../types'
import { GeoMascotAvatar } from './GeoMascotAvatar'
import { GEO_PENDING_REPLY as PENDING_REPLY, MascotContext, useGeoMascotConversation } from './geoMascotContext'
import './geoMascot.css'

type Action = 'idle' | 'greeting' | 'thinking' | 'speaking' | 'thought-complete' | 'happy'
type Work = { id: string; status: 'active' | 'success' | 'other'; kind: 'reply' | 'task' | 'article'; updatedAt?: string; observedAt?: number }
type Pose = { action: Action; key: number; label: string; work?: Work; messageId?: string }

export interface GeoMascotProps {
  messages: readonly ChatMessageView[]
  /** Fresh task facts mapped by the container; cached timelines are not truth. */
  backgroundWork?: readonly { id: string; status: Work['status']; updatedAt?: string }[]
  busy: boolean
  stopping?: boolean
  failed?: boolean
  loading?: boolean
  enabled?: boolean
  children?: ReactNode
}

function messageForWork(work: Work, messages: readonly ChatMessageView[]): string | undefined {
  return [...messages].reverse().find((message) => message.role === 'ASSISTANT' && (
    work.kind === 'article' ? `article:${message.articlePreparation?.id}` === work.id
      : work.kind === 'reply' ? `reply:${message.executionTimeline?.runId ?? message.executionTimeline?.id}` === work.id
        : [message.executionTimeline?.taskId, message.taskCreatedReceipt?.taskId, message.backgroundTaskNotification?.taskId,
          ...(message.parallelTaskGroup?.tasks.map((task) => task.taskId) ?? []),
          ...(message.taskExecutionOutcomeGroup?.outcomes.map((outcome) => outcome.taskId) ?? [])].some((id) => `task:${id}` === work.id)
  ))?.id
}

/** Only the primary row can animate. Guidance and old messages remain quiet. */
export function GeoMessageAvatar({ messageId, secondary = false }: { messageId?: string; secondary?: boolean }) {
  const context = useGeoMascotConversation()
  if (!context) return null
  const active = !secondary && messageId !== undefined && messageId === context.pose.messageId
  return <GeoMascotAvatar action={active ? context.pose.action : 'idle'}
    animationKey={active ? String(context.pose.key) : 'history'}
    label={active ? context.pose.label : '光禾AI智能体框架陪你一起完成任务'}
    onComplete={active ? () => context.complete(context.pose.key) : undefined} />
}

export function GeoPendingReply() {
  const context = useGeoMascotConversation()
  if (context?.pose.messageId !== PENDING_REPLY) return null
  return <article className="chat-message chat-message--assistant" aria-label="光禾AI智能体框架正在回复">
    <div className="chat-message__row"><GeoMessageAvatar messageId={PENDING_REPLY} />
      <div className="chat-message__content"><p className="geo-pending-reply" role="status">{context.pose.label}</p></div>
    </div>
  </article>
}

/** Read only structured state. Never infer success from the assistant's prose. */
function observedWork(messages: readonly ChatMessageView[], backgroundWork: GeoMascotProps['backgroundWork']): Work[] {
  const records = new Map<string, Work>()
  for (const task of backgroundWork ?? []) {
    records.set(`task:${task.id}`, { ...task, id: `task:${task.id}`, kind: 'task' })
  }
  for (const message of messages) {
    const preparation = message.articlePreparation
    if (preparation) {
      records.set(`article:${preparation.id}`, { id: `article:${preparation.id}`, kind: 'article', observedAt: preparation.observedAt,
        status: preparation.status === 'running' ? 'active' : preparation.status === 'succeeded' ? 'success' : 'other' })
    }
    const timeline = message.executionTimeline
    // TITLE / OUTLINE subtask success is not a finished article.
    if (!timeline || timeline.taskId || timeline.taskType === 'ARTICLE') continue
    const kind = 'reply'
    const id = `reply:${timeline.runId ?? timeline.id}`
    const outcome = timeline.businessOutcome ?? message.businessOutcome
    records.set(id, { id, kind, updatedAt: timeline.updatedAt,
      status: timeline.status === 'RUNNING' || timeline.status === 'QUEUED' ? 'active'
        : timeline.status === 'COMPLETED' && (!outcome || outcome.status === 'ANSWERED') ? 'success' : 'other' })
  }
  return [...records.values()]
}

/** Remount with the account / conversation / selected agent scope as its React key. */
export function GeoMascot({ messages, backgroundWork, busy, stopping, failed, loading, enabled = true, children }: GeoMascotProps) {
  const previous = useRef(new Map<string, Work>())
  const [enteredAt] = useState(() => Date.now())
  const initialized = useRef(false)
  const pendingCompletions = useRef<Work[]>([])
  const previouslyBusy = useRef(false)
  const speakingWork = useRef<string | undefined>(undefined)
  const sequence = useRef(0)
  const [pose, setPose] = useState<Pose>({ action: 'idle', key: 0, label: '随时可以开始' })

  useEffect(() => {
    const records = observedWork(messages, backgroundWork)
    const completions = records.filter((record) => record.status === 'success'
      && (record.kind === 'article' ? (previous.current.get(record.id)?.observedAt ?? 0) >= enteredAt
        : Date.parse(record.updatedAt ?? '') >= enteredAt)
      && previous.current.get(record.id)?.status === 'active')
    const interrupted = records.some((record) => record.status === 'other'
      && previous.current.get(record.id)?.status === 'active')
    const newReply = records.some((record) => record.kind === 'reply' && record.status === 'active'
      && !previous.current.has(record.id))
    if (newReply || busy && !previouslyBusy.current || interrupted || stopping || failed || loading || document.hidden) {
      pendingCompletions.current = []
      speakingWork.current = undefined
    }
    previouslyBusy.current = busy
    // Missing records (pagination, switching, temporary loading) are never success.
    previous.current = new Map(records.map((record) => [record.id, record]))
    const firstSnapshot = !initialized.current
    if (!loading) initialized.current = true
    if (!firstSnapshot && !newReply && !interrupted && !stopping && !failed && !loading && !document.hidden) {
      pendingCompletions.current.push(...completions)
    }
    const show = (action: Action, label: string, work?: Work, messageId?: string) => setPose((current) => current.action === action && current.work?.id === work?.id && current.messageId === messageId
      ? current.label === label ? current : { ...current, label }
      : { action, label, work, messageId, key: ++sequence.current })

    if (!enabled || stopping || failed || loading) {
      show('idle', stopping ? '正在停止回复' : failed ? '请查看提示后重试' : '随时可以开始')
      return
    }
    const activeWork = records.filter((record) => record.status === 'active' && record.kind !== 'reply')
    if (busy || activeWork.length) {
      if (busy) {
        const lastUser = messages.reduce((index, message, position) => message.role === 'USER' ? position : index, -1)
        const reply = messages.slice(lastUser + 1).reverse().find((message) => message.role === 'ASSISTANT'
          && !message.backgroundTaskNotification && !message.executionTimeline?.taskId
          && (message.outputStreaming || ['RUNNING', 'QUEUED'].includes(message.executionTimeline?.status ?? '')))
        const work = reply?.executionTimeline ? records.find((record) => record.id === `reply:${reply.executionTimeline?.runId ?? reply.executionTimeline?.id}`)
          : pendingCompletions.current.filter((record) => record.kind === 'reply').at(-1)
        // Never borrow an old reply's avatar while a newly sent message is being admitted.
        const target = reply?.id ?? (work ? messageForWork(work, messages) : undefined) ?? PENDING_REPLY
        if (reply?.outputStreaming) speakingWork.current = work?.id ?? reply.id
        const speaking = speakingWork.current !== undefined && speakingWork.current === (work?.id ?? reply?.id)
        show(speaking ? 'speaking' : 'thinking', speaking ? '正在和你说话' : '正在思考', work, target)
      } else {
        const work = [...activeWork].reverse().find((item) => messageForWork(item, messages))
        show(work ? 'thinking' : 'idle', work?.kind === 'article' ? '正在撰写文章' : '后台任务进行中', work)
      }
      return
    }
    // SSE completion can arrive before the snapshot clears STREAMING. Keep the
    // confirmed result until that same request stops being busy, not another run.
    if (pendingCompletions.current.length && !document.hidden) {
      const completed = [...pendingCompletions.current].reverse().find((work) => work.kind !== 'reply' && messageForWork(work, messages))
        ?? [...pendingCompletions.current].reverse().find((work) => messageForWork(work, messages))
      pendingCompletions.current = []
      if (completed) show(completed.kind !== 'reply' ? 'happy' : 'thought-complete', completed.kind !== 'reply' ? '成果已准备好' : '回复已完成', completed)
      else show('idle', '随时可以开始')
      return
    }
    // Preserve an in-flight decorative action only until its own completion.
    setPose((current) => interrupted || current.action === 'thinking' || current.action === 'speaking' || current.action === 'idle' && current.label !== '随时可以开始'
      ? { action: 'idle', label: '随时可以开始', key: ++sequence.current } : current)
  }, [messages, backgroundWork, busy, stopping, failed, loading, enabled, enteredAt])

  return <MascotContext.Provider value={enabled ? { pose: { ...pose, messageId: pose.work ? messageForWork(pose.work, messages) : pose.messageId }, complete: (key) => {
      setPose((current) => current.key === key
        ? { action: 'idle', label: '随时可以开始', key: ++sequence.current } : current)
    } } : undefined}>{children}</MascotContext.Provider>
}

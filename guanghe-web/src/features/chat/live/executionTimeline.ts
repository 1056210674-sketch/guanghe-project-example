import type { AgentProgressPayload, ConversationBusinessOutcome, PaidTask } from '../../../api/conversationApi'
import { readBusinessOutcome } from './businessOutcome'
import type { ConversationEventCursor, ParsedConversationEvent } from './conversationStream'

export const AGENT_PROGRESS_STAGE_CODES = [
  'UNDERSTANDING_REQUEST',
  'LOADING_BUSINESS_CONTEXT',
  'LOADING_PRODUCT_CONTEXT',
  'LOADING_REFERENCE_ASSETS',
  'ROUTING_INTENT',
  'PLANNING_WORKFLOW',
  'RUNNING_SAFE_TOOL',
  'SEARCHING_WEB',
  'PREPARING_TASK_DRAFT',
  'COMPOSING_RESPONSE',
] as const

export type AgentProgressStageCode = AgentProgressPayload['stageCode']
export type AgentProgressStatus = AgentProgressPayload['status']

export type ExecutionTimelineStatus =
  | 'RUNNING'
  | 'WAITING_CONFIRMATION'
  | 'QUEUED'
  | 'COMPLETED'
  | 'PARTIAL'
  | 'STOPPED'
  | 'FAILED'

export type ExecutionTimelineStepCode =
  | AgentProgressStageCode
  | 'DRAFT_READY'
  | 'TASK_QUEUED'
  | 'TASK_GENERATING'
  | 'TASK_RESULT_READY'
  | 'TASK_RESULT_CHECKING'
  | 'TASK_RESULT_RECOVERING'
  | 'TASK_RESULT_CHECK_FAILED'
  | 'TASK_ASSET_SAVING'
  | 'TASK_ASSET_SAVE_RETRY'
  | 'TASK_RUNNING'
  | 'TASK_GEO_SAMPLING'
  | 'TASK_GEO_RETRYING'
  | 'TASK_COMPLETED'
  | 'TASK_PARTIAL'
  | 'TASK_FAILED'

export interface ExecutionTimelineStep {
  id: string
  code: ExecutionTimelineStepCode
  status: AgentProgressStatus | 'WAITING'
  sequence: number
  ordinal?: number
  elapsedMs: number
  current?: number
  total?: number
}

export interface ExecutionTimeline {
  id: string
  messageId: string
  runId?: string
  taskId?: string
  /** Display metadata from the matching task record, not inferred from text. */
  taskType?: PaidTask['taskType']
  recordLabel?: '后台任务记录' | '回复过程记录'
  startedAt: string
  updatedAt: string
  elapsedMs: number
  status: ExecutionTimelineStatus
  businessOutcome?: ConversationBusinessOutcome
  steps: ExecutionTimelineStep[]
}

export type ExecutionTimelineMap = Record<string, ExecutionTimeline>

/** Task snapshots are business truth; a missed or replayed SSE stage must not
 * keep a finished task running in memory or in the browser recovery cache. */
export function reconcileTaskExecutionTimelines(
  current: ExecutionTimelineMap,
  tasks: readonly Pick<PaidTask, 'id' | 'status' | 'createdAt' | 'updatedAt'>[],
): ExecutionTimelineMap {
  const terminalTasks = new Map<string, (typeof tasks)[number]>()
  for (const task of tasks) {
    if (!['SUCCEEDED', 'PARTIAL', 'FAILED', 'SKIPPED', 'SYSTEM_TERMINATED'].includes(task.status)) continue
    const previous = terminalTasks.get(task.id)
    if (!previous || Date.parse(task.updatedAt) > Date.parse(previous.updatedAt)) {
      terminalTasks.set(task.id, task)
    }
  }
  let result = current
  for (const [key, timeline] of Object.entries(current)) {
    const task = timeline.taskId ? terminalTasks.get(timeline.taskId) : undefined
    if (!task) continue
    const status = task.status === 'SUCCEEDED' ? 'COMPLETED'
      : task.status === 'PARTIAL' ? 'PARTIAL' : 'FAILED'
    const elapsedMs = Math.max(0, Date.parse(task.updatedAt) - Date.parse(task.createdAt))
    if (!Number.isFinite(elapsedMs)) continue
    if (timeline.status === status && timeline.startedAt === task.createdAt
      && timeline.updatedAt === task.updatedAt && timeline.elapsedMs === elapsedMs) continue
    if (result === current) result = { ...current }
    result[key] = { ...timeline, status, startedAt: task.createdAt,
      updatedAt: task.updatedAt, elapsedMs }
  }
  return result
}

export interface ExecutionTimelineRecovery {
  cursor: ConversationEventCursor
  timelines: ExecutionTimelineMap
}

const CACHE_VERSION = 1
const CACHE_PREFIX = 'zhixiaohe.chat.execution-timeline.v1.'
const MAX_CACHED_TIMELINES = 100
const AGENT_STAGE_SET = new Set<string>(AGENT_PROGRESS_STAGE_CODES)
const AGENT_STATUS_SET = new Set<string>(['RUNNING', 'SUCCEEDED', 'FAILED'])
const TIMELINE_STATUS_SET = new Set<string>([
  'RUNNING',
  'WAITING_CONFIRMATION',
  'QUEUED',
  'COMPLETED',
  'PARTIAL',
  'STOPPED',
  'FAILED',
])
const STEP_CODE_SET = new Set<string>([
  ...AGENT_PROGRESS_STAGE_CODES,
  'DRAFT_READY',
  'TASK_QUEUED',
  'TASK_GENERATING',
  'TASK_RESULT_READY',
  'TASK_RESULT_CHECKING',
  'TASK_RESULT_RECOVERING',
  'TASK_RESULT_CHECK_FAILED',
  'TASK_ASSET_SAVING',
  'TASK_ASSET_SAVE_RETRY',
  'TASK_RUNNING',
  'TASK_GEO_SAMPLING',
  'TASK_GEO_RETRYING',
  'TASK_COMPLETED',
  'TASK_PARTIAL',
  'TASK_FAILED',
])
const STEP_STATUS_SET = new Set<string>([
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
  'WAITING',
])

const AGENT_STAGE_LABELS: Readonly<
  Record<AgentProgressStageCode, { running: string; succeeded: string }>
> = {
  UNDERSTANDING_REQUEST: {
    running: '正在理解你的需求',
    succeeded: '已理解你的需求',
  },
  LOADING_BUSINESS_CONTEXT: {
    running: '正在读取品牌资料',
    succeeded: '已读取品牌资料',
  },
  LOADING_PRODUCT_CONTEXT: {
    running: '正在读取商品资料',
    succeeded: '已读取商品资料',
  },
  LOADING_REFERENCE_ASSETS: {
    running: '正在核对参考素材',
    succeeded: '已核对参考素材',
  },
  ROUTING_INTENT: {
    running: '正在判断处理方式',
    succeeded: '已确定处理方式',
  },
  PLANNING_WORKFLOW: {
    running: '正在思考与分析',
    succeeded: '已完成本轮分析',
  },
  RUNNING_SAFE_TOOL: {
    running: '正在调用工具',
    succeeded: '工具调用已完成',
  },
  SEARCHING_WEB: {
    running: '正在联网查找资料',
    succeeded: '已完成联网查找资料',
  },
  PREPARING_TASK_DRAFT: {
    running: '正在整理任务草稿',
    succeeded: '已生成任务草稿',
  },
  COMPOSING_RESPONSE: {
    running: '正在组织回复',
    succeeded: '已完成本轮回复整理',
  },
}

const TASK_STEP_LABELS: Readonly<
  Record<Exclude<ExecutionTimelineStepCode, AgentProgressStageCode>, string>
> = {
  DRAFT_READY: '任务草稿已准备，等待确认',
  TASK_QUEUED: '任务已排队',
  TASK_GENERATING: '正在生成内容',
  TASK_RESULT_READY: '生成结果已返回',
  TASK_RESULT_CHECKING: '正在确认生成结果',
  TASK_RESULT_RECOVERING: '正在恢复生成结果',
  TASK_RESULT_CHECK_FAILED: '生成结果确认未完成',
  TASK_ASSET_SAVING: '正在保存到资产中心',
  TASK_ASSET_SAVE_RETRY: '正在重试保存资产',
  TASK_RUNNING: '任务正在执行',
  TASK_GEO_SAMPLING: '正在采集平台回答',
  TASK_GEO_RETRYING: '正在补采失败的监测项',
  TASK_COMPLETED: '任务已完成',
  TASK_PARTIAL: '任务部分完成',
  TASK_FAILED: '任务未完成',
}

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function nonEmptyString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}

function nonNegativeInteger(value: unknown): number | undefined {
  return Number.isSafeInteger(value) && (value as number) >= 0
    ? (value as number)
    : undefined
}

function validIsoDate(value: unknown): string | undefined {
  const candidate = nonEmptyString(value)
  return candidate && !Number.isNaN(Date.parse(candidate)) ? candidate : undefined
}

function cacheKey(ownerId: string, conversationId: string) {
  return `${CACHE_PREFIX}${ownerId}.${conversationId}`
}

function elapsedAt(previous: ExecutionTimeline | undefined, occurredAt: string) {
  if (!previous) return 0
  return Math.max(
    previous.elapsedMs,
    Date.parse(occurredAt) - Date.parse(previous.startedAt),
    0,
  )
}

function timelineFor(
  previous: ExecutionTimeline | undefined,
  options: {
    messageId: string
    occurredAt: string
    runId?: string
    taskId?: string
    elapsedMs?: number
  },
): ExecutionTimeline {
  const authoritativeElapsed = options.elapsedMs ?? 0
  const inferredStart = new Date(
    Math.max(0, Date.parse(options.occurredAt) - authoritativeElapsed),
  ).toISOString()
  return previous ?? {
    id: options.messageId,
    messageId: options.messageId,
    runId: options.runId,
    taskId: options.taskId,
    startedAt: inferredStart,
    updatedAt: options.occurredAt,
    elapsedMs: authoritativeElapsed,
    status: 'RUNNING',
    steps: [],
  }
}

function withStep(
  timeline: ExecutionTimeline,
  step: ExecutionTimelineStep,
  status: ExecutionTimelineStatus,
  occurredAt: string,
  ids: { runId?: string; taskId?: string } = {},
): ExecutionTimeline {
  const existingIndex = timeline.steps.findIndex(({ id }) => id === step.id)
  const steps = [...timeline.steps]
  if (existingIndex >= 0) {
    const existing = steps[existingIndex]
    if (existing.sequence > step.sequence) return timeline
    steps[existingIndex] = {
      ...existing,
      ...step,
      elapsedMs: Math.max(existing.elapsedMs, step.elapsedMs),
    }
  } else {
    steps.push(step)
  }
  steps.sort((left, right) => left.sequence - right.sequence)
  return {
    ...timeline,
    ...ids,
    updatedAt: occurredAt,
    elapsedMs: Math.max(timeline.elapsedMs, step.elapsedMs),
    status,
    steps,
  }
}

function payloadOf(event: ParsedConversationEvent) {
  return isRecord(event.data.payload) ? event.data.payload : {}
}

function eventMessageId(
  event: ParsedConversationEvent,
  linkedMessageId?: string,
) {
  const payload = payloadOf(event)
  return (
    nonEmptyString(payload.messageId) ??
    nonEmptyString(event.data.messageId) ??
    linkedMessageId
  )
}

function previousTimelineFor(
  current: ExecutionTimelineMap,
  messageId: string,
  runId?: string,
): { key: string; timeline: ExecutionTimeline } | undefined {
  const direct = current[messageId]
  if (direct) return { key: messageId, timeline: direct }
  if (!runId) return undefined
  const match = Object.entries(current).find(
    ([, timeline]) => timeline.runId === runId,
  )
  return match ? { key: match[0], timeline: match[1] } : undefined
}

function replaceTimeline(
  current: ExecutionTimelineMap,
  previousKey: string | undefined,
  messageId: string,
  timeline: ExecutionTimeline,
): ExecutionTimelineMap {
  if (!previousKey || previousKey === messageId) {
    return { ...current, [messageId]: timeline }
  }
  const next = { ...current }
  delete next[previousKey]
  next[messageId] = timeline
  return next
}

function rekeyTimeline(
  timeline: ExecutionTimeline,
  messageId: string,
): ExecutionTimeline {
  return timeline.messageId === messageId
    ? timeline
    : { ...timeline, id: messageId, messageId }
}

function agentProgress(
  event: ParsedConversationEvent,
): {
  messageId: string
  runId: string
  stageCode: AgentProgressStageCode
  status: AgentProgressStatus
  ordinal: number
  elapsedMs: number
  current?: number
  total?: number
} | undefined {
  const payload = payloadOf(event)
  const messageId = nonEmptyString(payload.messageId)
  const outerMessageId = nonEmptyString(event.data.messageId)
  const runId = nonEmptyString(payload.runId)
  const stageCode = nonEmptyString(payload.stageCode)
  const status = nonEmptyString(payload.status)
  const ordinal = nonNegativeInteger(payload.ordinal)
  const elapsedMs = nonNegativeInteger(payload.elapsedMs)
  if (
    !messageId ||
    !outerMessageId ||
    messageId !== outerMessageId ||
    !runId ||
    !stageCode ||
    !AGENT_STAGE_SET.has(stageCode) ||
    !status ||
    !AGENT_STATUS_SET.has(status) ||
    ordinal === undefined ||
    ordinal < 1 ||
    ordinal > 32 ||
    elapsedMs === undefined ||
    !Object.prototype.hasOwnProperty.call(payload, 'current') ||
    !Object.prototype.hasOwnProperty.call(payload, 'total')
  ) {
    return undefined
  }
  const current = payload.current === null
    ? undefined
    : nonNegativeInteger(payload.current)
  const total = payload.total === null
    ? undefined
    : nonNegativeInteger(payload.total)
  if (
    (current === undefined) !== (total === undefined) ||
    (current !== undefined && total !== undefined && current > total)
  ) {
    return undefined
  }
  return {
    messageId,
    runId,
    stageCode: stageCode as AgentProgressStageCode,
    status: status as AgentProgressStatus,
    ordinal,
    elapsedMs,
    current,
    total,
  }
}

function taskStepCode(event: ParsedConversationEvent): ExecutionTimelineStepCode {
  if (event.event === 'task.queued') return 'TASK_QUEUED'
  if (payloadOf(event).status === 'PARTIAL') return 'TASK_PARTIAL'
  if (event.event === 'task.completed') return 'TASK_COMPLETED'
  if (event.event === 'task.failed') return 'TASK_FAILED'
  const stageCode = nonEmptyString(payloadOf(event).stageCode)
  const codes: Readonly<Record<string, ExecutionTimelineStepCode>> = {
    QUEUED: 'TASK_QUEUED',
    PROVIDER_GENERATING: 'TASK_GENERATING',
    RESULT_READY: 'TASK_RESULT_READY',
    PROVIDER_RESULT_UNKNOWN: 'TASK_RESULT_CHECKING',
    PROVIDER_RESULT_RECOVERY: 'TASK_RESULT_RECOVERING',
    PROVIDER_RESULT_CONFIRMATION_FAILED: 'TASK_RESULT_CHECK_FAILED',
    ASSET_INGESTION_PENDING: 'TASK_ASSET_SAVING',
    ASSET_INGESTION_RETRY: 'TASK_ASSET_SAVE_RETRY',
    GEO_SAMPLING: 'TASK_GEO_SAMPLING',
    GEO_RETRYING: 'TASK_GEO_RETRYING',
    PARTIAL_COMPLETED: 'TASK_PARTIAL',
    COMPLETED: 'TASK_COMPLETED',
    FAILED: 'TASK_FAILED',
    SYSTEM_TERMINATED: 'TASK_FAILED',
  }
  return (stageCode ? codes[stageCode] : undefined) ?? 'TASK_RUNNING'
}

export function applyConversationEventToTimelines(
  current: ExecutionTimelineMap,
  event: ParsedConversationEvent,
  linkedMessageId?: string,
): ExecutionTimelineMap {
  const occurredAt = validIsoDate(event.data.occurredAt)
  if (!occurredAt) return current

  if (event.event === 'agent.progress') {
    const progress = agentProgress(event)
    if (!progress) return current
    const previousEntry = previousTimelineFor(
      current,
      progress.messageId,
      progress.runId,
    )
    const base = rekeyTimeline(timelineFor(previousEntry?.timeline, {
      messageId: progress.messageId,
      runId: progress.runId,
      occurredAt,
      elapsedMs: progress.elapsedMs,
    }), progress.messageId)
    const next = withStep(
      base,
      {
        id: `agent:${progress.runId}:${progress.ordinal}:${progress.stageCode}`,
        code: progress.stageCode,
        status: progress.status,
        sequence: event.data.sequence,
        ordinal: progress.ordinal,
        elapsedMs: progress.elapsedMs,
        current: progress.current,
        total: progress.total,
      },
      progress.status === 'FAILED' ? 'FAILED' : 'RUNNING',
      occurredAt,
      { runId: progress.runId },
    )
    return replaceTimeline(
      current,
      previousEntry?.key,
      progress.messageId,
      next,
    )
  }

  const messageId = eventMessageId(event, linkedMessageId)
  if (!messageId) return current
  const payload = payloadOf(event)
  const runId = nonEmptyString(payload.runId)
  const previousEntry = previousTimelineFor(current, messageId, runId)
  const previous = previousEntry?.timeline
  const taskId = nonEmptyString(payload.taskId) ?? nonEmptyString(event.data.taskId)
  const base = rekeyTimeline(
    timelineFor(previous, { messageId, runId, taskId, occurredAt }),
    messageId,
  )
  const elapsedMs = elapsedAt(base, occurredAt)

  if (event.event === 'message.start') {
    return replaceTimeline(
      current,
      previousEntry?.key,
      messageId,
      {
        ...base,
        runId: runId ?? base.runId,
        status: 'RUNNING',
        updatedAt: occurredAt,
        elapsedMs,
      },
    )
  }
  if (event.event === 'message.delta' && typeof payload.delta === 'string') {
    // A delta may arrive before the message-list snapshot or a start replay.
    // It establishes a real reply placeholder, not an artificial typing timer.
    if (previous && !['RUNNING', 'QUEUED'].includes(previous.status)) return current
    return replaceTimeline(current, previousEntry?.key, messageId, {
      ...base, updatedAt: occurredAt, elapsedMs,
    })
  }
  if (event.event === 'message.completed') {
    if (previous && !['RUNNING', 'FAILED', 'WAITING_CONFIRMATION'].includes(previous.status)) return current
    const terminalStatus = nonEmptyString(payload.status)
    const failed = previous?.status === 'FAILED' || terminalStatus === 'FAILED'
    return replaceTimeline(
      current,
      previousEntry?.key,
      messageId,
      {
        ...base,
        status: terminalStatus === 'STOPPED' ? 'STOPPED' : failed ? 'FAILED' : 'COMPLETED',
        businessOutcome: terminalStatus !== 'STOPPED' ? readBusinessOutcome(payload.businessOutcome) : undefined,
        updatedAt: occurredAt,
        elapsedMs,
      },
    )
  }
  if (event.event === 'draft.created') {
    const next = withStep(
      base,
      {
        id: `draft:${messageId}`,
        code: 'DRAFT_READY',
        status: 'WAITING',
        sequence: event.data.sequence,
        elapsedMs,
      },
      'WAITING_CONFIRMATION',
      occurredAt,
    )
    return replaceTimeline(current, previousEntry?.key, messageId, next)
  }
  if (event.event.startsWith('task.') && taskId) {
    const code = taskStepCode(event)
    const failed = event.event === 'task.failed' || code === 'TASK_FAILED'
    const partial = code === 'TASK_PARTIAL'
    const completed = event.event === 'task.completed' || code === 'TASK_COMPLETED'
    const status: ExecutionTimelineStatus = failed
      ? 'FAILED'
      : partial
        ? 'PARTIAL'
        : completed
        ? 'COMPLETED'
        : event.event === 'task.queued' || code === 'TASK_QUEUED'
          ? 'QUEUED'
          : 'RUNNING'
    const currentUnits = nonNegativeInteger(payload.completedUnits)
    const expectedUnits = nonNegativeInteger(payload.expectedUnits)
    const validUnits = currentUnits !== undefined && expectedUnits !== undefined && currentUnits <= expectedUnits
    const next = withStep(
      base,
      {
        id: `task:${taskId}:${code}`,
        code,
        status: failed ? 'FAILED' : partial ? 'WAITING' : completed ? 'SUCCEEDED' : 'RUNNING',
        sequence: event.data.sequence,
        elapsedMs,
        current: validUnits ? currentUnits : undefined,
        total: validUnits ? expectedUnits : undefined,
      },
      status,
      occurredAt,
      { taskId },
    )
    return replaceTimeline(current, previousEntry?.key, messageId, next)
  }
  return current
}

export function createPendingExecutionTimeline(options: {
  messageId: string
  runId: string
  startedAt?: string
}): ExecutionTimeline {
  const startedAt = options.startedAt ?? new Date().toISOString()
  return {
    id: options.messageId,
    messageId: options.messageId,
    runId: options.runId,
    startedAt,
    updatedAt: startedAt,
    elapsedMs: 0,
    status: 'RUNNING',
    steps: [],
  }
}

export function executionTimelineStepLabel(step: ExecutionTimelineStep, historical = false): string {
  if (historical && step.status === 'RUNNING') {
    const stageNames: Record<ExecutionTimelineStepCode, string> = {
      UNDERSTANDING_REQUEST: '理解需求',
      LOADING_BUSINESS_CONTEXT: '读取相关资料',
      LOADING_PRODUCT_CONTEXT: '读取商品资料',
      LOADING_REFERENCE_ASSETS: '核对参考素材',
      ROUTING_INTENT: '确定处理方式',
      PLANNING_WORKFLOW: '思考与分析',
      RUNNING_SAFE_TOOL: '调用工具',
      SEARCHING_WEB: '联网查找资料',
      PREPARING_TASK_DRAFT: '整理任务草稿',
      COMPOSING_RESPONSE: '组织回复',
      DRAFT_READY: '准备任务草稿',
      TASK_QUEUED: '任务排队',
      TASK_GENERATING: '生成内容',
      TASK_RESULT_READY: '接收生成结果',
      TASK_RESULT_CHECKING: '核对生成结果',
      TASK_RESULT_RECOVERING: '恢复生成结果',
      TASK_RESULT_CHECK_FAILED: '生成结果确认未完成',
      TASK_ASSET_SAVING: '保存到资产中心',
      TASK_ASSET_SAVE_RETRY: '重试保存资产',
      TASK_RUNNING: '执行任务',
      TASK_GEO_SAMPLING: '采集平台回答',
      TASK_GEO_RETRYING: '补采失败的监测项',
      TASK_COMPLETED: '任务已完成',
      TASK_PARTIAL: '任务部分完成',
      TASK_FAILED: '任务未完成',
    }
    return stageNames[step.code]
  }
  if (AGENT_STAGE_SET.has(step.code)) {
    if (step.status === 'FAILED') return step.code === 'SEARCHING_WEB' ? '联网查找资料未完成' : '该步骤未完成'
    const labels = AGENT_STAGE_LABELS[step.code as AgentProgressStageCode]
    return step.status === 'SUCCEEDED' ? labels.succeeded : labels.running
  }
  return TASK_STEP_LABELS[
    step.code as Exclude<ExecutionTimelineStepCode, AgentProgressStageCode>
  ]
}

export function storeExecutionTimelineRecovery(
  ownerId: string | undefined,
  conversationId: string,
  cursor: ConversationEventCursor,
  timelines: ExecutionTimelineMap,
): void {
  if (!ownerId) return
  const bounded = Object.values(timelines)
    .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt))
    .slice(0, MAX_CACHED_TIMELINES)
  try {
    storage()?.setItem(
      cacheKey(ownerId, conversationId),
      JSON.stringify({
        version: CACHE_VERSION,
        cursor,
        timelines: Object.fromEntries(bounded.map((timeline) => [timeline.id, timeline])),
      }),
    )
  } catch {
    // The server stream remains authoritative; a missing cache triggers replay.
  }
}

function parseStep(value: unknown): ExecutionTimelineStep | undefined {
  if (!isRecord(value)) return undefined
  const id = nonEmptyString(value.id)
  const code = nonEmptyString(value.code)
  const status = nonEmptyString(value.status)
  const sequence = nonNegativeInteger(value.sequence)
  const elapsedMs = nonNegativeInteger(value.elapsedMs)
  if (
    !id ||
    !code ||
    !STEP_CODE_SET.has(code) ||
    !status ||
    !STEP_STATUS_SET.has(status) ||
    sequence === undefined ||
    sequence < 1 ||
    elapsedMs === undefined
  ) {
    return undefined
  }
  const ordinal = value.ordinal === undefined
    ? undefined
    : nonNegativeInteger(value.ordinal)
  const current = value.current === undefined
    ? undefined
    : nonNegativeInteger(value.current)
  const total = value.total === undefined
    ? undefined
    : nonNegativeInteger(value.total)
  if (
    (current === undefined) !== (total === undefined) ||
    (current !== undefined && total !== undefined && current > total)
  ) {
    return undefined
  }
  return {
    id,
    code: code as ExecutionTimelineStepCode,
    status: status as ExecutionTimelineStep['status'],
    sequence,
    ordinal,
    elapsedMs,
    current,
    total,
  }
}

function parseTimeline(value: unknown): ExecutionTimeline | undefined {
  if (!isRecord(value) || !Array.isArray(value.steps)) return undefined
  const id = nonEmptyString(value.id)
  const messageId = nonEmptyString(value.messageId)
  const startedAt = validIsoDate(value.startedAt)
  const updatedAt = validIsoDate(value.updatedAt)
  const elapsedMs = nonNegativeInteger(value.elapsedMs)
  const status = nonEmptyString(value.status)
  const steps = value.steps.map(parseStep)
  if (
    !id ||
    !messageId ||
    !startedAt ||
    !updatedAt ||
    elapsedMs === undefined ||
    !status ||
    !TIMELINE_STATUS_SET.has(status) ||
    steps.some((step) => step === undefined)
  ) {
    return undefined
  }
  return {
    id,
    messageId,
    runId: nonEmptyString(value.runId),
    taskId: nonEmptyString(value.taskId),
    startedAt,
    updatedAt,
    elapsedMs,
    status: status as ExecutionTimelineStatus,
    businessOutcome: readBusinessOutcome(value.businessOutcome),
    steps: steps as ExecutionTimelineStep[],
  }
}

export function readExecutionTimelineRecovery(
  ownerId: string | undefined,
  conversationId: string | undefined,
): ExecutionTimelineRecovery | undefined {
  if (!ownerId || !conversationId) return undefined
  try {
    const raw = storage()?.getItem(cacheKey(ownerId, conversationId))
    if (!raw) return undefined
    const value = JSON.parse(raw) as unknown
    if (!isRecord(value) || value.version !== CACHE_VERSION) return undefined
    const cursorValue = value.cursor
    if (!isRecord(cursorValue)) return undefined
    const lastEventId = nonEmptyString(cursorValue.lastEventId)
    const lastSequence = nonNegativeInteger(cursorValue.lastSequence)
    if (!lastEventId || lastSequence === undefined || lastSequence < 1) {
      return undefined
    }
    if (!isRecord(value.timelines)) return undefined
    const timelines: ExecutionTimelineMap = {}
    for (const candidate of Object.values(value.timelines)) {
      const timeline = parseTimeline(candidate)
      if (!timeline) return undefined
      timelines[timeline.id] = timeline
    }
    return { cursor: { lastEventId, lastSequence }, timelines }
  } catch {
    return undefined
  }
}

export function recoverableTimelineCursor(
  recovery: ExecutionTimelineRecovery | undefined,
  storedCursor: ConversationEventCursor | undefined,
): ConversationEventCursor | null {
  if (
    recovery &&
    storedCursor &&
    recovery.cursor.lastEventId === storedCursor.lastEventId &&
    recovery.cursor.lastSequence === storedCursor.lastSequence
  ) {
    return storedCursor
  }
  return null
}

import { getConversation, getConversationSnapshot, listConversationMessages, type ConversationMessage } from '../../api/conversationApi'
import { listAgentInstallations, listProjects } from '../../api/agentPlatformApi'
import { createArticleAuthorization, listArticleAuthorizations, startArticleFullPreparation, type ArticleFullPreparation } from '../../api/articleFullApi'
import type { AuthSessionSnapshot } from '../../auth/tokenStorage'
import { ApiError } from '../../api/apiError'
import { storeFullPreparationRecovery, type FullPreparationAttempt } from '../../features/articles/live/fullPreparationRecovery'

interface Options { ownerId: string; conversationId: string; session: AuthSessionSnapshot; attempt: FullPreparationAttempt; current: () => boolean; onPending: (attempt: FullPreparationAttempt) => void; wait?: () => Promise<void> }
const waitForRun = () => new Promise<void>((resolve) => window.setTimeout(resolve, 2000))
const assertCurrent = (current: () => boolean) => { if (!current()) throw new Error('任务或登录状态已改变，未继续开始文章。') }

/** All writes descend from the original explicit user send. No assistant text is interpreted as authority. */
export async function invokeFullArticle(options: Options): Promise<ArticleFullPreparation> {
  const { ownerId, conversationId, session, current } = options
  let attempt = options.attempt
  let original: ConversationMessage | undefined
  let completedReply: ConversationMessage | undefined
  let complete = false
  for (let i = 0; i < 90; i += 1) {
    assertCurrent(current)
    const snapshot = await getConversationSnapshot(conversationId, 100)
    assertCurrent(current)
    let messages = snapshot.messages
    let before = snapshot.nextBeforeSequence
    let cursor: string | null = null
    // Recover only the original owned USER Run, never switch to the latest message.
    for (let page = 0; page < 10 && (before || cursor) && !messages.some((item) => item.runId === attempt.sourceRunId && item.role === 'USER'); page += 1) {
      const sequence = Number(cursor ?? before)
      if (!Number.isSafeInteger(sequence) || sequence < 0) throw new Error('原请求的历史定位暂不可用，未开始新文章。')
      const older = await listConversationMessages(conversationId, { ...(cursor ? { cursor: sequence } : { beforeSequence: sequence }), limit: 100 })
      assertCurrent(current); messages = [...older.items, ...messages]; before = null; cursor = older.nextCursor
    }
    original = messages.find((item) => item.role === 'USER' && item.runId === attempt.sourceRunId)
    const reply = messages.find((item) => item.role === 'ASSISTANT' && item.runId === attempt.sourceRunId)
    if (reply?.status === 'FAILED' || reply?.status === 'STOPPED' || snapshot.activeRun?.runId === attempt.sourceRunId && ['FAILED', 'STOPPED', 'STOP_REQUESTED'].includes(snapshot.activeRun.status)) throw new Error('本次资料核对未完成或已停止，未开始文章创作。')
    if (reply?.status === 'COMPLETED') { complete = true; completedReply = reply; break }
    await (options.wait ?? waitForRun)()
  }
  if (!complete) throw new ApiError({ status: 408, code: 'ARTICLE_SOURCE_RUN_PENDING', message: '原请求暂未核对完成，请稍后恢复同一次请求；未启动新文章。' })
  if (!original || typeof original.content.text !== 'string' || !original.content.text.trim()) throw new Error('无法读取原始请求，未启动新文章。')
  // If the agent explicitly needs more information, retain chat flexibility instead of spending on guessed facts.
  const snapshot = await getConversationSnapshot(conversationId, 100)
  assertCurrent(current)
  const reply = snapshot.messages.find((item) => item.role === 'ASSISTANT' && item.runId === attempt.sourceRunId) ?? completedReply
  if (reply?.content.businessOutcome?.status === 'NEEDS_INPUT' || reply?.content.businessOutcome?.status === 'FAILED') throw new Error('智能体需要补充资料，或本次资料核对失败。请按回复补充后再次发送，未开始文章创作。')
  const [conversation, installations, projects] = await Promise.all([getConversation(conversationId), listAgentInstallations(), listProjects()])
  assertCurrent(current)
  const identity = conversation.agentIdentity
  const installation = installations.find((item) => item.id === identity?.installationId)
  if (!identity || !identity.capabilities.includes('article.generate') || !installation?.enabled || installation.agentKey !== identity.agentKey || identity.projectId && (identity.projectId !== attempt.authorization.spaceId || !projects.some((item) => item.id === identity.projectId && item.status === 'ACTIVE'))) throw new Error('智能体或所属项目已不可执行，未开始文章创作。')
  if (!attempt.request) {
    const grants = await listArticleAuthorizations(conversationId, session)
    assertCurrent(current)
    const matching = grants.find((item) => item.status === 'ACTIVE' && item.spaceId === attempt.authorization.spaceId && (item.productId ?? null) === (attempt.authorization.productId ?? null) && item.maxPoints === attempt.authorization.maxPoints && item.expiresAt === attempt.authorization.expiresAt)
    const grant = matching ?? await createArticleAuthorization(conversationId, attempt.authorization, session)
    assertCurrent(current)
    attempt = { ...attempt, request: { sourceRunId: attempt.sourceRunId, grantId: grant.id, grantVersion: grant.version, spaceId: grant.spaceId, productId: grant.productId, ...(attempt.geoReportId ? { geoReportId: attempt.geoReportId } : {}) } }
    storeFullPreparationRecovery(ownerId, conversationId, { revision: session.revision, attempt }); options.onPending(attempt)
  }
  assertCurrent(current)
  return startArticleFullPreparation(conversationId, { ...attempt.request!, requestText: original.content.text.trim() }, attempt.key, session)
}

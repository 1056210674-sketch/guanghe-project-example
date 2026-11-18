import { useQueries } from '@tanstack/react-query'
import { useMemo } from 'react'
import { ApiError } from '../../api/apiError'
import { getArticleFullPreparation } from '../../api/articleFullApi'
import type { ConversationMessage, PaidTask } from '../../api/conversationApi'
import { captureAuthSession } from '../../auth/tokenStorage'
import type { ChatArticlePreparationView } from '../../features/chat/types'

const validId = (value: unknown): value is string => typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value)

/** Only Java-authored references are accepted. Model prose is never parsed for article IDs. */
export function messageArticlePreparation(message: ConversationMessage) {
  if (message.role !== 'ASSISTANT') return undefined
  const ref = message.content.articlePreparation
  return ref && validId(ref.preparationId) && validId(ref.articleId) ? ref : undefined
}
export function taskArticlePreparationId(task?: PaidTask) {
  return task?.taskType === 'ARTICLE' && validId(task.articlePreparationId) ? task.articlePreparationId : undefined
}

export function useChatArticlePreparations(ownerId: string | undefined, conversationId: string | undefined, messages: readonly ConversationMessage[], tasks: readonly PaidTask[]) {
  const session = useMemo(() => ownerId && conversationId ? captureAuthSession() : undefined, [ownerId, conversationId])
  const refs = useMemo(() => {
    const ids = new Map<string, string | undefined>()
    for (const task of tasks) { const id = taskArticlePreparationId(task); if (id) ids.set(id, undefined) }
    for (const message of messages) { const ref = messageArticlePreparation(message); if (ref) ids.set(ref.preparationId, ref.articleId) }
    return [...ids].map(([id, articleId]) => ({ id, articleId }))
  }, [messages, tasks])
  return useQueries({
    queries: refs.map(({ id, articleId }) => ({
      queryKey: ['chat-article-preparation', ownerId, session?.revision, conversationId, id],
      enabled: Boolean(session),
      retry: false,
      refetchIntervalInBackground: false,
      refetchInterval: (query: { state: { data?: Awaited<ReturnType<typeof getArticleFullPreparation>>; dataUpdatedAt: number; error: unknown } }) => !query.state.error && query.state.data?.status === 'RUNNING' && Date.parse(query.state.data.deadlineAt) > query.state.dataUpdatedAt ? 3000 : false,
      queryFn: async () => {
        if (!session) throw new Error('请先登录并打开文章所属对话。')
        const preparation = await getArticleFullPreparation(id, session)
        if (preparation.id !== id || preparation.conversationId !== conversationId || (articleId && preparation.articleId !== articleId)) {
          throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '文章准备记录与当前对话不一致。' })
        }
        return preparation
      },
    })),
    combine: (results) => Object.fromEntries(results.map((query, index): [string, ChatArticlePreparationView] => {
      const preparation = !query.isError ? query.data : undefined
      const expired = preparation?.status === 'RUNNING' && Date.parse(preparation.deadlineAt) <= query.dataUpdatedAt
      const status = query.isError ? 'UNAVAILABLE' : preparation?.status
      return [refs[index].id, {
        id: refs[index].id,
        observedAt: query.dataUpdatedAt,
        status: status === 'SUCCEEDED' ? 'succeeded' : status === 'FAILED' ? 'failed' : status === 'UNAVAILABLE' || expired ? 'unavailable' : status === 'RUNNING' ? 'running' : 'loading',
        statusLabel: status === 'SUCCEEDED' ? '完整文章已准备' : status === 'FAILED' ? '文章准备未完成' : status === 'UNAVAILABLE' || expired ? '文章状态待核对' : status === 'RUNNING' ? '正在准备完整文章' : '正在读取文章状态',
        description: status === 'SUCCEEDED' ? '文章已生成。满意可直接投稿，也可以保存到资产中心继续编辑。' : status === 'FAILED' ? '已保留完成的步骤，可查看具体原因。不会自动再次收费或投稿。' : status === 'UNAVAILABLE' || expired ? '暂时无法确认最新结果。请打开文章核对，不要重复创建。' : '正在撰写文章，正文会在右侧显示。投稿由你最后确认。',
        tone: status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'danger' : status === 'UNAVAILABLE' || expired ? 'warning' : 'info',
        loading: query.isLoading || status === 'RUNNING' && !expired,
        openLabel: '查看文章',
        stages: preparation?.stages.map((stage) => `${({ TITLE: '标题', OUTLINE: '结构', BODY: '正文' })[stage.mode]}${({ PENDING: '待处理', READY: '待处理', WAITING: '待处理', RUNNING: '进行中', SUCCEEDED: '已完成', FAILED: '未完成', SKIPPED: '已跳过' } as Record<string, string>)[stage.status] ?? '待核对'}`) ?? [],
      }]
    })),
  })
}

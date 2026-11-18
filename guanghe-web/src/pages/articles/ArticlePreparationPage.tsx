import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Empty, Space, Spin, Typography } from 'antd'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getArticleFullPreparation, getArticleFullPreview, type ArticleFullPreview } from '../../api/articleFullApi'
import { captureAuthSession } from '../../auth/tokenStorage'
import { MarkdownMessageContent } from '../../features/chat/components/MarkdownMessageContent'
import { ArticleContentState } from '../../features/articles/components/ArticleContentState'
import { UserShell } from '../../layouts/UserShell'
import { appLinks } from '../../routes/paths'
import { useAuthStore } from '../../stores/authStore'
import { ArticleDocumentContent } from './ArticleDocumentContent'
import '../../styles/article-full.css'

const stageNames = { TITLE: '整理标题', OUTLINE: '组织结构', BODY: '撰写正文' }
const safeError = (error: unknown) => error instanceof Error ? error.message : '读取失败，请稍后重试。'
export function ArticlePreparationPage() {
  const owner = useAuthStore((state) => state.user?.id)
  const { preparationId } = useParams()
  return <UserShell><ArticlePreparationContent key={`${owner}:${preparationId}`} ownerId={owner} preparationId={preparationId} /></UserShell>
}

/** Preview contains real persisted model output; polling never starts or retries a paid task. */
export function ArticlePreparationContent({ ownerId, preparationId, layout = 'page' }: { ownerId?: string; preparationId?: string; layout?: 'page' | 'panel' }) {
  const [session] = useState(captureAuthSession)
  const client = useQueryClient()
  const preparation = useQuery({ queryKey: ['article-full-result', ownerId, preparationId, 'preparation'], queryFn: () => getArticleFullPreparation(preparationId!, session), enabled: Boolean(ownerId && preparationId), retry: false,
    refetchInterval: (query) => !query.state.error && query.state.data?.status === 'RUNNING' && Date.parse(query.state.data.deadlineAt) > query.state.dataUpdatedAt ? 2500 : false, refetchIntervalInBackground: false })
  const value = preparation.isError ? undefined : preparation.data
  const previewKey = ['article-full-preview', ownerId, preparationId]
  const preview = useQuery({ queryKey: previewKey, queryFn: async () => {
    const next = await getArticleFullPreview(preparationId!, session)
    if (next.preparationId !== preparationId || value?.articleId && next.articleId !== value.articleId) throw new Error('正文预览与当前文章不一致，请重新读取。')
    const previous = client.getQueryData<ArticleFullPreview>(previewKey)
    // A delayed response must not put old text back after a retry/reset or newer stream snapshot.
    if (previous && ((next.executionNo ?? 0) < (previous.executionNo ?? 0) || next.executionNo === previous.executionNo && next.sequence < previous.sequence)) return previous
    return next
  }, enabled: Boolean(ownerId && preparationId && value && value.status !== 'SUCCEEDED'), retry: false,
    refetchInterval: (query) => !query.state.error && value?.status === 'RUNNING' && Date.parse(value.deadlineAt) > query.state.dataUpdatedAt ? 1500 : false, refetchIntervalInBackground: false })
  const refresh = () => Promise.all([preparation.refetch(), preview.refetch()])
  if (preparation.isLoading) return <ArticleContentState layout={layout} loading="正在读取文章…" />
  if (preparation.isError || !value) return <ArticleContentState layout={layout}><Alert type="error" showIcon title="无法读取文章状态" description={safeError(preparation.error)} action={<Button onClick={() => void preparation.refetch()}>重新加载</Button>} /></ArticleContentState>
  const currentStage = value.stages.find((stage) => ['RUNNING', 'WAITING'].includes(stage.status))
  const expired = value.status === 'RUNNING' && Date.parse(value.deadlineAt) <= preparation.dataUpdatedAt
  const content = !preview.isError ? preview.data : undefined
  return <section className={`article-full-page article-full-page--${layout}`}>
    {!value.knowledgeSourcesCurrent ? <Alert type="warning" showIcon title="引用资料已更新，请核对文章中的事实后再投稿。" /> : null}
    {value.status === 'SUCCEEDED' && value.articleId && ownerId ? <ArticleDocumentContent key={`${ownerId}:${value.articleId}`} ownerId={ownerId} articleId={value.articleId} preparationId={preparationId} layout={layout} /> : <>
      <header className="article-full-status"><Space><Spin size="small" spinning={value.status === 'RUNNING' && !expired} /><Typography.Text strong>{value.status === 'FAILED' ? '文章未完成' : expired ? '文章状态待核对' : currentStage ? stageNames[currentStage.mode] : '正在准备文章'}</Typography.Text></Space><Button size="small" onClick={() => void refresh()}>刷新</Button></header>
      {value.status === 'FAILED' || expired ? <Alert type={value.status === 'FAILED' ? 'error' : 'warning'} showIcon title={value.status === 'FAILED' ? '本次文章未完成，下方内容尚不是最终稿。' : '结果等待核对，请刷新；不要重复创建文章。'} description="已保留收到的内容和任务记录，当前不能保存或投稿。" action={<Space wrap>{value.stages.filter((stage) => stage.taskId && stage.status === 'FAILED').map((stage) => <Link key={stage.taskId} to={appLinks.taskDetail(stage.taskId!)}>查看失败原因</Link>)}</Space>} /> : null}
      <div className="article-full-page__preview">{preview.isError ? <Alert type="warning" showIcon title="正文预览连接暂不可用，生成任务仍可继续。" action={<Button onClick={() => void refresh()}>重新连接</Button>} /> : content?.contentMarkdown ? <article className="article-document article-document--streaming" aria-label="正在生成的文章正文"><Typography.Title level={2}>{content.title || '文章正文'}</Typography.Title><MarkdownMessageContent content={content.contentMarkdown} /><Typography.Text type="secondary">{value.status === 'RUNNING' ? '正在接收正文…' : '尚未完成的内容'}</Typography.Text></article> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={value.status === 'RUNNING' ? '文章正在准备中，正文生成后会在这里显示。' : '暂未收到正文'} />}</div>
      <div className="article-document__actions"><Button type="primary" disabled>投稿</Button><Button disabled>保存到资产中心</Button></div>
    </>}
    <Typography.Text className="article-full-settlement" type="secondary">已结算 {value.settledPoints} 积分 · 已释放 {value.releasedPoints} 积分</Typography.Text>
  </section>
}

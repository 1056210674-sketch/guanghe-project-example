import { ArrowLeftOutlined, EditOutlined, SendOutlined, FolderAddOutlined } from '@ant-design/icons'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Empty, Space, Typography } from 'antd'
import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { confirmArticleBody, getArticle, saveArticleToAssetCollection, type Article } from '../../api/articleApi'
import { listPreparationMediaRecommendations } from '../../api/articleFullApi'
import { listArticleMediaRecommendations } from '../../api/mediaApi'
import { captureAuthSession, isCurrentAuthSession } from '../../auth/tokenStorage'
import { useAssetDownloadUrls } from '../../features/assets/live/assetQueries'
import { SafeArticleContent } from '../../features/articles/components/SafeArticleContent'
import { ArticleContentState } from '../../features/articles/components/ArticleContentState'
import { ArticleMediaRecommendationCard } from '../../features/articles/components/ArticleMediaRecommendationCard'
import { articleKeys } from '../../features/articles/live/articleQueries'
import { toArticleMediaRecommendationSet } from '../../features/articles/live/mediaAdapters'
import { mediaArticleHasImages, mediaArticleHasLinks } from '../../features/articles/live/mediaContentPolicy'
import { submitRecommendedMedia } from '../../features/articles/live/recommendedMediaSubmission'
import type { ArticleRecommendedSubmissionSelection } from '../../features/articles/types'
import { createFormTaskDraftRecoveryStore } from '../../features/task-draft'
import { appLinks, APP_PATHS } from '../../routes/paths'
import '../../styles/article-full.css'

const ArticleRichTextEditor = lazy(() => import('./ArticleRichTextEditor').then((module) => ({ default: module.ArticleRichTextEditor })))
const safeError = (error: unknown) => error instanceof Error ? error.message : '操作失败，请稍后重试。'

export function ArticleDocumentContent({ ownerId, articleId, preparationId, editable = false, layout = 'page' }: { ownerId: string; articleId: string; preparationId?: string; editable?: boolean; layout?: 'page' | 'panel' }) {
  const client = useQueryClient()
  const [session] = useState(captureAuthSession)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const current = () => alive.current && isCurrentAuthSession(session)
  const lock = useRef(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const [editing, setEditing] = useState(false)
  const [mediaOpen, setMediaOpen] = useState(false)
  const viewport = useRef<HTMLDivElement>(null)
  useEffect(() => { if (viewport.current) viewport.current.scrollTop = 0 }, [editing, mediaOpen])
  const legacyRecovery = createFormTaskDraftRecoveryStore({ flow: `article-full-publication-${preparationId}` })
  const commonRecovery = createFormTaskDraftRecoveryStore({ flow: `article-publication-${articleId}` })
  const legacyAttempt = preparationId ? legacyRecovery.read(ownerId) : undefined
  const recovery = legacyAttempt?.confirmationAttempt ? legacyRecovery : commonRecovery
  const [submittedTaskId, setSubmittedTaskId] = useState(() => recovery.read(ownerId).confirmationAttempt?.taskId)
  const keys = articleKeys.forOwner(ownerId)
  const article = useQuery({ queryKey: keys.detail(articleId), queryFn: () => getArticle(articleId), retry: false })
  const value = article.isError ? undefined : article.data
  const completed = value?.status === 'COMPLETED' && Boolean(value.bodyText.trim()) && ['BODY_READY', 'EDITING', 'READY_TO_SUBMIT'].includes(value.workflowStage)
  const recommendations = useQuery({ queryKey: ['article-document-media', ownerId, articleId, value?.version], queryFn: () => listPreparationMediaRecommendations(articleId, session), enabled: Boolean(completed && mediaOpen && !editing), retry: false })
  const downloads = useAssetDownloadUrls(value?.assets.map((asset) => asset.assetId) ?? [])
  const urls = Object.fromEntries(Object.entries(downloads).map(([id, download]) => [id, download?.url]))
  const refresh = async () => {
    await Promise.all([client.invalidateQueries({ queryKey: keys.owner }), client.invalidateQueries({ queryKey: ['article-document-media', ownerId, articleId] })])
  }
  const store = async (updated: Article) => { client.setQueryData(keys.detail(articleId), updated); await refresh() }
  const perform = async (action: () => Promise<void>) => {
    if (lock.current || !current()) return
    lock.current = true; setBusy(true); setError(undefined)
    try { await action() } catch (problem) { if (current()) { setError(safeError(problem)); await refresh() } }
    finally { lock.current = false; if (current()) setBusy(false) }
  }
  async function publish(selection: ArticleRecommendedSubmissionSelection) {
    if (!completed || !value || !recommendations.data || recommendations.isError || submittedTaskId || editing) return
    const viewed = value
    const media = recommendations.data
    if (viewed.version !== selection.articleVersion || media.articleVersion !== selection.articleVersion || media.submissionEnabled === false) return
    await perform(async () => {
      const live = await getArticle(articleId)
      if (!current()) return
      if (live.version !== selection.articleVersion || live.bodyHtml !== viewed.bodyHtml || live.title !== viewed.title) throw new Error('文章已在别处修改。请核对最新正文和媒体后再确认投稿。')
      // The only authorization is the final media/price confirmation; no intermediate title/body prompt.
      if (['BODY_READY', 'EDITING'].includes(live.workflowStage)) {
        await confirmArticleBody(live.id, { version: live.version, goal: media.strategy.goal, preferredRegion: media.strategy.preferredRegion, maxTotalPoints: media.strategy.maxTotalPoints, requiresLink: media.strategy.requiresLink })
        if (!current()) return
      }
      const result = await submitRecommendedMedia({ selection, recovery: recovery.read(ownerId), isCurrent: current, onRecoveryChange: (next) => { if (current()) recovery.write(ownerId, next) }, dependencies: { loadRecommendations: listArticleMediaRecommendations } })
      if (!current()) return
      setSubmittedTaskId(result.accepted.taskId)
      commonRecovery.write(ownerId, recovery.read(ownerId))
      await refresh()
    })
  }
  if (article.isLoading) return <ArticleContentState layout={layout} loading="读取文章…" />
  if (article.isError) return <ArticleContentState layout={layout}><Alert type="error" showIcon title="文章读取失败" description={safeError(article.error)} action={<Button onClick={() => void article.refetch()}>重新加载</Button>} /></ArticleContentState>
  if (!value) return <ArticleContentState layout={layout}><Empty description="文章暂不可用" /></ArticleContentState>
  return <section className={`article-document article-document--${layout}`}>
    {error || submittedTaskId ? <div className="article-document__feedback" aria-live="polite">
      {error ? <Alert type="error" showIcon title={error} /> : null}
      {submittedTaskId ? <Alert type="success" showIcon title="投稿已受理，等待媒体处理" description="受理不代表已经发布。发布结果和回链可以在投稿记录中查看。" action={<Space wrap><Link to={appLinks.taskDetail(submittedTaskId)}>查看任务</Link><Link to={`${APP_PATHS.submissions}?tab=records`}>查看投稿记录</Link></Space>} /> : null}
    </div> : null}
    <div className="article-document__viewport" ref={viewport}>
    {editing ? <Suspense fallback={<ArticleContentState loading="加载文章编辑器…" />}><ArticleRichTextEditor key={`${ownerId}:${articleId}:editor`} article={value} onCancel={() => { setEditing(false); void refresh() }} onPersisted={(updated) => { if (current()) client.setQueryData(keys.detail(articleId), updated) }} onSaved={(updated, intent) => { if (current()) { setEditing(false); setMediaOpen(intent === 'publish'); setError(undefined); void store(updated) } }} /></Suspense> : <>
      <header className="article-document__heading"><Typography.Title level={2}>{value.title || '文章正文'}</Typography.Title><Space wrap><Typography.Text type="secondary">版本 {value.version}</Typography.Text>{editable && completed ? <Button size="small" icon={<EditOutlined />} disabled={busy || article.isFetching || Boolean(submittedTaskId)} onClick={() => { setMediaOpen(false); setEditing(true) }}>编辑文章</Button> : null}<Button type="text" size="small" loading={article.isFetching} disabled={busy} onClick={() => void refresh()}>刷新</Button></Space></header>
      {mediaOpen ? <><Button className="article-document__back" icon={<ArrowLeftOutlined />} onClick={() => setMediaOpen(false)}>返回正文</Button><ArticleMediaRecommendationCard recommendations={recommendations.isError ? undefined : recommendations.data ? toArticleMediaRecommendationSet(recommendations.data) : undefined} loading={recommendations.isLoading} submitting={busy} submissionBlocked={Boolean(submittedTaskId) || !completed}
        imageCount={value.assets.length} imageUnsupported={mediaArticleHasImages({ bodyHtml: value.bodyHtml, assetCount: value.assets.length })} hasExternalLinks={mediaArticleHasLinks(value.bodyHtml)} error={recommendations.isError ? { message: '媒体推荐暂不可用，尚未投稿。请刷新后再确认。' } : null} onRefresh={() => void recommendations.refetch()} onConfirmSubmission={(selection) => void publish(selection)} /></> : <SafeArticleContent html={value.bodyHtml} fallback="正文尚未准备完成" imageUrls={urls} />}
      {!completed ? <Alert type="info" showIcon title="正文尚未完成，暂不能保存到资产中心或投稿。" /> : null}
    </>}
    </div>
    {!editing && !mediaOpen ? <footer className="article-document__actions" aria-label="文章操作"><Button type="primary" icon={<SendOutlined />} disabled={!completed || busy || Boolean(submittedTaskId)} onClick={() => setMediaOpen(true)}>投稿</Button><Button icon={<FolderAddOutlined />} loading={busy} disabled={!completed || busy || value.savedToAssets && value.savedArticleVersion === value.version} onClick={() => void perform(async () => { const saved = await saveArticleToAssetCollection(articleId, { version: value.version }); if (current()) await store(saved) })}>{value.savedToAssets && value.savedArticleVersion === value.version ? '已保存到资产中心' : '保存到资产中心'}</Button>{value.savedToAssets ? <Link to={`${APP_PATHS.assets}?tab=articles&article=${encodeURIComponent(articleId)}`}>前往资产中心编辑</Link> : null}</footer> : null}
  </section>
}

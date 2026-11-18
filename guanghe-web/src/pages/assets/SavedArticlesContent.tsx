import { FileTextOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { Alert, App, Button, Card, Drawer, Empty, Pagination, Select, Space, Spin, Typography } from 'antd'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { listArticles, type ArticleSummary } from '../../api/articleApi'
import { articleKeys } from '../../features/articles/live/articleQueries'
import { useProjects } from '../../features/agent-platform/queries'
import { useAuthStore } from '../../stores/authStore'
import { ArticleDocumentContent } from '../articles/ArticleDocumentContent'

export function SavedArticlesContent() {
  const ownerId = useAuthStore((state) => state.user?.id)
  const { modal } = App.useApp()
  const [search, setSearch] = useSearchParams()
  const [spaceId, setSpaceId] = useState<string>()
  const [page, setPage] = useState(1)
  const spaces = useProjects()
  const query = { page, size: 20, spaceId, savedToAssets: true }
  const articles = useQuery({ queryKey: articleKeys.forOwner(ownerId).list(query), queryFn: () => listArticles(query), enabled: Boolean(ownerId), retry: false })
  const items: readonly ArticleSummary[] = articles.data?.items ?? []
  const requestedId = search.get('article')
  const articleId = requestedId && /^[1-9]\d*$/.test(requestedId) ? requestedId : undefined
  const select = (id?: string) => setSearch((previous) => { const next = new URLSearchParams(previous); if (id) next.set('article', id); else next.delete('article'); return next }, { replace: true })
  const close = () => {
    if (document.querySelector('[data-article-editor-dirty="true"]')) modal.confirm({ title: '离开文章编辑？', content: '未保存的修改会暂存在当前浏览器标签页，再次编辑时可以恢复。', okText: '暂存并离开', cancelText: '继续编辑', onOk: () => select(undefined) })
    else select(undefined)
  }
  return <section className="saved-articles">
    <header><div><Typography.Title level={2}>文章</Typography.Title><Typography.Text type="secondary">你保存的文章，可继续编辑和配图。修改与原对话中的文章同步。</Typography.Text></div><Space wrap><Select aria-label="筛选文章项目" allowClear placeholder="全部项目" value={spaceId} style={{ minWidth: 180 }} loading={spaces.isLoading} options={(spaces.data ?? []).map((space) => ({ value: space.id, label: space.name }))} onChange={(value) => { setSpaceId(value); setPage(1) }} /><Button onClick={() => void articles.refetch()} loading={articles.isFetching}>刷新</Button></Space></header>
    {articles.isError ? <Alert type="error" showIcon title="文章加载失败" description={articles.error instanceof Error ? articles.error.message : '请稍后再试。'} action={<Button onClick={() => void articles.refetch()}>重试</Button>} /> : articles.isLoading ? <Spin /> : items.length ? <><div className="saved-articles__grid">{items.map((article) => <Card key={article.id} size="small"><FileTextOutlined className="saved-articles__icon" /><Typography.Title level={4}>{article.title || '未命名文章'}</Typography.Title><Typography.Paragraph type="secondary">{spaces.data?.find((space) => space.id === article.spaceId)?.name ?? '所属项目'} · 版本 {article.version}</Typography.Paragraph><Typography.Paragraph type="secondary">更新于 {new Date(article.updatedAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })}</Typography.Paragraph><Button onClick={() => select(article.id)}>查看与编辑</Button></Card>)}</div><Pagination current={page} pageSize={20} total={articles.data?.total ?? 0} showSizeChanger={false} hideOnSinglePage onChange={setPage} /></> : <Empty description="还没有保存的文章。生成后点击“保存到资产中心”，即可在这里继续编辑。" />}
    <Drawer title="文章编辑" className="saved-article-drawer" open={Boolean(articleId)} size={1180} destroyOnHidden onClose={close}>{articleId && ownerId ? <ArticleDocumentContent key={`${ownerId}:${articleId}`} ownerId={ownerId} articleId={articleId} editable layout="panel" /> : null}</Drawer>
  </section>
}

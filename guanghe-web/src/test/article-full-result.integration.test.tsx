import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App, ConfigProvider } from 'antd'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { ArticlePreparationContent } from '../pages/articles/ArticlePreparationPage'
import type { Article, ArticleSubmissionSnapshot } from '../api/articleApi'
import type { ArticleFullPreparation } from '../api/articleFullApi'
import type { MediaRecommendationSet, MediaSubmissionSummary } from '../api/mediaApi'
import type { TaskDraft } from '../api/conversationApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'
import { useAuthStore } from '../stores/authStore'
import { renderApp } from './renderApp'

const now = '2026-10-07T01:00:00Z'
const article: Article = { id: '701', spaceId: '201', productId: null, title: '来小行星咖啡喝杯咖啡', status: 'COMPLETED', version: 5, savedToAssets: false, savedToAssetsAt: null, savedArticleVersion: null, createdAt: now, updatedAt: now, bodyHtml: '<p>这里有咖啡、曲奇和蛋糕，实际供应以门店为准。</p>', bodyText: '这里有咖啡、曲奇和蛋糕，实际供应以门店为准。', outline: [], assets: [], workflowStage: 'BODY_READY', titleCandidateBatch: null, confirmedTitle: null, creativePlan: null }
const preparation: ArticleFullPreparation = { id: '601', templateCode: 'ARTICLE_FULL_V1', status: 'SUCCEEDED', conversationId: '301', articleId: '701', articleVersion: 5, quotedPoints: 3, settledPoints: 3, releasedPoints: 0, deadlineAt: '2099-01-01T00:00:00Z', errorCode: null, stages: ['TITLE', 'OUTLINE', 'BODY'].map((mode, index) => ({ ordinal: index + 1, mode: mode as 'TITLE' | 'OUTLINE' | 'BODY', status: 'SUCCEEDED', taskId: null, quotedPoints: 1, errorCode: null })), publicationRequiresConfirmation: true, knowledgeSourcesCurrent: true }
const media: MediaRecommendationSet = { articleId: '701', articleVersion: 5, generatedAt: now, submissionEnabled: true, strategy: { goal: 'BALANCED', requiresLink: false, detectedCategories: ['餐饮'], candidateCount: 1, dataLimitations: [] }, items: [{ rank: 1, mediaResourceId: '801', reasonCode: 'INDUSTRY_CATEGORY_MATCH', reason: '餐饮频道可投', score: 1, evidence: ['品类匹配'], resource: { id: '801', providerCode: 'MEDIA', upstreamModelId: 'NEWS', upstreamResourceId: 'external', name: '餐饮资讯', status: 'ACTIVE', capabilities: {}, saleAmountCents: 1000, chargedPoints: 10, quoteExpiresAt: '2099-01-01T00:00:00Z', syncedAt: now } }] }
const snapshot: ArticleSubmissionSnapshot = { id: '901', articleId: '701', spaceId: '201', productId: null, articleVersion: 5, title: article.title, bodyHtml: article.bodyHtml, bodyText: article.bodyText, bodyHash: 'a'.repeat(64), outline: [], assets: [], createdAt: now }
const placement: MediaSubmissionSummary = { id: '1401', batchId: '1301', taskId: '1201', articleId: article.id, articleSnapshotId: snapshot.id, mediaResourceId: '801', mediaName: '餐饮资讯', status: 'PUBLISHED', saleAmountCents: 1000, chargedPoints: 10, publishedUrl: 'https://example.invalid/xiaoxingxing-coffee', publishedUrls: ['https://example.invalid/xiaoxingxing-coffee'], stableErrorCode: null, createdAt: now, updatedAt: now }
const draft: TaskDraft = { id: '1001', draftSource: 'FORM', taskType: 'MEDIA', parameterSchemaVersion: 1, status: 'WAITING_CONFIRMATION', spaceId: '201', productId: null, sourceMode: null, referenceAssetIds: [], parameters: { articleId: '701', articleSnapshotId: '901', items: [{ mediaResourceId: '801', remark: null }] }, warnings: [], inputHash: 'b'.repeat(64), pricingVersionId: '1101', quotedPoints: 10, confirmationToken: 'test-token', expiresAt: '2099-01-01T00:00:00Z', createdAt: now }
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data }), { headers: { 'Content-Type': 'application/json' } })
function stub(options: { changedVersion?: boolean; changedQuote?: boolean; sourceStale?: boolean; articleFailure?: boolean } = {}) {
  const writes: { url: string; body: Record<string, unknown> }[] = []; let reads = 0; let approved = false
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'
    if (method !== 'GET') writes.push({ url, body: init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : {} })
    if (url === '/api/v1/article-full-preparations/601') return ok({ ...preparation, knowledgeSourcesCurrent: !options.sourceStale })
    if (url === '/api/v1/articles/701') { reads += 1; if (options.articleFailure) return new Response(JSON.stringify({ code: 'UNAVAILABLE', message: '读取失败', traceId: testTraceId }), { status: 503, headers: { 'Content-Type': 'application/json' } }); return ok({ ...article, workflowStage: approved ? 'READY_TO_SUBMIT' : 'BODY_READY', version: options.changedVersion && reads > 1 ? 6 : 5 }) }
    if (url.endsWith('/preparation-media-recommendations') || url.startsWith('/api/v1/media/recommendations?')) return ok(media)
    if (url.includes('/body/confirm')) { approved = true; return ok({ ...article, workflowStage: 'READY_TO_SUBMIT' }) }
    if (url.endsWith('/submission-snapshots')) return ok(snapshot)
    if (url === '/api/v1/media/quotes') return ok({ ...draft, quotedPoints: options.changedQuote ? 12 : 10 })
    if (url === '/api/v1/task-drafts/1001/confirm') return ok({ taskId: '1201', status: 'QUEUED' })
    if (url === '/api/v1/task-drafts/1001/abandon') return ok(null)
    if (url.startsWith('/api/v1/media/submissions?')) return ok({ items: [placement], page: 1, size: 20, total: 1, hasNext: false })
    if (url.startsWith('/api/v1/media/resources?')) return ok({ items: [], page: 1, size: 20, total: 0, hasNext: false })
    if (url === '/api/v1/projects') return ok([])
    if (url.startsWith('/api/v1/conversations?')) return ok({ items: [], page: 1, size: 20, total: 0, hasNext: false })
    if (url === '/api/v1/conversations') return ok({ items: [], page: 1, size: 20, total: 0, hasNext: false })
    if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 100, frozenPoints: 0, version: 1 })
    if (url === '/api/v1/notifications/unread-count') return ok({ unreadCount: 0 })
    throw new Error(`Unexpected request ${url}`)
  }))
  return writes
}
const renderResult = () => render(<ConfigProvider theme={{ token: { motion: false } }}><App><QueryClientProvider client={createQueryClient()}><MemoryRouter><ArticlePreparationContent ownerId="1" preparationId="601" /></MemoryRouter></QueryClientProvider></App></ConfigProvider>)
beforeEach(() => { vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear(); storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt }); useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null }) })
describe('完整正文到最终投稿的一次确认 UI 旅程', () => {
  it('直接查看完整文章和真实媒体，只有最后一次确认点击才会批准正文、快照、报价和提交', async () => {
    const writes = stub({ sourceStale: true }); const user = userEvent.setup(); renderResult()
    await screen.findByText(article.bodyText); await screen.findByText('餐饮资讯'); expect(writes).toHaveLength(0)
    expect(screen.getByText('引用的项目资料已经修订或移除')).toBeInTheDocument()
    expect(screen.queryByText('确认标题')).not.toBeInTheDocument(); expect(screen.queryByText('确认生成文章')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /确认投稿/ }))
    await screen.findByText('投稿任务已受理，尚不代表已发布')
    expect(writes.map((item) => item.url)).toEqual(['/api/v1/articles/701/workflow/body/confirm', '/api/v1/articles/701/submission-snapshots', '/api/v1/media/quotes', '/api/v1/task-drafts/1001/confirm'])
    expect(writes[1].body).toEqual({ articleVersion: 5 })
    expect(writes[2].body).toEqual({ articleId: '701', articleSnapshotId: '901', items: [{ mediaResourceId: '801', remark: null }] })
    await user.click(screen.getByRole('button', { name: /确认投稿/ })); expect(writes).toHaveLength(4)
  })
  it('用户看到的正文版本发生变化，不批准正文，也不快照或扣费', async () => {
    const writes = stub({ changedVersion: true }); const user = userEvent.setup(); renderResult(); await screen.findByText('餐饮资讯')
    await user.click(screen.getByRole('button', { name: /确认投稿/ })); await screen.findByText(/正文已在别处修改/); expect(writes).toHaveLength(0)
  })
  it('最终价格变更，不默扣新价或自动确认，废弃等待中的报价', async () => {
    const writes = stub({ changedQuote: true }); const user = userEvent.setup(); renderResult(); await screen.findByText('餐饮资讯')
    await user.click(screen.getByRole('button', { name: /确认投稿/ })); await screen.findByText(/最终报价为 12 积分/)
    expect(writes.some((item) => item.url.endsWith('/1001/confirm'))).toBe(false); expect(writes.at(-1)?.url).toBe('/api/v1/task-drafts/1001/abandon')
  })
  it('正文无法读取时不给空正文投稿，也不显示缓存成功', async () => {
    const writes = stub({ articleFailure: true }); renderResult(); await screen.findByText('正文读取失败，暂不能投稿')
    await waitFor(() => expect(screen.queryByRole('button', { name: /确认投稿/ })).not.toBeInTheDocument()); expect(writes).toHaveLength(0)
  })
  it('真实成果路由完成一次确认后，发布回链按钮进入记录而不是目录，查看过程不再次下单', async () => {
    const writes = stub(); const user = userEvent.setup(); renderApp(['/app/article-preparations/601'])
    await screen.findByText('餐饮资讯', {}, { timeout: 5000 })
    await user.click(screen.getByRole('button', { name: /确认投稿/ }))
    await screen.findByText('投稿任务已受理，尚不代表已发布')
    expect(writes).toHaveLength(4)
    await user.click(screen.getByText('查看发布记录与回链'))
    await screen.findByLabelText('查看投稿 1401', {}, { timeout: 5000 })
    expect(screen.getByRole('tab', { name: '投稿记录' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('link', { name: /打开发布页面/ })).toHaveAttribute('href', placement.publishedUrl)
    expect(writes).toHaveLength(4)
  })
})

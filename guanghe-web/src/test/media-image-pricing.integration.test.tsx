import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import type { Article, ArticleSubmissionSnapshot } from '../api/articleApi'
import type { MediaArticlePricing, MediaRecommendationSet, MediaResource } from '../api/mediaApi'
import type { TaskDraft } from '../api/conversationApi'
import { storeAuthToken } from '../auth/tokenStorage'
import { testAccessToken, testExpiresAt } from './fixtures/auth'
import { ArticleMediaRecommendationCard } from '../features/articles/components/ArticleMediaRecommendationCard'
import { toArticleMediaRecommendationSet, toLiveMediaResource, toMediaPaidTaskConfirmation } from '../features/articles/live/mediaAdapters'
import { submitRecommendedMedia } from '../features/articles/live/recommendedMediaSubmission'

const date = '2026-10-09T01:00:00Z'
const price = (mode: MediaArticlePricing['imageFeeMode'], fee: number): MediaArticlePricing => ({
  imageCount: 2, baseSaleAmountCents: 12000, imageFeeAmountCents: fee * 100,
  totalSaleAmountCents: 12000 + fee * 100, basePoints: 120, imageFeePoints: fee,
  totalPoints: 120 + fee, imageFeeMode: mode, description: '按本稿配图计算，已包含在总价中。',
})
const mediaResource = (id: string, name: string, pricing?: MediaArticlePricing): MediaResource => ({
  id, name, providerCode: 'MEDIA', upstreamModelId: 'NEWS', upstreamResourceId: id, status: 'ACTIVE',
  capabilities: pricing ? { supportsImages: true, maxImageCount: 10, imageFeeMode: pricing.imageFeeMode === 'NONE' ? 'INCLUDED' : pricing.imageFeeMode, articlePricing: pricing } : { supportsImages: true, maxImageCount: 10, imageFeePoints: 0 },
  saleAmountCents: pricing?.totalSaleAmountCents ?? 12000, chargedPoints: pricing?.totalPoints ?? 120,
  quoteExpiresAt: '2099-10-09T01:00:00Z', syncedAt: date,
})
const recommendations = (...resources: MediaResource[]): MediaRecommendationSet => ({
  articleId: '701', articleVersion: 5, generatedAt: date, submissionEnabled: true,
  strategy: { goal: 'BALANCED', requiresLink: false, detectedCategories: [], candidateCount: resources.length, dataLimitations: [] },
  items: resources.map((resource, index) => ({ rank: index + 1, mediaResourceId: resource.id, reasonCode: 'ACTIVE_RESOURCE_FALLBACK', reason: '匹配本稿', score: 80, evidence: [], resource })),
})
const article: Article = { id: '701', spaceId: '201', title: '图文投稿验收', status: 'COMPLETED', version: 5, savedToAssets: true,
  savedToAssetsAt: date, savedArticleVersion: 5, createdAt: date, updatedAt: date, bodyHtml: '<p>正文</p><figure data-asset-id="901"></figure><figure data-asset-id="902"></figure>', bodyText: '正文', outline: [],
  assets: [{ assetId: '901', positionKey: 'first', sortOrder: 0 }, { assetId: '902', positionKey: 'second', sortOrder: 1 }],
  workflowStage: 'READY_TO_SUBMIT', titleCandidateBatch: null, confirmedTitle: null, creativePlan: null }
const snapshot: ArticleSubmissionSnapshot = { id: '801', articleId: article.id, spaceId: '201', articleVersion: 5, title: article.title,
  bodyHtml: article.bodyHtml, bodyText: article.bodyText, bodyHash: 'a'.repeat(64), assets: article.assets, outline: [], createdAt: date }

beforeEach(() => { vi.unstubAllGlobals(); sessionStorage.clear(); localStorage.clear(); storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt }) })

it('同一投稿卡按本稿数量展示免费、按张和按篇含图总价；未知费用不可选，只保留一次确认', async () => {
  const choices = recommendations(mediaResource('301', '免费图片媒体', price('INCLUDED', 0)),
    mediaResource('302', '按张图片媒体', price('PER_IMAGE', 20)), mediaResource('303', '按篇图片媒体', price('PER_ARTICLE', 60)),
    mediaResource('304', '未知图片费用媒体'))
  const submit = vi.fn()
  const { rerender } = render(<ArticleMediaRecommendationCard recommendations={toArticleMediaRecommendationSet(choices)} imageCount={2} onConfirmSubmission={submit} onRefresh={vi.fn()} />)
  expect(screen.getByText('当前文章：2 张配图；投稿使用当前已保存版本。')).toBeInTheDocument()
  expect(screen.getByRole('checkbox', { name: '选择投稿媒体 未知图片费用媒体' })).toBeDisabled()
  expect(screen.getByText('尚无本稿图片费用明细，不能按免费处理或确认投稿。')).toBeInTheDocument()
  const item = screen.getByText('按张图片媒体').closest('li')!
  expect(within(item).getByText(/基础费用 ¥120.00.*图片附加费 ¥20.00/)).toBeInTheDocument()
  expect(within(item).getByText(/本稿总价 ¥140.00/)).toBeInTheDocument()
  const button = screen.getByRole('button', { name: /确认投稿（440 积分）/ })
  expect(button).toBeEnabled()
  expect(submit).not.toHaveBeenCalled()
  await userEvent.setup().click(button)
  expect(submit).toHaveBeenCalledTimes(1)
  expect(submit.mock.calls[0][0]).toMatchObject({ expectedPoints: 440, mediaResourceIds: ['301', '302', '303'] })
  expect(submit.mock.calls[0][0].expectedPricing).toHaveLength(3)
  rerender(<ArticleMediaRecommendationCard recommendations={toArticleMediaRecommendationSet(choices)} imageCount={3} onConfirmSubmission={submit} onRefresh={vi.fn()} />)
  expect(screen.getByRole('button', { name: /确认投稿/ })).toBeDisabled()
})

it('推荐卡不展示过程说明，已有任务时只禁用确认操作', () => {
  const choices = recommendations(mediaResource('301', '普通媒体'))
  choices.strategy.dataLimitations = ['媒体库暂未提供历史过稿率，本次未对该指标评分。']
  const submit = vi.fn()

  const { rerender } = render(
    <ArticleMediaRecommendationCard
      recommendations={toArticleMediaRecommendationSet(choices)}
      submissionBlocked
      onConfirmSubmission={submit}
      onRefresh={vi.fn()}
    />,
  )

  expect(screen.queryByText('推荐数据说明')).not.toBeInTheDocument()
  expect(screen.queryByText('确认后才会触发投稿')).not.toBeInTheDocument()
  expect(screen.queryByText('已有投稿任务正在执行')).not.toBeInTheDocument()
  expect(screen.getByRole('checkbox', { name: '选择投稿媒体 普通媒体' })).toBeDisabled()
  expect(screen.getByRole('button', { name: /确认投稿/ })).toBeDisabled()

  choices.submissionEnabled = false
  rerender(
    <ArticleMediaRecommendationCard
      recommendations={toArticleMediaRecommendationSet(choices)}
      onConfirmSubmission={submit}
      onRefresh={vi.fn()}
    />,
  )
  expect(screen.queryByText('投稿执行暂未开放')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: /确认投稿/ })).toBeDisabled()
  expect(submit).not.toHaveBeenCalled()
})

it('媒体目录基础价格不会覆盖最终含图报价，确认明细显示图片费用与实际总价', () => {
  const pricing = price('PER_ARTICLE', 60)
  const catalogResource = mediaResource('303', '按篇图片媒体', pricing)
  delete catalogResource.capabilities.articlePricing
  catalogResource.saleAmountCents = 12000
  catalogResource.chargedPoints = 120
  const draft: TaskDraft = { id: '401', taskType: 'MEDIA', draftSource: 'FORM', parameterSchemaVersion: 1, status: 'WAITING_CONFIRMATION', sourceMode: null, referenceAssetIds: [],
    spaceId: '201', parameters: { articleId: '701', articleSnapshotId: '801', items: [{ mediaResourceId: '303', remark: null, pricing }] }, warnings: [],
    inputHash: 'b'.repeat(64), pricingVersionId: '501', quotedPoints: 180, confirmationToken: 'fixture', expiresAt: '2099-10-09T01:00:00Z', createdAt: date }
  const view = toMediaPaidTaskConfirmation(draft, { userId: '1', availablePoints: 1000, frozenPoints: 0, version: 1 },
    { id: snapshot.id, articleId: article.id, title: article.title, versionLabel: 'V5', createdAt: date, bodyHtml: article.bodyHtml, bodyText: article.bodyText, outlineCount: 0, assetCount: 2 },
    [toLiveMediaResource(catalogResource)], false, false, true)
  expect(view?.confirmEnabled).toBe(true)
  expect(view?.mediaSubmissionDetails?.targets[0]).toMatchObject({ saleAmountLabel: '¥180.00', chargedPoints: 180 })
  expect(view?.mediaSubmissionDetails?.targets[0].imageFeeLabel).toContain('图片附加费 ¥60.00 / 60 积分')
})

it('最终图片费金额变化即使总积分相同，也停止投稿而不确认任务', async () => {
  const pricing = price('PER_IMAGE', 20)
  const choices = recommendations(mediaResource('302', '按张图片媒体', pricing))
  const changed = { ...pricing, imageFeeAmountCents: 2001, totalSaleAmountCents: 14001 }
  const draft: TaskDraft = { id: '401', taskType: 'MEDIA', draftSource: 'FORM', parameterSchemaVersion: 1, status: 'WAITING_CONFIRMATION', sourceMode: null, referenceAssetIds: [],
    spaceId: '201', parameters: { articleId: '701', articleSnapshotId: '801', items: [{ mediaResourceId: '302', remark: null, pricing: changed }] }, warnings: [],
    inputHash: 'b'.repeat(64), pricingVersionId: '501', quotedPoints: 140, confirmationToken: 'fixture', expiresAt: '2099-10-09T01:00:00Z', createdAt: date }
  const calls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input); calls.push(path)
    if (!['/api/v1/media/quotes', '/api/v1/task-drafts/401/abandon'].includes(path)) throw new Error('Unexpected business request')
    return new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: 'a'.repeat(32), data: path.endsWith('/quotes') ? draft : null }), { headers: { 'Content-Type': 'application/json' } })
  }))
  await expect(submitRecommendedMedia({ selection: { articleId: article.id, articleVersion: 5, expectedPoints: 140, mediaResourceIds: ['302'], expectedPricing: [{ mediaResourceId: '302', pricing }] },
    recovery: {}, onRecoveryChange: vi.fn(), dependencies: { loadArticle: async () => article, loadRecommendations: async () => choices, createSnapshot: async () => snapshot } })).rejects.toMatchObject({ code: 'MEDIA_QUOTE_CHANGED' })
  expect(calls).toEqual(['/api/v1/media/quotes', '/api/v1/task-drafts/401/abandon'])
})

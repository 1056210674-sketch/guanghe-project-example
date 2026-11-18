import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createMediaQuote,
  getMediaResource,
  getMediaSubmission,
  listMediaResources,
  listMediaSubmissions,
} from '../api/mediaApi'
import type { MediaResource, MediaSubmissionDetail } from '../api/mediaApi'
import type { TaskDraft } from '../api/conversationApi'
import { storeAuthToken } from '../auth/tokenStorage'
import {
  ArticleMediaRecommendationCard,
  ArticleMediaSubmissionPanel,
  getMediaTaskRecoveryStorageKey,
  mediaArticleHasImages,
  mediaArticleHasLinks,
  mediaResourceSupportsLinks,
  mediaSubmissionListNeedsPolling,
  mediaSubmissionNeedsPolling,
  mediaSubmissionPollPauseAt,
  mediaSubmissionPollInterval,
  mediaSubmissionPollingPaused,
  storeMediaTaskRecovery,
  toLiveMediaResource,
  toLiveMediaSubmissionDetail,
  toMediaPaidTaskConfirmation,
} from '../features/articles'
import type {
  ArticleMediaRecommendationSetView,
  ArticleMediaSubmissionPanelProps,
  ArticleWorkspaceRecordView,
  SubmissionSnapshotView,
} from '../features/articles'
import { prepareFormTaskDraft } from '../features/task-draft'

const traceId = '1'.repeat(32)
const articleId = '91000000000000001'
const snapshotId = '91000000000000002'
const resourceId = '91000000000000003'
const submissionId = '91000000000000004'

function ok(data: unknown, status = 200) {
  return new Response(
    JSON.stringify({ code: 'OK', message: 'success', data, traceId }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function stubFetch(...responses: Response[]) {
  const fetchMock = vi.fn<typeof fetch>()
  responses.forEach((response) => fetchMock.mockResolvedValueOnce(response))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const resource: MediaResource = {
  id: resourceId,
  providerCode: 'MEDIA_PROVIDER',
  upstreamModelId: 'NEWS',
  upstreamResourceId: 'external-resource',
  name: '行业媒体 A',
  status: 'ACTIVE',
  resourceType: '行业媒体',
  channelCategory: '科技',
  region: '全国',
  estimatedDays: 3,
  capabilities: {},
  saleAmountCents: 12000,
  chargedPoints: 120,
  quoteExpiresAt: '2099-09-09T10:00:00Z',
  syncedAt: '2026-09-09T10:00:00Z',
}

const draft: TaskDraft = {
  id: '91000000000000005',
  draftSource: 'FORM',
  taskType: 'MEDIA',
  parameterSchemaVersion: 1,
  status: 'WAITING_CONFIRMATION',
  spaceId: '91000000000000006',
  productId: null,
  sourceMode: null,
  referenceAssetIds: [],
  parameters: {
    articleId,
    articleSnapshotId: snapshotId,
    items: [{ mediaResourceId: resourceId, remark: '保持原排版' }],
  },
  warnings: ['投稿属于不可逆操作'],
  inputHash: 'a'.repeat(64),
  pricingVersionId: '91000000000000007',
  quotedPoints: 120,
  confirmationToken: 'opaque-confirmation-token',
  expiresAt: '2099-09-09T10:00:00Z',
  createdAt: '2026-09-09T10:00:00Z',
}

const submission: MediaSubmissionDetail = {
  id: submissionId,
  batchId: '91000000000000008',
  taskId: '91000000000000009',
  articleId,
  articleSnapshotId: snapshotId,
  mediaResourceId: resourceId,
  mediaName: resource.name,
  status: 'SUBMIT_UNKNOWN',
  saleAmountCents: 12000,
  chargedPoints: 120,
  publishedUrl: 'https://example.com/published/article',
  publishedUrls: ['https://example.com/published/article'],
  stableErrorCode: null,
  createdAt: '2026-09-09T10:00:00Z',
  updatedAt: '2026-09-09T10:05:00Z',
  remark: '保持原排版',
  events: [
    {
      id: '91000000000000010',
      sequence: 1,
      eventType: 'SUBMISSION_CREATED',
      fromStatus: null,
      toStatus: 'SUBMIT_UNKNOWN',
      stableErrorCode: null,
      occurredAt: '2026-09-09T10:00:00Z',
    },
  ],
}

const article: ArticleWorkspaceRecordView = {
  id: articleId,
  spaceId: '91000000000000006',
  title: '正式文章',
  bodyHtml: '<p>正文</p>',
  bodyText: '正文',
  status: 'DRAFT',
  statusLabel: '草稿',
  version: 3,
  updatedAt: '2026/09/09 18:00',
  outline: [],
  assets: [],
}

const snapshot: SubmissionSnapshotView = {
  id: snapshotId,
  articleId,
  title: '正式文章',
  versionLabel: 'V3',
  createdAt: '2026/09/09 18:00',
  bodyHtml: '<p>正文</p>',
  bodyText: '正文',
  outlineCount: 0,
  assetCount: 0,
}

function panelProps(
  overrides: Partial<ArticleMediaSubmissionPanelProps> = {},
): ArticleMediaSubmissionPanelProps {
  return {
    article,
    snapshot,
    resources: [],
    resourceModels: [],
    resourceTypes: [],
    resourceFilter: { search: '' },
    resourcePagination: { current: 1, pageSize: 20, total: 0 },
    submissions: [],
    submissionPagination: { current: 1, pageSize: 20, total: 0 },
    onCreateSnapshot: vi.fn(),
    onResourceFilterChange: vi.fn(),
    onResourcePageChange: vi.fn(),
    onOpenResource: vi.fn(),
    onPrepareQuote: vi.fn(),
    onEditQuote: vi.fn(),
    onConfirmQuote: vi.fn(),
    onOpenRecharge: vi.fn(),
    onSubmissionStatusChange: vi.fn(),
    onSubmissionPageChange: vi.fn(),
    onRefreshSubmissions: vi.fn(),
    onOpenSubmission: vi.fn(),
    onRefreshSubmission: vi.fn(),
    onViewTask: vi.fn(),
    onTaskAction: vi.fn(),
    onRetry: vi.fn(),
    ...overrides,
  }
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  storeAuthToken({
    accessToken: 'media-browser-token',
    expiresAt: '2099-01-01T00:00:00Z',
  })
})

describe('MEDIA Browser API 与投稿闭环', () => {
  it('媒体价格筛选保留两个独立输入，修改最高价不改变最低价或直接触发投稿', () => {
    const onResourceFilterChange = vi.fn()
    const onConfirmQuote = vi.fn()
    const { container } = render(<ArticleMediaSubmissionPanel {...panelProps({
      resourceFilter: { search: '行业', minSaleAmountCents: 100, maxSaleAmountCents: 500 },
      onResourceFilterChange,
      onConfirmQuote,
    })} />)
    const filters = container.querySelector('.media-live-resource-filter')!
    expect(filters.children).toHaveLength(6)
    const minimum = screen.getByRole('spinbutton', { name: '最低销售价（分）' })
    const maximum = screen.getByRole('spinbutton', { name: '最高销售价（分）' })
    expect(filters).toContainElement(minimum)
    expect(filters).toContainElement(maximum)
    expect(minimum).toHaveValue('100')
    fireEvent.change(maximum, { target: { value: '800' } })
    expect(onResourceFilterChange).toHaveBeenLastCalledWith({
      search: '行业', minSaleAmountCents: 100, maxSaleAmountCents: 800,
    })
    expect(onConfirmQuote).not.toHaveBeenCalled()
  })

  it('仅对尚未终态的投稿继续轮询', () => {
    expect(mediaSubmissionNeedsPolling('QUEUED')).toBe(true)
    expect(mediaSubmissionNeedsPolling('SUBMIT_UNKNOWN')).toBe(true)
    expect(mediaSubmissionNeedsPolling('RECONCILING')).toBe(true)
    expect(mediaSubmissionNeedsPolling('PUBLISHED')).toBe(false)
    expect(mediaSubmissionNeedsPolling('REJECTED')).toBe(false)
    expect(mediaSubmissionNeedsPolling('FAILED')).toBe(false)
    expect(mediaSubmissionListNeedsPolling([
      { status: 'PUBLISHED' },
      { status: 'ACCEPTED' },
    ])).toBe(true)
    expect(mediaSubmissionListNeedsPolling([
      { status: 'PUBLISHED' },
      { status: 'REJECTED' },
    ])).toBe(false)
    expect(mediaSubmissionPollInterval({ status: 'QUEUED' })).toBe(5_000)
    expect(mediaSubmissionPollInterval({ status: 'RECONCILING' })).toBe(10_000)
    expect(mediaSubmissionPollInterval({ status: 'ACCEPTED' })).toBe(30_000)
    expect(mediaSubmissionPollInterval({
      status: 'SUBMIT_UNKNOWN',
      updatedAt: '2026-09-20T10:00:00+08:00',
    }, Date.parse('2026-09-20T10:31:00+08:00'))).toBe(false)

    const pending = {
      status: 'SUBMIT_UNKNOWN' as const,
      updatedAt: '2026-09-20T10:00:00+08:00',
    }
    const pauseAt = Date.parse(pending.updatedAt) + 30 * 60_000 + 1
    expect(mediaSubmissionPollPauseAt(pending)).toBe(pauseAt)
    expect(mediaSubmissionPollingPaused(pending, pauseAt - 1)).toBe(false)
    expect(mediaSubmissionPollingPaused(pending, pauseAt)).toBe(true)
    expect(mediaSubmissionPollInterval(pending, pauseAt)).toBe(false)
    expect(mediaSubmissionPollPauseAt({ ...pending, status: 'PUBLISHED' })).toBeUndefined()
    expect(mediaSubmissionPollingPaused({ ...pending, status: 'PUBLISHED' }, pauseAt)).toBe(false)
  })

  it('使用冻结契约的资源、报价和投稿路径并保留字符串 ID', async () => {
    const resourcePage = { items: [resource], page: 1, size: 20, total: 1, hasNext: false }
    const submissionPage = { items: [submission], page: 1, size: 20, total: 1, hasNext: false }
    const fetchMock = stubFetch(
      ok(resourcePage),
      ok(resource),
      ok(draft, 201),
      ok(submissionPage),
      ok(submission),
    )

    await listMediaResources({ search: '行业', modelId: 'NEWS', page: 1, size: 20 })
    await getMediaResource(resourceId)
    await createMediaQuote(
      {
        articleId,
        articleSnapshotId: snapshotId,
        items: [{ mediaResourceId: resourceId, remark: '保持原排版' }],
      },
      'media-idempotency-key',
    )
    await listMediaSubmissions({ status: 'SUBMIT_UNKNOWN', taskId: submission.taskId, page: 1, size: 20 })
    const detail = await getMediaSubmission(submissionId)

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/media/resources?search=%E8%A1%8C%E4%B8%9A&modelId=NEWS&page=1&size=20',
      `/api/v1/media/resources/${resourceId}`,
      '/api/v1/media/quotes',
      `/api/v1/media/submissions?status=SUBMIT_UNKNOWN&taskId=${submission.taskId}&page=1&size=20`,
      `/api/v1/media/submissions/${submissionId}`,
    ])
    expect((fetchMock.mock.calls[2][1]?.headers as Headers).get('Idempotency-Key')).toBe(
      'media-idempotency-key',
    )
    expect(typeof detail.id).toBe('string')
  })

  it('复用通用 TaskDraft 生命周期并为相同输入复用一次报价', async () => {
    stubFetch(ok(draft))
    const createQuote = vi.fn().mockResolvedValue(draft)
    let recovery = {}
    const request = {
      articleId,
      articleSnapshotId: snapshotId,
      items: [{ mediaResourceId: resourceId, remark: null }],
    }
    const first = await prepareFormTaskDraft({
      recovery,
      request,
      onRecoveryChange: (next) => { recovery = next },
      createDraft: createQuote,
    })
    const second = await prepareFormTaskDraft({
      recovery: first.recovery,
      request,
      currentDraft: draft,
      onRecoveryChange: (next) => { recovery = next },
      createDraft: createQuote,
    })

    expect(createQuote).toHaveBeenCalledTimes(1)
    expect(second.reusedExistingDraft).toBe(true)
  })

  it('确认卡展示 Java 报价且价格变化或资源失效时禁止确认', () => {
    const validResource = toLiveMediaResource(resource, Date.parse('2026-09-09T10:00:00Z'))
    const normal = toMediaPaidTaskConfirmation(
      draft,
      { userId: 'owner', availablePoints: 999, frozenPoints: 0, version: 1 },
      snapshot,
      [validResource],
      false,
      false,
      true,
    )
    const stale = toMediaPaidTaskConfirmation(
      draft,
      { userId: 'owner', availablePoints: 999, frozenPoints: 0, version: 1 },
      snapshot,
      [validResource],
      false,
      true,
      true,
    )

    expect(normal?.pointsCost).toBe(120)
    expect(normal?.mediaSubmissionDetails?.targets[0]).toMatchObject({
      name: '行业媒体 A',
      saleAmountLabel: '¥120.00',
      chargedPoints: 120,
    })
    expect(normal?.confirmEnabled).toBe(true)
    expect(stale?.confirmEnabled).toBe(false)
    expect(stale?.warningText).toContain('媒体报价已变化')
    const paused = toMediaPaidTaskConfirmation(draft,
      { userId: 'owner', availablePoints: 999, frozenPoints: 0, version: 1 },
      snapshot, [validResource], false, false, false)
    expect(paused?.confirmEnabled).toBe(false)
    expect(paused?.warningText).toContain('投稿执行暂未开放')
  })

  it('停用资源不展示保留的旧价和未来报价期限，也不能被选择投稿', () => {
    const unavailable = toLiveMediaResource({ ...resource, status: 'INACTIVE' })
    expect(unavailable).toMatchObject({
      orderable: false,
      saleAmountLabel: '暂无可用报价',
      quoteExpiresAt: '—',
    })
    expect(unavailable.chargedPoints).toBeUndefined()

    render(
      <ArticleMediaSubmissionPanel
        {...panelProps({
          resources: [unavailable],
          selectedResource: unavailable,
          resourcePagination: { current: 1, pageSize: 20, total: 1 },
        })}
      />,
    )
    const row = screen.getByRole('row', { name: /行业媒体 A/ })
    expect(within(row).getByText('暂无可用报价')).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: /选\s*择/ })).toBeDisabled()
    expect(screen.getByText('该媒体当前不可下单')).toBeInTheDocument()
    expect(screen.queryByText('¥120.00')).not.toBeInTheDocument()
    expect(screen.queryByText('120')).not.toBeInTheDocument()
    expect(screen.queryByText(/2099/)).not.toBeInTheDocument()
  })

  it('未核验的图文渠道在推荐、报价及确认前被阻断，保留原图文稿件', async () => {
    expect(mediaArticleHasImages({ bodyHtml: '<p>文字</p>', assetCount: 0 })).toBe(false)
    expect(mediaArticleHasImages({ bodyHtml: '<p>文字</p>', assetCount: 1 })).toBe(true)
    expect(mediaArticleHasImages({ bodyHtml: '<IMG src="x">', assetCount: 0 })).toBe(true)
    expect(mediaArticleHasImages({ bodyHtml: '<div data-asset-id="123">图</div>', assetCount: 0 })).toBe(true)

    const imageSnapshot = { ...snapshot, assetCount: 1 }
    const validResource = toLiveMediaResource(resource, Date.parse('2026-09-09T10:00:00Z'))
    const confirmation = toMediaPaidTaskConfirmation(
      draft,
      { userId: 'owner', availablePoints: 999, frozenPoints: 0, version: 1 },
      imageSnapshot,
      [validResource],
      false,
      false,
    )
    expect(confirmation?.confirmEnabled).toBe(false)
    expect(confirmation?.warningText).toContain('所选渠道的图片规则尚未核验')

    const onPrepareQuote = vi.fn()
    const { unmount } = render(
      <ArticleMediaSubmissionPanel
        {...panelProps({
          snapshot: imageSnapshot,
          resources: [validResource],
          resourcePagination: { current: 1, pageSize: 20, total: 1 },
          onPrepareQuote,
        })}
      />,
    )
    expect(screen.getByText('请选择已核验的图文媒体')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: /选\s*择/ }))
    expect(screen.getByRole('button', { name: '获取最新报价' })).toBeDisabled()
    expect(onPrepareQuote).not.toHaveBeenCalled()
    unmount()

    const recommendations: ArticleMediaRecommendationSetView = {
      articleId,
      articleVersion: article.version,
      generatedAt: '2026-09-09T10:00:00Z',
      submissionEnabled: true,
      strategy: {
        goal: 'BALANCED', requiresLink: false, detectedCategories: [],
        candidateCount: 1, dataLimitations: [],
      },
      items: [{
        rank: 1, reasonCode: 'ACTIVE_RESOURCE_FALLBACK', reason: '可投稿',
        score: 60, evidence: [], resource: validResource,
      }],
    }
    const onConfirmSubmission = vi.fn()
    render(
      <ArticleMediaRecommendationCard
        recommendations={recommendations}
        imageUnsupported
        onConfirmSubmission={onConfirmSubmission}
        onRefresh={vi.fn()}
      />,
    )
    expect(screen.getByText('这篇文章含图，图文渠道尚未开放')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /确认投稿/ })).toBeDisabled()
    expect(onConfirmSubmission).not.toHaveBeenCalled()
  })

  it('含外链正文只能选择支持外链的媒体，推荐和手动报价均提前阻断不兼容资源', async () => {
    expect(mediaArticleHasLinks('<p>普通正文</p>')).toBe(false)
    expect(mediaArticleHasLinks('<p>访问 https://example.com</p>')).toBe(true)
    expect(mediaArticleHasLinks('<A HREF="/local">文章</A>')).toBe(true)
    expect(mediaResourceSupportsLinks({ linkPolicy: '不可带网址' })).toBe(false)
    expect(mediaResourceSupportsLinks({ linkPolicy: '可带网址' })).toBe(true)
    expect(mediaResourceSupportsLinks({ supportsLink: false, linkPolicy: '可带网址' })).toBe(false)
    expect(mediaResourceSupportsLinks({ supportsLink: true, linkPolicy: '不可带网址' })).toBe(true)
    expect(mediaResourceSupportsLinks({})).toBe(false)

    const linkedSnapshot = { ...snapshot, bodyHtml: '<p>访问 https://example.com</p>' }
    const noLink = toLiveMediaResource(resource)
    const supportsLink = toLiveMediaResource({
      ...resource,
      id: '91000000000000011',
      name: '可带链媒体',
      capabilities: { supportsLink: true },
    })
    const quote = toMediaPaidTaskConfirmation(
      draft,
      { userId: 'owner', availablePoints: 999, frozenPoints: 0, version: 1 },
      linkedSnapshot,
      [noLink],
      false,
      false,
    )
    expect(quote?.confirmEnabled).toBe(false)
    expect(quote?.warningText).toContain('所选媒体未标记支持外链')

    const onPrepareQuote = vi.fn()
    const { unmount } = render(
      <ArticleMediaSubmissionPanel
        {...panelProps({
          snapshot: linkedSnapshot,
          resources: [noLink, supportsLink],
          resourcePagination: { current: 1, pageSize: 20, total: 2 },
          onPrepareQuote,
        })}
      />,
    )
    expect(screen.getByText('当前快照含外链')).toBeInTheDocument()
    const noLinkRow = screen.getByRole('row', { name: /行业媒体 A/ })
    expect(within(noLinkRow).getByRole('button', { name: /选\s*择/ })).toBeDisabled()
    const supportsLinkRow = screen.getByRole('row', { name: /可带链媒体/ })
    await userEvent.setup().click(within(supportsLinkRow).getByRole('button', { name: /选\s*择/ }))
    await userEvent.setup().click(screen.getByRole('button', { name: '获取最新报价' }))
    expect(onPrepareQuote).toHaveBeenCalledWith({
      mediaResourceIds: [supportsLink.id],
      remark: '',
    })
    unmount()

    const recommendations: ArticleMediaRecommendationSetView = {
      articleId, articleVersion: article.version, generatedAt: '2026-09-09T10:00:00Z',
      submissionEnabled: true,
      strategy: { goal: 'BALANCED', requiresLink: false, detectedCategories: [], candidateCount: 2, dataLimitations: [] },
      items: [
        { rank: 1, reasonCode: 'ACTIVE_RESOURCE_FALLBACK', reason: '可投稿', score: 60, evidence: [], resource: noLink },
        { rank: 2, reasonCode: 'ACTIVE_RESOURCE_FALLBACK', reason: '可投稿', score: 50, evidence: [], resource: supportsLink },
      ],
    }
    const onConfirmSubmission = vi.fn()
    render(
      <ArticleMediaRecommendationCard
        recommendations={recommendations}
        hasExternalLinks
        onConfirmSubmission={onConfirmSubmission}
        onRefresh={vi.fn()}
      />,
    )
    expect(screen.getByText('当前正文含外链')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: '选择投稿媒体 行业媒体 A' })).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: '选择投稿媒体 可带链媒体' })).toBeChecked()
    await userEvent.setup().click(screen.getByRole('button', { name: /确认投稿/ }))
    expect(onConfirmSubmission).toHaveBeenCalledWith({
      articleId,
      articleVersion: article.version,
      mediaResourceIds: [supportsLink.id],
      expectedPoints: supportsLink.chargedPoints,
    })
  })

  it('不可变投稿快照未恢复时不允许确认，并显示当前文章与快照版本差异', () => {
    const validResource = toLiveMediaResource(resource, Date.parse('2026-09-09T10:00:00Z'))
    const missingSnapshot = toMediaPaidTaskConfirmation(
      draft,
      { userId: 'owner', availablePoints: 999, frozenPoints: 0, version: 1 },
      undefined,
      [validResource],
      false,
      false,
    )

    expect(missingSnapshot?.confirmEnabled).toBe(false)
    expect(missingSnapshot?.warningText).toContain('不可变投稿快照尚未恢复')

    render(
      <ArticleMediaSubmissionPanel
        {...panelProps({ article: { ...article, version: 4 }, snapshot })}
      />,
    )
    expect(screen.getByText('当前文章版本与投稿快照不同')).toBeInTheDocument()
    expect(screen.getByText('V4')).toBeInTheDocument()
    expect(screen.getByText(/V3 的不可变快照为准/)).toBeInTheDocument()
  })

  it('资源空态不伪造数据，选择媒体后只请求外部报价回调', async () => {
    const onPrepareQuote = vi.fn()
    const { rerender } = render(
      <ArticleMediaSubmissionPanel {...panelProps({ onPrepareQuote })} />,
    )
    expect(screen.getByText('暂无符合条件的媒体资源')).toBeInTheDocument()

    rerender(
      <ArticleMediaSubmissionPanel
        {...panelProps({
          resources: [toLiveMediaResource(resource)],
          resourcePagination: { current: 1, pageSize: 20, total: 1 },
          onPrepareQuote,
        })}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /选\s*择/ }))
    await user.type(screen.getByRole('textbox', { name: '投稿备注' }), '保留格式')
    await user.click(screen.getByRole('button', { name: '获取最新报价' }))
    expect(onPrepareQuote).toHaveBeenCalledWith({
      mediaResourceIds: [resourceId],
      remark: '保留格式',
    })
  })

  it('SUBMIT_UNKNOWN 明确禁止重复下单，且不会伪造发布链接', () => {
    const detail = toLiveMediaSubmissionDetail({
      ...submission,
      publishedUrl: null,
      publishedUrls: [],
    })
    render(
      <ArticleMediaSubmissionPanel
        {...panelProps({
          selectedSubmission: detail,
          submissionDetailOpen: true,
        })}
      />,
    )
    expect(screen.getByText('正在核对，系统不会重复下单')).toBeInTheDocument()
    expect(screen.queryByLabelText('发布回链')).not.toBeInTheDocument()
  })

  it('自动刷新暂停后提示仍待处理，并只请求刷新当前投稿状态', async () => {
    const onRefreshSubmission = vi.fn()
    const pending = toLiveMediaSubmissionDetail({
      ...submission,
      publishedUrl: null,
      publishedUrls: [],
    })
    const { rerender } = render(
      <ArticleMediaSubmissionPanel
        {...panelProps({
          selectedSubmission: pending,
          submissionDetailOpen: true,
          submissionPollingPaused: true,
          onRefreshSubmission,
        })}
      />,
    )

    expect(screen.getByText('自动刷新已暂停，投稿仍待处理')).toBeInTheDocument()
    expect(screen.getByText(/刷新不会再次下单/)).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: '刷新此投稿状态' }))
    expect(onRefreshSubmission).toHaveBeenCalledExactlyOnceWith(submissionId)

    rerender(
      <ArticleMediaSubmissionPanel
        {...panelProps({
          selectedSubmission: toLiveMediaSubmissionDetail({
            ...submission,
            status: 'PUBLISHED',
          }),
          submissionDetailOpen: true,
          submissionPollingPaused: false,
          onRefreshSubmission,
        })}
      />,
    )
    expect(screen.queryByText('自动刷新已暂停，投稿仍待处理')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '刷新此投稿状态' })).not.toBeInTheDocument()
  })

  it('投稿列表在正常与长时间等待时都能手动刷新，不触发再次下单', async () => {
    const onRefreshSubmissions = vi.fn()
    render(
      <ArticleMediaSubmissionPanel
        {...panelProps({
          submissions: [toLiveMediaSubmissionDetail(submission)],
          onRefreshSubmissions,
        })}
      />,
    )
    await userEvent.setup().click(screen.getByRole('button', { name: '刷新投稿状态' }))
    expect(onRefreshSubmissions).toHaveBeenCalledTimes(1)
  })

  it('展示全部安全 HTTP(S) 回链并保留查询参数，拒绝危险协议和凭据', () => {
    const detail = toLiveMediaSubmissionDetail({
      ...submission,
      status: 'PUBLISHED',
      publishedUrls: [
        'https://example.com/published/article',
        'http://example.org/article?source=media&ref=one',
        'javascript:alert(1)',
        'https://user:password@example.org/private',
      ],
    })
    expect(detail.publishedUrls).toEqual([
      'https://example.com/published/article',
      'http://example.org/article?source=media&ref=one',
    ])
    render(
      <ArticleMediaSubmissionPanel
        {...panelProps({ selectedSubmission: detail, submissionDetailOpen: true })}
      />,
    )
    const links = screen.getAllByRole('link', { name: /打开发布页面/ })
    expect(links).toHaveLength(2)
    expect(links[1]).toHaveAttribute('href', 'http://example.org/article?source=media&ref=one')
    for (const link of links) {
      expect(link).toHaveAttribute('target', '_blank')
      expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    }
  })

  it('投稿已发布但上游没有可访问链接时明确说明完成状态', () => {
    const detail = toLiveMediaSubmissionDetail({
      ...submission,
      status: 'PUBLISHED',
      publishedUrl: null,
      publishedUrls: [],
    })
    render(
      <ArticleMediaSubmissionPanel
        {...panelProps({ selectedSubmission: detail, submissionDetailOpen: true })}
      />,
    )
    expect(screen.getByText('已发布，上游暂未提供可访问链接')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /打开发布页面/ })).not.toBeInTheDocument()
  })

  it('恢复存储只持久化幂等标识，不保存令牌、价格快照或文章内容', () => {
    storeMediaTaskRecovery('owner-media', {
      draftAttempt: {
        fingerprint: 'fingerprint',
        idempotencyKey: 'idempotency-key',
        draftId: draft.id,
      },
      confirmationAttempt: {
        draftId: draft.id,
        inputHash: draft.inputHash,
        pricingVersionId: draft.pricingVersionId,
        idempotencyKey: 'confirm-idempotency-key',
      },
    })
    const raw = window.localStorage.getItem(
      getMediaTaskRecoveryStorageKey('owner-media'),
    )
    expect(raw).toContain('idempotency-key')
    expect(raw).not.toContain('opaque-confirmation-token')
    expect(raw).not.toContain('saleAmountCents')
    expect(raw).not.toContain('正式文章')
  })
})

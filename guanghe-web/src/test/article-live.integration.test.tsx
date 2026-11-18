import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createArticle,
  createArticleSubmissionSnapshot,
  getArticleSubmissionSnapshot,
  replaceArticleAssets,
  replaceArticleOutline,
  updateArticle,
} from '../api/articleApi'
import { ApiError } from '../api/apiError'
import type { ArticleWorkspaceRecordView } from '../features/articles'
import {
  SafeArticleContent,
  ArticleSubmissionWorkspace,
  saveArticleEditorRecovery,
  articleEditorRecoveryKey,
  toArticleAssetOption,
  toArticleTaskDraftRequest,
} from '../features/articles'
import { storeAuthToken } from '../auth/tokenStorage'
import { articleBlockIds } from '../features/articles'

type FetchMock = ReturnType<typeof vi.fn<typeof fetch>>

function successResponse(data: unknown): Response {
  return new Response(
    JSON.stringify({
      code: 'OK',
      message: 'success',
      data,
      traceId: 'a'.repeat(32),
    }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  )
}

function stubFetch(...responses: Response[]): FetchMock {
  const fetchMock = vi.fn<typeof fetch>()
  responses.forEach((response) => fetchMock.mockResolvedValueOnce(response))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const article: ArticleWorkspaceRecordView = {
  id: '90440000000000001',
  spaceId: '90440000000000002',
  productId: '90440000000000003',
  title: '正式文章',
  bodyHtml: '<section data-article-block-id="section-1"><h2>第一节</h2><p>正文</p></section>',
  bodyText: '正文',
  status: 'DRAFT',
  statusLabel: '草稿',
  version: 5,
  updatedAt: '2026-09-09 10:00',
  outline: [
    { key: 'section-1', level: 2, heading: '第一节', brief: '说明' },
  ],
  assets: [],
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  storeAuthToken({
    accessToken: 'article-browser-token',
    expiresAt: '2099-01-01T00:00:00Z',
  })
})

describe('ARTICLE Browser API 与安全展示', () => {
  it('局部改写只列出服务端正文 section 的真实 block key', () => {
    expect(articleBlockIds(article.bodyHtml)).toEqual(['section-1'])
    expect(articleBlockIds('<p data-block-id="legacy">旧格式</p>')).toEqual([])
  })
  it('使用生成契约路径、版本头和整体替换请求', async () => {
    const responseArticle = { id: article.id, version: 6 }
    const snapshot = { id: '90440000000000004', articleId: article.id }
    const fetchMock = stubFetch(
      successResponse(responseArticle),
      successResponse(responseArticle),
      successResponse(responseArticle),
      successResponse(responseArticle),
      successResponse(snapshot),
      successResponse(snapshot),
    )

    await createArticle({ spaceId: article.spaceId, productId: article.productId })
    await updateArticle(article.id, { version: 5, title: '新标题' })
    await replaceArticleOutline(article.id, {
      version: 6,
      items: [{ key: 'section-1', level: 2, heading: '第一节' }],
    })
    await replaceArticleAssets(article.id, {
      version: 7,
      items: [
        {
          assetId: '90440000000000005',
          positionKey: 'article-end-1',
          sortOrder: 1,
        },
      ],
    })
    await createArticleSubmissionSnapshot(article.id, { articleVersion: 8 })
    await getArticleSubmissionSnapshot(
      article.id,
      '90440000000000004',
    )

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/articles',
      `/api/v1/articles/${article.id}`,
      `/api/v1/articles/${article.id}/outline`,
      `/api/v1/articles/${article.id}/assets`,
      `/api/v1/articles/${article.id}/submission-snapshots`,
      `/api/v1/articles/${article.id}/submission-snapshots/90440000000000004`,
    ])
    const updateHeaders = fetchMock.mock.calls[1][1]?.headers as Headers
    expect(updateHeaders.get('If-Match')).toBe('"5"')
    expect(fetchMock.mock.calls.every(([url]) =>
      typeof url === 'string' && url.startsWith('/api/v1/'),
    )).toBe(true)
  })

  it('409 版本冲突保留真实 message 与 traceId', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: 'RESOURCE_VERSION_CONFLICT',
            message: '文章版本已更新',
            details: { latestVersion: 7 },
            traceId: 'article-conflict-trace',
          }),
          { status: 409, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    await expect(
      updateArticle(article.id, { version: 5, title: '本地未提交标题' }),
    ).rejects.toMatchObject({
      status: 409,
      message: '文章版本已更新',
      traceId: 'article-conflict-trace',
    } satisfies Partial<ApiError>)
  })

  it.each([
    ['TITLE', null, 0, null, 0],
    ['OUTLINE', '正式文章', 0, null, 0],
    ['BODY', '正式文章', 1, null, 0],
    ['REWRITE', '正式文章', 1, '精简正文', 1],
  ] as const)(
    '%s 阶段映射为真实 ARTICLE 参数且保持 Snowflake 字符串 ID',
    (mode, selectedTitle, outlineCount, rewriteInstruction, blockCount) => {
      const request = toArticleTaskDraftRequest('90440000000000006', article, {
        mode,
        brief: '围绕真实商品生成内容',
        tone: 'PROFESSIONAL',
        targetWordCount: 1200,
        rewriteInstruction: rewriteInstruction ?? '',
        rewriteBlockIds: rewriteInstruction ? ['block-1'] : [],
        sourceAssetIds: ['90440000000000005'],
      })
      expect(request.taskType).toBe('ARTICLE')
      expect(request.spaceId).toBe(article.spaceId)
      expect(request.parameters).toMatchObject({
        mode,
        articleId: article.id,
        articleVersion: 5,
        selectedTitle,
        rewriteInstruction,
      })
      if ('confirmedOutline' in request.parameters) {
        expect(request.parameters.confirmedOutline).toHaveLength(outlineCount)
        expect(request.parameters.rewriteBlockIds).toHaveLength(blockCount)
      }
    },
  )

  it('资产候选保持 Java READY/IMAGE 元数据，不制造签名 URL', () => {
    const option = toArticleAssetOption({
      id: '90440000000000005',
      assetType: 'IMAGE',
      sourceType: 'GENERATED',
      status: 'READY',
      safetyStatus: 'APPROVED',
      name: '正文配图',
      spaceId: article.spaceId,
      primaryProductId: article.productId,
      contentType: 'image/png',
      sizeBytes: 1024,
      sha256: 'f'.repeat(64),
      width: 1024,
      height: 1024,
      sourceTaskId: null,
      createdAt: '2026-09-09T10:00:00Z',
      updatedAt: '2026-09-09T10:00:00Z',
    })
    expect(option).toEqual({
      id: '90440000000000005',
      name: '正文配图',
      status: 'READY',
      contentType: 'image/png',
      width: 1024,
      height: 1024,
    })
    expect(JSON.stringify(option)).not.toMatch(/https?:|data:|blob:/)
  })

  it('文章正文只渲染允许的语义节点，不执行脚本或加载外部图片', () => {
    render(
      <SafeArticleContent
        html={'<h2>安全标题</h2><p>正文<strong>重点</strong></p><script>window.evil=1</script><img src="https://evil.example/a.png"><a href="javascript:alert(1)">链接文字</a>'}
      />,
    )
    expect(screen.getByRole('heading', { name: '安全标题' })).toBeInTheDocument()
    expect(screen.getByText('重点')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(document.querySelector('script')).toBeNull()
  })

  it('真实工作区展示服务端文章并保持媒体投稿边界禁用', async () => {
    const user = userEvent.setup()
    const onCreateSnapshot = vi.fn()
    const onSaveArticle = vi.fn()
    const noop = vi.fn()
    render(
      <ArticleSubmissionWorkspace
        businessSpaces={[]}
        products={[]}
        articles={[]}
        mediaResources={[]}
        submissions={[]}
        live={{
          businessSpaces: [{ label: '经营空间', value: article.spaceId }],
          products: [],
          articles: [
            {
              id: article.id,
              title: article.title,
              status: 'DRAFT',
              statusLabel: '草稿',
              versionLabel: 'V5',
              updatedAt: article.updatedAt,
            },
          ],
          article,
          assets: [],
          articleFilter: { keyword: '' },
          articlePagination: { current: 1, pageSize: 20, total: 1 },
          onSelectArticle: noop,
          onBusinessSpaceChange: noop,
          onArticleFilterChange: noop,
          onArticlePageChange: noop,
          onCreateArticle: noop,
          onSaveArticle,
          onSaveOutline: noop,
          onSaveAssets: noop,
          onDeleteArticle: noop,
          onPrepareTask: noop,
          onConfirmTask: noop,
          onEditTask: noop,
          onUseTitleCandidate: noop,
          onUseGeneratedOutline: noop,
          onCreateSnapshot,
          onReloadArticle: noop,
          onViewTask: noop,
          onTaskAction: noop,
          onOpenRecharge: noop,
        }}
      />,
    )

    expect(screen.getByText('服务端版本 V5 · 2026-09-09 10:00')).toBeInTheDocument()
    await user.clear(screen.getByRole('textbox', { name: '文章标题' }))
    await user.type(screen.getByRole('textbox', { name: '文章标题' }), '只改标题')
    await user.click(screen.getByRole('button', { name: /保存当前版本/ }))
    expect(onSaveArticle).toHaveBeenCalledWith({
      title: '只改标题',
      bodyHtml: article.bodyHtml,
    })
    expect(screen.getByRole('button', { name: /保存当前版本/ })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: '媒体投稿' }))
    expect(screen.getByText('当前只开放不可变文章快照')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '确认投稿' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '创建不可变投稿快照' }))
    expect(onCreateSnapshot).toHaveBeenCalledTimes(1)
  })

  it('刷新后只提示同一服务端版本的本地标题/正文草稿，并可即时预览恢复内容', async () => {
    const user = userEvent.setup()
    const noop = vi.fn()
    saveArticleEditorRecovery(article.id, {
      baseVersion: article.version,
      title: '恢复后的标题',
      bodyText: '恢复后的正文',
    })

    render(
      <ArticleSubmissionWorkspace
        businessSpaces={[]}
        products={[]}
        articles={[]}
        mediaResources={[]}
        submissions={[]}
        live={{
          businessSpaces: [{ label: '经营空间', value: article.spaceId }],
          products: [],
          articles: [],
          article,
          assets: [],
          articleFilter: { keyword: '' },
          articlePagination: { current: 1, pageSize: 20, total: 0 },
          onSelectArticle: noop,
          onBusinessSpaceChange: noop,
          onArticleFilterChange: noop,
          onArticlePageChange: noop,
          onCreateArticle: noop,
          onSaveArticle: noop,
          onSaveOutline: noop,
          onSaveAssets: noop,
          onDeleteArticle: noop,
          onPrepareTask: noop,
          onConfirmTask: noop,
          onEditTask: noop,
          onUseTitleCandidate: noop,
          onUseGeneratedOutline: noop,
          onCreateSnapshot: noop,
          onReloadArticle: noop,
          onViewTask: noop,
          onTaskAction: noop,
          onOpenRecharge: noop,
        }}
      />,
    )

    expect(screen.getByText('发现未保存的标题和正文')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '恢复编辑' }))
    expect(screen.getByRole('textbox', { name: '文章标题' })).toHaveValue(
      '恢复后的标题',
    )
    expect(screen.getByRole('textbox', { name: '文章正文' })).toHaveValue(
      '恢复后的正文',
    )
    expect(
      within(screen.getByLabelText('文章安全预览')).getByText('恢复后的正文'),
    ).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /保存当前版本/ }))
    expect(window.localStorage.getItem(articleEditorRecoveryKey(article.id))).not.toBeNull()
  })

  it('恢复整份编辑状态时同时回填创作方案和配图顺序', async () => {
    const user = userEvent.setup()
    const noop = vi.fn()
    const articleWithEdits: ArticleWorkspaceRecordView = {
      ...article,
      outline: [{ key: 'section-1', level: 2, heading: '服务端小节' }],
      assets: [
        {
          assetId: '90440000000000005',
          positionKey: 'article-end-1',
          sortOrder: 1,
        },
      ],
    }
    saveArticleEditorRecovery(articleWithEdits.id, {
      baseVersion: articleWithEdits.version,
      title: articleWithEdits.title,
      bodyText: articleWithEdits.bodyText,
      outline: [{ key: 'section-1', level: 3, heading: '恢复后的方案' }],
      assetIds: ['90440000000000006'],
    })

    render(
      <ArticleSubmissionWorkspace
        businessSpaces={[]}
        products={[]}
        articles={[]}
        mediaResources={[]}
        submissions={[]}
        live={{
          businessSpaces: [{ label: '经营空间', value: article.spaceId }],
          products: [],
          articles: [],
          article: articleWithEdits,
          assets: [
            {
              id: '90440000000000005',
              name: '服务端配图',
              status: 'READY',
              contentType: 'image/png',
            },
            {
              id: '90440000000000006',
              name: '恢复配图',
              status: 'READY',
              contentType: 'image/png',
            },
          ],
          articleFilter: { keyword: '' },
          articlePagination: { current: 1, pageSize: 20, total: 0 },
          onSelectArticle: noop,
          onBusinessSpaceChange: noop,
          onArticleFilterChange: noop,
          onArticlePageChange: noop,
          onCreateArticle: noop,
          onSaveArticle: noop,
          onSaveOutline: noop,
          onSaveAssets: noop,
          onDeleteArticle: noop,
          onPrepareTask: noop,
          onConfirmTask: noop,
          onEditTask: noop,
          onUseTitleCandidate: noop,
          onUseGeneratedOutline: noop,
          onCreateSnapshot: noop,
          onReloadArticle: noop,
          onViewTask: noop,
          onTaskAction: noop,
          onOpenRecharge: noop,
        }}
      />,
    )

    expect(screen.getByText('发现未保存的创作方案、配图')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '恢复编辑' }))
    expect(screen.getByRole('textbox', { name: '创作方案 1 标题' })).toHaveValue(
      '恢复后的方案',
    )
    expect(screen.getByRole('button', { name: /保存当前版本/ })).toBeDisabled()
    expect(screen.getAllByText('恢复配图')).not.toHaveLength(0)
  })
})

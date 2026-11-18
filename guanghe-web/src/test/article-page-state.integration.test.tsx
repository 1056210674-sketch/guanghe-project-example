import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation, useNavigate } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { userFixture, testAccessToken, testExpiresAt } from './fixtures/auth'
import { renderApp } from './renderApp'
import { readArticleTaskRecovery, storeArticleTaskRecovery } from '../features/articles'
import type { TaskDraft } from '../api/conversationApi'

const now = '2026-10-05T00:00:00Z'
const page = (items: unknown[] = []) => ({ items, page: 1, size: 100, total: items.length, hasNext: false })
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', message: 'success', data, traceId: 'a'.repeat(32) }), { headers: { 'Content-Type': 'application/json' } })
const spaces = [
  { id: '21', name: '空间 A', brandName: '品牌 A', productCount: 1 },
  { id: '22', name: '空间 B', brandName: '品牌 B', productCount: 1 },
]
const article = { id: '31', spaceId: '21', productId: null, title: '已有文章', bodyHtml: '', bodyText: '', status: 'DRAFT', version: 1, outline: [], assets: [], createdAt: now, updatedAt: now }

function RouteObserver() {
  const location = useLocation()
  const navigate = useNavigate()
  return <>
    <output aria-label="路由身份">{location.key}</output>
    <button onClick={() => navigate('/app/articles?article=31')}>打开已有文章</button>
    <button onClick={() => navigate('/app/articles?view=creation')}>更新查询参数</button>
    <button onClick={() => navigate(-1)}>返回上页</button>
  </>
}

async function choose(user: ReturnType<typeof userEvent.setup>, label: string, option: string) {
  await user.click(screen.getByLabelText(label))
  const matches = await screen.findAllByText(option)
  await user.click(matches.at(-1)!)
}

function installApi(productA?: Promise<Response>, draft?: unknown) {
  const requests: Array<{ url: string; body?: string }> = []
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    requests.push({ url, body: typeof init?.body === 'string' ? init.body : undefined })
    if (url === '/api/v1/articles' && init?.method === 'POST') {
      return ok({ ...article, ...JSON.parse(String(init.body)), id: '32' })
    }
    if (url === '/api/v1/articles/31' || url === '/api/v1/articles/32') return ok(article)
    if (url.startsWith('/api/v1/articles?')) return ok(page())
    if (url.startsWith('/api/v1/spaces?')) return ok(page(spaces))
    if (url.includes('/spaces/21/products?')) return productA ?? ok(page([{ id: '51', spaceId: '21', name: '商品 A' }]))
    if (url.includes('/spaces/22/products?')) return ok(page([{ id: '52', spaceId: '22', name: '商品 B' }]))
    if (url.startsWith('/api/v1/assets?') || url.startsWith('/api/v1/media/resources?') || url.startsWith('/api/v1/media/submissions?')) return ok(page())
    if (url === '/api/v1/media/resources/41') return ok({ id: '41', name: '媒体', status: 'ACTIVE', saleAmountCents: 0 })
    if (url.includes('/notifications/unread-count')) return ok({ unreadCount: 0 })
    if (url === '/api/v1/billing/point-account') return ok({ userId: userFixture.id, availablePoints: 500, frozenPoints: 0, version: 1 })
    if (url === '/api/v1/task-drafts/61' && draft) return ok(draft)
    throw new Error(`Unexpected Browser API request: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, requests }
}

beforeEach(async () => {
  await import('../pages/articles/ArticleSubmissionPage')
  vi.unstubAllGlobals()
  localStorage.clear()
  sessionStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('文章页面新建状态与路由恢复', () => {
  it('主动新建不会被旧报价草稿反选原文章，旧恢复记录不丢失', async () => {
    const user = userEvent.setup()
    const recovery = { conversationId: '71', draftAttempt: { draftId: '61', fingerprint: 'old-article', idempotencyKey: 'old-attempt' } }
    storeArticleTaskRecovery(userFixture.id, recovery)
    expect(readArticleTaskRecovery(userFixture.id)).toEqual(recovery)
    const draft: TaskDraft = { id: '61', draftSource: 'FORM', taskType: 'ARTICLE', parameterSchemaVersion: 2, status: 'WAITING_CONFIRMATION',
      spaceId: '21', productId: null, sourceMode: null, referenceAssetIds: [], parameters: { articleId: '31', articleVersion: 1, mode: 'TITLE', tone: 'PROFESSIONAL', targetLength: 'MEDIUM', brief: '',
        selectedTitle: null, confirmedOutline: [], confirmedPlan: null, rewriteInstruction: null, rewriteBlockIds: [], targetPlatform: null, sourceAssetIds: [], geoReportId: null },
      quotedPoints: 1, pricingVersionId: '81', inputHash: 'hash', confirmationToken: 'token', warnings: [], expiresAt: '2099-01-01T00:00:00Z', createdAt: now }
    const api = installApi(undefined, draft)
    renderApp(['/app/articles?article=31'], <RouteObserver />)
    await waitFor(() => expect(api.requests.map(({ url }) => url).join('\n')).toContain('/api/v1/articles/31'))
    await screen.findByLabelText('文章标题', {}, { timeout: 5000 })
    await user.click(screen.getByRole('button', { name: /新\s*建\s*文\s*章/ }))
    fireEvent.change(screen.getByLabelText('新文章标题'), { target: { value: '另一篇文章' } })
    await user.click(screen.getByRole('button', { name: '更新查询参数' }))
    expect(screen.getByLabelText('新文章标题')).toHaveValue('另一篇文章')
    expect(screen.queryByLabelText('文章标题')).not.toBeInTheDocument()
    expect(readArticleTaskRecovery(userFixture.id)).toEqual(recovery)
  })
  it('标题先填、慢商品请求及查询参数变化保留表单，同值操作不换路由身份', async () => {
    const user = userEvent.setup()
    let finishProducts!: (response: Response) => void
    const api = installApi(new Promise<Response>((resolve) => { finishProducts = resolve }))
    renderApp(['/app/articles'], <RouteObserver />)
    const title = await screen.findByLabelText('新文章标题')
    fireEvent.change(title, { target: { value: '办公室咖啡怎么选' } })
    await choose(user, '经营空间', '空间 A')
    expect(title).toHaveValue('办公室咖啡怎么选')
    const key = screen.getByLabelText('路由身份').textContent
    await choose(user, '经营空间', '空间 A')
    expect(screen.getByLabelText('路由身份')).toHaveTextContent(key!)
    await user.click(screen.getByRole('button', { name: '更新查询参数' }))
    expect(screen.getByLabelText('新文章标题')).toHaveValue('办公室咖啡怎么选')
    await act(async () => { finishProducts(ok(page([{ id: '51', spaceId: '21', name: '商品 A' }]))) })
    await choose(user, '商品', '商品 A')
    await user.click(screen.getByRole('button', { name: '创建空文章' }))
    await waitFor(() => expect(api.requests.filter(({ url, body }) => url === '/api/v1/articles' && body)).toHaveLength(1))
    expect(JSON.parse(api.requests.find(({ url, body }) => url === '/api/v1/articles' && body)!.body!)).toEqual({ spaceId: '21', productId: '51', title: '办公室咖啡怎么选' })
  })

  it('A 迟返回不覆盖 B；已有文章隐藏新建栏，新建清标题并沿用空间，换账号清输入', async () => {
    const user = userEvent.setup()
    let finishA!: (response: Response) => void
    installApi(new Promise<Response>((resolve) => { finishA = resolve }))
    renderApp(['/app/articles'], <RouteObserver />)
    fireEvent.change(await screen.findByLabelText('新文章标题'), { target: { value: '保留到新建前' } })
    await choose(user, '经营空间', '空间 A')
    await choose(user, '经营空间', '空间 B')
    await act(async () => { finishA(ok(page([{ id: '51', spaceId: '21', name: '商品 A' }]))) })
    await choose(user, '商品', '商品 B')
    expect(screen.queryByText('商品 A')).not.toBeInTheDocument()
    expect(screen.getByLabelText('新文章标题')).toHaveValue('保留到新建前')
    await user.click(screen.getByRole('button', { name: '打开已有文章' }))
    await screen.findByLabelText('文章标题')
    expect(screen.queryByLabelText('新文章标题')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /新\s*建\s*文\s*章/ }))
    expect(screen.getByLabelText('新文章标题')).toHaveValue('')
    expect(screen.getByText('空间 A')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('新文章标题'), { target: { value: '账号 A 的输入' } })
    await act(async () => { useAuthStore.setState({ user: { ...userFixture, id: '1009' } }) })
    expect(screen.getByLabelText('新文章标题')).toHaveValue('')
    expect(screen.queryByText('账号 A 的输入')).not.toBeInTheDocument()
  })
})

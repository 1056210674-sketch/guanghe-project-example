import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  getAdminMediaCallback,
  getAdminMediaOverview,
  getAdminMediaResource,
  getAdminMediaSubmission,
  listAdminMediaCallbacks,
  listAdminMediaResources,
  listAdminMediaSubmissions,
  listAdminMediaSyncStates,
  type AdminMediaCallback,
  type AdminMediaResource,
  type AdminMediaSubmission,
  type AdminMediaSubmissionStatus,
  type AdminMediaSubmissionSummary,
  type AdminMediaSyncState,
} from '../api/adminMediaApi'
import { AdminMediaWorkspace } from '../features/admin-publishing/AdminMediaWorkspace'
import { AdminInvoiceWorkspace } from '../features/admin-publishing/AdminInvoiceWorkspace'
import {
  MEDIA_CALLBACK_STATUS_META,
  MEDIA_RESOURCE_STATUS_META,
  MEDIA_SUBMISSION_STATE_META,
  MEDIA_SUBMISSION_STATUS_META,
  toAdminMediaResourceQuery,
  toAdminMediaSubmissionQuery,
  displayCount,
  type InvoiceApplicationView,
} from '../features/admin-publishing/types'
import { storeAuthToken, clearStoredAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import {
  adminFixture,
  apiErrorResponse,
  jsonResponse,
  testAccessToken,
  testExpiresAt,
  testTraceId,
  userFixture,
} from './fixtures/auth'
import { renderApp } from './renderApp'

function resource(overrides: Partial<AdminMediaResource> = {}): AdminMediaResource {
  return {
    id: '7001',
    providerCode: 'mcapi',
    upstreamModelId: 'model_alpha',
    upstreamResourceId: 'res-1001',
    name: '媒体甲',
    status: 'ACTIVE',
    resourceType: '图文',
    channelCategory: '综合资讯',
    region: '全国',
    estimatedDays: 1,
    capabilities: { max_images: 8, allow_images: true },
    upstreamPrice: '368.0000',
    markupRate: '0.3500',
    referenceSaleAmountCents: 49700,
    referenceChargedPoints: 49700,
    lastSeenAt: '2026-09-07T09:42:18Z',
    syncedAt: '2026-09-07T09:40:00Z',
    version: 7,
    ...overrides,
  }
}

function submission(
  status: AdminMediaSubmissionStatus,
  overrides: Partial<AdminMediaSubmissionSummary> = {},
): AdminMediaSubmissionSummary {
  return {
    id: `submission-${status}`,
    batchId: '8001',
    taskId: '9001',
    userId: '6001',
    mediaResourceId: '7001',
    mediaName: '媒体甲',
    providerCode: 'mcapi',
    upstreamModelId: 'model_alpha',
    upstreamResourceId: 'res-1001',
    customerOrderId: 'ORDER-1',
    upstreamOrderId: null,
    upstreamOrderNo: null,
    upstreamStatus: 'UNKNOWN',
    status,
    submissionState: 'UNKNOWN',
    publishedUrl: null,
    publishedUrls: [],
    stableErrorCode: null,
    upstreamPrice: '368.0000',
    markupRate: '0.3500',
    saleAmountCents: 49700,
    chargedPoints: 49700,
    remark: null,
    submitAttemptedAt: null,
    nextPollAt: null,
    publishedAt: null,
    rejectedAt: null,
    createdAt: '2026-09-07T09:48:15Z',
    updatedAt: '2026-09-07T09:49:02Z',
    ...overrides,
  }
}

const callback: AdminMediaCallback = {
  id: '9011',
  providerCode: 'mcapi',
  customerOrderId: 'ORDER-1',
  upstreamModelId: 'model_alpha',
  upstreamResourceId: 'res-1001',
  eventType: 'order.status_changed',
  status: 'RECEIVED',
  attempts: 2,
  stableErrorCode: 'STATUS_CONFLICT',
  receivedAt: '2026-09-07T09:45:00Z',
  processedAt: null,
  updatedAt: '2026-09-07T09:45:00Z',
}

const syncState: AdminMediaSyncState = {
  providerCode: 'mcapi',
  upstreamModelId: 'model_alpha',
  status: 'SUCCEEDED',
  lastAttemptedAt: '2026-09-07T09:40:00Z',
  lastSucceededAt: '2026-09-07T09:42:18Z',
  lastErrorCode: null,
  lastResourceCount: 6,
  version: 7,
  updatedAt: '2026-09-07T09:42:18Z',
}

const invoice: InvoiceApplicationView = { id: 'INV-1', account: '132****1234', orderId: 'RECHARGE-1', amountCents: 29900, title: '测试企业', taxIdMasked: '9111****1234', emailMasked: 'a***@example.com', status: 'PENDING', createdAt: '2026-09-07 10:00:00', events: [{ title: '用户提交', time: '2026-09-07 10:00:00' }] }

beforeEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  clearStoredAuthToken()
  useAuthStore.setState({ status: 'anonymous', user: null, error: null })
})

async function selectOption(user: ReturnType<typeof userEvent.setup>, name: string, value: string) {
  await user.click(screen.getByRole('combobox', { name }))
  await user.click(screen.getByText(value, { selector: '.ant-select-item-option-content' }))
}

/**
 * Opens the detail drawer and returns it, titled.
 *
 * The drawer's accessible name is resolved through `aria-labelledby`, and under `NODE_ENV=test`
 * `@rc-component/util`'s `useId()` returns the constant `test-id` for every caller, so the title's
 * id collides with the first rc-component that also asked for an auto id — here the status select's
 * input, which sits earlier in the DOM. `getElementById` therefore lands on that input and the
 * dialog is nameless in tests only; in production the id is React's real, unique one. So the dialog
 * is found by role and its title asserted by text, which is what the name lookup was checking.
 */
async function openDetail(title: string) {
  const dialog = within(await screen.findByRole('dialog'))
  expect(dialog.getByText(title)).toBeInTheDocument()
  return dialog
}

/** The media api is the only place these paths are written; the console must not invent one. */
describe('管理端媒体接口契约', () => {
  it('八个只读端点的路径、查询串与 Bearer 认证', async () => {
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    const page = { items: [], page: 1, size: 20, total: 0, hasNext: false }
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockImplementation(async () =>
        jsonResponse({ code: 'OK', message: 'success', traceId: testTraceId, data: page }),
      )
    vi.stubGlobal('fetch', fetchMock)

    await getAdminMediaOverview()
    await listAdminMediaResources({ page: 1, size: 20 })
    await listAdminMediaResources({ status: 'ACTIVE', type: '图文', page: 2, size: 20 })
    await getAdminMediaResource('7001')
    await listAdminMediaSubmissions({ group: 'EXCEPTION', page: 1, size: 20 })
    await getAdminMediaSubmission('8001')
    await listAdminMediaCallbacks({ page: 1, size: 20 })
    await getAdminMediaCallback('9001')
    await listAdminMediaSyncStates()

    expect(fetchMock.mock.calls.map(([url]) => String(url))).toEqual([
      '/api/v1/admin/media/overview',
      '/api/v1/admin/media/resources?page=1&size=20',
      `/api/v1/admin/media/resources?type=${encodeURIComponent('图文')}&status=ACTIVE&page=2&size=20`,
      '/api/v1/admin/media/resources/7001',
      '/api/v1/admin/media/submissions?group=EXCEPTION&page=1&size=20',
      '/api/v1/admin/media/submissions/8001',
      '/api/v1/admin/media/callbacks?page=1&size=20',
      '/api/v1/admin/media/callbacks/9001',
      '/api/v1/admin/media/sync-states',
    ])
    for (const [, init] of fetchMock.mock.calls) {
      expect(new Headers(init?.headers).get('Authorization')).toBe(`Bearer ${testAccessToken}`)
      expect(init?.method).toBe('GET')
    }
  })

  it.each([401, 403, 404, 422])('%s 错误保留后端 message 和 traceId', async (status) => {
    // Without a stored token the client refuses before it ever reaches fetch, and the status under
    // test would never be the one the backend sent.
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockImplementation(async () => apiErrorResponse(status, `媒体错误-${status}`)),
    )
    await expect(getAdminMediaResource('7001')).rejects.toMatchObject({
      status,
      message: `媒体错误-${status}`,
      traceId: testTraceId,
    })
  })
})

describe('管理端媒体筛选编码', () => {
  it('投稿筛选只编码状态或分组，绝不同时发送两者', () => {
    expect(toAdminMediaSubmissionQuery('ALL', 1, 20)).toEqual({ page: 1, size: 20 })
    expect(toAdminMediaSubmissionQuery('GROUP:EXCEPTION', 1, 20)).toEqual({
      group: 'EXCEPTION',
      page: 1,
      size: 20,
    })
    expect(toAdminMediaSubmissionQuery('STATUS:SUBMIT_UNKNOWN', 2, 50)).toEqual({
      status: 'SUBMIT_UNKNOWN',
      page: 2,
      size: 50,
    })
    for (const filter of ['ALL', 'GROUP:PENDING', 'GROUP:EXCEPTION', 'STATUS:QUEUED', 'STATUS:FAILED']) {
      const query = toAdminMediaSubmissionQuery(filter, 1, 20)
      const exclusive = ['status', 'group'].filter((key) => key in query)
      expect(exclusive).toHaveLength(filter === 'ALL' ? 0 : 1)
    }
  })

  it('资源筛选去除首尾空格且不发送空条件', () => {
    expect(toAdminMediaResourceQuery({ search: '  媒体甲  ', modelId: '  ', type: '', status: 'INACTIVE' }, 3, 20)).toEqual({
      search: '媒体甲',
      modelId: undefined,
      type: undefined,
      status: 'INACTIVE',
      page: 3,
      size: 20,
    })
  })
})

describe('管理端媒体与投稿', () => {
  it('四个页签的真实空态，不展示未提供的手动写操作', async () => {
    const user = userEvent.setup()
    const request = vi.spyOn(globalThis, 'fetch')
    render(<AdminMediaWorkspace />)

    expect(screen.queryByRole('button', { name: '同步媒体资源' })).not.toBeInTheDocument()
    // Reads are not blocked: an operator may narrow the list before anything has loaded.
    expect(screen.getByLabelText('搜索媒体')).toBeEnabled()
    expect(screen.getByLabelText('上游模型 ID')).toBeEnabled()
    expect(screen.queryByLabelText('上游资源 ID')).not.toBeInTheDocument()
    expect(screen.getAllByText('—')).toHaveLength(4)

    for (const [tab, empty] of [
      ['投稿订单', '暂无投稿订单'],
      ['异常回调', '暂无异常回调'],
      ['当前同步状态', '暂无同步状态记录'],
      ['媒体资源', '暂无媒体资源'],
    ]) {
      await user.click(screen.getByRole('tab', { name: tab }))
      expect(screen.getByText(empty)).toBeInTheDocument()
    }
    // 同步记录 was the invented name; the surface shows the current state, not a run log.
    expect(screen.queryByRole('tab', { name: '同步记录' })).not.toBeInTheDocument()
    expect(request).not.toHaveBeenCalled()
  })

  it('投稿列表展示 Java 的八个真实状态，不压缩成五种', async () => {
    const user = userEvent.setup()
    const statuses = Object.keys(MEDIA_SUBMISSION_STATUS_META) as AdminMediaSubmissionStatus[]
    expect(statuses).toHaveLength(8)
    render(
      <AdminMediaWorkspace
        submissions={{ items: statuses.map((status) => submission(status)), filter: 'ALL', page: 1, pageSize: 20, total: statuses.length, loading: false }}
      />,
    )
    await user.click(screen.getByRole('tab', { name: '投稿订单' }))

    for (const status of statuses) {
      expect(screen.getAllByText(MEDIA_SUBMISSION_STATUS_META[status].label)).toHaveLength(1)
    }
    expect(screen.getByText('受理结果未知')).toBeInTheDocument()
    expect(screen.getByText('提交失败')).toBeInTheDocument()
    expect(screen.getByText('共 8 条')).toBeInTheDocument()
    for (const state of Object.values(MEDIA_SUBMISSION_STATE_META)) {
      expect(state.label).not.toBe('')
    }
  })

  it('资源报价按服务端原值展示，前端不做运算也不修补无效小数', () => {
    render(
      <AdminMediaWorkspace
        resources={{
          items: [
            resource(),
            resource({ id: '7002', name: '媒体乙', status: 'INACTIVE', upstreamPrice: '0.0100', markupRate: '0.3500', referenceSaleAmountCents: 100, referenceChargedPoints: 100, estimatedDays: null }),
            resource({ id: '7003', name: '媒体丙', upstreamPrice: 'not-a-decimal', markupRate: '1e3', referenceSaleAmountCents: 12345, referenceChargedPoints: 12345 }),
          ],
          filters: {},
          page: 1,
          pageSize: 20,
          total: 3,
          loading: false,
        }}
      />,
    )

    expect(screen.getByText('¥368.0000')).toBeInTheDocument()
    expect(screen.getByText('¥497.00')).toBeInTheDocument()
    expect(screen.getAllByText('0.3500')).toHaveLength(2)
    expect(screen.getByText('¥0.0100')).toBeInTheDocument()
    expect(screen.getByText('¥1.00')).toBeInTheDocument()
    // A price the server did not write as a plain decimal is shown as absent, never reformatted.
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
    expect(screen.queryByText('¥1000.00')).not.toBeInTheDocument()
    // The status is a read-only fact, not an enable/disable control.
    expect(screen.getAllByRole('cell', { name: MEDIA_RESOURCE_STATUS_META.ACTIVE.label })).toHaveLength(2)
    expect(screen.getAllByRole('cell', { name: MEDIA_RESOURCE_STATUS_META.INACTIVE.label })).toHaveLength(1)
    expect(screen.getByText('¥123.45')).toBeInTheDocument()
    const inactiveRow = screen.getByRole('row', { name: /媒体乙/ })
    expect(within(inactiveRow).getAllByText('（历史）')).toHaveLength(3)
  })

  it('停用资源详情将保留金额明确标为历史报价', async () => {
    const inactive = resource({ status: 'INACTIVE' })
    render(
      <AdminMediaWorkspace
        detail={{ target: { kind: 'resource', id: inactive.id }, resource: inactive, loading: false }}
      />,
    )
    const detail = await openDetail('资源详情')
    expect(detail.getByText('最近记录的报价（当前不可投稿）')).toBeInTheDocument()
    expect(detail.getByText(/以上金额是本地最后一次记录值，仅供核对/)).toBeInTheDocument()
  })

  it('资源详情逐字展示上游能力，不提供资源启停', async () => {
    // The workspace is a view: opening a row is the container's job, so the detail arrives as a
    // prop here. The click-through path is covered by the console case below.
    const row = resource({
      capabilities: { publish_window: '2-6h', z_extra: ['a', 'b'], allow_images: true, none: null },
    })
    render(
      <AdminMediaWorkspace
        resources={{ items: [row], filters: {}, page: 1, pageSize: 20, total: 1, loading: false }}
        detail={{ target: { kind: 'resource', id: row.id }, resource: row, loading: false }}
      />,
    )
    const detail = await openDetail('资源详情')

    // Sorted by key, printed as the provider wrote them: no translation, no merging.
    expect(detail.getAllByRole('term').map((node) => node.textContent)).toEqual([
      '采购原价（元）',
      '加成系数',
      '上取整后售价',
      '用户积分价',
      '资源类型',
      '渠道分类',
      '地区',
      '预计时效',
      'allow_images',
      'none',
      'publish_window',
      'z_extra',
      '本地状态',
      '最近上游可见',
      '最近同步',
      '记录版本',
    ])
    expect(detail.getByText('true')).toBeInTheDocument()
    expect(detail.getByText('["a","b"]')).toBeInTheDocument()
    expect(detail.getByText('2-6h')).toBeInTheDocument()
    expect(detail.getByText('¥368.0000')).toBeInTheDocument()
    expect(detail.queryByRole('button', { name: '启用 / 停用' })).not.toBeInTheDocument()
    expect(detail.getByText(/向上取整到整元，再换算为分/)).toBeInTheDocument()
  })

  it('回调列表以回执编号为准且没有 Trace 列，不提供回调重放', async () => {
    const user = userEvent.setup()
    render(
      <AdminMediaWorkspace
        callbacks={{ items: [callback], page: 1, pageSize: 20, total: 1, loading: false }}
      />,
    )
    await user.click(screen.getByRole('tab', { name: '异常回调' }))

    expect(screen.getByText('9011')).toBeInTheDocument()
    expect(screen.getByText(MEDIA_CALLBACK_STATUS_META.RECEIVED.label)).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: /trace/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/trace/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重放回调' })).not.toBeInTheDocument()
  })

  it('同步页签显示当前状态：约束内的四个值加上原样显示的未知值', () => {
    const states: AdminMediaSyncState[] = [
      { ...syncState, status: 'IDLE' },
      { ...syncState, status: 'RUNNING' },
      syncState,
      { ...syncState, status: 'FAILED', lastErrorCode: 'UPSTREAM_TIMEOUT' },
      // Simulate a contract-violating runtime payload to keep the UI fallback covered even though
      // the generated contract correctly narrows this field to the four states Java can emit.
      { ...syncState, upstreamModelId: 'model_unknown', status: 'PAUSED_BY_VENDOR' as unknown as AdminMediaSyncState['status'] },
    ]
    // The sync tab is stated, not clicked: only the visible tab reads, so nothing below it renders
    // until the operator is actually on it.
    render(
      <AdminMediaWorkspace activeTab="sync" syncStates={{ items: states, loading: false }} />,
    )

    expect(screen.getByRole('tab', { name: '当前同步状态' })).toBeInTheDocument()
    expect(screen.getByText('待运行')).toBeInTheDocument()
    expect(screen.getByText('同步中')).toBeInTheDocument()
    expect(screen.getByText('已完成')).toBeInTheDocument()
    expect(screen.getByText('失败')).toBeInTheDocument()
    // An unrecognised value is shown as itself rather than mapped onto one of the four labels.
    expect(screen.getByText('PAUSED_BY_VENDOR')).toBeInTheDocument()
    expect(screen.getByText('UPSTREAM_TIMEOUT')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新同步' })).not.toBeInTheDocument()
    expect(screen.getByText(/没有同步批次、操作人或本次新增\/更新计数/)).toBeInTheDocument()
  })

  it('投稿详情用事件 ID 而非 Trace，不提供未经定义的人工处置', async () => {
    const row = submission('SUBMIT_UNKNOWN', { stableErrorCode: 'UPSTREAM_TIMEOUT' })
    render(
      <AdminMediaWorkspace
        submissions={{ items: [row], filter: 'ALL', page: 1, pageSize: 20, total: 1, loading: false }}
        detail={{
          target: { kind: 'submission', id: row.id },
          submission: row,
          submissionDetail: {
            ...row,
            events: [
              { id: 'EVENT-1', sequence: 1, eventType: 'SUBMIT_STARTED', fromStatus: null, toStatus: 'SUBMITTING', stableErrorCode: null, occurredAt: '2026-09-07T09:48:16Z' },
              { id: 'EVENT-2', sequence: 2, eventType: 'SUBMIT_TIMEOUT', fromStatus: 'SUBMITTING', toStatus: 'SUBMIT_UNKNOWN', stableErrorCode: 'UPSTREAM_TIMEOUT', occurredAt: '2026-09-07T09:49:02Z' },
            ],
          },
          loading: false,
        }}
      />,
    )
    const detail = await openDetail('投稿详情')

    expect(detail.getByRole('columnheader', { name: '事件 ID' })).toBeInTheDocument()
    expect(detail.getByText('EVENT-1')).toBeInTheDocument()
    expect(detail.getByRole('cell', { name: '— → 提交中' })).toBeInTheDocument()
    expect(detail.getByRole('cell', { name: '提交中 → 受理结果未知' })).toBeInTheDocument()
    expect(detail.getByText(/因此这里没有 Trace/)).toBeInTheDocument()
    expect(detail.queryByRole('columnheader', { name: /trace id/i })).not.toBeInTheDocument()
    expect(detail.getByText('上游结果未知或超时不代表失败，不得直接重投')).toBeInTheDocument()
    for (const name of ['查询上游状态', '标记人工核对']) {
      expect(detail.queryByRole('button', { name })).not.toBeInTheDocument()
    }
    expect(detail.getByText('成交价格（下单时冻结）')).toBeInTheDocument()
  })

  it('投稿详情到达后以 by-id 最新主体字段覆盖列表旧值', async () => {
    const row = submission('QUEUED', {
      mediaName: '列表中的旧媒体名',
      upstreamStatus: 'QUEUED',
      upstreamOrderNo: null,
      publishedUrl: null,
      saleAmountCents: 49700,
      chargedPoints: 49700,
      remark: '列表旧备注',
    })
    const current: AdminMediaSubmission = {
      ...row,
      mediaName: '详情中的最新媒体名',
      status: 'PUBLISHED',
      submissionState: 'CONFIRMED',
      upstreamStatus: 'PUBLISHED',
      upstreamOrderId: 'UPSTREAM-ID-NEW',
      upstreamOrderNo: 'UPSTREAM-NO-NEW',
      publishedUrl: 'https://example.com/latest-article',
      publishedUrls: [
        'https://example.com/latest-article',
        'http://example.org/second-article',
      ],
      saleAmountCents: 60000,
      chargedPoints: 60000,
      remark: '详情最新备注',
      publishedAt: '2026-09-07T10:00:00Z',
      updatedAt: '2026-09-07T10:00:01Z',
      events: [],
    }
    render(
      <AdminMediaWorkspace
        detail={{
          target: { kind: 'submission', id: row.id },
          submission: row,
          submissionDetail: current,
          loading: false,
        }}
      />,
    )

    const detail = await openDetail('投稿详情')
    expect(detail.getByText('详情中的最新媒体名')).toBeInTheDocument()
    expect(detail.getAllByText('已发布')).toHaveLength(2)
    expect(detail.getByText('PUBLISHED')).toBeInTheDocument()
    expect(detail.getByText('UPSTREAM-NO-NEW')).toBeInTheDocument()
    expect(detail.getByText('UPSTREAM-ID-NEW')).toBeInTheDocument()
    expect(detail.getByText('详情最新备注')).toBeInTheDocument()
    expect(detail.getByText('¥600.00')).toBeInTheDocument()
    expect(detail.getByText('60,000 积分')).toBeInTheDocument()
    expect(detail.getByRole('link', { name: '查看已发布文章 1' })).toHaveAttribute(
      'href',
      'https://example.com/latest-article',
    )
    expect(detail.getByRole('link', { name: '查看已发布文章 2' })).toHaveAttribute(
      'href',
      'http://example.org/second-article',
    )
    expect(detail.queryByText('列表中的旧媒体名')).not.toBeInTheDocument()
    expect(detail.queryByText('列表旧备注')).not.toBeInTheDocument()
  })

  it('投稿详情在展开前不编造状态事件', async () => {
    const row = submission('QUEUED')
    render(
      <AdminMediaWorkspace
        submissions={{ items: [row], filter: 'ALL', page: 1, pageSize: 20, total: 1, loading: false }}
        detail={{ target: { kind: 'submission', id: row.id }, submission: row, loading: false }}
      />,
    )
    const detail = await openDetail('投稿详情')
    expect(detail.getByText('该投稿尚未记录状态事件')).toBeInTheDocument()
  })

  it('回调详情不展示正文摘要，回执编号即句柄', async () => {
    render(
      <AdminMediaWorkspace
        callbacks={{ items: [callback], page: 1, pageSize: 20, total: 1, loading: false }}
        detail={{ target: { kind: 'callback', id: callback.id }, callback, loading: false }}
      />,
    )
    const detail = await openDetail('回调回执详情')
    expect(detail.getByText('不展示回调请求体与正文摘要')).toBeInTheDocument()
    expect(detail.getByText('2')).toBeInTheDocument()
    expect(detail.queryByRole('button', { name: '重放回调' })).not.toBeInTheDocument()
  })

  it.each(['javascript:alert(1)', 'data:text/html,test', 'file:///test', 'invalid', '', 'https://user:pass@example.com'])(
    '拒绝不安全回链 %s',
    async (url) => {
      const row = submission('PUBLISHED', { publishedUrl: url })
      render(
        <AdminMediaWorkspace
          submissions={{ items: [row], filter: 'ALL', page: 1, pageSize: 20, total: 1, loading: false }}
          detail={{ target: { kind: 'submission', id: row.id }, submission: row, loading: false }}
        />,
      )
      const detail = await openDetail('投稿详情')
      expect(detail.queryByRole('link', { name: /查看已发布文章/ })).not.toBeInTheDocument()
      expect(detail.getByText('已发布，上游暂未提供可访问链接')).toBeInTheDocument()
    },
  )

  it('合法 HTTPS 回链可点击且带 noopener', async () => {
    const row = submission('PUBLISHED', { publishedUrl: 'https://example.com/article' })
    render(
      <AdminMediaWorkspace
        submissions={{ items: [row], filter: 'ALL', page: 1, pageSize: 20, total: 1, loading: false }}
        detail={{ target: { kind: 'submission', id: row.id }, submission: row, loading: false }}
      />,
    )
    const link = await screen.findByRole('link', { name: '查看已发布文章 1' })
    expect(link).toHaveAttribute('href', 'https://example.com/article')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('关闭详情后再次渲染同一条记录不会自动重新打开', async () => {
    const user = userEvent.setup()
    const { rerender } = render(
      <AdminMediaWorkspace
        resources={{ items: [resource()], filters: {}, page: 1, pageSize: 20, total: 1, loading: false }}
      />,
    )
    await user.click(screen.getByRole('button', { name: '查看资源' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    rerender(
      <AdminMediaWorkspace
        resources={{ items: [resource()], filters: {}, page: 1, pageSize: 20, total: 1, loading: false }}
        detail={{ target: { kind: 'resource', id: '7001' }, resource: resource(), loading: false }}
      />,
    )
    await openDetail('资源详情')
    rerender(
      <AdminMediaWorkspace
        resources={{ items: [resource()], filters: {}, page: 1, pageSize: 20, total: 1, loading: false }}
        detail={null}
      />,
    )
    // The close is animated, so the panel stays in the DOM for the duration of the motion.
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('服务端错误原样呈现并可直接重试，失败列表不伪装成空数据', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    render(
      <AdminMediaWorkspace
        overview={undefined}
        overviewError={{ message: '媒体概览暂时不可用', traceId: testTraceId }}
        onRetryOverview={onRetry}
        resources={{ items: [], filters: {}, page: 1, pageSize: 20, total: 0, loading: false, error: { message: '资源列表加载失败' } }}
        submissions={{ items: [], filter: 'ALL', page: 1, pageSize: 20, total: 0, loading: false, error: { message: '投稿列表加载失败' } }}
        callbacks={{ items: [], page: 1, pageSize: 20, total: 0, loading: false, error: { message: '回调列表加载失败' } }}
        syncStates={{ items: [], loading: false, error: { message: '同步状态加载失败' } }}
      />,
    )
    expect(screen.getByText('媒体概览暂时不可用')).toBeInTheDocument()
    expect(screen.getByText(`traceId：${testTraceId}`)).toBeInTheDocument()
    expect(screen.getByText('资源列表加载失败')).toBeInTheDocument()
    expect(screen.queryByText('暂无媒体资源')).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: '投稿订单' }))
    expect(screen.getByText('投稿列表加载失败')).toBeInTheDocument()
    expect(screen.queryByText('暂无投稿订单')).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: '异常回调' }))
    expect(screen.getByText('回调列表加载失败')).toBeInTheDocument()
    expect(screen.queryByText('暂无异常回调')).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: '当前同步状态' }))
    expect(screen.getByText('同步状态加载失败')).toBeInTheDocument()
    expect(screen.queryByText('暂无同步状态记录')).not.toBeInTheDocument()

    // A failed count is not rendered as zero.
    expect(screen.getAllByText('—')).toHaveLength(4)
    fireEvent.click(screen.getAllByRole('button', { name: '重新加载' })[0])
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})

describe('管理端媒体控制台', () => {
  function stubMediaApi(items: readonly AdminMediaResource[] = [resource()]) {
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      const envelope = (data: unknown) =>
        jsonResponse({ code: 'OK', message: 'success', traceId: testTraceId, data })
      if (url.includes('/api/v1/admin/media/overview')) {
        return envelope({ activeResources: items.length, walletBalanceCents: null, pendingSubmissions: 1, exceptionSubmissions: 2 })
      }
      if (url.includes('/api/v1/admin/media/resources/')) return envelope(items[0] ?? null)
      if (url.includes('/api/v1/admin/media/resources')) {
        return envelope({ items, page: 1, size: 20, total: items.length, hasNext: false })
      }
      if (url.includes('/api/v1/admin/media/submissions')) {
        return envelope({ items: [submission(url.includes('group=EXCEPTION') ? 'SUBMIT_UNKNOWN' : 'QUEUED')], page: 1, size: 20, total: 1, hasNext: false })
      }
      if (url.includes('/api/v1/admin/media/callbacks')) {
        return envelope({ items: [callback], page: 1, size: 20, total: 1, hasNext: false })
      }
      if (url.includes('/api/v1/admin/media/sync-states')) return envelope([syncState])
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    return () => fetchMock.mock.calls.map(([url]) => String(url))
  }

  async function openAdminMedia() {
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    const rendered = renderApp(['/admin/media-submissions'])
    expect(await screen.findByRole('heading', { name: '媒体资源与投稿' })).toBeInTheDocument()
    return rendered
  }

  it('正式路由渲染真实控制台，首屏只读概览与当前页签', async () => {
    const urls = stubMediaApi()
    await openAdminMedia()

    expect(await screen.findByText('媒体甲')).toBeInTheDocument()
    expect(screen.getByText('¥368.0000')).toBeInTheDocument()
    expect(screen.getByText('共 1 条')).toBeInTheDocument()
    expect(screen.queryByText('404 页面未找到')).not.toBeInTheDocument()
    expect(screen.queryByText('PREVIEW-MEDIA-001')).not.toBeInTheDocument()
    expect(screen.queryByText('PREVIEW-INVOICE-001')).not.toBeInTheDocument()

    // One overview plus the visible tab: the other three tabs cost nothing until they are opened.
    const requested = urls()
    expect(requested.some((url) => url.includes('/api/v1/admin/media/overview'))).toBe(true)
    expect(requested.some((url) => url.includes('/api/v1/admin/media/resources?page=1&size=20'))).toBe(true)
    for (const path of ['/submissions', '/callbacks', '/sync-states']) {
      expect(requested.some((url) => url.includes(`/api/v1/admin/media${path}`))).toBe(false)
    }
  })

  it('刷新只重读当前页签和概览，不调用上游写接口', async () => {
    const user = userEvent.setup()
    const urls = stubMediaApi()
    await openAdminMedia()
    await screen.findByText('媒体甲')

    await user.click(screen.getByRole('button', { name: '刷新当前数据' }))
    await waitFor(() => {
      expect(urls().filter((url) => url.includes('/api/v1/admin/media/overview'))).toHaveLength(2)
      expect(urls().filter((url) => url.includes('/api/v1/admin/media/resources?page=1&size=20'))).toHaveLength(2)
    })
    expect(urls().some((url) => /\/api\/v1\/admin\/media\/(submissions|callbacks|sync-states)/.test(url))).toBe(false)
  })

  it('搜索条件提交后才生效并去除空格，切换到其他页签才发起对应请求', async () => {
    const user = userEvent.setup()
    const urls = stubMediaApi()
    await openAdminMedia()
    await screen.findByText('媒体甲')

    fireEvent.change(screen.getByLabelText('搜索媒体'), { target: { value: '  媒体甲  ' } })
    // Typing alone must not query: the draft is applied on 查询.
    expect(urls().every((url) => !url.includes('search='))).toBe(true)
    await user.click(screen.getByRole('button', { name: '查询' }))
    await waitFor(() =>
      expect(
        urls().some((url) => url.includes(`search=${encodeURIComponent('媒体甲')}`)),
      ).toBe(true),
    )
    expect(urls().some((url) => url.includes('%20'))).toBe(false)

    await user.click(screen.getByRole('tab', { name: '异常回调' }))
    expect(await screen.findByText('9011')).toBeInTheDocument()
    await waitFor(() =>
      expect(
        urls().some((url) => url.includes('/api/v1/admin/media/callbacks?page=1&size=20')),
      ).toBe(true),
    )

    await user.click(screen.getByRole('tab', { name: '当前同步状态' }))
    expect(await screen.findByText('model_alpha')).toBeInTheDocument()
    await waitFor(() =>
      expect(urls().some((url) => url.includes('/api/v1/admin/media/sync-states'))).toBe(true),
    )
  })

  it('异常投稿快捷入口进入投稿页签并只发送分组筛选', async () => {
    const user = userEvent.setup()
    const urls = stubMediaApi()
    await openAdminMedia()
    await screen.findByText('媒体甲')

    await user.click(screen.getByRole('button', { name: '查看异常投稿' }))
    expect(await screen.findByRole('tab', { name: '投稿订单', selected: true })).toBeInTheDocument()
    await waitFor(() => {
      const submissionUrls = urls().filter((url) => url.includes('/api/v1/admin/media/submissions'))
      expect(submissionUrls).toHaveLength(1)
      expect(submissionUrls[0]).toContain('group=EXCEPTION')
      expect(submissionUrls[0]).not.toContain('status=')
    })
  })

  it('详情抽屉按编号读取资源并保留列表行作为加载占位', async () => {
    const user = userEvent.setup()
    const urls = stubMediaApi()
    await openAdminMedia()

    await user.click(await screen.findByRole('button', { name: '查看资源' }))
    const detail = await openDetail('资源详情')
    expect(detail.getByText('¥497.00')).toBeInTheDocument()
    await waitFor(() =>
      expect(urls().some((url) => url.includes('/api/v1/admin/media/resources/7001'))).toBe(true),
    )
  })

  it.each(['/admin/media-submissions', '/admin/invoices'])('普通用户不可访问 %s', async (path) => {
    useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
    renderApp([path])
    expect(await screen.findByText('403 拒绝访问')).toBeInTheDocument()
  })

  it.each([['/preview/admin/media-submissions', '媒体资源与投稿', '媒体与投稿'], ['/preview/admin/invoices', '发票管理', '发票管理']])('开发预览路由 %s 可用且导航高亮', async (path, heading, nav) => {
    renderApp([path]); expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
    expect(within(screen.getByRole('navigation', { name: '管理员主导航' })).getByRole('button', { name: nav })).toHaveAttribute('aria-current', 'page')
  })

  it('正式媒体路由在管理员导航中可见且高亮', async () => {
    stubMediaApi()
    await openAdminMedia()
    expect(
      within(screen.getByRole('navigation', { name: '管理员主导航' })).getByRole('button', {
        name: '媒体与投稿',
      }),
    ).toHaveAttribute('aria-current', 'page')
  })
})

describe('管理员发票管理', () => {
  it('无数据保持空态及未知统计，不能查询或处理', () => {
    render(<AdminInvoiceWorkspace />)
    expect(screen.getByText('暂无发票申请')).toBeInTheDocument()
    expect(screen.getAllByText('—')).toHaveLength(4)
    expect(screen.getByRole('button', { name: '查询' })).toBeDisabled()
    expect(screen.getByLabelText('搜索发票账号')).toBeDisabled()
  })
  it('申请号、账号、状态、日期组合查询及重置', async () => {
    const user = userEvent.setup(); render(<AdminInvoiceWorkspace applications={[invoice, { ...invoice, id: 'INV-2', status: 'COMPLETED', createdAt: '2026-09-06' }]} />)
    fireEvent.change(screen.getByLabelText('搜索发票申请号'), { target: { value: ' inv-1 ' } })
    fireEvent.change(screen.getByLabelText('搜索发票账号'), { target: { value: ' 1234 ' } })
    await selectOption(user, '发票状态', '待处理')
    fireEvent.change(screen.getByLabelText('发票开始日期'), { target: { value: '2026-09-07' } })
    fireEvent.change(screen.getByLabelText('发票结束日期'), { target: { value: '2026-09-07' } })
    await user.click(screen.getByRole('button', { name: '查询' }))
    expect(screen.getByText('INV-1')).toBeInTheDocument(); expect(screen.queryByText('INV-2')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('发票结束日期'), { target: { value: '2026-09-06' } })
    expect(screen.getByText('开始日期不能晚于结束日期')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '查询' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '重置' }))
    expect(screen.getByText('INV-2')).toBeInTheDocument()
  })
  it('填写处理信息后所有状态操作仍禁用且不发请求', async () => {
    const user = userEvent.setup(); const request = vi.spyOn(globalThis, 'fetch'); render(<AdminInvoiceWorkspace applications={[invoice]} />)
    await user.click(screen.getByRole('button', { name: '查看申请' }))
    expect(screen.getByText('用户提交')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('发票号码'), { target: { value: 'INVOICE-NUMBER' } })
    fireEvent.change(screen.getByLabelText('发票处理备注'), { target: { value: '仅本地备注' } })
    await user.click(screen.getByRole('radio', { name: '发送成功' }))
    for (const name of ['驳回申请', '标记处理中', '标记已完成']) {
      expect(screen.getByRole('button', { name })).toBeDisabled(); await user.click(screen.getByRole('button', { name }))
    }
    expect(request).not.toHaveBeenCalled()
  })
  it('关闭脏表单需要确认，继续保留，放弃后清空', async () => {
    const user = userEvent.setup(); render(<AdminInvoiceWorkspace applications={[invoice]} />)
    await user.click(screen.getByRole('button', { name: '查看申请' }))
    fireEvent.change(screen.getByLabelText('发票处理备注'), { target: { value: '未保存内容' } })
    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(screen.getByText('放弃发票本地填写？')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '继续填写' }))
    expect(screen.getByLabelText('发票处理备注')).toHaveValue('未保存内容')
    await user.click(screen.getByRole('button', { name: 'Close' }))
    await user.click(screen.getByRole('button', { name: '放弃填写' }))
    await user.click(screen.getByRole('button', { name: '查看申请' }))
    expect(screen.getByLabelText('发票处理备注')).toHaveValue('')
  })
  it.each([null, { status: 'COMPLETED' }, { id: 'OTHER' }, { amountCents: 999 }, { account: '其他账号' } ] as (Partial<InvoiceApplicationView> | null)[])('申请删除或关键字段刷新清空旧表单：%j', async change => {
    const user = userEvent.setup(); const { rerender } = render(<AdminInvoiceWorkspace applications={[invoice]} />)
    await user.click(screen.getByRole('button', { name: '查看申请' }))
    fireEvent.change(screen.getByLabelText('发票处理备注'), { target: { value: '旧内容' } })
    rerender(<AdminInvoiceWorkspace applications={change ? [{ ...invoice, ...change }] : []} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText('申请数据已更新，请重新打开详情')).toBeInTheDocument()
    rerender(<AdminInvoiceWorkspace applications={[invoice]} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '查看申请' }))
    expect(screen.getByLabelText('发票处理备注')).toHaveValue('')
  })
  it('等价 Props 新对象保留处理表单', async () => {
    const user = userEvent.setup(); const { rerender } = render(<AdminInvoiceWorkspace applications={[invoice]} />)
    await user.click(screen.getByRole('button', { name: '查看申请' }))
    fireEvent.change(screen.getByLabelText('发票处理备注'), { target: { value: '保留内容' } })
    rerender(<AdminInvoiceWorkspace applications={[{ ...invoice, events: invoice.events.map(item => ({ ...item })) }]} />)
    expect(screen.getByLabelText('发票处理备注')).toHaveValue('保留内容')
  })
  it.each(['COMPLETED', 'REJECTED'] as const)('结束状态 %s 的处理信息只读', async status => {
    const user = userEvent.setup(); render(<AdminInvoiceWorkspace applications={[{ ...invoice, status }]} />)
    await user.click(screen.getByRole('button', { name: '查看申请' }))
    expect(screen.getByLabelText('发票号码')).toBeDisabled()
    expect(screen.getByLabelText('发票处理备注')).toBeDisabled()
    expect(screen.getByRole('radio', { name: '发送成功' })).toBeDisabled()
  })
  it('大安全整数金额精确展示、无效计数拒绝展示', () => {
    render(<AdminInvoiceWorkspace applications={[{ ...invoice, amountCents: 9007199254740990 }]} />)
    expect(screen.getByText('¥90071992547409.90')).toBeInTheDocument()
    for (const value of [NaN, Infinity, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) expect(displayCount(value)).toBe('—')
  })
})

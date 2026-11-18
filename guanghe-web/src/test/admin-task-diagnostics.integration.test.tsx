import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { PropsWithChildren } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  getAdminRunDiagnostic,
  getAdminTaskDiagnostic,
  listAdminRunDiagnostics,
  listAdminTaskDiagnostics,
} from '../api/adminDiagnosticsApi'
import { storeAuthToken } from '../auth/tokenStorage'
import {
  AdminTaskDiagnosticsWorkspace,
  AdminRunDiagnosticsWorkspace,
  toAdminRunDiagnosticDetailView,
  toAdminRunDiagnosticPageView,
  toAdminTaskDiagnosticDetailView,
  toAdminTaskDiagnosticPageView,
  useAdminRunDiagnostics,
  useAdminTaskDiagnostics,
} from '../features/admin-tasks'
import type { RunDiagnosticDto, TaskDiagnosticDto } from '../api/adminDiagnosticsApi'
import { AdminTaskServicePage } from '../pages/admin/AdminTaskServicePage'
import { useAuthStore } from '../stores/authStore'
import { adminFixture, testAccessToken, testExpiresAt, testTraceId } from './fixtures/auth'

const diagnostic: TaskDiagnosticDto = {
  taskId: '9001', taskType: 'GEO', taskStatus: 'PARTIAL', stage: 'RESULT_READY',
  quotedPoints: 80, settledPoints: 60, createdAt: '2026-09-09T01:00:00Z',
  updatedAt: '2026-09-09T01:03:00Z', finishedAt: '2026-09-09T01:03:00Z',
  decision: { failureCategory: 'PROVIDER', stableErrorCode: 'UPSTREAM_TIMEOUT', retryability: 'SAFE_RETRY', retryable: true, waitingExternalRecovery: false },
  traceId: testTraceId,
  timeline: [{ eventType: 'TASK_PARTIAL', fromStatus: 'RUNNING', toStatus: 'PARTIAL', source: 'JAVA', stableErrorCode: 'UPSTREAM_TIMEOUT', occurredAt: '2026-09-09T01:03:00Z' }],
  executions: [{ executionNo: 1, status: 'FAILED', stage: 'PROVIDER', provider: 'QWEN', model: 'safe-model', latencyMs: 1200, stableErrorCode: 'UPSTREAM_TIMEOUT' }],
  outbox: [{ executionAttempt: 1, status: 'PUBLISHED', attempts: 1, availableAt: '2026-09-09T01:00:01Z', publishedAt: '2026-09-09T01:00:02Z', createdAt: '2026-09-09T01:00:01Z' }],
  inbox: [{ disposition: 'CLAIMED', createdAt: '2026-09-09T01:00:03Z' }],
  billing: { reservationStatus: 'SETTLED', quotedPoints: 80, frozenPoints: 80, settledPoints: 60, releasedPoints: 20, ledger: [{ entryType: 'SETTLE', availableDelta: 0, frozenDelta: -60, createdAt: '2026-09-09T01:03:00Z' }] },
  assetIngestion: [{ status: 'COMPLETED', attempt: 1, maxAttempts: 3, provider: 'OSS', model: null, stableErrorCode: null, availableAt: '2026-09-09T01:02:00Z', completedAt: '2026-09-09T01:02:10Z', updatedAt: '2026-09-09T01:02:10Z' }],
  geo: { runId: 'geo-run-1', status: 'PARTIAL', expectedUnits: 7, succeededUnits: 6, failedUnits: 1, missingUnits: 0, updatedAt: '2026-09-09T01:03:00Z' },
  article: null,
  media: null,
  dataGaps: ['TRACE_ID_NOT_PERSISTED'],
}

const runDiagnostic: RunDiagnosticDto = {
  runId: 'run-safe-1',
  conversationId: '91001',
  status: 'FAILED',
  lastPythonSequence: 12,
  stableErrorCode: 'PROVIDER_TIMEOUT',
  decision: {
    failureCategory: 'PROVIDER',
    stableErrorCode: 'PROVIDER_TIMEOUT',
    retryability: 'SAFE_RETRY',
    retryable: true,
    waitingExternalRecovery: false,
  },
  traceId: testTraceId,
  startedAt: '2026-09-10T01:00:01Z',
  completedAt: '2026-09-10T01:00:04Z',
  createdAt: '2026-09-10T01:00:00Z',
  updatedAt: '2026-09-10T01:00:04Z',
  usage: {
    workflowStepCount: 3,
    modelCallCount: 1,
    contextLoadCount: 2,
    toolCallCount: 1,
    providerCallCount: 1,
    estimatedInputTokens: 820,
    providerInputTokens: 780,
    providerOutputTokens: 120,
    providerLatencyMs: 2500,
    provider: 'SAFE_PROVIDER',
    model: 'safe-model',
  },
  contextBudget: {
    targetInputTokens: 4000,
    reservedOutputTokens: 800,
    reservedToolTokens: 400,
    reservedSafetyTokens: 200,
    availableHistoryTokens: 2600,
    estimatedHistoryTokens: 3200,
    truncated: true,
    contextSource: 'REDIS',
    cacheHit: true,
  },
  linkedTaskId: '9001',
  timeline: [
    { eventType: 'RUN_FAILED', sequence: 12, stableErrorCode: 'PROVIDER_TIMEOUT', occurredAt: '2026-09-10T01:00:04Z' },
    { eventType: 'RUN_STARTED', sequence: 2, stableErrorCode: null, occurredAt: '2026-09-10T01:00:01Z' },
  ],
  dataGaps: ['USAGE_NOT_REPORTED'],
}

function renderWorkspace(overrides: Partial<React.ComponentProps<typeof AdminTaskDiagnosticsWorkspace>> = {}) {
  const detail = toAdminTaskDiagnosticDetailView(diagnostic)
  return render(<AdminTaskDiagnosticsWorkspace tasks={[detail]} total={1} page={1} pageSize={20} filters={{}} onFiltersChange={vi.fn()} onPageChange={vi.fn()} onOpenTask={vi.fn()} onCloseTask={vi.fn()} {...overrides} />)
}

function renderRunWorkspace(
  overrides: Partial<React.ComponentProps<typeof AdminRunDiagnosticsWorkspace>> = {},
) {
  const detail = toAdminRunDiagnosticDetailView(runDiagnostic)
  return render(
    <AdminRunDiagnosticsWorkspace
      runs={[detail]}
      total={1}
      page={1}
      pageSize={20}
      filters={{}}
      onFiltersChange={vi.fn()}
      onPageChange={vi.fn()}
      onOpenRun={vi.fn()}
      onCloseRun={vi.fn()}
      {...overrides}
    />,
  )
}

beforeEach(() => {
  vi.restoreAllMocks()
  window.localStorage.clear()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
})

describe('管理员真实任务诊断', () => {
  it('生成契约适配列表和完整只读详情', async () => {
    const page = toAdminTaskDiagnosticPageView({ items: [diagnostic], page: 1, size: 20, total: 1, hasNext: false })
    expect(page.items[0]).toMatchObject({ id: '9001', statusTone: 'warning', stableErrorCode: 'UPSTREAM_TIMEOUT' })
    const user = userEvent.setup()
    renderWorkspace({ selectedTaskId: '9001', detail: toAdminTaskDiagnosticDetailView(diagnostic) })
    expect(screen.getByRole('table', { name: '任务诊断列表' })).toHaveTextContent('UPSTREAM_TIMEOUT')
    expect(await screen.findByRole('dialog')).toHaveTextContent('RESULT_READY')
    expect(screen.getByRole('table', { name: '执行尝试' })).toHaveTextContent('safe-model')
    expect(screen.getByText('geo-run-1')).toBeInTheDocument()
    expect(screen.getByText('TRACE_ID_NOT_PERSISTED')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /重试|停止|结算|审核/ })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Close' }))
  })

  it('筛选只回传白名单查询字段且表格分页外部受控', async () => {
    const user = userEvent.setup()
    const onFiltersChange = vi.fn()
    renderWorkspace({ onFiltersChange })
    fireEvent.change(screen.getByLabelText('任务号'), { target: { value: '9001' } })
    fireEvent.change(screen.getByLabelText('稳定错误码'), { target: { value: 'UPSTREAM_TIMEOUT' } })
    await user.click(screen.getByRole('button', { name: /查\s*询/ }))
    expect(onFiltersChange).toHaveBeenCalledWith(expect.objectContaining({ taskId: '9001', stableErrorCode: 'UPSTREAM_TIMEOUT' }))
  })

  it('API使用Bearer访问两个冻结的只读Browser端点', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data: { items: [diagnostic], page: 1, size: 20, total: 1, hasNext: false } }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: testTraceId, data: diagnostic }), { status: 200 }))
    await listAdminTaskDiagnostics({ taskType: 'GEO', page: 1, size: 20 })
    await getAdminTaskDiagnostic('9001')
    expect(fetchSpy.mock.calls[0]?.[0]).toContain('/api/v1/admin/diagnostics/tasks?taskType=GEO&page=1&size=20')
    expect(fetchSpy.mock.calls[1]?.[0]).toContain('/api/v1/admin/diagnostics/tasks/9001')
    for (const [, init] of fetchSpy.mock.calls) expect(new Headers(init?.headers).get('Authorization')).toBe(`Bearer ${testAccessToken}`)
  })

  it.each([401, 403, 404, 422])('%s错误保留后端message和traceId', async (status) => {
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(JSON.stringify({ code: `HTTP_${status}`, message: `诊断错误-${status}`, details: {}, traceId: testTraceId }), { status }))
    await expect(getAdminTaskDiagnostic('9001')).rejects.toMatchObject({ status, message: `诊断错误-${status}`, traceId: testTraceId })
  })

  it('Query Key按管理员用户ID隔离', async () => {
    const listSpy = vi.spyOn(await import('../api/adminDiagnosticsApi'), 'listAdminTaskDiagnostics').mockResolvedValue({ items: [], page: 1, size: 20, total: 0, hasNext: false })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const Wrapper = ({ children }: PropsWithChildren) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
    const Probe = () => { useAdminTaskDiagnostics({ page: 1, size: 20 }); return null }
    const view = render(<Probe />, { wrapper: Wrapper })
    await waitFor(() => expect(listSpy).toHaveBeenCalledTimes(1))
    useAuthStore.setState({ status: 'authenticated', user: { ...adminFixture, id: 'other-admin' }, error: null })
    view.rerender(<Probe />)
    await waitFor(() => expect(listSpy).toHaveBeenCalledTimes(2))
    expect(client.getQueryCache().findAll({ queryKey: ['admin-task-diagnostics'] }).map((query) => query.queryKey[1])).toEqual(expect.arrayContaining([adminFixture.id, 'other-admin']))
  })

  it('后端错误展示message和traceId，空列表展示真实空态', () => {
    renderWorkspace({ tasks: [], total: 0, error: { message: '参数不合法', traceId: testTraceId } })
    expect(screen.getByText('参数不合法')).toBeInTheDocument()
    expect(screen.getByText(`traceId：${testTraceId}`)).toBeInTheDocument()
    expect(screen.getByText('暂无任务诊断数据')).toBeInTheDocument()
  })

  it('只展示Java白名单，不渲染Provider内部ID、URL或业务正文', () => {
    renderWorkspace({ selectedTaskId: '9001', detail: toAdminTaskDiagnosticDetailView(diagnostic) })
    expect(document.body).not.toHaveTextContent(/providerTaskId|https?:\/\/|完整正文|完整 Prompt/)
  })
})

describe('管理员真实 AI 对话 Run 诊断', () => {
  it('同一诊断页面切换 Run Tab，并可从关联任务进入现有任务详情', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input)
      const data = url.endsWith('/api/v1/admin/diagnostics/runs/run-safe-1')
        ? runDiagnostic
        : url.includes('/api/v1/admin/diagnostics/runs')
          ? { items: [runDiagnostic], page: 1, size: 20, total: 1, hasNext: false }
          : url.endsWith('/api/v1/admin/diagnostics/tasks/9001')
            ? diagnostic
            : { items: [], page: 1, size: 20, total: 0, hasNext: false }
      return new Response(JSON.stringify({
        code: 'OK', message: 'success', traceId: testTraceId, data,
      }), { status: 200 })
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/admin/tasks']}>
        <QueryClientProvider client={client}>
          <AdminTaskServicePage />
        </QueryClientProvider>
      </MemoryRouter>,
    )
    await user.click(screen.getByRole('button', { name: 'AI 对话 Run' }))
    const runTable = await screen.findByRole('table', { name: 'Run 诊断列表' })
    expect(runTable).toHaveTextContent('run-safe-1')
    await user.click(within(runTable).getByRole('button', { name: '诊断详情' }))
    const runDialog = await screen.findByRole('dialog')
    await user.click(within(runDialog).getByRole('button', { name: '9001' }))
    expect(await screen.findByText('任务诊断 · 9001')).toBeVisible()
  })

  it('按 sequence 排序安全时间线并展示 Usage、Context、数据缺口和关联任务', async () => {
    const onOpenLinkedTask = vi.fn()
    const detail = toAdminRunDiagnosticDetailView(runDiagnostic)
    expect(detail.timeline.map((item) => item.sequence)).toEqual([2, 12])
    const user = userEvent.setup()
    renderRunWorkspace({
      selectedRunId: runDiagnostic.runId,
      detail,
      onOpenLinkedTask,
    })
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('safe-model')
    expect(dialog).toHaveTextContent('REDIS')
    expect(dialog).toHaveTextContent('历史已截断')
    expect(dialog).toHaveTextContent('该 Run 未上报完整 Usage')
    const rows = screen.getByRole('table', { name: 'Run 安全时间线' }).querySelectorAll('tbody tr')
    expect(rows[0]).toHaveTextContent('RUN_STARTED')
    expect(rows[1]).toHaveTextContent('RUN_FAILED')
    await user.click(screen.getByRole('button', { name: '9001' }))
    expect(onOpenLinkedTask).toHaveBeenCalledWith('9001')
    expect(document.body).not.toHaveTextContent(/prompt-secret|context-ref-secret|tool-input-secret|provider-response-secret/)
  })

  it('列表筛选和分页保持受控并按用户隔离 Query Key', async () => {
    const user = userEvent.setup()
    const onFiltersChange = vi.fn()
    const onPageChange = vi.fn()
    renderRunWorkspace({ onFiltersChange, onPageChange, total: 42 })
    await user.type(screen.getByLabelText('Run ID'), 'run-safe-1')
    await user.type(screen.getByLabelText('Conversation ID'), '91001')
    await user.type(screen.getByLabelText('Run 稳定错误码'), 'provider_timeout')
    await user.click(screen.getByRole('button', { name: /查\s*询/ }))
    expect(onFiltersChange).toHaveBeenCalledWith(expect.objectContaining({
      runId: 'run-safe-1',
      conversationId: '91001',
      stableErrorCode: 'provider_timeout',
    }))

    const listSpy = vi
      .spyOn(await import('../api/adminDiagnosticsApi'), 'listAdminRunDiagnostics')
      .mockResolvedValue({ items: [], page: 1, size: 20, total: 0, hasNext: false })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const Wrapper = ({ children }: PropsWithChildren) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    const Probe = () => { useAdminRunDiagnostics({ page: 1, size: 20 }); return null }
    const view = render(<Probe />, { wrapper: Wrapper })
    await waitFor(() => expect(listSpy).toHaveBeenCalledTimes(1))
    useAuthStore.setState({
      status: 'authenticated',
      user: { ...adminFixture, id: 'other-run-admin' },
      error: null,
    })
    view.rerender(<Probe />)
    await waitFor(() => expect(listSpy).toHaveBeenCalledTimes(2))
    expect(
      client.getQueryCache().findAll({ queryKey: ['admin-run-diagnostics'] })
        .map((query) => query.queryKey[1]),
    ).toEqual(expect.arrayContaining([adminFixture.id, 'other-run-admin']))
  })

  it('API 使用冻结的 Run 列表和详情端点并保留 Bearer', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({
        code: 'OK', message: 'success', traceId: testTraceId,
        data: { items: [runDiagnostic], page: 1, size: 20, total: 1, hasNext: false },
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        code: 'OK', message: 'success', traceId: testTraceId, data: runDiagnostic,
      }), { status: 200 }))
    const page = await listAdminRunDiagnostics({
      runId: 'run-safe-1',
      conversationId: '91001',
      status: 'FAILED',
      stableErrorCode: 'PROVIDER_TIMEOUT',
      traceId: testTraceId,
      page: 2,
      size: 20,
    })
    await getAdminRunDiagnostic('run-safe-1')
    expect(toAdminRunDiagnosticPageView(page).items[0]).toMatchObject({
      id: 'run-safe-1', statusTone: 'danger', stableErrorCode: 'PROVIDER_TIMEOUT',
    })
    expect(fetchSpy.mock.calls[0]?.[0]).toContain('/api/v1/admin/diagnostics/runs?')
    expect(fetchSpy.mock.calls[0]?.[0]).toContain('conversationId=91001')
    expect(fetchSpy.mock.calls[1]?.[0]).toContain('/api/v1/admin/diagnostics/runs/run-safe-1')
    for (const [, init] of fetchSpy.mock.calls) {
      expect(new Headers(init?.headers).get('Authorization')).toBe(`Bearer ${testAccessToken}`)
    }
  })

  it.each([401, 403, 404, 422])('%s错误保留Run接口message和traceId', async (status) => {
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(JSON.stringify({
      code: `HTTP_${status}`,
      message: `Run诊断错误-${status}`,
      details: {},
      traceId: testTraceId,
    }), { status }))
    await expect(getAdminRunDiagnostic('run-safe-1')).rejects.toMatchObject({
      status,
      message: `Run诊断错误-${status}`,
      traceId: testTraceId,
    })
  })

  it('缺失安全字段显示占位且不恢复任何正文或内部载荷', () => {
    const sparse = toAdminRunDiagnosticDetailView({
      ...runDiagnostic,
      traceId: null,
      linkedTaskId: null,
      usage: {},
      contextBudget: {},
      timeline: [],
      dataGaps: ['TRACE_ID_NOT_PERSISTED'],
    })
    renderRunWorkspace({ selectedRunId: sparse.id, detail: sparse })
    expect(screen.getByText('历史 Run 未持久化 Trace ID，无法关联完整链路。')).toBeInTheDocument()
    expect(screen.getAllByText('—').length).toBeGreaterThan(5)
    expect(document.body).not.toHaveTextContent(/https?:\/\/|prompt-secret|contextRef-secret|toolInput-secret|providerResponse-secret/i)
  })
})

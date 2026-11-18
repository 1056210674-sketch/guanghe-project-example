import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  getAdminAgentVersion,
  listAdminAgentVersionEvaluations,
  listAdminAgentVersionRuns,
  listAdminAgentVersions,
  type AdminAgentEvaluationPage,
  type AdminAgentRuntimeSnapshot,
  type AdminAgentVersionDetail,
  type AdminAgentVersionRun,
  type AdminAgentVersionSummary,
} from '../api/adminAgentVersionsApi'
import { clearStoredAuthToken, storeAuthToken } from '../auth/tokenStorage'
import {
  AdminAgentVersionsWorkspace,
  type AdminAgentVersionsWorkspaceProps,
} from '../features/admin-observability/AdminAgentVersionsWorkspace'
import { useAuthStore } from '../stores/authStore'
import {
  adminFixture,
  jsonResponse,
  testAccessToken,
  testExpiresAt,
  testTraceId,
  userFixture,
} from './fixtures/auth'
import { renderApp } from './renderApp'

const availableRuntime: AdminAgentRuntimeSnapshot = {
  availability: 'AVAILABLE',
  reasonCode: null,
  serviceVersion: 'service-2',
  bundleVersion: 'bundle-current',
  bundleHash: 'bundle-hash-current',
  promptHash: 'prompt-hash-current',
  gitCommit: 'abcdef0',
  modelGateway: {
    mode: 'REAL',
    provider: 'DEEPSEEK',
    model: 'deepseek-chat',
    status: 'AVAILABLE',
    reasonCode: null,
  },
  profiles: [{ profileCode: 'ARTICLE_WRITER', profileVersion: '2', taskTypes: ['ARTICLE'] }],
  providers: [{ code: 'DEEPSEEK', status: 'AVAILABLE', model: 'deepseek-chat' }],
}

const unavailableRuntime: AdminAgentRuntimeSnapshot = {
  availability: 'UNAVAILABLE',
  reasonCode: 'TIMEOUT',
  serviceVersion: null,
  bundleVersion: null,
  bundleHash: null,
  promptHash: null,
  gitCommit: null,
  modelGateway: null,
  profiles: [],
  providers: [],
}

const currentSummary: AdminAgentVersionSummary = {
  bundleVersion: 'bundle-current',
  status: 'CURRENT',
  firstObservedAt: '2026-09-20T08:00:00Z',
  lastObservedAt: '2026-09-21T08:00:00Z',
  runCount: 3,
}

const observedSummary: AdminAgentVersionSummary = {
  bundleVersion: 'bundle-observed',
  status: 'OBSERVED',
  firstObservedAt: '2026-09-01T08:00:00Z',
  lastObservedAt: '2026-09-22T09:00:00Z',
  runCount: 8,
}

const currentDetail: AdminAgentVersionDetail = {
  version: currentSummary,
  currentRuntime: availableRuntime,
  evaluationAvailability: 'NOT_REGISTERED',
  dataGaps: ['EVALUATIONS_NOT_REGISTERED'],
}

const evaluationPage: AdminAgentEvaluationPage = {
  availability: 'NOT_REGISTERED',
  results: { items: [], page: 1, size: 20, total: 0, hasNext: false },
}

const taskRun: AdminAgentVersionRun = {
  executionId: '101',
  source: 'TASK',
  parentId: '201',
  taskType: 'ARTICLE',
  status: 'SUCCEEDED',
  stage: 'COMPLETED',
  provider: 'DEEPSEEK',
  model: 'deepseek-chat',
  stableErrorCode: null,
  startedAt: '2026-09-21T08:00:00Z',
  finishedAt: '2026-09-21T08:00:02.410Z',
  durationMs: 2410,
}

const workflowRun: AdminAgentVersionRun = {
  ...taskRun,
  executionId: '102',
  source: 'WORKFLOW',
  parentId: '202',
  taskType: 'WORKFLOW',
  status: 'FAILED',
  stage: null,
  provider: null,
  model: null,
  stableErrorCode: 'WORKFLOW_FAILED',
  durationMs: 1000,
}

function success(data: unknown) {
  return jsonResponse({ code: 'OK', message: 'success', traceId: testTraceId, data })
}

function workspaceProps(
  overrides: Partial<AdminAgentVersionsWorkspaceProps> = {},
): AdminAgentVersionsWorkspaceProps {
  return {
    versions: {
      runtime: availableRuntime,
      items: [observedSummary, currentSummary],
      page: 1,
      pageSize: 20,
      total: 2,
      loading: false,
      error: null,
    },
    onOpenVersion: vi.fn(),
    onRefresh: vi.fn(),
    ...overrides,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  clearStoredAuthToken()
  useAuthStore.setState({ status: 'anonymous', user: null, error: null })
})

describe('管理端 Agent 版本接口契约', () => {
  it('四个只读 GET 使用真实路径、分页参数、编码后的 Bundle 与 Bearer 认证', async () => {
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async () => success({}))
    vi.stubGlobal('fetch', fetchMock)

    await listAdminAgentVersions({ page: 2, size: 20 })
    await getAdminAgentVersion('bundle:1')
    await listAdminAgentVersionRuns('bundle:1', { page: 3, size: 10 })
    await listAdminAgentVersionEvaluations('bundle:1', { page: 1, size: 20 })

    expect(fetchMock.mock.calls.map(([url]) => String(url))).toEqual([
      '/api/v1/admin/agent-versions?page=2&size=20',
      '/api/v1/admin/agent-versions/bundle%3A1',
      '/api/v1/admin/agent-versions/bundle%3A1/runs?page=3&size=10',
      '/api/v1/admin/agent-versions/bundle%3A1/evaluations?page=1&size=20',
    ])
    for (const [, init] of fetchMock.mock.calls) {
      expect(init?.method).toBe('GET')
      expect(new Headers(init?.headers).get('Authorization')).toBe(`Bearer ${testAccessToken}`)
    }
  })
})

describe('管理端 Agent 版本观测台', () => {
  it('以 AVAILABLE runtime 展示精确运行 Bundle，并只从同 Bundle 摘要读取观测数', () => {
    render(<AdminAgentVersionsWorkspace {...workspaceProps()} />)
    const card = within(screen.getByLabelText('当前运行版本'))
    expect(card.getByText('bundle-current')).toBeInTheDocument()
    expect(card.queryByText('bundle-observed')).not.toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Agent 版本列表' })).toHaveTextContent('历史观测')
  })

  it.each([
    ['capability 不可用', unavailableRuntime, [observedSummary], '当前运行版本暂不可确认'],
  ])('%s 时不从第一条或最新观测时间推断当前版本', (_name, runtime, items, warning) => {
    render(<AdminAgentVersionsWorkspace {...workspaceProps({ versions: { runtime, items, page: 1, pageSize: 20, total: items.length, loading: false, error: null } })} />)
    expect(screen.getByText(warning)).toBeInTheDocument()
    expect(screen.queryByLabelText('当前运行版本')).not.toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Agent 版本列表' })).toBeInTheDocument()
  })

  it('翻到没有 CURRENT 摘要的后页仍展示 runtime，且不把历史首项当作当前版本', () => {
    render(<AdminAgentVersionsWorkspace {...workspaceProps({
      versions: {
        runtime: availableRuntime,
        items: [observedSummary],
        page: 2,
        pageSize: 20,
        total: 21,
        loading: false,
        error: null,
      },
    })} />)

    const card = within(screen.getByLabelText('当前运行版本'))
    expect(card.getByText('bundle-current')).toBeInTheDocument()
    expect(card.getByText('当前页未载入')).toBeInTheDocument()
    expect(card.queryByText('bundle-observed')).not.toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Agent 版本列表' })).toHaveTextContent('bundle-observed')
  })

  it('同 Bundle 摘要为 OBSERVED 时只借用观测数，不覆盖 AVAILABLE runtime 身份', () => {
    const observedSameBundle = { ...currentSummary, status: 'OBSERVED' as const, runCount: 7 }
    render(<AdminAgentVersionsWorkspace {...workspaceProps({
      versions: {
        runtime: availableRuntime,
        items: [observedSameBundle],
        page: 1,
        pageSize: 20,
        total: 1,
        loading: false,
        error: null,
      },
    })} />)

    const card = within(screen.getByLabelText('当前运行版本'))
    expect(card.getByText('bundle-current')).toBeInTheDocument()
    expect(card.getByText('7')).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Agent 版本列表' })).toHaveTextContent('历史观测')
  })

  it('区分请求失败与真实空态', () => {
    const { rerender } = render(<AdminAgentVersionsWorkspace {...workspaceProps({ versions: { items: [], page: 1, pageSize: 20, total: 0, loading: false, error: { message: '版本证据读取失败', traceId: testTraceId } } })} />)
    expect(screen.getByText('版本证据读取失败')).toBeInTheDocument()
    expect(screen.queryByText('暂无 Agent 版本观测记录')).not.toBeInTheDocument()

    rerender(<AdminAgentVersionsWorkspace {...workspaceProps({ versions: { runtime: unavailableRuntime, items: [], page: 1, pageSize: 20, total: 0, loading: false, error: null } })} />)
    expect(screen.getByText('暂无 Agent 版本观测记录')).toBeInTheDocument()
    expect(screen.queryByText('版本证据读取失败')).not.toBeInTheDocument()
  })

  it('详情到达后覆盖旧 summary，展示 TASK/WORKFLOW 与明确的未登记评测证据', () => {
    const oldSummary = { ...currentSummary, status: 'OBSERVED' as const, runCount: 1 }
    render(<AdminAgentVersionsWorkspace {...workspaceProps({
      versions: { runtime: availableRuntime, items: [oldSummary], page: 1, pageSize: 20, total: 1, loading: false, error: null },
      detail: {
        summary: oldSummary,
        data: currentDetail,
        loading: false,
        error: null,
        runs: { items: [taskRun, workflowRun], page: 1, pageSize: 20, total: 2, loading: false, error: null },
        evaluations: { data: evaluationPage, loading: false, error: null },
      },
      onCloseDetail: vi.fn(),
    })} />)

    const detail = within(screen.getByRole('dialog'))
    expect(detail.getByText('当前运行时证据')).toBeInTheDocument()
    expect(detail.getByText('3')).toBeInTheDocument()
    expect(detail.queryByText('历史执行观测')).not.toBeInTheDocument()
    expect(detail.getByText('暂无与该版本绑定的已登记评测证据')).toBeInTheDocument()
    expect(detail.queryByText(/0%/)).not.toBeInTheDocument()
    const runs = detail.getByRole('table', { name: '关联执行列表' })
    expect(runs).toHaveTextContent('任务执行')
    expect(runs).toHaveTextContent('工作流执行')
    expect(runs).toHaveTextContent('2.410 s')
    for (const invented of ['部署人', '部署时间', '文件清单', '工具预算']) {
      expect(detail.queryByText(invented)).not.toBeInTheDocument()
    }
    expect(detail.queryByRole('button', { name: /发布|回滚|评测|部署/ })).not.toBeInTheDocument()
  })

  it('历史详情只称执行观测，不展示当前运行清单', () => {
    render(<AdminAgentVersionsWorkspace {...workspaceProps({
      detail: {
        summary: observedSummary,
        data: { version: observedSummary, currentRuntime: availableRuntime, evaluationAvailability: 'NOT_REGISTERED', dataGaps: ['HISTORICAL_RUNTIME_METADATA_NOT_PERSISTED'] },
        loading: false,
        error: null,
        runs: { items: [], page: 1, pageSize: 20, total: 0, loading: false, error: null },
        evaluations: { data: evaluationPage, loading: false, error: null },
      },
      onCloseDetail: vi.fn(),
    })} />)
    const detail = within(screen.getByRole('dialog'))
    expect(detail.getByText('历史执行观测')).toBeInTheDocument()
    expect(detail.getByText('历史运行时清单未持久化')).toBeInTheDocument()
    expect(detail.queryByText('当前运行时证据')).not.toBeInTheDocument()
  })

  it('Run 分页与分区错误均由 Props 驱动，错误不伪装成空态', async () => {
    const user = userEvent.setup()
    const onRunsPageChange = vi.fn()
    render(<AdminAgentVersionsWorkspace {...workspaceProps({
      detail: {
        summary: currentSummary,
        data: currentDetail,
        loading: false,
        error: null,
        runs: { items: [taskRun], page: 1, pageSize: 20, total: 21, loading: false, error: null, onPageChange: onRunsPageChange },
        evaluations: { loading: false, error: { message: '评测证据读取失败' } },
      },
      onCloseDetail: vi.fn(),
    })} />)
    const detail = within(screen.getByRole('dialog'))
    expect(detail.getByText('评测证据读取失败')).toBeInTheDocument()
    expect(detail.queryByText('暂无与该版本绑定的已登记评测证据')).not.toBeInTheDocument()
    await user.click(detail.getByTitle('2'))
    expect(onRunsPageChange).toHaveBeenCalledWith(2)
  })
})

describe('正式 Agent 版本路由', () => {
  it('管理员从真实接口加载列表、详情、Runs 与 evaluations', async () => {
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
      const url = String(input)
      if (url === '/api/v1/admin/agent-versions?page=1&size=20') {
        return success({ currentRuntime: availableRuntime, versions: { items: [currentSummary], page: 1, size: 20, total: 1, hasNext: false } })
      }
      if (url === '/api/v1/admin/agent-versions/bundle-current') return success(currentDetail)
      if (url === '/api/v1/admin/agent-versions/bundle-current/runs?page=1&size=20') {
        return success({ items: [taskRun, workflowRun], page: 1, size: 20, total: 2, hasNext: false })
      }
      if (url === '/api/v1/admin/agent-versions/bundle-current/evaluations?page=1&size=20') return success(evaluationPage)
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)

    renderApp(['/admin/agent-versions'])
    expect(await screen.findAllByText('bundle-current')).toHaveLength(2)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '查看版本' }))
    const detail = within(await screen.findByRole('dialog'))
    expect(await detail.findByText('当前运行时证据')).toBeInTheDocument()
    expect(await detail.findByRole('table', { name: '关联执行列表' })).toHaveTextContent('工作流执行')
    expect(detail.getByText('暂无与该版本绑定的已登记评测证据')).toBeInTheDocument()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4))
    await user.click(screen.getByRole('button', { name: '刷新证据' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(8))
  })

  it('普通用户仍由管理员路由保护，且不会请求版本接口', async () => {
    useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
    const fetchMock = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', fetchMock)
    renderApp(['/admin/agent-versions'])
    expect(await screen.findByText('403 拒绝访问')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

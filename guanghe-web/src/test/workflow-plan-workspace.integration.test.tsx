import { QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntApp } from 'antd'
import type { PropsWithChildren } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../app/queryClient'
import { storeAuthToken } from '../auth/tokenStorage'
import {
  WorkflowPlanDetailDrawer,
  WorkflowPlanConfirmationDialog,
  WorkflowPlanList,
  WorkflowPlanStartDialog,
  prepareWorkflowPlanStartAttempt,
  readWorkflowPlanStartRecovery,
  toWorkflowPlanDetail,
  toWorkflowTaskResult,
  workflowPlanDetailRefreshInterval,
} from '../features/workflow-plans'
import type { WorkflowPlan } from '../api/workflowPlanApi'
import { WorkflowPlansPage } from '../pages/workflow-plans/WorkflowPlansPage'
import {
  APP_PATHS,
  APP_ROUTE_PATTERNS,
  workflowTaskResultPath,
} from '../routes/paths'
import { useAuthStore } from '../stores/authStore'

vi.mock('../layouts/UserShell', () => ({
  UserShell: ({ children }: PropsWithChildren) => children,
}))

const productionModules = import.meta.glob(
  [
    '../features/workflow-plans/**/*.{ts,tsx}',
    '../pages/workflow-plans/**/*.{ts,tsx}',
    '../api/workflowPlanApi.ts',
  ],
  { eager: true, import: 'default', query: '?raw' },
) as Record<string, string>
const productionSource = Object.values(productionModules).join('\n')

const plan: WorkflowPlan = {
  id: '90071992547409931',
  conversationId: '90071992547409930',
  goalSummary: '完成跨工具新品推广计划',
  status: 'WAITING_CONFIRMATION',
  version: 3,
  stepCount: 2,
  totalQuotedPoints: 18,
  maxPointCost: 30,
  confirmationExpiresAt: '2099-09-10T10:00:00Z',
  createdAt: '2026-09-10T08:00:00Z',
  updatedAt: '2026-09-10T08:10:00Z',
  spaceId: '90071992547409920',
  productId: '90071992547409921',
  budget: {
    maxSteps: 6,
    maxReplans: 1,
    maxModelCalls: 8,
    maxToolCalls: 12,
    maxProviderCalls: 4,
    maxPointCost: 30,
    deadlineAt: '2026-09-11T08:00:00Z',
  },
  steps: [
    {
      stepId: 'step-2', ordinal: 2, type: 'PREPARE', toolName: 'task_draft.propose',
      toolVersion: '1', risk: 'PREPARE', dependsOn: ['step-1'],
      successCriteria: ['草稿报价可确认'], inputRefs: ['analysis'], outputRef: 'draft',
      taskDraft: { draftId: '90071992547409940', taskType: 'IMAGE', pricingVersionId: '90071992547409941', quotedPoints: 18 },
      status: 'PENDING', taskId: null, taskStatus: null, taskResultRef: null,
    },
    {
      stepId: 'step-1', ordinal: 1, type: 'ANALYZE', toolName: 'analysis.competitor.compare',
      toolVersion: '1', risk: 'ANALYZE', dependsOn: [], successCriteria: ['完成竞品差异汇总'],
      inputRefs: ['catalog'], outputRef: 'analysis', taskDraft: null, status: 'PENDING',
      taskId: null, taskStatus: null, taskResultRef: null,
    },
  ],
  confirmation: { planVersion: 3, quotedPoints: 18, maxPointCost: 30, expiresAt: '2099-09-10T10:00:00Z', consumptionEnabled: false },
  events: [
    { sequence: 1, eventType: 'PLAN_PROPOSED', fromStatus: null, toStatus: 'DRAFT', stableErrorCode: null, occurredAt: '2026-09-10T08:00:00Z' },
    { sequence: 2, eventType: 'PLAN_WAITING_CONFIRMATION', fromStatus: 'DRAFT', toStatus: 'WAITING_CONFIRMATION', stableErrorCode: null, occurredAt: '2026-09-10T08:10:00Z' },
  ],
  abandonedAt: null,
  executionEnabled: false,
  executionStarted: false,
  lastStableError: null,
}

describe('组合计划工作区', () => {
  it('详情严格按后端步骤顺序展示并明确未确认边界', () => {
    const view = toWorkflowPlanDetail(plan)
    render(
      <WorkflowPlanDetailDrawer
        open plan={view} loading={false} error={null} abandonError={null} abandoning={false}
        confirmationResult={null} startResult={null} onClose={vi.fn()} onRetry={vi.fn()}
        onRequestConfirmation={vi.fn()} onRequestStart={vi.fn()} onAbandon={vi.fn()}
        onOpenTask={vi.fn()} onOpenTaskResult={vi.fn()}
      />,
    )

    expect(screen.getByText('计划已生成，尚未确认、未执行、未冻结积分')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认计划' })).toBeEnabled()
    const cards = screen.getAllByText(/准备任务|分析/)
    expect(cards[0]).toHaveTextContent('准备任务')
    expect(screen.getByText('依赖：step-1')).toBeInTheDocument()
    expect(screen.getByText('草稿编号')).toBeInTheDocument()
    expect(screen.getByText('计划已生成')).toBeInTheDocument()
  })

  it('放弃操作只回传 planId 与 version，已放弃计划为只读', () => {
    const onAbandon = vi.fn()
    const { rerender } = render(
      <WorkflowPlanDetailDrawer
        open plan={toWorkflowPlanDetail(plan)} loading={false} error={null} abandonError={null} abandoning={false}
        confirmationResult={null} startResult={null} onClose={vi.fn()} onRetry={vi.fn()}
        onRequestConfirmation={vi.fn()} onRequestStart={vi.fn()} onAbandon={onAbandon}
        onOpenTask={vi.fn()} onOpenTaskResult={vi.fn()}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: '放弃计划' }))
    expect(onAbandon).toHaveBeenCalledWith(plan.id, plan.version)

    rerender(
      <WorkflowPlanDetailDrawer
        open plan={toWorkflowPlanDetail({ ...plan, status: 'ABANDONED', version: 4, abandonedAt: '2026-09-10T09:00:00Z' })}
        loading={false} error={null} abandonError={null} abandoning={false}
        confirmationResult={null} startResult={null} onClose={vi.fn()} onRetry={vi.fn()}
        onRequestConfirmation={vi.fn()} onRequestStart={vi.fn()} onAbandon={onAbandon}
        onOpenTask={vi.fn()} onOpenTaskResult={vi.fn()}
      />,
    )
    expect(screen.queryByRole('button', { name: '放弃计划' })).not.toBeInTheDocument()
    expect(screen.getByText('该计划已放弃')).toBeInTheDocument()
  })

  it('执行能力关闭时保持只读，开放时才允许请求开始', () => {
    const onRequestStart = vi.fn()
    render(
      <WorkflowPlanDetailDrawer
        open
        plan={{ ...toWorkflowPlanDetail(plan), status: 'CONFIRMED', confirmedAt: '2099/09/10 18:01:00' }}
        loading={false}
        error={null}
        abandonError={null}
        confirmationResult={null}
        startResult={null}
        abandoning={false}
        onClose={vi.fn()}
        onRetry={vi.fn()}
        onRequestConfirmation={vi.fn()}
        onRequestStart={onRequestStart}
        onAbandon={vi.fn()}
        onOpenTask={vi.fn()}
        onOpenTaskResult={vi.fn()}
      />,
    )
    expect(screen.getAllByText('暂未开放')).not.toHaveLength(0)
    expect(screen.getByRole('button', { name: '暂不可开始执行' })).toBeDisabled()
    expect(onRequestStart).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: '放弃计划' })).not.toBeInTheDocument()
  })

  it('执行确认展示计划级授权，开放时只向上回传开始意图', () => {
    const onStart = vi.fn()
    const executable = { ...toWorkflowPlanDetail(plan), status: 'CONFIRMED' as const, executionEnabled: true }
    const onRequestStart = vi.fn()
    const { unmount } = render(
      <WorkflowPlanDetailDrawer
        open plan={executable} loading={false} error={null} abandonError={null} abandoning={false}
        confirmationResult={null} startResult={null} onClose={vi.fn()} onRetry={vi.fn()}
        onRequestConfirmation={vi.fn()} onRequestStart={onRequestStart} onAbandon={vi.fn()}
        onOpenTask={vi.fn()} onOpenTaskResult={vi.fn()}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: '开始执行' }))
    expect(onRequestStart).toHaveBeenCalledWith(plan.id, plan.version)
    unmount()

    render(<WorkflowPlanStartDialog open plan={executable} starting={false} error={null} onClose={vi.fn()} onStart={onStart} />)
    expect(screen.getByText('开始执行将冻结整份计划授权')).toBeInTheDocument()
    expect(screen.getByText('30 积分')).toBeInTheDocument()
    expect(screen.getByText(/依赖：step-1/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '确认开始执行' }))
    expect(onStart).toHaveBeenCalledTimes(1)
  })

  it('执行恢复仅保存隔离后的安全幂等字段，同一输入复用，不同版本隔离', async () => {
    localStorage.clear()
    const first = await prepareWorkflowPlanStartAttempt('owner-a', plan.id, 3)
    const retry = await prepareWorkflowPlanStartAttempt('owner-a', plan.id, 3)
    const changed = await prepareWorkflowPlanStartAttempt('owner-a', plan.id, 4)
    const otherOwner = await prepareWorkflowPlanStartAttempt('owner-b', plan.id, 3)
    expect(retry.idempotencyKey).toBe(first.idempotencyKey)
    expect(changed.idempotencyKey).not.toBe(first.idempotencyKey)
    expect(otherOwner.idempotencyKey).not.toBe(first.idempotencyKey)
    expect(readWorkflowPlanStartRecovery('owner-a')).toMatchObject({ planId: plan.id, planVersion: 4 })
    const persisted = Object.values(localStorage).join(' ')
    expect(persisted).not.toMatch(/jwt|bearer|token|https?:|provider|signed/i)
  })

  it('仅执行中状态轮询，等待输入和终态行为明确', () => {
    expect(workflowPlanDetailRefreshInterval({ status: 'QUEUED' })).toBe(5_000)
    expect(workflowPlanDetailRefreshInterval({ status: 'RUNNING' })).toBe(5_000)
    expect(workflowPlanDetailRefreshInterval({ status: 'WAITING_INPUT' })).toBe(5_000)
    expect(workflowPlanDetailRefreshInterval({ status: 'RUNNING' }, 2)).toBe(20_000)
    expect(workflowPlanDetailRefreshInterval({ status: 'RUNNING' }, 20)).toBe(30_000)
    expect(workflowPlanDetailRefreshInterval({ status: 'SUCCEEDED' })).toBe(false)
    expect(workflowPlanDetailRefreshInterval({ status: 'FAILED' })).toBe(false)
    expect(workflowPlanDetailRefreshInterval({ status: 'RECONFIRMATION_REQUIRED' })).toBe(false)
  })

  it('任务投影保持 Java 状态，不从步骤状态或计划状态推断', () => {
    const taskStatuses = [
      'QUEUED',
      'RUNNING',
      'RETRY_WAIT',
      'SUCCEEDED',
      'PARTIAL',
      'FAILED',
      'SKIPPED',
      'SYSTEM_TERMINATED',
    ] as const
    const view = toWorkflowPlanDetail({
      ...plan,
      status: 'RUNNING',
      steps: taskStatuses.map((taskStatus, index) => ({
        ...plan.steps[0],
        stepId: `prepare-${index}`,
        ordinal: index + 1,
        status: 'RUNNING' as const,
        taskId: String(90071992547410000n + BigInt(index)),
        taskStatus,
        taskResultRef: null,
      })),
    })

    expect(view.steps.map((step) => step.taskStatus)).toEqual(taskStatuses)
    expect(toWorkflowPlanDetail(plan).steps[0]).toMatchObject({
      taskId: undefined,
      taskStatus: undefined,
      taskResult: undefined,
    })
  })

  it('四类交付引用只为合法 Snowflake 生成安全路由', () => {
    const id = '90071992547409988'
    const expectations = [
      ['task', `/app/tasks/${id}`],
      ['asset', `/app/assets?asset=${id}`],
      ['geo-report', `/app/geo/reports/${id}`],
      ['article', `/app/articles?article=${id}`],
    ] as const

    expectations.forEach(([kind, path]) => {
      const result = toWorkflowTaskResult(`${kind}:${id}`)
      expect(result).toEqual({ raw: `${kind}:${id}`, kind, id })
      expect(workflowTaskResultPath(result?.kind, result?.id)).toBe(path)
    })
    expect(toWorkflowTaskResult('provider:secret-id')).toEqual({ raw: 'provider:secret-id' })
    expect(toWorkflowTaskResult('asset:not-a-snowflake')).toEqual({ raw: 'asset:not-a-snowflake' })
    expect(workflowTaskResultPath('asset', 'not-a-snowflake')).toBeUndefined()
  })

  it('准备步骤分别展示任务、步骤和计划状态，交付操作只回传安全意图', () => {
    const onOpenTask = vi.fn()
    const onOpenTaskResult = vi.fn()
    const running = toWorkflowPlanDetail({
      ...plan,
      status: 'RUNNING',
      executionEnabled: true,
      executionStarted: true,
      steps: [{
        ...plan.steps[0],
        status: 'FAILED',
        taskId: '90071992547409980',
        taskStatus: 'PARTIAL',
        taskResultRef: 'asset:90071992547409981',
      }],
    })
    const common = {
      open: true,
      loading: false,
      error: null,
      abandonError: null,
      confirmationResult: null,
      startResult: null,
      abandoning: false,
      onClose: vi.fn(),
      onRetry: vi.fn(),
      onRequestConfirmation: vi.fn(),
      onRequestStart: vi.fn(),
      onAbandon: vi.fn(),
      onOpenTask,
      onOpenTaskResult,
    }
    const { rerender } = render(<WorkflowPlanDetailDrawer {...common} plan={running} />)

    expect(screen.getByText('计划执行中')).toBeInTheDocument()
    expect(screen.getByText('失败')).toBeInTheDocument()
    expect(screen.getByText('部分完成')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '查看任务详情' }))
    fireEvent.click(screen.getByRole('button', { name: '打开交付结果' }))
    expect(onOpenTask).toHaveBeenCalledWith('90071992547409980')
    expect(onOpenTaskResult).toHaveBeenCalledWith({
      raw: 'asset:90071992547409981',
      kind: 'asset',
      id: '90071992547409981',
    })

    rerender(<WorkflowPlanDetailDrawer
      {...common}
      plan={{
        ...running,
        steps: [{
          ...running.steps[0],
          taskResult: { raw: 'unknown:90071992547409981' },
        }],
      }}
    />)
    expect(screen.queryByRole('button', { name: '打开交付结果' })).not.toBeInTheDocument()
    expect(screen.getByText('当前交付引用无法安全打开')).toBeInTheDocument()
    expect(screen.queryByText('unknown:90071992547409981')).not.toBeInTheDocument()
  })

  it('等待输入、重新确认和失败终态均为只读并展示用户可理解的信息', () => {
    const failedPlan = toWorkflowPlanDetail({
      ...plan,
      status: 'FAILED',
      executionEnabled: true,
      executionStarted: true,
      lastStableError: 'WORKFLOW_STEP_FAILED',
      steps: plan.steps.map((step, index) => ({ ...step, status: index === 0 ? 'FAILED' as const : 'SKIPPED' as const })),
      events: [...plan.events, {
        sequence: 3, eventType: 'PLAN_FAILED', fromStatus: 'RUNNING', toStatus: 'FAILED',
        stableErrorCode: 'WORKFLOW_STEP_FAILED', occurredAt: '2026-09-10T09:00:00Z',
      }],
    })
    const common = {
      open: true, loading: false, error: null, abandonError: null, abandoning: false,
      confirmationResult: null, startResult: null, onClose: vi.fn(), onRetry: vi.fn(),
      onRequestConfirmation: vi.fn(), onRequestStart: vi.fn(), onAbandon: vi.fn(),
      onOpenTask: vi.fn(), onOpenTaskResult: vi.fn(),
    }
    const { rerender } = render(<WorkflowPlanDetailDrawer {...common} plan={failedPlan} />)
    expect(screen.getAllByText('计划执行失败').length).toBeGreaterThan(0)
    expect(screen.getByText('请查看失败步骤和关联任务的最新状态。')).toBeInTheDocument()
    expect(
      screen.getAllByText('任务执行未完成，请稍后重试；如重复失败，请联系支持。').length,
    ).toBeGreaterThan(0)
    expect(screen.queryByText('WORKFLOW_STEP_FAILED')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /开始执行|确认计划/ })).not.toBeInTheDocument()

    rerender(<WorkflowPlanDetailDrawer {...common} plan={{ ...failedPlan, status: 'WAITING_INPUT' }} />)
    expect(screen.getByText('计划等待补充输入')).toBeInTheDocument()
    rerender(<WorkflowPlanDetailDrawer {...common} plan={{ ...failedPlan, status: 'RECONFIRMATION_REQUIRED' }} />)
    expect(screen.getByText('旧确认授权不会被复用；请刷新详情后确认最新信息。')).toBeInTheDocument()
    rerender(<WorkflowPlanDetailDrawer {...common} plan={{ ...failedPlan, status: 'SUCCEEDED' }} />)
    expect(screen.getByText('组合任务已完成')).toBeInTheDocument()
    expect(screen.getByText('暂无可用交付物')).toBeInTheDocument()
  })

  it('确认弹窗只展示脱敏权威报价，并将授权上限固定为总报价', () => {
    const onConfirm = vi.fn()
    render(
      <WorkflowPlanConfirmationDialog
        open
        plan={toWorkflowPlanDetail(plan)}
        preview={{
          planId: plan.id,
          planVersion: plan.version,
          totalQuotedPoints: 18,
          maxPointCost: 30,
          expiresAt: '2099/09/10 18:00:00',
          executionEnabled: false,
        }}
        loading={false}
        confirming={false}
        error={null}
        confirmEnabled
        onClose={vi.fn()}
        onRetryPreview={vi.fn()}
        onConfirm={onConfirm}
      />,
    )
    expect(screen.getByText('确认后仅记录授权，尚不执行、不冻结积分')).toBeInTheDocument()
    expect(screen.getByText('本次确认不会创建任务、调用服务或改变积分余额。确认后返回计划详情，执行能力开放时可再明确开始执行。')).toBeInTheDocument()
    expect(screen.queryByText(/执行能力将在后续阶段接入/)).not.toBeInTheDocument()
    expect(screen.getByText('18 积分（固定等于计划总报价）')).toBeInTheDocument()
    expect(screen.queryByText(/token/i)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '明确确认计划' }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })

  it.each([false, true])('执行开放=%s：先取权威预览，重复确认只授权一次，不自动开始执行', async (executionEnabled) => {
    localStorage.clear()
    storeAuthToken({ accessToken: 'workflow-page-token', expiresAt: '2099-01-01T00:00:00Z' })
    useAuthStore.setState({
      status: 'authenticated',
      user: { id: 'user-workflow', username: 'user', displayName: null, role: 'USER', status: 'ACTIVE' },
      error: null,
    })
    let confirmed = false
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      if (url.endsWith('/confirmation-preview')) {
        return new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: '0123456789abcdef0123456789abcdef', data: {
          planId: plan.id,
          planVersion: plan.version,
          totalQuotedPoints: 18,
          maxPointCost: 30,
          expiresAt: '2099-09-10T10:00:00Z',
          executionEnabled: false,
          confirmationToken: 'page-only-confirmation-secret',
        } }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      if (url.endsWith('/confirm')) {
        confirmed = true
        return new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: '0123456789abcdef0123456789abcdef', data: {
          planId: plan.id,
          planVersion: plan.version,
          status: 'CONFIRMED',
          totalQuotedPoints: 18,
          authorizedMaxPoints: 18,
          confirmedAt: '2099-09-10T09:00:00Z',
          executionEnabled: false,
          executionStarted: false,
        } }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      if (url.includes('/api/v1/workflow-plans?')) {
        const item = confirmed ? { ...plan, executionEnabled, status: 'CONFIRMED' as const } : { ...plan, executionEnabled }
        return new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: '0123456789abcdef0123456789abcdef', data: {
          items: [item], page: 1, size: 20, total: 1, hasNext: false,
        } }), { status: 200 })
      }
      const detailPlan = confirmed ? {
        ...plan,
        executionEnabled,
        status: 'CONFIRMED' as const,
        events: [...plan.events, {
          sequence: 3,
          eventType: 'PLAN_CONFIRMED' as const,
          fromStatus: 'WAITING_CONFIRMATION' as const,
          toStatus: 'CONFIRMED' as const,
          stableErrorCode: null,
          occurredAt: '2099-09-10T09:00:00Z',
        }],
      } : { ...plan, executionEnabled }
      expect(init?.method).toBe('GET')
      return new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: '0123456789abcdef0123456789abcdef', data: detailPlan }), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <AntApp>
        <QueryClientProvider client={createQueryClient()}>
          <MemoryRouter initialEntries={[`/app/workflow-plans/${plan.id}`]}>
            <Routes>
              <Route path={APP_ROUTE_PATTERNS.workflowPlanDetail} element={<WorkflowPlansPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </AntApp>,
    )

    fireEvent.click(await screen.findByRole('button', { name: '确认计划' }))
    expect(await screen.findByText('确认后仅记录授权，尚不执行、不冻结积分')).toBeInTheDocument()
    const confirmButton = screen.getByRole('button', { name: '明确确认计划' })
    fireEvent.click(confirmButton)
    fireEvent.click(confirmButton)

    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/confirm'))).toHaveLength(1))
    if (executionEnabled) expect(await screen.findByRole('button', { name: '开始执行' })).toBeEnabled()
    else expect(await screen.findByRole('button', { name: '暂不可开始执行' })).toBeDisabled()
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/start'))).toHaveLength(0)
    expect(screen.queryByText(/执行中|已冻结/)).not.toBeInTheDocument()
    const confirmInit = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/confirm'))?.[1]
    expect(JSON.parse(String(confirmInit?.body))).toMatchObject({ authorizedMaxPoints: 18 })
    expect(new Headers(confirmInit?.headers).get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/i)
  })

  it('执行开放时双击只开始一次，200 后立即显示队列状态和执行序号', async () => {
    localStorage.clear()
    storeAuthToken({ accessToken: 'workflow-start-token', expiresAt: '2099-01-01T00:00:00Z' })
    useAuthStore.setState({
      status: 'authenticated',
      user: { id: 'user-start', username: 'user', displayName: null, role: 'USER', status: 'ACTIVE' },
      error: null,
    })
    const executable = { ...plan, status: 'CONFIRMED' as const, executionEnabled: true }
    let resolveStart!: (value: Response) => void
    const pendingStart = new Promise<Response>((resolve) => { resolveStart = resolve })
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const url = String(input)
      if (url.endsWith('/start')) return pendingStart
      if (url.includes('/api/v1/workflow-plans?')) {
        return new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: '0123456789abcdef0123456789abcdef', data: {
          items: [executable], page: 1, size: 20, total: 1, hasNext: false,
        } }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      return new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: '0123456789abcdef0123456789abcdef', data: executable }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <AntApp>
        <QueryClientProvider client={createQueryClient()}>
          <MemoryRouter initialEntries={[`/app/workflow-plans/${plan.id}`]}>
            <Routes><Route path={APP_ROUTE_PATTERNS.workflowPlanDetail} element={<WorkflowPlansPage />} /></Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </AntApp>,
    )

    fireEvent.click(await screen.findByRole('button', { name: '开始执行' }))
    const startButton = await screen.findByRole('button', { name: '确认开始执行' })
    fireEvent.click(startButton)
    fireEvent.click(startButton)
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/start'))).toHaveLength(1))
    resolveStart(new Response(JSON.stringify({ code: 'OK', message: 'success', traceId: '0123456789abcdef0123456789abcdef', data: {
      planId: plan.id, planVersion: plan.version, status: 'QUEUED',
      executionEnabled: true, executionStarted: true, executionNo: 7,
    } }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    expect((await screen.findAllByText('计划已进入执行队列')).length).toBeGreaterThan(0)
    expect(screen.getByText('7')).toBeInTheDocument()
    const startInit = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/start'))?.[1]
    expect(JSON.parse(String(startInit?.body))).toEqual({ planVersion: plan.version })
    expect(new Headers(startInit?.headers).get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/i)
  })

  it('列表支持真实 Empty、Error 与分页展示能力', () => {
    const { rerender } = render(
      <WorkflowPlanList
        plans={[]} loading={false} error={null} pagination={{ current: 1, pageSize: 20, total: 0 }}
        onStatusChange={vi.fn()} onPageChange={vi.fn()} onView={vi.fn()} onRetry={vi.fn()}
      />,
    )
    expect(screen.getByText('还没有组合计划')).toBeInTheDocument()
    rerender(
      <WorkflowPlanList
        plans={[]} loading={false} error={{ message: '读取失败', traceId: 'trace-1' }} pagination={{ current: 1, pageSize: 20, total: 0 }}
        onStatusChange={vi.fn()} onPageChange={vi.fn()} onView={vi.fn()} onRetry={vi.fn()}
      />,
    )
    expect(screen.getByText('读取失败')).toBeInTheDocument()
    expect(screen.queryByText(/trace-1/)).not.toBeInTheDocument()
  })

  it('路由受 USER 守卫覆盖且生产模块无 Fixture、Mock、Python 或内部接口', () => {
    expect(APP_PATHS.workflowPlans).toBe('/app/workflow-plans')
    expect(APP_ROUTE_PATTERNS.workflowPlanDetail).toBe('/app/workflow-plans/:planId')
    expect(productionSource).not.toMatch(/src\/test|fixtures|mock/i)
    expect(productionSource).not.toMatch(/8000|\/internal\/v1|sessionStorage|console\./i)
    expect(productionSource).not.toMatch(/\/api\/v1\/tasks|provider:|aliyuncs|oss key/i)
    expect(productionSource).not.toMatch(/localStorage[^\n]*taskResultRef|taskResultRef[^\n]*localStorage/i)
    expect(productionSource).toContain('/api/v1/workflow-plans')
  })
})

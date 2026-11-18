import { render, screen, waitFor, within } from '@testing-library/react'
import { useState } from 'react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  AdminTaskServiceWorkspace,
  type AdminServiceSummary,
  type AdminTaskStatus,
  type AdminTaskSummary,
} from '../features/admin-tasks'
import {
  AdminUserManagementWorkspace,
  OneTimePasswordModal,
  type AdminUserStatus,
  type AdminUserSummary,
} from '../features/admin-users'
import {
  createAdminUser,
  disableAdminUser,
  resetAdminUserPassword,
  restoreAdminUser,
} from '../api/adminUserApi'
import {
  PREVIEW_ADMIN_SERVICES,
  PREVIEW_ADMIN_TASKS,
  PREVIEW_ADMIN_USERS,
} from '../pages/admin/previewData'
import { APP_PATHS } from '../routes/paths'
import { DEV_PREVIEW_PATHS } from '../routes/devPreviewPaths'
import { useAuthStore } from '../stores/authStore'
import { clearStoredAuthToken, storeAuthToken } from '../auth/tokenStorage'
import { adminFixture, jsonResponse, testAccessToken, testExpiresAt, testTraceId, userFixture } from './fixtures/auth'
import { renderApp } from './renderApp'

function createTask(
  status: AdminTaskStatus,
  id = `task-${status.toLocaleLowerCase()}`,
): AdminTaskSummary {
  return {
    id,
    type: '测试任务',
    userMasked: '138****0000',
    status,
    createdAt: '2026-09-07 12:00:00',
    traceId: `trace-${id}`,
  }
}

function createUser(
  status: AdminUserStatus,
  id = `user-${status.toLocaleLowerCase()}`,
): AdminUserSummary {
  return {
    id,
    username: id,
    phoneMasked: '138****0000',
    businessSpaceCount: 1,
    productCount: 1,
    availablePoints: 100,
    status,
    registeredAt: '2026-09-07 12:00:00',
  }
}

async function openOnlyTaskDetail(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: '诊断详情' }))
}

async function openOnlyUserDetail(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: '查看' }))
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  clearStoredAuthToken()
  useAuthStore.setState({ status: 'anonymous', user: null, error: null })
})

describe('管理员用户管理', () => {
  it('正式路由接入真实分页列表并激活用户管理导航', async () => {
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({
      code: 'OK', message: 'success', traceId: testTraceId,
      data: { items: [{ id: '9001', phone: '+8613800138000', displayName: '新用户', status: 'ACTIVE', credentialState: 'MUST_CHANGE_PASSWORD', tokenVersion: 1, createdAt: '2026-09-11T00:00:00Z' }], page: 1, size: 20, total: 1, hasNext: false },
    })))
    useAuthStore.setState({
      status: 'authenticated',
      user: adminFixture,
      error: null,
    })
    renderApp([APP_PATHS.adminUsers])

    expect(await screen.findByRole('heading', { name: '用户管理' })).toBeInTheDocument()
    expect(await screen.findByText('+8613800138000')).toBeInTheDocument()
    expect(screen.getByText('首次登录待改密')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '用户管理' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(screen.getByLabelText('搜索用户')).toBeEnabled()
  })

  it('开户、重置、停用和恢复使用冻结 Browser API 路径', async () => {
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    const user = { id: '9001', phone: '+8613800138000', displayName: '新用户', status: 'ACTIVE', credentialState: 'MUST_CHANGE_PASSWORD', tokenVersion: 1 }
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ code: 'OK', message: 'success', traceId: testTraceId, data: { user, temporaryPassword: 'temporary-password' } }, 201))
      .mockResolvedValueOnce(jsonResponse({ code: 'OK', message: 'success', traceId: testTraceId, data: { userId: '9001', credentialState: 'MUST_CHANGE_PASSWORD', temporaryPassword: 'another-temporary' } }))
      .mockResolvedValueOnce(jsonResponse({ code: 'OK', message: 'success', traceId: testTraceId, data: { ...user, status: 'DISABLED' } }))
      .mockResolvedValueOnce(jsonResponse({ code: 'OK', message: 'success', traceId: testTraceId, data: user }))
    vi.stubGlobal('fetch', fetchMock)

    await createAdminUser({ phone: '+8613800138000', displayName: '新用户', initialPointGrant: 0 })
    await resetAdminUserPassword('9001')
    await disableAdminUser('9001')
    await restoreAdminUser('9001')

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/admin/users',
      '/api/v1/admin/users/9001/password-reset',
      '/api/v1/admin/users/9001/disable',
      '/api/v1/admin/users/9001/restore',
    ])
  })

  it('一次性密码仅主动复制，关闭后从 DOM 与浏览器持久状态消失', async () => {
    const password = 'one-time-password-secret'
    const originalLocalStorage = JSON.stringify(window.localStorage)
    const originalSessionStorage = JSON.stringify(window.sessionStorage)
    const writeText = vi.fn().mockResolvedValue(undefined)
    function Harness() {
      const [credential, setCredential] = useState<{ userId: string; label: string; temporaryPassword: string } | undefined>({ userId: '9001', label: '+86138****8000', temporaryPassword: password })
      return <OneTimePasswordModal credential={credential} onClose={() => setCredential(undefined)} />
    }
    const user = userEvent.setup()
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    render(<Harness />)
    expect(screen.getByText(password)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /复制临时密码/ }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(password))
    await user.click(screen.getByRole('button', { name: '我已安全保存并关闭' }))
    await waitFor(() => expect(screen.queryByText(password)).not.toBeInTheDocument())
    expect(JSON.stringify(window.localStorage)).toBe(originalLocalStorage)
    expect(JSON.stringify(window.sessionStorage)).toBe(originalSessionStorage)
    expect(JSON.stringify(window.localStorage)).not.toContain(password)
    expect(JSON.stringify(window.sessionStorage)).not.toContain(password)
    expect(window.location.href).not.toContain(password)
  })

  it('开发预览支持搜索和账号状态组合筛选', async () => {
    const user = userEvent.setup()
    render(<AdminUserManagementWorkspace users={PREVIEW_ADMIN_USERS} preview />)

    await user.type(screen.getByLabelText('搜索用户'), '003')
    await user.click(screen.getByLabelText('账号状态'))
    const frozenOptions = await screen.findAllByText('已冻结')
    await user.click(frozenOptions[frozenOptions.length - 1])

    const table = screen.getByRole('table', { name: '用户列表' })
    expect(within(table).getByText('demo-user-003')).toBeInTheDocument()
    expect(within(table).queryByText('demo-user-001')).not.toBeInTheDocument()
  })

  it('用户详情隐藏敏感值且未接入回调时高风险操作禁用', async () => {
    const user = userEvent.setup()
    render(<AdminUserManagementWorkspace users={PREVIEW_ADMIN_USERS} preview />)

    const row = screen.getByText('demo-user-001').closest('tr')
    expect(row).not.toBeNull()
    await user.click(within(row as HTMLElement).getByRole('button', { name: '查看' }))

    expect(screen.getByText('demo-user-001 详情')).toBeInTheDocument()
    expect(screen.getAllByText('138****0001').length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: '补偿积分' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '冻结账号' })).toBeDisabled()
    expect(screen.queryByText(/密码|Access Key|JWT|完整 Prompt/)).not.toBeInTheDocument()
  })

  it('积分补偿和账号冻结提交结构化且去空格的审计 DTO', async () => {
    const user = userEvent.setup()
    const onCompensatePoints = vi.fn()
    const onChangeAccountStatus = vi.fn()
    render(
      <AdminUserManagementWorkspace
        users={PREVIEW_ADMIN_USERS}
        actionsEnabled
        onCompensatePoints={onCompensatePoints}
        onChangeAccountStatus={onChangeAccountStatus}
      />,
    )
    const row = screen.getByText('demo-user-001').closest('tr')
    await user.click(within(row as HTMLElement).getByRole('button', { name: '查看' }))

    await user.click(screen.getByRole('button', { name: '补偿积分' }))
    await user.type(screen.getByLabelText('补偿积分数'), '120')
    await user.type(screen.getByLabelText('补偿原因'), '  上游任务异常补偿  ')
    await user.type(screen.getByLabelText('关联任务号'), '  PREVIEW-TASK-001  ')
    await user.click(screen.getByRole('button', { name: '确认补偿' }))
    expect(onCompensatePoints).toHaveBeenCalledWith({
      userId: 'preview-user-001',
      points: 120,
      reason: '上游任务异常补偿',
      taskId: 'PREVIEW-TASK-001',
    })

    await user.click(screen.getByRole('button', { name: '冻结账号' }))
    await user.type(screen.getByLabelText('账号状态变更原因'), '  风险核查  ')
    await user.click(screen.getByRole('button', { name: '确认冻结' }))
    expect(onChangeAccountStatus).toHaveBeenCalledWith({
      userId: 'preview-user-001',
      action: 'FREEZE',
      reason: '风险核查',
    })
  })

  it('ACTIVE 用户冻结确认后变为 FROZEN 时旧确认失效', async () => {
    const user = userEvent.setup()
    const onChangeAccountStatus = vi.fn()
    const activeUser = createUser('ACTIVE', 'user-status-refresh')
    const { rerender } = render(
      <AdminUserManagementWorkspace
        users={[activeUser]}
        actionsEnabled
        onChangeAccountStatus={onChangeAccountStatus}
      />,
    )
    await openOnlyUserDetail(user)
    await user.click(screen.getByRole('button', { name: '冻结账号' }))
    await user.type(screen.getByLabelText('账号状态变更原因'), '风险核查')
    expect(screen.getByRole('button', { name: '确认冻结' })).toBeEnabled()

    rerender(
      <AdminUserManagementWorkspace
        users={[{ ...activeUser, status: 'FROZEN' }]}
        actionsEnabled
        onChangeAccountStatus={onChangeAccountStatus}
      />,
    )

    const staleConfirm = screen.queryByRole('button', { name: '确认冻结' })
    if (staleConfirm) {
      expect(staleConfirm).toBeDisabled()
    }
    expect(onChangeAccountStatus).not.toHaveBeenCalled()
  })

  it('ACTIVE 用户冻结确认后变为 DISABLED 时旧确认失效', async () => {
    const user = userEvent.setup()
    const onChangeAccountStatus = vi.fn()
    const activeUser = createUser('ACTIVE', 'user-disabled-refresh')
    const { rerender } = render(
      <AdminUserManagementWorkspace
        users={[activeUser]}
        actionsEnabled
        onChangeAccountStatus={onChangeAccountStatus}
      />,
    )
    await openOnlyUserDetail(user)
    await user.click(screen.getByRole('button', { name: '冻结账号' }))
    await user.type(screen.getByLabelText('账号状态变更原因'), '停用核查')

    rerender(
      <AdminUserManagementWorkspace
        users={[{ ...activeUser, status: 'DISABLED' }]}
        actionsEnabled
        onChangeAccountStatus={onChangeAccountStatus}
      />,
    )

    const staleConfirm = screen.queryByRole('button', { name: '确认冻结' })
    if (staleConfirm) {
      expect(staleConfirm).toBeDisabled()
    }
    expect(onChangeAccountStatus).not.toHaveBeenCalled()
  })

  it('FROZEN 用户解冻确认后变为 ACTIVE 时旧确认失效', async () => {
    const user = userEvent.setup()
    const onChangeAccountStatus = vi.fn()
    const frozenUser = createUser('FROZEN', 'user-unfreeze-refresh')
    const { rerender } = render(
      <AdminUserManagementWorkspace
        users={[frozenUser]}
        actionsEnabled
        onChangeAccountStatus={onChangeAccountStatus}
      />,
    )
    await openOnlyUserDetail(user)
    await user.click(screen.getByRole('button', { name: '解冻账号' }))
    await user.type(screen.getByLabelText('账号状态变更原因'), '风险解除')

    rerender(
      <AdminUserManagementWorkspace
        users={[{ ...frozenUser, status: 'ACTIVE' }]}
        actionsEnabled
        onChangeAccountStatus={onChangeAccountStatus}
      />,
    )

    const staleConfirm = screen.queryByRole('button', { name: '确认解冻' })
    if (staleConfirm) {
      expect(staleConfirm).toBeDisabled()
    }
    expect(onChangeAccountStatus).not.toHaveBeenCalled()
  })

  it('用户从 Props 删除后旧账号状态确认失效', async () => {
    const user = userEvent.setup()
    const onChangeAccountStatus = vi.fn()
    const activeUser = createUser('ACTIVE', 'user-removed')
    const { rerender } = render(
      <AdminUserManagementWorkspace
        users={[activeUser]}
        actionsEnabled
        onChangeAccountStatus={onChangeAccountStatus}
      />,
    )
    await openOnlyUserDetail(user)
    await user.click(screen.getByRole('button', { name: '冻结账号' }))
    await user.type(screen.getByLabelText('账号状态变更原因'), '风险核查')

    rerender(
      <AdminUserManagementWorkspace
        users={[]}
        actionsEnabled
        onChangeAccountStatus={onChangeAccountStatus}
      />,
    )

    const staleConfirm = screen.queryByRole('button', { name: '确认冻结' })
    if (staleConfirm) {
      expect(staleConfirm).toBeDisabled()
    }
    expect(onChangeAccountStatus).not.toHaveBeenCalled()
  })

  it('用户 ID 被替换后旧账号状态确认失效', async () => {
    const user = userEvent.setup()
    const onChangeAccountStatus = vi.fn()
    const activeUser = createUser('ACTIVE', 'user-before-replacement')
    const { rerender } = render(
      <AdminUserManagementWorkspace
        users={[activeUser]}
        actionsEnabled
        onChangeAccountStatus={onChangeAccountStatus}
      />,
    )
    await openOnlyUserDetail(user)
    await user.click(screen.getByRole('button', { name: '冻结账号' }))
    await user.type(screen.getByLabelText('账号状态变更原因'), '风险核查')

    rerender(
      <AdminUserManagementWorkspace
        users={[createUser('ACTIVE', 'user-after-replacement')]}
        actionsEnabled
        onChangeAccountStatus={onChangeAccountStatus}
      />,
    )

    const staleConfirm = screen.queryByRole('button', { name: '确认冻结' })
    if (staleConfirm) {
      expect(staleConfirm).toBeDisabled()
    }
    expect(onChangeAccountStatus).not.toHaveBeenCalled()
  })
})

describe('管理员任务与服务', () => {
  it('正式路由使用真实只读任务诊断容器且不注入预览数据', async () => {
    useAuthStore.setState({
      status: 'authenticated',
      user: adminFixture,
      error: null,
    })
    renderApp([APP_PATHS.adminTasks])

    expect(await screen.findByRole('heading', { name: '任务诊断' })).toBeInTheDocument()
    expect(screen.queryByText('PREVIEW-TASK-GEO-001')).not.toBeInTheDocument()
    expect(screen.getByLabelText('任务号')).toBeEnabled()
    expect(screen.queryByRole('button', { name: /重试|停止|结算|审核/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '任务诊断' })).toHaveAttribute(
      'aria-current',
      'page',
    )
  })

  it('任务筛选、服务页签和预览导航均正常', async () => {
    const user = userEvent.setup()
    renderApp([DEV_PREVIEW_PATHS.adminTasks])
    expect(await screen.findByText('PREVIEW-TASK-GEO-001')).toBeInTheDocument()

    await user.type(screen.getByLabelText('搜索任务'), 'IMAGE-002')
    const table = screen.getByRole('table', { name: '管理员任务列表' })
    expect(within(table).getByText('PREVIEW-TASK-IMAGE-002')).toBeInTheDocument()
    expect(within(table).queryByText('PREVIEW-TASK-GEO-001')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '服务状态' }))
    expect(screen.getByLabelText('服务状态列表')).toBeInTheDocument()
    expect(screen.getByText('视频生成 Provider')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '用户管理' }))
    expect(await screen.findByRole('heading', { name: '用户管理' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '用户管理' })).toHaveAttribute(
      'aria-current',
      'page',
    )
  })

  it('任务诊断详情展示七个平台、Trace 和异常摘要且操作安全禁用', async () => {
    const user = userEvent.setup()
    render(
      <AdminTaskServiceWorkspace
        tasks={PREVIEW_ADMIN_TASKS}
        services={PREVIEW_ADMIN_SERVICES}
        preview
      />,
    )
    const row = screen.getByText('PREVIEW-TASK-GEO-001').closest('tr')
    await user.click(
      within(row as HTMLElement).getByRole('button', { name: '诊断详情' }),
    )

    const platformTable = screen.getByRole('table', { name: '平台执行结果' })
    expect(within(platformTable).getAllByRole('row')).toHaveLength(8)
    expect(within(platformTable).getByText('腾讯元宝')).toBeInTheDocument()
    expect(screen.getByLabelText('执行轨迹')).toHaveTextContent('RabbitMQ 投递')
    expect(screen.getByText('腾讯元宝响应超时，重试 2 次仍未取得有效样本。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新拉取状态' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '受控重试' })).toBeDisabled()
  })

  it('受控重试仅在显式启用和回调存在时提交审计 DTO', async () => {
    const user = userEvent.setup()
    const onTaskAction = vi.fn()
    render(
      <AdminTaskServiceWorkspace
        tasks={PREVIEW_ADMIN_TASKS}
        services={PREVIEW_ADMIN_SERVICES}
        actionsEnabled
        onTaskAction={onTaskAction}
      />,
    )
    const row = screen.getByText('PREVIEW-TASK-GEO-001').closest('tr')
    await user.click(
      within(row as HTMLElement).getByRole('button', { name: '诊断详情' }),
    )
    await user.click(screen.getByRole('button', { name: '受控重试' }))
    expect(screen.getByRole('button', { name: '确认操作' })).toBeDisabled()
    await user.type(screen.getByLabelText('任务操作原因'), '  Provider 恢复后复核  ')
    await user.click(screen.getByRole('button', { name: '确认操作' }))

    expect(onTaskAction).toHaveBeenCalledWith({
      taskId: 'PREVIEW-TASK-GEO-001',
      action: 'CONTROLLED_RETRY',
      reason: 'Provider 恢复后复核',
    })
  })

  it.each<AdminTaskStatus>(['QUEUED', 'RUNNING', 'SUCCEEDED'])(
    '%s 任务不能受控重试且不会调用回调',
    async (taskStatus) => {
      const user = userEvent.setup()
      const onTaskAction = vi.fn()
      render(
        <AdminTaskServiceWorkspace
          tasks={[createTask(taskStatus)]}
          services={[]}
          actionsEnabled
          onTaskAction={onTaskAction}
        />,
      )
      await openOnlyTaskDetail(user)

      const retryButton = screen.getByRole('button', { name: '受控重试' })
      expect(retryButton).toBeDisabled()
      await user.click(retryButton)
      expect(screen.queryByRole('button', { name: '确认操作' })).not.toBeInTheDocument()
      expect(onTaskAction).not.toHaveBeenCalled()
    },
  )

  it.each<AdminTaskStatus>(['FAILED', 'PARTIAL_FAILED', 'UNKNOWN'])(
    '%s 任务可打开受控重试确认并提交当前任务 DTO',
    async (taskStatus) => {
      const user = userEvent.setup()
      const onTaskAction = vi.fn()
      const task = createTask(taskStatus)
      render(
        <AdminTaskServiceWorkspace
          tasks={[task]}
          services={[]}
          actionsEnabled
          onTaskAction={onTaskAction}
        />,
      )
      await openOnlyTaskDetail(user)
      await user.click(screen.getByRole('button', { name: '受控重试' }))
      expect(screen.getByRole('button', { name: '确认操作' })).toBeDisabled()
      await user.type(screen.getByLabelText('任务操作原因'), '  状态核验后重试  ')
      await user.click(screen.getByRole('button', { name: '确认操作' }))

      expect(onTaskAction).toHaveBeenCalledWith({
        taskId: task.id,
        action: 'CONTROLLED_RETRY',
        reason: '状态核验后重试',
      })
    },
  )

  it('失败任务打开确认后变为 SUCCEEDED 时旧确认失效', async () => {
    const user = userEvent.setup()
    const onTaskAction = vi.fn()
    const failedTask = createTask('FAILED', 'task-status-refresh')
    const { rerender } = render(
      <AdminTaskServiceWorkspace
        tasks={[failedTask]}
        services={[]}
        actionsEnabled
        onTaskAction={onTaskAction}
      />,
    )
    await openOnlyTaskDetail(user)
    await user.click(screen.getByRole('button', { name: '受控重试' }))
    await user.type(screen.getByLabelText('任务操作原因'), '失败后重试')
    expect(screen.getByRole('button', { name: '确认操作' })).toBeEnabled()

    rerender(
      <AdminTaskServiceWorkspace
        tasks={[{ ...failedTask, status: 'SUCCEEDED' }]}
        services={[]}
        actionsEnabled
        onTaskAction={onTaskAction}
      />,
    )

    const staleConfirm = screen.queryByRole('button', { name: '确认操作' })
    if (staleConfirm) {
      expect(staleConfirm).toBeDisabled()
    }
    expect(screen.getByRole('button', { name: '受控重试' })).toBeDisabled()
    expect(onTaskAction).not.toHaveBeenCalled()
  })

  it('当前任务从 Props 删除后旧确认失效且不能提交', async () => {
    const user = userEvent.setup()
    const onTaskAction = vi.fn()
    const failedTask = createTask('FAILED', 'task-removed')
    const { rerender } = render(
      <AdminTaskServiceWorkspace
        tasks={[failedTask]}
        services={[]}
        actionsEnabled
        onTaskAction={onTaskAction}
      />,
    )
    await openOnlyTaskDetail(user)
    await user.click(screen.getByRole('button', { name: '受控重试' }))
    await user.type(screen.getByLabelText('任务操作原因'), '失败后重试')

    rerender(
      <AdminTaskServiceWorkspace
        tasks={[]}
        services={[]}
        actionsEnabled
        onTaskAction={onTaskAction}
      />,
    )

    const staleConfirm = screen.queryByRole('button', { name: '确认操作' })
    if (staleConfirm) {
      expect(staleConfirm).toBeDisabled()
    }
    expect(onTaskAction).not.toHaveBeenCalled()
  })

  it('当前任务 ID 被替换后旧确认失效且不能提交', async () => {
    const user = userEvent.setup()
    const onTaskAction = vi.fn()
    const failedTask = createTask('FAILED', 'task-before-replacement')
    const { rerender } = render(
      <AdminTaskServiceWorkspace
        tasks={[failedTask]}
        services={[]}
        actionsEnabled
        onTaskAction={onTaskAction}
      />,
    )
    await openOnlyTaskDetail(user)
    await user.click(screen.getByRole('button', { name: '受控重试' }))
    await user.type(screen.getByLabelText('任务操作原因'), '失败后重试')

    rerender(
      <AdminTaskServiceWorkspace
        tasks={[createTask('FAILED', 'task-after-replacement')]}
        services={[]}
        actionsEnabled
        onTaskAction={onTaskAction}
      />,
    )

    const staleConfirm = screen.queryByRole('button', { name: '确认操作' })
    if (staleConfirm) {
      expect(staleConfirm).toBeDisabled()
    }
    expect(onTaskAction).not.toHaveBeenCalled()
  })

  it('非法服务错误率统一显示占位符', async () => {
    const user = userEvent.setup()
    const invalidRates = [Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY, -0.1, 100.1]
    const services: AdminServiceSummary[] = invalidRates.map((errorRatePercent, index) => ({
      id: `invalid-rate-${index}`,
      name: `非法错误率服务 ${index + 1}`,
      kind: 'PROVIDER',
      status: 'DEGRADED',
      errorRatePercent,
      updatedAt: '2026-09-07 12:00:00',
    }))
    render(<AdminTaskServiceWorkspace tasks={[]} services={services} />)
    await user.click(screen.getByRole('button', { name: '服务状态' }))

    for (const service of services) {
      const card = screen.getByText(service.name).closest('article')
      expect(card).not.toBeNull()
      const errorRateLabel = within(card as HTMLElement).getByText('错误率')
      expect(errorRateLabel.nextElementSibling).toHaveTextContent('—')
    }
    expect(screen.queryByText(/NaN%|Infinity%|-0\.1%|100\.1%/)).not.toBeInTheDocument()
  })

  it('服务错误率 0、100 和合法小数正常显示百分号', async () => {
    const user = userEvent.setup()
    const services: AdminServiceSummary[] = [
      { id: 'rate-zero', name: '零错误率服务', kind: 'PROVIDER', status: 'HEALTHY', errorRatePercent: 0, updatedAt: '2026-09-07 12:00:00' },
      { id: 'rate-hundred', name: '满错误率服务', kind: 'PROVIDER', status: 'UNAVAILABLE', errorRatePercent: 100, updatedAt: '2026-09-07 12:00:00' },
      { id: 'rate-decimal', name: '小数错误率服务', kind: 'PROVIDER', status: 'DEGRADED', errorRatePercent: 12.5, updatedAt: '2026-09-07 12:00:00' },
    ]
    render(<AdminTaskServiceWorkspace tasks={[]} services={services} />)
    await user.click(screen.getByRole('button', { name: '服务状态' }))

    expect(screen.getByText('0%')).toBeInTheDocument()
    expect(screen.getByText('100%')).toBeInTheDocument()
    expect(screen.getByText('12.5%')).toBeInTheDocument()
  })

  it('普通用户访问管理员新路由仍进入 403', async () => {
    useAuthStore.setState({
      status: 'authenticated',
      user: userFixture,
      error: null,
    })
    renderApp([APP_PATHS.adminUsers])
    expect(await screen.findByRole('heading', { name: '403 拒绝访问' })).toBeInTheDocument()
  })
})

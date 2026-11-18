import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminCreateUserRequest, AdminUserDto } from '../api/adminUserApi'
import { clearStoredAuthToken, storeAuthToken } from '../auth/tokenStorage'
import { useAuthStore } from '../stores/authStore'
import { adminFixture, jsonResponse, testAccessToken, testExpiresAt, testTraceId } from './fixtures/auth'
import { renderApp } from './renderApp'

const usernameAccount: AdminUserDto = {
  id: '9101', username: 'merchant_123', phone: null, displayName: '测试商家',
  status: 'ACTIVE', credentialState: 'MUST_CHANGE_PASSWORD', tokenVersion: 1,
}
const temporaryPassword = 'test-only-random-credential'

function setupApi(initialUsers: AdminUserDto[] = [], rejectCreate = false) {
  let users = [...initialUsers]
  const ok = (data: unknown, status = 200) => jsonResponse({ code: 'OK', message: 'success', traceId: testTraceId, data }, status)
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input, init) => {
    const url = String(input)
    if (url === '/api/v1/admin/users' && init?.method === 'POST') {
      if (rejectCreate) return jsonResponse({ code: 'USERNAME_ALREADY_EXISTS', message: '用户名已存在', traceId: testTraceId }, 409)
      const request = JSON.parse(String(init.body)) as AdminCreateUserRequest
      const account: AdminUserDto = { ...usernameAccount, username: request.username || request.phone!, phone: request.phone ?? null, displayName: request.displayName }
      users = [account]
      return ok({ user: account, temporaryPassword }, 201)
    }
    if (url === '/api/v1/admin/users/9101/password-reset') return ok({ userId: '9101', credentialState: 'MUST_CHANGE_PASSWORD', temporaryPassword })
    if (url === '/api/v1/admin/users/9101') return ok(users[0])
    if (url.startsWith('/api/v1/admin/users?')) return ok({ items: users, page: 1, size: 20, total: users.length, hasNext: false })
    throw new Error(`Unexpected account test request: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

async function openCreate() {
  const user = userEvent.setup()
  renderApp(['/admin/users'])
  await user.click(await screen.findByRole('button', { name: /管理员开户/ }))
  return user
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  window.sessionStorage.clear()
  clearStoredAuthToken()
  storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
  useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
})

describe('正式管理员开户', () => {
  it('默认手机号开户，沿用手机号规范化与初始积分请求，不发送用户名', async () => {
    const fetchMock = setupApi()
    const user = await openCreate()
    expect(screen.getByRole('radio', { name: '手机号开户' })).toBeChecked()
    expect(screen.queryByLabelText('登录用户名')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('中国大陆手机号'), '13800138000')
    await user.type(screen.getByLabelText('用户名称/称呼'), ' 手机商家 ')
    fireEvent.change(screen.getByLabelText('初始赠送积分（选填）'), { target: { value: '500' } })
    await user.click(screen.getByRole('button', { name: '确认开户' }))

    await screen.findByText(temporaryPassword)
    const request = fetchMock.mock.calls.find(([url, init]) => url === '/api/v1/admin/users' && init?.method === 'POST')
    expect(JSON.parse(String(request?.[1]?.body))).toEqual({ phone: '+8613800138000', displayName: '手机商家', initialPointGrant: 500 })
    expect(screen.getByText('账号：+8613800138000')).toBeInTheDocument()
  })

  it('从已填写手机号切换用户名开户，仅提交规范化用户名并正确展示一次性密码账号', async () => {
    const fetchMock = setupApi()
    const user = await openCreate()
    await user.type(screen.getByLabelText('中国大陆手机号'), '13800138000')
    await user.click(screen.getByRole('radio', { name: '用户名开户' }))
    expect(screen.queryByLabelText('中国大陆手机号')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('登录用户名'), ' Merchant_123 ')
    await user.type(screen.getByLabelText('用户名称/称呼'), '测试商家')
    fireEvent.change(screen.getByLabelText('初始赠送积分（选填）'), { target: { value: '500' } })
    await user.click(screen.getByRole('button', { name: '确认开户' }))

    await screen.findByText(temporaryPassword)
    const request = fetchMock.mock.calls.find(([url, init]) => url === '/api/v1/admin/users' && init?.method === 'POST')
    expect(JSON.parse(String(request?.[1]?.body))).toEqual({ username: 'merchant_123', displayName: '测试商家', initialPointGrant: 500 })
    expect(screen.getByText('账号：merchant_123')).toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: '用户列表' })).getByText('merchant_123')).toBeInTheDocument()
    expect(screen.queryByText('+8613800138000')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '我已安全保存并关闭' }))
    await waitFor(() => expect(screen.queryByText(temporaryPassword)).not.toBeInTheDocument())
    expect(JSON.stringify(window.localStorage)).not.toContain(temporaryPassword)
    expect(JSON.stringify(window.sessionStorage)).not.toContain(temporaryPassword)
  })

  it('用户名切回手机号不残留用户名，取消重开仍默认手机号', async () => {
    const fetchMock = setupApi()
    const user = await openCreate()
    await user.click(screen.getByRole('radio', { name: '用户名开户' }))
    await user.type(screen.getByLabelText('登录用户名'), 'merchant_123')
    await user.click(screen.getByRole('radio', { name: '手机号开户' }))
    expect(screen.queryByLabelText('登录用户名')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('中国大陆手机号'), '13800138000')
    await user.type(screen.getByLabelText('用户名称/称呼'), '手机商家')
    await user.click(screen.getByRole('button', { name: '确认开户' }))
    await screen.findByText(temporaryPassword)
    const request = fetchMock.mock.calls.find(([url, init]) => url === '/api/v1/admin/users' && init?.method === 'POST')
    expect(JSON.parse(String(request?.[1]?.body))).toEqual({ phone: '+8613800138000', displayName: '手机商家', initialPointGrant: 0 })
    await user.click(screen.getByRole('button', { name: '我已安全保存并关闭' }))
    await user.click(screen.getByRole('button', { name: /管理员开户/ }))
    expect(screen.getByRole('radio', { name: '手机号开户' })).toBeChecked()
    expect(screen.getByLabelText('中国大陆手机号')).toHaveValue('')
  })

  it.each(['ab', '123abc', 'user-name', '用户名'])('无效用户名 %s 不发开户请求', async (username) => {
    const fetchMock = setupApi()
    const user = await openCreate()
    await user.click(screen.getByRole('radio', { name: '用户名开户' }))
    await user.type(screen.getByLabelText('登录用户名'), username)
    await user.type(screen.getByLabelText('用户名称/称呼'), '测试商家')
    await user.click(screen.getByRole('button', { name: '确认开户' }))
    expect(await screen.findByText('请输入3–32位用户名，以字母开头，仅含字母、数字和下划线')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })

  it('用户名重复时保留输入，失败不显示一次性密码且不写入持久存储', async () => {
    const fetchMock = setupApi([], true)
    const user = await openCreate()
    await user.click(screen.getByRole('radio', { name: '用户名开户' }))
    await user.type(screen.getByLabelText('登录用户名'), 'merchant_123')
    await user.type(screen.getByLabelText('用户名称/称呼'), '测试商家')
    await user.click(screen.getByRole('button', { name: '确认开户' }))
    expect(await screen.findByText('用户名已存在')).toBeInTheDocument()
    expect(screen.getByLabelText('登录用户名')).toHaveValue('merchant_123')
    expect(screen.queryByText('一次性临时密码')).not.toBeInTheDocument()
    expect(screen.queryByText(temporaryPassword)).not.toBeInTheDocument()
    expect(JSON.stringify(window.localStorage)).not.toContain(temporaryPassword)
    expect(JSON.stringify(window.sessionStorage)).not.toContain(temporaryPassword)
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })

  it('无手机号账号列表和详情展示真实用户名，重置密码凭据仍绑定该用户名', async () => {
    setupApi([usernameAccount])
    const user = userEvent.setup()
    renderApp(['/admin/users'])
    const table = await screen.findByRole('table', { name: '用户列表' })
    expect(within(table).getByText('merchant_123')).toBeInTheDocument()
    await user.click(within(table).getByRole('button', { name: '查看' }))
    expect(await screen.findByText('未绑定')).toBeInTheDocument()
    const detail = screen.getByRole('dialog', { name: '用户详情' })
    expect(within(detail).getByText('merchant_123')).toBeInTheDocument()
    await user.click(within(detail).getByRole('button', { name: '重置密码' }))
    await user.click(screen.getByRole('button', { name: /确\s*认/ }))
    await screen.findByText(temporaryPassword)
    expect(screen.getByText('账号：merchant_123')).toBeInTheDocument()
  })
})

import { fireEvent, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { signOut, signOutAndRedirectToLogin } from '../auth'
import { clearFirstLoginActivation, readFirstLoginActivation, safeAgreementUrl } from '../auth'
import {
  clearStoredAuthToken,
  readStoredAuthToken,
  storeAuthToken,
} from '../auth/tokenStorage'
import { ProtectedRoute } from '../routes/RouteGuards'
import { useAuthStore } from '../stores/authStore'
import {
  adminFixture,
  activationLoginResponse,
  apiErrorResponse,
  jsonResponse,
  loginResponse,
  meResponse,
  testAccessToken,
  testExpiresAt,
  testTraceId,
  userFixture,
} from './fixtures/auth'
import { renderApp, renderWithRouter } from './renderApp'

type FetchMock = ReturnType<typeof vi.fn<typeof fetch>>

function resetAuthState(): void {
  clearFirstLoginActivation()
  clearStoredAuthToken()
  useAuthStore.setState({ status: 'unknown', user: null, error: null })
}

function mockFetchOnce(response: Response): FetchMock {
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValueOnce(response)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

async function submitLogin(
  username = 'test-user',
  password = 'test-password',
): Promise<void> {
  const user = userEvent.setup()
  await screen.findByRole('heading', { name: '欢迎使用光禾AI智能体框架' })
  await user.type(screen.getByLabelText('手机号/用户名'), username)
  await user.type(screen.getByLabelText('密码'), password)
  await user.click(screen.getByRole('button', { name: /登\s*录/ }))
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  resetAuthState()
})

describe('登录页视觉与范围边界', () => {
  it('实际渲染仓库内登录图片并提供装饰图加载失败兜底', async () => {
    const { container } = renderApp(['/login'])

    await screen.findByRole('heading', { name: '欢迎使用光禾AI智能体框架' })
    const artwork = container.querySelector('.login-hero__visual')
    expect(artwork).toHaveAttribute('aria-hidden', 'true')

    const images = Array.from(artwork?.querySelectorAll('img') ?? [])
    const imageSources = images.map((image) => image.getAttribute('src') ?? '')
    expect(imageSources.some((src) => src.includes('product-main.webp'))).toBe(true)
    expect(imageSources.some((src) => src.includes('home-kitchen.webp'))).toBe(true)
    expect(imageSources.some((src) => src.includes('office-desk.webp'))).toBe(true)
    expect(imageSources.some((src) => src.includes('outdoor-camping.webp'))).toBe(true)

    const productImage = artwork?.querySelector<HTMLImageElement>(
      '.login-product-focus__image',
    )
    expect(productImage).toHaveAttribute('width', '960')
    expect(productImage).toHaveAttribute('height', '960')
    fireEvent.error(productImage as HTMLImageElement)
    expect(
      artwork?.querySelector('.login-product-focus .login-art-image--fallback'),
    ).toBeInTheDocument()
  })

  it('不展示公开注册、协议确认或找回密码操作入口', async () => {
    renderApp(['/login'])

    await screen.findByRole('heading', { name: '欢迎使用光禾AI智能体框架' })
    expect(screen.queryByRole('link', { name: /注册|忘记密码|用户协议|隐私政策/ })).not.toBeInTheDocument()
    expect(screen.queryByText(/手机号注册|注册步骤|记住账号/)).not.toBeInTheDocument()
  })
})

describe('真实认证路由闭环', () => {
  it('USER 登录成功后进入 /app 新任务', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(loginResponse(userFixture)))
    vi.stubGlobal('fetch', fetchMock)
    renderApp(['/login'])

    await submitLogin()

    expect(
      await screen.findByRole('heading', { name: '今天想完成什么？' }, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/auth/login',
      expect.objectContaining({ method: 'POST' }),
    )
    expect(readStoredAuthToken()).toEqual({
      accessToken: testAccessToken,
      expiresAt: testExpiresAt,
    })
  })

  it('ADMIN 登录成功后进入 /admin', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(loginResponse(adminFixture))))
    renderApp(['/login'])

    await submitLogin('test-admin')

    expect(
      await screen.findByRole('heading', { name: '运营概览' }, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.getByText('管理用户账号并排查任务与 Run 的执行问题')).toBeInTheDocument()
  })

  it('错误密码保留在登录页并显示安全错误，不回显密码', async () => {
    const password = 'never-render-this-password'
    vi.stubGlobal('fetch', vi.fn<typeof fetch>()
      .mockResolvedValueOnce(apiErrorResponse(401, '账号或密码错误')))
    renderApp(['/login'])

    await submitLogin('test-user', password)

    expect(await screen.findByText('账号或密码错误')).toBeInTheDocument()
    expect(screen.queryByText(password)).not.toBeInTheDocument()
    expect(readStoredAuthToken()).toBeNull()
  })

  it('429 显示明确的限流反馈，不向未登录用户展示 traceId', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>()
      .mockResolvedValueOnce(apiErrorResponse(429, 'backend detail')))
    renderApp(['/login'])

    await submitLogin()

    expect(
      await screen.findByText('登录尝试过多，请稍后重试。'),
    ).toBeInTheDocument()
    expect(screen.queryByText(`traceId：${testTraceId}`)).not.toBeInTheDocument()
  })

  it('503 显示认证服务不可用反馈', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>()
      .mockResolvedValueOnce(apiErrorResponse(503, 'backend detail')))
    renderApp(['/login'])

    await submitLogin()

    expect(
      await screen.findByText('认证服务暂时不可用，请稍后重试。'),
    ).toBeInTheDocument()
  })

  it('未登录访问保护路由会跳转登录并保留完整目标', async () => {
    useAuthStore.setState({ status: 'anonymous', user: null, error: null })

    function LoginLocationProbe() {
      const location = useLocation()
      const state = location.state as { from?: string } | null
      return <output aria-label="原始目标">{state?.from}</output>
    }

    renderWithRouter(
      <Routes>
        <Route element={<ProtectedRoute role="USER" />}>
          <Route path="/private" element={<Outlet />} />
        </Route>
        <Route path="/login" element={<LoginLocationProbe />} />
      </Routes>,
      ['/private?panel=one#details'],
    )

    expect(await screen.findByLabelText('原始目标')).toHaveTextContent(
      '/private?panel=one#details',
    )
  })

  it('USER 访问 /admin 会进入 403', async () => {
    useAuthStore.setState({
      status: 'authenticated',
      user: userFixture,
      error: null,
    })
    renderApp(['/admin'])

    expect(
      await screen.findByRole('heading', { name: '403 拒绝访问' }),
    ).toBeInTheDocument()
  })

  it('刷新恢复会携带 Bearer token 调用 /auth/me', async () => {
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    const fetchMock = mockFetchOnce(jsonResponse(meResponse(userFixture)))
    renderApp(['/app'])

    expect(
      await screen.findByRole('heading', { name: '今天想完成什么？' }),
    ).toBeInTheDocument()
    const authMeCall = fetchMock.mock.calls.find(([url]) => url === '/api/v1/auth/me')
    expect(authMeCall).toBeDefined()
    const [url, init] = authMeCall ?? []
    expect(url).toBe('/api/v1/auth/me')
    expect(new Headers(init?.headers).get('Authorization')).toBe(
      `Bearer ${testAccessToken}`,
    )
  })

  it('/auth/me 返回 401 时清 token 并展示登录过期反馈', async () => {
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    mockFetchOnce(apiErrorResponse(401, 'token invalid'))
    renderApp(['/app'])

    expect(await screen.findByText('登录状态已过期')).toBeInTheDocument()
    expect(screen.getByText('为保障账号安全，请重新登录。')).toBeInTheDocument()
    expect(readStoredAuthToken()).toBeNull()
    expect(useAuthStore.getState().status).toBe('expired')
  })

  it('退出调用真实 logout 路径并总是清理本地 token 和用户', async () => {
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({
      status: 'authenticated',
      user: userFixture,
      error: null,
    })
    const fetchMock = mockFetchOnce(
      jsonResponse({
        code: 'OK',
        message: 'success',
        data: null,
        traceId: testTraceId,
      }),
    )

    await signOut()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(url).toBe('/api/v1/auth/logout')
    expect(init?.method).toBe('POST')
    expect(new Headers(init?.headers).get('Authorization')).toBe(
      `Bearer ${testAccessToken}`,
    )
    expect(readStoredAuthToken()).toBeNull()
    expect(useAuthStore.getState()).toMatchObject({
      status: 'anonymous',
      user: null,
    })
  })

  it('logout 服务失败也不会把用户困在已登录状态', async () => {
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({
      status: 'authenticated',
      user: userFixture,
      error: null,
    })
    mockFetchOnce(apiErrorResponse(503, 'unavailable'))

    await expect(signOut()).rejects.toMatchObject({ status: 503 })
    expect(readStoredAuthToken()).toBeNull()
    expect(useAuthStore.getState().status).toBe('anonymous')
  })

  it.each([200, 503])('退出接口返回 %i 时都会清理会话并重新打开登录页', async (status) => {
    storeAuthToken({ accessToken: testAccessToken, expiresAt: testExpiresAt })
    useAuthStore.setState({
      status: 'authenticated',
      user: userFixture,
      error: null,
    })
    mockFetchOnce(status === 200
      ? jsonResponse({ code: 'OK', message: 'success', data: null, traceId: testTraceId })
      : apiErrorResponse(503, 'unavailable'))
    const replace = vi.fn<(path: string) => void>(() => {
      expect(readStoredAuthToken()).toBeNull()
      expect(useAuthStore.getState().status).toBe('anonymous')
    })

    await signOutAndRedirectToLogin(replace)

    expect(replace).toHaveBeenCalledExactlyOnceWith('/login')
  })

  it('登录错误不提供 traceId 或复制按钮', async () => {
    const writeText = vi.fn<(text: string) => Promise<void>>().mockResolvedValue()
    vi.stubGlobal('fetch', vi.fn<typeof fetch>()
      .mockResolvedValueOnce(apiErrorResponse(401, '账号或密码错误')))
    renderApp(['/login'])

    await submitLogin()
    await screen.findByText('账号或密码错误')
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    expect(screen.queryByText(`traceId：${testTraceId}`)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /复制/ })).not.toBeInTheDocument()
    expect(writeText).not.toHaveBeenCalled()
  })
})

describe('认证安全边界', () => {
  it.each([6, 128])('首次设置接受 %i 个字符的新密码，原样发送且不限制为恰好六位', async (length) => {
    const user = userEvent.setup()
    const password = 'a'.repeat(length)
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(activationLoginResponse(userFixture)))
      .mockResolvedValueOnce(jsonResponse(loginResponse(userFixture)))
    vi.stubGlobal('fetch', fetchMock)
    renderApp(['/login'])

    await submitLogin('13800138000', 'temporary-password')
    await screen.findByRole('heading', { name: '完成首次登录' })
    expect(screen.getByLabelText('新密码')).toHaveAttribute('maxlength', '128')
    expect(screen.getByLabelText('确认新密码')).toHaveAttribute('maxlength', '128')
    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: password } })
    fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: password } })
    await user.click(screen.getByRole('checkbox', { name: /用户协议/ }))
    await user.click(screen.getByRole('checkbox', { name: /隐私政策/ }))
    await user.click(screen.getByRole('button', { name: '完成设置并登录' }))

    await screen.findByRole('heading', { name: '今天想完成什么？' })
    const completeCalls = fetchMock.mock.calls.filter(([url]) => url === '/api/v1/auth/complete-first-login')
    expect(completeCalls).toHaveLength(1)
    expect(JSON.parse(String(completeCalls[0]?.[1]?.body)).newPassword).toBe(password)
  })

  it.each([
    ['aaaaa', 'aaaaa', '密码长度须为6–128个字符'],
    ['a'.repeat(129), 'a'.repeat(129), '密码长度须为6–128个字符'],
    ['aaaaaa', 'bbbbbb', '两次输入的密码不一致'],
  ])('首次设置拒绝不合规或不一致的密码 %#，不会调用改密接口', async (password, confirmation, message) => {
    const user = userEvent.setup()
    const fetchMock = mockFetchOnce(jsonResponse(activationLoginResponse(userFixture)))
    renderApp(['/login'])

    await submitLogin('13800138000', 'temporary-password')
    await screen.findByRole('heading', { name: '完成首次登录' })
    fireEvent.change(screen.getByLabelText('新密码'), { target: { value: password } })
    fireEvent.change(screen.getByLabelText('确认新密码'), { target: { value: confirmation } })
    await user.click(screen.getByRole('checkbox', { name: /用户协议/ }))
    await user.click(screen.getByRole('checkbox', { name: /隐私政策/ }))
    await user.click(screen.getByRole('button', { name: '完成设置并登录' }))

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/v1/auth/complete-first-login')).toBe(false)
    expect(readStoredAuthToken()).toBeNull()
  })

  it.each([6, 64])('普通登录仍接受 %i 个字符的已有密码，原样发送不截断', async (length) => {
    const password = 'a'.repeat(length)
    const fetchMock = mockFetchOnce(jsonResponse(loginResponse(userFixture)))
    renderApp(['/login'])

    await submitLogin('test-user', password)

    await screen.findByRole('heading', { name: '今天想完成什么？' })
    const loginCall = fetchMock.mock.calls.find(([url]) => url === '/api/v1/auth/login')
    expect(JSON.parse(String(loginCall?.[1]?.body)).password).toBe(password)
  })

  it('Activation 不落普通 token，完成协议确认后才建立服务端 expiresAt 会话', async () => {
    const user = userEvent.setup()
    const access = loginResponse(userFixture)
    access.data.expiresAt = '2099-09-14T12:00:00+08:00'
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(activationLoginResponse(userFixture)))
      .mockResolvedValueOnce(jsonResponse(access))
    vi.stubGlobal('fetch', fetchMock)
    renderApp(['/login'])

    await submitLogin('13800138000', 'temporary-password')
    expect(await screen.findByRole('heading', { name: '完成首次登录' })).toBeInTheDocument()
    expect(readStoredAuthToken()).toBeNull()
    expect(readFirstLoginActivation()).not.toBeNull()
    await user.type(screen.getByLabelText('新密码'), 'new-password-123')
    await user.type(screen.getByLabelText('确认新密码'), 'new-password-123')
    await user.click(screen.getByRole('checkbox', { name: /用户协议/ }))
    await user.click(screen.getByRole('checkbox', { name: /隐私政策/ }))
    await user.click(screen.getByRole('button', { name: '完成设置并登录' }))

    expect(await screen.findByRole('heading', { name: '今天想完成什么？' })).toBeInTheDocument()
    expect(readStoredAuthToken()?.expiresAt).toBe('2099-09-14T12:00:00+08:00')
    const completeCall = fetchMock.mock.calls.find(([url]) => url === '/api/v1/auth/complete-first-login')
    expect(JSON.parse(String(completeCall?.[1]?.body))).toMatchObject({
      termsAccepted: true,
      privacyAccepted: true,
      termsVersion: 'terms-v1',
      privacyVersion: 'privacy-v1',
    })
    expect(window.localStorage.getItem('activationToken')).toBeNull()
  })

  it('首次改密页强刷后仍恢复原授权页面，不会丢失深链', async () => {
    const user = userEvent.setup()
    const access = loginResponse(userFixture)
    access.data.expiresAt = '2099-09-14T12:00:00+08:00'
    const fetchMock = vi.fn<typeof fetch>().mockImplementation(async (input) => {
      const url = String(input)
      if (url === '/api/v1/auth/login') {
        return jsonResponse(activationLoginResponse(userFixture))
      }
      if (url === '/api/v1/auth/complete-first-login') {
        return jsonResponse(access)
      }
      if (url === '/api/v1/billing/point-account') {
        return jsonResponse({
          code: 'OK',
          message: 'success',
          traceId: testTraceId,
          data: {
            userId: userFixture.id,
            availablePoints: 100,
            frozenPoints: 0,
            version: 1,
          },
        })
      }
      return apiErrorResponse(404, '测试未配置该请求')
    })
    vi.stubGlobal('fetch', fetchMock)

    const firstRender = renderApp([
      '/app/business-data?source=protected#overview',
    ])
    await submitLogin('13800138000', 'temporary-password')
    expect(
      await screen.findByRole('heading', { name: '完成首次登录' }),
    ).toBeInTheDocument()
    expect(window.sessionStorage.length).toBeGreaterThan(0)

    firstRender.unmount()
    renderApp(['/first-login'])
    expect(
      await screen.findByRole('heading', { name: '完成首次登录' }),
    ).toBeInTheDocument()

    await user.type(screen.getByLabelText('新密码'), 'new-password-123')
    await user.type(
      screen.getByLabelText('确认新密码'),
      'new-password-123',
    )
    await user.click(screen.getByRole('checkbox', { name: /用户协议/ }))
    await user.click(screen.getByRole('checkbox', { name: /隐私政策/ }))
    await user.click(
      screen.getByRole('button', { name: '完成设置并登录' }),
    )

    expect(
      await screen.findByRole('heading', { name: '经营资料' }),
    ).toBeInTheDocument()
    expect(window.sessionStorage.length).toBe(0)
  })

  it('首次登录上下文丢失时安全回登录页，协议链接拒绝危险 URL', async () => {
    useAuthStore.setState({ status: 'anonymous', user: null, error: null })
    renderApp(['/first-login'])
    expect(await screen.findByText('首次登录会话已失效')).toBeInTheDocument()
    expect(safeAgreementUrl('javascript:alert(1)')).toBeUndefined()
    expect(safeAgreementUrl('data:text/html,bad')).toBeUndefined()
    expect(safeAgreementUrl('https://user:pass@example.test/terms')).toBeUndefined()
    expect(safeAgreementUrl('/agreements/terms')).toBe('/agreements/terms')
    expect(safeAgreementUrl('https://legal.example.test/terms')).toBe('https://legal.example.test/terms')
  })

  it('生产 TypeScript 和 CSS 不引用本机绝对路径', () => {
    const modules = import.meta.glob('../**/*.{ts,tsx,css}', {
      eager: true,
      import: 'default',
      query: '?raw',
    }) as Record<string, string>

    const productionSources = Object.entries(modules).filter(
      ([path]) => !path.includes('/test/') && !path.includes('.test.'),
    )

    for (const [path, source] of productionSources) {
      expect(source, path).not.toMatch(/[A-Za-z]:[\\/]/)
    }
  })

  it('生产源码不导入测试 fixture', () => {
    const modules = import.meta.glob('../**/*.{ts,tsx}', {
      eager: true,
      import: 'default',
      query: '?raw',
    }) as Record<string, string>

    const productionSources = Object.entries(modules).filter(
      ([path]) => !path.includes('/test/') && !path.includes('.test.'),
    )

    expect(productionSources.length).toBeGreaterThan(0)
    for (const [path, source] of productionSources) {
      expect(source, path).not.toMatch(
        /(?:from|import\s*)[\s(']*['"][^'"]*(?:\/test\/|fixtures?)/,
      )
    }
  })

  it('登录与退出流程不会把密码或 token 写入 console', async () => {
    const password = 'console-secret-password'
    const consoleSpies = [
      vi.spyOn(console, 'log').mockImplementation(() => undefined),
      vi.spyOn(console, 'info').mockImplementation(() => undefined),
      vi.spyOn(console, 'debug').mockImplementation(() => undefined),
      vi.spyOn(console, 'warn').mockImplementation(() => undefined),
      vi.spyOn(console, 'error').mockImplementation(() => undefined),
    ]
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(loginResponse(userFixture)))
      .mockImplementation(() => Promise.resolve(
        jsonResponse({
          code: 'OK',
          message: 'success',
          data: null,
          traceId: testTraceId,
        }),
      ))
    vi.stubGlobal('fetch', fetchMock)
    renderApp(['/login'])

    await submitLogin('test-user', password)
    await screen.findByRole('heading', { name: '今天想完成什么？' })
    await signOut()

    const consoleOutput = consoleSpies.flatMap((spy) => spy.mock.calls).join(' ')
    expect(consoleOutput).not.toContain(password)
    expect(consoleOutput).not.toContain(testAccessToken)
  })

  it('已过期的本地凭证不会发送到网络', async () => {
    storeAuthToken({
      accessToken: testAccessToken,
      expiresAt: '2000-01-01T00:00:00Z',
    })
    const fetchMock = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', fetchMock)
    renderApp(['/app'])

    expect(await screen.findByText('登录状态已过期')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(readStoredAuthToken()).toBeNull()
  })
})

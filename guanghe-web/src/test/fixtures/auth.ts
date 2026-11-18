import type {
  AuthUser,
  FirstLoginResponse,
  LoginApiResponse,
  UserApiResponse,
} from '../../api/authApi'

export const testTraceId = '0123456789abcdef0123456789abcdef'
export const testAccessToken = 'test-access-token-that-is-never-production-data'
export const testExpiresAt = '2099-01-01T00:00:00Z'

export const userFixture: AuthUser = {
  id: '1001',
  username: 'test-user',
  displayName: '测试用户',
  role: 'USER',
  status: 'ACTIVE',
}

export const adminFixture: AuthUser = {
  id: '1002',
  username: 'test-admin',
  displayName: '测试管理员',
  role: 'ADMIN',
  status: 'ACTIVE',
}

export function loginResponse(user: AuthUser): LoginApiResponse {
  return {
    code: 'OK',
    message: 'success',
    traceId: testTraceId,
    data: {
      accessToken: testAccessToken,
      tokenType: 'Bearer',
      expiresAt: testExpiresAt,
      user,
    },
  }
}

export function activationLoginResponse(user: AuthUser): LoginApiResponse {
  const data: FirstLoginResponse = {
    accessToken: null,
    tokenType: 'Activation',
    nextAction: 'COMPLETE_FIRST_LOGIN',
    activationToken: 'test-activation-token-never-production',
    expiresAt: '2099-01-01T00:10:00Z',
    user,
    terms: { version: 'terms-v1', url: '/agreements/terms-v1' },
    privacy: { version: 'privacy-v1', url: '/agreements/privacy-v1' },
  }
  return { code: 'OK', message: 'success', traceId: testTraceId, data }
}

export function registrationConfigResponse() {
  return {
    code: 'OK' as const,
    message: 'success' as const,
    traceId: testTraceId,
    data: {
      registrationEnabled: false as const,
      terms: { version: 'terms-v1', url: '/agreements/terms-v1' },
      privacy: { version: 'privacy-v1', url: '/agreements/privacy-v1' },
      smsCooldownSeconds: 60,
    },
  }
}

export function meResponse(user: AuthUser): UserApiResponse {
  return {
    code: 'OK',
    message: 'success',
    traceId: testTraceId,
    data: user,
  }
}

export function apiErrorResponse(
  statusCode: number,
  message: string,
  traceId = testTraceId,
): Response {
  return jsonResponse(
    {
      code: `HTTP_${statusCode}`,
      message,
      details: {},
      traceId,
    },
    statusCode,
  )
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

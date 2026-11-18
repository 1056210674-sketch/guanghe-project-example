import type { components } from './generated/browser-api'
import { apiRequest } from './client'

export type AuthUser = components['schemas']['AuthUser']
export type LoginRequest = components['schemas']['LoginRequest']
export type LoginResponse = components['schemas']['LoginResponse']
export type AccessLoginResponse = components['schemas']['AccessLoginResponse']
export type FirstLoginResponse = components['schemas']['FirstLoginResponse']
export type CompleteFirstLoginRequest = components['schemas']['CompleteFirstLoginRequest']
export type RegistrationConfig = components['schemas']['RegistrationConfig']
export type LoginApiResponse = components['schemas']['LoginApiResponse']
export type RegistrationConfigApiResponse = components['schemas']['RegistrationConfigApiResponse']
export type UserApiResponse = components['schemas']['UserApiResponse']
export type LogoutApiResponse = components['schemas']['LogoutApiResponse']

export async function login(request: LoginRequest): Promise<LoginResponse> {
  const response = await apiRequest<LoginApiResponse>('/api/v1/auth/login', {
    method: 'POST',
    auth: 'none',
    unauthorized: 'propagate',
    body: request,
  })

  return response.data
}

export async function completeFirstLogin(
  request: CompleteFirstLoginRequest,
): Promise<LoginResponse> {
  const response = await apiRequest<LoginApiResponse>('/api/v1/auth/complete-first-login', {
    method: 'POST',
    auth: 'none',
    unauthorized: 'propagate',
    body: request,
  })
  return response.data
}

export async function getRegistrationConfig(): Promise<RegistrationConfig> {
  const response = await apiRequest<RegistrationConfigApiResponse>(
    '/api/v1/public/registration-config',
    { method: 'GET', auth: 'none', unauthorized: 'propagate' },
  )
  return response.data
}

export async function getCurrentUser(): Promise<AuthUser> {
  const response = await apiRequest<UserApiResponse>('/api/v1/auth/me', {
    method: 'GET',
    auth: 'required',
    unauthorized: 'propagate',
  })

  return response.data
}

export async function logout(): Promise<void> {
  await apiRequest<LogoutApiResponse>('/api/v1/auth/logout', {
    method: 'POST',
    auth: 'required',
    unauthorized: 'propagate',
  })
}

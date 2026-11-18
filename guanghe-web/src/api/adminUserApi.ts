import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiRequest } from './client'

export type AdminUserDto = components['schemas']['AdminUser']
export type AdminUserPageDto = components['schemas']['AdminUserPage']
export type AdminCreateUserRequest = components['schemas']['AdminCreateUserRequest']
export type AdminProvisionedUser = components['schemas']['AdminProvisionedUser']
export type AdminPasswordReset = components['schemas']['AdminPasswordReset']
export type AdminUserListQuery = NonNullable<operations['listAdminUsers']['parameters']['query']>

type AdminUserApiResponse = components['schemas']['AdminUserApiResponse']
type AdminUserPageApiResponse = components['schemas']['AdminUserPageApiResponse']
type AdminProvisionedUserApiResponse = components['schemas']['AdminProvisionedUserApiResponse']
type AdminPasswordResetApiResponse = components['schemas']['AdminPasswordResetApiResponse']

function requireData<T>(data: T | undefined): T {
  if (data === undefined) {
    throw new ApiError({ status: 200, code: 'INVALID_RESPONSE', message: '服务返回了无法识别的用户数据。' })
  }
  return data
}

function listPath(query: AdminUserListQuery): string {
  const search = new URLSearchParams()
  if (query.search) search.set('search', query.search)
  if (query.page !== undefined) search.set('page', String(query.page))
  if (query.size !== undefined) search.set('size', String(query.size))
  const suffix = search.toString()
  return suffix ? `/api/v1/admin/users?${suffix}` : '/api/v1/admin/users'
}

export async function listAdminUsers(query: AdminUserListQuery = {}): Promise<AdminUserPageDto> {
  const response = await apiRequest<AdminUserPageApiResponse>(listPath(query), { method: 'GET', auth: 'required' })
  return requireData(response.data)
}

export async function getAdminUser(userId: string): Promise<AdminUserDto> {
  const response = await apiRequest<AdminUserApiResponse>(`/api/v1/admin/users/${encodeURIComponent(userId)}`, { method: 'GET', auth: 'required' })
  return requireData(response.data)
}

export async function createAdminUser(request: AdminCreateUserRequest): Promise<AdminProvisionedUser> {
  const response = await apiRequest<AdminProvisionedUserApiResponse>('/api/v1/admin/users', { method: 'POST', auth: 'required', body: request })
  return requireData(response.data)
}

export async function resetAdminUserPassword(userId: string): Promise<AdminPasswordReset> {
  const response = await apiRequest<AdminPasswordResetApiResponse>(`/api/v1/admin/users/${encodeURIComponent(userId)}/password-reset`, { method: 'POST', auth: 'required' })
  return requireData(response.data)
}

async function changeAdminUserStatus(userId: string, action: 'disable' | 'restore'): Promise<AdminUserDto> {
  const response = await apiRequest<AdminUserApiResponse>(`/api/v1/admin/users/${encodeURIComponent(userId)}/${action}`, { method: 'POST', auth: 'required' })
  return requireData(response.data)
}

export const disableAdminUser = (userId: string) => changeAdminUserStatus(userId, 'disable')
export const restoreAdminUser = (userId: string) => changeAdminUserStatus(userId, 'restore')

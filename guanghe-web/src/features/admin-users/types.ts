export type AdminUserStatus = 'ACTIVE' | 'FROZEN' | 'DISABLED'

export interface AdminUserRecentTask {
  id: string
  type: string
  status: string
  pointsChange?: number
  createdAt: string
}

export interface AdminUserSummary {
  id: string
  username: string
  phoneMasked?: string
  businessSpaceCount: number
  productCount: number
  availablePoints: number
  totalEarnedPoints?: number
  totalConsumedPoints?: number
  expiringPoints?: number
  status: AdminUserStatus
  registeredAt: string
  lastLoginAt?: string
  recentTasks?: readonly AdminUserRecentTask[]
}

export interface AdminPointsCompensationDraft {
  userId: string
  points: number
  reason: string
  taskId?: string
}

export interface AdminAccountStatusDraft {
  userId: string
  action: 'FREEZE' | 'UNFREEZE'
  reason: string
}

export type AdminCredentialState = 'ACTIVE' | 'MUST_CHANGE_PASSWORD'

export interface AdminAccountUserView {
  id: string
  phone?: string | null
  username?: string | null
  displayName: string
  status: 'ACTIVE' | 'DISABLED'
  credentialState: AdminCredentialState
  createdAt?: string
}

export type AdminCreateAccountDraft = ({ phone: string; username?: never } | { username: string; phone?: never }) & {
  displayName: string
  initialPointGrant: number
}

export function adminLoginIdentifier(user: { phone?: string | null; username?: string | null }): string {
  return user.phone?.trim() || user.username?.trim() || '—'
}

export interface AdminAccountErrorView {
  message: string
  traceId?: string
}

export interface OneTimePasswordView {
  userId: string
  label: string
  temporaryPassword: string
}

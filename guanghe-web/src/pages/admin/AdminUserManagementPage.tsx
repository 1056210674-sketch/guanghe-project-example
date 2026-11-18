import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  createAdminUser,
  disableAdminUser,
  resetAdminUserPassword,
  restoreAdminUser,
  type AdminCreateUserRequest,
  type AdminUserDto,
} from '../../api/adminUserApi'
import { isApiError } from '../../api/apiError'
import {
  AdminAccountManagementWorkspace,
  OneTimePasswordModal,
  adminUserKeys,
  adminLoginIdentifier,
  useAdminUser,
  useAdminUsers,
  type AdminAccountErrorView,
  type AdminAccountUserView,
  type AdminCreateAccountDraft,
  type OneTimePasswordView,
} from '../../features/admin-users'
import { AdminShell } from '../../layouts/AdminShell'
import { useAuthStore } from '../../stores/authStore'

const PAGE_SIZE = 20

function toView(user: AdminUserDto): AdminAccountUserView {
  return {
    id: user.id,
    phone: user.phone,
    username: user.username,
    displayName: user.displayName,
    status: user.status,
    credentialState: user.credentialState,
    createdAt: user.createdAt ?? undefined,
  }
}

function toError(error: unknown): AdminAccountErrorView | null {
  if (!error) return null
  if (isApiError(error)) return { message: error.message, traceId: error.traceId }
  return { message: '请求失败，请稍后重试。' }
}

export function AdminUserManagementPage() {
  const ownerId = useAuthStore((state) => state.user?.id)
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(1)
  const [selectedUserId, setSelectedUserId] = useState<string>()
  const [credential, setCredential] = useState<OneTimePasswordView>()
  const [pendingAction, setPendingAction] = useState<'create' | 'reset' | 'disable' | 'restore'>()
  const [actionError, setActionError] = useState<AdminAccountErrorView | null>(null)
  const listQuery = useAdminUsers(ownerId, { search: search || undefined, page, size: PAGE_SIZE })
  const detailQuery = useAdminUser(ownerId, selectedUserId)

  const invalidateUser = async (userId?: string) => {
    await queryClient.invalidateQueries({ queryKey: adminUserKeys.all(ownerId) })
    if (userId) await queryClient.invalidateQueries({ queryKey: adminUserKeys.detail(ownerId, userId) })
  }

  const create = async (draft: AdminCreateAccountDraft): Promise<boolean> => {
    if (pendingAction) return false
    setPendingAction('create'); setActionError(null)
    try {
      const request: AdminCreateUserRequest = draft
      const result = await createAdminUser(request)
      setCredential({ userId: result.user.id, label: adminLoginIdentifier(result.user), temporaryPassword: result.temporaryPassword })
      await invalidateUser()
      return true
    } catch (error) {
      setActionError(toError(error)); return false
    } finally { setPendingAction(undefined) }
  }

  const resetPassword = async (userId: string) => {
    if (pendingAction) return
    setPendingAction('reset'); setActionError(null)
    try {
      const result = await resetAdminUserPassword(userId)
      setCredential({ userId, label: detailQuery.data ? adminLoginIdentifier(detailQuery.data) : '—', temporaryPassword: result.temporaryPassword })
      await invalidateUser(userId)
    } catch (error) { setActionError(toError(error)) }
    finally { setPendingAction(undefined) }
  }

  const changeStatus = async (userId: string, action: 'disable' | 'restore') => {
    if (pendingAction) return
    setPendingAction(action); setActionError(null)
    try {
      if (action === 'disable') await disableAdminUser(userId)
      else await restoreAdminUser(userId)
      await invalidateUser(userId)
    } catch (error) { setActionError(toError(error)) }
    finally { setPendingAction(undefined) }
  }

  return (
    <AdminShell>
      <AdminAccountManagementWorkspace
        users={(listQuery.data?.items ?? []).map(toView)}
        total={listQuery.data?.total ?? 0}
        page={listQuery.data?.page ?? page}
        pageSize={listQuery.data?.size ?? PAGE_SIZE}
        loading={listQuery.isLoading}
        error={toError(listQuery.error)}
        detail={detailQuery.data ? toView(detailQuery.data) : undefined}
        detailLoading={detailQuery.isLoading}
        detailError={toError(detailQuery.error)}
        actionError={actionError}
        pendingAction={pendingAction}
        onSearch={(value) => { setSearch(value); setPage(1) }}
        onPageChange={setPage}
        onOpenUser={setSelectedUserId}
        onCloseUser={() => setSelectedUserId(undefined)}
        onCreate={create}
        onResetPassword={(id) => void resetPassword(id)}
        onDisable={(id) => void changeStatus(id, 'disable')}
        onRestore={(id) => void changeStatus(id, 'restore')}
        onRetryList={() => void listQuery.refetch()}
        onRetryDetail={() => void detailQuery.refetch()}
      />
      <OneTimePasswordModal credential={credential} onClose={() => setCredential(undefined)} />
    </AdminShell>
  )
}

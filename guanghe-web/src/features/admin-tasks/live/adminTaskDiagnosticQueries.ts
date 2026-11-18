import { useQuery } from '@tanstack/react-query'
import {
  getAdminRunDiagnostic,
  getAdminTaskDiagnostic,
  listAdminRunDiagnostics,
  listAdminTaskDiagnostics,
} from '../../../api/adminDiagnosticsApi'
import type {
  AdminRunDiagnosticListQuery,
  AdminTaskDiagnosticListQuery,
} from '../../../api/adminDiagnosticsApi'
import { useAuthStore } from '../../../stores/authStore'
import {
  toAdminTaskDiagnosticDetailView,
  toAdminTaskDiagnosticPageView,
} from './adminTaskDiagnosticAdapters'
import {
  toAdminRunDiagnosticDetailView,
  toAdminRunDiagnosticPageView,
} from './adminRunDiagnosticAdapters'

export function useAdminRunDiagnostics(
  query: AdminRunDiagnosticListQuery,
  enabled = true,
) {
  const userId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: ['admin-run-diagnostics', userId ?? null, 'list', query],
    queryFn: async () => toAdminRunDiagnosticPageView(
      await listAdminRunDiagnostics(query),
    ),
    enabled,
  })
}

export function useAdminRunDiagnostic(runId?: string, enabled = true) {
  const userId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: ['admin-run-diagnostics', userId ?? null, 'detail', runId ?? null],
    queryFn: async () => toAdminRunDiagnosticDetailView(
      await getAdminRunDiagnostic(runId as string),
    ),
    enabled: enabled && Boolean(runId),
  })
}

export function useAdminTaskDiagnostics(
  query: AdminTaskDiagnosticListQuery,
  enabled = true,
) {
  const userId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: ['admin-task-diagnostics', userId ?? null, 'list', query],
    queryFn: async () => toAdminTaskDiagnosticPageView(
      await listAdminTaskDiagnostics(query),
    ),
    enabled,
  })
}

export function useAdminTaskDiagnostic(taskId?: string, enabled = true) {
  const userId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: ['admin-task-diagnostics', userId ?? null, 'detail', taskId ?? null],
    queryFn: async () => toAdminTaskDiagnosticDetailView(
      await getAdminTaskDiagnostic(taskId as string),
    ),
    enabled: enabled && Boolean(taskId),
  })
}

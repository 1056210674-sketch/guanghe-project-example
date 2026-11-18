import { useQuery } from '@tanstack/react-query'
import {
  getAdminAgentVersion,
  listAdminAgentVersionEvaluations,
  listAdminAgentVersionRuns,
  listAdminAgentVersions,
  type AdminAgentEvaluationListQuery,
  type AdminAgentVersionListQuery,
  type AdminAgentVersionRunListQuery,
} from '../../api/adminAgentVersionsApi'
import { useAuthStore } from '../../stores/authStore'

export const adminAgentVersionKeys = {
  all: (ownerId?: string) => ['admin-agent-versions', ownerId ?? null] as const,
  versions: (ownerId: string | undefined, query: AdminAgentVersionListQuery) =>
    [...adminAgentVersionKeys.all(ownerId), 'versions', query] as const,
  detail: (ownerId: string | undefined, bundleVersion: string) =>
    [...adminAgentVersionKeys.all(ownerId), 'detail', bundleVersion] as const,
  runs: (
    ownerId: string | undefined,
    bundleVersion: string,
    query: AdminAgentVersionRunListQuery,
  ) => [...adminAgentVersionKeys.all(ownerId), 'runs', bundleVersion, query] as const,
  evaluations: (
    ownerId: string | undefined,
    bundleVersion: string,
    query: AdminAgentEvaluationListQuery,
  ) => [...adminAgentVersionKeys.all(ownerId), 'evaluations', bundleVersion, query] as const,
}

function useOwnerId(): string | undefined {
  return useAuthStore((state) => state.user?.id)
}

export function useAdminAgentVersions(query: AdminAgentVersionListQuery) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: adminAgentVersionKeys.versions(ownerId, query),
    queryFn: () => listAdminAgentVersions(query),
    enabled: Boolean(ownerId),
  })
}

export function useAdminAgentVersion(bundleVersion: string | undefined) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: adminAgentVersionKeys.detail(ownerId, bundleVersion ?? ''),
    queryFn: () => getAdminAgentVersion(bundleVersion as string),
    enabled: Boolean(ownerId) && Boolean(bundleVersion),
  })
}

export function useAdminAgentVersionRuns(
  bundleVersion: string | undefined,
  query: AdminAgentVersionRunListQuery,
  enabled: boolean,
) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: adminAgentVersionKeys.runs(ownerId, bundleVersion ?? '', query),
    queryFn: () => listAdminAgentVersionRuns(bundleVersion as string, query),
    enabled: enabled && Boolean(ownerId) && Boolean(bundleVersion),
  })
}

export function useAdminAgentVersionEvaluations(
  bundleVersion: string | undefined,
  query: AdminAgentEvaluationListQuery,
  enabled: boolean,
) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: adminAgentVersionKeys.evaluations(ownerId, bundleVersion ?? '', query),
    queryFn: () => listAdminAgentVersionEvaluations(bundleVersion as string, query),
    enabled: enabled && Boolean(ownerId) && Boolean(bundleVersion),
  })
}

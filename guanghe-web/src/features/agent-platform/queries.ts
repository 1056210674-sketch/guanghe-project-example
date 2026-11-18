import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { addAgentInstallation, createProject, listAgentInstallations, listAgents, listProjects, removeAgentInstallation, updateAgentInstallation } from '../../api/agentPlatformApi'
import type { UpdateAgentInstallationRequest } from '../../api/agentPlatformApi'
import { useAuthStore } from '../../stores/authStore'

export const agentPlatformKeys = {
  all: (ownerId?: string) => ['agent-platform', ownerId ?? null] as const,
  catalog: (ownerId?: string) => [...agentPlatformKeys.all(ownerId), 'catalog'] as const,
  installations: (ownerId?: string) => [...agentPlatformKeys.all(ownerId), 'installations'] as const,
  projects: (ownerId?: string) => [...agentPlatformKeys.all(ownerId), 'projects'] as const,
}

function useOwner() {
  return useAuthStore((state) => state.status === 'authenticated' && state.user?.role === 'USER' ? state.user.id : undefined)
}

export function useAgentCatalog(enabled = true) {
  const owner = useOwner()
  return useQuery({ queryKey: agentPlatformKeys.catalog(owner), queryFn: listAgents, enabled: enabled && Boolean(owner) })
}

export function useAgentInstallations(enabled = true) {
  const owner = useOwner()
  return useQuery({ queryKey: agentPlatformKeys.installations(owner), queryFn: listAgentInstallations, enabled: enabled && Boolean(owner) })
}

export function useProjects(enabled = true) {
  const owner = useOwner()
  return useQuery({ queryKey: agentPlatformKeys.projects(owner), queryFn: listProjects, enabled: enabled && Boolean(owner) })
}

export function useAgentActions() {
  const owner = useOwner()
  const client = useQueryClient()
  const refresh = () => Promise.all([
    client.invalidateQueries({ queryKey: agentPlatformKeys.catalog(owner) }),
    client.invalidateQueries({ queryKey: agentPlatformKeys.installations(owner) }),
  ])
  const add = useMutation({ mutationFn: addAgentInstallation, onSuccess: refresh })
  const update = useMutation({ mutationFn: ({ id, request }: { id: string; request: UpdateAgentInstallationRequest }) => updateAgentInstallation(id, request), onSuccess: refresh })
  const remove = useMutation({ mutationFn: removeAgentInstallation, onSuccess: refresh })
  return { add, update, remove }
}

export function useCreateProject() {
  const owner = useOwner()
  const client = useQueryClient()
  return useMutation({ mutationFn: createProject, onSuccess: () => client.invalidateQueries({ queryKey: agentPlatformKeys.projects(owner) }) })
}

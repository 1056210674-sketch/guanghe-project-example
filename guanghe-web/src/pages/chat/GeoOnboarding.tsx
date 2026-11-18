import { useQuery } from '@tanstack/react-query'
import { getProjectGeoTarget, type Project } from '../../api/agentPlatformApi'
import { listKnowledgeDocuments, listMemoryEntries, type KnowledgeDocument } from '../../api/knowledgeApi'
import { GeoOnboardingWelcome } from '../../features/chat/components/GeoOnboardingWelcome'
import { useAuthStore } from '../../stores/authStore'

/** Reuses project knowledge caches; opening a welcome never creates a run or stores facts. */
export function GeoOnboarding({ project, disabled, onUpload, onOpenKnowledge, onPrefill }: {
  project?: Project
  disabled: boolean
  onUpload: () => void
  onOpenKnowledge: () => void
  onPrefill: (text: string) => boolean
}) {
  const owner = useAuthStore((state) => state.user?.id)
  const projectId = project?.id
  const enabled = Boolean(owner && projectId)
  const identity = useQuery({ queryKey: ['project-geo-target', owner ?? null, projectId],
    queryFn: () => getProjectGeoTarget(projectId!), enabled, retry: false })
  const memories = useQuery({ queryKey: ['project-knowledge', owner ?? null, projectId ?? null, 'PROJECT', 'memory-entries'],
    queryFn: () => listMemoryEntries('PROJECT', projectId, true), enabled, retry: false })
  const documents = useQuery({ queryKey: ['project-knowledge', owner ?? null, projectId ?? null, 'documents', 1],
    queryFn: () => listKnowledgeDocuments(projectId!), enabled, retry: false,
    refetchInterval: (query) => query.state.data?.items.some((item: KnowledgeDocument) => ['UPLOADING', 'PARSING'].includes(item.status)) ? 3000 : false,
    refetchIntervalInBackground: false })
  const error = identity.isError || memories.isError || documents.isError
  const loaded = identity.isSuccess && memories.isSuccess && documents.isSuccess
  const existing = loaded && (identity.data.configured || memories.data.some((item) => !item.deleted)
    || documents.data.items.some((item: KnowledgeDocument) => item.projectId === projectId && item.status === 'READY'))
  return <GeoOnboardingWelcome projectName={project?.name} disabled={disabled}
    knowledgeState={!projectId ? 'unscoped' : error ? 'error' : !loaded ? 'loading' : existing ? 'existing' : 'empty'}
    knownBrandName={!error && identity.isSuccess && identity.data.configured ? identity.data.name ?? undefined : undefined}
    onUpload={onUpload} onOpenKnowledge={onOpenKnowledge} onPrefill={onPrefill}
    onRetry={() => void Promise.all([identity.refetch(), memories.refetch(), documents.refetch()])} />
}

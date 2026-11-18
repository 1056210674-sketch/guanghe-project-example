import { useQuery } from '@tanstack/react-query'
import {
  getAdminMediaCallback,
  getAdminMediaOverview,
  getAdminMediaResource,
  getAdminMediaSubmission,
  listAdminMediaCallbacks,
  listAdminMediaResources,
  listAdminMediaSubmissions,
  listAdminMediaSyncStates,
  type AdminMediaCallbackListQuery,
  type AdminMediaResourceListQuery,
  type AdminMediaSubmissionListQuery,
} from '../../api/adminMediaApi'
import { useAuthStore } from '../../stores/authStore'

/**
 * The media console's reads. Every one of them is scoped to the signed-in operator, so the queries
 * are keyed by that account: switching administrators must not show the previous one's cached rows.
 *
 * Each hook takes an `enabled` flag because the four tabs are separate reads. Loading all of them
 * when the console opens would spend four requests to render one tab, and would report "there is
 * something in the callback tab" from data nobody asked for yet.
 */
export const adminMediaKeys = {
  all: (ownerId?: string) => ['admin-media', ownerId ?? null] as const,
  overview: (ownerId?: string) => [...adminMediaKeys.all(ownerId), 'overview'] as const,
  resources: (ownerId: string | undefined, query: AdminMediaResourceListQuery) =>
    [...adminMediaKeys.all(ownerId), 'resources', query] as const,
  resource: (ownerId: string | undefined, resourceId: string) =>
    [...adminMediaKeys.all(ownerId), 'resource', resourceId] as const,
  submissions: (ownerId: string | undefined, query: AdminMediaSubmissionListQuery) =>
    [...adminMediaKeys.all(ownerId), 'submissions', query] as const,
  submission: (ownerId: string | undefined, submissionId: string) =>
    [...adminMediaKeys.all(ownerId), 'submission', submissionId] as const,
  callbacks: (ownerId: string | undefined, query: AdminMediaCallbackListQuery) =>
    [...adminMediaKeys.all(ownerId), 'callbacks', query] as const,
  callback: (ownerId: string | undefined, callbackId: string) =>
    [...adminMediaKeys.all(ownerId), 'callback', callbackId] as const,
  syncStates: (ownerId?: string) => [...adminMediaKeys.all(ownerId), 'sync-states'] as const,
}

function useOwnerId(): string | undefined {
  return useAuthStore((state) => state.user?.id)
}

export function useAdminMediaOverview(enabled = true) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: adminMediaKeys.overview(ownerId),
    queryFn: () => getAdminMediaOverview(),
    enabled: enabled && Boolean(ownerId),
  })
}

export function useAdminMediaResources(
  query: AdminMediaResourceListQuery,
  enabled = true,
) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: adminMediaKeys.resources(ownerId, query),
    queryFn: () => listAdminMediaResources(query),
    enabled: enabled && Boolean(ownerId),
  })
}

export function useAdminMediaResource(resourceId: string | undefined) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: adminMediaKeys.resource(ownerId, resourceId ?? ''),
    queryFn: () => getAdminMediaResource(resourceId as string),
    enabled: Boolean(ownerId) && Boolean(resourceId),
  })
}

export function useAdminMediaSubmissions(
  query: AdminMediaSubmissionListQuery,
  enabled = true,
) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: adminMediaKeys.submissions(ownerId, query),
    queryFn: () => listAdminMediaSubmissions(query),
    enabled: enabled && Boolean(ownerId),
  })
}

/** The detail read exists for the event trail: the list row carries no status history. */
export function useAdminMediaSubmission(submissionId: string | undefined) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: adminMediaKeys.submission(ownerId, submissionId ?? ''),
    queryFn: () => getAdminMediaSubmission(submissionId as string),
    enabled: Boolean(ownerId) && Boolean(submissionId),
  })
}

export function useAdminMediaCallbacks(query: AdminMediaCallbackListQuery, enabled = true) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: adminMediaKeys.callbacks(ownerId, query),
    queryFn: () => listAdminMediaCallbacks(query),
    enabled: enabled && Boolean(ownerId),
  })
}

export function useAdminMediaCallback(callbackId: string | undefined) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: adminMediaKeys.callback(ownerId, callbackId ?? ''),
    queryFn: () => getAdminMediaCallback(callbackId as string),
    enabled: Boolean(ownerId) && Boolean(callbackId),
  })
}

export function useAdminMediaSyncStates(enabled = true) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: adminMediaKeys.syncStates(ownerId),
    queryFn: () => listAdminMediaSyncStates(),
    enabled: enabled && Boolean(ownerId),
  })
}

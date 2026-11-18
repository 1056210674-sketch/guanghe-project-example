import { useQueries, useQuery } from '@tanstack/react-query'
import {
  getMediaResource,
  getMediaSubmission,
  listArticleMediaRecommendations,
  listMediaResources,
  listMediaSubmissions,
} from '../../../api/mediaApi'
import type {
  MediaResourceListQuery,
  MediaSubmissionStatus,
  MediaSubmissionListQuery,
} from '../../../api/mediaApi'
import { useAuthStore } from '../../../stores/authStore'

function createMediaKeys(ownerId?: string | null) {
  const owner = ['media', 'owner', ownerId ?? null] as const
  return {
    owner,
    resourceLists: () => [...owner, 'resources', 'list'] as const,
    resourceList: (query: MediaResourceListQuery) =>
      [...owner, 'resources', 'list', query] as const,
    resource: (resourceId?: string) =>
      [...owner, 'resources', 'detail', resourceId ?? null] as const,
    recommendations: (articleId?: string, articleVersion?: number) =>
      [
        ...owner,
        'recommendations',
        articleId ?? null,
        articleVersion ?? null,
      ] as const,
    submissionLists: () => [...owner, 'submissions', 'list'] as const,
    submissionList: (query: MediaSubmissionListQuery) =>
      [...owner, 'submissions', 'list', query] as const,
    submission: (submissionId?: string) =>
      [...owner, 'submissions', 'detail', submissionId ?? null] as const,
  }
}

export const mediaKeys = {
  all: ['media'] as const,
  forOwner: createMediaKeys,
}

function useMediaKeys() {
  return createMediaKeys(useAuthStore((state) => state.user?.id))
}

const MEDIA_SUBMISSION_FAST_POLL_INTERVAL_MS = 5_000
const MEDIA_SUBMISSION_RECOVERY_POLL_INTERVAL_MS = 10_000
const MEDIA_SUBMISSION_ACCEPTED_POLL_INTERVAL_MS = 30_000
const MEDIA_SUBMISSION_POLL_WINDOW_MS = 30 * 60_000
const TERMINAL_MEDIA_SUBMISSION_STATUSES = new Set<MediaSubmissionStatus>([
  'PUBLISHED',
  'REJECTED',
  'FAILED',
])

export function mediaSubmissionNeedsPolling(
  status?: MediaSubmissionStatus,
) {
  return Boolean(status && !TERMINAL_MEDIA_SUBMISSION_STATUSES.has(status))
}

export function mediaSubmissionListNeedsPolling(
  items?: readonly { status: MediaSubmissionStatus }[],
) {
  return Boolean(items?.some((item) => mediaSubmissionNeedsPolling(item.status)))
}

export function mediaSubmissionPollPauseAt(
  submission?: { status: MediaSubmissionStatus; updatedAt?: string },
): number | undefined {
  if (!submission || !mediaSubmissionNeedsPolling(submission.status) || !submission.updatedAt) {
    return undefined
  }
  const updatedAt = Date.parse(submission.updatedAt)
  return Number.isFinite(updatedAt)
    ? updatedAt + MEDIA_SUBMISSION_POLL_WINDOW_MS + 1
    : undefined
}

export function mediaSubmissionPollingPaused(
  submission?: { status: MediaSubmissionStatus; updatedAt?: string },
  now = Date.now(),
): boolean {
  const pauseAt = mediaSubmissionPollPauseAt(submission)
  return pauseAt !== undefined && now >= pauseAt
}

export function mediaSubmissionPollInterval(
  submission?: { status: MediaSubmissionStatus; updatedAt?: string },
  now = Date.now(),
): number | false {
  if (!submission || !mediaSubmissionNeedsPolling(submission.status)) return false
  if (mediaSubmissionPollingPaused(submission, now)) return false
  if (submission.status === 'ACCEPTED') {
    return MEDIA_SUBMISSION_ACCEPTED_POLL_INTERVAL_MS
  }
  if (
    submission.status === 'SUBMIT_UNKNOWN' ||
    submission.status === 'RECONCILING'
  ) {
    return MEDIA_SUBMISSION_RECOVERY_POLL_INTERVAL_MS
  }
  return MEDIA_SUBMISSION_FAST_POLL_INTERVAL_MS
}

function mediaSubmissionListPollInterval(
  items?: readonly { status: MediaSubmissionStatus; updatedAt?: string }[],
): number | false {
  const intervals = (items ?? [])
    .map((item) => mediaSubmissionPollInterval(item))
    .filter((interval): interval is number => interval !== false)
  return intervals.length > 0 ? Math.min(...intervals) : false
}

export function useMediaResources(query: MediaResourceListQuery = {}) {
  const keys = useMediaKeys()
  return useQuery({
    queryKey: keys.resourceList(query),
    queryFn: () => listMediaResources(query),
  })
}

export function useMediaResource(resourceId?: string) {
  const keys = useMediaKeys()
  return useQuery({
    queryKey: keys.resource(resourceId),
    queryFn: () => getMediaResource(resourceId as string),
    enabled: Boolean(resourceId),
  })
}

export function useArticleMediaRecommendations(
  articleId?: string,
  articleVersion?: number,
  enabled = true,
) {
  const keys = useMediaKeys()
  return useQuery({
    queryKey: keys.recommendations(articleId, articleVersion),
    queryFn: () => listArticleMediaRecommendations(articleId as string, 5),
    enabled: enabled && Boolean(articleId) && articleVersion !== undefined,
  })
}

export function useMediaResourcesByIds(resourceIds: readonly string[]) {
  const keys = useMediaKeys()
  const uniqueIds = [...new Set(resourceIds)]
  return useQueries({
    queries: uniqueIds.map((resourceId) => ({
      queryKey: keys.resource(resourceId),
      queryFn: () => getMediaResource(resourceId),
      enabled: Boolean(resourceId),
    })),
  })
}

export function useMediaSubmissions(
  query: MediaSubmissionListQuery = {},
  pollingEnabled = true,
  enabled = true,
) {
  const keys = useMediaKeys()
  return useQuery({
    queryKey: keys.submissionList(query),
    queryFn: () => listMediaSubmissions(query),
    enabled,
    refetchInterval: (current) =>
      pollingEnabled
        ? mediaSubmissionListPollInterval(current.state.data?.items)
        : false,
    refetchIntervalInBackground: false,
  })
}

export function useMediaSubmission(submissionId?: string) {
  const keys = useMediaKeys()
  return useQuery({
    queryKey: keys.submission(submissionId),
    queryFn: () => getMediaSubmission(submissionId as string),
    enabled: Boolean(submissionId),
    refetchInterval: (current) =>
      mediaSubmissionPollInterval(current.state.data),
    refetchIntervalInBackground: false,
  })
}

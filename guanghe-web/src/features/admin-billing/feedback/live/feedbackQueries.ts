import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  handleFeedback,
  listAdminFeedback,
  type AdminFeedbackListQuery,
  type HandleFeedbackRequest,
} from '../../../../api/adminBillingApi'
import { useAuthStore } from '../../../../stores/authStore'

export const feedbackKeys = {
  all: (ownerId?: string) => ['admin-billing', ownerId ?? null, 'feedback'] as const,
  list: (ownerId: string | undefined, query: AdminFeedbackListQuery) =>
    [...feedbackKeys.all(ownerId), 'list', query] as const,
}

export function useAdminFeedback(query: AdminFeedbackListQuery) {
  const ownerId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: feedbackKeys.list(ownerId, query),
    queryFn: () => listAdminFeedback(query),
    enabled: Boolean(ownerId),
  })
}

interface HandleFeedbackVariables {
  feedbackId: string
  body: HandleFeedbackRequest
}

/** Posts the transition, then refetches so the row shows the server's answer rather than the request. */
export function useHandleFeedback() {
  const ownerId = useAuthStore((state) => state.user?.id)
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ feedbackId, body }: HandleFeedbackVariables) =>
      handleFeedback(feedbackId, body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: feedbackKeys.all(ownerId) })
    },
  })
}

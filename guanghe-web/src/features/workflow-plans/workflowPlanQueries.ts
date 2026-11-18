import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  abandonWorkflowPlan,
  getWorkflowPlan,
  listWorkflowPlans,
} from '../../api/workflowPlanApi'
import type {
  WorkflowPlanAbandonRequest,
  WorkflowPlanId,
  WorkflowPlanListQuery,
} from '../../api/workflowPlanApi'
import { useAuthStore } from '../../stores/authStore'

const rootKey = ['workflow-plans'] as const
const ACTIVE_EXECUTION_STATUSES = new Set(['QUEUED', 'RUNNING', 'WAITING_INPUT'])

export function workflowPlanDetailRefreshInterval(
  plan?: { status: string } | null,
  failureCount = 0,
): number | false {
  if (!plan || !ACTIVE_EXECUTION_STATUSES.has(plan.status)) return false
  return Math.min(5_000 * (2 ** Math.max(0, failureCount)), 30_000)
}

export function workflowPlanKeys(ownerId?: string | null) {
  const ownerRoot = [...rootKey, 'owner', ownerId ?? null] as const
  return {
    ownerRoot,
    lists: () => [...ownerRoot, 'list'] as const,
    list: (query: WorkflowPlanListQuery) => [...ownerRoot, 'list', query] as const,
    detail: (planId?: WorkflowPlanId) => [...ownerRoot, 'detail', planId ?? null] as const,
  }
}

function useKeys() {
  const ownerId = useAuthStore((state) => state.user?.id)
  return workflowPlanKeys(ownerId)
}

export function useWorkflowPlans(query: WorkflowPlanListQuery) {
  const keys = useKeys()
  return useQuery({ queryKey: keys.list(query), queryFn: () => listWorkflowPlans(query) })
}

export function useWorkflowPlan(planId?: WorkflowPlanId) {
  const keys = useKeys()
  return useQuery({
    queryKey: keys.detail(planId),
    queryFn: () => getWorkflowPlan(planId as WorkflowPlanId),
    enabled: Boolean(planId),
    refetchInterval: (query) => workflowPlanDetailRefreshInterval(
      query.state.data,
      query.state.fetchFailureCount,
    ),
    refetchIntervalInBackground: false,
  })
}

export interface AbandonWorkflowPlanVariables {
  planId: WorkflowPlanId
  request: WorkflowPlanAbandonRequest
}

export function useAbandonWorkflowPlan() {
  const keys = useKeys()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ planId, request }: AbandonWorkflowPlanVariables) =>
      abandonWorkflowPlan(planId, request),
    onSuccess: async (plan) => {
      queryClient.setQueryData(keys.detail(plan.id), plan)
      await queryClient.invalidateQueries({ queryKey: keys.lists() })
    },
  })
}

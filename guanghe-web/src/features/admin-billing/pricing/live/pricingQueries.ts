import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createPricingVersion,
  listPricingVersions,
  listRateLimits,
  publishPricingVersion,
  retirePricingVersion,
  updatePricingVersion,
  updateRateLimit,
} from '../../../../api/adminBillingApi'
import type {
  CreatePricingVersionRequest,
  PricingVersionListQuery,
  UpdatePricingVersionRequest,
  UpdateRateLimitRequest,
} from '../../../../api/adminBillingApi'
import { useAuthStore } from '../../../../stores/authStore'
import type { RateLimitAction, RateLimitRuleDraft } from '../types'

/**
 * Query keys for the pricing console.
 *
 * The owner id is part of every key so two administrators signed in on the same browser profile
 * never read each other's cached pages, and every query is disabled while it is missing, which is
 * what keeps the anonymous DEV preview routes from issuing requests.
 */
export const pricingKeys = {
  all: (ownerId?: string) => ['admin-billing', ownerId ?? null] as const,
  versions: (ownerId: string | undefined, query: PricingVersionListQuery) =>
    ['admin-billing', ownerId ?? null, 'pricing-versions', query] as const,
  rateLimits: (ownerId: string | undefined) =>
    ['admin-billing', ownerId ?? null, 'rate-limits', null] as const,
}

export function usePricingVersions(
  ownerId: string | undefined,
  query: PricingVersionListQuery,
) {
  return useQuery({
    queryKey: pricingKeys.versions(ownerId, query),
    queryFn: () => listPricingVersions(query),
    enabled: Boolean(ownerId),
  })
}

export function useRateLimitRules(ownerId: string | undefined) {
  return useQuery({
    queryKey: pricingKeys.rateLimits(ownerId),
    queryFn: () => listRateLimits(),
    enabled: Boolean(ownerId),
  })
}

/**
 * Reads the signed-in administrator's id. Every hook below takes it as an argument rather than
 * reading the store itself, so a workspace can be rendered in isolation in tests and previews.
 */
export function usePricingOwnerId(): string | undefined {
  return useAuthStore((state) => state.user?.id)
}

function usePricingInvalidator(ownerId: string | undefined) {
  const queryClient = useQueryClient()
  return async () => {
    await queryClient.invalidateQueries({ queryKey: pricingKeys.all(ownerId) })
  }
}

export function useCreatePricingVersionMutation(ownerId: string | undefined) {
  const invalidate = usePricingInvalidator(ownerId)
  return useMutation({
    mutationFn: (request: CreatePricingVersionRequest) => createPricingVersion(request),
    onSuccess: invalidate,
  })
}

export interface UpdatePricingVersionVariables {
  versionId: string
  /** The optimistic version last read, so a stale edit is refused rather than applied. */
  expectedVersion: number
  request: CreatePricingVersionRequest
}

export function useUpdatePricingVersionMutation(ownerId: string | undefined) {
  const invalidate = usePricingInvalidator(ownerId)
  return useMutation({
    mutationFn: ({ versionId, expectedVersion, request }: UpdatePricingVersionVariables) => {
      const body: UpdatePricingVersionRequest = { ...request, expectedVersion }
      return updatePricingVersion(versionId, body)
    },
    onSuccess: invalidate,
  })
}

export function usePublishPricingVersionMutation(ownerId: string | undefined) {
  const invalidate = usePricingInvalidator(ownerId)
  return useMutation({
    mutationFn: (versionId: string) => publishPricingVersion(versionId),
    onSuccess: invalidate,
  })
}

export function useRetirePricingVersionMutation(ownerId: string | undefined) {
  const invalidate = usePricingInvalidator(ownerId)
  return useMutation({
    mutationFn: (versionId: string) => retirePricingVersion(versionId),
    onSuccess: invalidate,
  })
}

export interface UpdateRateLimitVariables {
  action: RateLimitAction
  /** The `version` that was loaded with the rule; 0 creates it for the first time. */
  expectedVersion: number
  request: RateLimitRuleDraft
}

export function useUpdateRateLimitMutation(ownerId: string | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ action, expectedVersion, request }: UpdateRateLimitVariables) => {
      const body: UpdateRateLimitRequest = { ...request, expectedVersion }
      return updateRateLimit(action, body)
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: pricingKeys.all(ownerId) })
    },
  })
}

/** Refetches the rules after a rejection so the operator sees what the server actually holds. */
export function useRefreshRateLimits(ownerId: string | undefined) {
  const queryClient = useQueryClient()
  return () =>
    queryClient.invalidateQueries({ queryKey: pricingKeys.rateLimits(ownerId) })
}

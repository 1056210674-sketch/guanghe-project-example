/**
 * React Query hooks for the 积分账务 console.
 *
 * Every key is namespaced by the signed-in operator (`ownerId`) so that two administrators sharing a
 * browser never read each other's cached page, and every query is disabled until that operator is
 * known. `ledgerKeys.all` is the single invalidation handle: a mutation invalidates it and the list,
 * the totals and the bookkeeping cards all refresh from the server.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  confirmPointAdjustment,
  getAdminPointLedgerSummary,
  getBookkeepingSummary,
  listAdminPointLedger,
  listPointAdjustments,
  requestPointAdjustment,
} from '../../../../api/adminBillingApi'
import type {
  AdminPointLedgerListQuery,
  AdminPointLedgerSummaryQuery,
  ConfirmPointAdjustmentRequest,
  CreatePointAdjustmentRequest,
  PointAdjustmentListQuery,
} from '../../../../api/adminBillingApi'
import { useAuthStore } from '../../../../stores/authStore'
import {
  toAdminPointLedgerPageView,
  toBookkeepingView,
  toLedgerSummaryView,
  toPointAdjustmentPageView,
} from '../types'

type OwnerId = string | null | undefined

export const ledgerKeys = {
  /** Everything cached for this operator. Pass it to `invalidateQueries` after any write. */
  all: (ownerId: OwnerId) => ['admin-billing', ownerId ?? null] as const,
  pointLedger: (ownerId: OwnerId, query: AdminPointLedgerListQuery) =>
    [...ledgerKeys.all(ownerId), 'point-ledger', query] as const,
  pointLedgerSummary: (ownerId: OwnerId, query: AdminPointLedgerSummaryQuery) =>
    [...ledgerKeys.all(ownerId), 'point-ledger-summary', query] as const,
  bookkeepingSummary: (ownerId: OwnerId) =>
    [...ledgerKeys.all(ownerId), 'bookkeeping-summary'] as const,
  pointAdjustments: (ownerId: OwnerId, query: PointAdjustmentListQuery) =>
    [...ledgerKeys.all(ownerId), 'point-adjustments', query] as const,
}

export function useAdminPointLedger(query: AdminPointLedgerListQuery) {
  const ownerId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: ledgerKeys.pointLedger(ownerId, query),
    queryFn: async () => toAdminPointLedgerPageView(await listAdminPointLedger(query)),
    enabled: Boolean(ownerId),
  })
}

/**
 * Totals for a filter set. Deliberately a separate request from the list: a total that could be
 * recomputed from the open page would silently become wrong as soon as the page changed.
 */
export function useAdminPointLedgerSummary(query: AdminPointLedgerSummaryQuery) {
  const ownerId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: ledgerKeys.pointLedgerSummary(ownerId, query),
    queryFn: async () => toLedgerSummaryView(await getAdminPointLedgerSummary(query)),
    enabled: Boolean(ownerId),
  })
}

export function useBookkeepingSummary() {
  const ownerId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: ledgerKeys.bookkeepingSummary(ownerId),
    queryFn: async () => toBookkeepingView(await getBookkeepingSummary()),
    enabled: Boolean(ownerId),
  })
}

/** The adjustment page. The console asks for `status: 'PENDING_CONFIRMATION'` only. */
export function usePointAdjustments(query: PointAdjustmentListQuery) {
  const ownerId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: ledgerKeys.pointAdjustments(ownerId, query),
    queryFn: async () => toPointAdjustmentPageView(await listPointAdjustments(query)),
    enabled: Boolean(ownerId),
  })
}

function useInvalidateLedger() {
  const ownerId = useAuthStore((state) => state.user?.id)
  const queryClient = useQueryClient()
  return () =>
    queryClient.invalidateQueries({ queryKey: ledgerKeys.all(ownerId) })
}

/**
 * Step one. The server answers with a PENDING_CONFIRMATION adjustment and a token that exists in
 * that response only; no points have moved. The caller must not treat this as a credit.
 */
export function useRequestPointAdjustment() {
  const invalidate = useInvalidateLedger()
  return useMutation({
    mutationFn: (body: CreatePointAdjustmentRequest) => requestPointAdjustment(body),
    onSuccess: () => invalidate(),
  })
}

/** Step two. The server moves the points and writes the ledger row in the same transaction. */
export function useConfirmPointAdjustment() {
  const invalidate = useInvalidateLedger()
  return useMutation({
    mutationFn: ({
      adjustmentId,
      body,
    }: {
      adjustmentId: string
      body: ConfirmPointAdjustmentRequest
    }) => confirmPointAdjustment(adjustmentId, body),
    onSuccess: () => invalidate(),
  })
}

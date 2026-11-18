import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  acceptInvoiceApplication,
  approveInvoiceApplication,
  deliverInvoiceApplication,
  retryInvoiceWebhook,
  getAdminInvoiceApplication,
  listAdminInvoiceApplications,
  rejectInvoiceApplication,
  type AdminInvoiceApplicationListQuery,
  type RejectInvoiceApplicationRequest,
  type ConfirmManualInvoiceDeliveryRequest,
} from '../../../../api/adminBillingApi'
import { useAuthStore } from '../../../../stores/authStore'

/**
 * Admin invoice queries and state-machine mutations.
 *
 * The owner id is part of every key, and every mutation invalidates the whole invoice resource for
 * that owner, so neither the list nor an open detail survives a transition with a stale status.
 */
export const invoiceKeys = {
  all: (ownerId?: string | null) =>
    ['admin-billing', ownerId ?? null, 'invoice-applications'] as const,
  list: (ownerId: string | undefined, query: AdminInvoiceApplicationListQuery) =>
    [...invoiceKeys.all(ownerId), query] as const,
  detail: (ownerId: string | undefined, applicationId?: string) =>
    [...invoiceKeys.all(ownerId), 'detail', applicationId ?? null] as const,
}

export function useAdminInvoiceApplications(query: AdminInvoiceApplicationListQuery) {
  const ownerId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: invoiceKeys.list(ownerId, query),
    queryFn: () => listAdminInvoiceApplications(query),
    enabled: Boolean(ownerId),
  })
}

export function useAdminInvoiceApplication(applicationId?: string) {
  const ownerId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: invoiceKeys.detail(ownerId, applicationId),
    queryFn: () => getAdminInvoiceApplication(applicationId as string),
    enabled: Boolean(ownerId && applicationId),
  })
}

function useInvalidateInvoices() {
  const ownerId = useAuthStore((state) => state.user?.id)
  const queryClient = useQueryClient()
  return async () => {
    await queryClient.invalidateQueries({ queryKey: invoiceKeys.all(ownerId) })
  }
}

/** SUBMITTED → UNDER_REVIEW. Still no invoice exists at this point. */
export function useAcceptInvoiceApplication() {
  const invalidate = useInvalidateInvoices()
  return useMutation({
    mutationFn: (applicationId: string) => acceptInvoiceApplication(applicationId),
    onSuccess: invalidate,
  })
}

/** UNDER_REVIEW → APPROVED. Internal review passed; not an issue date. */
export function useApproveInvoiceApplication() {
  const invalidate = useInvalidateInvoices()
  return useMutation({
    mutationFn: (applicationId: string) => approveInvoiceApplication(applicationId),
    onSuccess: invalidate,
  })
}

/** SUBMITTED/UNDER_REVIEW → REJECTED. The reason is mandatory and the server enforces it too. */
export function useRejectInvoiceApplication() {
  const invalidate = useInvalidateInvoices()
  return useMutation({
    mutationFn: (input: { applicationId: string; body: RejectInvoiceApplicationRequest }) =>
      rejectInvoiceApplication(input.applicationId, input.body),
    onSuccess: invalidate,
  })
}

/**
 * APPROVED → DELIVERED only after the administrator attests external manual sending.
 */
export function useDeliverInvoiceApplication() {
  const invalidate = useInvalidateInvoices()
  return useMutation({
    mutationFn: (input: { applicationId: string; body: ConfirmManualInvoiceDeliveryRequest }) =>
      deliverInvoiceApplication(input.applicationId, input.body),
    onSuccess: invalidate,
  })
}

export function useRetryInvoiceWebhook() {
  const invalidate = useInvalidateInvoices()
  return useMutation({
    mutationFn: (applicationId: string) => retryInvoiceWebhook(applicationId),
    onSuccess: invalidate,
  })
}

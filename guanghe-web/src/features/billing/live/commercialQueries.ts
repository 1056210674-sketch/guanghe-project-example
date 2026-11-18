import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  cancelInvoiceApplication,
  closeRechargeOrder,
  createFeedback,
  createInvoiceApplication,
  createRechargeOrder,
  getActiveRateCard,
  getInvoiceCapability,
  getInvoiceApplication,
  getMyFeedback,
  getRechargeCapability,
  getRechargeOrder,
  listInvoiceApplications,
  listMyFeedback,
  listRechargeOrders,
  queryRechargeOrder,
  type CreateFeedbackRequest,
  type CreateInvoiceApplicationRequest,
  type CreateRechargeOrderRequest,
  type FeedbackListQuery,
  type InvoiceApplicationListQuery,
  type RechargeOrderListQuery,
} from '../../../api/billingApi'
import { useAuthStore } from '../../../stores/authStore'

/**
 * Queries for the user-facing commercial surface.
 *
 * Every key is namespaced by the signed-in user id, and every query is disabled without one. That is
 * what keeps the DEV preview routes request-free: the preview pages render the same containers, and
 * an anonymous visitor produces no calls rather than a page full of failed ones.
 */

export const commercialKeys = {
  all: (ownerId?: string) => ['billing', ownerId ?? null] as const,
}

export const rechargeKeys = {
  all: (ownerId?: string) => [...commercialKeys.all(ownerId), 'recharge'] as const,
  capability: (ownerId?: string) => [...rechargeKeys.all(ownerId), 'capability'] as const,
  rateCard: (ownerId?: string) => [...rechargeKeys.all(ownerId), 'rate-card'] as const,
  orders: (ownerId?: string) => [...rechargeKeys.all(ownerId), 'orders'] as const,
  orderList: (ownerId: string | undefined, query: RechargeOrderListQuery) =>
    [...rechargeKeys.orders(ownerId), 'list', query] as const,
  order: (ownerId: string | undefined, orderId: string) =>
    [...rechargeKeys.orders(ownerId), 'detail', orderId] as const,
}

export const invoiceKeys = {
  all: (ownerId?: string) => [...commercialKeys.all(ownerId), 'invoices'] as const,
  capability: (ownerId?: string) => [...invoiceKeys.all(ownerId), 'capability'] as const,
  list: (ownerId: string | undefined, query: InvoiceApplicationListQuery) =>
    [...invoiceKeys.all(ownerId), 'list', query] as const,
  detail: (ownerId: string | undefined, id?: string) => [...invoiceKeys.all(ownerId), 'detail', id] as const,
}

export const feedbackKeys = {
  all: (ownerId?: string) => [...commercialKeys.all(ownerId), 'feedback'] as const,
  list: (ownerId: string | undefined, query: FeedbackListQuery) =>
    [...feedbackKeys.all(ownerId), 'list', query] as const,
  detail: (ownerId: string | undefined, id?: string) => [...feedbackKeys.all(ownerId), 'detail', id] as const,
}

function useOwnerId(): string | undefined {
  return useAuthStore((state) => state.user?.id)
}

export function useRechargeCapability() {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: rechargeKeys.capability(ownerId),
    queryFn: getRechargeCapability,
    enabled: Boolean(ownerId),
  })
}

export function useActiveRateCard() {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: rechargeKeys.rateCard(ownerId),
    queryFn: getActiveRateCard,
    enabled: Boolean(ownerId),
  })
}

export function useRechargeOrders(query: RechargeOrderListQuery) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: rechargeKeys.orderList(ownerId, query),
    queryFn: () => listRechargeOrders(query),
    enabled: Boolean(ownerId),
  })
}

export function useRechargeOrder(orderId: string | undefined) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: rechargeKeys.order(ownerId, orderId ?? ''),
    queryFn: () => getRechargeOrder(orderId as string),
    enabled: Boolean(ownerId) && Boolean(orderId),
  })
}

/**
 * Creates an order. The idempotency key comes from the caller because it belongs to the logical
 * action, not to the HTTP call: a retry of the same amount+context must reuse it, a different amount
 * must not.
 */
export function useCreateRechargeOrder() {
  const ownerId = useOwnerId()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      body,
      idempotencyKey,
    }: {
      body: CreateRechargeOrderRequest
      idempotencyKey: string
    }) => createRechargeOrder(body, idempotencyKey),
    onSuccess: async (order) => {
      queryClient.setQueryData(rechargeKeys.order(ownerId, order.id), order)
      await queryClient.invalidateQueries({ queryKey: rechargeKeys.orders(ownerId) })
    },
  })
}

/** Asks the provider for the order's state and adopts whatever the server answers. */
export function useQueryRechargeOrder() {
  const ownerId = useOwnerId()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (orderId: string) => queryRechargeOrder(orderId),
    onSuccess: async (order) => {
      queryClient.setQueryData(rechargeKeys.order(ownerId, order.id), order)
      await queryClient.invalidateQueries({ queryKey: rechargeKeys.orders(ownerId) })
    },
  })
}

export function useCloseRechargeOrder() {
  const ownerId = useOwnerId()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (orderId: string) => closeRechargeOrder(orderId),
    onSuccess: async (order) => {
      queryClient.setQueryData(rechargeKeys.order(ownerId, order.id), order)
      await queryClient.invalidateQueries({ queryKey: rechargeKeys.orders(ownerId) })
    },
  })
}

export function useInvoiceCapability() {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: invoiceKeys.capability(ownerId),
    queryFn: getInvoiceCapability,
    enabled: Boolean(ownerId),
  })
}

export function useInvoiceApplications(query: InvoiceApplicationListQuery) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: invoiceKeys.list(ownerId, query),
    queryFn: () => listInvoiceApplications(query),
    enabled: Boolean(ownerId),
  })
}

export function useInvoiceApplication(applicationId?: string) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: invoiceKeys.detail(ownerId, applicationId),
    queryFn: () => getInvoiceApplication(applicationId!),
    enabled: Boolean(ownerId && applicationId),
  })
}

export function useCreateInvoiceApplication() {
  const ownerId = useOwnerId()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      body,
      idempotencyKey,
    }: {
      body: CreateInvoiceApplicationRequest
      idempotencyKey: string
    }) => createInvoiceApplication(body, idempotencyKey),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: invoiceKeys.all(ownerId) })
    },
  })
}

export function useCancelInvoiceApplication() {
  const ownerId = useOwnerId()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (applicationId: string) => cancelInvoiceApplication(applicationId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: invoiceKeys.all(ownerId) })
    },
  })
}

export function useMyFeedback(query: FeedbackListQuery) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: feedbackKeys.list(ownerId, query),
    queryFn: () => listMyFeedback(query),
    enabled: Boolean(ownerId),
  })
}

export function useFeedbackDetail(feedbackId?: string) {
  const ownerId = useOwnerId()
  return useQuery({
    queryKey: feedbackKeys.detail(ownerId, feedbackId),
    queryFn: () => getMyFeedback(feedbackId!),
    enabled: Boolean(ownerId && feedbackId),
  })
}

export function useCreateFeedback() {
  const ownerId = useOwnerId()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      body,
      idempotencyKey,
    }: {
      body: CreateFeedbackRequest
      idempotencyKey: string
    }) => createFeedback(body, idempotencyKey),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: feedbackKeys.all(ownerId) })
    },
  })
}

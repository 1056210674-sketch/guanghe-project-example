import { useMemo, useState } from 'react'
import { isApiError } from '../../../../api/apiError'
import type { AdminRechargeOrderListQuery } from '../../../../api/adminBillingApi'
import type { AdminErrorView } from '../../format'
import {
  useAdminRechargeOrder,
  useAdminRechargeOrders,
  useReconcileAdminRechargeOrder,
} from '../live/orderQueries'
import {
  toAdminRechargeOrderView,
  type AdminReconcileOutcome,
  type AdminRechargeOrderStatus,
} from '../types'
import { AdminRechargeOrdersWorkspace } from './AdminRechargeOrderWorkspace'

const PAGE_SIZE = 20

function toErrorView(error: unknown): AdminErrorView | null {
  if (!error) return null
  if (isApiError(error)) {
    return { message: error.message, traceId: error.traceId, code: error.code }
  }
  return { message: '请求失败，请稍后重试。' }
}

/** The selection captures the filter it was opened under, so the listed account cannot drift. */
interface OrderSelection {
  orderId: string
  scopedUserId: string | null
}

/**
 * Container for the recharge-order console: it owns loading, filtering, the detail selection and
 * the reconcile call. The workspace below it receives view models only and never calls the API.
 */
export function AdminRechargeOrdersConsole() {
  const [userId, setUserId] = useState('')
  const [status, setStatus] = useState<AdminRechargeOrderStatus | undefined>(undefined)
  const [page, setPage] = useState(1)
  const [selection, setSelection] = useState<OrderSelection | undefined>(undefined)
  const [reconcileOutcome, setReconcileOutcome] = useState<AdminReconcileOutcome | null>(null)
  const [reconcileError, setReconcileError] = useState<AdminErrorView | null>(null)

  const listQuery = useMemo<AdminRechargeOrderListQuery>(
    () => ({ userId: userId || undefined, status, page, size: PAGE_SIZE }),
    [userId, status, page],
  )
  const list = useAdminRechargeOrders(listQuery)
  const detail = useAdminRechargeOrder(selection?.orderId)
  const reconcile = useReconcileAdminRechargeOrder()

  // Once the server has answered, the drawer renders that answer rather than the cached copy.
  const reconciledOrder =
    selection && reconcileOutcome?.order.id === selection.orderId
      ? reconcileOutcome.order
      : undefined
  const detailView =
    reconciledOrder ??
    (detail.data ? toAdminRechargeOrderView(detail.data, selection?.scopedUserId ?? null) : undefined)

  const openOrder = (orderId: string) => {
    setSelection({ orderId, scopedUserId: userId.trim() || null })
    setReconcileOutcome(null)
    setReconcileError(null)
  }

  const runReconcile = async (orderId: string) => {
    if (reconcile.isPending) return
    setReconcileError(null)
    setReconcileOutcome(null)
    const previousStatus = detail.data?.status ?? null
    try {
      const order = await reconcile.mutateAsync(orderId)
      setReconcileOutcome({
        order: toAdminRechargeOrderView(order, selection?.scopedUserId ?? null),
        previousStatus,
        statusChanged: previousStatus === null ? null : previousStatus !== order.status,
      })
    } catch (error) {
      setReconcileError(toErrorView(error))
    }
  }

  return (
    <AdminRechargeOrdersWorkspace
      orders={(list.data?.items ?? []).map((order) =>
        toAdminRechargeOrderView(order, userId.trim() || null),
      )}
      userId={userId}
      status={status}
      page={list.data?.page ?? page}
      pageSize={list.data?.size ?? PAGE_SIZE}
      total={list.data?.total ?? 0}
      loading={list.isLoading}
      error={toErrorView(list.error)}
      onUserIdChange={(next) => {
        setUserId(next)
        setPage(1)
      }}
      onStatusChange={(next) => {
        setStatus(next)
        setPage(1)
      }}
      onPageChange={setPage}
      onRetry={() => void list.refetch()}
      onOpenOrder={openOrder}
      detail={detailView}
      detailLoading={detail.isLoading}
      detailError={toErrorView(detail.error)}
      onCloseDetail={() => setSelection(undefined)}
      onRetryDetail={() => void detail.refetch()}
      onReconcile={(orderId) => void runReconcile(orderId)}
      reconcilePending={reconcile.isPending}
      reconcileError={reconcileError}
      reconcileOutcome={reconcileOutcome}
    />
  )
}

import { useMemo, useState } from 'react'
import { isApiError } from '../../../../api/apiError'
import type { AdminInvoiceApplicationListQuery } from '../../../../api/adminBillingApi'
import type { InvoiceApplicationDto } from '../../../../api/billingApi'
import type { AdminErrorView } from '../../format'
import {
  useAcceptInvoiceApplication,
  useAdminInvoiceApplication,
  useAdminInvoiceApplications,
  useApproveInvoiceApplication,
  useDeliverInvoiceApplication,
  useRejectInvoiceApplication,
  useRetryInvoiceWebhook,
} from '../live/invoiceQueries'
import {
  isDeliveryCapabilityRefusal,
  toAdminInvoiceApplicationView,
  type AdminDeliveryRefusalView,
  type AdminInvoiceAction,
  type AdminInvoiceActionOutcome,
  type AdminInvoiceApplicationStatus,
} from '../types'
import { AdminInvoiceWorkspace } from './AdminInvoiceWorkspace'

const PAGE_SIZE = 20

function toErrorView(error: unknown): AdminErrorView | null {
  if (!error) return null
  if (isApiError(error)) {
    return { message: error.message, traceId: error.traceId, code: error.code }
  }
  return { message: '请求失败，请稍后重试。' }
}

/**
 * A refused delivery is reported as the server reported it. It is never turned into a success, and
 * a missing delivery capability is named as such instead of being blamed on the applicant.
 */
function toDeliveryRefusal(error: unknown): AdminDeliveryRefusalView {
  if (isApiError(error)) {
    return {
      message: error.message,
      traceId: error.traceId,
      code: error.code,
      capability: isDeliveryCapabilityRefusal(error),
    }
  }
  return { message: '请求失败，请稍后重试。', capability: false }
}

/** The selection captures the filter it was opened under, so the listed account cannot drift. */
interface InvoiceSelection {
  applicationId: string
  scopedUserId: string | null
}

/**
 * Container for the invoice console: it owns loading, filtering, the detail selection and the four
 * state-machine transitions. The workspace below it receives view models only.
 *
 * Note on capability: the contract exposes no admin-side invoice capability endpoint — only the
 * user-side `getInvoiceCapability`, which an admin console must not call. The delivery capability is
 * therefore learned from the server's answer to an actual delivery attempt, which is exactly what
 * the refusal panel renders.
 */
export function AdminInvoiceConsole() {
  const [userId, setUserId] = useState('')
  const [status, setStatus] = useState<AdminInvoiceApplicationStatus | undefined>(undefined)
  const [page, setPage] = useState(1)
  const [selection, setSelection] = useState<InvoiceSelection | undefined>(undefined)
  const [pendingAction, setPendingAction] = useState<AdminInvoiceAction | undefined>(undefined)
  const [actionError, setActionError] = useState<AdminErrorView | null>(null)
  const [deliveryRefusal, setDeliveryRefusal] = useState<AdminDeliveryRefusalView | null>(null)
  const [actionOutcome, setActionOutcome] = useState<AdminInvoiceActionOutcome | null>(null)

  const listQuery = useMemo<AdminInvoiceApplicationListQuery>(
    () => ({ userId: userId || undefined, status, page, size: PAGE_SIZE }),
    [userId, status, page],
  )
  const list = useAdminInvoiceApplications(listQuery)
  const detail = useAdminInvoiceApplication(selection?.applicationId)
  const accept = useAcceptInvoiceApplication()
  const approve = useApproveInvoiceApplication()
  const reject = useRejectInvoiceApplication()
  const deliver = useDeliverInvoiceApplication()
  const retryWebhook = useRetryInvoiceWebhook()

  const outcomeApplication =
    selection && actionOutcome?.application.id === selection.applicationId
      ? actionOutcome.application
      : undefined
  const detailView =
    outcomeApplication ??
    (detail.data
      ? toAdminInvoiceApplicationView(detail.data, selection?.scopedUserId ?? null)
      : undefined)

  const openApplication = (applicationId: string) => {
    setSelection({ applicationId, scopedUserId: userId.trim() || null })
    setActionError(null)
    setDeliveryRefusal(null)
    setActionOutcome(null)
  }

  const runAction = async (
    action: AdminInvoiceAction,
    run: () => Promise<InvoiceApplicationDto>,
  ) => {
    if (pendingAction) return
    setPendingAction(action)
    setActionError(null)
    setDeliveryRefusal(null)
    setActionOutcome(null)
    try {
      const application = await run()
      setActionOutcome({
        action,
        application: toAdminInvoiceApplicationView(application, selection?.scopedUserId ?? null),
      })
    } catch (error) {
      if (action === 'DELIVER') setDeliveryRefusal(toDeliveryRefusal(error))
      else setActionError(toErrorView(error))
    } finally {
      setPendingAction(undefined)
    }
  }

  return (
    <AdminInvoiceWorkspace
      applications={(list.data?.items ?? []).map((application) =>
        toAdminInvoiceApplicationView(application, userId.trim() || null),
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
      onOpenApplication={openApplication}
      detail={detailView}
      detailLoading={detail.isLoading}
      detailError={toErrorView(detail.error)}
      onCloseDetail={() => setSelection(undefined)}
      onRetryDetail={() => void detail.refetch()}
      pendingAction={pendingAction}
      webhookRetrying={retryWebhook.isPending}
      actionError={actionError}
      deliveryRefusal={deliveryRefusal}
      actionOutcome={actionOutcome}
      onAccept={(applicationId) => void runAction('ACCEPT', () => accept.mutateAsync(applicationId))}
      onApprove={(applicationId) =>
        void runAction('APPROVE', () => approve.mutateAsync(applicationId))
      }
      onReject={(applicationId, reason) =>
        void runAction('REJECT', () =>
          reject.mutateAsync({ applicationId, body: { reason } }),
        )
      }
      onDeliver={(applicationId, invoiceNumber) =>
        void runAction('DELIVER', () => deliver.mutateAsync({ applicationId, body: { invoiceNumber } }))
      }
      onRetryWebhook={(applicationId) => {
        setActionError(null)
        setActionOutcome(null)
        void retryWebhook.mutateAsync(applicationId).catch((error: unknown) => {
          setActionError(toErrorView(error))
        })
      }}
    />
  )
}

/**
 * 积分账务 console — the container.
 *
 * It owns every query, every mutation and every piece of state; the two workspaces below it only
 * render view models and call back. The ledger tab and the manual-adjustment tab are mounted
 * together so that an open confirmation dialog survives a tab switch: the confirmation token is
 * shown once and losing it would strand the pending record.
 */
import { Tabs, Typography } from 'antd'
import { useState } from 'react'
import type { AdminPointLedgerEntryView, AdminLedgerFiltersView, PointAdjustmentDraft, PointAdjustmentView } from '../types'
import {
  CONFIRMATION_EXPIRED_MESSAGE,
  confirmationExpiryState,
  toAdminErrorView,
  toAdminPointLedgerListQuery,
  toAdminPointLedgerSummaryQuery,
  toPointAdjustmentView,
} from '../types'
import {
  useAdminPointLedger,
  useAdminPointLedgerSummary,
  useBookkeepingSummary,
  useConfirmPointAdjustment,
  usePointAdjustments,
  useRequestPointAdjustment,
} from '../live/ledgerQueries'
import { AdminPointLedgerWorkspace } from './AdminPointLedgerWorkspace'
import { AdminAdjustmentWorkspace } from './AdminAdjustmentWorkspace'

const PAGE_SIZE = 20

type ConsoleTab = 'ledger' | 'adjustments'

export function AdminLedgerConsole() {
  const [tab, setTab] = useState<ConsoleTab>('ledger')

  const [filters, setFilters] = useState<AdminLedgerFiltersView>({})
  const [page, setPage] = useState(1)
  const [selectedEntryId, setSelectedEntryId] = useState<string>()

  const [adjustmentUserId, setAdjustmentUserId] = useState<string>()
  const [adjustmentPage, setAdjustmentPage] = useState(1)
  const [requestError, setRequestError] = useState<ReturnType<typeof toAdminErrorView>>(null)
  const [confirmation, setConfirmation] = useState<PointAdjustmentView>()
  const [confirmationError, setConfirmationError] = useState<ReturnType<typeof toAdminErrorView>>(null)
  const [lastApplied, setLastApplied] = useState<PointAdjustmentView>()

  const listQuery = useAdminPointLedger(toAdminPointLedgerListQuery(filters, page, PAGE_SIZE))
  const summaryQuery = useAdminPointLedgerSummary(toAdminPointLedgerSummaryQuery(filters))
  const bookkeepingQuery = useBookkeepingSummary()
  const adjustmentsQuery = usePointAdjustments({
    status: 'PENDING_CONFIRMATION',
    userId: adjustmentUserId || undefined,
    page: adjustmentPage,
    size: PAGE_SIZE,
  })

  const requestAdjustment = useRequestPointAdjustment()
  const confirmAdjustment = useConfirmPointAdjustment()

  const entries = listQuery.data?.items ?? []
  const selectedEntry: AdminPointLedgerEntryView | undefined = entries.find(
    (entry) => entry.id === selectedEntryId,
  )

  /**
   * Step one. A `PENDING_CONFIRMATION` answer means nothing has moved: the response carries the
   * one-time token, and the operator still has to confirm it before any point is credited.
   */
  const handleRequestAdjustment = async (draft: PointAdjustmentDraft): Promise<boolean> => {
    setRequestError(null)
    try {
      const view = toPointAdjustmentView(await requestAdjustment.mutateAsync(draft))
      if (view.status !== 'PENDING_CONFIRMATION') {
        setRequestError({
          message: `服务端返回了 ${view.status} 状态的补录记录，本次不视为待确认，请核对后再操作。`,
        })
        return false
      }
      setLastApplied(undefined)
      setConfirmationError(null)
      setConfirmation(view)
      return true
    } catch (error) {
      setRequestError(toAdminErrorView(error))
      return false
    }
  }

  /** Re-opening a pending row re-checks its expiry now, not when the page was fetched. */
  const handleResumeAdjustment = (adjustmentId: string) => {
    const item = (adjustmentsQuery.data?.items ?? []).find(
      (adjustment) => adjustment.id === adjustmentId,
    )
    if (!item) return
    setConfirmationError(null)
    setConfirmation({
      ...item,
      confirmationExpiryState: confirmationExpiryState(item.confirmationExpiresAt),
    })
  }

  /**
   * Step two. The server moves the points; the UI reports exactly what came back and never closes a
   * failed confirmation, because the operator's typed token is the only copy left.
   */
  const handleConfirmAdjustment = async (adjustmentId: string, confirmationToken: string) => {
    setConfirmationError(null)
    if (!confirmation || confirmation.id !== adjustmentId) return
    if (confirmationExpiryState(confirmation.confirmationExpiresAt) !== 'VALID') {
      setConfirmationError({ message: CONFIRMATION_EXPIRED_MESSAGE })
      return
    }
    try {
      const view = toPointAdjustmentView(
        await confirmAdjustment.mutateAsync({ adjustmentId, body: { confirmationToken } }),
      )
      setConfirmation(undefined)
      setConfirmationError(null)
      setLastApplied(view)
    } catch (error) {
      setConfirmationError(toAdminErrorView(error))
    }
  }

  return (
    <section className="admin-workspace admin-ledger-console" aria-labelledby="admin-ledger-console-title">
      <header className="admin-workspace__heading">
        <div>
          <Typography.Title id="admin-ledger-console-title" level={1}>
            积分账务
          </Typography.Title>
          <Typography.Text type="secondary">
            查询积分流水与账务汇总，人工补录须由操作人二次确认后才入账
          </Typography.Text>
        </div>
      </header>

      <Tabs
        activeKey={tab}
        onChange={(key) => setTab(key as ConsoleTab)}
        items={[
          {
            key: 'ledger',
            label: '积分流水',
            children: (
              <AdminPointLedgerWorkspace
                entries={entries}
                total={listQuery.data?.total ?? 0}
                page={page}
                pageSize={PAGE_SIZE}
                loading={listQuery.isLoading}
                error={toAdminErrorView(listQuery.error)}
                filters={filters}
                summary={summaryQuery.data}
                summaryLoading={summaryQuery.isLoading}
                summaryError={toAdminErrorView(summaryQuery.error)}
                bookkeeping={bookkeepingQuery.data}
                bookkeepingLoading={bookkeepingQuery.isLoading}
                bookkeepingError={toAdminErrorView(bookkeepingQuery.error)}
                detail={selectedEntry}
                onFiltersChange={(next) => {
                  setFilters(next)
                  setPage(1)
                }}
                onPageChange={setPage}
                onOpenEntry={setSelectedEntryId}
                onCloseEntry={() => setSelectedEntryId(undefined)}
                onRetryList={() => void listQuery.refetch()}
                onRetrySummary={() => void summaryQuery.refetch()}
                onRetryBookkeeping={() => void bookkeepingQuery.refetch()}
              />
            ),
          },
          {
            key: 'adjustments',
            label: '人工补录',
            children: (
              <AdminAdjustmentWorkspace
                adjustments={adjustmentsQuery.data?.items ?? []}
                total={adjustmentsQuery.data?.total ?? 0}
                page={adjustmentPage}
                pageSize={PAGE_SIZE}
                loading={adjustmentsQuery.isLoading}
                error={toAdminErrorView(adjustmentsQuery.error)}
                userFilter={adjustmentUserId}
                requestPending={requestAdjustment.isPending}
                requestError={requestError}
                confirmation={confirmation}
                confirmationPending={confirmAdjustment.isPending}
                confirmationError={confirmationError}
                lastApplied={lastApplied}
                onUserFilterChange={(userId) => {
                  setAdjustmentUserId(userId)
                  setAdjustmentPage(1)
                }}
                onPageChange={setAdjustmentPage}
                onRequestAdjustment={handleRequestAdjustment}
                onResumeAdjustment={handleResumeAdjustment}
                onCloseConfirmation={() => {
                  setConfirmation(undefined)
                  setConfirmationError(null)
                }}
                onConfirmAdjustment={(adjustmentId, confirmationToken) => {
                  void handleConfirmAdjustment(adjustmentId, confirmationToken)
                }}
                onRetryList={() => void adjustmentsQuery.refetch()}
              />
            ),
          },
        ]}
      />
    </section>
  )
}

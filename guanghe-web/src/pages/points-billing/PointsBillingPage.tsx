import { Alert, Tabs } from 'antd'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { isApiError } from '../../api/apiError'
import { FeedbackConsole } from '../../features/billing/components/FeedbackConsole'
import { InvoiceConsole } from '../../features/billing/components/InvoiceConsole'
import { RechargeConsole } from '../../features/billing/components/RechargeConsole'
import { RechargeOrderConsole } from '../../features/billing/components/RechargeOrderConsole'
import { parseReturnContext } from '../../features/billing/types'
import {
  PointsBalanceOverview,
  PointsBillingHeader,
  PointsLedgerPanel,
  toPointsLedgerEntry,
  usePointAccountSummary,
  usePointLedger,
} from '../../features/points-billing'
import { UserShell } from '../../layouts/UserShell'
import { errorTextWithDiagnosticTrace } from '../../shared/tracePresentation'

const TAB_KEYS = ['ledger', 'recharge', 'orders', 'invoices', 'feedback'] as const
type TabKey = (typeof TAB_KEYS)[number]

/**
 * 积分与账单.
 *
 * Balance and the ledger come from the point account and the point ledger; recharge, invoice and
 * feedback come from the commercial API. Every panel renders what the server returned — an empty
 * list is an empty state, an unavailable capability is a stated one, and a disabled control always
 * carries the reason it is disabled.
 *
 * A `returnContext` in the URL means the payer arrived here from a task draft, plan, article or GEO
 * plan that ran out of points; the recharge tab is opened for them and the binding is shown as text.
 * The value is never turned into a link on the way in, or rendered as one on the way out. A value
 * that does not parse opens the same tab: the warning explaining that it was ignored lives there,
 * and dropping the payer on 积分明细 would hide the reason from them.
 */
export function PointsBillingPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const rawReturnContext = searchParams.get('returnContext')
  const returnContext = parseReturnContext(rawReturnContext)
  const invalidReturnContext =
    rawReturnContext && !returnContext ? rawReturnContext : undefined
  const requestedTab = searchParams.get('tab')
  const initialTab: TabKey =
    rawReturnContext
      ? 'recharge'
      : TAB_KEYS.includes(requestedTab as TabKey)
        ? (requestedTab as TabKey)
        : 'ledger'

  const activeTab = initialTab
  const rawTargetId = searchParams.get('targetId')
  const targetId = rawTargetId && rawTargetId.length <= 128 ? rawTargetId : undefined
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(20)
  const accountQuery = usePointAccountSummary()
  const ledgerTargetId = activeTab === 'ledger' ? targetId : undefined
  const ledgerQuery = usePointLedger({ page: ledgerTargetId ? 1 : page, size: pageSize, ledgerId: ledgerTargetId })
  const ledgerEntries = (ledgerQuery.data?.items ?? []).map(toPointsLedgerEntry)

  return (
    <UserShell>
      <PointsBillingHeader />
      {accountQuery.isError ? (
        <Alert
          type="error"
          showIcon
          title="积分余额加载失败"
          description="请稍后重试；任务扣费和结算仍以服务端记录为准。"
          action={
            <button
              type="button"
              className="link-button"
              onClick={() => void accountQuery.refetch()}
            >
              重新加载
            </button>
          }
        />
      ) : null}
      <PointsBalanceOverview
        summary={
          accountQuery.data
            ? {
                availablePoints: accountQuery.data.availablePoints,
                frozenPoints: accountQuery.data.frozenPoints,
              }
            : undefined
        }
      />

      <Tabs
        activeKey={activeTab}
        onChange={(key) => {
          const next = new URLSearchParams(searchParams)
          next.set('tab', key)
          next.delete('returnContext')
          next.delete('targetId')
          setSearchParams(next)
        }}
        items={[
          {
            key: 'ledger',
            label: '积分明细',
            children: (
              <>
              {ledgerTargetId ? <Alert showIcon type="info" title="正在查看通知关联的积分明细" description={`流水号：${ledgerTargetId}`} action={<button type="button" className="link-button" onClick={() => {
                const next = new URLSearchParams(searchParams)
                next.delete('targetId')
                setPage(1)
                setSearchParams(next)
              }}>查看全部明细</button>} /> : null}
              <PointsLedgerPanel
                entries={ledgerEntries}
                loading={ledgerQuery.isLoading}
                error={ledgerQuery.error ? ledgerErrorMessage(ledgerQuery.error) : undefined}
                pagination={{
                  current: ledgerQuery.data?.page ?? page,
                  pageSize: ledgerQuery.data?.size ?? pageSize,
                  total: ledgerQuery.data?.total ?? 0,
                }}
                onPageChange={(nextPage, nextPageSize) => {
                  setPage(nextPageSize === pageSize ? nextPage : 1)
                  setPageSize(nextPageSize)
                }}
                onRetry={() => void ledgerQuery.refetch()}
                emptyMessage={ledgerTargetId ? '关联积分记录不存在或不可访问，请查看全部明细。' : undefined}
              />
              </>
            ),
          },
          {
            key: 'recharge',
            label: '积分充值',
            children: (
              <RechargeConsole
                returnContext={returnContext}
                invalidReturnContext={invalidReturnContext}
              />
            ),
          },
          {
            key: 'orders',
            label: '充值记录',
            children: <RechargeOrderConsole key={activeTab === 'orders' ? targetId : undefined} targetId={activeTab === 'orders' ? targetId : undefined} />,
          },
          {
            key: 'invoices',
            label: '发票申请',
            children: <InvoiceConsole targetId={activeTab === 'invoices' ? targetId : undefined} />,
          },
          {
            key: 'feedback',
            label: '意见反馈',
            children: <FeedbackConsole targetId={activeTab === 'feedback' ? targetId : undefined} />,
          },
        ]}
      />
    </UserShell>
  )
}

function ledgerErrorMessage(error: unknown): string {
  if (isApiError(error)) {
    return errorTextWithDiagnosticTrace(error.message, error.traceId)
  }
  return '积分明细加载失败，请稍后重试。'
}

import { Alert, Button, Card, Select, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useState } from 'react'
import { Button as AntButton } from 'antd'
import { errorText } from '../live/commercialError'
import {
  useCloseRechargeOrder,
  useQueryRechargeOrder,
  useRechargeOrder,
  useRechargeOrders,
} from '../live/commercialQueries'
import { RechargeOrderPanel } from './RechargeOrderPanel'
import {
  toRechargeOrderView,
  type RechargeOrderStatus,
  type RechargeOrderView,
} from '../types'

const STATUS_FILTER_OPTIONS: { label: string; value: RechargeOrderStatus | 'ALL' }[] = [
  { label: '全部状态', value: 'ALL' },
  { label: '待支付', value: 'CREATED' },
  { label: '支付中', value: 'PAYING' },
  { label: '已到账', value: 'SUCCEEDED' },
  { label: '已关闭', value: 'CLOSED' },
  { label: '已过期', value: 'EXPIRED' },
  { label: '支付失败', value: 'FAILED' },
]

/**
 * The 充值记录 tab.
 *
 * The status filter is a server query parameter, so what is on screen is what the server returned for
 * that status — not a client-side slice of one page pretending to be a filtered history. 已过期 and
 * 已关闭 are shown as the two different outcomes they are.
 */
export function RechargeOrderConsole({ targetId }: { targetId?: string }) {
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [status, setStatus] = useState<RechargeOrderStatus | 'ALL'>('ALL')
  const [selectedOrderId, setSelectedOrderId] = useState<string | undefined>(targetId)
  const [actionNotice, setActionNotice] = useState<string>()

  const ordersQuery = useRechargeOrders({
    page,
    size: pageSize,
    status: status === 'ALL' ? undefined : status,
  })
  const detailQuery = useRechargeOrder(selectedOrderId)
  const queryOrder = useQueryRechargeOrder()
  const closeOrder = useCloseRechargeOrder()

  const orders = (ordersQuery.data?.items ?? []).map(toRechargeOrderView)
  const selectedOrder = detailQuery.data
    ? toRechargeOrderView(detailQuery.data)
    : orders.find((order) => order.id === selectedOrderId)

  const columns: ColumnsType<RechargeOrderView> = [
    { title: '订单号', dataIndex: 'orderNo', key: 'orderNo' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (_value, order) => <Tag color="default">{order.statusLabel}</Tag>,
    },
    { title: '金额', dataIndex: 'amountText', key: 'amountText' },
    { title: '积分', dataIndex: 'pointsText', key: 'pointsText' },
    { title: '渠道', dataIndex: 'provider', key: 'provider' },
    { title: '创建时间', dataIndex: 'createdAtText', key: 'createdAtText' },
    { title: '支付截止', dataIndex: 'expiresAtText', key: 'expiresAtText' },
    {
      title: '操作',
      key: 'actions',
      render: (_value, order) => (
        <AntButton
          type="link"
          onClick={() => {
            setActionNotice(undefined)
            setSelectedOrderId(order.id)
          }}
        >
          查看状态
        </AntButton>
      ),
    },
  ]

  return (
    <Space orientation="vertical" size="middle" style={{ display: 'flex' }}>
      <Card title="充值记录" variant="borderless">
        <Space orientation="vertical" size="small" style={{ display: 'flex' }}>
          <Space wrap>
            <Select
              aria-label="订单状态筛选"
              value={status}
              style={{ minWidth: 160 }}
              options={STATUS_FILTER_OPTIONS}
              onChange={(value) => {
                setStatus(value)
                setPage(1)
                setSelectedOrderId(undefined)
              }}
            />
            <Button onClick={() => void ordersQuery.refetch()} loading={ordersQuery.isFetching}>
              刷新
            </Button>
          </Space>

          {ordersQuery.isError ? (
            <Alert
              type="error"
              showIcon
              title="充值记录加载失败"
              description={errorText(ordersQuery.error)}
            />
          ) : (
            <Table<RechargeOrderView>
              rowKey="id"
              size="small"
              columns={columns}
              dataSource={orders}
              loading={ordersQuery.isLoading}
              locale={{
                emptyText:
                  status === 'ALL'
                    ? '暂无充值订单：当前账户还没有任何充值记录。'
                    : '该状态下没有充值订单。',
              }}
              pagination={{
                current: ordersQuery.data?.page ?? page,
                pageSize: ordersQuery.data?.size ?? pageSize,
                total: ordersQuery.data?.total ?? 0,
                showSizeChanger: true,
                onChange: (nextPage, nextPageSize) => {
                  setPage(nextPageSize === pageSize ? nextPage : 1)
                  setPageSize(nextPageSize)
                },
              }}
            />
          )}
        </Space>
      </Card>

      {selectedOrderId && detailQuery.isError ? <Alert type="error" showIcon title="关联充值记录无法加载" description={errorText(detailQuery.error)} action={<Button onClick={() => void detailQuery.refetch()}>重新加载</Button>} /> : null}
      {selectedOrderId && detailQuery.isLoading ? <Typography.Text>正在加载关联充值记录…</Typography.Text> : null}
      {selectedOrder && !detailQuery.isError ? (
        <Card title="订单状态" variant="borderless">
          <RechargeOrderPanel
            order={selectedOrder}
            title={`订单 ${selectedOrder.orderNo}`}
            pendingAction={
              queryOrder.isPending ? 'query' : closeOrder.isPending ? 'close' : undefined
            }
            actionNotice={actionNotice}
            actionError={
              queryOrder.isError
                  ? errorText(queryOrder.error)
                  : closeOrder.isError
                    ? errorText(closeOrder.error)
                    : undefined
            }
            onQuery={(orderId) => {
              setActionNotice(undefined)
              queryOrder.mutate(orderId, {
                onSuccess: (order) =>
                  setActionNotice(
                    order.status === 'CREATED' || order.status === 'PAYING'
                      ? `已向服务端确认：订单仍为「${order.status === 'CREATED' ? '待支付' : '支付中'}」，没有支付成功的结论。`
                      : `服务端返回的最新状态：${order.status}`,
                  ),
              })
            }}
            onClose={(orderId) => {
              setActionNotice(undefined)
              closeOrder.mutate(orderId, {
                onSuccess: (order) => setActionNotice(`订单已关闭，服务端状态：${order.status}`),
              })
            }}
          />
          <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
            状态以服务端返回为准；本页不提供任何本地标记支付成功或补记积分的入口。
          </Typography.Paragraph>
        </Card>
      ) : null}
    </Space>
  )
}

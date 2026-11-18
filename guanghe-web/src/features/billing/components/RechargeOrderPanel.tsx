import { Button, Descriptions, Space, Tag, Typography } from 'antd'
import { Link } from 'react-router-dom'
import type { RechargeOrderView } from '../types'

/**
 * One recharge order, as the server last described it.
 *
 * The payment code is rendered as text, never as an image or a link: `codeUrl` is only ever set from
 * a real provider response, and while the channel is off it stays empty. Printing the URL string
 * next to the fact that no code exists is the honest rendering — drawing a QR placeholder or hiding
 * the row would both suggest a payment step that has not happened.
 */
export function RechargeOrderPanel({
  order,
  title,
  pendingAction,
  onQuery,
  onClose,
  actionError,
  actionNotice,
}: {
  order: RechargeOrderView
  title?: string
  pendingAction?: 'query' | 'close'
  onQuery?: (orderId: string) => void
  onClose?: (orderId: string) => void
  actionError?: string
  actionNotice?: string
}) {
  const busy = pendingAction !== undefined

  return (
    <section aria-label={`充值订单 ${order.orderNo}`}>
      <Typography.Title level={title ? 4 : 5}>
        {title ?? `订单 ${order.orderNo}`}
      </Typography.Title>
      <Descriptions column={1} size="small" bordered>
        <Descriptions.Item label="订单号">{order.orderNo}</Descriptions.Item>
        <Descriptions.Item label="状态">
          <Tag color="default">{order.statusLabel}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="金额">{order.amountText}</Descriptions.Item>
        <Descriptions.Item label="到账积分">{order.pointsText}</Descriptions.Item>
        <Descriptions.Item label="支付渠道">{order.provider}</Descriptions.Item>
        <Descriptions.Item label="支付平台单号">
          {order.providerOrderNo ?? '—（通道未返回）'}
        </Descriptions.Item>
        <Descriptions.Item label="支付二维码">
          {order.hasPaymentCode ? (
            <Typography.Text>{order.codeUrl}</Typography.Text>
          ) : (
            <Typography.Text type="secondary">
              未生成：支付通道未启用，本环境不会产生真实支付二维码
            </Typography.Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="下单时间">{order.createdAtText}</Descriptions.Item>
        <Descriptions.Item label="支付截止">{order.expiresAtText}</Descriptions.Item>
        <Descriptions.Item label="支付时间">{order.paidAtText}</Descriptions.Item>
        <Descriptions.Item label="关闭时间">{order.closedAtText}</Descriptions.Item>
        <Descriptions.Item label="失败时间">{order.failedAtText}</Descriptions.Item>
        <Descriptions.Item label="失败原因">{order.failureCode ?? '—'}</Descriptions.Item>
      </Descriptions>

      {order.returnContext ? (
        <Typography.Paragraph type="secondary">
          本单绑定来源：{order.returnContext.kindLabel}（{order.returnContext.raw}）。
          <Link to={order.returnContext.path}>{order.returnContext.pathLabel}</Link>
        </Typography.Paragraph>
      ) : null}

      {actionNotice ? (
        <Typography.Paragraph role="status">{actionNotice}</Typography.Paragraph>
      ) : null}
      {actionError ? (
        <Typography.Paragraph type="danger" role="alert">
          {actionError}
        </Typography.Paragraph>
      ) : null}

      {order.canQuery || order.canClose ? (
        <Space wrap>
          {order.canQuery && onQuery ? (
            <Button
              type="primary"
              loading={pendingAction === 'query'}
              disabled={busy && pendingAction !== 'query'}
              onClick={() => onQuery(order.id)}
            >
              查询支付结果
            </Button>
          ) : null}
          {order.canClose && onClose ? (
            <Button
              danger
              loading={pendingAction === 'close'}
              disabled={busy && pendingAction !== 'close'}
              onClick={() => onClose(order.id)}
            >
              关闭订单
            </Button>
          ) : null}
        </Space>
      ) : (
        <Typography.Text type="secondary">
          该订单已进入终态（{order.statusLabel}），不再提供查询或关闭操作。
        </Typography.Text>
      )}

      {order.status === 'CREATED' || order.status === 'PAYING' ? (
        <Typography.Paragraph type="secondary">
          提示：本环境未启用真实微信支付，「查询支付结果」只会向服务端询问当前状态，不会产生任何支付成功的结论。
        </Typography.Paragraph>
      ) : null}
    </section>
  )
}

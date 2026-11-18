import { Alert, Button, Card, InputNumber, Radio, Space, Tag, Typography } from 'antd'
import { useRef, useState } from 'react'
import { formatCents, formatPoints, yuanToCents } from '../../../shared/format'
import { errorText } from '../live/commercialError'
import {
  useActiveRateCard,
  useCloseRechargeOrder,
  useCreateRechargeOrder,
  useQueryRechargeOrder,
  useRechargeCapability,
} from '../live/commercialQueries'
import { RechargeOrderPanel } from './RechargeOrderPanel'
import {
  toRateCardView,
  toRechargeCapabilityView,
  toRechargeOrderView,
  type ParsedReturnContext,
} from '../types'

/**
 * The 积分充值 tab.
 *
 * The payable flag decides whether the create button exists in an enabled state, and the reason it is
 * disabled is always on screen with the server's own status token next to it. Nothing here fakes a
 * payment step: with the channel off there is no order to pay, no code to scan and no result to poll
 * for success.
 */
export function RechargeConsole({
  returnContext,
  invalidReturnContext,
}: {
  returnContext?: ParsedReturnContext
  invalidReturnContext?: string
}) {
  const capabilityQuery = useRechargeCapability()
  const rateCardQuery = useActiveRateCard()
  const createOrder = useCreateRechargeOrder()
  const queryOrder = useQueryRechargeOrder()
  const closeOrder = useCloseRechargeOrder()

  const [selectedTierId, setSelectedTierId] = useState<string>()
  const [manualYuan, setManualYuan] = useState<number | null>(null)
  const [actionNotice, setActionNotice] = useState<string>()
  // The idempotency key belongs to the amount currently on screen. Re-submitting the same amount
  // reuses it so the server can recognise the replay; changing the amount makes it a new action.
  const draftKey = useRef<{ amountCents: number; key: string } | undefined>(undefined)

  const capability = capabilityQuery.data
    ? toRechargeCapabilityView(capabilityQuery.data)
    : undefined
  const rateCard = rateCardQuery.data ? toRateCardView(rateCardQuery.data) : undefined

  const tier = rateCard?.tiers.find((candidate) => candidate.tierId === selectedTierId)
  const manualCents = manualYuan === null ? undefined : yuanToCents(manualYuan)
  const amountCents = tier?.amountCents ?? manualCents

  const minCents = capability?.minAmountCents
  const maxCents = capability?.maxAmountCents
  const amountProblem = amountProblemFor(amountCents, minCents, maxCents, manualYuan)
  const payable = capability?.available === true
  const creating = createOrder.isPending
  const submitDisabled = !payable || amountCents === undefined || amountProblem !== undefined

  const createdOrder = createOrder.data ? toRechargeOrderView(createOrder.data) : undefined
  const pendingAction = queryOrder.isPending
    ? ('query' as const)
    : closeOrder.isPending
      ? ('close' as const)
      : undefined

  function idempotencyKeyFor(targetAmountCents: number): string {
    if (draftKey.current?.amountCents === targetAmountCents) return draftKey.current.key
    const key = crypto.randomUUID()
    draftKey.current = { amountCents: targetAmountCents, key }
    return key
  }

  return (
    <Space orientation="vertical" size="middle" style={{ display: 'flex' }}>
      <Card title="充值能力" variant="borderless">
        {capabilityQuery.isError ? (
          <Alert
            type="error"
            showIcon
            title="充值能力查询失败"
            description={errorText(capabilityQuery.error)}
            action={
              <Button type="link" onClick={() => void capabilityQuery.refetch()}>
                重新加载
              </Button>
            }
          />
        ) : capability === undefined ? (
          <Typography.Text type="secondary">正在查询充值能力…</Typography.Text>
        ) : (
          <Space orientation="vertical" size="small" style={{ display: 'flex' }}>
            <Space wrap>
              <Tag color={capability.available ? 'success' : 'warning'}>
                {capability.statusLabel}
              </Tag>
              <Typography.Text code>{capability.statusToken}</Typography.Text>
              <Typography.Text type="secondary">渠道：{capability.provider}</Typography.Text>
              <Typography.Text type="secondary">
                回调验签：{capability.callbackVerifiable ? '可用' : '不可用'}
              </Typography.Text>
            </Space>
            <Typography.Paragraph style={{ marginBottom: 0 }}>
              {capability.detail}
            </Typography.Paragraph>
            {capability.missingRequirements.length > 0 ? (
              <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                缺少的配置项：{capability.missingRequirements.join('、')}
                （仅列出名称，不含任何密钥值）
              </Typography.Paragraph>
            ) : null}
            <Typography.Text type="secondary">
              单笔范围：{formatCents(capability.minAmountCents)} –{' '}
              {formatCents(capability.maxAmountCents)}；订单有效期 {capability.ttlSeconds} 秒。
            </Typography.Text>
          </Space>
        )}
      </Card>

      <Card title="费率与金额" variant="borderless">
        {rateCardQuery.isError ? (
          <Alert
            type="error"
            showIcon
            title="费率加载失败"
            description={errorText(rateCardQuery.error)}
            action={
              <Button type="link" onClick={() => void rateCardQuery.refetch()}>
                重新加载
              </Button>
            }
          />
        ) : rateCard === undefined ? (
          <Typography.Text type="secondary">正在加载当前费率…</Typography.Text>
        ) : (
          <Space orientation="vertical" size="small" style={{ display: 'flex' }}>
            <Space wrap>
              <Typography.Text>
                当前费率：{formatPoints(rateCard.pointsPerYuan)} 积分 / 元
              </Typography.Text>
              {rateCard.productionRate ? (
                <Tag color="blue">
                  正式费率{rateCard.versionNo === undefined ? '' : ` · 版本 #${rateCard.versionNo}`}
                </Tag>
              ) : (
                <Tag color="orange">非生产费率（占位）</Tag>
              )}
            </Space>
            {rateCard.productionRate ? null : (
              <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                服务端标记该卡为非生产费率：没有已发布的定价版本，因此不会返回版本号。此费率仅用于联调，
                不代表真实定价，到账积分以订单创建结果为准。
              </Typography.Paragraph>
            )}
            {rateCard.note ? (
              <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                说明：{rateCard.note}
              </Typography.Paragraph>
            ) : null}
            <Typography.Text type="secondary">
              发布时间：{rateCard.publishedAt ?? '—（未发布）'}
            </Typography.Text>

            {rateCard.tiers.length > 0 ? (
              <Radio.Group
                aria-label="选择充值金额"
                value={selectedTierId}
                onChange={(event) => {
                  setSelectedTierId(event.target.value as string)
                  setManualYuan(null)
                }}
              >
                <Space orientation="vertical">
                  {rateCard.tiers.map((candidate) => (
                    <Radio key={candidate.tierId} value={candidate.tierId}>
                      {candidate.label}：{formatCents(candidate.amountCents)} · 到账{' '}
                      {formatPoints(candidate.points)} 积分
                      {candidate.giftPoints > 0
                        ? `（含赠送 ${formatPoints(candidate.giftPoints)}）`
                        : ''}
                    </Radio>
                  ))}
                </Space>
              </Radio.Group>
            ) : (
              <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                服务端未返回固定充值档位，请按整元输入金额；预计积分仅供参考，最终以订单创建结果为准。
              </Typography.Paragraph>
            )}

            <Space wrap>
              <Typography.Text>自定义金额（元）</Typography.Text>
              <InputNumber
                aria-label="自定义充值金额（元）"
                min={0}
                step={1}
                precision={0}
                value={manualYuan}
                onChange={(value) => {
                  setManualYuan(typeof value === 'number' ? value : null)
                  setSelectedTierId(undefined)
                }}
              />
              {amountCents === undefined ? null : (
                <Typography.Text type="secondary">
                  应付 {formatCents(amountCents)}
                  {capability === undefined
                    ? ''
                    : ` · 预计到账 ${formatPoints(estimatePoints(amountCents, capability.pointsPerYuan))} 积分（以服务端为准）`}
                </Typography.Text>
              )}
            </Space>

            {returnContext ? (
              <Alert
                type="info"
                showIcon
                title={`本次充值将绑定来源：${returnContext.kindLabel}`}
                description={`来源标识 ${returnContext.raw}（按文本展示，不作为链接地址）；充值完成后可回到原页面继续。`}
              />
            ) : null}
            {invalidReturnContext ? (
              <Alert
                type="warning"
                showIcon
                title="链接中的来源标识格式不正确，已忽略"
                description={`收到的值为「${invalidReturnContext}」，不会随充值订单提交。`}
              />
            ) : null}

            {amountProblem ? (
              <Typography.Text type="warning">{amountProblem}</Typography.Text>
            ) : null}
            {!payable ? (
              <Typography.Text type="warning">
                当前不可创建充值订单：{capability?.statusLabel ?? '充值能力未知'}。
              </Typography.Text>
            ) : null}

            <Button
              type="primary"
              loading={creating}
              disabled={submitDisabled}
              onClick={() => {
                if (amountCents === undefined) return
                setActionNotice(undefined)
                createOrder.mutate({
                  body: {
                    amountCents,
                    returnContext: returnContext?.raw ?? null,
                  },
                  idempotencyKey: idempotencyKeyFor(amountCents),
                })
              }}
            >
              创建充值订单
            </Button>
            {submitDisabled && !creating ? (
              <Typography.Text type="secondary">
                按钮不可用的原因：
                {!payable
                  ? capability === undefined
                    ? '充值能力尚未返回'
                    : capability.statusLabel
                  : amountCents === undefined
                    ? '尚未选择或输入金额'
                    : amountProblem}
              </Typography.Text>
            ) : null}
          </Space>
        )}
      </Card>

      {createOrder.isError ? (
        <Alert type="error" showIcon title="创建充值订单失败" description={errorText(createOrder.error)} />
      ) : null}

      {createdOrder ? (
        <Card title="刚创建的订单" variant="borderless">
          <RechargeOrderPanel
            order={createdOrder}
            pendingAction={pendingAction}
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
        </Card>
      ) : null}
    </Space>
  )
}

function amountProblemFor(
  amountCents: number | undefined,
  minCents: number | undefined,
  maxCents: number | undefined,
  manualYuan: number | null,
): string | undefined {
  if (amountCents === undefined) return undefined
  if (manualYuan !== null && (!Number.isSafeInteger(manualYuan) || manualYuan <= 0)) {
    return '金额必须是大于 0 的整元金额'
  }
  if (minCents !== undefined && amountCents < minCents) {
    return `金额低于服务端下限 ${formatCents(minCents)}`
  }
  if (maxCents !== undefined && amountCents > maxCents) {
    return `金额高于服务端上限 ${formatCents(maxCents)}`
  }
  return undefined
}

function estimatePoints(amountCents: number, pointsPerYuan: number): number | undefined {
  const yuan = amountCents / 100
  const points = yuan * pointsPerYuan
  return Number.isSafeInteger(points) ? points : undefined
}

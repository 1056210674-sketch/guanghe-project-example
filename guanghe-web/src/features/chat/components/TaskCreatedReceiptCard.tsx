import {
  CheckCircleFilled,
  PictureOutlined,
  RightOutlined,
} from '@ant-design/icons'
import { Button, Tag, Typography } from 'antd'
import { useState } from 'react'
import type { TaskCreatedReceiptView } from '../types'

const SINGLE_TASK_MESSAGE =
  '该任务已经提交，无需再次确认。'

export interface TaskCreatedReceiptCardProps {
  receipt: TaskCreatedReceiptView
  onViewTask: (taskId: string) => void
}

function isValidPoints(value: number) {
  return Number.isSafeInteger(value) && value >= 0
}

function formatIntegerPoints(value: number) {
  return isValidPoints(value) ? value.toLocaleString('zh-CN') : '—'
}

function ReceiptProductImage({
  name,
  src,
}: {
  name?: string
  src?: string
}) {
  const [loadFailed, setLoadFailed] = useState(false)
  const accessibleName = name ?? '关联商品'

  if (!src || loadFailed) {
    return (
      <span
        className="task-created-receipt__image-placeholder"
        role="img"
        aria-label={`${accessibleName}暂无商品图片`}
      >
        <PictureOutlined aria-hidden="true" />
      </span>
    )
  }

  return (
    <img
      className="task-created-receipt__image"
      src={src}
      alt={`${accessibleName}商品图片`}
      width={48}
      height={48}
      loading="lazy"
      onError={() => setLoadFailed(true)}
    />
  )
}

export function TaskCreatedReceiptCard({
  receipt,
  onViewTask,
}: TaskCreatedReceiptCardProps) {
  const {
    taskId,
    taskNumber,
    title,
    taskType,
    statusLabel,
    statusTone,
    productName,
    productImageUrl,
    version,
    pointsLabel,
    pointsAmount,
    frozenPoints,
    pointBalance,
    createdAt,
    message,
    detailEnabled,
  } = receipt
  const displayedPoints = pointsAmount ?? frozenPoints
  const displayedPointsLabel = pointsLabel ?? '冻结积分'

  return (
    <section
      className="task-created-receipt"
      aria-label={`任务创建成功回执：${title}`}
    >
      <header className="task-created-receipt__header">
        <div className="task-created-receipt__title">
          <CheckCircleFilled aria-hidden="true" />
          <Typography.Title level={3}>{title}</Typography.Title>
        </div>
        <Tag
          className={`task-created-receipt__status task-created-receipt__status--${statusTone}`}
        >
          {statusLabel}
        </Tag>
      </header>

      {message ? (
        <Typography.Paragraph className="task-created-receipt__message">
          {message}
        </Typography.Paragraph>
      ) : null}

      <dl className="task-created-receipt__details">
        <div>
          <dt>任务号</dt>
          <dd>{taskNumber}</dd>
        </div>
        <div>
          <dt>任务类型</dt>
          <dd>{taskType}</dd>
        </div>
        {productName || productImageUrl ? (
          <div>
            <dt>关联商品</dt>
            <dd className="task-created-receipt__product">
              <ReceiptProductImage
                key={productImageUrl ?? 'no-image'}
                name={productName}
                src={productImageUrl}
              />
              {productName ? <strong>{productName}</strong> : null}
            </dd>
          </div>
        ) : null}
        {version ? (
          <div>
            <dt>确认版本</dt>
            <dd>{version}</dd>
          </div>
        ) : null}
        {displayedPoints !== undefined || pointsLabel !== undefined ? (
          <div>
            <dt>{displayedPointsLabel}</dt>
            <dd className="task-created-receipt__points">
              <strong>{displayedPoints === undefined ? '暂不可查' : `${formatIntegerPoints(displayedPoints)} 积分`}</strong>
              {pointBalance !== undefined ? (
                <span>
                  当前余额 {formatIntegerPoints(pointBalance)} 积分
                </span>
              ) : null}
            </dd>
          </div>
        ) : pointBalance !== undefined ? (
          <div>
            <dt>当前积分余额</dt>
            <dd>{formatIntegerPoints(pointBalance)} 积分</dd>
          </div>
        ) : null}
        {createdAt ? (
          <div>
            <dt>创建时间</dt>
            <dd>{createdAt}</dd>
          </div>
        ) : null}
      </dl>

      <div
        className="task-created-receipt__notice"
        role="note"
        aria-label="单任务保护说明"
      >
        <span aria-hidden="true">i</span>
        <strong>{SINGLE_TASK_MESSAGE}</strong>
      </div>

      <div className="task-created-receipt__actions">
        <Button
          aria-label="查看任务详情"
          disabled={!detailEnabled}
          icon={<RightOutlined aria-hidden="true" />}
          iconPlacement="end"
          onClick={() => {
            if (detailEnabled) {
              onViewTask(taskId)
            }
          }}
        >
          查看任务详情
        </Button>
      </div>
    </section>
  )
}

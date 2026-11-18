import {
  ClockCircleOutlined,
  PictureOutlined,
  RightOutlined,
} from '@ant-design/icons'
import { Button, Tag, Typography } from 'antd'
import { useState } from 'react'
import type { ParallelTaskGroupView, ParallelTaskItemView } from '../types'

export interface ParallelTaskGroupProps {
  group: ParallelTaskGroupView
  onViewTask: (taskId: string) => void
}

function isValidProgress(value?: number): value is number {
  return (
    value !== undefined &&
    Number.isFinite(value) &&
    value >= 0 &&
    value <= 100
  )
}

function ParallelTaskProductImage({
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
        className="parallel-task-card__image-placeholder"
        role="img"
        aria-label={`${accessibleName}暂无商品图片`}
      >
        <PictureOutlined aria-hidden="true" />
      </span>
    )
  }

  return (
    <img
      className="parallel-task-card__image"
      src={src}
      alt={`${accessibleName}商品图片`}
      width={52}
      height={52}
      loading="lazy"
      onError={() => setLoadFailed(true)}
    />
  )
}

function ParallelTaskCard({
  task,
  onViewTask,
}: {
  task: ParallelTaskItemView
  onViewTask: (taskId: string) => void
}) {
  const {
    taskId,
    taskNumber,
    taskType,
    statusLabel,
    statusTone,
    productName,
    productImageUrl,
    progressLabel,
    progressPercent,
    timingLabel,
    detailEnabled,
  } = task
  const validProgress = isValidProgress(progressPercent)
    ? progressPercent
    : undefined

  return (
    <li className="parallel-task-card" aria-label={`任务：${taskNumber}`}>
      <header className="parallel-task-card__header">
        <Typography.Text strong>{taskType}</Typography.Text>
        <Tag
          className={`parallel-task-card__status parallel-task-card__status--${statusTone}`}
        >
          {statusLabel}
        </Tag>
      </header>

      <Typography.Text
        className="parallel-task-card__number"
        type="secondary"
      >
        任务号：{taskNumber}
      </Typography.Text>

      {productName || productImageUrl ? (
        <div className="parallel-task-card__product">
          <ParallelTaskProductImage
            key={productImageUrl ?? 'no-image'}
            name={productName}
            src={productImageUrl}
          />
          {productName ? (
            <Typography.Text strong>{productName}</Typography.Text>
          ) : null}
        </div>
      ) : null}

      <div className="parallel-task-card__status-details">
        {progressLabel ? (
          <Typography.Text strong>{progressLabel}</Typography.Text>
        ) : null}
        {validProgress !== undefined ? (
          <div
            className="parallel-task-card__progress"
            role="progressbar"
            aria-label={`${taskNumber}任务进度`}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={validProgress}
          >
            <span style={{ width: `${validProgress}%` }} />
          </div>
        ) : null}
        {timingLabel ? (
          <Typography.Text
            className="parallel-task-card__timing"
            type="secondary"
          >
            <ClockCircleOutlined aria-hidden="true" />
            {timingLabel}
          </Typography.Text>
        ) : null}
      </div>

      <Button
        block
        className="parallel-task-card__view"
        disabled={!detailEnabled}
        icon={<RightOutlined aria-hidden="true" />}
        iconPlacement="end"
        onClick={() => {
          if (detailEnabled) {
            onViewTask(taskId)
          }
        }}
      >
        查看任务
      </Button>
    </li>
  )
}

export function ParallelTaskGroup({
  group,
  onViewTask,
}: ParallelTaskGroupProps) {
  if (group.tasks.length === 0) {
    return null
  }

  return (
    <section
      className="parallel-task-group"
      aria-label={`并行任务组：${group.title}`}
    >
      <Typography.Title level={3}>{group.title}</Typography.Title>
      <ul className="parallel-task-group__tasks">
        {group.tasks.map((task) => (
          <ParallelTaskCard
            key={task.taskId}
            task={task}
            onViewTask={onViewTask}
          />
        ))}
      </ul>
    </section>
  )
}

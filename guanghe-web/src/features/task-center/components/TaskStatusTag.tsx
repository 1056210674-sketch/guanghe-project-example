import { Tag } from 'antd'
import {
  TASK_CENTER_STATUS_LABELS,
  TASK_CENTER_STATUS_TONES,
} from '../presentation'
import type { TaskCenterTaskStatus } from '../types'

export function TaskStatusTag({ status }: { status: TaskCenterTaskStatus }) {
  const tone = TASK_CENTER_STATUS_TONES[status]

  return (
    <Tag className={`task-center-status task-center-status--${tone}`}>
      {TASK_CENTER_STATUS_LABELS[status]}
    </Tag>
  )
}

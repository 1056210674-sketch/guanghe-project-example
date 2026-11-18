import { Button, Space } from 'antd'
import { TASK_CENTER_ACTION_LABELS } from '../presentation'
import type {
  TaskCenterActionType,
  TaskCenterActionView,
  TaskCenterId,
} from '../types'

export function TaskActionButtons({
  taskId,
  actions,
  onAction,
  compact = false,
}: {
  taskId: TaskCenterId
  actions: readonly TaskCenterActionView[]
  onAction: (taskId: TaskCenterId, actionType: TaskCenterActionType) => void
  compact?: boolean
}) {
  if (actions.length === 0) {
    return null
  }

  return (
    <Space size={compact ? 2 : 8} wrap>
      {actions.map((action, index) => (
        <Button
          key={`${index}-${action.type}`}
          type={compact ? 'link' : 'default'}
          size={compact ? 'small' : 'middle'}
          disabled={!action.enabled}
          onClick={() => {
            if (action.enabled) {
              onAction(taskId, action.type)
            }
          }}
        >
          {TASK_CENTER_ACTION_LABELS[action.type]}
        </Button>
      ))}
    </Space>
  )
}

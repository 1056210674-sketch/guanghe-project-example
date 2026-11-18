import type { TaskCenterDetailDrawerProps } from './TaskCenterDetailDrawer'
import { TaskCenterDetailDrawer } from './TaskCenterDetailDrawer'
import type { TaskCenterListProps } from './TaskCenterList'
import { TaskCenterList } from './TaskCenterList'

export interface TaskCenterProps {
  list: TaskCenterListProps
  detail: TaskCenterDetailDrawerProps
}

export function TaskCenter({ list, detail }: TaskCenterProps) {
  return (
    <>
      <TaskCenterList {...list} />
      <TaskCenterDetailDrawer {...detail} />
    </>
  )
}

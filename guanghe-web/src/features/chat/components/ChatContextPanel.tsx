import { Empty } from 'antd'
import type {
  ChatHistoricalContextView,
  ChatTaskFocusOptionView,
  ChatTaskStatusSummaryView,
  ChatTaskContextView,
} from '../types'
import { ChatCurrentTaskContext } from './ChatCurrentTaskContext'
import { ChatHistoricalTaskContext } from './ChatHistoricalTaskContext'
import { ChatTaskFocusSwitcher } from './ChatTaskFocusSwitcher'
import { ChatTaskStatusOverview } from './ChatTaskStatusOverview'

export interface ChatContextPanelProps {
  taskFocusOptions: readonly ChatTaskFocusOptionView[]
  taskStatusSummaries: readonly ChatTaskStatusSummaryView[]
  selectedTaskId?: string
  currentTaskContext?: ChatTaskContextView
  historicalTaskContext?: ChatHistoricalContextView
  historicalContextExpanded?: boolean
  onSelectTaskFocus: (taskId: string) => void
  onToggleHistoricalContext: (contextId: string, expanded: boolean) => void
  onOpenReferenceAsset: (assetId: string) => void
}

/** Task details only. Customer scope is selected once, when starting a project chat. */
export function ChatContextPanel({
  taskFocusOptions,
  taskStatusSummaries,
  selectedTaskId,
  currentTaskContext,
  historicalTaskContext,
  historicalContextExpanded = false,
  onSelectTaskFocus,
  onToggleHistoricalContext,
  onOpenReferenceAsset,
}: ChatContextPanelProps) {
  const hasTasks = Boolean(taskFocusOptions.length || taskStatusSummaries.length
    || currentTaskContext || historicalTaskContext)
  return <aside className="chat-task-panel" aria-label="本次聊天任务">
    {!hasTasks ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="本次聊天暂无执行任务" /> : null}
    <ChatTaskFocusSwitcher options={taskFocusOptions} selectedTaskId={selectedTaskId} onSelect={onSelectTaskFocus} />
    {currentTaskContext ? <ChatCurrentTaskContext context={currentTaskContext} onOpenReferenceAsset={onOpenReferenceAsset} /> : null}
    {historicalTaskContext ? <ChatHistoricalTaskContext context={historicalTaskContext} expanded={historicalContextExpanded}
      onToggle={onToggleHistoricalContext} onOpenReferenceAsset={onOpenReferenceAsset} /> : null}
    <ChatTaskStatusOverview summaries={taskStatusSummaries} selectedTaskId={selectedTaskId} onSelect={onSelectTaskFocus} />
  </aside>
}

import type { ChatTaskStatusSummaryView } from '../types'

export interface ChatTaskStatusOverviewProps {
  summaries: readonly ChatTaskStatusSummaryView[]
  selectedTaskId?: string
  onSelect: (taskId: string) => void
}

export function ChatTaskStatusOverview({
  summaries,
  selectedTaskId,
  onSelect,
}: ChatTaskStatusOverviewProps) {
  if (summaries.length === 0) {
    return null
  }

  return (
    <section
      className="chat-task-status-overview"
      aria-label="任务状态总览"
    >
      <h3>任务状态总览</h3>
      <ul className="chat-task-status-overview__list">
        {summaries.map((summary) => {
          const selected = summary.taskId === selectedTaskId

          return (
            <li key={summary.taskId}>
              <button
                type="button"
                className={`chat-task-status-overview__item${
                  selected
                    ? ' chat-task-status-overview__item--selected'
                    : ''
                }`}
                aria-pressed={selected}
                disabled={!summary.focusEnabled}
                onClick={() => {
                  if (summary.focusEnabled) {
                    onSelect(summary.taskId)
                  }
                }}
              >
                <span className="chat-task-status-overview__label">
                  {summary.label}
                </span>
                <span
                  className={`chat-task-status-overview__status chat-task-status-overview__status--${summary.statusTone}`}
                >
                  <span
                    className="chat-task-status-overview__status-dot"
                    aria-hidden="true"
                  />
                  {summary.statusLabel}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

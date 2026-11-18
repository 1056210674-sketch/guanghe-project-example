import type { ChatTaskFocusOptionView } from '../types'

export interface ChatTaskFocusSwitcherProps {
  options: readonly ChatTaskFocusOptionView[]
  selectedTaskId?: string
  onSelect: (taskId: string) => void
}

export function ChatTaskFocusSwitcher({
  options,
  selectedTaskId,
  onSelect,
}: ChatTaskFocusSwitcherProps) {
  if (options.length === 0) {
    return null
  }

  const selectedOption = options.find(
    (option) => option.taskId === selectedTaskId,
  )

  return (
    <section
      className="chat-task-focus-switcher"
      aria-label="当前焦点"
    >
      <header className="chat-task-focus-switcher__header">
        <h3>当前焦点</h3>
        {selectedOption ? (
          <span
            className="chat-task-focus-switcher__selected-label"
            aria-label="已选焦点"
          >
            {selectedOption.label}
          </span>
        ) : null}
      </header>

      <div className="chat-task-focus-switcher__options">
        {options.map((option) => {
          const selected = option.taskId === selectedTaskId

          return (
            <button
              key={option.taskId}
              type="button"
              className={`chat-task-focus-switcher__option${
                selected ? ' chat-task-focus-switcher__option--selected' : ''
              }`}
              aria-pressed={selected}
              disabled={option.disabled}
              onClick={() => {
                if (!option.disabled) {
                  onSelect(option.taskId)
                }
              }}
            >
              <span className="chat-task-focus-switcher__option-label">
                {option.label}
              </span>
              {option.statusLabel ? (
                <span className="chat-task-focus-switcher__status-label">
                  {option.statusLabel}
                </span>
              ) : null}
            </button>
          )
        })}
      </div>
    </section>
  )
}

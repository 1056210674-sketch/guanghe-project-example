import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ChatTaskStatusOverview } from '../features/chat'
import { chatTaskStatusSummariesFixture } from './fixtures/chat'

describe('右侧任务状态总览', () => {
  it('严格按传入顺序展示，并由 selectedTaskId 控制当前项', () => {
    render(
      <ChatTaskStatusOverview
        summaries={chatTaskStatusSummariesFixture}
        selectedTaskId={chatTaskStatusSummariesFixture[1].taskId}
        onSelect={vi.fn()}
      />,
    )

    const overview = screen.getByRole('region', { name: '任务状态总览' })
    const buttons = within(overview).getAllByRole('button')
    expect(buttons.map((button) => button.textContent)).toEqual(
      chatTaskStatusSummariesFixture.map(
        (summary) => `${summary.label}${summary.statusLabel}`,
      ),
    )
    expect(buttons[0]).toHaveAttribute('aria-pressed', 'false')
    expect(buttons[1]).toHaveAttribute('aria-pressed', 'true')
    expect(buttons[2]).toHaveAttribute('aria-pressed', 'false')
    expect(buttons[0].tagName).toBe('BUTTON')
    expect(buttons[0]).toHaveAttribute('type', 'button')
  })

  it('未提供 selectedTaskId 时不自行选中任何状态项', () => {
    render(
      <ChatTaskStatusOverview
        summaries={chatTaskStatusSummariesFixture}
        onSelect={vi.fn()}
      />,
    )

    const buttons = within(
      screen.getByRole('region', { name: '任务状态总览' }),
    ).getAllByRole('button')
    for (const button of buttons) {
      expect(button).toHaveAttribute('aria-pressed', 'false')
    }
  })

  it('点击或键盘激活只回传 taskId，focusEnabled=false 时不可触发', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    render(
      <ChatTaskStatusOverview
        summaries={chatTaskStatusSummariesFixture}
        selectedTaskId={chatTaskStatusSummariesFixture[0].taskId}
        onSelect={onSelect}
      />,
    )

    const enabledSummary = chatTaskStatusSummariesFixture[2]
    const disabledSummary = chatTaskStatusSummariesFixture[1]
    const enabledButton = screen.getByRole('button', {
      name: new RegExp(enabledSummary.label),
    })
    const disabledButton = screen.getByRole('button', {
      name: new RegExp(disabledSummary.label),
    })

    expect(enabledButton).toBeEnabled()
    expect(disabledButton).toBeDisabled()
    await user.click(enabledButton)
    await user.click(disabledButton)
    enabledButton.focus()
    await user.keyboard('{Enter}')

    expect(onSelect).toHaveBeenCalledTimes(2)
    expect(onSelect).toHaveBeenNthCalledWith(1, enabledSummary.taskId)
    expect(onSelect).toHaveBeenNthCalledWith(2, enabledSummary.taskId)
    expect(enabledButton).toHaveAttribute('aria-pressed', 'false')
    expect(
      screen.getByRole('button', {
        name: new RegExp(chatTaskStatusSummariesFixture[0].label),
      }),
    ).toHaveAttribute('aria-pressed', 'true')
  })

  it('只有外部更新 selectedTaskId 后才切换当前项', () => {
    const { rerender } = render(
      <ChatTaskStatusOverview
        summaries={chatTaskStatusSummariesFixture}
        selectedTaskId={chatTaskStatusSummariesFixture[0].taskId}
        onSelect={vi.fn()}
      />,
    )

    rerender(
      <ChatTaskStatusOverview
        summaries={chatTaskStatusSummariesFixture}
        selectedTaskId={chatTaskStatusSummariesFixture[2].taskId}
        onSelect={vi.fn()}
      />,
    )

    expect(
      screen.getByRole('button', {
        name: new RegExp(chatTaskStatusSummariesFixture[0].label),
      }),
    ).toHaveAttribute('aria-pressed', 'false')
    expect(
      screen.getByRole('button', {
        name: new RegExp(chatTaskStatusSummariesFixture[2].label),
      }),
    ).toHaveAttribute('aria-pressed', 'true')
  })

  it('状态颜色仅由 statusTone 决定，不解析状态文案', () => {
    render(
      <ChatTaskStatusOverview
        summaries={[
          {
            taskId: 'chat-task-status-tone-test-001',
            label: '测试状态语义隔离',
            statusLabel: '文案写着已完成但不代表颜色',
            statusTone: 'danger',
            focusEnabled: true,
          },
        ]}
        onSelect={vi.fn()}
      />,
    )

    expect(screen.getByText('文案写着已完成但不代表颜色')).toHaveClass(
      'chat-task-status-overview__status--danger',
    )
  })

  it('空 summaries 不渲染组件', () => {
    const { container } = render(
      <ChatTaskStatusOverview summaries={[]} onSelect={vi.fn()} />,
    )

    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByLabelText('任务状态总览')).not.toBeInTheDocument()
  })
})

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ChatTaskFocusSwitcher } from '../features/chat'
import { chatTaskFocusOptionsFixture } from './fixtures/chat'

describe('对话当前任务焦点切换器', () => {
  it('严格按传入顺序展示，并由 selectedTaskId 控制选中状态', () => {
    render(
      <ChatTaskFocusSwitcher
        options={chatTaskFocusOptionsFixture}
        selectedTaskId={chatTaskFocusOptionsFixture[1].taskId}
        onSelect={vi.fn()}
      />,
    )

    const switcher = screen.getByRole('region', { name: '当前焦点' })
    const buttons = within(switcher).getAllByRole('button')
    expect(buttons.map((button) => button.textContent)).toEqual(
      chatTaskFocusOptionsFixture.map(
        (option) => `${option.label}${option.statusLabel ?? ''}`,
      ),
    )
    expect(buttons[0]).toHaveAttribute('aria-pressed', 'false')
    expect(buttons[1]).toHaveAttribute('aria-pressed', 'true')
    expect(buttons[2]).toHaveAttribute('aria-pressed', 'false')
    expect(buttons[3]).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByLabelText('已选焦点')).toHaveTextContent(
      chatTaskFocusOptionsFixture[1].label,
    )
  })

  it('未提供 selectedTaskId 时不自行选择第一项', () => {
    render(
      <ChatTaskFocusSwitcher
        options={chatTaskFocusOptionsFixture}
        onSelect={vi.fn()}
      />,
    )

    const buttons = within(
      screen.getByRole('region', { name: '当前焦点' }),
    ).getAllByRole('button')
    expect(buttons).not.toHaveLength(0)
    for (const button of buttons) {
      expect(button).toHaveAttribute('aria-pressed', 'false')
    }
    expect(screen.queryByLabelText('已选焦点')).not.toBeInTheDocument()
  })

  it('可用选项只回传 taskId，禁用选项不可触发', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    render(
      <ChatTaskFocusSwitcher
        options={chatTaskFocusOptionsFixture}
        selectedTaskId={chatTaskFocusOptionsFixture[0].taskId}
        onSelect={onSelect}
      />,
    )

    const enabledOption = chatTaskFocusOptionsFixture[1]
    const disabledOption = chatTaskFocusOptionsFixture[2]
    const enabledButton = screen.getByRole('button', {
      name: new RegExp(enabledOption.label),
    })
    const disabledButton = screen.getByRole('button', {
      name: new RegExp(disabledOption.label),
    })

    expect(enabledButton).toBeEnabled()
    expect(disabledButton).toBeDisabled()
    await user.click(enabledButton)
    await user.click(disabledButton)

    expect(onSelect).toHaveBeenCalledOnce()
    expect(onSelect).toHaveBeenCalledWith(enabledOption.taskId)
    expect(screen.getByLabelText('已选焦点')).toHaveTextContent(
      chatTaskFocusOptionsFixture[0].label,
    )
  })

  it('空选项不渲染组件', () => {
    const { container } = render(
      <ChatTaskFocusSwitcher options={[]} onSelect={vi.fn()} />,
    )

    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByLabelText('当前焦点')).not.toBeInTheDocument()
  })
})

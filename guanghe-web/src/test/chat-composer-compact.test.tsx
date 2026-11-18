import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps } from 'react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ChatComposer } from '../features/chat/components/ChatComposer'

const textareaProps = vi.hoisted(() => vi.fn())
vi.mock('antd', async (importOriginal) => {
  const actual = await importOriginal<typeof import('antd')>()
  return {
    ...actual,
    Input: {
      ...actual.Input,
      TextArea: (props: ComponentProps<typeof actual.Input.TextArea>) => {
        textareaProps(props)
        return <actual.Input.TextArea {...props} />
      },
    },
  }
})

const props = (): ComponentProps<typeof ChatComposer> => ({
  value: '', isGenerating: false, sendEnabled: true,
  onChange: vi.fn(), onSend: vi.fn(), onStop: vi.fn(), onCreateDiagnosticReport: vi.fn(),
})

describe('紧凑聊天输入框', () => {
  it('默认一行，按内容增高至八行，保留加号并移除外露能力按钮', () => {
    render(<ChatComposer {...props()} />)
    const input = screen.getByRole('textbox', { name: '消息输入' })
    expect(input).toHaveAttribute('rows', '1')
    expect(input.closest('form')).toHaveClass('chat-composer--compact')
    expect(textareaProps).toHaveBeenLastCalledWith(expect.objectContaining({ autoSize: { minRows: 1, maxRows: 8 } }))
    expect(screen.getByRole('button', { name: '添加资料' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: '监测一次' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '写文章' })).not.toBeInTheDocument()
  })

  it('多行编辑、打开加号和任务进度不会丢失输入或自动发送', async () => {
    const send = vi.fn()
    const taskProgress = vi.fn()
    function Harness() {
      const [value, setValue] = useState('第一行')
      return <ChatComposer {...props()} value={value} onChange={setValue} onSend={send} onViewTaskProgress={taskProgress} />
    }
    render(<Harness />)
    const input = screen.getByRole('textbox', { name: '消息输入' })
    const text = Array.from({ length: 12 }, (_, index) => `第 ${index + 1} 行`).join('\n')
    fireEvent.change(input, { target: { value: text } })
    await userEvent.click(screen.getByRole('button', { name: '添加资料' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: /任务进度/ }))
    expect(taskProgress).toHaveBeenCalledOnce()
    expect(input).toHaveValue(text)
    expect(send).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(send).toHaveBeenCalledExactlyOnceWith(text)
  })

  it('诊断入口从加号打开，未传入任务进度回调时不展示该入口', async () => {
    const config = props()
    render(<ChatComposer {...config} value="保留这段草稿" />)
    await userEvent.click(screen.getByRole('button', { name: '添加资料' }))
    expect(screen.queryByRole('menuitem', { name: /任务进度/ })).not.toBeInTheDocument()
    await userEvent.click(await screen.findByRole('menuitem', { name: /诊断报告/ }))
    expect(config.onCreateDiagnosticReport).toHaveBeenCalledOnce()
    expect(config.onSend).not.toHaveBeenCalled()
    expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue('保留这段草稿')
  })

  it('回复中仍能编辑草稿并停止，不把停止视为发送', async () => {
    const config = props()
    const { rerender } = render(<ChatComposer {...config} isGenerating value="继续完善" />)
    expect(screen.getByRole('textbox', { name: '消息输入' })).toBeEnabled()
    await userEvent.click(screen.getByRole('button', { name: '停止当前回复' }))
    expect(config.onStop).toHaveBeenCalledOnce()
    expect(config.onSend).not.toHaveBeenCalled()
    rerender(<ChatComposer {...config} value="继续完善" />)
    expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue('继续完善')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(config.onSend).toHaveBeenCalledExactlyOnceWith('继续完善')
  })

  it('保留发送错误和上传处理中约束，报告引用仍然显示', () => {
    const config = props()
    render(<ChatComposer {...config} value="基于报告生成" sendFeedback="发送失败，请重试"
      reportReference={<div>当前引用：品牌报告</div>} attachments={[{ assetId: '1', name: '参考图', status: 'PROCESSING' }]} />)
    expect(screen.getByText('当前引用：品牌报告')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('发送失败，请重试')
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled()
    fireEvent.keyDown(screen.getByRole('textbox', { name: '消息输入' }), { key: 'Enter' })
    expect(config.onSend).not.toHaveBeenCalled()
  })
})

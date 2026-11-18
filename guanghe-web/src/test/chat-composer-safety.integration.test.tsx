import { createEvent, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ChatComposer } from '../features/chat/components/ChatComposer'
import { GeneratedImageResultGallery } from '../features/chat/components/GeneratedImageResultGallery'
import { PaidTaskConfirmationCard } from '../features/chat/components/PaidTaskConfirmationCard'
import { generatedImageResultGroupFixture, paidTaskConfirmationFixture } from './fixtures/chat'

describe('普通用户对话输入和结果操作边界', () => {
  it('Enter 发送一次并阻止默认换行，按住回车的重复事件不会重复发送', () => {
    const send = vi.fn()
    render(<ChatComposer value="帮我看看这张图" isGenerating={false} sendEnabled onChange={vi.fn()} onSend={send} onStop={vi.fn()} />)
    const input = screen.getByRole('textbox', { name: '消息输入' })
    const enter = createEvent.keyDown(input, { key: 'Enter' })
    fireEvent(input, enter)
    fireEvent.keyDown(input, { key: 'Enter', repeat: true })
    expect(enter.defaultPrevented).toBe(true)
    expect(send).toHaveBeenCalledExactlyOnceWith('帮我看看这张图')
    expect(screen.queryByText('Enter 发送 · Shift + Enter 换行')).not.toBeInTheDocument()
  })

  it('空输入框不再显示闲置引导文字或空状态栏，仍保留操作入口', () => {
    render(<ChatComposer value="" isGenerating={false} sendEnabled
      onChange={vi.fn()} onSend={vi.fn()} onStop={vi.fn()} />)
    expect(screen.queryByText('可以直接提问，不必先绑定商品。')).not.toBeInTheDocument()
    expect(screen.queryByText('Enter 发送 · Shift + Enter 换行')).not.toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled()
    expect(screen.getByRole('button', { name: /上传本次图片/ })).toBeDisabled()
  })

  it('删去闲置提示不影响真正的发送错误反馈', () => {
    render(<ChatComposer value="帮我写一篇文章" isGenerating={false} sendEnabled
      sendFeedback="发送失败，请重试" onChange={vi.fn()} onSend={vi.fn()} onStop={vi.fn()} />)
    expect(screen.getByRole('status')).toHaveTextContent('发送失败，请重试')
  })

  it('Shift+Enter 真正插入换行，之后 Enter 发送完整多行内容', async () => {
    const send = vi.fn()
    function Composer() {
      const [value, setValue] = useState('第一行')
      return <ChatComposer value={value} isGenerating={false} sendEnabled onChange={setValue} onSend={send} onStop={vi.fn()} />
    }
    render(<Composer />)
    const user = userEvent.setup()
    const input = screen.getByRole('textbox', { name: '消息输入' })
    await user.click(input)
    await user.keyboard('{End}{Shift>}{Enter}{/Shift}第二行')
    expect(input).toHaveValue('第一行\n第二行')
    expect(send).not.toHaveBeenCalled()
    await user.keyboard('{Enter}')
    expect(send).toHaveBeenCalledExactlyOnceWith('第一行\n第二行')
  })

  it('中文组合输入、选字回车和兼容 229 事件都不发送', () => {
    const send = vi.fn()
    render(<ChatComposer value="你好" isGenerating={false} sendEnabled onChange={vi.fn()} onSend={send} onStop={vi.fn()} />)
    const input = screen.getByRole('textbox', { name: '消息输入' })
    fireEvent.compositionStart(input)
    fireEvent.keyDown(input, { key: 'Enter' })
    fireEvent.compositionEnd(input)
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 229 })
    expect(send).not.toHaveBeenCalled()
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(send).toHaveBeenCalledExactlyOnceWith('你好')
  })

  it.each([{ ctrlKey: true }, { metaKey: true }])('保留已有 Ctrl/Cmd+Enter 兼容：%j', (modifiers) => {
    const send = vi.fn()
    render(<ChatComposer value="  继续  " isGenerating={false} sendEnabled onChange={vi.fn()} onSend={send} onStop={vi.fn()} />)
    fireEvent.keyDown(screen.getByRole('textbox', { name: '消息输入' }), { key: 'Enter', ...modifiers })
    expect(send).toHaveBeenCalledExactlyOnceWith('继续')
  })

  it.each(['UPLOADING', 'PROCESSING', 'FAILED'] as const)('附件 %s 时 Enter 不绕过发送限制', (status) => {
    const send = vi.fn()
    render(<ChatComposer value="看看这个" isGenerating={false} sendEnabled onChange={vi.fn()} onSend={send} onStop={vi.fn()}
      attachments={[{ assetId: 'image', name: '参考图', status }]} />)
    const input = screen.getByRole('textbox', { name: '消息输入' })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(send).not.toHaveBeenCalled()
    expect(input).toHaveValue('看看这个')
  })

  it.each([{ value: '', sendEnabled: true }, { value: '   ', sendEnabled: true }, { value: '发送处理中', sendEnabled: false }])('空白或发送处理中 Enter 不发送：%j', (props) => {
    const send = vi.fn()
    render(<ChatComposer {...props} isGenerating={false} onChange={vi.fn()} onSend={send} onStop={vi.fn()} />)
    fireEvent.keyDown(screen.getByRole('textbox', { name: '消息输入' }), { key: 'Enter' })
    expect(send).not.toHaveBeenCalled()
  })

  it('回复期间新内容仍未发送，停止与停止后发送是两个明确动作', async () => {
    const user = userEvent.setup()
    const send = vi.fn()
    const stop = vi.fn()
    const stopAndSend = vi.fn()
    render(<ChatComposer value="不对，改成室外" isGenerating sendEnabled onChange={vi.fn()} onSend={send} onStop={stop} onStopAndSend={stopAndSend} />)
    expect(screen.getByText(/这段内容尚未发送/)).toBeVisible()
    fireEvent.keyDown(screen.getByRole('textbox', { name: '消息输入' }), { key: 'Enter' })
    expect(send).not.toHaveBeenCalled()
    expect(stop).not.toHaveBeenCalled()
    expect(stopAndSend).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '停止当前回复' }))
    expect(stop).toHaveBeenCalledTimes(1)
    expect(send).not.toHaveBeenCalled()
    expect(stopAndSend).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '停止当前回复并发送' }))
    expect(stopAndSend).toHaveBeenCalledExactlyOnceWith('不对，改成室外')
    expect(send).not.toHaveBeenCalled()
  })

  it('附件失败只阻止本条发送，保留文字并提供移除入口', async () => {
    const remove = vi.fn()
    const send = vi.fn()
    render(<ChatComposer value="看看这个" isGenerating={false} sendEnabled onChange={vi.fn()} onSend={send} onStop={vi.fn()}
      attachments={[{ assetId: 'failed-image', name: '参考图', status: 'FAILED' }]} onRemoveAttachment={remove} />)
    expect(screen.getByRole('textbox', { name: '消息输入' })).toHaveValue('看看这个')
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '删除本次附件 参考图' }))
    expect(remove).toHaveBeenCalledExactlyOnceWith('failed-image')
    expect(send).not.toHaveBeenCalled()
  })

  it('结果选图明确加入下一条，不误触预览、下载或关联商品', async () => {
    const select = vi.fn()
    const open = vi.fn()
    render(<GeneratedImageResultGallery group={{ ...generatedImageResultGroupFixture, images: generatedImageResultGroupFixture.images.slice(0, 1), selectedAssetIds: [] }}
      onSelectGeneratedImage={select} onPreviewGeneratedImage={open} onDownloadGeneratedImage={open} onSaveGeneratedImageToAssets={open} />)
    await userEvent.click(screen.getByRole('button', { name: /用这张继续创作/ }))
    expect(select).toHaveBeenCalledExactlyOnceWith(generatedImageResultGroupFixture.images[0].assetId)
    expect(open).not.toHaveBeenCalled()
  })

  it('收费按钮明确展示服务端报价，只有点击它才确认', async () => {
    const confirm = vi.fn()
    const edit = vi.fn()
    render(<PaidTaskConfirmationCard confirmation={{ ...paidTaskConfirmationFixture, pointsCost: 37 }}
      onConfirm={confirm} onEdit={edit} onOpenRecharge={vi.fn()} onPrepareReestimate={vi.fn()}
      onOpenPaidTaskReferenceAsset={vi.fn()} onAddPaidTaskReferenceAsset={vi.fn()} />)
    await userEvent.click(screen.getByRole('button', { name: '返回修改' }))
    expect(confirm).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: '确认开始 · 37 积分' }))
    expect(confirm).toHaveBeenCalledExactlyOnceWith(paidTaskConfirmationFixture.id)
  })
})

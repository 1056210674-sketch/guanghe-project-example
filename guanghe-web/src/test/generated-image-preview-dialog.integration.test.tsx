import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { GeneratedImagePreviewDialog } from '../features/chat/components/GeneratedImagePreviewDialog'

const props = { name: '骑车的小猫.png', url: 'https://assets.example/original.png', loading: false, error: false,
  onRetry: vi.fn(), onClose: vi.fn() }

describe('生成图片弹窗预览', () => {
  it('页内显示原图，读取真实像素尺寸并支持原始尺寸与适应窗口切换', () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    render(<GeneratedImagePreviewDialog {...props} />)
    const dialog = screen.getByRole('dialog', { name: '图片预览' })
    const image = within(dialog).getByAltText('骑车的小猫.png原图')
    expect(image).toHaveAttribute('src', props.url)
    Object.defineProperties(image, { naturalWidth: { value: 2048 }, naturalHeight: { value: 1152 } })
    fireEvent.load(image)
    expect(within(dialog).getByText('2048 × 1152 像素')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: '适应窗口' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(within(dialog).getByRole('button', { name: '原始尺寸（1:1）' }))
    expect(within(dialog).getByRole('region', { name: '原始尺寸图片，可滚动查看' })).toHaveClass('generated-image-preview__viewport--original')
    fireEvent.click(within(dialog).getByRole('button', { name: '适应窗口' }))
    expect(within(dialog).getByRole('region', { name: '适应窗口图片' })).not.toHaveClass('generated-image-preview__viewport--original')
    expect(open).not.toHaveBeenCalled()
    open.mockRestore()
  })

  it('链接刷新期间显示加载状态，不使用旧链接', () => {
    render(<GeneratedImagePreviewDialog {...props} loading />)
    expect(screen.getByRole('status')).toHaveTextContent('正在加载原图')
    expect(screen.queryByAltText('骑车的小猫.png原图')).not.toBeInTheDocument()
  })

  it.each(['url', 'image'])('原图%s失败可手动重试，不自动跳转下载', (mode) => {
    const onRetry = vi.fn()
    render(<GeneratedImagePreviewDialog {...props} error={mode === 'url'} onRetry={onRetry} />)
    if (mode === 'image') fireEvent.error(screen.getByAltText('骑车的小猫.png原图'))
    expect(screen.getByRole('alert')).toHaveTextContent('图片加载失败，请重试。')
    fireEvent.click(screen.getByRole('button', { name: '重新加载' }))
    expect(onRetry).toHaveBeenCalledOnce()
  })
})

import { fireEvent, render, screen } from '@testing-library/react'
import { lazy, Suspense } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { PageErrorBoundary } from '../components/PageErrorBoundary'

function BrokenPage(): never {
  throw new Error('private-render-error traceId=internal-only')
}

describe('页面加载失败恢复', () => {
  it('渲染异常显示安全的恢复入口，而非白屏或内部错误', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const onReload = vi.fn()
    render(<PageErrorBoundary onReload={onReload}><BrokenPage /></PageErrorBoundary>)
    expect(screen.getByRole('alert')).toHaveTextContent('页面加载失败')
    expect(screen.queryByText(/private-render-error|traceId|internal-only/)).not.toBeInTheDocument()
    expect(onReload).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '重新加载页面' }))
    expect(onReload).toHaveBeenCalledOnce()
  })

  it('旧分包加载失败由边界接住，不自动刷新或重新执行付费请求', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const onReload = vi.fn()
    const MissingChunk = lazy(async () => {
      throw new TypeError('Failed to fetch dynamically imported module: /assets/old-private-path.js')
    })
    try {
      render(<PageErrorBoundary onReload={onReload}>
        <Suspense fallback={<p>正在加载</p>}><MissingChunk /></Suspense>
      </PageErrorBoundary>)
      expect(await screen.findByRole('alert')).toHaveTextContent('页面加载失败')
      expect(screen.queryByText(/old-private-path/)).not.toBeInTheDocument()
      expect(onReload).not.toHaveBeenCalled()
      expect(fetchMock).not.toHaveBeenCalled()
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('切换到另一条路由可恢复正常内容，不被上一页异常锁住', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const { rerender } = render(<PageErrorBoundary key="plans"><BrokenPage /></PageErrorBoundary>)
    expect(screen.getByRole('alert')).toBeInTheDocument()
    rerender(<PageErrorBoundary key="tasks"><h1>任务中心</h1></PageErrorBoundary>)
    expect(screen.getByRole('heading', { name: '任务中心' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('稳定页面身份仅在失败后由导航恢复，不卸载正常页面输入', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const { rerender } = render(<PageErrorBoundary resetKey="first"><input aria-label="未保存标题" defaultValue="" /></PageErrorBoundary>)
    fireEvent.change(screen.getByLabelText('未保存标题'), { target: { value: '保留标题' } })
    rerender(<PageErrorBoundary resetKey="second"><input aria-label="未保存标题" defaultValue="" /></PageErrorBoundary>)
    expect(screen.getByLabelText('未保存标题')).toHaveValue('保留标题')
    rerender(<PageErrorBoundary resetKey="second"><BrokenPage /></PageErrorBoundary>)
    expect(screen.getByRole('alert')).toBeInTheDocument()
    rerender(<PageErrorBoundary resetKey="third"><h1>正常文章</h1></PageErrorBoundary>)
    expect(screen.getByRole('heading', { name: '正常文章' })).toBeInTheDocument()
  })
})

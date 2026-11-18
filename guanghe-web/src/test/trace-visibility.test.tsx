import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TraceId } from '../components/TraceId'
import { GeoStateFrame } from '../features/geo/components/GeoStateFrame'
import { useAuthStore } from '../stores/authStore'
import { adminFixture, testTraceId, userFixture } from './fixtures/auth'

beforeEach(() => {
  useAuthStore.setState({ status: 'anonymous', user: null, error: null })
})

describe('诊断 traceId 展示边界', () => {
  it('GEO 错误卡保留重试与错误说明，普通用户不看到或复制 traceId', () => {
    useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
    const onRetry = vi.fn()
    render(
      <GeoStateFrame
        label="监测概览"
        loading={false}
        error={{ message: '系统暂时不可用，请稍后重试', traceId: testTraceId }}
        onRetry={onRetry}
        empty={false}
        emptyTitle="暂无内容"
        emptyDescription="暂无内容"
      >
        正常内容
      </GeoStateFrame>,
    )

    expect(screen.getByText('系统暂时不可用，请稍后重试')).toBeInTheDocument()
    expect(screen.queryByText(new RegExp(testTraceId))).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /复制/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /重新加载/ }))
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('已认证管理员可以查看并复制诊断 traceId', async () => {
    useAuthStore.setState({ status: 'authenticated', user: adminFixture, error: null })
    const writeText = vi.fn<(value: string) => Promise<void>>().mockResolvedValue()
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })

    render(<TraceId traceId={testTraceId} />)
    expect(screen.getByText(`traceId：${testTraceId}`)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /复制/ }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(testTraceId))
  })
})

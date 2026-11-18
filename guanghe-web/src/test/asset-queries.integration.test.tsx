import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { PropsWithChildren } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createAssetDownloadUrl, listAssets } from '../api/assetApi'
import {
  assetKeys,
  useAllAssets,
  useAssetDownloadUrls,
  useAssets,
} from '../features/assets'
import { useAuthStore } from '../stores/authStore'
import { userFixture } from './fixtures/auth'

vi.mock('../api/assetApi', async () => {
  const actual = await vi.importActual<typeof import('../api/assetApi')>('../api/assetApi')
  return {
    ...actual,
    createAssetDownloadUrl: vi.fn(),
    getAsset: vi.fn(),
    listAssets: vi.fn(),
  }
})

function createClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false },
    },
  })
}

function wrapperFor(client: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  useAuthStore.setState({ status: 'authenticated', user: userFixture, error: null })
})

describe('永久资产 Query 缓存边界', () => {
  it('文章配图跨页加载全部资产，不静默截断第 101 条', async () => {
    const asset = (id: string) => ({
      id,
      assetType: 'IMAGE' as const,
      sourceType: 'UPLOADED' as const,
      status: 'READY' as const,
      safetyStatus: 'APPROVED' as const,
      name: `图片 ${id}`,
      spaceId: 'space-1',
      contentType: 'image/png',
      sizeBytes: 1,
      sha256: id.padEnd(64, '0'),
      createdAt: '2026-09-17T00:00:00Z',
      updatedAt: '2026-09-17T00:00:00Z',
    })
    vi.mocked(listAssets)
      .mockResolvedValueOnce({
        items: [asset('asset-1')],
        page: 1,
        size: 100,
        total: 101,
        hasNext: true,
      })
      .mockResolvedValueOnce({
        items: [asset('asset-101')],
        page: 2,
        size: 100,
        total: 101,
        hasNext: false,
      })

    const hook = renderHook(
      () => useAllAssets({ type: 'IMAGE', spaceId: 'space-1' }),
      { wrapper: wrapperFor(createClient()) },
    )

    await waitFor(() =>
      expect(hook.result.current.data?.map(({ id }) => id)).toEqual([
        'asset-1',
        'asset-101',
      ]),
    )
    expect(listAssets).toHaveBeenNthCalledWith(1, {
      type: 'IMAGE',
      spaceId: 'space-1',
      page: 1,
      size: 100,
    })
    expect(listAssets).toHaveBeenNthCalledWith(2, {
      type: 'IMAGE',
      spaceId: 'space-1',
      page: 2,
      size: 100,
    })
  })

  it('列表缓存按当前用户 ID 隔离', async () => {
    const firstPage = { items: [], page: 1, size: 20, total: 1, hasNext: false }
    const secondPage = { ...firstPage, total: 2 }
    vi.mocked(listAssets).mockResolvedValueOnce(firstPage).mockResolvedValueOnce(secondPage)
    const client = createClient()
    const hook = renderHook(() => useAssets({ page: 1, size: 20 }), {
      wrapper: wrapperFor(client),
    })
    await waitFor(() => expect(hook.result.current.data).toEqual(firstPage))

    act(() => {
      useAuthStore.setState({
        status: 'authenticated',
        user: { ...userFixture, id: 'second-user-id' },
        error: null,
      })
    })
    await waitFor(() => expect(hook.result.current.data).toEqual(secondPage))
    expect(client.getQueryData(
      assetKeys.forOwner(userFixture.id).list({ page: 1, size: 20 }),
    )).toEqual(firstPage)
    expect(client.getQueryData(
      assetKeys.forOwner('second-user-id').list({ page: 1, size: 20 }),
    )).toEqual(secondPage)
  })

  it('每次重新进入页面重新签发短效链接，且仅保存在 Query 缓存', async () => {
    vi.mocked(createAssetDownloadUrl)
      .mockResolvedValueOnce({
        url: 'https://asset.example/first.png',
        expiresAt: '2099-01-01T00:00:00Z',
      })
      .mockResolvedValueOnce({
        url: 'https://asset.example/second.png',
        expiresAt: '2099-01-01T00:05:00Z',
      })
    const client = createClient()
    const wrapper = wrapperFor(client)
    const first = renderHook(() => useAssetDownloadUrls(['asset-1']), { wrapper })
    await waitFor(() => expect(first.result.current['asset-1']?.url).toContain('first'))
    first.unmount()
    const second = renderHook(() => useAssetDownloadUrls(['asset-1']), { wrapper })
    await waitFor(() => expect(second.result.current['asset-1']?.url).toContain('second'))
    expect(createAssetDownloadUrl).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(window.localStorage)).not.toContain('asset.example')
  })
})

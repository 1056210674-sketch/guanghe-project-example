import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { PropsWithChildren } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/apiError'
import {
  createCompetitor,
  createProduct,
  listBusinessSpaces,
  listCompetitors,
  listProducts,
  replaceCompetitorAliases,
  replaceProductAliases,
  updateProduct,
} from '../api/catalogApi'
import {
  CatalogPartialCreateError,
  catalogKeys,
  isCatalogPartialCreateError,
  useAllBusinessSpaceOptions,
  useAllProductOptions,
  useCatalogQueryIsolation,
  useBusinessSpaces,
  useCompetitors,
  useCreateCompetitor,
  useCreateProduct,
  useProducts,
  useReplaceCompetitorAliases,
  useReplaceProductAliases,
  useUpdateProduct,
} from '../features/business-data/catalogQueries'
import { useAuthStore } from '../stores/authStore'
import {
  catalogBusinessSpacePage,
  catalogCompetitor,
  catalogProduct,
  catalogProductPage,
  catalogProductId,
  catalogSpaceId,
} from './fixtures/catalogApi'
import { userFixture } from './fixtures/auth'

vi.mock('../api/catalogApi', async () => {
  const actual = await vi.importActual<typeof import('../api/catalogApi')>(
    '../api/catalogApi',
  )

  return {
    ...actual,
    createCompetitor: vi.fn(),
    createProduct: vi.fn(),
    listBusinessSpaces: vi.fn(),
    listCompetitors: vi.fn(),
    listProducts: vi.fn(),
    replaceCompetitorAliases: vi.fn(),
    replaceProductAliases: vi.fn(),
    updateProduct: vi.fn(),
  }
})

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  })
}

function wrapperFor(queryClient: QueryClient) {
  return function QueryWrapper({ children }: PropsWithChildren) {
    return (
      <QueryClientProvider client={queryClient}>
        {children}
      </QueryClientProvider>
    )
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  useAuthStore.setState({
    status: 'authenticated',
    user: userFixture,
    error: null,
  })
})

describe('Catalog TanStack Query 边界', () => {
  const ownerKeys = () => catalogKeys.forOwner(userFixture.id)

  it('未恢复登录态时不请求经营空间或商品选项', () => {
    useAuthStore.setState({ status: 'anonymous', user: null, error: null })
    const queryClient = createTestQueryClient()
    const wrapper = wrapperFor(queryClient)

    const spacesHook = renderHook(() => useAllBusinessSpaceOptions(), {
      wrapper,
    })
    const productsHook = renderHook(() => useAllProductOptions(catalogSpaceId), {
      wrapper,
    })

    expect(spacesHook.result.current.fetchStatus).toBe('idle')
    expect(productsHook.result.current.fetchStatus).toBe('idle')
    expect(listBusinessSpaces).not.toHaveBeenCalled()
    expect(listProducts).not.toHaveBeenCalled()
  })

  it('登录用户切换时清理旧用户的目录缓存', async () => {
    const queryClient = createTestQueryClient()
    const oldOptionsKey = ownerKeys().businessSpaceOptions()
    queryClient.setQueryData(oldOptionsKey, [catalogBusinessSpacePage.items[0]])

    renderHook(() => useCatalogQueryIsolation(), {
      wrapper: wrapperFor(queryClient),
    })

    act(() => {
      useAuthStore.setState({
        status: 'authenticated',
        user: { ...userFixture, id: 'other-user' },
        error: null,
      })
    })

    await waitFor(() => {
      expect(queryClient.getQueryData(oldOptionsKey)).toBeUndefined()
    })
  })

  it('没有经营空间 ID 时不请求商品或竞品', async () => {
    const queryClient = createTestQueryClient()
    const wrapper = wrapperFor(queryClient)

    const productHook = renderHook(() => useProducts(undefined), { wrapper })
    const competitorHook = renderHook(() => useCompetitors(undefined), { wrapper })

    expect(productHook.result.current.fetchStatus).toBe('idle')
    expect(competitorHook.result.current.fetchStatus).toBe('idle')
    expect(listProducts).not.toHaveBeenCalled()
    expect(listCompetitors).not.toHaveBeenCalled()
  })

  it('经营空间选项逐页读取到 hasNext=false，严格使用服务端页码', async () => {
    vi.mocked(listBusinessSpaces)
      .mockResolvedValueOnce({
        ...catalogBusinessSpacePage,
        page: 1,
        size: 100,
        total: 101,
        hasNext: true,
      })
      .mockResolvedValueOnce({
        ...catalogBusinessSpacePage,
        items: [
          {
            ...catalogBusinessSpacePage.items[0],
            id: '1900012345678901199',
          },
        ],
        page: 2,
        size: 100,
        total: 101,
        hasNext: false,
      })
    const queryClient = createTestQueryClient()
    const { result } = renderHook(() => useAllBusinessSpaceOptions(), {
      wrapper: wrapperFor(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(listBusinessSpaces).toHaveBeenNthCalledWith(1, { page: 1, size: 100 })
    expect(listBusinessSpaces).toHaveBeenNthCalledWith(2, { page: 2, size: 100 })
    expect(result.current.data).toHaveLength(2)
  })

  it('商品选项逐页读取，避免只展示前 100 个商品', async () => {
    const secondProduct = {
      ...catalogProductPage.items[0],
      id: '1900012345678901299',
      name: '第二页商品',
    }
    vi.mocked(listProducts)
      .mockResolvedValueOnce({
        ...catalogProductPage,
        page: 1,
        size: 100,
        total: 101,
        hasNext: true,
      })
      .mockResolvedValueOnce({
        ...catalogProductPage,
        items: [secondProduct],
        page: 2,
        size: 100,
        total: 101,
        hasNext: false,
      })
    const queryClient = createTestQueryClient()
    const { result } = renderHook(() => useAllProductOptions(catalogSpaceId), {
      wrapper: wrapperFor(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(listProducts).toHaveBeenNthCalledWith(1, catalogSpaceId, {
      page: 1,
      size: 100,
    })
    expect(listProducts).toHaveBeenNthCalledWith(2, catalogSpaceId, {
      page: 2,
      size: 100,
    })
    expect(result.current.data).toEqual([
      catalogProductPage.items[0],
      secondProduct,
    ])
  })

  it('分页响应无法前进时立即失败，不会无限请求', async () => {
    vi.mocked(listBusinessSpaces).mockResolvedValue({
      ...catalogBusinessSpacePage,
      items: [],
      page: 1,
      size: 100,
      total: 101,
      hasNext: true,
    })
    const queryClient = createTestQueryClient()
    const { result } = renderHook(() => useAllBusinessSpaceOptions(), {
      wrapper: wrapperFor(queryClient),
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error).toMatchObject({ code: 'INVALID_RESPONSE' })
    expect(listBusinessSpaces).toHaveBeenCalledTimes(1)
  })

  it('Query key 按当前用户 ID 隔离，切换账号时不读取上一账号缓存', async () => {
    const secondUser = {
      ...userFixture,
      id: '1009',
      username: 'second-user',
    }
    const secondPage = {
      ...catalogBusinessSpacePage,
      items: [
        {
          ...catalogBusinessSpacePage.items[0],
          id: '1900012345678901199',
          name: '第二账号经营空间',
        },
      ],
    }
    let resolveSecondPage: ((value: typeof secondPage) => void) | undefined
    const pendingSecondPage = new Promise<typeof secondPage>((resolve) => {
      resolveSecondPage = resolve
    })
    vi.mocked(listBusinessSpaces)
      .mockResolvedValueOnce(catalogBusinessSpacePage)
      .mockReturnValueOnce(pendingSecondPage)
    const queryClient = createTestQueryClient()
    const { result } = renderHook(() => useBusinessSpaces(), {
      wrapper: wrapperFor(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual(catalogBusinessSpacePage)

    act(() => {
      useAuthStore.setState({
        status: 'authenticated',
        user: secondUser,
        error: null,
      })
    })

    await waitFor(() => expect(listBusinessSpaces).toHaveBeenCalledTimes(2))
    expect(result.current.data).toBeUndefined()
    expect(
      queryClient.getQueryData(
        catalogKeys.forOwner(secondUser.id).businessSpaceList(),
      ),
    ).toBeUndefined()

    resolveSecondPage?.(secondPage)
    await waitFor(() => expect(result.current.data).toEqual(secondPage))

    expect(
      queryClient.getQueryData(
        catalogKeys.forOwner(userFixture.id).businessSpaceList(),
      ),
    ).toEqual(catalogBusinessSpacePage)
    expect(
      queryClient.getQueryData(
        catalogKeys.forOwner(secondUser.id).businessSpaceList(),
      ),
    ).toEqual(secondPage)
  })

  it('新建商品先 POST 再整体替换别名，并只失效商品空间列表和经营空间计数', async () => {
    const created = { ...catalogProduct, aliases: [], version: 20 }
    const aliased = { ...created, aliases: ['商品别名'], version: 21 }
    vi.mocked(createProduct).mockResolvedValue(created)
    vi.mocked(replaceProductAliases).mockResolvedValue(aliased)
    const queryClient = createTestQueryClient()
    const invalidate = vi
      .spyOn(queryClient, 'invalidateQueries')
      .mockResolvedValue(undefined)
    const { result } = renderHook(() => useCreateProduct(), {
      wrapper: wrapperFor(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        spaceId: catalogSpaceId,
        request: { name: created.name },
        aliases: ['商品别名'],
      })
    })

    expect(createProduct).toHaveBeenCalledWith(catalogSpaceId, {
      name: created.name,
    })
    expect(replaceProductAliases).toHaveBeenCalledWith(catalogProductId, {
      version: 20,
      aliases: ['商品别名'],
    })
    expect(queryClient.getQueryData(ownerKeys().productDetail(catalogProductId))).toEqual(
      aliased,
    )
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: ownerKeys().productListsForSpace(catalogSpaceId),
    })
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: ownerKeys().businessSpaceLists(),
    })
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: ownerKeys().businessSpaceDetail(catalogSpaceId),
    })
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: catalogKeys.all })
    expect(invalidate).not.toHaveBeenCalledWith({
      queryKey: ownerKeys().competitorLists(),
    })
  })

  it('商品 POST 成功但别名 PUT 失败时返回可恢复的部分创建错误', async () => {
    const created = { ...catalogProduct, aliases: [], version: 24 }
    const operationError = new ApiError({
      status: 409,
      code: 'ALIAS_CONFLICT',
      message: '商品别名冲突',
      traceId: '1234567890abcdef1234567890abcdef',
    })
    vi.mocked(createProduct).mockResolvedValue(created)
    vi.mocked(replaceProductAliases).mockRejectedValue(operationError)
    const queryClient = createTestQueryClient()
    const { result } = renderHook(() => useCreateProduct(), {
      wrapper: wrapperFor(queryClient),
    })
    let caughtError: unknown

    await act(async () => {
      try {
        await result.current.mutateAsync({
          spaceId: catalogSpaceId,
          request: { name: created.name },
          aliases: ['冲突商品别名'],
        })
      } catch (error) {
        caughtError = error
      }
    })

    expect(isCatalogPartialCreateError(caughtError)).toBe(true)
    expect(caughtError).toBeInstanceOf(CatalogPartialCreateError)
    expect(caughtError).toMatchObject({
      createdResource: created,
      operationError,
    })
    expect(
      queryClient.getQueryData(ownerKeys().productDetail(created.id)),
    ).toEqual(created)
  })

  it('别名替换失败时保留已成功 PATCH 的最新详情并定向失效列表', async () => {
    const updated = { ...catalogProduct, name: '已更新商品', version: 31 }
    vi.mocked(updateProduct).mockResolvedValue(updated)
    vi.mocked(replaceProductAliases).mockRejectedValue(
      new Error('alias conflict'),
    )
    const queryClient = createTestQueryClient()
    const invalidate = vi
      .spyOn(queryClient, 'invalidateQueries')
      .mockResolvedValue(undefined)
    const { result } = renderHook(() => useUpdateProduct(), {
      wrapper: wrapperFor(queryClient),
    })

    await expect(
      act(async () =>
        result.current.mutateAsync({
          productId: catalogProductId,
          spaceId: catalogSpaceId,
          request: { version: 30, name: updated.name },
          aliases: ['冲突别名'],
        }),
      ),
    ).rejects.toThrow('alias conflict')

    expect(replaceProductAliases).toHaveBeenCalledWith(catalogProductId, {
      version: 31,
      aliases: ['冲突别名'],
    })
    expect(queryClient.getQueryData(ownerKeys().productDetail(catalogProductId))).toEqual(
      updated,
    )
    expect(invalidate).toHaveBeenCalledTimes(1)
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: ownerKeys().productListsForSpace(catalogSpaceId),
    })
  })

  it('新建竞品把 View 层之外的真实 type/status 交给 API，并定向刷新计数', async () => {
    const created = { ...catalogCompetitor, aliases: [], version: 40 }
    vi.mocked(createCompetitor).mockResolvedValue(created)
    vi.mocked(replaceCompetitorAliases).mockResolvedValue(created)
    const queryClient = createTestQueryClient()
    const invalidate = vi
      .spyOn(queryClient, 'invalidateQueries')
      .mockResolvedValue(undefined)
    const { result } = renderHook(() => useCreateCompetitor(), {
      wrapper: wrapperFor(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        spaceId: catalogSpaceId,
        request: {
          name: created.name,
          type: 'DIRECT',
          description: created.description,
          status: 'ACTIVE',
        },
        aliases: [],
      })
    })

    expect(createCompetitor).toHaveBeenCalledWith(
      catalogSpaceId,
      expect.objectContaining({ type: 'DIRECT', status: 'ACTIVE' }),
    )
    expect(replaceCompetitorAliases).not.toHaveBeenCalled()
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: ownerKeys().competitorListsForSpace(catalogSpaceId),
    })
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: ownerKeys().businessSpaceLists(),
    })
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: catalogKeys.all })
  })

  it('竞品 POST 成功但别名 PUT 失败时保留已创建资源和原始错误', async () => {
    const created = { ...catalogCompetitor, aliases: [], version: 44 }
    const operationError = new Error('competitor aliases unavailable')
    vi.mocked(createCompetitor).mockResolvedValue(created)
    vi.mocked(replaceCompetitorAliases).mockRejectedValue(operationError)
    const queryClient = createTestQueryClient()
    const { result } = renderHook(() => useCreateCompetitor(), {
      wrapper: wrapperFor(queryClient),
    })
    let caughtError: unknown

    await act(async () => {
      try {
        await result.current.mutateAsync({
          spaceId: catalogSpaceId,
          request: {
            name: created.name,
            type: 'DIRECT',
            status: 'ACTIVE',
          },
          aliases: ['冲突竞品别名'],
        })
      } catch (error) {
        caughtError = error
      }
    })

    expect(isCatalogPartialCreateError(caughtError)).toBe(true)
    expect(caughtError).toMatchObject({
      createdResource: created,
      operationError,
    })
    expect(
      queryClient.getQueryData(ownerKeys().competitorDetail(created.id)),
    ).toEqual(created)
  })

  it('商品更新发生 409 时只刷新当前账号的对应详情 Query', async () => {
    const conflict = new ApiError({
      status: 409,
      code: 'RESOURCE_VERSION_CONFLICT',
      message: '版本冲突',
    })
    vi.mocked(updateProduct).mockRejectedValue(conflict)
    const queryClient = createTestQueryClient()
    const invalidate = vi
      .spyOn(queryClient, 'invalidateQueries')
      .mockResolvedValue(undefined)
    const { result } = renderHook(() => useUpdateProduct(), {
      wrapper: wrapperFor(queryClient),
    })

    await expect(
      result.current.mutateAsync({
        productId: catalogProductId,
        spaceId: catalogSpaceId,
        request: { version: 10, name: '冲突商品' },
      }),
    ).rejects.toBe(conflict)

    expect(invalidate).toHaveBeenCalledOnce()
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: ownerKeys().productDetail(catalogProductId),
    })
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: catalogKeys.all })
  })

  it('商品和竞品别名 PUT 的 409 只刷新各自详情 Query', async () => {
    const conflict = new ApiError({
      status: 409,
      code: 'ALIAS_CONFLICT',
      message: '别名冲突',
    })
    vi.mocked(replaceProductAliases).mockRejectedValue(conflict)
    vi.mocked(replaceCompetitorAliases).mockRejectedValue(conflict)
    const queryClient = createTestQueryClient()
    const invalidate = vi
      .spyOn(queryClient, 'invalidateQueries')
      .mockResolvedValue(undefined)
    const productHook = renderHook(() => useReplaceProductAliases(), {
      wrapper: wrapperFor(queryClient),
    })
    const competitorHook = renderHook(() => useReplaceCompetitorAliases(), {
      wrapper: wrapperFor(queryClient),
    })

    await expect(
      productHook.result.current.mutateAsync({
        productId: catalogProductId,
        request: { version: 10, aliases: ['商品冲突别名'] },
      }),
    ).rejects.toBe(conflict)
    await expect(
      competitorHook.result.current.mutateAsync({
        competitorId: catalogCompetitor.id,
        request: { version: 10, aliases: ['竞品冲突别名'] },
      }),
    ).rejects.toBe(conflict)

    expect(invalidate).toHaveBeenCalledTimes(2)
    expect(invalidate).toHaveBeenNthCalledWith(1, {
      queryKey: ownerKeys().productDetail(catalogProductId),
    })
    expect(invalidate).toHaveBeenNthCalledWith(2, {
      queryKey: ownerKeys().competitorDetail(catalogCompetitor.id),
    })
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createBusinessSpace,
  createCompetitor,
  createCompetitorWithAliases,
  createProduct,
  createProductWithAliases,
  deleteBusinessSpace,
  deleteCompetitor,
  deleteProduct,
  getBusinessSpace,
  getCompetitor,
  getProduct,
  getProductIdentityAssets,
  listBusinessSpaces,
  listCompetitors,
  listProducts,
  replaceCompetitorAliases,
  replaceProductAliases,
  replaceProductIdentityAssets,
  updateBusinessSpace,
  updateCompetitor,
  updateCompetitorWithAliases,
  updateProduct,
  updateProductWithAliases,
} from '../api/catalogApi'
import { ApiError } from '../api/apiError'
import { storeAuthToken } from '../auth/tokenStorage'
import {
  catalogBusinessSpace,
  catalogBusinessSpacePage,
  catalogCompetitor,
  catalogCompetitorId,
  catalogCompetitorPage,
  catalogProduct,
  catalogProductId,
  catalogProductPage,
  catalogSpaceId,
  catalogTraceId,
} from './fixtures/catalogApi'

type FetchMock = ReturnType<typeof vi.fn<typeof fetch>>

function successResponse(data: unknown, status = 200): Response {
  return new Response(
    JSON.stringify({ code: 'OK', message: 'success', data, traceId: catalogTraceId }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function errorResponse(status: number, code: string): Response {
  return new Response(
    JSON.stringify({
      code,
      message: `catalog error ${status}`,
      details: { field: 'test' },
      traceId: catalogTraceId,
    }),
    { status, headers: { 'Content-Type': 'application/json' } },
  )
}

function stubFetch(...responses: Response[]): FetchMock {
  const fetchMock = vi.fn<typeof fetch>()
  responses.forEach((response) => fetchMock.mockResolvedValueOnce(response))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function requestAt(fetchMock: FetchMock, index: number): RequestInit {
  return fetchMock.mock.calls[index][1] as RequestInit
}

function jsonBody(fetchMock: FetchMock, index: number): unknown {
  return JSON.parse(String(requestAt(fetchMock, index).body)) as unknown
}

beforeEach(() => {
  vi.unstubAllGlobals()
  window.localStorage.clear()
  storeAuthToken({
    accessToken: 'catalog-browser-token',
    expiresAt: '2099-01-01T00:00:00Z',
  })
})

describe('Catalog Browser API 路径与包络', () => {
  it('三个列表使用一基分页参数并解包真实 data，Snowflake ID 保持字符串', async () => {
    const fetchMock = stubFetch(
      successResponse(catalogBusinessSpacePage),
      successResponse(catalogProductPage),
      successResponse(catalogCompetitorPage),
    )

    const spaces = await listBusinessSpaces({ page: 2, size: 50 })
    const products = await listProducts(catalogSpaceId, { page: 2, size: 50 })
    const competitors = await listCompetitors(catalogSpaceId, { page: 2, size: 50 })

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/spaces?page=2&size=50',
      `/api/v1/spaces/${catalogSpaceId}/products?page=2&size=50`,
      `/api/v1/spaces/${catalogSpaceId}/competitors?page=2&size=50`,
    ])
    expect(spaces).toEqual(catalogBusinessSpacePage)
    expect(products).toEqual(catalogProductPage)
    expect(competitors).toEqual(catalogCompetitorPage)
    expect(typeof spaces.items[0].id).toBe('string')
    expect(spaces.items[0].id).toBe(catalogSpaceId)
    expect((requestAt(fetchMock, 0).headers as Headers).get('Authorization')).toBe(
      'Bearer catalog-browser-token',
    )
  })

  it('经营空间 CRUD 使用真实方法、路径、version 与 200 data:null 删除包络', async () => {
    const updated = { ...catalogBusinessSpace, version: 8 }
    const fetchMock = stubFetch(
      successResponse(catalogBusinessSpace),
      successResponse(catalogBusinessSpace, 201),
      successResponse(updated),
      successResponse(null),
    )
    const createRequest = {
      name: catalogBusinessSpace.name,
      brand: {
        name: catalogBusinessSpace.brand.name,
        aliases: catalogBusinessSpace.brand.aliases,
        description: catalogBusinessSpace.brand.description,
        tone: catalogBusinessSpace.brand.tone,
        audience: catalogBusinessSpace.brand.audience,
        sellingPoints: catalogBusinessSpace.brand.sellingPoints,
        prohibitedClaims: catalogBusinessSpace.brand.prohibitedClaims,
      },
    }

    await getBusinessSpace(catalogSpaceId)
    await createBusinessSpace(createRequest)
    await updateBusinessSpace(catalogSpaceId, { version: 7, name: '更新空间' })
    await deleteBusinessSpace(catalogSpaceId)

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      `/api/v1/spaces/${catalogSpaceId}`,
      '/api/v1/spaces',
      `/api/v1/spaces/${catalogSpaceId}`,
      `/api/v1/spaces/${catalogSpaceId}`,
    ])
    expect(fetchMock.mock.calls.map(([, init]) => init?.method)).toEqual([
      'GET',
      'POST',
      'PATCH',
      'DELETE',
    ])
    expect(jsonBody(fetchMock, 1)).toEqual(createRequest)
    expect(jsonBody(fetchMock, 2)).toEqual({ version: 7, name: '更新空间' })
    expect(requestAt(fetchMock, 3).body).toBeUndefined()
  })

  it('商品 CRUD 与别名整体替换使用真实路径，modelSpec 和 version 原样提交', async () => {
    const fetchMock = stubFetch(
      successResponse(catalogProduct),
      successResponse(catalogProduct, 201),
      successResponse({ ...catalogProduct, version: 12 }),
      successResponse({ ...catalogProduct, aliases: ['新别名'], version: 12 }),
      successResponse(null),
    )
    const createRequest = {
      name: catalogProduct.name,
      modelSpec: catalogProduct.modelSpec,
      primaryAssetId: null,
    }

    await getProduct(catalogProductId)
    await createProduct(catalogSpaceId, createRequest)
    await updateProduct(catalogProductId, { version: 11, modelSpec: 'MODEL-02' })
    await replaceProductAliases(catalogProductId, {
      version: 11,
      aliases: ['新别名'],
    })
    await deleteProduct(catalogProductId)

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      `/api/v1/products/${catalogProductId}`,
      `/api/v1/spaces/${catalogSpaceId}/products`,
      `/api/v1/products/${catalogProductId}`,
      `/api/v1/products/${catalogProductId}/aliases`,
      `/api/v1/products/${catalogProductId}`,
    ])
    expect(fetchMock.mock.calls.map(([, init]) => init?.method)).toEqual([
      'GET',
      'POST',
      'PATCH',
      'PUT',
      'DELETE',
    ])
    expect(jsonBody(fetchMock, 1)).toEqual(createRequest)
    expect(jsonBody(fetchMock, 2)).toEqual({ version: 11, modelSpec: 'MODEL-02' })
    expect(jsonBody(fetchMock, 3)).toEqual({ version: 11, aliases: ['新别名'] })
  })

  it('商品身份图按权威 GET/PUT 路径读取并整体替换角色与顺序', async () => {
    const identityAssets = {
      productId: catalogProductId,
      version: 12,
      creativeReady: true,
      items: [
        {
          assetId: '880000000000000001',
          role: 'PRIMARY' as const,
          sequence: 1,
          status: 'READY' as const,
          contentType: 'image/png',
          width: 1200,
          height: 1200,
        },
        {
          assetId: '880000000000000002',
          role: 'WHITE_BACKGROUND' as const,
          sequence: 2,
          status: 'READY' as const,
          contentType: 'image/jpeg',
          width: 1200,
          height: 1200,
        },
      ],
    }
    const fetchMock = stubFetch(
      successResponse(identityAssets),
      successResponse({ ...identityAssets, version: 13 }),
    )
    const request = {
      version: 12,
      items: identityAssets.items.map(({ assetId, role, sequence }) => ({
        assetId,
        role,
        sequence,
      })),
    }

    expect(await getProductIdentityAssets(catalogProductId)).toEqual(identityAssets)
    expect(
      await replaceProductIdentityAssets(catalogProductId, request),
    ).toEqual({ ...identityAssets, version: 13 })
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      `/api/v1/products/${catalogProductId}/identity-assets`,
      `/api/v1/products/${catalogProductId}/identity-assets`,
    ])
    expect(fetchMock.mock.calls.map(([, init]) => init?.method)).toEqual([
      'GET',
      'PUT',
    ])
    expect(jsonBody(fetchMock, 1)).toEqual(request)
  })

  it('竞品 API 映射真实 type 字段并覆盖 CRUD 与别名替换路径', async () => {
    const fetchMock = stubFetch(
      successResponse(catalogCompetitor),
      successResponse(catalogCompetitor, 201),
      successResponse({ ...catalogCompetitor, type: 'INDIRECT', version: 14 }),
      successResponse({ ...catalogCompetitor, aliases: ['新别名'], version: 14 }),
      successResponse(null),
    )
    const createRequest = {
      name: catalogCompetitor.name,
      type: 'DIRECT' as const,
      description: catalogCompetitor.description,
      status: 'ACTIVE' as const,
    }

    await getCompetitor(catalogCompetitorId)
    await createCompetitor(catalogSpaceId, createRequest)
    await updateCompetitor(catalogCompetitorId, {
      version: 13,
      type: 'INDIRECT',
    })
    await replaceCompetitorAliases(catalogCompetitorId, {
      version: 13,
      aliases: ['新别名'],
    })
    await deleteCompetitor(catalogCompetitorId)

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      `/api/v1/competitors/${catalogCompetitorId}`,
      `/api/v1/spaces/${catalogSpaceId}/competitors`,
      `/api/v1/competitors/${catalogCompetitorId}`,
      `/api/v1/competitors/${catalogCompetitorId}/aliases`,
      `/api/v1/competitors/${catalogCompetitorId}`,
    ])
    expect(fetchMock.mock.calls.map(([, init]) => init?.method)).toEqual([
      'GET',
      'POST',
      'PATCH',
      'PUT',
      'DELETE',
    ])
    expect(jsonBody(fetchMock, 1)).toEqual(createRequest)
    expect(jsonBody(fetchMock, 2)).toEqual({ version: 13, type: 'INDIRECT' })
    expect(jsonBody(fetchMock, 3)).toEqual({ version: 13, aliases: ['新别名'] })
  })

  it.each([
    [401, 'UNAUTHENTICATED'],
    [403, 'FORBIDDEN'],
    [404, 'RESOURCE_NOT_FOUND'],
    [409, 'RESOURCE_VERSION_CONFLICT'],
    [422, 'VALIDATION_FAILED'],
    [429, 'RATE_LIMITED'],
    [503, 'PROVIDER_NOT_CONFIGURED'],
  ] as const)('保留 HTTP %i 的真实错误和 traceId', async (status, code) => {
    stubFetch(errorResponse(status, code))

    await expect(getBusinessSpace(catalogSpaceId)).rejects.toMatchObject({
      status,
      code,
      message: `catalog error ${status}`,
      details: { field: 'test' },
      traceId: catalogTraceId,
    } satisfies Partial<ApiError>)
  })
})

describe('Catalog version 与 aliases 串联', () => {
  it('新建商品后只在有别名时 PUT，并使用创建响应的最新 version', async () => {
    const created = { ...catalogProduct, aliases: [], version: 21 }
    const replaced = { ...created, aliases: ['别名一'], version: 22 }
    const fetchMock = stubFetch(successResponse(created, 201), successResponse(replaced))

    const result = await createProductWithAliases(
      catalogSpaceId,
      { name: created.name },
      ['别名一'],
    )

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls[1][0]).toBe(
      `/api/v1/products/${catalogProductId}/aliases`,
    )
    expect(jsonBody(fetchMock, 1)).toEqual({ version: 21, aliases: ['别名一'] })
    expect(result).toEqual(replaced)
  })

  it('编辑商品后以 PATCH 响应 version 替换别名，不复用表单旧 version', async () => {
    const updated = { ...catalogProduct, version: 31 }
    const replaced = { ...updated, aliases: [], version: 32 }
    const fetchMock = stubFetch(successResponse(updated), successResponse(replaced))

    await updateProductWithAliases(
      catalogProductId,
      { version: 30, name: '已更新商品' },
      [],
    )

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(jsonBody(fetchMock, 0)).toEqual({ version: 30, name: '已更新商品' })
    expect(jsonBody(fetchMock, 1)).toEqual({ version: 31, aliases: [] })
  })

  it('新建和编辑竞品同样使用前一步响应 version，且不把 competitorType 发给后端', async () => {
    const created = { ...catalogCompetitor, aliases: [], version: 41 }
    const aliased = { ...created, aliases: ['竞品别名'], version: 42 }
    const updated = { ...aliased, type: 'SUBSTITUTE' as const, version: 43 }
    const finalValue = { ...updated, aliases: [], version: 44 }
    const fetchMock = stubFetch(
      successResponse(created, 201),
      successResponse(aliased),
      successResponse(updated),
      successResponse(finalValue),
    )

    await createCompetitorWithAliases(
      catalogSpaceId,
      { name: created.name, type: 'DIRECT', status: 'ACTIVE' },
      ['竞品别名'],
    )
    await updateCompetitorWithAliases(
      catalogCompetitorId,
      { version: 42, type: 'SUBSTITUTE' },
      [],
    )

    expect(jsonBody(fetchMock, 0)).toEqual({
      name: created.name,
      type: 'DIRECT',
      status: 'ACTIVE',
    })
    expect(jsonBody(fetchMock, 1)).toEqual({ version: 41, aliases: ['竞品别名'] })
    expect(jsonBody(fetchMock, 2)).toEqual({ version: 42, type: 'SUBSTITUTE' })
    expect(jsonBody(fetchMock, 3)).toEqual({ version: 43, aliases: [] })
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain('competitorType')
  })

  it('空别名的新建操作不发送多余 PUT', async () => {
    const productFetch = stubFetch(successResponse(catalogProduct, 201))
    await createProductWithAliases(catalogSpaceId, { name: catalogProduct.name }, [])
    expect(productFetch).toHaveBeenCalledOnce()

    const competitorFetch = stubFetch(successResponse(catalogCompetitor, 201))
    await createCompetitorWithAliases(
      catalogSpaceId,
      { name: catalogCompetitor.name, type: 'DIRECT', status: null },
      [],
    )
    expect(competitorFetch).toHaveBeenCalledOnce()
  })
})

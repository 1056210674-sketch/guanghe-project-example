import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import { ApiError, isApiError } from '../../api/apiError'
import {
  createBusinessSpace,
  createCompetitor,
  createProduct,
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
  updateProduct,
} from '../../api/catalogApi'
import { useAuthStore } from '../../stores/authStore'
import type {
  BusinessSpaceListQuery,
  BusinessSpaceSummary,
  CatalogEntityId,
  CompetitorAliases,
  CompetitorListQuery,
  CreateBusinessSpaceRequest,
  CreateCompetitorRequest,
  CreateProductRequest,
  ProductAliases,
  ProductListQuery,
  ProductSummary,
  ReplaceProductIdentityAssetsRequest,
  ReplaceCompetitorAliasesRequest,
  ReplaceProductAliasesRequest,
  UpdateBusinessSpaceRequest,
  UpdateCompetitorRequest,
  UpdateProductRequest,
} from '../../api/catalogApi'

const DEFAULT_PAGE = 1
const DEFAULT_PAGE_SIZE = 20
const OPTIONS_PAGE_SIZE = 100

type CatalogResource = { id: CatalogEntityId }

function nextOptionsPage(
  requestedPage: number,
  result: {
    page: number
    size: number
    total: number
    hasNext: boolean
    items?: readonly unknown[]
  },
): number | undefined {
  if (!result.hasNext) return undefined

  const advances =
    result.page === requestedPage &&
    result.size > 0 &&
    (result.items?.length ?? 0) > 0 &&
    result.page * result.size < result.total

  if (!advances) {
    throw new ApiError({
      status: 200,
      code: 'INVALID_RESPONSE',
      message: '经营资料分页返回异常，请刷新后重试。',
    })
  }

  return requestedPage + 1
}

export class CatalogPartialCreateError<
  TResource extends CatalogResource = CatalogResource,
> extends Error {
  readonly createdResource: TResource
  readonly operationError: unknown

  constructor(createdResource: TResource, operationError: unknown) {
    super('基础资料已创建，但别名保存失败。')
    this.name = 'CatalogPartialCreateError'
    this.createdResource = createdResource
    this.operationError = operationError
  }
}

export function isCatalogPartialCreateError(
  error: unknown,
): error is CatalogPartialCreateError {
  return error instanceof CatalogPartialCreateError
}

function normalizedPage(query: { page?: number; size?: number }) {
  return [query.page ?? DEFAULT_PAGE, query.size ?? DEFAULT_PAGE_SIZE] as const
}

const catalogRootKey = ['catalog'] as const

function createCatalogKeys(ownerId?: string | null) {
  const ownerRoot = [...catalogRootKey, 'owner', ownerId ?? null] as const
  const businessSpaces = () => [...ownerRoot, 'business-spaces'] as const
  const businessSpaceLists = () => [...businessSpaces(), 'list'] as const
  const products = () => [...ownerRoot, 'products'] as const
  const productLists = () => [...products(), 'list'] as const
  const competitors = () => [...ownerRoot, 'competitors'] as const
  const competitorLists = () => [...competitors(), 'list'] as const

  return {
    ownerRoot,
    businessSpaces,
    businessSpaceLists,
    businessSpaceList: (query: BusinessSpaceListQuery = {}) => {
      const [page, size] = normalizedPage(query)
      return [...businessSpaceLists(), page, size] as const
    },
    businessSpaceOptions: () => [...businessSpaces(), 'options'] as const,
    businessSpaceDetails: () => [...businessSpaces(), 'detail'] as const,
    businessSpaceDetail: (spaceId: CatalogEntityId | undefined) =>
      [...businessSpaces(), 'detail', spaceId ?? null] as const,
    products,
    productLists,
    productListsForSpace: (spaceId: CatalogEntityId) =>
      [...productLists(), spaceId] as const,
    productOptions: (spaceId: CatalogEntityId | undefined) =>
      [...productLists(), spaceId ?? null, 'options'] as const,
    productList: (
      spaceId: CatalogEntityId | undefined,
      query: ProductListQuery = {},
    ) => {
      const [page, size] = normalizedPage(query)
      return [...productLists(), spaceId ?? null, page, size] as const
    },
    productDetails: () => [...products(), 'detail'] as const,
    productDetail: (productId: CatalogEntityId | undefined) =>
      [...products(), 'detail', productId ?? null] as const,
    productIdentityAssets: (productId: CatalogEntityId | undefined) =>
      [...products(), 'identity-assets', productId ?? null] as const,
    competitors,
    competitorLists,
    competitorListsForSpace: (spaceId: CatalogEntityId) =>
      [...competitorLists(), spaceId] as const,
    competitorList: (
      spaceId: CatalogEntityId | undefined,
      query: CompetitorListQuery = {},
    ) => {
      const [page, size] = normalizedPage(query)
      return [...competitorLists(), spaceId ?? null, page, size] as const
    },
    competitorDetails: () => [...competitors(), 'detail'] as const,
    competitorDetail: (competitorId: CatalogEntityId | undefined) =>
      [...competitors(), 'detail', competitorId ?? null] as const,
  }
}

const anonymousCatalogKeys = createCatalogKeys()

export const catalogKeys = {
  all: catalogRootKey,
  forOwner: createCatalogKeys,
  ...anonymousCatalogKeys,
}

type CatalogKeys = ReturnType<typeof createCatalogKeys>

function useCatalogKeys(): CatalogKeys {
  const ownerId = useAuthStore((state) => state.user?.id)
  return createCatalogKeys(ownerId)
}

/**
 * Catalog data is scoped by owner in its query key.  Removing the previous
 * owner's catalog cache when the session changes makes that boundary explicit
 * and prevents a selector mounted across logout/login from briefly reusing an
 * old account's spaces or products.
 */
export function useCatalogQueryIsolation() {
  const queryClient = useQueryClient()
  const ownerId = useAuthStore((state) => state.user?.id)
  const previousOwnerIdRef = useRef(ownerId)

  useEffect(() => {
    if (previousOwnerIdRef.current === ownerId) return

    previousOwnerIdRef.current = ownerId
    queryClient.removeQueries({ queryKey: catalogRootKey })
  }, [ownerId, queryClient])
}

async function loadAllBusinessSpaceOptions() {
  const options: BusinessSpaceSummary[] = []
  let page = DEFAULT_PAGE

  while (true) {
    const result = await listBusinessSpaces({
      page,
      size: OPTIONS_PAGE_SIZE,
    })
    options.push(...(result.items ?? []))

    const nextPage = nextOptionsPage(page, result)
    if (nextPage === undefined) return options
    page = nextPage
  }
}

async function loadAllProductOptions(spaceId: CatalogEntityId) {
  const options: ProductSummary[] = []
  let page = DEFAULT_PAGE

  while (true) {
    const result = await listProducts(spaceId, {
      page,
      size: OPTIONS_PAGE_SIZE,
    })
    options.push(...(result.items ?? []))

    const nextPage = nextOptionsPage(page, result)
    if (nextPage === undefined) return options
    page = nextPage
  }
}

async function invalidateBusinessSpaceCounts(
  queryClient: ReturnType<typeof useQueryClient>,
  keys: CatalogKeys,
  spaceId: CatalogEntityId,
) {
  await Promise.all([
    queryClient.invalidateQueries({
      queryKey: keys.businessSpaceLists(),
    }),
    queryClient.invalidateQueries({
      queryKey: keys.businessSpaceDetail(spaceId),
    }),
  ])
}

async function invalidateProductList(
  queryClient: ReturnType<typeof useQueryClient>,
  keys: CatalogKeys,
  spaceId: CatalogEntityId,
) {
  await queryClient.invalidateQueries({
    queryKey: keys.productListsForSpace(spaceId),
  })
}

async function invalidateCompetitorList(
  queryClient: ReturnType<typeof useQueryClient>,
  keys: CatalogKeys,
  spaceId: CatalogEntityId,
) {
  await queryClient.invalidateQueries({
    queryKey: keys.competitorListsForSpace(spaceId),
  })
}

async function invalidateBusinessSpaceCollections(
  queryClient: ReturnType<typeof useQueryClient>,
  keys: CatalogKeys,
) {
  await Promise.all([
    queryClient.invalidateQueries({
      queryKey: keys.businessSpaceLists(),
    }),
    queryClient.invalidateQueries({
      queryKey: keys.businessSpaceOptions(),
    }),
  ])
}

async function refreshDetailOnConflict(
  queryClient: ReturnType<typeof useQueryClient>,
  queryKey: readonly unknown[],
  error: unknown,
) {
  if (isApiError(error) && error.status === 409) {
    await queryClient.invalidateQueries({ queryKey })
  }
}

export function useBusinessSpaces(query: BusinessSpaceListQuery = {}) {
  const keys = useCatalogKeys()

  return useQuery({
    queryKey: keys.businessSpaceList(query),
    queryFn: () => listBusinessSpaces(query),
  })
}

export function useAllBusinessSpaceOptions() {
  const keys = useCatalogKeys()
  const ownerId = useAuthStore((state) => state.user?.id)

  return useQuery({
    queryKey: keys.businessSpaceOptions(),
    queryFn: loadAllBusinessSpaceOptions,
    // Catalog endpoints require an authenticated owner.  Keeping this query
    // disabled until the session is restored avoids an anonymous 401 racing
    // the login flow and leaving the selector in an error state.
    enabled: Boolean(ownerId),
  })
}

export function useBusinessSpace(spaceId?: CatalogEntityId) {
  const keys = useCatalogKeys()

  return useQuery({
    queryKey: keys.businessSpaceDetail(spaceId),
    queryFn: () => getBusinessSpace(spaceId as CatalogEntityId),
    enabled: Boolean(spaceId),
  })
}

export function useCreateBusinessSpace() {
  const queryClient = useQueryClient()
  const keys = useCatalogKeys()

  return useMutation({
    mutationFn: (request: CreateBusinessSpaceRequest) =>
      createBusinessSpace(request),
    onSuccess: async (businessSpace) => {
      queryClient.setQueryData(
        keys.businessSpaceDetail(businessSpace.id),
        businessSpace,
      )
      await invalidateBusinessSpaceCollections(queryClient, keys)
    },
  })
}

export interface UpdateBusinessSpaceVariables {
  spaceId: CatalogEntityId
  request: UpdateBusinessSpaceRequest
}

export function useUpdateBusinessSpace() {
  const queryClient = useQueryClient()
  const keys = useCatalogKeys()

  return useMutation({
    mutationFn: ({ spaceId, request }: UpdateBusinessSpaceVariables) =>
      updateBusinessSpace(spaceId, request),
    onSuccess: async (businessSpace) => {
      queryClient.setQueryData(
        keys.businessSpaceDetail(businessSpace.id),
        businessSpace,
      )
      await invalidateBusinessSpaceCollections(queryClient, keys)
    },
    onError: (error, { spaceId }) =>
      refreshDetailOnConflict(
        queryClient,
        keys.businessSpaceDetail(spaceId),
        error,
      ),
  })
}

export function useDeleteBusinessSpace() {
  const queryClient = useQueryClient()
  const keys = useCatalogKeys()

  return useMutation({
    mutationFn: (spaceId: CatalogEntityId) => deleteBusinessSpace(spaceId),
    onSuccess: async (_, spaceId) => {
      queryClient.removeQueries({
        queryKey: keys.businessSpaceDetail(spaceId),
      })
      await invalidateBusinessSpaceCollections(queryClient, keys)
    },
  })
}

export function useProducts(
  spaceId?: CatalogEntityId,
  query: ProductListQuery = {},
) {
  const keys = useCatalogKeys()

  return useQuery({
    queryKey: keys.productList(spaceId, query),
    queryFn: () => listProducts(spaceId as CatalogEntityId, query),
    enabled: Boolean(spaceId),
  })
}

export function useAllProductOptions(spaceId?: CatalogEntityId) {
  const keys = useCatalogKeys()
  const ownerId = useAuthStore((state) => state.user?.id)

  return useQuery({
    queryKey: keys.productOptions(spaceId),
    queryFn: () => loadAllProductOptions(spaceId as CatalogEntityId),
    enabled: Boolean(ownerId && spaceId),
  })
}

export function useProduct(productId?: CatalogEntityId) {
  const keys = useCatalogKeys()

  return useQuery({
    queryKey: keys.productDetail(productId),
    queryFn: () => getProduct(productId as CatalogEntityId),
    enabled: Boolean(productId),
  })
}

export function useProductIdentityAssets(productId?: CatalogEntityId) {
  const keys = useCatalogKeys()

  return useQuery({
    queryKey: keys.productIdentityAssets(productId),
    queryFn: () => getProductIdentityAssets(productId as CatalogEntityId),
    enabled: Boolean(productId),
  })
}

export interface ReplaceProductIdentityAssetsVariables {
  productId: CatalogEntityId
  spaceId: CatalogEntityId
  request: ReplaceProductIdentityAssetsRequest
}

export function useReplaceProductIdentityAssets() {
  const queryClient = useQueryClient()
  const keys = useCatalogKeys()

  return useMutation({
    mutationFn: ({
      productId,
      request,
    }: ReplaceProductIdentityAssetsVariables) =>
      replaceProductIdentityAssets(productId, request),
    onSuccess: async (identityAssets, { productId, spaceId }) => {
      queryClient.setQueryData(
        keys.productIdentityAssets(productId),
        identityAssets,
      )
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: keys.productDetail(productId),
        }),
        invalidateProductList(queryClient, keys, spaceId),
      ])
    },
    onError: (error, { productId }) =>
      refreshDetailOnConflict(
        queryClient,
        keys.productIdentityAssets(productId),
        error,
      ),
  })
}

export interface CreateProductVariables {
  spaceId: CatalogEntityId
  request: CreateProductRequest
  aliases: ProductAliases
}

export function useCreateProduct() {
  const queryClient = useQueryClient()
  const keys = useCatalogKeys()

  return useMutation({
    mutationFn: async ({ spaceId, request, aliases }: CreateProductVariables) => {
      const created = await createProduct(spaceId, request)
      queryClient.setQueryData(keys.productDetail(created.id), created)

      try {
        const product =
          aliases.length === 0
            ? created
            : await replaceProductAliases(created.id, {
                version: created.version,
                aliases,
              })
        queryClient.setQueryData(keys.productDetail(product.id), product)
        await Promise.all([
          invalidateProductList(queryClient, keys, product.spaceId),
          invalidateBusinessSpaceCounts(queryClient, keys, product.spaceId),
        ])
        return product
      } catch (error) {
        await Promise.all([
          invalidateProductList(queryClient, keys, created.spaceId),
          invalidateBusinessSpaceCounts(queryClient, keys, created.spaceId),
          refreshDetailOnConflict(
            queryClient,
            keys.productDetail(created.id),
            error,
          ),
        ])
        throw new CatalogPartialCreateError(created, error)
      }
    },
  })
}

export interface UpdateProductVariables {
  productId: CatalogEntityId
  spaceId: CatalogEntityId
  request: UpdateProductRequest
  aliases?: ProductAliases
}

export function useUpdateProduct() {
  const queryClient = useQueryClient()
  const keys = useCatalogKeys()

  return useMutation({
    mutationFn: async ({
      productId,
      spaceId,
      request,
      aliases,
    }: UpdateProductVariables) => {
      const updated = await updateProduct(productId, request)
      queryClient.setQueryData(keys.productDetail(updated.id), updated)

      try {
        const product =
          aliases === undefined
            ? updated
            : await replaceProductAliases(updated.id, {
                version: updated.version,
                aliases,
              })
        queryClient.setQueryData(keys.productDetail(product.id), product)
        await invalidateProductList(queryClient, keys, spaceId)
        return product
      } catch (error) {
        await invalidateProductList(queryClient, keys, spaceId)
        throw error
      }
    },
    onError: (error, { productId }) =>
      refreshDetailOnConflict(
        queryClient,
        keys.productDetail(productId),
        error,
      ),
  })
}

export interface ReplaceProductAliasesVariables {
  productId: CatalogEntityId
  request: ReplaceProductAliasesRequest
}

export function useReplaceProductAliases() {
  const queryClient = useQueryClient()
  const keys = useCatalogKeys()

  return useMutation({
    mutationFn: ({ productId, request }: ReplaceProductAliasesVariables) =>
      replaceProductAliases(productId, request),
    onSuccess: async (product) => {
      queryClient.setQueryData(keys.productDetail(product.id), product)
      await invalidateProductList(queryClient, keys, product.spaceId)
    },
    onError: (error, { productId }) =>
      refreshDetailOnConflict(
        queryClient,
        keys.productDetail(productId),
        error,
      ),
  })
}

export interface DeleteProductVariables {
  productId: CatalogEntityId
  spaceId: CatalogEntityId
}

export function useDeleteProduct() {
  const queryClient = useQueryClient()
  const keys = useCatalogKeys()

  return useMutation({
    mutationFn: ({ productId }: DeleteProductVariables) =>
      deleteProduct(productId),
    onSuccess: async (_, { productId, spaceId }) => {
      queryClient.removeQueries({
        queryKey: keys.productDetail(productId),
      })
      await Promise.all([
        invalidateProductList(queryClient, keys, spaceId),
        invalidateBusinessSpaceCounts(queryClient, keys, spaceId),
      ])
    },
  })
}

export function useCompetitors(
  spaceId?: CatalogEntityId,
  query: CompetitorListQuery = {},
) {
  const keys = useCatalogKeys()

  return useQuery({
    queryKey: keys.competitorList(spaceId, query),
    queryFn: () => listCompetitors(spaceId as CatalogEntityId, query),
    enabled: Boolean(spaceId),
  })
}

export function useCompetitor(competitorId?: CatalogEntityId) {
  const keys = useCatalogKeys()

  return useQuery({
    queryKey: keys.competitorDetail(competitorId),
    queryFn: () => getCompetitor(competitorId as CatalogEntityId),
    enabled: Boolean(competitorId),
  })
}

export interface CreateCompetitorVariables {
  spaceId: CatalogEntityId
  request: CreateCompetitorRequest
  aliases: CompetitorAliases
}

export function useCreateCompetitor() {
  const queryClient = useQueryClient()
  const keys = useCatalogKeys()

  return useMutation({
    mutationFn: async ({ spaceId, request, aliases }: CreateCompetitorVariables) => {
      const created = await createCompetitor(spaceId, request)
      queryClient.setQueryData(
        keys.competitorDetail(created.id),
        created,
      )

      try {
        const competitor =
          aliases.length === 0
            ? created
            : await replaceCompetitorAliases(created.id, {
                version: created.version,
                aliases,
              })
        queryClient.setQueryData(
          keys.competitorDetail(competitor.id),
          competitor,
        )
        await Promise.all([
          invalidateCompetitorList(queryClient, keys, competitor.spaceId),
          invalidateBusinessSpaceCounts(queryClient, keys, competitor.spaceId),
        ])
        return competitor
      } catch (error) {
        await Promise.all([
          invalidateCompetitorList(queryClient, keys, created.spaceId),
          invalidateBusinessSpaceCounts(queryClient, keys, created.spaceId),
          refreshDetailOnConflict(
            queryClient,
            keys.competitorDetail(created.id),
            error,
          ),
        ])
        throw new CatalogPartialCreateError(created, error)
      }
    },
  })
}

export interface UpdateCompetitorVariables {
  competitorId: CatalogEntityId
  spaceId: CatalogEntityId
  request: UpdateCompetitorRequest
  aliases?: CompetitorAliases
}

export function useUpdateCompetitor() {
  const queryClient = useQueryClient()
  const keys = useCatalogKeys()

  return useMutation({
    mutationFn: async ({
      competitorId,
      spaceId,
      request,
      aliases,
    }: UpdateCompetitorVariables) => {
      const updated = await updateCompetitor(competitorId, request)
      queryClient.setQueryData(
        keys.competitorDetail(updated.id),
        updated,
      )

      try {
        const competitor =
          aliases === undefined
            ? updated
            : await replaceCompetitorAliases(updated.id, {
                version: updated.version,
                aliases,
              })
        queryClient.setQueryData(
          keys.competitorDetail(competitor.id),
          competitor,
        )
        await invalidateCompetitorList(queryClient, keys, spaceId)
        return competitor
      } catch (error) {
        await invalidateCompetitorList(queryClient, keys, spaceId)
        throw error
      }
    },
    onError: (error, { competitorId }) =>
      refreshDetailOnConflict(
        queryClient,
        keys.competitorDetail(competitorId),
        error,
      ),
  })
}

export interface ReplaceCompetitorAliasesVariables {
  competitorId: CatalogEntityId
  request: ReplaceCompetitorAliasesRequest
}

export function useReplaceCompetitorAliases() {
  const queryClient = useQueryClient()
  const keys = useCatalogKeys()

  return useMutation({
    mutationFn: ({ competitorId, request }: ReplaceCompetitorAliasesVariables) =>
      replaceCompetitorAliases(competitorId, request),
    onSuccess: async (competitor) => {
      queryClient.setQueryData(
        keys.competitorDetail(competitor.id),
        competitor,
      )
      await invalidateCompetitorList(queryClient, keys, competitor.spaceId)
    },
    onError: (error, { competitorId }) =>
      refreshDetailOnConflict(
        queryClient,
        keys.competitorDetail(competitorId),
        error,
      ),
  })
}

export interface DeleteCompetitorVariables {
  competitorId: CatalogEntityId
  spaceId: CatalogEntityId
}

export function useDeleteCompetitor() {
  const queryClient = useQueryClient()
  const keys = useCatalogKeys()

  return useMutation({
    mutationFn: ({ competitorId }: DeleteCompetitorVariables) =>
      deleteCompetitor(competitorId),
    onSuccess: async (_, { competitorId, spaceId }) => {
      queryClient.removeQueries({
        queryKey: keys.competitorDetail(competitorId),
      })
      await Promise.all([
        invalidateCompetitorList(queryClient, keys, spaceId),
        invalidateBusinessSpaceCounts(queryClient, keys, spaceId),
      ])
    },
  })
}

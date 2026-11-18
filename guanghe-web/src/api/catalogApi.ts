import type { components, operations } from './generated/browser-api'
import { apiRequest } from './client'

export type CatalogEntityId = components['schemas']['BusinessId']

export type BusinessSpace = components['schemas']['BusinessSpace']
export type BusinessSpaceSummary = components['schemas']['BusinessSpaceSummary']
export type BusinessSpacePage = components['schemas']['BusinessSpacePage']
export type CreateBusinessSpaceRequest =
  components['schemas']['CreateBusinessSpaceRequest']
export type UpdateBusinessSpaceRequest =
  components['schemas']['UpdateBusinessSpaceRequest']

export type Product = components['schemas']['Product']
export type ProductSummary = components['schemas']['ProductSummary']
export type ProductPage = components['schemas']['ProductPage']
export type CreateProductRequest = components['schemas']['CreateProductRequest']
export type UpdateProductRequest = components['schemas']['UpdateProductRequest']
export type ReplaceProductAliasesRequest =
  components['schemas']['ReplaceProductAliasesRequest']
export type ProductAliases = components['schemas']['ProductAliases']
export type ProductIdentityAsset =
  components['schemas']['ProductIdentityAsset']
export type ProductIdentityAssetInput =
  components['schemas']['ProductIdentityAssetInput']
export type ProductIdentityAssets =
  components['schemas']['ProductIdentityAssets']
export type ReplaceProductIdentityAssetsRequest =
  components['schemas']['ReplaceProductIdentityAssetsRequest']

export type Competitor = components['schemas']['Competitor']
export type CompetitorSummary = components['schemas']['CompetitorSummary']
export type CompetitorPage = components['schemas']['CompetitorPage']
export type CreateCompetitorRequest =
  components['schemas']['CreateCompetitorRequest']
export type UpdateCompetitorRequest =
  components['schemas']['UpdateCompetitorRequest']
export type ReplaceCompetitorAliasesRequest =
  components['schemas']['ReplaceCompetitorAliasesRequest']
export type CompetitorAliases = components['schemas']['CompetitorAliases']

export type BusinessSpaceListQuery = NonNullable<
  operations['listBusinessSpaces']['parameters']['query']
>
export type ProductListQuery = NonNullable<
  operations['listProducts']['parameters']['query']
>
export type CompetitorListQuery = NonNullable<
  operations['listCompetitors']['parameters']['query']
>

type BusinessSpaceApiResponse =
  components['schemas']['BusinessSpaceApiResponse']
type BusinessSpacePageApiResponse =
  components['schemas']['BusinessSpacePageApiResponse']
type ProductApiResponse = components['schemas']['ProductApiResponse']
type ProductIdentityAssetsApiResponse =
  components['schemas']['ProductIdentityAssetsApiResponse']
type ProductPageApiResponse = components['schemas']['ProductPageApiResponse']
type CompetitorApiResponse = components['schemas']['CompetitorApiResponse']
type CompetitorPageApiResponse =
  components['schemas']['CompetitorPageApiResponse']
type EmptySuccessApiResponse = components['schemas']['EmptySuccessApiResponse']

function encodedId(id: CatalogEntityId): string {
  return encodeURIComponent(id)
}

function withPageQuery(
  path: string,
  query: BusinessSpaceListQuery | ProductListQuery | CompetitorListQuery,
): string {
  const search = new URLSearchParams()

  if (query.page !== undefined) {
    search.set('page', String(query.page))
  }
  if (query.size !== undefined) {
    search.set('size', String(query.size))
  }

  const queryString = search.toString()
  return queryString ? `${path}?${queryString}` : path
}

export async function listBusinessSpaces(
  query: BusinessSpaceListQuery = {},
): Promise<BusinessSpacePage> {
  const response = await apiRequest<BusinessSpacePageApiResponse>(
    withPageQuery('/api/v1/spaces', query),
    { method: 'GET', auth: 'required' },
  )

  return response.data
}

export async function getBusinessSpace(
  spaceId: CatalogEntityId,
): Promise<BusinessSpace> {
  const response = await apiRequest<BusinessSpaceApiResponse>(
    `/api/v1/spaces/${encodedId(spaceId)}`,
    { method: 'GET', auth: 'required' },
  )

  return response.data
}

export async function createBusinessSpace(
  request: CreateBusinessSpaceRequest,
): Promise<BusinessSpace> {
  const response = await apiRequest<BusinessSpaceApiResponse>(
    '/api/v1/spaces',
    { method: 'POST', auth: 'required', body: request },
  )

  return response.data
}

export async function updateBusinessSpace(
  spaceId: CatalogEntityId,
  request: UpdateBusinessSpaceRequest,
): Promise<BusinessSpace> {
  const response = await apiRequest<BusinessSpaceApiResponse>(
    `/api/v1/spaces/${encodedId(spaceId)}`,
    { method: 'PATCH', auth: 'required', body: request },
  )

  return response.data
}

export async function deleteBusinessSpace(
  spaceId: CatalogEntityId,
): Promise<void> {
  await apiRequest<EmptySuccessApiResponse>(
    `/api/v1/spaces/${encodedId(spaceId)}`,
    { method: 'DELETE', auth: 'required' },
  )
}

export async function listProducts(
  spaceId: CatalogEntityId,
  query: ProductListQuery = {},
): Promise<ProductPage> {
  const response = await apiRequest<ProductPageApiResponse>(
    withPageQuery(`/api/v1/spaces/${encodedId(spaceId)}/products`, query),
    { method: 'GET', auth: 'required' },
  )

  return response.data
}

export async function getProduct(
  productId: CatalogEntityId,
): Promise<Product> {
  const response = await apiRequest<ProductApiResponse>(
    `/api/v1/products/${encodedId(productId)}`,
    { method: 'GET', auth: 'required' },
  )

  return response.data
}

export async function createProduct(
  spaceId: CatalogEntityId,
  request: CreateProductRequest,
): Promise<Product> {
  const response = await apiRequest<ProductApiResponse>(
    `/api/v1/spaces/${encodedId(spaceId)}/products`,
    { method: 'POST', auth: 'required', body: request },
  )

  return response.data
}

export async function updateProduct(
  productId: CatalogEntityId,
  request: UpdateProductRequest,
): Promise<Product> {
  const response = await apiRequest<ProductApiResponse>(
    `/api/v1/products/${encodedId(productId)}`,
    { method: 'PATCH', auth: 'required', body: request },
  )

  return response.data
}

export async function deleteProduct(
  productId: CatalogEntityId,
): Promise<void> {
  await apiRequest<EmptySuccessApiResponse>(
    `/api/v1/products/${encodedId(productId)}`,
    { method: 'DELETE', auth: 'required' },
  )
}

export async function replaceProductAliases(
  productId: CatalogEntityId,
  request: ReplaceProductAliasesRequest,
): Promise<Product> {
  const response = await apiRequest<ProductApiResponse>(
    `/api/v1/products/${encodedId(productId)}/aliases`,
    { method: 'PUT', auth: 'required', body: request },
  )

  return response.data
}

export async function getProductIdentityAssets(
  productId: CatalogEntityId,
): Promise<ProductIdentityAssets> {
  const response = await apiRequest<ProductIdentityAssetsApiResponse>(
    `/api/v1/products/${encodedId(productId)}/identity-assets`,
    { method: 'GET', auth: 'required' },
  )

  return response.data
}

export async function replaceProductIdentityAssets(
  productId: CatalogEntityId,
  request: ReplaceProductIdentityAssetsRequest,
): Promise<ProductIdentityAssets> {
  const response = await apiRequest<ProductIdentityAssetsApiResponse>(
    `/api/v1/products/${encodedId(productId)}/identity-assets`,
    { method: 'PUT', auth: 'required', body: request },
  )

  return response.data
}

export async function createProductWithAliases(
  spaceId: CatalogEntityId,
  request: CreateProductRequest,
  aliases: ProductAliases,
): Promise<Product> {
  const created = await createProduct(spaceId, request)

  if (aliases.length === 0) {
    return created
  }

  return replaceProductAliases(created.id, {
    version: created.version,
    aliases,
  })
}

export async function updateProductWithAliases(
  productId: CatalogEntityId,
  request: UpdateProductRequest,
  aliases?: ProductAliases,
): Promise<Product> {
  const updated = await updateProduct(productId, request)

  if (aliases === undefined) {
    return updated
  }

  return replaceProductAliases(updated.id, {
    version: updated.version,
    aliases,
  })
}

export async function listCompetitors(
  spaceId: CatalogEntityId,
  query: CompetitorListQuery = {},
): Promise<CompetitorPage> {
  const response = await apiRequest<CompetitorPageApiResponse>(
    withPageQuery(`/api/v1/spaces/${encodedId(spaceId)}/competitors`, query),
    { method: 'GET', auth: 'required' },
  )

  return response.data
}

export async function getCompetitor(
  competitorId: CatalogEntityId,
): Promise<Competitor> {
  const response = await apiRequest<CompetitorApiResponse>(
    `/api/v1/competitors/${encodedId(competitorId)}`,
    { method: 'GET', auth: 'required' },
  )

  return response.data
}

export async function createCompetitor(
  spaceId: CatalogEntityId,
  request: CreateCompetitorRequest,
): Promise<Competitor> {
  const response = await apiRequest<CompetitorApiResponse>(
    `/api/v1/spaces/${encodedId(spaceId)}/competitors`,
    { method: 'POST', auth: 'required', body: request },
  )

  return response.data
}

export async function updateCompetitor(
  competitorId: CatalogEntityId,
  request: UpdateCompetitorRequest,
): Promise<Competitor> {
  const response = await apiRequest<CompetitorApiResponse>(
    `/api/v1/competitors/${encodedId(competitorId)}`,
    { method: 'PATCH', auth: 'required', body: request },
  )

  return response.data
}

export async function deleteCompetitor(
  competitorId: CatalogEntityId,
): Promise<void> {
  await apiRequest<EmptySuccessApiResponse>(
    `/api/v1/competitors/${encodedId(competitorId)}`,
    { method: 'DELETE', auth: 'required' },
  )
}

export async function replaceCompetitorAliases(
  competitorId: CatalogEntityId,
  request: ReplaceCompetitorAliasesRequest,
): Promise<Competitor> {
  const response = await apiRequest<CompetitorApiResponse>(
    `/api/v1/competitors/${encodedId(competitorId)}/aliases`,
    { method: 'PUT', auth: 'required', body: request },
  )

  return response.data
}

export async function createCompetitorWithAliases(
  spaceId: CatalogEntityId,
  request: CreateCompetitorRequest,
  aliases: CompetitorAliases,
): Promise<Competitor> {
  const created = await createCompetitor(spaceId, request)

  if (aliases.length === 0) {
    return created
  }

  return replaceCompetitorAliases(created.id, {
    version: created.version,
    aliases,
  })
}

export async function updateCompetitorWithAliases(
  competitorId: CatalogEntityId,
  request: UpdateCompetitorRequest,
  aliases?: CompetitorAliases,
): Promise<Competitor> {
  const updated = await updateCompetitor(competitorId, request)

  if (aliases === undefined) {
    return updated
  }

  return replaceCompetitorAliases(updated.id, {
    version: updated.version,
    aliases,
  })
}

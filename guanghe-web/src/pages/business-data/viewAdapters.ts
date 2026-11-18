import { isApiError } from '../../api/apiError'
import type {
  BusinessSpace,
  BusinessSpacePage,
  BusinessSpaceSummary,
  Competitor,
  CompetitorPage,
  CompetitorSummary,
  CreateBusinessSpaceRequest,
  CreateCompetitorRequest,
  CreateProductRequest,
  Product,
  ProductPage,
  ProductSummary,
  UpdateBusinessSpaceRequest,
  UpdateCompetitorRequest,
  UpdateProductRequest,
} from '../../api/catalogApi'
import type {
  BusinessListPagination,
  BusinessSpaceFormValues,
  BusinessSpaceListItemView,
  BusinessSpaceOption,
  BusinessSpaceSummaryView,
  BusinessViewError,
  CompetitorFormValues,
  CompetitorListItemView,
  ProductDetailView,
  ProductFormValues,
  ProductListItemView,
} from '../../features/business-data'
import { creativeGroundingErrorMessage } from '../../features/chat/creativeGrounding'

const DEFAULT_PAGE = 1
const DEFAULT_PAGE_SIZE = 20

export function toBusinessViewError(error: unknown): BusinessViewError | null {
  if (!error) {
    return null
  }

  if (isApiError(error)) {
    return {
      message: creativeGroundingErrorMessage(error.code, error.message),
      traceId: error.traceId,
    }
  }

  return { message: '请求失败，请稍后重试。' }
}

export function toPagination(
  page: Pick<BusinessSpacePage | ProductPage | CompetitorPage, 'page' | 'size' | 'total'> | undefined,
  fallbackPage = DEFAULT_PAGE,
  fallbackSize = DEFAULT_PAGE_SIZE,
): BusinessListPagination {
  return {
    current: page?.page ?? fallbackPage,
    pageSize: page?.size ?? fallbackSize,
    total: page?.total ?? 0,
  }
}

export function toBusinessSpaceOption(
  space: BusinessSpaceSummary,
): BusinessSpaceOption {
  return { id: space.id, name: space.name }
}

export function toBusinessSpaceListItem(
  space: BusinessSpaceSummary,
): BusinessSpaceListItemView {
  return {
    id: space.id,
    name: space.name,
    brandName: space.brandName,
    linkedProductCount: space.productCount,
    updatedAt: space.updatedAt,
  }
}

export function toBusinessSpaceSummaryView(
  page: BusinessSpacePage | undefined,
): BusinessSpaceSummaryView {
  return {
    totalSpaces: page?.total,
  }
}

function joinItems(items: readonly string[]): string | undefined {
  return items.length > 0 ? items.join('\n') : undefined
}

function toItems(value: string | undefined): string[] {
  return (value ?? '')
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function toNullableText(value: string | undefined): string | null {
  const normalized = value?.trim()
  return normalized ? normalized : null
}

export function toBusinessSpaceFormValues(
  space: BusinessSpace,
): BusinessSpaceFormValues {
  return {
    name: space.name,
    brandName: space.brand.name,
    brandAliases: [...space.brand.aliases],
    brandIntroduction: space.brand.description ?? undefined,
    targetAudience: joinItems(space.brand.audience),
    coreSellingPoints: joinItems(space.brand.sellingPoints),
    forbiddenExpressions: [...space.brand.prohibitedClaims],
  }
}

function toBusinessSpaceBrand(
  values: BusinessSpaceFormValues,
  tone?: readonly string[],
): CreateBusinessSpaceRequest['brand'] {
  return {
    name: values.brandName.trim(),
    aliases: values.brandAliases,
    description: toNullableText(values.brandIntroduction),
    ...(tone ? { tone: [...tone] } : {}),
    audience: toItems(values.targetAudience),
    sellingPoints: toItems(values.coreSellingPoints),
    prohibitedClaims: values.forbiddenExpressions,
  }
}

export function toCreateBusinessSpaceRequest(
  values: BusinessSpaceFormValues,
): CreateBusinessSpaceRequest {
  return {
    name: values.name.trim(),
    brand: toBusinessSpaceBrand(values),
  }
}

export function toUpdateBusinessSpaceRequest(
  values: BusinessSpaceFormValues,
  current: BusinessSpace,
): UpdateBusinessSpaceRequest {
  return {
    version: current.version,
    name: values.name.trim(),
    brand: toBusinessSpaceBrand(values, current.brand.tone),
  }
}

export function toProductListItem(
  product: ProductSummary,
  businessSpaceName: string,
): ProductListItemView {
  return {
    id: product.id,
    businessSpaceId: product.spaceId,
    businessSpaceName,
    name: product.name,
    aliasCount: product.aliasCount,
    category: product.category ?? undefined,
    modelOrSpecification: product.modelSpec ?? undefined,
    updatedAt: product.updatedAt,
  }
}

export function productPageItems(page: ProductPage | undefined): ProductSummary[] {
  return (page?.items ?? []) as ProductSummary[]
}

function toProductParameters(parameters: Product['parameters']) {
  return Object.entries(parameters).map(([name, value]) => ({ name, value }))
}

export function toProductDetailView(
  product: Product,
  businessSpaceName: string,
): ProductDetailView {
  return {
    id: product.id,
    businessSpaceId: product.spaceId,
    businessSpaceName,
    name: product.name,
    aliases: [...product.aliases],
    category: product.category ?? undefined,
    modelOrSpecification: product.modelSpec ?? undefined,
    updatedAt: product.updatedAt,
    parameters: toProductParameters(product.parameters),
    sellingPoints: joinItems(product.sellingPoints),
    targetAudience: joinItems(product.audience),
    usageScenarios: joinItems(product.usageScenarios),
    precautions: joinItems(product.prohibitedClaims),
    referenceAssetUrls: [],
  }
}

export function toProductFormValues(product: Product): ProductFormValues {
  return {
    businessSpaceId: product.spaceId,
    name: product.name,
    aliases: [...product.aliases],
    category: product.category ?? undefined,
    modelOrSpecification: product.modelSpec ?? undefined,
    parameters: toProductParameters(product.parameters),
    sellingPoints: joinItems(product.sellingPoints),
    targetAudience: joinItems(product.audience),
    usageScenarios: joinItems(product.usageScenarios),
    precautions: joinItems(product.prohibitedClaims),
  }
}

function toParameterRecord(values: ProductFormValues): Record<string, string> {
  return Object.fromEntries(
    values.parameters.map(({ name, value }) => [name.trim(), value.trim()]),
  )
}

function productFields(values: ProductFormValues) {
  return {
    name: values.name.trim(),
    category: toNullableText(values.category),
    modelSpec: toNullableText(values.modelOrSpecification),
    sellingPoints: toItems(values.sellingPoints),
    parameters: toParameterRecord(values),
    audience: toItems(values.targetAudience),
    usageScenarios: toItems(values.usageScenarios),
    prohibitedClaims: toItems(values.precautions),
  }
}

export function toCreateProductRequest(
  values: ProductFormValues,
): CreateProductRequest {
  return productFields(values)
}

export function toUpdateProductRequest(
  values: ProductFormValues,
  current: Product,
): UpdateProductRequest {
  return {
    version: current.version,
    ...productFields(values),
  }
}

export function toCompetitorListItem(
  competitor: CompetitorSummary,
  businessSpaceName: string,
): CompetitorListItemView {
  return {
    id: competitor.id,
    businessSpaceId: competitor.spaceId,
    businessSpaceName,
    name: competitor.name,
    aliasCount: competitor.aliasCount,
    competitorType: competitor.type,
    status: competitor.status === 'ACTIVE' ? '启用' : '停用',
    updatedAt: competitor.updatedAt,
  }
}

export function competitorPageItems(
  page: CompetitorPage | undefined,
): CompetitorSummary[] {
  return (page?.items ?? []) as CompetitorSummary[]
}

export function toCompetitorFormValues(
  competitor: Competitor,
): CompetitorFormValues {
  return {
    businessSpaceId: competitor.spaceId,
    name: competitor.name,
    aliases: [...competitor.aliases],
    competitorType: competitor.type,
    description: competitor.description ?? undefined,
  }
}

export function toCreateCompetitorRequest(
  values: CompetitorFormValues,
): CreateCompetitorRequest {
  return {
    name: values.name.trim(),
    type: values.competitorType,
    description: toNullableText(values.description),
    status: 'ACTIVE',
  }
}

export function toUpdateCompetitorRequest(
  values: CompetitorFormValues,
  current: Competitor,
): UpdateCompetitorRequest {
  return {
    version: current.version,
    name: values.name.trim(),
    type: values.competitorType,
    description: toNullableText(values.description),
  }
}

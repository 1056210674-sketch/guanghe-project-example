/**
 * Presentation-layer view models for the M2A business-data screens.
 *
 * These types deliberately remain presentation-only view models. Page
 * containers map generated Java Browser OpenAPI types into this shape.
 */

export type BusinessEntityId = string

export interface BusinessViewError {
  message: string
  traceId?: string
}

export interface BusinessListPagination {
  current: number
  pageSize: number
  total: number
}

export interface BusinessDataPermissions {
  canCreate: boolean
  canEdit: boolean
  canDelete: boolean
}

export interface BusinessSpaceOption {
  id: BusinessEntityId
  name: string
}

export interface BusinessSpaceSummaryView {
  totalSpaces?: number
  averageCompleteness?: number
  linkedProducts?: number
  incompleteSpaces?: number
}

export interface BusinessSpaceListItemView {
  id: BusinessEntityId
  name: string
  brandName: string
  brandAliases?: string[]
  completeness?: number
  linkedProductCount?: number
  updatedAt?: string
}

export interface BusinessSpaceFormValues {
  name: string
  brandName: string
  brandAliases: string[]
  logoAssetReference?: string
  brandIntroduction?: string
  targetAudience?: string
  coreSellingPoints?: string
  forbiddenExpressions: string[]
}

export interface ProductParameterView {
  name: string
  value: string
}

export type ProductIdentityAssetRole =
  | 'PRIMARY'
  | 'WHITE_BACKGROUND'
  | 'DETAIL'
  | 'PACKAGE'

export type ProductIdentityAssetStatus =
  | 'UPLOADING'
  | 'SCANNING'
  | 'READY'
  | 'UNAVAILABLE'

export interface ProductIdentityAssetView {
  assetId: BusinessEntityId
  name: string
  role: ProductIdentityAssetRole
  order: number
  status: ProductIdentityAssetStatus
  thumbnailUrl?: string
}

export interface ProductListItemView {
  id: BusinessEntityId
  businessSpaceId: BusinessEntityId
  businessSpaceName: string
  name: string
  aliases?: string[]
  aliasCount?: number
  category?: string
  modelOrSpecification?: string
  mainAssetUrl?: string
  updatedAt?: string
}

export interface ProductDetailView extends Omit<ProductListItemView, 'aliases'> {
  aliases: string[]
  parameters: ProductParameterView[]
  sellingPoints?: string
  targetAudience?: string
  usageScenarios?: string
  precautions?: string
  referenceAssetUrls: string[]
}

export interface ProductFormValues {
  businessSpaceId: BusinessEntityId
  name: string
  aliases: string[]
  category?: string
  modelOrSpecification?: string
  parameters: ProductParameterView[]
  sellingPoints?: string
  targetAudience?: string
  usageScenarios?: string
  precautions?: string
}

export type CompetitorType = 'DIRECT' | 'INDIRECT' | 'SUBSTITUTE'

export interface CompetitorListItemView {
  id: BusinessEntityId
  businessSpaceId: BusinessEntityId
  businessSpaceName: string
  name: string
  aliases?: string[]
  aliasCount?: number
  competitorType: CompetitorType
  description?: string
  status?: string
  updatedAt?: string
}

export interface CompetitorFormValues {
  businessSpaceId: BusinessEntityId
  name: string
  aliases: string[]
  competitorType: CompetitorType
  description?: string
}

export interface BusinessCollectionProps<T> {
  data: T[]
  loading: boolean
  error: BusinessViewError | null
  pagination: BusinessListPagination
  permissions: BusinessDataPermissions
  onCreate: () => void
  onEdit: (id: BusinessEntityId) => void
  deleteError?: BusinessViewError | null
  onDelete: (id: BusinessEntityId) => void | Promise<void>
  onPageChange: (page: number, pageSize: number) => void
  onRetry?: () => void
}

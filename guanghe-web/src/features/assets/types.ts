export type AssetKind = 'IMAGE' | 'VIDEO' | 'ARTICLE' | 'GEO_REPORT'

export type AssetStatus =
  | 'UPLOADING'
  | 'SCANNING'
  | 'READY'
  | 'SAFETY_REVIEW'
  | 'HIDDEN'
  | 'REJECTED'
  | 'FAILED'
  | 'DELETING'

export type AssetSafetyStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'TEST_BYPASS'
  | 'NOT_REVIEWED'
  | 'REJECTED'
  | 'UNAVAILABLE'

export type AssetSource = 'GENERATED' | 'UPLOADED' | 'IMPORTED'

export interface AssetOption {
  label: string
  value: string
}

export interface AssetItem {
  id: string
  name: string
  kind: AssetKind
  status: AssetStatus
  safetyStatus: AssetSafetyStatus
  source: AssetSource
  createdAt: string
  updatedAt: string
  contentType: string
  sizeBytes: number
  sha256: string
  width?: number
  height?: number
  durationSeconds?: string
  businessSpaceId: string
  businessSpaceName?: string
  productId?: string
  productName?: string
  sourceTaskId?: string
}

export interface AssetStorageSummary {
  usedBytes?: number
  limitBytes?: number
}

export interface AssetViewError {
  message: string
  traceId?: string
}

export interface AssetPaginationView {
  page: number
  size: number
  total: number
  hasNext: boolean
}

export interface AssetFilterView {
  type?: AssetKind
  spaceId?: string
  productId?: string
  sourceType?: AssetSource
}

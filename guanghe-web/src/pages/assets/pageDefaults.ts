import type {
  AssetItem,
  AssetOption,
  AssetPaginationView,
  AssetStorageSummary,
} from '../../features/assets'

export const EMPTY_ASSET_ITEMS: readonly AssetItem[] = []
export const EMPTY_ASSET_OPTIONS: readonly AssetOption[] = []
export const EMPTY_ASSET_STORAGE: AssetStorageSummary = {}
export const EMPTY_ASSET_PAGINATION: AssetPaginationView = {
  page: 1,
  size: 20,
  total: 0,
  hasNext: false,
}

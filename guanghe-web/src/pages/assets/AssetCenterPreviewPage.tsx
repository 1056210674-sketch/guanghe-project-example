import { AssetCenterWorkspace } from '../../features/assets'
import { UserShell } from '../../layouts/UserShell'
import {
  EMPTY_ASSET_ITEMS,
  EMPTY_ASSET_OPTIONS,
  EMPTY_ASSET_PAGINATION,
  EMPTY_ASSET_STORAGE,
} from './pageDefaults'

export function AssetCenterPreviewPage() {
  return (
    <UserShell>
      <AssetCenterWorkspace
        assets={EMPTY_ASSET_ITEMS}
        businessSpaces={EMPTY_ASSET_OPTIONS}
        products={EMPTY_ASSET_OPTIONS}
        storage={EMPTY_ASSET_STORAGE}
        filters={{}}
        pagination={EMPTY_ASSET_PAGINATION}
        loading={false}
        error={null}
        detailOpen={false}
        detailAsset={null}
        detailLoading={false}
        detailError={null}
        previewUrlsByAssetId={{}}
        previewExpiresAtByAssetId={{}}
        onFiltersChange={() => undefined}
        onPageChange={() => undefined}
        onOpenDetail={() => undefined}
        onCloseDetail={() => undefined}
        onOpenOriginal={() => undefined}
        onRetry={() => undefined}
        onRetryDetail={() => undefined}
      />
    </UserShell>
  )
}

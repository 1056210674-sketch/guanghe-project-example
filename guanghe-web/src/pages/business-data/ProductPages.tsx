import { ArrowLeftOutlined } from '@ant-design/icons'
import { Button, Typography } from 'antd'
import { useState } from 'react'
import type { ReactNode } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import type { ProductIdentityAssetInput } from '../../api/catalogApi'
import {
  ProductDetailDrawer,
  ProductForm,
  ProductList,
  useAllBusinessSpaceOptions,
  useCreateProduct,
  useDeleteProduct,
  useProduct,
  useProductIdentityAssets,
  useProducts,
  useReplaceProductIdentityAssets,
  useReplaceProductAliases,
  useUpdateProduct,
} from '../../features/business-data'
import type {
  BusinessSpaceOption,
  BusinessViewError,
  ProductFormValues,
  ProductIdentityAssetRole,
  ProductIdentityAssetView,
} from '../../features/business-data'
import {
  useAssetDetails,
  useAssetDownloadUrls,
  useUploadImageAsset,
} from '../../features/assets/live/assetQueries'
import { ImageAssetPickerDialog } from '../../features/assets/components/ImageAssetPickerDialog'
import {
  EmptyState,
  ErrorState,
  PageLoading,
} from '../../components/GlobalState'
import { UserShell } from '../../layouts/UserShell'
import { errorTextWithDiagnosticTrace } from '../../shared/tracePresentation'
import { APP_PATHS, appLinks } from '../../routes/paths'
import {
  BUSINESS_DATA_PERMISSIONS,
  toCatalogCreateRecoveryState,
} from './pageDefaults'
import type { CatalogCreateRecoveryState } from './pageDefaults'
import {
  toBusinessSpaceOption,
  toBusinessViewError,
  toCreateProductRequest,
  toPagination,
  productPageItems,
  toProductDetailView,
  toProductFormValues,
  toProductListItem,
  toUpdateProductRequest,
} from './viewAdapters'

const DEFAULT_PAGE_SIZE = 20

export function ProductListPage() {
  const navigate = useNavigate()
  const [businessSpaceId, setBusinessSpaceId] = useState<string>()
  const [selectedProductId, setSelectedProductId] = useState<string>()
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)
  const businessSpacesQuery = useAllBusinessSpaceOptions()
  const productsQuery = useProducts(businessSpaceId, { page, size: pageSize })
  const productQuery = useProduct(selectedProductId)
  const deleteMutation = useDeleteProduct()
  const replaceAliasesMutation = useReplaceProductAliases()

  const businessSpaceOptions = (businessSpacesQuery.data ?? []).map(
    toBusinessSpaceOption,
  )
  const selectedBusinessSpaceName =
    businessSpaceOptions.find((option) => option.id === businessSpaceId)?.name ?? '—'
  const detailBusinessSpaceName =
    businessSpaceOptions.find((option) => option.id === productQuery.data?.spaceId)
      ?.name ?? '—'
  const listError = toBusinessViewError(
    businessSpacesQuery.error ?? productsQuery.error,
  )
  const pageItems = productPageItems(productsQuery.data)

  const handleBusinessSpaceChange = (nextBusinessSpaceId?: string) => {
    setBusinessSpaceId(nextBusinessSpaceId)
    setSelectedProductId(undefined)
    setPage(1)
  }

  const handlePageChange = (nextPage: number, nextPageSize: number) => {
    setPage(nextPageSize === pageSize ? nextPage : 1)
    setPageSize(nextPageSize)
  }

  return (
    <UserShell>
      <ProductList
        data={pageItems.map((product) =>
          toProductListItem(product, selectedBusinessSpaceName),
        )}
        loading={
          businessSpacesQuery.isPending ||
          (Boolean(businessSpaceId) && productsQuery.isPending)
        }
        error={listError}
        deleteError={toBusinessViewError(deleteMutation.error)}
        pagination={toPagination(productsQuery.data, page, pageSize)}
        permissions={BUSINESS_DATA_PERMISSIONS}
        businessSpaceOptions={businessSpaceOptions}
        businessSpaceId={businessSpaceId}
        onBusinessSpaceChange={handleBusinessSpaceChange}
        onCreate={() => navigate(APP_PATHS.productCreate)}
        onEdit={(id) => navigate(appLinks.productEdit(id))}
        onDelete={async (productId) => {
          if (!businessSpaceId) {
            return
          }
          await deleteMutation.mutateAsync({
            productId,
            spaceId: businessSpaceId,
          })
          if (page > 1 && pageItems.length <= 1) {
            setPage(page - 1)
          }
        }}
        onView={(productId) => {
          replaceAliasesMutation.reset()
          setSelectedProductId(productId)
        }}
        onPageChange={handlePageChange}
        onRetry={() => {
          void businessSpacesQuery.refetch()
          if (businessSpaceId) {
            void productsQuery.refetch()
          }
        }}
      />
      <ProductDetailDrawer
        key={
          productQuery.data
            ? `${productQuery.data.id}:${productQuery.data.version}`
            : selectedProductId ?? 'product-drawer-closed'
        }
        open={Boolean(selectedProductId)}
        product={
          productQuery.data
            ? toProductDetailView(productQuery.data, detailBusinessSpaceName)
            : null
        }
        loading={Boolean(selectedProductId) && productQuery.isPending}
        error={toBusinessViewError(productQuery.error)}
        actionError={toBusinessViewError(replaceAliasesMutation.error)}
        savingAliases={replaceAliasesMutation.isPending}
        onClose={() => {
          replaceAliasesMutation.reset()
          setSelectedProductId(undefined)
        }}
        onRetry={() => void productQuery.refetch()}
        onEdit={(id) => navigate(appLinks.productEdit(id))}
        onSaveAliases={(productId, aliases) => {
          if (!productQuery.data) {
            return
          }
          replaceAliasesMutation.mutate({
            productId,
            request: { version: productQuery.data.version, aliases },
          })
        }}
      />
    </UserShell>
  )
}

function ProductFormState({
  loading,
  error,
  businessSpaceOptions,
  onRetry,
  onCreateBusinessSpace,
  children,
}: {
  loading: boolean
  error: BusinessViewError | null
  businessSpaceOptions: BusinessSpaceOption[]
  onRetry: () => void
  onCreateBusinessSpace: () => void
  children: ReactNode
}) {
  if (loading) {
    return <PageLoading label="正在加载经营空间…" />
  }

  if (error) {
    return <ErrorState message={error.message} traceId={error.traceId} onRetry={onRetry} />
  }

  if (businessSpaceOptions.length === 0) {
    return (
      <EmptyState
        title="请先新建经营空间"
        description="商品必须归属于一个经营空间。"
        action={
          <Button type="primary" onClick={onCreateBusinessSpace}>
            新建经营空间
          </Button>
        }
      />
    )
  }

  return children
}

export function ProductCreatePage() {
  const navigate = useNavigate()
  const businessSpacesQuery = useAllBusinessSpaceOptions()
  const createMutation = useCreateProduct()
  const businessSpaceOptions = (businessSpacesQuery.data ?? []).map(
    toBusinessSpaceOption,
  )

  const handleSubmit = (values: ProductFormValues) => {
    createMutation.mutate(
      {
        spaceId: values.businessSpaceId,
        request: toCreateProductRequest(values),
        aliases: values.aliases,
      },
      {
        onSuccess: (product) => {
          navigate(appLinks.productEdit(product.id), { replace: true })
        },
        onError: (error) => {
          const recoveryState = toCatalogCreateRecoveryState(
            error,
            '商品',
            values.aliases,
          )
          if (recoveryState) {
            navigate(appLinks.productEdit(recoveryState.createdResourceId), {
              replace: true,
              state: recoveryState.state,
            })
          }
        },
      },
    )
  }

  return (
    <UserShell>
      <section className="business-form-page" aria-labelledby="product-form-title">
        <header className="business-page-heading">
          <div>
            <Button
              className="business-back-button"
              type="link"
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate(APP_PATHS.products)}
            >
              返回商品
            </Button>
            <Typography.Title id="product-form-title" level={1}>
              新建商品
            </Typography.Title>
            <Typography.Paragraph type="secondary">
              商品资料需归属经营空间，商品素材统一由资产中心管理。
            </Typography.Paragraph>
          </div>
        </header>

        <ProductFormState
          loading={businessSpacesQuery.isPending}
          error={toBusinessViewError(businessSpacesQuery.error)}
          businessSpaceOptions={businessSpaceOptions}
          onRetry={() => void businessSpacesQuery.refetch()}
          onCreateBusinessSpace={() => navigate(APP_PATHS.businessSpaceCreate)}
        >
          <ProductForm
            mode="create"
            businessSpaceOptions={businessSpaceOptions}
            submitting={createMutation.isPending}
            error={toBusinessViewError(createMutation.error)}
            onSubmit={handleSubmit}
            onCancel={() => navigate(APP_PATHS.products)}
          />
        </ProductFormState>
      </section>
    </UserShell>
  )
}

interface ProductEditViewProps {
  initialValues: ProductFormValues | null
  businessSpaceOptions: BusinessSpaceOption[]
  loading: boolean
  error: BusinessViewError | null
  submitting?: boolean
  submitError?: BusinessViewError | null
  identityAssets?: readonly ProductIdentityAssetView[]
  identityAssetsComplete?: boolean
  identityAssetManagementEnabled?: boolean
  identityAssetDisabledReason?: string
  identityAssetError?: BusinessViewError | null
  identityAssetUploads?: readonly {
    id: string
    name: string
    progressPercent: number
    errorMessage?: string
  }[]
  onSubmit: (values: ProductFormValues) => void
  onCancel: () => void
  onRetry?: () => void
  onChooseIdentityAssets?: () => void
  onUploadIdentityAssets?: (files: readonly File[]) => void
  onIdentityAssetRoleChange?: (
    assetId: string,
    role: ProductIdentityAssetRole,
  ) => void
  onMoveIdentityAsset?: (assetId: string, direction: 'UP' | 'DOWN') => void
  onUnbindIdentityAsset?: (assetId: string) => void
}

export function ProductEditView({
  initialValues,
  businessSpaceOptions,
  loading,
  error,
  submitting,
  submitError,
  identityAssets,
  identityAssetsComplete,
  identityAssetManagementEnabled,
  identityAssetDisabledReason,
  identityAssetError,
  identityAssetUploads,
  onSubmit,
  onCancel,
  onRetry,
  onChooseIdentityAssets,
  onUploadIdentityAssets,
  onIdentityAssetRoleChange,
  onMoveIdentityAsset,
  onUnbindIdentityAsset,
}: ProductEditViewProps) {
  if (loading) {
    return <PageLoading label="正在加载商品详情…" />
  }

  if (error) {
    return <ErrorState message={error.message} traceId={error.traceId} onRetry={onRetry} />
  }

  if (!initialValues) {
    return (
      <EmptyState
        title="未找到商品"
        description="该商品可能已被删除，或当前账号无权访问。"
        action={
          <Button type="primary" onClick={onCancel}>
            返回商品
          </Button>
        }
      />
    )
  }

  return (
    <ProductForm
      mode="edit"
      businessSpaceOptions={businessSpaceOptions}
      initialValues={initialValues}
      submitting={submitting}
      error={submitError}
      disabled={businessSpaceOptions.length === 0}
      identityAssets={identityAssets}
      identityAssetsComplete={identityAssetsComplete}
      identityAssetManagementEnabled={identityAssetManagementEnabled}
      identityAssetUploadEnabled
      identityAssetDisabledReason={identityAssetDisabledReason}
      identityAssetError={identityAssetError}
      identityAssetUploads={identityAssetUploads}
      onUploadIdentityAssets={onUploadIdentityAssets}
      onChooseAssets={onChooseIdentityAssets}
      onIdentityAssetRoleChange={onIdentityAssetRoleChange}
      onMoveIdentityAsset={onMoveIdentityAsset}
      onUnbindIdentityAsset={onUnbindIdentityAsset}
      onSubmit={onSubmit}
      onCancel={onCancel}
    />
  )
}

export function ProductEditPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { productId } = useParams<{ productId: string }>()
  const productQuery = useProduct(productId)
  const identityAssetsQuery = useProductIdentityAssets(productId)
  const businessSpacesQuery = useAllBusinessSpaceOptions()
  const updateMutation = useUpdateProduct()
  const replaceIdentityAssetsMutation = useReplaceProductIdentityAssets()
  const uploadImageMutation = useUploadImageAsset()
  const [identityAssetPickerOpen, setIdentityAssetPickerOpen] = useState(false)
  const [identityAssetUploads, setIdentityAssetUploads] = useState<Array<{
    id: string
    name: string
    progressPercent: number
    errorMessage?: string
  }>>([])
  const currentProduct = productQuery.data
  const identityItems = identityAssetsQuery.data?.items ?? []
  const identityAssetDetails = useAssetDetails(
    identityItems.map(({ assetId }) => assetId),
  )
  const identityAssetDownloads = useAssetDownloadUrls(
    identityItems.map(({ assetId }) => assetId),
  )
  const identityAssetViews: ProductIdentityAssetView[] = identityItems.map(
    (item, index) => ({
      assetId: item.assetId,
      name: identityAssetDetails[item.assetId]?.name ?? `商品图片 ${index + 1}`,
      role: item.role,
      order: item.sequence,
      status: 'READY',
      thumbnailUrl: identityAssetDownloads[item.assetId]?.url,
    }),
  )
  const recoveryState = location.state as CatalogCreateRecoveryState | null
  const [recoveryError, setRecoveryError] = useState(
    recoveryState?.partialCreateError ?? null,
  )
  const [recoveryAliases, setRecoveryAliases] = useState(
    recoveryState?.pendingAliases,
  )
  const businessSpaceOptions = (businessSpacesQuery.data ?? []).map(
    toBusinessSpaceOption,
  )

  const handleSubmit = (values: ProductFormValues) => {
    if (!productId || !currentProduct) {
      return
    }

    setRecoveryError(null)
    setRecoveryAliases(undefined)
    updateMutation.mutate(
      {
        productId,
        spaceId: currentProduct.spaceId,
        request: toUpdateProductRequest(values, currentProduct),
        aliases: values.aliases,
      },
      {
        onSuccess: () => navigate(APP_PATHS.products),
      },
    )
  }

  const replaceIdentityAssets = (items: readonly ProductIdentityAssetInput[]) => {
    if (!productId || !currentProduct || !identityAssetsQuery.data) return
    replaceIdentityAssetsMutation.mutate({
      productId,
      spaceId: currentProduct.spaceId,
      request: {
        version: identityAssetsQuery.data.version,
        items: items.map((item, index) => ({ ...item, sequence: index + 1 })),
      },
    })
  }

  const handleChooseIdentityAssets = (assetIds: readonly string[]) => {
    let hasIdentityImage = identityItems.some(
      ({ assetId, role }) =>
        assetIds.includes(assetId) &&
        (role === 'PRIMARY' || role === 'WHITE_BACKGROUND'),
    )
    const nextItems = assetIds.map((assetId, index) => {
      const existing = identityItems.find((item) => item.assetId === assetId)
      if (existing) return existing
      const role: ProductIdentityAssetRole = hasIdentityImage ? 'DETAIL' : 'PRIMARY'
      hasIdentityImage = true
      return { assetId, role, sequence: index + 1 }
    })
    replaceIdentityAssets(nextItems)
    setIdentityAssetPickerOpen(false)
  }

  const handleMoveIdentityAsset = (
    assetId: string,
    direction: 'UP' | 'DOWN',
  ) => {
    const index = identityItems.findIndex((item) => item.assetId === assetId)
    const targetIndex = direction === 'UP' ? index - 1 : index + 1
    if (index < 0 || targetIndex < 0 || targetIndex >= identityItems.length) return
    const nextItems = [...identityItems]
    ;[nextItems[index], nextItems[targetIndex]] = [
      nextItems[targetIndex],
      nextItems[index],
    ]
    replaceIdentityAssets(nextItems)
  }

  const handleUploadIdentityAssets = async (files: readonly File[]) => {
    if (!productId || !currentProduct || !identityAssetsQuery.data) return
    const availableSlots = Math.max(0, 20 - identityItems.length)
    const acceptedFiles = files.slice(0, availableSlots)
    const uploads = acceptedFiles.map((file) => ({
      id: crypto.randomUUID(),
      file,
    }))
    setIdentityAssetUploads((current) => [
      ...current.filter(({ errorMessage }) => Boolean(errorMessage)),
      ...uploads.map(({ id, file }) => ({
        id,
        name: file.name,
        progressPercent: 0,
      })),
    ])

    const results = await Promise.allSettled(
      uploads.map(async ({ id, file }) => {
        try {
          const asset = await uploadImageMutation.mutateAsync({
            spaceId: currentProduct.spaceId,
            file,
            name: file.name,
            idempotencyKey: crypto.randomUUID(),
            onUploadProgress: (progressPercent) =>
              setIdentityAssetUploads((current) =>
                current.map((upload) =>
                  upload.id === id ? { ...upload, progressPercent } : upload,
                ),
              ),
          })
          setIdentityAssetUploads((current) =>
            current.filter((upload) => upload.id !== id),
          )
          return asset
        } catch (error) {
          const viewError = toBusinessViewError(error)
          setIdentityAssetUploads((current) =>
            current.map((upload) =>
              upload.id === id
                ? {
                    ...upload,
                    errorMessage: viewError
                      ? errorTextWithDiagnosticTrace(viewError.message, viewError.traceId)
                      : '图片上传失败，请重试。',
                  }
                : upload,
            ),
          )
          throw error
        }
      }),
    )
    const uploadedAssets = results.flatMap((result) =>
      result.status === 'fulfilled' ? [result.value] : [],
    )
    if (uploadedAssets.length === 0) return
    let hasIdentityImage = identityItems.some(({ role }) =>
      role === 'PRIMARY' || role === 'WHITE_BACKGROUND',
    )
    const newItems: ProductIdentityAssetInput[] = uploadedAssets.map((asset) => {
      const role: ProductIdentityAssetRole = hasIdentityImage ? 'DETAIL' : 'PRIMARY'
      hasIdentityImage = true
      return { assetId: asset.id, role, sequence: 0 }
    })
    replaceIdentityAssets([...identityItems, ...newItems])
  }

  return (
    <UserShell>
      <section className="business-form-page" aria-labelledby="product-edit-title">
        <header className="business-page-heading">
          <div>
            <Button
              className="business-back-button"
              type="link"
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate(APP_PATHS.products)}
            >
              返回商品
            </Button>
            <Typography.Title id="product-edit-title" level={1}>
              编辑商品
            </Typography.Title>
            <Typography.Paragraph type="secondary">
              更新商品资料和别名；商品归属的经营空间不可在编辑时变更。
            </Typography.Paragraph>
          </div>
        </header>
        <ProductEditView
          key={
            currentProduct
              ? `${currentProduct.id}:${currentProduct.version}`
              : 'product-loading'
          }
          initialValues={
            currentProduct
              ? {
                  ...toProductFormValues(currentProduct),
                  aliases:
                    recoveryAliases ?? toProductFormValues(currentProduct).aliases,
                }
              : null
          }
          businessSpaceOptions={businessSpaceOptions}
          loading={
            (Boolean(productId) && productQuery.isPending) ||
            businessSpacesQuery.isPending
          }
          error={toBusinessViewError(
            productQuery.error ?? businessSpacesQuery.error,
          )}
          submitting={updateMutation.isPending}
          submitError={
            toBusinessViewError(updateMutation.error) ?? recoveryError
          }
          identityAssets={identityAssetViews}
          identityAssetsComplete={identityAssetsQuery.data?.creativeReady ?? false}
          identityAssetManagementEnabled={
            Boolean(identityAssetsQuery.data) &&
            !identityAssetsQuery.isPending &&
            !replaceIdentityAssetsMutation.isPending &&
            !uploadImageMutation.isPending
          }
          identityAssetDisabledReason={
            identityAssetsQuery.isPending
              ? '正在加载商品图片…'
              : identityAssetsQuery.error
                ? '商品图片暂时无法调整，请先重试。'
                : replaceIdentityAssetsMutation.isPending
                  ? '正在保存商品图片…'
                  : uploadImageMutation.isPending
                    ? '正在上传商品图片…'
                  : undefined
          }
          identityAssetError={toBusinessViewError(
            identityAssetsQuery.error ?? replaceIdentityAssetsMutation.error,
          )}
          identityAssetUploads={identityAssetUploads}
          onUploadIdentityAssets={(files) => void handleUploadIdentityAssets(files)}
          onChooseIdentityAssets={() => setIdentityAssetPickerOpen(true)}
          onIdentityAssetRoleChange={(assetId, role) =>
            replaceIdentityAssets(
              identityItems.map((item) =>
                item.assetId === assetId ? { ...item, role } : item,
              ),
            )
          }
          onMoveIdentityAsset={handleMoveIdentityAsset}
          onUnbindIdentityAsset={(assetId) =>
            replaceIdentityAssets(
              identityItems.filter((item) => item.assetId !== assetId),
            )
          }
          onSubmit={handleSubmit}
          onCancel={() => navigate(APP_PATHS.products)}
          onRetry={() => {
            void productQuery.refetch()
            void businessSpacesQuery.refetch()
          }}
        />
        {identityAssetPickerOpen && currentProduct ? (
          <ImageAssetPickerDialog
            open
            title="选择商品身份图片"
            selectedAssetIds={identityItems.map(({ assetId }) => assetId)}
            businessSpaceId={currentProduct.spaceId}
            maxSelection={20}
            onCancel={() => setIdentityAssetPickerOpen(false)}
            onConfirm={handleChooseIdentityAssets}
          />
        ) : null}
      </section>
    </UserShell>
  )
}

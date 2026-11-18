import { ArrowLeftOutlined } from '@ant-design/icons'
import { Button, Typography } from 'antd'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  BusinessSpaceForm,
  BusinessSpaceList,
  useBusinessSpace,
  useBusinessSpaces,
  useCreateBusinessSpace,
  useDeleteBusinessSpace,
  useUpdateBusinessSpace,
} from '../../features/business-data'
import type {
  BusinessSpaceFormValues,
  BusinessViewError,
} from '../../features/business-data'
import {
  EmptyState,
  ErrorState,
  PageLoading,
} from '../../components/GlobalState'
import { UserShell } from '../../layouts/UserShell'
import { APP_PATHS, appLinks } from '../../routes/paths'
import { BUSINESS_DATA_PERMISSIONS } from './pageDefaults'
import {
  toBusinessSpaceFormValues,
  toBusinessSpaceListItem,
  toBusinessSpaceSummaryView,
  toBusinessViewError,
  toCreateBusinessSpaceRequest,
  toPagination,
  toUpdateBusinessSpaceRequest,
} from './viewAdapters'

const DEFAULT_PAGE_SIZE = 20

export function BusinessSpaceListPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)
  const businessSpacesQuery = useBusinessSpaces({ page, size: pageSize })
  const deleteMutation = useDeleteBusinessSpace()
  const pageItems = businessSpacesQuery.data?.items ?? []

  const handlePageChange = (nextPage: number, nextPageSize: number) => {
    setPage(nextPageSize === pageSize ? nextPage : 1)
    setPageSize(nextPageSize)
  }

  return (
    <UserShell>
      <BusinessSpaceList
        data={pageItems.map(toBusinessSpaceListItem)}
        loading={businessSpacesQuery.isPending}
        error={toBusinessViewError(businessSpacesQuery.error)}
        deleteError={toBusinessViewError(deleteMutation.error)}
        pagination={toPagination(businessSpacesQuery.data, page, pageSize)}
        permissions={BUSINESS_DATA_PERMISSIONS}
        summary={toBusinessSpaceSummaryView(businessSpacesQuery.data)}
        onCreate={() => navigate(APP_PATHS.businessSpaceCreate)}
        onEdit={(id) => navigate(appLinks.businessSpaceEdit(id))}
        onDelete={async (id) => {
          await deleteMutation.mutateAsync(id)
          if (page > 1 && pageItems.length <= 1) {
            setPage(page - 1)
          }
        }}
        onPageChange={handlePageChange}
        onRetry={() => void businessSpacesQuery.refetch()}
      />
    </UserShell>
  )
}

export function BusinessSpaceCreatePage() {
  const navigate = useNavigate()
  const createMutation = useCreateBusinessSpace()

  const handleSubmit = (values: BusinessSpaceFormValues) => {
    createMutation.mutate(toCreateBusinessSpaceRequest(values), {
      onSuccess: (businessSpace) => {
        navigate(appLinks.businessSpaceEdit(businessSpace.id), { replace: true })
      },
    })
  }

  return (
    <UserShell>
      <section className="business-form-page" aria-labelledby="business-space-form-title">
        <header className="business-page-heading">
          <div>
            <Button
              className="business-back-button"
              type="link"
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate(APP_PATHS.businessSpaces)}
            >
              返回经营空间
            </Button>
            <Typography.Title id="business-space-form-title" level={1}>
              新建经营空间
            </Typography.Title>
            <Typography.Paragraph type="secondary">
              统一维护品牌经营背景，为商品创作与GEO监测提供准确上下文。
            </Typography.Paragraph>
          </div>
        </header>

        <BusinessSpaceForm
          mode="create"
          submitting={createMutation.isPending}
          error={toBusinessViewError(createMutation.error)}
          onSubmit={handleSubmit}
          onCancel={() => navigate(APP_PATHS.businessSpaces)}
        />
      </section>
    </UserShell>
  )
}

interface BusinessSpaceEditViewProps {
  initialValues: BusinessSpaceFormValues | null
  loading: boolean
  error: BusinessViewError | null
  submitting?: boolean
  submitError?: BusinessViewError | null
  onSubmit: (values: BusinessSpaceFormValues) => void
  onCancel: () => void
  onRetry?: () => void
}

export function BusinessSpaceEditView({
  initialValues,
  loading,
  error,
  submitting,
  submitError,
  onSubmit,
  onCancel,
  onRetry,
}: BusinessSpaceEditViewProps) {
  if (loading) {
    return <PageLoading label="正在加载经营空间详情…" />
  }

  if (error) {
    return (
      <ErrorState
        message={error.message}
        traceId={error.traceId}
        onRetry={onRetry}
      />
    )
  }

  if (!initialValues) {
    return (
      <EmptyState
        title="未找到经营空间"
        description="该经营空间可能已被删除，或当前账号无权访问。"
        action={
          <Button type="primary" onClick={onCancel}>
            返回经营空间
          </Button>
        }
      />
    )
  }

  return (
    <BusinessSpaceForm
      mode="edit"
      initialValues={initialValues}
      submitting={submitting}
      error={submitError}
      onSubmit={onSubmit}
      onCancel={onCancel}
    />
  )
}

export function BusinessSpaceEditPage() {
  const navigate = useNavigate()
  const { businessSpaceId } = useParams<{ businessSpaceId: string }>()
  const businessSpaceQuery = useBusinessSpace(businessSpaceId)
  const updateMutation = useUpdateBusinessSpace()
  const currentBusinessSpace = businessSpaceQuery.data

  const handleSubmit = (values: BusinessSpaceFormValues) => {
    if (!businessSpaceId || !currentBusinessSpace) {
      return
    }

    updateMutation.mutate(
      {
        spaceId: businessSpaceId,
        request: toUpdateBusinessSpaceRequest(values, currentBusinessSpace),
      },
      {
        onSuccess: () => navigate(APP_PATHS.businessSpaces),
      },
    )
  }

  return (
    <UserShell>
      <section className="business-form-page" aria-labelledby="business-space-edit-title">
        <header className="business-page-heading">
          <div>
            <Button
              className="business-back-button"
              type="link"
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate(APP_PATHS.businessSpaces)}
            >
              返回经营空间
            </Button>
            <Typography.Title id="business-space-edit-title" level={1}>
              编辑经营空间
            </Typography.Title>
            <Typography.Paragraph type="secondary">
              更新品牌经营背景后，将为后续商品创作和监测提供最新上下文。
            </Typography.Paragraph>
          </div>
        </header>
        <BusinessSpaceEditView
          key={
            currentBusinessSpace
              ? `${currentBusinessSpace.id}:${currentBusinessSpace.version}`
              : 'business-space-loading'
          }
          initialValues={
            currentBusinessSpace
              ? toBusinessSpaceFormValues(currentBusinessSpace)
              : null
          }
          loading={Boolean(businessSpaceId) && businessSpaceQuery.isPending}
          error={toBusinessViewError(businessSpaceQuery.error)}
          submitting={updateMutation.isPending}
          submitError={toBusinessViewError(updateMutation.error)}
          onSubmit={handleSubmit}
          onCancel={() => navigate(APP_PATHS.businessSpaces)}
          onRetry={() => void businessSpaceQuery.refetch()}
        />
      </section>
    </UserShell>
  )
}

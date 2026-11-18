import { ArrowLeftOutlined } from '@ant-design/icons'
import { Button, Typography } from 'antd'
import { useState } from 'react'
import type { ReactNode } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import {
  CompetitorForm,
  CompetitorList,
  useAllBusinessSpaceOptions,
  useCompetitor,
  useCompetitors,
  useCreateCompetitor,
  useDeleteCompetitor,
  useUpdateCompetitor,
} from '../../features/business-data'
import type {
  BusinessSpaceOption,
  BusinessViewError,
  CompetitorFormValues,
} from '../../features/business-data'
import {
  EmptyState,
  ErrorState,
  PageLoading,
} from '../../components/GlobalState'
import { UserShell } from '../../layouts/UserShell'
import { APP_PATHS, appLinks } from '../../routes/paths'
import {
  BUSINESS_DATA_PERMISSIONS,
  toCatalogCreateRecoveryState,
} from './pageDefaults'
import type { CatalogCreateRecoveryState } from './pageDefaults'
import {
  toBusinessSpaceOption,
  toBusinessViewError,
  toCompetitorFormValues,
  toCompetitorListItem,
  toCreateCompetitorRequest,
  toPagination,
  competitorPageItems,
  toUpdateCompetitorRequest,
} from './viewAdapters'

const DEFAULT_PAGE_SIZE = 20

export function CompetitorListPage() {
  const navigate = useNavigate()
  const [businessSpaceId, setBusinessSpaceId] = useState<string>()
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE)
  const businessSpacesQuery = useAllBusinessSpaceOptions()
  const competitorsQuery = useCompetitors(businessSpaceId, {
    page,
    size: pageSize,
  })
  const deleteMutation = useDeleteCompetitor()
  const businessSpaceOptions = (businessSpacesQuery.data ?? []).map(
    toBusinessSpaceOption,
  )
  const selectedBusinessSpaceName =
    businessSpaceOptions.find((option) => option.id === businessSpaceId)?.name ?? '—'
  const pageItems = competitorPageItems(competitorsQuery.data)

  const handleBusinessSpaceChange = (nextBusinessSpaceId?: string) => {
    setBusinessSpaceId(nextBusinessSpaceId)
    setPage(1)
  }

  const handlePageChange = (nextPage: number, nextPageSize: number) => {
    setPage(nextPageSize === pageSize ? nextPage : 1)
    setPageSize(nextPageSize)
  }

  return (
    <UserShell>
      <CompetitorList
        data={pageItems.map((competitor) =>
          toCompetitorListItem(competitor, selectedBusinessSpaceName),
        )}
        loading={
          businessSpacesQuery.isPending ||
          (Boolean(businessSpaceId) && competitorsQuery.isPending)
        }
        error={toBusinessViewError(
          businessSpacesQuery.error ?? competitorsQuery.error,
        )}
        deleteError={toBusinessViewError(deleteMutation.error)}
        pagination={toPagination(competitorsQuery.data, page, pageSize)}
        permissions={BUSINESS_DATA_PERMISSIONS}
        businessSpaceOptions={businessSpaceOptions}
        businessSpaceId={businessSpaceId}
        onBusinessSpaceChange={handleBusinessSpaceChange}
        onCreate={() => navigate(APP_PATHS.competitorCreate)}
        onOpenGeoWorkbench={() => navigate(`${APP_PATHS.geoCompetitors}${businessSpaceId ? `?spaceId=${encodeURIComponent(businessSpaceId)}` : ''}`)}
        onEdit={(id) => navigate(appLinks.competitorEdit(id))}
        onDelete={async (competitorId) => {
          if (!businessSpaceId) {
            return
          }
          await deleteMutation.mutateAsync({
            competitorId,
            spaceId: businessSpaceId,
          })
          if (page > 1 && pageItems.length <= 1) {
            setPage(page - 1)
          }
        }}
        onPageChange={handlePageChange}
        onRetry={() => {
          void businessSpacesQuery.refetch()
          if (businessSpaceId) {
            void competitorsQuery.refetch()
          }
        }}
      />
    </UserShell>
  )
}

function CompetitorFormState({
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
        description="竞品必须归属于一个经营空间。"
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

export function CompetitorCreatePage() {
  const navigate = useNavigate()
  const businessSpacesQuery = useAllBusinessSpaceOptions()
  const createMutation = useCreateCompetitor()
  const businessSpaceOptions = (businessSpacesQuery.data ?? []).map(
    toBusinessSpaceOption,
  )

  const handleSubmit = (values: CompetitorFormValues) => {
    createMutation.mutate(
      {
        spaceId: values.businessSpaceId,
        request: toCreateCompetitorRequest(values),
        aliases: values.aliases,
      },
      {
        onSuccess: (competitor) => {
          navigate(appLinks.competitorEdit(competitor.id), { replace: true })
        },
        onError: (error) => {
          const recoveryState = toCatalogCreateRecoveryState(
            error,
            '竞品',
            values.aliases,
          )
          if (recoveryState) {
            navigate(appLinks.competitorEdit(recoveryState.createdResourceId), {
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
      <section className="business-form-page" aria-labelledby="competitor-form-title">
        <header className="business-page-heading">
          <div>
            <Button
              className="business-back-button"
              type="link"
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate(APP_PATHS.competitors)}
            >
              返回竞品
            </Button>
            <Typography.Title id="competitor-form-title" level={1}>
              新建竞品
            </Typography.Title>
            <Typography.Paragraph type="secondary">
              按经营空间维护竞品名称、别名与关系类型。
            </Typography.Paragraph>
          </div>
        </header>

        <CompetitorFormState
          loading={businessSpacesQuery.isPending}
          error={toBusinessViewError(businessSpacesQuery.error)}
          businessSpaceOptions={businessSpaceOptions}
          onRetry={() => void businessSpacesQuery.refetch()}
          onCreateBusinessSpace={() => navigate(APP_PATHS.businessSpaceCreate)}
        >
          <CompetitorForm
            mode="create"
            businessSpaceOptions={businessSpaceOptions}
            submitting={createMutation.isPending}
            error={toBusinessViewError(createMutation.error)}
            onSubmit={handleSubmit}
            onCancel={() => navigate(APP_PATHS.competitors)}
          />
        </CompetitorFormState>
      </section>
    </UserShell>
  )
}

interface CompetitorEditViewProps {
  initialValues: CompetitorFormValues | null
  businessSpaceOptions: BusinessSpaceOption[]
  loading: boolean
  error: BusinessViewError | null
  submitting?: boolean
  submitError?: BusinessViewError | null
  onSubmit: (values: CompetitorFormValues) => void
  onCancel: () => void
  onRetry?: () => void
}

export function CompetitorEditView({
  initialValues,
  businessSpaceOptions,
  loading,
  error,
  submitting,
  submitError,
  onSubmit,
  onCancel,
  onRetry,
}: CompetitorEditViewProps) {
  if (loading) {
    return <PageLoading label="正在加载竞品详情…" />
  }

  if (error) {
    return <ErrorState message={error.message} traceId={error.traceId} onRetry={onRetry} />
  }

  if (!initialValues) {
    return (
      <EmptyState
        title="未找到竞品"
        description="该竞品可能已被删除，或当前账号无权访问。"
        action={
          <Button type="primary" onClick={onCancel}>
            返回竞品
          </Button>
        }
      />
    )
  }

  return (
    <CompetitorForm
      mode="edit"
      businessSpaceOptions={businessSpaceOptions}
      initialValues={initialValues}
      submitting={submitting}
      error={submitError}
      disabled={businessSpaceOptions.length === 0}
      onSubmit={onSubmit}
      onCancel={onCancel}
    />
  )
}

export function CompetitorEditPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { competitorId } = useParams<{ competitorId: string }>()
  const competitorQuery = useCompetitor(competitorId)
  const businessSpacesQuery = useAllBusinessSpaceOptions()
  const updateMutation = useUpdateCompetitor()
  const currentCompetitor = competitorQuery.data
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

  const handleSubmit = (values: CompetitorFormValues) => {
    if (!competitorId || !currentCompetitor) {
      return
    }

    setRecoveryError(null)
    setRecoveryAliases(undefined)
    updateMutation.mutate(
      {
        competitorId,
        spaceId: currentCompetitor.spaceId,
        request: toUpdateCompetitorRequest(values, currentCompetitor),
        aliases: values.aliases,
      },
      {
        onSuccess: () => navigate(APP_PATHS.competitors),
      },
    )
  }

  return (
    <UserShell>
      <section className="business-form-page" aria-labelledby="competitor-edit-title">
        <header className="business-page-heading">
          <div>
            <Button
              className="business-back-button"
              type="link"
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate(APP_PATHS.competitors)}
            >
              返回竞品
            </Button>
            <Typography.Title id="competitor-edit-title" level={1}>
              编辑竞品
            </Typography.Title>
            <Typography.Paragraph type="secondary">
              更新竞品资料和别名；竞品归属的经营空间不可在编辑时变更。
            </Typography.Paragraph>
          </div>
        </header>
        <CompetitorEditView
          key={
            currentCompetitor
              ? `${currentCompetitor.id}:${currentCompetitor.version}`
              : 'competitor-loading'
          }
          initialValues={
            currentCompetitor
              ? {
                  ...toCompetitorFormValues(currentCompetitor),
                  aliases:
                    recoveryAliases ??
                    toCompetitorFormValues(currentCompetitor).aliases,
                }
              : null
          }
          businessSpaceOptions={businessSpaceOptions}
          loading={
            (Boolean(competitorId) && competitorQuery.isPending) ||
            businessSpacesQuery.isPending
          }
          error={toBusinessViewError(
            competitorQuery.error ?? businessSpacesQuery.error,
          )}
          submitting={updateMutation.isPending}
          submitError={
            toBusinessViewError(updateMutation.error) ?? recoveryError
          }
          onSubmit={handleSubmit}
          onCancel={() => navigate(APP_PATHS.competitors)}
          onRetry={() => {
            void competitorQuery.refetch()
            void businessSpacesQuery.refetch()
          }}
        />
      </section>
    </UserShell>
  )
}

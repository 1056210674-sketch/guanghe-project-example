import { App as AntdApp, Alert, Tabs, Typography } from 'antd'
import { useState } from 'react'
import { isApiError } from '../../../../api/apiError'
import type { CreatePricingVersionRequest } from '../../../../api/adminBillingApi'
import type { AdminErrorView } from '../../format'
import {
  useCreatePricingVersionMutation,
  usePricingOwnerId,
  usePricingVersions,
  usePublishPricingVersionMutation,
  useRateLimitRules,
  useRefreshRateLimits,
  useRetirePricingVersionMutation,
  useUpdatePricingVersionMutation,
  useUpdateRateLimitMutation,
} from '../live/pricingQueries'
import {
  GIFT_VERSION_LIST_SIZE,
  PRICING_VERSION_PAGE_SIZE,
  rateLimitActionLabel,
  toPricingVersionPageView,
  toRateLimitRuleView,
} from '../types'
import type {
  PricingVersionView,
  RateLimitAction,
  RateLimitRuleDraft,
  RateLimitRuleView,
} from '../types'
import { AdminRateLimitWorkspace } from './AdminRateLimitWorkspace'
import { AdminPricingVersionsWorkspace } from './AdminPricingVersionsWorkspace'
import type {
  AdminPricingVersionsWorkspaceProps,
  PricingPendingAction,
  PricingVersionFilter,
} from './AdminPricingVersionsWorkspace'

type PricingConsoleTab = 'versions' | 'gift-tiers' | 'rate-limits'

const RATE_LIMIT_CONFLICT_NOTICE =
  '本次保存被服务端拒绝，列表已按服务端最新状态重新加载，请比对后再保存。'

function toErrorView(error: unknown): AdminErrorView {
  if (isApiError(error)) {
    return { message: error.message, traceId: error.traceId, code: error.code }
  }
  return { message: '请求失败，请稍后重试。' }
}

/**
 * The 定价配置 console.
 *
 * It owns every request: the three tabs are rendered over workspaces that only receive view
 * models, flags and callbacks. Nothing here invents a value the server did not return — a failure
 * carries the server's own message and traceId, and a success message is only shown after the
 * response that proves it has arrived.
 */
export function AdminPricingConsole() {
  const ownerId = usePricingOwnerId()
  const { message } = AntdApp.useApp()
  const [tab, setTab] = useState<PricingConsoleTab>('versions')
  const [statusFilter, setStatusFilter] = useState<PricingVersionFilter>('ALL')
  const [page, setPage] = useState(1)
  const [selectedGiftVersionId, setSelectedGiftVersionId] = useState<string>()
  const [pendingAction, setPendingAction] = useState<PricingPendingAction>()
  const [rateLimitPending, setRateLimitPending] = useState<RateLimitAction>()
  const [versionActionError, setVersionActionError] = useState<AdminErrorView | null>(null)
  const [versionFormError, setVersionFormError] = useState<AdminErrorView | null>(null)
  const [rateLimitFormError, setRateLimitFormError] = useState<AdminErrorView | null>(null)
  const [rateLimitNotice, setRateLimitNotice] = useState<string | null>(null)

  const versionsQuery = usePricingVersions(ownerId, {
    status: statusFilter === 'ALL' ? undefined : statusFilter,
    page,
    size: PRICING_VERSION_PAGE_SIZE,
  })
  const giftVersionsQuery = usePricingVersions(ownerId, {
    page: 1,
    size: GIFT_VERSION_LIST_SIZE,
  })
  const rateLimitsQuery = useRateLimitRules(ownerId)

  const createVersionMutation = useCreatePricingVersionMutation(ownerId)
  const updateVersionMutation = useUpdatePricingVersionMutation(ownerId)
  const publishVersionMutation = usePublishPricingVersionMutation(ownerId)
  const retireVersionMutation = useRetirePricingVersionMutation(ownerId)
  const updateRateLimitMutation = useUpdateRateLimitMutation(ownerId)
  const refreshRateLimits = useRefreshRateLimits(ownerId)

  const versionPage = versionsQuery.data ? toPricingVersionPageView(versionsQuery.data) : undefined
  const giftVersionPage = giftVersionsQuery.data
    ? toPricingVersionPageView(giftVersionsQuery.data)
    : undefined
  const giftVersions = (giftVersionPage?.items ?? []).filter(
    (version) => version.status !== 'RETIRED',
  )
  const activeGiftVersion = giftVersions.find((version) => version.status === 'ACTIVE')
  const selectedGiftVersion =
    giftVersions.find((version) => version.id === selectedGiftVersionId) ??
    activeGiftVersion ??
    giftVersions[0]
  const rules: readonly RateLimitRuleView[] = (rateLimitsQuery.data ?? []).map(toRateLimitRuleView)

  /** The loaded state of a version, used as `expectedVersion` so a stale edit is refused. */
  const knownVersions = new Map<string, PricingVersionView>()
  for (const version of versionPage?.items ?? []) knownVersions.set(version.id, version)
  for (const version of giftVersions) knownVersions.set(version.id, version)

  const createVersion = async (request: CreatePricingVersionRequest): Promise<boolean> => {
    if (pendingAction) return false
    setVersionFormError(null)
    setPendingAction({ kind: 'create' })
    try {
      const created = await createVersionMutation.mutateAsync(request)
      message.success(`已创建草稿 v${created.versionNo}，发布前不会影响任何付费页面。`)
      return true
    } catch (error) {
      setVersionFormError(toErrorView(error))
      return false
    } finally {
      setPendingAction(undefined)
    }
  }

  const updateVersion = async (
    versionId: string,
    request: CreatePricingVersionRequest,
  ): Promise<boolean> => {
    if (pendingAction) return false
    const loaded = knownVersions.get(versionId)
    if (!loaded) {
      setVersionFormError({
        message: '未找到该版本的最新数据，请先刷新列表后再编辑，避免用过期版本号提交。',
      })
      return false
    }
    setVersionFormError(null)
    setPendingAction({ kind: 'update', versionId })
    try {
      const updated = await updateVersionMutation.mutateAsync({
        versionId,
        expectedVersion: loaded.versionNo,
        request,
      })
      message.success(`草稿 v${updated.versionNo} 已保存。`)
      return true
    } catch (error) {
      setVersionFormError(toErrorView(error))
      return false
    } finally {
      setPendingAction(undefined)
    }
  }

  const publishVersion = async (versionId: string) => {
    if (pendingAction) return
    setVersionActionError(null)
    setPendingAction({ kind: 'publish', versionId })
    try {
      const published = await publishVersionMutation.mutateAsync(versionId)
      message.success(`v${published.versionNo} 已发布并生效。`)
    } catch (error) {
      setVersionActionError(toErrorView(error))
    } finally {
      setPendingAction(undefined)
    }
  }

  const retireVersion = async (versionId: string) => {
    if (pendingAction) return
    setVersionActionError(null)
    setPendingAction({ kind: 'retire', versionId })
    try {
      const retired = await retireVersionMutation.mutateAsync(versionId)
      message.success(`v${retired.versionNo} 已停用。`)
    } catch (error) {
      setVersionActionError(toErrorView(error))
    } finally {
      setPendingAction(undefined)
    }
  }

  const saveRateLimit = async (
    action: RateLimitAction,
    draft: RateLimitRuleDraft,
  ): Promise<boolean> => {
    if (rateLimitPending) return false
    const loaded = rules.find((rule) => rule.action === action)
    if (!loaded) {
      setRateLimitFormError({
        message: '未加载到该动作的最新规则，请先重新加载列表后再保存。',
      })
      return false
    }
    setRateLimitFormError(null)
    setRateLimitNotice(null)
    setRateLimitPending(action)
    try {
      const updated = await updateRateLimitMutation.mutateAsync({
        action,
        expectedVersion: loaded.version,
        request: draft,
      })
      message.success(`${rateLimitActionLabel(updated.action)} 的频率限制已保存。`)
      return true
    } catch (error) {
      setRateLimitFormError(toErrorView(error))
      setRateLimitNotice(RATE_LIMIT_CONFLICT_NOTICE)
      await refreshRateLimits()
      return false
    } finally {
      setRateLimitPending(undefined)
    }
  }

  const sharedVersionProps: Omit<AdminPricingVersionsWorkspaceProps, 'mode'> = {
    versions: versionPage?.items ?? [],
    total: versionPage?.total ?? 0,
    page: versionPage?.page ?? page,
    pageSize: versionPage?.size ?? PRICING_VERSION_PAGE_SIZE,
    statusFilter,
    listLoading: versionsQuery.isLoading,
    listError: versionsQuery.error ? toErrorView(versionsQuery.error) : null,
    giftVersions,
    giftVersionsTruncated: Boolean(giftVersionPage?.hasNext),
    giftVersionsLoading: giftVersionsQuery.isLoading,
    giftVersionsError: giftVersionsQuery.error ? toErrorView(giftVersionsQuery.error) : null,
    selectedGiftVersionId: selectedGiftVersion?.id,
    giftVersion: selectedGiftVersion,
    actionError: versionActionError,
    formError: versionFormError,
    pendingAction,
    onStatusFilterChange: (filter) => {
      setStatusFilter(filter)
      setPage(1)
    },
    onPageChange: setPage,
    onSelectGiftVersion: setSelectedGiftVersionId,
    onCreate: createVersion,
    onUpdate: updateVersion,
    onPublish: (versionId) => void publishVersion(versionId),
    onRetire: (versionId) => void retireVersion(versionId),
    onRetryList: () => void versionsQuery.refetch(),
    onRetryGiftVersions: () => void giftVersionsQuery.refetch(),
  }

  return (
    <section className="admin-workspace" aria-labelledby="admin-pricing-title">
      <header className="admin-workspace__heading">
        <div>
          <Typography.Title id="admin-pricing-title" level={1}>
            定价配置
          </Typography.Title>
          <Typography.Text type="secondary">
            价格版本、充值档位的赠送积分与三类动作的频率限制
          </Typography.Text>
        </div>
      </header>

      {ownerId ? null : (
        <Alert
          type="warning"
          showIcon
          title="当前没有已登录的管理员会话，未发起任何请求"
          description="开发预览路由以匿名身份打开，因此这里只展示空态；定价数据一律以服务端返回为准，不会用本地样例填充。"
        />
      )}

      <Tabs
        activeKey={tab}
        onChange={(key) => setTab(key as PricingConsoleTab)}
        items={[
          {
            key: 'versions',
            label: '定价版本',
            children: <AdminPricingVersionsWorkspace mode="versions" {...sharedVersionProps} />,
          },
          {
            key: 'gift-tiers',
            label: '赠送积分',
            children: <AdminPricingVersionsWorkspace mode="gift-tiers" {...sharedVersionProps} />,
          },
          {
            key: 'rate-limits',
            label: '频率限制',
            children: (
              <AdminRateLimitWorkspace
                rules={rules}
                loading={rateLimitsQuery.isLoading}
                error={rateLimitsQuery.error ? toErrorView(rateLimitsQuery.error) : null}
                notice={rateLimitNotice}
                formError={rateLimitFormError}
                pendingAction={rateLimitPending}
                onSave={saveRateLimit}
                onRetry={() => void rateLimitsQuery.refetch()}
              />
            ),
          },
        ]}
      />
    </section>
  )
}

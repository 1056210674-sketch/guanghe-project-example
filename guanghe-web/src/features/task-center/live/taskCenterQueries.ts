import { useQueries, useQuery } from '@tanstack/react-query'
import { getBusinessSpace, getProduct } from '../../../api/catalogApi'
import {
  getPaidTask,
  listPaidTaskEvents,
  listPaidTasks,
} from '../../../api/conversationApi'
import type { PaidTaskListQuery } from '../../../api/conversationApi'
import { useAuthStore } from '../../../stores/authStore'
import { catalogKeys } from '../../business-data/catalogQueries'
import {
  toTaskCenterListItem,
  toTaskCenterTaskDetail,
} from './taskCenterAdapters'

const ACTIVE_TASK_STATUSES = new Set(['QUEUED', 'RUNNING', 'RETRY_WAIT'])
const CATALOG_NAME_STALE_TIME = 5 * 60_000

function refreshDelay(failureCount: number): number {
  return Math.min(5_000 * (2 ** Math.max(0, failureCount)), 30_000)
}

function uniqueIds(values: readonly (string | null | undefined)[]): string[] {
  return [...new Set(values.filter((value): value is string => Boolean(value)))]
}

function useTaskCenterCatalogNames(
  tasks: Readonly<Awaited<ReturnType<typeof listPaidTasks>>['items']>,
  ownerId?: string,
) {
  const keys = catalogKeys.forOwner(ownerId)
  const spaceIds = uniqueIds(tasks.map((task) => task.spaceId))
  const productIds = uniqueIds(tasks.map((task) => task.productId))

  // Name lookups are deliberately best-effort. A task list must remain usable
  // when an old/deleted catalog record cannot be resolved; the adapter falls
  // back to the stable ID in that case.
  const spaceQueries = useQueries({
    queries: spaceIds.map((spaceId) => ({
      queryKey: keys.businessSpaceDetail(spaceId),
      queryFn: async () => {
        try {
          return await getBusinessSpace(spaceId)
        } catch {
          return null
        }
      },
      staleTime: CATALOG_NAME_STALE_TIME,
      retry: false,
    })),
  })
  const productQueries = useQueries({
    queries: productIds.map((productId) => ({
      queryKey: keys.productDetail(productId),
      queryFn: async () => {
        try {
          return await getProduct(productId)
        } catch {
          return null
        }
      },
      staleTime: CATALOG_NAME_STALE_TIME,
      retry: false,
    })),
  })

  return {
    businessSpaceNames: new Map(
      spaceIds.map((spaceId, index) => [spaceId, spaceQueries[index]?.data?.name]),
    ),
    productNames: new Map(
      productIds.map((productId, index) => [productId, productQueries[index]?.data?.name]),
    ),
  }
}

async function loadTaskCenterDetail(taskId: string) {
  const [task, events] = await Promise.all([
    getPaidTask(taskId),
    listPaidTaskEvents(taskId),
  ])
  const [businessSpace, product] = await Promise.all([
    getBusinessSpace(task.spaceId).catch(() => undefined),
    task.productId
      ? getProduct(task.productId).catch(() => undefined)
      : Promise.resolve(undefined),
  ])

  return toTaskCenterTaskDetail(
    task,
    {
      businessSpaceName: businessSpace?.name,
      productName: product?.name,
    },
    events,
  )
}

export function useTaskCenterList(query: PaidTaskListQuery) {
  const ownerId = useAuthStore((state) => state.user?.id)
  const taskPageQuery = useQuery({
    queryKey: ['task-center', ownerId ?? null, 'list', query],
    queryFn: () => listPaidTasks(query),
    refetchInterval: (result) =>
      result.state.data?.items.some((task) => ACTIVE_TASK_STATUSES.has(task.status))
        ? refreshDelay(result.state.fetchFailureCount)
        : false,
    refetchIntervalInBackground: false,
  })

  const catalogNames = useTaskCenterCatalogNames(
    taskPageQuery.data?.items ?? [],
    ownerId,
  )
  const data = taskPageQuery.data
    ? {
        ...taskPageQuery.data,
        items: taskPageQuery.data.items.map((task) =>
          toTaskCenterListItem(task, {
            businessSpaceName: catalogNames.businessSpaceNames.get(task.spaceId),
            productName: task.productId
              ? catalogNames.productNames.get(task.productId)
              : undefined,
          }),
        ),
      }
    : undefined

  return { ...taskPageQuery, data }
}

export function useTaskCenterDetail(taskId?: string) {
  const ownerId = useAuthStore((state) => state.user?.id)
  return useQuery({
    queryKey: ['task-center', ownerId ?? null, 'detail', taskId ?? null],
    queryFn: () => loadTaskCenterDetail(taskId as string),
    enabled: Boolean(taskId),
    refetchInterval: (result) =>
      result.state.data && ACTIVE_TASK_STATUSES.has(result.state.data.status)
        ? refreshDelay(result.state.fetchFailureCount)
        : false,
    refetchIntervalInBackground: false,
  })
}

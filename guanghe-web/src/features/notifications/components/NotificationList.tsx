import { Alert, Empty, Pagination, Typography } from 'antd'
import { ErrorState, PageLoading } from '../../../components/GlobalState'
import type {
  NotificationListItemView,
  NotificationViewError,
} from '../live/notificationAdapters'

/**
 * Presentational only: it renders the view models the container hands it and reports clicks back.
 * Selecting a row is not the same act as marking it read, so there is no read action here.
 */
export interface NotificationListProps {
  items: readonly NotificationListItemView[]
  selectedId?: string
  loading: boolean
  error: NotificationViewError | null
  pendingId?: string
  /** Set when the in-app channel is off: an explicit notice replaces the list. */
  channelNotice?: string
  emptyMessage?: string
  total: number
  page: number
  size: number
  onSelect: (notificationId: string) => void
  onPageChange: (page: number, size: number) => void
  onRetry: () => void
}

export function NotificationList({
  items,
  selectedId,
  loading,
  error,
  pendingId,
  channelNotice,
  emptyMessage = '暂无通知',
  total,
  page,
  size,
  onSelect,
  onPageChange,
  onRetry,
}: NotificationListProps) {
  return (
    <aside className="notification-list" aria-label="通知列表">
      {channelNotice ? (
        <Alert
          type="warning"
          showIcon
          title={channelNotice}
          description="暂时无法获取通知，请稍后刷新查看。"
          action={<button type="button" className="link-button" onClick={onRetry}>重试</button>}
        />
      ) : null}

      {channelNotice ? null : loading ? (
        <PageLoading label="正在加载通知…" />
      ) : error ? (
        <ErrorState message={error.message} traceId={error.traceId} onRetry={onRetry} />
      ) : items.length === 0 ? (
        <Empty description={emptyMessage} />
      ) : (
        <ul>
          {items.map((item) => (
            <li key={item.id}>
              <button
                type="button"
                className={`notification-item ${item.read ? '' : 'notification-item--unread'}`}
                aria-pressed={item.id === selectedId}
                aria-label={`${item.title}，${item.readLabel}`}
                onClick={() => onSelect(item.id)}
              >
                <span className="notification-item__content">
                  <span className="notification-item__heading">
                    <span className="notification-item__title">{item.title}</span>
                    {item.read ? null : <span className="notification-unread-dot" aria-hidden="true" />}
                  </span>
                  <span className="notification-item__meta">
                    <span>{item.categoryLabel}</span>
                    <span aria-hidden="true">·</span>
                    <span>{item.createdAt}</span>
                  </span>
                  {item.id === pendingId ? (
                    <Typography.Text type="secondary">正在标记已读…</Typography.Text>
                  ) : null}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {channelNotice || loading || error || total <= size ? null : (
        <footer className="notification-list__end">
          <span>共 {total} 条</span>
          <Pagination
            current={page}
            pageSize={size}
            total={total}
            showSizeChanger={false}
            onChange={onPageChange}
          />
        </footer>
      )}
    </aside>
  )
}

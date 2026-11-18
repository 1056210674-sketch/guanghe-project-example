import { ArrowRightOutlined } from '@ant-design/icons'
import { Button, Empty } from 'antd'
import { Link } from 'react-router-dom'
import type { NotificationDetailView } from '../live/notificationAdapters'

/**
 * Presentational only. `body` is user-facing text and is rendered as text — never as HTML.
 * `targetType`/`targetId` remain plain data; a link is rendered only when the adapter has resolved
 * the pair through the application's local resource-kind allowlist.
 */
export interface NotificationDetailProps {
  notification: NotificationDetailView | null
  markingRead: boolean
  onMarkRead: (notificationId: string) => void
}

export function NotificationDetail({
  notification,
  markingRead,
  onMarkRead,
}: NotificationDetailProps) {
  if (!notification) {
    return (
      <div className="notification-detail-container">
        <Empty description="请选择一条通知查看详情" />
      </div>
    )
  }

  return (
    <div className="notification-detail-container">
      <article className="notification-detail" aria-label="通知详情">
        <header className="notification-detail__heading">
          <div className="notification-detail__heading-copy">
            <div className="notification-detail__meta">
              <span>{notification.categoryLabel}</span>
              <span aria-hidden="true">·</span>
              <span>{notification.createdAt}</span>
            </div>
            <h2>{notification.title}</h2>
          </div>
          <span className={`notification-detail__status${notification.read ? '' : ' notification-detail__status--unread'}`}>
            {notification.readLabel}
          </span>
        </header>

        <p className="notification-detail__body">{notification.body}</p>

        <div className="notification-detail__actions">
          {notification.targetLink ? (
            <Link className="notification-detail__target" to={notification.targetLink.path}>
              {notification.targetLink.label}<ArrowRightOutlined aria-hidden="true" />
            </Link>
          ) : null}
          {notification.read ? (
            <span className="notification-detail__read-time">
              {notification.readAt ? `已读于 ${notification.readAt}` : '已读'}
            </span>
          ) : (
            <Button
              loading={markingRead}
              onClick={() => onMarkRead(notification.id)}
            >
              标为已读
            </Button>
          )}
        </div>

        {notification.targetId && !notification.targetLink ? (
          <p className="notification-muted">相关记录暂无法从这里打开，请到对应功能页查询。</p>
        ) : null}
      </article>
    </div>
  )
}

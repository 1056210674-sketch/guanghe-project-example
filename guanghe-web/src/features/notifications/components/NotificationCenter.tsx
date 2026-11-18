import { CheckCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, Badge, Button, Checkbox, Space, Tabs, Typography } from 'antd'
import { useMemo, useState } from 'react'
import type { NotificationListQuery } from '../../../api/billingApi'
import { useAuthStore } from '../../../stores/authStore'
import { diagnosticTraceDescription } from '../../../shared/tracePresentation'
import {
  notificationTabs,
  toNotificationCapabilityView,
  toNotificationDetail,
  toNotificationViewError,
  toUnreadCount,
  type NotificationTabKey,
} from '../live/notificationAdapters'
import {
  NOTIFICATION_PAGE_SIZE,
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotificationCapability,
  useNotifications,
  useUnreadNotificationCount,
} from '../live/notificationQueries'
import { NotificationDetail } from './NotificationDetail'
import { NotificationList } from './NotificationList'
import '../../../styles/notifications.css'

export function NotificationCenter() {
  const ownerId = useAuthStore((state) => state.user?.role === 'USER' ? state.user.id : undefined)
  const [category, setCategory] = useState<NotificationTabKey>('ALL')
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(NOTIFICATION_PAGE_SIZE)
  const [selectedId, setSelectedId] = useState<string>()

  const query = useMemo<NotificationListQuery>(() => ({
    page, size, category: category === 'ALL' ? undefined : category, unreadOnly: unreadOnly || undefined,
  }), [page, size, category, unreadOnly])

  const notifications = useNotifications(query)
  const unreadCountQuery = useUnreadNotificationCount()
  const capabilityQuery = useNotificationCapability()
  const markRead = useMarkNotificationRead(query)
  const markAllRead = useMarkAllNotificationsRead()

  const details = useMemo(
    () => (notifications.data?.items ?? []).map(toNotificationDetail),
    [notifications.data],
  )
  const listIsEmpty = Boolean(notifications.data) && details.length === 0
  const selected = notifications.isError || notifications.isPending
    ? null : details.find((item) => item.id === selectedId) ?? null

  const capability = capabilityQuery.data
    ? toNotificationCapabilityView(capabilityQuery.data)
    : undefined
  const unreadCount = unreadCountQuery.data
    ? toUnreadCount(unreadCountQuery.data)
    : undefined
  const listError = notifications.error
    ? toNotificationViewError(notifications.error)
    : null
  const actionErrorSource = markRead.error ?? markAllRead.error
  const actionError = actionErrorSource ? toNotificationViewError(actionErrorSource) : null
  const markAllSwept = markAllRead.data ? toUnreadCount(markAllRead.data) : undefined

  const unreadLabel =
    unreadCount === undefined ? '未读通知数量暂不可用' : `未读通知 ${unreadCount} 条`

  if (!ownerId) {
    return (
      <section className="notification-workspace notification-center" aria-label="通知中心">
        <Typography.Title level={1}>通知中心</Typography.Title>
        <Alert
          type="info"
          showIcon
          title="登录后可查看通知"
          description="当前未登录，不会发起任何通知请求。"
        />
      </section>
    )
  }

  return (
    <section className="notification-workspace notification-center" aria-label="通知中心">
      <header className="notification-center__heading">
        <Typography.Title level={1}>通知中心</Typography.Title>
        <span className="notification-unread" role="status" aria-label={unreadLabel}>
          {unreadCount === undefined ? (
            <Typography.Text type="secondary">未读：暂不可用</Typography.Text>
          ) : (
            <>
              <Badge count={unreadCount} showZero />
              <Typography.Text>未读</Typography.Text>
            </>
          )}
        </span>
      </header>

      <div className="notification-tabs">
        <Tabs
          activeKey={category}
          onChange={(key) => {
            setCategory(key as NotificationTabKey)
            setPage(1)
            setSelectedId(undefined)
            markRead.reset()
            markAllRead.reset()
          }}
          items={notificationTabs.map((tab) => ({ key: tab.key, label: tab.label }))}
        />
        <Button
          icon={<CheckCircleOutlined />}
          loading={markAllRead.isPending}
          disabled={unreadCount === undefined || unreadCount === 0 || capability?.inApp === false || markRead.isPending}
          onClick={() => {
            markRead.reset()
            markAllRead.mutate(undefined, { onSuccess: () => {
              if (unreadOnly) { setPage(1); setSelectedId(undefined) }
            } })
          }}
        >
          全部标为已读
        </Button>
      </div>

      <div className="notification-center__toolbar">
        <Checkbox checked={unreadOnly} onChange={(event) => {
          setUnreadOnly(event.target.checked)
          setPage(1)
          setSelectedId(undefined)
          markRead.reset()
          markAllRead.reset()
        }}>只看未读</Checkbox>
        <Space>
          <Typography.Text type="secondary">通知自动更新，不影响正在执行的任务</Typography.Text>
          <Button icon={<ReloadOutlined />} loading={notifications.isFetching} onClick={() => {
            void notifications.refetch()
            void unreadCountQuery.refetch()
            void capabilityQuery.refetch()
          }}>刷新通知</Button>
        </Space>
      </div>

      {unreadCountQuery.isError ? <Alert type="warning" showIcon title="未读数量更新失败，请刷新重试。" /> : null}
      {capabilityQuery.isError ? <Alert type="warning" showIcon title="通知通道状态暂时无法获取" action={<Button onClick={() => void capabilityQuery.refetch()}>重试</Button>} /> : null}
      {capability?.inApp && (!capability.mailConfigured || capability.mailStatus !== 'MAIL_DELIVERY_READY') ? (
        <Typography.Paragraph type="secondary">当前通过站内通知接收消息，暂不发送邮件。</Typography.Paragraph>
      ) : null}

      {actionError ? (
        <Alert
          type="error"
          showIcon
          title={actionError.message}
          description={diagnosticTraceDescription(actionError.traceId)}
        />
      ) : null}

      {markAllRead.isSuccess && markAllSwept !== undefined ? (
        <Alert
          type="success"
          showIcon
          title={markAllSwept === 0 ? '已标记全部通知为已读' : `已标记全部通知为已读，另有 ${markAllSwept} 条新通知。`}
        />
      ) : null}

      <div className={`notification-columns${listIsEmpty ? ' notification-columns--empty' : ''}`}>
        <NotificationList
          items={details}
          selectedId={selectedId}
          loading={notifications.isPending}
          error={listError}
          pendingId={markRead.isPending ? markRead.variables : undefined}
          channelNotice={
            capability && !capability.inApp
              ? '站内通知暂不可用，请稍后重试'
              : undefined
          }
          emptyMessage={unreadOnly ? '暂无未读通知' : category === 'ALL' ? '暂无通知' : '暂无此类通知'}
          total={notifications.data?.total ?? 0}
          page={notifications.data?.page ?? page}
          size={notifications.data?.size ?? size}
          onSelect={setSelectedId}
          onPageChange={(nextPage, nextSize) => {
            setPage(nextSize === size ? nextPage : 1)
            setSize(nextSize)
            setSelectedId(undefined)
          }}
          onRetry={() => {
            void notifications.refetch()
            void unreadCountQuery.refetch()
            void capabilityQuery.refetch()
          }}
        />
        {listIsEmpty ? null : <NotificationDetail
          notification={capability?.inApp === false ? null : selected}
          markingRead={Boolean(markRead.isPending && markRead.variables === selected?.id)}
          onMarkRead={(notificationId) => {
            markAllRead.reset()
            markRead.mutate(notificationId, { onSuccess: () => {
              if (unreadOnly) { setPage(1); setSelectedId(undefined) }
            } })
          }}
        />}
      </div>
    </section>
  )
}

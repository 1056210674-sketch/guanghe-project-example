import { useEffect, useState } from 'react'
import { Alert, Button, Empty, Tabs, Tag, Typography } from 'antd'
import { BellOutlined, CheckCircleFilled, CheckCircleOutlined, ClockCircleOutlined, CloseCircleFilled, FileImageOutlined, FileTextOutlined, PlaySquareOutlined, RadarChartOutlined, StarFilled } from '@ant-design/icons'
import { notificationCategories, notificationPoints, notificationPrice, type NotificationView } from './types'
import '../../styles/notifications.css'

const emptyNotifications: readonly NotificationView[] = []
const icons = { IMAGE: <FileImageOutlined />, GEO: <RadarChartOutlined />, VIDEO: <PlaySquareOutlined />, ARTICLE: <FileTextOutlined />, POINTS: <StarFilled />, INVOICE: <FileTextOutlined />, SYSTEM: <BellOutlined /> }
function NotificationDetail({ notification }: { notification: NotificationView }) {
  const item = notification
  return <article className="notification-detail" aria-label="通知详情">
    <header className="notification-detail__heading"><span className={`notification-icon notification-icon--${item.kind.toLowerCase()}`}>{icons[item.kind]}</span><div><h2>{item.title}</h2><p>通知时间：{item.createdAt}</p></div>{item.status && <Tag>{item.status}</Tag>}</header>
    <p className="notification-detail__body">{item.body}</p>
    {(item.product || item.execution) && <div className="notification-detail__context">
      {item.product && <section><h3>关联商品</h3><div className="notification-product"><span className="notification-product__placeholder"><FileImageOutlined />图片未接入</span><div><strong>{item.product.name}</strong><p>ID：{item.product.id}</p><p>价格：{notificationPrice(item.product.priceCents)}</p><Button type="link" disabled>查看商品详情</Button></div></div></section>}
      {item.execution && <section><h3>执行时间</h3><p>{item.execution.startedAt}</p><p>{item.execution.durationLabel}</p></section>}
    </div>}
    {item.platforms && <section className="notification-platforms"><h3>各平台执行结果（{item.platforms.length}）</h3>{item.platforms.length ? <ul>{item.platforms.map(platform => <li key={platform.id}><span className="notification-platforms__name">{platform.name}</span><span className={`notification-outcome notification-outcome--${platform.outcome.toLowerCase()}`}>{platform.outcome === 'SUCCESS' ? <CheckCircleFilled /> : platform.outcome === 'FAILED' ? <CloseCircleFilled /> : <ClockCircleOutlined />}{platform.status}</span></li>)}</ul> : <Empty description="暂无平台执行结果" />}</section>}
    {item.settlement && <section className="notification-settlement"><div><strong>{item.settlement.title}</strong><p>{item.settlement.summary}</p></div><strong>{notificationPoints(item.settlement.pointsDelta)}</strong></section>}
    <div className="notification-detail__actions">{item.related && <Button type="primary" disabled>{item.related.label}</Button>}<Button aria-label={item.unread ? '标为已读' : '已读'} disabled>{item.unread ? '标为已读' : '已读'}</Button></div>
    <p className="notification-muted">已读及关联详情接口尚未接入。查看通知不会更改已读状态，任务和账务结果以服务端为准。</p>
  </article>
}
export function NotificationWorkspace({ notifications = emptyNotifications, preview = false }: { notifications?: readonly NotificationView[]; preview?: boolean }) {
  const [category, setCategory] = useState('ALL')
  const [selectedId, setSelectedId] = useState<string>()
  const rows = notifications.filter(item => category === 'ALL' || item.category === category)
  const selected = rows.find(item => item.id === selectedId)
  useEffect(() => {
    if (!selectedId || selected) return
    let cancelled = false
    queueMicrotask(() => {
      if (!cancelled) setSelectedId(undefined)
    })
    return () => {
      cancelled = true
    }
  }, [selected, selectedId])
  return <section className="notification-workspace" aria-label="通知中心">
    <Typography.Title level={1}>通知中心</Typography.Title>
    <div className="notification-tabs"><Tabs activeKey={category} onChange={value => { setCategory(value); setSelectedId(undefined) }} items={[{ key: 'ALL', label: '全部' }, ...Object.entries(notificationCategories).map(([key, label]) => ({ key, label }))]} /><Button type="text" icon={<CheckCircleOutlined />} disabled>全部标为已读</Button></div>
    <Alert showIcon type="info" title={preview ? '开发设计预览 · 通知样例仅用于界面验收' : '通知接口尚未接入'} description="当前不获取真实通知，不修改已读状态；未接入的操作保持禁用。" />
    <div className="notification-columns"><aside className="notification-list" aria-label="通知列表">
      {rows.length ? <><ul>{rows.map(item => <li key={item.id}><button type="button" className={`notification-item ${item.unread ? 'notification-item--unread' : ''}`} aria-pressed={item.id === selectedId} aria-label={`${item.title}，${item.unread ? '未读' : '已读'}`} onClick={() => setSelectedId(item.id)}>
        <span className={`notification-icon notification-icon--${item.kind.toLowerCase()}`}>{icons[item.kind]}</span><span className="notification-item__content"><span className="notification-item__title">{item.title}</span><span className="notification-item__time">{item.createdAt}{item.unread && <span className="notification-unread-dot" aria-hidden="true" />}</span><span className="notification-item__summary">{item.summary}</span><span className="notification-item__more">查看通知 ›</span></span>
      </button></li>)}</ul><p className="notification-muted notification-list__end">当前已加载 {rows.length} 条通知</p></> : <Empty description={notifications.length ? '暂无此类通知' : '暂无通知'} />}
    </aside><div className="notification-detail-container">{selected ? <NotificationDetail notification={selected} /> : <Empty description={rows.length ? '请选择一条通知查看详情' : '暂无通知详情'} />}</div></div>
  </section>
}

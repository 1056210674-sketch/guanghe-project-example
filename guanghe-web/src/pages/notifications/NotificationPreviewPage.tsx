import { NotificationWorkspace } from '../../features/notifications/NotificationWorkspace'
import type { NotificationView } from '../../features/notifications/types'
import { UserShell } from '../../layouts/UserShell'

// DEV-only, explicitly labelled design examples. Not backend notification data.
const notifications: NotificationView[] = [
  { id: 'PREVIEW-NOTIFICATION-IMAGE', category: 'TASK', kind: 'IMAGE', title: '商品主图已生成（样例）', summary: '开发样例：展示图片任务完成的通知样式。', body: '这是图片任务完成通知的开发设计样例，不代表真实生成结果。', createdAt: '2026-09-07 10:02', unread: true, related: { type: 'ASSET', id: 'preview-image', label: '查看图片' } },
  { id: 'PREVIEW-NOTIFICATION-GEO', category: 'TASK', kind: 'GEO', title: 'GEO 监测任务部分完成（样例）', summary: '开发样例：七个平台执行结果与结算摘要展示。', body: '开发设计样例：便携咖啡机 GEO 监测任务部分完成。以下平台状态和积分摘要仅用于视觉验收，不代表真实执行或结算。', createdAt: '2026-09-07 09:42', unread: true, status: '部分完成（样例）', product: { name: '便携咖啡机（样例）', id: 'preview-product-001', priceCents: 19900 }, execution: { startedAt: '2026-09-07 09:30', durationLabel: '用时 12 分钟（样例）' }, platforms: ['豆包', 'DeepSeek', '通义千问', '文心一言', 'Kimi', '腾讯元宝', '智谱'].map((name, index) => ({ id: `preview-platform-${index}`, name, status: index === 5 ? '暂时失败' : '成功', outcome: index === 5 ? 'FAILED' : 'SUCCESS' })), settlement: { title: '积分结算信息（开发样例）', summary: '仅展示结算摘要样式，不产生实际积分变更。', pointsDelta: 120 }, related: { type: 'TASK', id: 'preview-geo-task', label: '查看任务详情' } },
  { id: 'PREVIEW-NOTIFICATION-VIDEO', category: 'TASK', kind: 'VIDEO', title: '视频生成失败（样例）', summary: '开发样例：失败通知和积分处理提示。', body: '视频任务失败展示样例，具体错误和积分处理须由服务端确认。', createdAt: '2026-09-07 09:20', unread: true },
  { id: 'preview-notification-submission', category: 'SUBMISSION', kind: 'ARTICLE', title: '稿件提交状态更新（样例）', summary: '开发样例：媒体投稿状态通知。', body: '投稿状态通知展示样例，未执行真实投稿。', createdAt: '2026-09-07 09:00', unread: false },
  { id: 'preview-notification-points', category: 'POINTS', kind: 'POINTS', title: '积分账户通知（样例）', summary: '开发样例：账户积分变更通知。', body: '积分通知展示样例，不代表充值、扣款或到账。', createdAt: '2026-09-06 10:24', unread: false, related: { type: 'BILLING', id: 'preview-ledger', label: '查看账单' } },
  { id: 'preview-notification-invoice', category: 'INVOICE', kind: 'INVOICE', title: '发票处理状态更新（样例）', summary: '开发样例：发票通知。', body: '发票通知展示样例，不代表实际开票或邮件已发送。', createdAt: '2026-09-05 16:40', unread: false },
  { id: 'preview-notification-system', category: 'SYSTEM', kind: 'SYSTEM', title: '系统公告（样例）', summary: '开发样例：系统消息展示。', body: '这是一条开发设计预览消息，不代表真实维护安排。', createdAt: '2026-09-05 09:00', unread: false },
]
export function NotificationPreviewPage() { return <UserShell><NotificationWorkspace preview notifications={notifications} /></UserShell> }

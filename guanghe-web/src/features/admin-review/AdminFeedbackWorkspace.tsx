import { useState } from 'react'
import { Alert, Button, Card, Empty, Tabs, Tag, Typography } from 'antd'
import { CheckCircleOutlined, ClockCircleOutlined, FileImageOutlined, MessageOutlined, SmileOutlined } from '@ant-design/icons'
import { displayCount, displayPercent, feedbackFingerprint, feedbackStatuses, type FeedbackAggregateView, type FeedbackStatus, type FeedbackView } from './types'
import { ReviewDetail, ReviewFilterBar, ReviewHistory, ReviewMetadata } from './shared'
import { emptyReviewFilters, matchesReviewFilters, useReviewTarget } from './state'

const emptyTickets: readonly FeedbackView[] = []
const emptyAggregates: readonly FeedbackAggregateView[] = []
const colors = { NEW: 'blue', PROCESSING: 'orange', RESOLVED: 'success' }
export function AdminFeedbackWorkspace({ tickets = emptyTickets, aggregates = emptyAggregates, summary, preview = false }: {
  tickets?: readonly FeedbackView[]; aggregates?: readonly FeedbackAggregateView[]
  summary?: Partial<Record<FeedbackStatus, number>> & { satisfaction7d?: number }; preview?: boolean
}) {
  const [tab, setTab] = useState('tickets')
  const [filters, setFilters] = useState(emptyReviewFilters)
  const detail = useReviewTarget(tickets, feedbackFingerprint)
  const ticket = detail.record
  const rows = tickets.filter(item => matchesReviewFilters(item, filters) && (filters.type === 'ALL' || item.business === filters.type) && (filters.problem === 'ALL' || item.problemType === filters.problem))
  const cards = [{ label: '新增反馈', value: displayCount(summary?.NEW), icon: <MessageOutlined /> }, { label: '处理中', value: displayCount(summary?.PROCESSING), icon: <ClockCircleOutlined /> }, { label: '已解决', value: displayCount(summary?.RESOLVED), icon: <CheckCircleOutlined /> }, { label: '近 7 日满意率', value: displayPercent(summary?.satisfaction7d), icon: <SmileOutlined /> }]
  return <section className="admin-workspace admin-review" aria-label="用户反馈">
    <div className="admin-workspace__heading"><div><Typography.Title level={1}>用户反馈</Typography.Title><Typography.Text type="secondary">跟踪用户问题与客服沟通记录</Typography.Text></div><div className="admin-review-actions"><Button disabled>导出反馈</Button><Button disabled>刷新数据</Button></div></div>
    <Alert showIcon type="info" title={preview ? '开发设计预览 · 反馈样例仅用于界面验收' : '用户反馈接口尚未接入'} description="当前不提交回复、不改变工单状态、不发送通知，也不会自动加入评测。" />
    {detail.conflict && <Alert type="warning" showIcon title="反馈记录已更新，请重新打开详情" description="旧详情和本地处理备注已清空。" />}
    <div className="admin-stat-grid">{cards.map(card => <Card key={card.label}>{card.icon}<span>{card.label}</span><strong>{card.value}</strong></Card>)}</div>
    <Card className="admin-data-card"><Tabs activeKey={tab} onChange={value => { setTab(value); detail.close(); setFilters(emptyReviewFilters) }} items={[{ key: 'tickets', label: '反馈工单' }, { key: 'analysis', label: '聚合分析' }]} />
      {tab === 'tickets' && <><ReviewFilterBar filters={filters} onChange={setFilters} disabled={!tickets.length} types={[...new Set(tickets.map(item => item.business))]} statuses={Object.entries(feedbackStatuses).map(([value, label]) => ({ value, label }))} problems={[...new Set(tickets.map(item => item.problemType))]} />
        {filters.start && filters.end && filters.start > filters.end && <Alert type="error" showIcon title="开始日期不能晚于结束日期" />}
        {rows.length ? <div className="admin-table-scroll"><table className="admin-data-table admin-review-records" aria-label="反馈工单列表"><thead><tr>{['反馈号', '脱敏用户', '业务类型', '用户描述摘要', '关联任务', '创建时间', '状态', '负责人', '操作'].map(label => <th key={label}>{label}</th>)}</tr></thead><tbody>{rows.map(item => <tr key={item.id}><td><strong>{item.id}</strong></td><td>{item.account}</td><td><Tag>{item.business}</Tag></td><td className="admin-review-text">{item.description}</td><td>{item.taskId}</td><td>{item.createdAt}</td><td><Tag color={colors[item.status]}>{feedbackStatuses[item.status]}</Tag></td><td>{item.assignee || '未分配'}</td><td><Button type="link" onClick={() => detail.open(item)}>查看反馈</Button></td></tr>)}</tbody></table></div> : <Empty description={tickets.length ? '没有符合条件的反馈' : '暂无反馈工单'} />}
        <div className="admin-review-note">当前已加载 {rows.length} 条反馈 · 用户信息及描述应由服务端二次脱敏后提供</div>
      </>}
      {tab === 'analysis' && (aggregates.length ? <div className="admin-table-scroll"><table className="admin-data-table" aria-label="反馈聚合分析"><thead><tr><th>业务类型</th><th>问题类型</th><th>反馈数量</th><th>已解决数量</th></tr></thead><tbody>{aggregates.map(item => <tr key={item.id}><td>{item.business}</td><td>{item.problemType}</td><td>{displayCount(item.count)}</td><td>{displayCount(item.resolved)}</td></tr>)}</tbody></table></div> : <Empty description="暂无反馈聚合数据" />)}
    </Card>
    <Alert type="info" showIcon title="反馈与用户行为仅供问题分析" description="下载、保存、采纳行为不等同于质量结论。系统不会自动标注质量或修改 Prompt。" />
    {ticket && <ReviewDetail key={detail.fingerprint} title="反馈详情" noteLabel="回复记录备注" editable={ticket.status !== 'RESOLVED'} actions={['标记处理中', '保存回复记录', '标记解决']} onClose={detail.close}>
      <section><div className="admin-review-actions"><strong>{ticket.id}</strong><Tag color={colors[ticket.status]}>{feedbackStatuses[ticket.status]}</Tag></div><h3>用户原话（脱敏摘要）</h3><blockquote>{ticket.description}</blockquote><p className="admin-review-muted">仅展示服务端提供的脱敏摘要，不展示原始敏感信息。</p><h3>附件（用户上传）</h3>{ticket.attachments.length ? <div className="admin-review-attachments">{ticket.attachments.map(file => <div key={file.id}><FileImageOutlined /><span>{file.name}</span><Button disabled>预览未接入</Button></div>)}</div> : <p>暂无附件</p>}</section>
      <section><h3>关联信息</h3><ReviewMetadata rows={[
        ['脱敏用户', ticket.account], ['业务类型', ticket.business], ['关联任务', ticket.taskId], ['关联 Run', ticket.runId], ['任务状态', ticket.taskStatus], ['Provider', ticket.provider], ['Prompt/Profile 哈希', ticket.profileHash], ['错误分类', ticket.problemType], ['负责人', ticket.assignee], ['创建时间', ticket.createdAt],
      ]} /></section>
      <section><h3>沟通记录</h3><ReviewHistory events={ticket.events} /></section>
    </ReviewDetail>}
  </section>
}

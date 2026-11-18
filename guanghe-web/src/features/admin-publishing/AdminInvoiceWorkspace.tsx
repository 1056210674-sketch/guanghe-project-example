import { useState } from 'react'
import { Alert, Button, Card, Drawer, Empty, Input, Modal, Radio, Select, Tag, Timeline, Typography } from 'antd'
import { ClockCircleOutlined, CloseCircleOutlined, FileDoneOutlined, FileTextOutlined } from '@ant-design/icons'
import { formatCents } from '../admin-finance/types'
import { displayCount, invoiceFingerprint, type InvoiceApplicationView, type InvoiceSummaryView } from './types'

const statuses = { PENDING: '待处理', PROCESSING: '处理中', COMPLETED: '已完成', REJECTED: '驳回' }
const colors = { PENDING: 'orange', PROCESSING: 'processing', COMPLETED: 'success', REJECTED: 'error' }
const emptyApplications: readonly InvoiceApplicationView[] = []
const emptyFilters = { id: '', account: '', status: 'ALL', start: '', end: '' }

function InvoiceDetail({ invoice, onClose }: { invoice: InvoiceApplicationView; onClose: () => void }) {
  const [number, setNumber] = useState(invoice.invoiceNumber ?? '')
  const [note, setNote] = useState(invoice.processingNote ?? '')
  const [delivery, setDelivery] = useState(invoice.deliveryResult ?? 'NOT_SENT')
  const [discard, setDiscard] = useState(false)
  const editable = invoice.status === 'PENDING' || invoice.status === 'PROCESSING'
  const dirty = number !== (invoice.invoiceNumber ?? '') || note !== (invoice.processingNote ?? '') || delivery !== (invoice.deliveryResult ?? 'NOT_SENT')
  return <><Drawer title="发票申请详情" size={480} open onClose={() => dirty ? setDiscard(true) : onClose()}>
    <div className="admin-detail-stack admin-invoice-detail">
      <Card title="基本信息"><dl className="admin-detail-list">{[
        ['申请号', invoice.id], ['状态', statuses[invoice.status]], ['申请时间', invoice.createdAt],
        ['脱敏账号', invoice.account], ['抬头', invoice.title], ['税号（脱敏）', invoice.taxIdMasked],
        ['联系电话（脱敏）', invoice.phoneMasked ?? '—'], ['收票邮箱（脱敏）', invoice.emailMasked],
        ['申请备注', invoice.note ?? '—'], ['关联充值订单', invoice.orderId], ['开票金额', formatCents(invoice.amountCents)],
      ].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl></Card>
      <Card title="处理流程">{invoice.events.length ? <Timeline items={invoice.events.map(event => ({ title: event.title, content: <><p>{event.time}</p>{event.detail && <p>{event.detail}</p>}</> }))} /> : <Empty description="暂无处理记录" />}</Card>
      <Card title="处理信息"><div className="admin-modal-form"><Alert showIcon type="info" title={editable ? '仅本地填写，尚未保存或变更申请状态。' : '此申请已结束，处理信息只读。'} />
        <label>发票号码<Input aria-label="发票号码" value={number} onChange={event => setNumber(event.target.value)} maxLength={50} disabled={!editable} placeholder="填写税务系统生成的发票号码" /></label>
        <label>处理备注<Input.TextArea aria-label="发票处理备注" value={note} onChange={event => setNote(event.target.value)} maxLength={500} rows={3} disabled={!editable} placeholder="内部备注，仅管理员可见（选填）" /></label>
        <fieldset className="admin-invoice-delivery"><legend>发送结果</legend><Radio.Group aria-label="发票发送结果" value={delivery} onChange={event => setDelivery(event.target.value)} disabled={!editable} options={[{ label: '发送成功', value: 'SENT' }, { label: '发送失败', value: 'FAILED' }, { label: '未发送', value: 'NOT_SENT' }]} /><Typography.Text type="secondary">仅在管理员完成实际发送后记录结果；当前选择不会发送邮件。</Typography.Text></fieldset>
      </div></Card>
      <div className="admin-publishing-actions"><Button danger disabled>驳回申请</Button><Button type="primary" disabled>标记处理中</Button><Button type="primary" disabled>标记已完成</Button></div>
      <Alert showIcon type="warning" title="处理接口尚未接入" description="第一版由管理员从外部邮箱发送发票，系统不代发附件。状态更新须由服务端重新核验权限、订单资格、金额与幂等性，并保留审计日志。" />
    </div>
  </Drawer>{discard && <Modal title="放弃发票本地填写？" open onCancel={() => setDiscard(false)} onOk={onClose} okText="放弃填写" cancelText="继续填写"><p>发票号码、处理备注和发送结果尚未保存，放弃后将清空本次修改。</p></Modal>}</>
}

export function AdminInvoiceWorkspace({ applications = emptyApplications, summary, preview = false }: {
  applications?: readonly InvoiceApplicationView[]
  summary?: InvoiceSummaryView
  preview?: boolean
}) {
  const [filters, setFilters] = useState(emptyFilters)
  const [applied, setApplied] = useState(emptyFilters)
  const [target, setTarget] = useState<{ id: string; fingerprint: string }>()
  const [conflict, setConflict] = useState(false)
  const invoice = applications.find(item => item.id === target?.id)
  const fingerprint = invoice ? invoiceFingerprint(invoice) : undefined
  // Do not carry processing notes or delivery selections across a refreshed
  // application. Equivalent Props preserve the form without reference checks.
  if (target && target.fingerprint !== fingerprint) {
    setTarget(undefined)
    setConflict(true)
  }
  const invalidDates = Boolean(filters.start && filters.end && filters.start > filters.end)
  const rows = applications.filter(item => item.id.toLowerCase().includes(applied.id.trim().toLowerCase()) &&
    item.account.toLowerCase().includes(applied.account.trim().toLowerCase()) && (applied.status === 'ALL' || item.status === applied.status) &&
    (!applied.start || item.createdAt.slice(0, 10) >= applied.start) && (!applied.end || item.createdAt.slice(0, 10) <= applied.end))
  const icons = { PENDING: <FileTextOutlined />, PROCESSING: <ClockCircleOutlined />, COMPLETED: <FileDoneOutlined />, REJECTED: <CloseCircleOutlined /> }
  const reset = () => { setFilters(emptyFilters); setApplied(emptyFilters) }
  return <section className="admin-workspace admin-publishing" aria-label="发票管理">
    <div className="admin-workspace__heading"><div><Typography.Title level={1}>发票管理</Typography.Title><Typography.Text type="secondary">核对充值开票申请，跟踪人工处理与发送记录</Typography.Text></div><Tag>管理员专用</Tag></div>
    <Alert showIcon type="info" title={preview ? '开发设计预览 · 发票样例仅用于界面验收' : '发票管理接口尚未接入'} description="当前不执行开票、驳回或发送邮件；全部处理按钮保持禁用。" />
    {conflict && <Alert showIcon type="warning" title="申请数据已更新，请重新打开详情" description="旧处理表单已清空，未保存的内容不会应用到新申请。" />}
    <div className="admin-stat-grid">{(Object.keys(statuses) as (keyof typeof statuses)[]).map(key => <Card key={key}>{icons[key]}<span>{statuses[key]}</span><strong>{displayCount(summary?.[key])}</strong></Card>)}</div>
    <Card className="admin-data-card"><form className="admin-invoice-filters" onSubmit={event => { event.preventDefault(); if (!invalidDates) setApplied({ ...filters }) }}>
      <label>申请号<Input aria-label="搜索发票申请号" value={filters.id} onChange={event => setFilters({ ...filters, id: event.target.value })} disabled={!applications.length} placeholder="请输入申请号" /></label>
      <label>账号<Input aria-label="搜索发票账号" value={filters.account} onChange={event => setFilters({ ...filters, account: event.target.value })} disabled={!applications.length} placeholder="请输入脱敏账号" /></label>
      <label>状态<Select aria-label="发票状态" value={filters.status} onChange={status => setFilters({ ...filters, status })} disabled={!applications.length} options={[{ label: '全部状态', value: 'ALL' }, ...Object.entries(statuses).map(([value, label]) => ({ value, label }))]} /></label>
      <label>开始日期<Input aria-label="发票开始日期" type="date" value={filters.start} onChange={event => setFilters({ ...filters, start: event.target.value })} disabled={!applications.length} /></label>
      <label>结束日期<Input aria-label="发票结束日期" type="date" value={filters.end} onChange={event => setFilters({ ...filters, end: event.target.value })} disabled={!applications.length} /></label>
      <div className="admin-publishing-actions"><Button aria-label="重置" onClick={reset} disabled={!applications.length}>重置</Button><Button aria-label="查询" type="primary" htmlType="submit" disabled={!applications.length || invalidDates}>查询</Button></div>
    </form>{invalidDates && <Alert showIcon type="error" title="开始日期不能晚于结束日期" />}
      {rows.length ? <div className="admin-table-scroll"><table className="admin-data-table" aria-label="发票申请列表"><thead><tr>{['申请号', '脱敏用户', '关联充值订单', '开票金额', '抬头', '税号（脱敏）', '收票邮箱（脱敏）', '申请时间', '状态', '操作'].map(label => <th key={label}>{label}</th>)}</tr></thead><tbody>{rows.map(item => <tr key={item.id}><td><strong>{item.id}</strong></td><td>{item.account}</td><td>{item.orderId}</td><td>{formatCents(item.amountCents)}</td><td className="admin-publishing-title">{item.title}</td><td>{item.taxIdMasked}</td><td>{item.emailMasked}</td><td>{item.createdAt}</td><td><Tag color={colors[item.status]}>{statuses[item.status]}</Tag></td><td><Button type="link" onClick={() => { setTarget({ id: item.id, fingerprint: invoiceFingerprint(item) }); setConflict(false) }}>查看申请</Button></td></tr>)}</tbody></table></div> : <Empty description={applications.length ? '没有符合条件的发票申请' : '暂无发票申请'} />}
      <div className="admin-publishing-note">当前已加载 {rows.length} 条申请 · 订单资格、开票金额和最终状态以服务端为准。</div>
    </Card>
    {invoice && target && target.fingerprint === fingerprint && <InvoiceDetail key={target.fingerprint} invoice={invoice} onClose={() => setTarget(undefined)} />}
  </section>
}

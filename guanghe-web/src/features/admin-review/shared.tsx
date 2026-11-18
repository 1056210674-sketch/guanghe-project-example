import { useId, useState, type ReactNode } from 'react'
import { Alert, Button, Drawer, Empty, Input, Modal, Select, Timeline } from 'antd'
import type { ReviewEventView } from './types'
import { emptyReviewFilters, type ReviewFilters } from './state'

export function ReviewDetail({ title, children, noteLabel, editable, actions, onClose }: {
  title: string; children: ReactNode; noteLabel: string; editable: boolean; actions: readonly string[]; onClose: () => void
}) {
  const titleId = useId()
  const [note, setNote] = useState('')
  const [discard, setDiscard] = useState(false)
  return <><Drawer aria-labelledby={titleId} title={<span id={titleId}>{title}</span>} size={480} open onClose={() => note ? setDiscard(true) : onClose()}>
    <div className="admin-detail-stack admin-review-detail">{children}
      <section><h3>本地处理备注</h3><label>{noteLabel}<Input.TextArea aria-label={noteLabel} value={note} onChange={event => setNote(event.target.value)} rows={3} maxLength={500} showCount disabled={!editable} placeholder="仅本地填写，关闭并放弃后清空；不会提交或持久保存" /></label></section>
      <div className="admin-review-actions">{actions.map(action => <Button key={action} disabled danger={action.includes('删除') || action.includes('拒绝')}>{action}</Button>)}</div>
      <Alert showIcon type="warning" title="处理接口尚未接入" description="当前不会变更状态、发送通知或调整积分。服务端必须重新核验权限、当前记录和操作资格，保证幂等并保留审计日志；处置需填写原因。" />
    </div>
  </Drawer>{discard && <Modal title="放弃本地处理备注？" open onCancel={() => setDiscard(false)} onOk={onClose} okText="确认放弃" cancelText="继续填写"><p>备注尚未保存，确认放弃后将清空，不会提交到服务端。</p></Modal>}</>
}

export function ReviewMetadata({ rows }: { rows: readonly (readonly [string, string | undefined])[] }) {
  return <dl className="admin-detail-list">{rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value || '—'}</dd></div>)}</dl>
}
export function ReviewHistory({ events }: { events: readonly ReviewEventView[] }) {
  return events.length ? <Timeline items={events.map(event => ({ key: event.id, title: `${event.actor} · ${event.time}`, content: event.summary }))} /> : <Empty description="暂无处理记录" />
}
export function ReviewFilterBar({ filters, onChange, disabled, types, statuses, problems }: {
  filters: ReviewFilters; onChange: (filters: ReviewFilters) => void; disabled: boolean
  types: readonly string[]; statuses: readonly { value: string; label: string }[]; problems?: readonly string[]
}) {
  const filterId = useId()
  return <div className="admin-review-filters">
    <label>搜索<Input aria-label="搜索记录" value={filters.search} disabled={disabled} placeholder="记录号 / 脱敏用户 / 关联任务" onChange={event => onChange({ ...filters, search: event.target.value })} allowClear /></label>
    <label>{problems ? '业务类型' : '内容类型'}<Select id={`${filterId}-type`} aria-label={problems ? '业务类型' : '内容类型'} value={filters.type} disabled={disabled} onChange={type => onChange({ ...filters, type })} options={[{ label: '全部类型', value: 'ALL' }, ...types.map(value => ({ label: value, value }))]} /></label>
    <label>处理状态<Select id={`${filterId}-status`} aria-label="处理状态" value={filters.status} disabled={disabled} onChange={status => onChange({ ...filters, status })} options={[{ label: '全部状态', value: 'ALL' }, ...statuses]} /></label>
    {problems && <label>问题类型<Select id={`${filterId}-problem`} aria-label="问题类型" value={filters.problem} disabled={disabled} onChange={problem => onChange({ ...filters, problem })} options={[{ label: '全部问题', value: 'ALL' }, ...problems.map(value => ({ label: value, value }))]} /></label>}
    <label>开始日期<Input aria-label="开始日期" type="date" value={filters.start} disabled={disabled} onChange={event => onChange({ ...filters, start: event.target.value })} /></label>
    <label>结束日期<Input aria-label="结束日期" type="date" value={filters.end} disabled={disabled} onChange={event => onChange({ ...filters, end: event.target.value })} /></label>
    <Button aria-label="重置筛选" disabled={disabled} onClick={() => onChange(emptyReviewFilters)}>重置</Button>
  </div>
}

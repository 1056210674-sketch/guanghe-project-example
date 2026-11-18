import { useId, useState } from 'react'
import { Alert, Button, Card, Empty, Input, Pagination, Select, Tag, Timeline, Typography } from 'antd'
import { auditResults, redactedChangesJson, type AuditLogView } from './types'
import { Metadata, ReadOnlyDetail } from './ReadOnlyDetail'

const emptyLogs: readonly AuditLogView[] = []
const defaults = { administrator: 'ALL', action: 'ALL', objectType: 'ALL', result: 'ALL', trace: '', start: '', end: '' }
const resultColors = { SUCCESS: 'success', FAILED: 'error', PARTIAL: 'orange' }
function AuditDetail({ log, onClose }: { log: AuditLogView; onClose: () => void }) {
  const [json, setJson] = useState(false)
  return <ReadOnlyDetail title="日志详情" onClose={onClose}>
    <div className="admin-observability-actions"><Tag color={resultColors[log.result]}>{auditResults[log.result]}</Tag><strong>{log.action}</strong><span>{log.occurredAt}</span></div>
    <section><h3>基本信息</h3><Metadata rows={[
      ['日志号', log.id], ['管理员', log.administrator], ['对象类型', log.objectType], ['对象编号', log.objectId], ['traceId', log.traceId], ['IP（脱敏）', log.ipMasked],
    ]} /></section>
    <section><div className="admin-observability-actions"><h3>变更摘要（操作前后）</h3><Button disabled={!log.changes.length} onClick={() => setJson(!json)}>{json ? '收起摘要 JSON' : '查看摘要 JSON'}</Button></div>
      <p>{log.summary}</p>{log.changes.length ? <div className="admin-observability-detail-scroll"><table className="admin-observability-small-table" aria-label="操作前后变更"><thead><tr><th>字段</th><th>操作前</th><th>操作后</th></tr></thead><tbody>{log.changes.map((change, index) => <tr key={`${index}-${change.field}`}><th scope="row">{change.field}</th><td>{change.before || '—'}</td><td>{change.after || '—'}</td></tr>)}</tbody></table></div> : <Empty description="暂无变更摘要" />}
      {json && <pre aria-label="脱敏变更 JSON">{redactedChangesJson(log)}</pre>}
    </section>
    <section><h3>操作原因</h3><p>{log.reason || '暂无操作原因'}</p></section>
    <section><h3>二次确认</h3>{log.confirmation ? <Metadata rows={[
      ['确认人', log.confirmation.administrator], ['确认时间', log.confirmation.time], ['结果', log.confirmation.result],
    ]} /> : <Empty description="暂无二次确认记录" />}</section>
    <section><h3>关联信息</h3><Metadata rows={[
      ['关联任务', log.taskId], ['关联订单', log.orderId], ['关联用户（脱敏）', log.userIdMasked],
    ]} /><p className="admin-observability-muted">关联详情接口尚未接入，仅展示标识，不跳转至无权限或未知地址。</p></section>
    <section><h3>时间线</h3>{log.events.length ? <Timeline items={log.events.map(event => ({ key: event.id, title: event.time, content: event.summary }))} /> : <Empty description="暂无事件记录" />}</section>
    <Alert showIcon type="info" title="日志只读，不支持编辑或删除" description="仅展示服务端提供的脱敏摘要；不展示密码、Token、API Key、完整 Prompt 或完整业务正文。" />
  </ReadOnlyDetail>
}

export function AdminAuditWorkspace({ logs = emptyLogs, preview = false }: { logs?: readonly AuditLogView[]; preview?: boolean }) {
  const filterId = useId()
  const [filters, setFilters] = useState(defaults)
  const [applied, setApplied] = useState(defaults)
  const [page, setPage] = useState(1)
  const [selectedId, setSelectedId] = useState<string>()
  const log = logs.find(item => item.id === selectedId)
  if (selectedId && !log) setSelectedId(undefined)
  const invalidDates = Boolean(filters.start && filters.end && filters.start > filters.end)
  const rows = logs.filter(item => (applied.administrator === 'ALL' || item.administrator === applied.administrator) &&
    (applied.action === 'ALL' || item.action === applied.action) && (applied.objectType === 'ALL' || item.objectType === applied.objectType) &&
    (applied.result === 'ALL' || item.result === applied.result) && item.traceId.toLowerCase().includes(applied.trace.trim().toLowerCase()) &&
    (!applied.start || item.occurredAt.slice(0, 10) >= applied.start) && (!applied.end || item.occurredAt.slice(0, 10) <= applied.end))
  const currentPage = Math.min(page, Math.max(1, Math.ceil(rows.length / 10)))
  if (page !== currentPage) setPage(currentPage)
  return <section className="admin-workspace admin-observability" aria-label="操作日志">
    <div className="admin-workspace__heading"><div><Typography.Title level={1}>操作日志</Typography.Title><Typography.Text type="secondary">记录管理员在平台内的关键操作行为，日志只读，不支持编辑或删除。</Typography.Text></div><Tag>只读审计</Tag></div>
    <Alert showIcon type="info" title={preview ? '开发设计预览 · 操作日志样例仅用于界面验收' : '操作日志接口尚未接入'} description="日志内容须由服务端脱敏并鉴权返回；导出功能未接入，不产生本地模拟审计记录。" />
    <Card className="admin-data-card"><form className="admin-observability-filters" onSubmit={event => { event.preventDefault(); if (!invalidDates) { setApplied({ ...filters }); setPage(1) } }}>
      {(['administrator', 'action', 'objectType'] as const).map((key, index) => <label key={key}>{['管理员', '操作类型', '业务对象'][index]}<Select id={`${filterId}-${key}`} aria-label={['管理员', '操作类型', '业务对象'][index]} value={filters[key]} disabled={!logs.length} onChange={value => setFilters({ ...filters, [key]: value })} options={[{ value: 'ALL', label: '全部' }, ...[...new Set(logs.map(item => item[key]))].map(value => ({ value, label: value }))]} /></label>)}
      <label>结果<Select id={`${filterId}-result`} aria-label="操作结果" value={filters.result} disabled={!logs.length} onChange={result => setFilters({ ...filters, result })} options={[{ value: 'ALL', label: '全部结果' }, ...Object.entries(auditResults).map(([value, label]) => ({ value, label }))]} /></label>
      <label>开始日期<Input aria-label="日志开始日期" type="date" value={filters.start} disabled={!logs.length} onChange={event => setFilters({ ...filters, start: event.target.value })} /></label>
      <label>结束日期<Input aria-label="日志结束日期" type="date" value={filters.end} disabled={!logs.length} onChange={event => setFilters({ ...filters, end: event.target.value })} /></label>
      <label>traceId<Input aria-label="搜索 traceId" value={filters.trace} disabled={!logs.length} onChange={event => setFilters({ ...filters, trace: event.target.value })} placeholder="输入 traceId，支持模糊查询" /></label>
      <div className="admin-observability-actions"><Button aria-label="查询日志" htmlType="submit" type="primary" disabled={!logs.length || invalidDates}>查询</Button><Button aria-label="重置日志筛选" disabled={!logs.length} onClick={() => { setFilters(defaults); setApplied(defaults); setPage(1) }}>重置</Button><Button disabled>导出日志</Button></div>
    </form>{invalidDates && <Alert showIcon type="error" title="开始日期不能晚于结束日期" />}<p className="admin-observability-note">仅记录必要元数据与脱敏摘要，不记录密码、Token、API Key、完整 Prompt 和完整业务正文。</p></Card>
    <Card className="admin-data-card">{rows.length ? <div className="admin-table-scroll"><table className="admin-data-table admin-observability-records" aria-label="操作日志列表"><thead><tr>{['日志号', '管理员', '操作类型', '对象类型', '对象编号', '变更摘要', '结果', 'IP 脱敏', '发生时间', '详情'].map(label => <th key={label}>{label}</th>)}</tr></thead><tbody>{rows.slice((currentPage - 1) * 10, currentPage * 10).map(item => <tr key={item.id}><td>{item.id}</td><td>{item.administrator}</td><td>{item.action}</td><td>{item.objectType}</td><td>{item.objectId || '—'}</td><td className="admin-observability-text">{item.summary}</td><td><Tag color={resultColors[item.result]}>{auditResults[item.result]}</Tag></td><td>{item.ipMasked}</td><td>{item.occurredAt}</td><td><Button type="link" onClick={() => setSelectedId(item.id)}>查看日志</Button></td></tr>)}</tbody></table></div> : <Empty description={logs.length ? '没有符合条件的操作日志' : '暂无操作日志'} />}
      <div className="admin-observability-footer"><span>当前已加载 {rows.length} 条符合条件的日志</span><Pagination size="small" current={currentPage} pageSize={10} total={rows.length} showSizeChanger={false} hideOnSinglePage onChange={setPage} /></div>
    </Card>
    {log && <AuditDetail key={JSON.stringify([log.id, redactedChangesJson(log)])} log={log} onClose={() => setSelectedId(undefined)} />}
  </section>
}

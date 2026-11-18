import { useState } from 'react'
import { Alert, Button, Card, Drawer, Empty, Input, InputNumber, Modal, Select, Tabs, Tag, Timeline, Typography } from 'antd'
import { ledgerFingerprint, type CompensationConfirmation } from './fingerprints'
import { DollarOutlined } from '@ant-design/icons'
import { formatCents, formatPoints, type LedgerRowView, type LedgerSummaryView } from './types'

const statuses = { SUCCESS: '成功', PENDING: '处理中', EXCEPTION: '异常' }
const emptyRows: readonly LedgerRowView[] = []
export function AdminLedgerWorkspace({ entries = emptyRows, orders = emptyRows, settlements = emptyRows, summary, preview = false }: {
  entries?: readonly LedgerRowView[]
  orders?: readonly LedgerRowView[]
  settlements?: readonly LedgerRowView[]
  summary?: LedgerSummaryView
  preview?: boolean
}) {
  const [tab,setTab] = useState('ledger')
  const [query,setQuery] = useState('')
  const [business,setBusiness] = useState('ALL')
  const [direction,setDirection] = useState('ALL')
  const [start,setStart] = useState('')
  const [end,setEnd] = useState('')
  const [selectedId,setSelectedId] = useState<string>()
  const [compensate,setCompensate] = useState(false)
  const [points,setPoints] = useState<number | null>(null)
  const [reason,setReason] = useState('')
  const [confirmation,setConfirmation] = useState<CompensationConfirmation>()
  const [targetFingerprint,setTargetFingerprint] = useState<string>()
  const [targetTab,setTargetTab] = useState<string>()
  const source = tab === 'orders' ? orders : tab === 'settlements' ? settlements : entries
  const selected = source.find(row=>row.id === selectedId)
  const currentFingerprint = ledgerFingerprint(selected)
  const targetValid = compensate && selected?.status === 'EXCEPTION' &&
    targetFingerprint !== undefined && targetFingerprint === currentFingerprint && targetTab === tab
  // Clear stale forms before children commit; hiding a dialog alone would let
  // a removed record resurrect its previous application when it reappears.
  if (compensate && !targetValid) {
    setCompensate(false)
    setConfirmation(undefined)
    setPoints(null)
    setReason('')
    setTargetFingerprint(undefined)
    setTargetTab(undefined)
  }
  const confirmationValid = targetValid && confirmation?.ledgerFingerprint === currentFingerprint &&
    confirmation.points === points && confirmation.reason === reason.trim()
  const dateInvalid = Boolean(start && end && start > end)
  const filtered = source.filter(row =>
    (tab !== 'exceptions' || row.status === 'EXCEPTION') &&
    `${row.id} ${row.account} ${row.reference ?? ''} ${row.taskId ?? ''}`.toLowerCase().includes(query.trim().toLowerCase()) &&
    (business === 'ALL' || row.business === business) &&
    (direction === 'ALL' || (direction === 'IN' ? row.points > 0 : row.points < 0)) &&
    !dateInvalid && (!start || row.occurredAt.slice(0,10) >= start) && (!end || row.occurredAt.slice(0,10) <= end))
  const validCompensation = targetValid && Number.isSafeInteger(points) && (points ?? 0) > 0 && reason.trim().length >= 10
  const reset = () => {setQuery('');setBusiness('ALL');setDirection('ALL');setStart('');setEnd('')}
  const closeCompensation = () => {setCompensate(false);setConfirmation(undefined);setPoints(null);setReason('');setTargetFingerprint(undefined);setTargetTab(undefined)}
  const openCompensation = () => {
    if (!selected || selected.status !== 'EXCEPTION' || !currentFingerprint) return
    closeCompensation()
    setTargetFingerprint(currentFingerprint)
    setTargetTab(tab)
    setCompensate(true)
  }
  const previewConfirmation = () => {
    if (!validCompensation || !selected || points === null || !currentFingerprint) return
    setConfirmation({
      ledgerId: selected.id, account: selected.account, points,
      reason: reason.trim(), ledgerFingerprint: currentFingerprint,
    })
  }
  const changeTab = (key:string) => {setTab(key);reset();setSelectedId(undefined);closeCompensation()}

  return <section className="admin-workspace admin-finance" aria-label="积分账务">
    <div className="admin-workspace__heading"><div><Typography.Title level={1}>积分账务</Typography.Title><Typography.Text type="secondary">查询积分流水、充值订单、结算记录与异常核对</Typography.Text></div><Tag>管理员专用</Tag></div>
    <Alert showIcon type="info" title={preview ? '开发设计预览 · 账务数据仅用于界面验收' : '积分账务接口尚未接入'} description="充值问题通过核查与积分补偿处理；账务变更必须由服务端核验并保留审计记录。" />
    <div className="admin-stat-grid admin-finance-stats">{([['今日充值',summary?.recharge],['今日消耗',summary?.consumption],['今日退款释放',summary?.released],['人工补偿',summary?.compensation],['账实差异',summary?.discrepancy]] as const).map(([label,value])=><Card key={label}><DollarOutlined /><span>{label}</span><strong>{formatPoints(value)} <small>积分</small></strong></Card>)}</div>
    <Card className="admin-data-card"><div className="admin-finance-tabs"><Tabs activeKey={tab} onChange={changeTab} items={[{key:'ledger',label:'积分流水'},{key:'orders',label:'充值订单'},{key:'settlements',label:'结算记录'},{key:'exceptions',label:'异常核对'}]} /></div>
      <div className="admin-finance-filters">
        <label>账号 / 流水号<Input aria-label="搜索账务" placeholder="账号、流水号、订单号或任务号" value={query} onChange={e=>setQuery(e.target.value)} disabled={!source.length} /></label>
        <label>业务类型<Select aria-label="账务业务类型" value={business} onChange={setBusiness} disabled={!source.length} options={[{label:'全部业务',value:'ALL'},...Array.from(new Set(source.map(row=>row.business))).map(value=>({label:value,value}))]} /></label>
        <label>流水方向<Select aria-label="流水方向" value={direction} onChange={setDirection} disabled={!source.length} options={[{label:'全部方向',value:'ALL'},{label:'收入',value:'IN'},{label:'支出',value:'OUT'}]} /></label>
        <label>开始日期<Input type="date" aria-label="账务开始日期" value={start} onChange={e=>setStart(e.target.value)} disabled={!source.length} /></label>
        <label>结束日期<Input type="date" aria-label="账务结束日期" value={end} onChange={e=>setEnd(e.target.value)} disabled={!source.length} /></label><Button onClick={reset} disabled={!query && business === 'ALL' && direction === 'ALL' && !start && !end}>重置</Button>
      </div>
      {dateInvalid && <Alert type="error" showIcon title="开始日期不能晚于结束日期" />}
      {filtered.length ? <div className="admin-table-scroll"><table className="admin-data-table" aria-label="积分账务列表"><thead><tr>{['流水号','脱敏用户','业务','变动积分','变动前','变动后','关联任务 / 订单','发生时间','状态','操作'].map(x=><th key={x}>{x}</th>)}</tr></thead><tbody>{filtered.map(row=><tr key={row.id} className={row.status === 'EXCEPTION' ? 'admin-finance-exception' : undefined}><td><strong>{row.id}</strong></td><td>{row.account}</td><td>{row.business}</td><td>{row.points > 0 && Number.isSafeInteger(row.points) ? '+' : ''}{formatPoints(row.points)}</td><td>{formatPoints(row.before)}</td><td>{formatPoints(row.after)}</td><td>{row.reference ?? row.taskId ?? '—'}</td><td>{row.occurredAt}</td><td><Tag color={row.status === 'EXCEPTION' ? 'error' : row.status === 'SUCCESS' ? 'success' : 'processing'}>{statuses[row.status]}</Tag></td><td><Button type="link" onClick={()=>setSelectedId(row.id)}>查看详情</Button></td></tr>)}</tbody></table></div> : <Empty description={source.length ? '没有符合条件的账务记录' : '暂无账务数据'} />}
      <div className="admin-finance-card-note">当前已加载 {filtered.length} 条记录 · 金额、余额与结算结果均以服务端账本为准。</div>
    </Card>
    <Drawer title="流水详情" size={460} open={Boolean(selected)} onClose={()=>{setSelectedId(undefined);closeCompensation()}}>
      {selected && <div className="admin-finance-form">{selected.status === 'EXCEPTION' && <Alert showIcon type="error" title="该流水存在异常，请核对业务依据后处理。" />}
        <Card title="基本信息"><dl className="admin-finance-details">{[['流水号',selected.id],['状态',statuses[selected.status]],['发生时间',selected.occurredAt],['用户',selected.account],['业务',selected.business],['变动积分',formatPoints(selected.points)],['变动前',formatPoints(selected.before)],['变动后',formatPoints(selected.after)],['关联订单',selected.reference ?? '—'],['关联任务',selected.taskId ?? '—']].map(([key,value])=><div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}</dl></Card>
        <Card title="业务依据"><p>订单金额：{formatCents(selected.amountCents)}</p><p>Trace：{selected.traceId ?? '—'}</p><p>备注：{selected.note ?? '—'}</p></Card>
        <Card title="冻结 / 结算过程">{selected.events?.length ? <Timeline items={selected.events.map(event=>({title:event.title,content:<><p>{event.time}</p><p>{event.detail}</p></>}))} /> : <Empty description="暂无处理过程" />}</Card>
        <Alert type="info" showIcon title="账务记录只读；补偿须重新验证权限、当前异常状态和幂等性，并写入不可删除的审计日志。" />
        {selected.status === 'EXCEPTION' && <Button onClick={openCompensation}>填写补偿申请</Button>}
        <Button type="primary" disabled>执行人工补偿（尚未接入）</Button>
      </div>}
    </Drawer>
    {targetValid && <Modal title="人工补偿申请" open onCancel={closeCompensation} footer={<Button disabled={!validCompensation} onClick={previewConfirmation}>预览补偿确认</Button>}>
      <div className="admin-finance-form"><Alert type="warning" showIcon title="仅填写本地申请，不会变更用户积分。" /><p>补偿用户：{selected?.account}</p><label>补偿积分<InputNumber aria-label="补偿积分" min={1} max={Number.MAX_SAFE_INTEGER} precision={0} value={points} onChange={value=>{setPoints(value);setConfirmation(undefined)}} /></label><label>原因说明<Input.TextArea aria-label="补偿原因" value={reason} maxLength={500} placeholder="至少 10 个字符" onChange={e=>{setReason(e.target.value);setConfirmation(undefined)}} /></label></div>
    </Modal>}
    {confirmation && confirmationValid && <Modal title="补偿申请二次确认" open onCancel={()=>setConfirmation(undefined)} footer={<Button type="primary" disabled>确认补偿（接口尚未接入）</Button>}><p>用户：{confirmation.account}</p><p>关联流水：{confirmation.ledgerId}</p><p>申请补偿：{formatPoints(confirmation.points)} 积分</p><p>原因：{confirmation.reason}</p><Alert type="warning" showIcon title="补偿尚未提交，不代表积分到账。" /></Modal>}
  </section>
}

import { useState } from 'react'
import { Alert, Button, Card, Drawer, Empty, Input, InputNumber, Modal, Steps, Tabs, Tag, Typography } from 'antd'
import { ControlOutlined, PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { formatPoints, type PricingVersionView } from './types'
import { pricingFingerprint } from './fingerprints'

export function AdminPricingWorkspace({ version, preview = false }: {
  version?: PricingVersionView
  preview?: boolean
}) {
  const [tab, setTab] = useState('pricing')
  const [drawer, setDrawer] = useState<'view' | 'edit'>()
  const [draft, setDraft] = useState<Record<string, number | null>>({})
  const [reason, setReason] = useState('')
  const [effectiveAt, setEffectiveAt] = useState('')
  const [discard, setDiscard] = useState(false)
  const [draftFingerprint, setDraftFingerprint] = useState<string>()
  const [conflict, setConflict] = useState(false)
  const currentFingerprint = pricingFingerprint(version)
  // Reconcile before children commit, so no frame combines a stale draft with
  // refreshed prices. Equivalent objects and reordered rows retain the draft.
  if (drawer === 'edit' && draftFingerprint !== currentFingerprint) {
    setDrawer(undefined)
    setDraft({})
    setReason('')
    setEffectiveAt('')
    setDiscard(false)
    setDraftFingerprint(undefined)
    setConflict(true)
  }
  const dirty = reason !== '' || effectiveAt !== '' || Object.keys(draft).length > 0
  const close = () => { setDrawer(undefined); setDraft({}); setReason(''); setEffectiveAt(''); setDiscard(false); setDraftFingerprint(undefined) }
  const openEdit = () => {
    close()
    setDraftFingerprint(currentFingerprint)
    setConflict(false)
    setDrawer('edit')
  }

  return <section className="admin-workspace admin-finance" aria-label="定价配置">
    {conflict && <Alert showIcon type="warning" title="价格版本已更新，请重新编辑" description="旧草稿已清空，请基于当前版本重新创建。" />}
    <div className="admin-workspace__heading"><div><Typography.Title level={1}>定价与运营配置</Typography.Title><Typography.Text type="secondary">管理业务计费、注册赠送和使用频率</Typography.Text></div><Tag icon={<SafetyCertificateOutlined />}>管理员专用</Tag></div>
    <Alert showIcon type="info" title={preview ? '开发设计预览' : '定价配置接口尚未接入'} description={preview ? '价格版本为界面验收样例；本地编辑不会生效，发布与保存均不可用。' : '接入后展示服务端价格版本。当前不代表免费计费或已启用的运营规则。'} />
    <Tabs activeKey={tab} onChange={setTab} items={[{key:'pricing',label:'业务定价'},{key:'gift',label:'注册赠送'},{key:'limits',label:'频率限制'}]} />
    {tab === 'pricing' ? <>
      <Card className="admin-finance-version"><div className="admin-finance-version__content"><div><span>当前生效版本</span><strong>{version ? <Tag color="blue">{version.id}</Tag> : '—'}</strong></div><div><span>生效时间</span><strong>{version?.effectiveAt ?? '—'}</strong></div><div><span>修改人</span><strong>{version?.operator ?? '—'}</strong></div><Button type="primary" icon={<PlusOutlined />} onClick={openEdit}>新建价格版本</Button></div></Card>
      <Alert showIcon type="info" title="仅影响新确认卡；已确认任务和历史订单保持原价格。" />
      <Card className="admin-data-card">
        {version?.rows.length ? <div className="admin-table-scroll"><table className="admin-data-table admin-pricing-table" aria-label="业务定价列表"><thead><tr>{['业务项','计费单位','用户积分（当前）','状态','最近调整','操作'].map(x=><th key={x}>{x}</th>)}</tr></thead><tbody>{version.rows.map(row=><tr key={row.id}><td><ControlOutlined /> <strong>{row.name}</strong></td><td>{row.unit}</td><td>{row.formula ?? formatPoints(row.points)}</td><td><Tag color="success">{preview ? '预览版本' : '生效中'}</Tag></td><td>{version.effectiveAt}<br />{version.operator}</td><td><Button type="link" onClick={()=>setDrawer('view')}>查看版本</Button></td></tr>)}</tbody></table></div> : <Empty description="暂无价格版本" />}
        <div className="admin-finance-card-note">媒体投稿按服务端动态报价计费，不可手工覆盖历史价格快照。</div>
      </Card>
    </> : <Card title={tab === 'gift' ? '注册赠送规则' : '使用频率限制'}><Empty description={tab === 'gift' ? '暂无注册赠送配置' : '暂无频率限制配置'} /><div className="admin-finance-empty-actions"><Typography.Text type="secondary">配置接口尚未接入，当前规则及生效范围以服务端为准。</Typography.Text><Button disabled>编辑规则</Button></div></Card>}
    {drawer && <Drawer title={drawer === 'edit' ? '新建价格版本' : '价格版本详情'} size={480} open={Boolean(drawer)} onClose={()=>drawer === 'edit' && dirty ? setDiscard(true) : close()}>
      {drawer === 'edit' ? <div className="admin-finance-form"><Steps size="small" current={0} items={[{title:'编辑版本'},{title:'管理员确认'}]} />
        <Card title="价格设置（积分）">{version?.rows.length ? version.rows.map(row=><label className="admin-finance-price-field" key={row.id}><span>{row.name}（{row.unit}）</span>{row.formula ? <Typography.Text type="secondary">{row.formula}</Typography.Text> : <InputNumber aria-label={`${row.name}积分`} min={0} max={Number.MAX_SAFE_INTEGER} precision={0} value={row.id in draft ? draft[row.id] : row.points} onChange={value=>setDraft({...draft,[row.id]:value})} />}</label>) : <Empty description="尚无可编辑的业务价格项" />}</Card>
        <label>变更原因 <span className="admin-finance-required">*</span><Input.TextArea aria-label="价格变更原因" value={reason} onChange={e=>setReason(e.target.value)} maxLength={200} showCount rows={3} placeholder="说明调整原因、依据及预期影响" /></label>
        <label>预计生效时间 <span className="admin-finance-required">*</span><Input type="datetime-local" aria-label="价格生效时间" value={effectiveAt} onChange={e=>setEffectiveAt(e.target.value)} /></label>
        <Alert type="warning" showIcon title="新版本仅影响新确认卡，历史订单价格保持不变。" />
        <div className="admin-finance-actions"><Button disabled>保存草稿</Button><Button type="primary" disabled>确认发布</Button></div><Typography.Text type="secondary">发布接口尚未接入。发布须由服务端重新验证权限、价格、生效时间，并完成二次认证与审计。</Typography.Text>
      </div> : version ? <div className="admin-finance-form"><Tag color="blue">{version.id}</Tag><p>生效时间：{version.effectiveAt}</p><p>修改人：{version.operator}</p>{version.rows.map(row=><p key={row.id}>{row.name}：{row.formula ?? `${formatPoints(row.points)} 积分 / ${row.unit}`}</p>)}<Alert showIcon title="历史版本只读，不可覆盖已确认任务的价格快照。" type="info" /></div> : null}
    </Drawer>}
    <Modal title="放弃本地编辑？" open={discard} onCancel={()=>setDiscard(false)} onOk={close} okText="放弃编辑" cancelText="继续编辑"><p>当前价格、原因和生效时间尚未保存，放弃后将清空。</p></Modal>
  </section>
}

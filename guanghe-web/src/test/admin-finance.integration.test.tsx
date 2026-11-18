import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { AdminPricingWorkspace } from '../features/admin-finance/AdminPricingWorkspace'
import { AdminLedgerWorkspace } from '../features/admin-finance/AdminLedgerWorkspace'
import { formatCents, formatPoints, type LedgerRowView, type PricingVersionView } from '../features/admin-finance/types'
import { useAuthStore } from '../stores/authStore'
import { adminFixture, userFixture } from './fixtures/auth'
import { renderApp } from './renderApp'

const version: PricingVersionView = {id:'test-v1',effectiveAt:'2026-09-07',operator:'测试管理员',rows:[{id:'image',name:'图片创作',unit:'张',points:80}]}
const entries: readonly LedgerRowView[] = [
  {id:'TX-1',account:'用户甲',business:'图片创作',points:-80,before:100,after:20,occurredAt:'2026-09-07 10:00:00',status:'SUCCESS'},
  {id:'TX-2',account:'用户乙',business:'充值到账',points:200,before:0,after:200,occurredAt:'2026-09-06 10:00:00',status:'EXCEPTION',amountCents:2000},
]
beforeEach(()=>useAuthStore.setState({status:'anonymous',user:null,error:null}))

const draftReason = '测试旧价格草稿原因'
const compensationReason = '核查订单后申请人工积分补偿'
function fillPricingDraft() {
  fireEvent.change(screen.getByLabelText('图片创作积分'), {target:{value:'999'}})
  fireEvent.change(screen.getByLabelText('价格变更原因'), {target:{value:draftReason}})
  fireEvent.change(screen.getByLabelText('价格生效时间'), {target:{value:'2026-10-01T10:00'}})
}
async function openCompensationForm(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', {name:'查看详情'}))
  await user.click(screen.getByRole('button', {name:'填写补偿申请'}))
}
function fillCompensation() {
  fireEvent.change(screen.getByLabelText('补偿积分'), {target:{value:'200'}})
  fireEvent.change(screen.getByLabelText('补偿原因'), {target:{value:`  ${compensationReason}  `}})
}

describe('价格草稿版本绑定', () => {
  const changes: [string, PricingVersionView | undefined][] = [
    ['版本 ID 改变', {...version,id:'test-v2'}],
    ['同 ID 积分改变', {...version,rows:[{...version.rows[0],points:100}]}],
    ['价格项增加', {...version,rows:[...version.rows,{id:'video',name:'视频创作',unit:'秒',points:15}]}],
    ['价格项删除', {...version,rows:[]}],
    ['公式改变', {...version,rows:[{...version.rows[0],formula:'新报价公式'}]}],
    ['单位改变', {...version,rows:[{...version.rows[0],unit:'组'}]}],
    ['版本删除', undefined],
  ]
  it.each(changes)('%s 时清空旧草稿且恢复旧版本也不会复活', async (_, next) => {
    const user=userEvent.setup()
    const {rerender}=render(<AdminPricingWorkspace version={version} />)
    await user.click(screen.getByRole('button',{name:/新建价格版本/}))
    fillPricingDraft()
    rerender(<AdminPricingWorkspace version={next} />)
    expect(screen.getByText('价格版本已更新，请重新编辑')).toBeInTheDocument()
    expect(screen.queryByLabelText('价格变更原因')).not.toBeInTheDocument()
    expect(screen.queryByDisplayValue('999')).not.toBeInTheDocument()
    expect(screen.queryByRole('button',{name:'确认发布'})).not.toBeInTheDocument()
    rerender(<AdminPricingWorkspace version={{...version,rows:version.rows.map(row=>({...row}))}} />)
    expect(screen.queryByLabelText('价格变更原因')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button',{name:/新建价格版本/}))
    expect(screen.getByLabelText('价格变更原因')).toHaveValue('')
    expect(screen.getByLabelText('价格生效时间')).toHaveValue('')
    expect(screen.getByLabelText('图片创作积分')).toHaveValue('80')
    expect(screen.getByRole('button',{name:'保存草稿'})).toBeDisabled()
    expect(screen.getByRole('button',{name:'确认发布'})).toBeDisabled()
  })
  it('新版本重新编辑只显示新价格，不混入旧草稿', async()=>{
    const user=userEvent.setup();const {rerender}=render(<AdminPricingWorkspace version={version} />)
    await user.click(screen.getByRole('button',{name:/新建价格版本/}));fillPricingDraft()
    rerender(<AdminPricingWorkspace version={{...version,rows:[{...version.rows[0],points:120}]}} />)
    await user.click(screen.getByRole('button',{name:/新建价格版本/}))
    expect(screen.getByLabelText('图片创作积分')).toHaveValue('120')
    expect(screen.getByLabelText('价格变更原因')).toHaveValue('')
    expect(screen.getByLabelText('价格生效时间')).toHaveValue('')
  })
  it('内容相同的新对象及价格项重排保留草稿', async()=>{
    const user=userEvent.setup()
    const original={...version,rows:[...version.rows,{id:'video',name:'视频创作',unit:'秒',points:15}]}
    const {rerender}=render(<AdminPricingWorkspace version={original} />)
    await user.click(screen.getByRole('button',{name:/新建价格版本/}));fillPricingDraft()
    rerender(<AdminPricingWorkspace version={{...original,rows:original.rows.map(row=>({...row})).reverse()}} />)
    expect(screen.getByLabelText('图片创作积分')).toHaveValue('999')
    expect(screen.getByLabelText('价格变更原因')).toHaveValue(draftReason)
    expect(screen.getByLabelText('价格生效时间')).toHaveValue('2026-10-01T10:00')
    expect(screen.queryByText('价格版本已更新，请重新编辑')).not.toBeInTheDocument()
  })
})

describe('补偿申请指纹与确认快照',()=>{
  const exception=entries[1]
  const changes: [string, Partial<LedgerRowView> | null][] = [
    ['删除',null],['状态成功',{status:'SUCCESS'}],['ID 替换',{id:'TX-new'}],
    ['账号改变',{account:'另一账号'}],['变动前余额改变',{before:1}],
    ['变动后余额改变',{after:201}],['金额改变',{amountCents:3000}],
    ['关联订单改变',{reference:'ORDER-new'}],['关联任务改变',{taskId:'TASK-new'}],
    ['业务改变',{business:'人工补偿'}],['积分改变',{points:201}],
    ['发生时间改变',{occurredAt:'2026-09-08 10:00:00'}],
  ]
  it.each(changes)('流水%s 后清空申请和确认，记录恢复也不复活',async(_,change)=>{
    const user=userEvent.setup()
    const {rerender}=render(<AdminLedgerWorkspace entries={[exception]} />)
    await openCompensationForm(user);fillCompensation()
    await user.click(screen.getByRole('button',{name:'预览补偿确认'}))
    expect(screen.getByText(`原因：${compensationReason}`)).toBeInTheDocument()
    rerender(<AdminLedgerWorkspace entries={change ? [{...exception,...change}] : []} />)
    expect(screen.queryByLabelText('补偿积分')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('补偿原因')).not.toBeInTheDocument()
    expect(screen.queryByText(`原因：${compensationReason}`)).not.toBeInTheDocument()
    expect(screen.queryByRole('button',{name:'确认补偿（接口尚未接入）'})).not.toBeInTheDocument()
    rerender(<AdminLedgerWorkspace entries={[{...exception}]} />)
    expect(screen.queryByLabelText('补偿积分')).not.toBeInTheDocument()
    expect(screen.queryByText(`原因：${compensationReason}`)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button',{name:'填写补偿申请'}))
    expect(screen.getByLabelText('补偿积分')).toHaveValue('')
    expect(screen.getByLabelText('补偿原因')).toHaveValue('')
    expect(screen.getByRole('button',{name:'预览补偿确认'})).toBeDisabled()
  })
  it('语义相同的新 entries 数组保留表单和快照，原因去除首尾空格',async()=>{
    const user=userEvent.setup();const {rerender}=render(<AdminLedgerWorkspace entries={[exception]} />)
    await openCompensationForm(user);fillCompensation()
    await user.click(screen.getByRole('button',{name:'预览补偿确认'}))
    rerender(<AdminLedgerWorkspace entries={[{...exception}]} />)
    expect(screen.getByLabelText('补偿积分')).toHaveValue('200')
    expect(screen.getByLabelText('补偿原因')).toHaveValue(`  ${compensationReason}  `)
    expect(screen.getByText(`原因：${compensationReason}`).textContent).toBe(`原因：${compensationReason}`)
    expect(screen.getByText('关联流水：TX-2')).toBeInTheDocument()
    expect(screen.getByRole('button',{name:'确认补偿（接口尚未接入）'})).toBeDisabled()
  })
  it.each(['补偿积分','补偿原因'])('修改%s 立即销毁旧确认，重新预览使用新值',async(field)=>{
    const user=userEvent.setup();render(<AdminLedgerWorkspace entries={[exception]} />)
    await openCompensationForm(user);fillCompensation()
    await user.click(screen.getByRole('button',{name:'预览补偿确认'}))
    fireEvent.change(screen.getByLabelText(field),{target:{value:field === '补偿积分' ? '300' : '重新核对后的新补偿原因'}})
    expect(screen.queryByText(`原因：${compensationReason}`)).not.toBeInTheDocument()
    expect(screen.queryByRole('button',{name:'确认补偿（接口尚未接入）'})).not.toBeInTheDocument()
    await user.click(screen.getByRole('button',{name:'预览补偿确认'}))
    expect(screen.getByText(field === '补偿积分' ? '申请补偿：300 积分' : '原因：重新核对后的新补偿原因')).toBeInTheDocument()
  })
  it('切换页签和数据源清空表单，即使另一数据源含相同流水',async()=>{
    const user=userEvent.setup();render(<AdminLedgerWorkspace entries={[exception]} orders={[exception]} />)
    await openCompensationForm(user);fillCompensation()
    await user.click(screen.getByRole('button',{name:'预览补偿确认'}))
    // Props-driven workspace tab selection also covers programmatic tab changes
    // while dialogs are open; no hidden modal state may survive the switch.
    fireEvent.click(screen.getByRole('tab',{name:'充值订单'}))
    expect(screen.queryByLabelText('补偿积分')).not.toBeInTheDocument()
    expect(screen.queryByText(`原因：${compensationReason}`)).not.toBeInTheDocument()
    await user.click(screen.getByRole('tab',{name:'积分流水'}))
    await openCompensationForm(user)
    expect(screen.getByLabelText('补偿积分')).toHaveValue('')
    expect(screen.getByLabelText('补偿原因')).toHaveValue('')
    expect(screen.getByRole('button',{name:'预览补偿确认'})).toBeDisabled()
  })
})

describe('关联任务搜索',()=>{
  it('taskId 搜索忽略大小写和首尾空格并支持组合筛选',async()=>{
    const user=userEvent.setup()
    render(<AdminLedgerWorkspace entries={[{...entries[0],taskId:'TASK-IMAGE-001'},entries[1]]} />)
    fireEvent.change(screen.getByLabelText('搜索账务'),{target:{value:'  task-image-001  '}})
    expect(screen.getByText('TX-1')).toBeInTheDocument()
    expect(screen.queryByText('TX-2')).not.toBeInTheDocument()
    await user.click(screen.getByRole('combobox',{name:'账务业务类型'}))
    await user.click(screen.getByRole('option',{name:'图片创作'}))
    await user.click(screen.getByRole('combobox',{name:'流水方向'}))
    await user.click(screen.getByText('支出', {selector:'.ant-select-item-option-content'}))
    expect(screen.getByRole('combobox',{name:'流水方向'}).closest('.ant-select')).toHaveTextContent('支出')
    fireEvent.change(screen.getByLabelText('账务开始日期'),{target:{value:'2026-09-07'}})
    fireEvent.change(screen.getByLabelText('账务结束日期'),{target:{value:'2026-09-07'}})
    expect(screen.getByText('TX-1')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('账务开始日期'),{target:{value:'2026-09-08'}})
    expect(screen.queryByText('TX-1')).not.toBeInTheDocument()
  })
})
describe('管理端定价与积分账务',()=>{
  it('正式定价无数据时显示真实空态，发布保持禁用', async()=>{
    const user=userEvent.setup(); render(<AdminPricingWorkspace />)
    expect(screen.getByText('暂无价格版本')).toBeInTheDocument()
    await user.click(screen.getByRole('button',{name:/新建价格版本/}))
    expect(screen.getByRole('button',{name:'确认发布'})).toBeDisabled()
    expect(screen.getByRole('button',{name:'保存草稿'})).toBeDisabled()
  })
  it('修改价格草稿后关闭需要明确放弃确认',async()=>{
    const user=userEvent.setup(); render(<AdminPricingWorkspace version={version} />)
    await user.click(screen.getByRole('button',{name:/新建价格版本/}))
    fireEvent.change(screen.getByLabelText('价格变更原因'),{target:{value:'调整计费规则'}})
    await user.click(within(screen.getByLabelText('价格变更原因').closest('[role="dialog"]') as HTMLElement).getByRole('button',{name:'Close'}))
    expect(screen.getByText('放弃本地编辑？')).toBeInTheDocument()
    await user.click(screen.getByRole('button',{name:'继续编辑'}))
    expect(screen.getByLabelText('价格变更原因')).toHaveValue('调整计费规则')
    await user.click(within(screen.getByLabelText('价格变更原因').closest('[role="dialog"]') as HTMLElement).getByRole('button',{name:'Close'}))
    await user.click(screen.getByRole('button',{name:'放弃编辑'}))
    await user.click(screen.getByRole('button',{name:/新建价格版本/}))
    expect(screen.getByLabelText('价格变更原因')).toHaveValue('')
  })
  it('注册赠送和频率限制切换展示未接入空态',async()=>{
    const user=userEvent.setup();render(<AdminPricingWorkspace />)
    await user.click(screen.getByRole('tab',{name:'注册赠送'}));expect(screen.getByText('暂无注册赠送配置')).toBeInTheDocument()
    await user.click(screen.getByRole('tab',{name:'频率限制'}));expect(screen.getByText('暂无频率限制配置')).toBeInTheDocument()
    expect(screen.getByRole('button',{name:'编辑规则'})).toBeDisabled()
  })
  it('账务四个页签保持空态，不伪造零余额',async()=>{
    const user=userEvent.setup();render(<AdminLedgerWorkspace />)
    expect(screen.getAllByText('—')).toHaveLength(5)
    expect(screen.getByLabelText('搜索账务')).toBeDisabled()
    for(const name of ['充值订单','结算记录','异常核对','积分流水']) {
      await user.click(screen.getByRole('tab',{name}));expect(screen.getByText('暂无账务数据')).toBeInTheDocument()
    }
  })
  it('搜索和日期同时过滤，日期倒置明确报错，重置恢复',async()=>{
    const user=userEvent.setup();render(<AdminLedgerWorkspace entries={entries} />)
    fireEvent.change(screen.getByLabelText('搜索账务'),{target:{value:'用户乙'}})
    expect(screen.queryByText('TX-1')).not.toBeInTheDocument();expect(screen.getByText('TX-2')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('账务开始日期'),{target:{value:'2026-09-07'}})
    expect(screen.getByText('没有符合条件的账务记录')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('账务结束日期'),{target:{value:'2026-09-06'}})
    expect(screen.getByText('开始日期不能晚于结束日期')).toBeInTheDocument()
    await user.click(screen.getByRole('button',{name:/重\s*置/}));expect(screen.getByText('TX-1')).toBeInTheDocument()
  })
  it('异常筛选、详情及补偿预览不会执行补偿',async()=>{
    const user=userEvent.setup();render(<AdminLedgerWorkspace entries={entries} />)
    await user.click(screen.getByRole('tab',{name:'异常核对'}))
    expect(screen.queryByText('TX-1')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button',{name:'查看详情'}))
    expect(screen.getByText('订单金额：¥20.00')).toBeInTheDocument()
    expect(screen.getByRole('button',{name:'执行人工补偿（尚未接入）'})).toBeDisabled()
    await user.click(screen.getByRole('button',{name:'填写补偿申请'}))
    expect(screen.getByRole('button',{name:'预览补偿确认'})).toBeDisabled()
    fireEvent.change(screen.getByLabelText('补偿积分'),{target:{value:'200'}})
    fireEvent.change(screen.getByLabelText('补偿原因'),{target:{value:'核对订单后填写测试补偿原因'}})
    await user.click(screen.getByRole('button',{name:'预览补偿确认'}))
    expect(screen.getByRole('button',{name:'确认补偿（接口尚未接入）'})).toBeDisabled()
    expect(screen.getByText('补偿尚未提交，不代表积分到账。')).toBeInTheDocument()
  })
  it('金额和积分格式化拒绝无效值且保留安全整数精度',()=>{
    expect(formatCents(9007199254740990)).toBe('¥90071992547409.90')
    for(const value of [NaN,Infinity,-1,1.1,Number.MAX_SAFE_INTEGER+1]) expect(formatCents(value)).toBe('—')
    expect(formatPoints(Number.MAX_SAFE_INTEGER)).toBe('9,007,199,254,740,991')
    expect(formatPoints(Infinity)).toBe('—')
  })
  it.each([['/admin/pricing','定价配置'],['/admin/points-ledger','积分账务']])('正式路由 %s 渲染真实控制台，不暴露预览样例',async(path,heading)=>{
    useAuthStore.setState({status:'authenticated',user:adminFixture,error:null});renderApp([path])
    expect(await screen.findByRole('heading',{name:heading})).toBeInTheDocument()
    expect(screen.queryByText('404 页面未找到')).not.toBeInTheDocument()
    expect(screen.queryByText('PREVIEW-PRICE-V1.3')).not.toBeInTheDocument()
    expect(screen.queryByText('PREVIEW-LEDGER-001')).not.toBeInTheDocument()
    expect(within(screen.getByRole('navigation', { name: '管理员主导航' })).getByRole('button', { name: heading })).toHaveAttribute('aria-current', 'page')
  })
  it.each(['/admin/pricing','/admin/points-ledger'])('普通用户禁止访问 %s',async(path)=>{
    useAuthStore.setState({status:'authenticated',user:userFixture,error:null});renderApp([path])
    expect(await screen.findByText('403 拒绝访问')).toBeInTheDocument()
  })
  it.each([['/preview/admin/pricing','定价与运营配置'],['/preview/admin/points-ledger','积分账务']])('开发预览路由 %s 正常',async(path,title)=>{
    renderApp([path]);expect(await screen.findByRole('heading',{name:title})).toBeInTheDocument()
    expect(within(screen.getByRole('navigation',{name:'管理员主导航'})).getByRole('button',{name:title === '积分账务' ? '积分账务' : '定价配置'})).toHaveAttribute('aria-current','page')
  })
})

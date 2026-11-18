import { AdminPricingWorkspace } from '../../features/admin-finance/AdminPricingWorkspace'
import { AdminLedgerWorkspace } from '../../features/admin-finance/AdminLedgerWorkspace'
import type { LedgerRowView, PricingVersionView } from '../../features/admin-finance/types'
import { AdminShell } from '../../layouts/AdminShell'

// This module is lazy-loaded exclusively from DEV routes.
const version: PricingVersionView = {
  id:'PREVIEW-PRICE-V1.3', effectiveAt:'2026-09-07 10:30:00', operator:'设计预览管理员',
  rows:[
    {id:'geo',name:'GEO 监测',unit:'平台 / 问题',points:120},
    {id:'image',name:'图片创作',unit:'张',points:80},
    {id:'video',name:'视频创作',unit:'秒',points:15},
    {id:'article',name:'文章生成',unit:'篇',points:60},
    {id:'media',name:'媒体投稿',unit:'次',formula:'采购网原价 × 1.35，向上取整到元，再 × 100 积分'},
  ],
}
const entries: readonly LedgerRowView[] = [
  {id:'PREVIEW-LEDGER-001',account:'用**9527',business:'图片创作',points:-80,before:2080,after:2000,taskId:'PREVIEW-IMAGE-001',occurredAt:'2026-09-07 09:42:18',status:'SUCCESS'},
  {id:'PREVIEW-LEDGER-002',account:'用**7315',business:'视频创作',points:-150,before:3250,after:3100,taskId:'PREVIEW-VIDEO-002',occurredAt:'2026-09-07 09:28:55',status:'SUCCESS'},
  {id:'PREVIEW-LEDGER-003',account:'用**4210',business:'充值到账',points:200,before:980,after:1180,reference:'PREVIEW-ORDER-003',amountCents:2000,occurredAt:'2026-09-07 09:15:33',status:'EXCEPTION',note:'界面预览：订单入账需人工核对',traceId:'PREVIEW-LEDGER-TRACE',events:[{title:'订单创建',time:'2026-09-07 09:15:20',detail:'预览事件：订单创建'},{title:'入账待核对',time:'2026-09-07 09:15:33',detail:'预览事件：幂等记录需复核'}]},
  {id:'PREVIEW-LEDGER-004',account:'用**6681',business:'文章生成',points:-60,before:700,after:640,taskId:'PREVIEW-ARTICLE-004',occurredAt:'2026-09-07 08:56:07',status:'SUCCESS'},
  {id:'PREVIEW-LEDGER-005',account:'用**3098',business:'注册赠送',points:10,before:210,after:220,occurredAt:'2026-09-07 08:41:22',status:'SUCCESS'},
  {id:'PREVIEW-LEDGER-006',account:'用**7315',business:'人工补偿',points:30,before:3040,after:3070,occurredAt:'2026-09-07 08:20:05',status:'SUCCESS'},
]
export function AdminPricingPreviewPage() {
  return <AdminShell><AdminPricingWorkspace preview version={version} /></AdminShell>
}
export function AdminLedgerPreviewPage() {
  return <AdminShell><AdminLedgerWorkspace preview entries={entries} orders={entries.filter(x=>x.reference)} settlements={entries.filter(x=>x.taskId)} summary={{recharge:200,consumption:290,released:0,compensation:30,discrepancy:200}} /></AdminShell>
}

import { useState } from 'react'
import type {
  AdminMediaCallback,
  AdminMediaResource,
  AdminMediaSubmission,
  AdminMediaSubmissionSummary,
  AdminMediaSyncState,
} from '../../api/adminMediaApi'
import {
  AdminMediaWorkspace,
  type AdminMediaDetailProps,
  type AdminMediaDetailTarget,
} from '../../features/admin-publishing/AdminMediaWorkspace'
import { AdminInvoiceWorkspace } from '../../features/admin-publishing/AdminInvoiceWorkspace'
import type { InvoiceApplicationView } from '../../features/admin-publishing/types'
import { AdminShell } from '../../layouts/AdminShell'

// DEV-only lazy entry point. These are explicit UI examples, never live results: every id is
// PREVIEW-*, and the amounts follow the server's published rule (ceil(原价 × 1.35) 元) so a reviewer
// can check the column arithmetic by hand without mistaking a sample for a quotation.
const PREVIEW_PROVIDER = 'mcapi'
const resources: readonly AdminMediaResource[] = [
  { id: 'PREVIEW-MEDIA-001', providerCode: PREVIEW_PROVIDER, upstreamModelId: 'preview_baijiahao_article', upstreamResourceId: 'preview-res-1001', name: '百家号', status: 'ACTIVE', resourceType: '图文发布', channelCategory: '综合资讯', region: '全国', estimatedDays: 1, capabilities: { allow_images: true, max_images: 8, publish_window: '2-6h', content_types: ['article', 'video'] }, upstreamPrice: '18.0000', markupRate: '0.3500', referenceSaleAmountCents: 2500, referenceChargedPoints: 2500, lastSeenAt: '2026-09-07T09:42:18Z', syncedAt: '2026-09-07T09:40:00Z', version: 7 },
  { id: 'PREVIEW-MEDIA-002', providerCode: PREVIEW_PROVIDER, upstreamModelId: 'preview_toutiao_article', upstreamResourceId: 'preview-res-1002', name: '头条号', status: 'ACTIVE', resourceType: '图文发布', channelCategory: '综合资讯', region: '全国', estimatedDays: 1, capabilities: { allow_images: true, max_images: 6 }, upstreamPrice: '16.0000', markupRate: '0.3500', referenceSaleAmountCents: 2200, referenceChargedPoints: 2200, lastSeenAt: '2026-09-07T09:41:05Z', syncedAt: '2026-09-07T09:40:00Z', version: 7 },
  { id: 'PREVIEW-MEDIA-003', providerCode: PREVIEW_PROVIDER, upstreamModelId: 'preview_sohu_article', upstreamResourceId: 'preview-res-1003', name: '搜狐号', status: 'ACTIVE', resourceType: '图文发布', channelCategory: '综合资讯', region: '全国', estimatedDays: 1, capabilities: {}, upstreamPrice: '15.0000', markupRate: '0.3500', referenceSaleAmountCents: 2100, referenceChargedPoints: 2100, lastSeenAt: '2026-09-07T09:40:32Z', syncedAt: '2026-09-07T09:40:00Z', version: 6 },
  { id: 'PREVIEW-MEDIA-004', providerCode: PREVIEW_PROVIDER, upstreamModelId: 'preview_netease_article', upstreamResourceId: 'preview-res-1004', name: '网易号', status: 'INACTIVE', resourceType: '图文发布', channelCategory: '综合资讯', region: '全国', estimatedDays: null, capabilities: {}, upstreamPrice: '17.0000', markupRate: '0.3500', referenceSaleAmountCents: 2300, referenceChargedPoints: 2300, lastSeenAt: '2026-09-07T09:39:15Z', syncedAt: '2026-09-07T09:40:00Z', version: 6 },
  { id: 'PREVIEW-MEDIA-005', providerCode: PREVIEW_PROVIDER, upstreamModelId: 'preview_zhihu_article', upstreamResourceId: 'preview-res-1005', name: '知乎号', status: 'ACTIVE', resourceType: '专栏文章', channelCategory: '知识社区', region: '全国', estimatedDays: 2, capabilities: { allow_images: false }, upstreamPrice: '20.0000', markupRate: '0.3500', referenceSaleAmountCents: 2700, referenceChargedPoints: 2700, lastSeenAt: '2026-09-07T09:38:50Z', syncedAt: '2026-09-07T09:40:00Z', version: 5 },
  { id: 'PREVIEW-MEDIA-006', providerCode: PREVIEW_PROVIDER, upstreamModelId: 'preview_wechat_mp', upstreamResourceId: 'preview-res-1006', name: '微信公众号', status: 'INACTIVE', resourceType: '公众号文章', channelCategory: '微信生态', region: '全国', estimatedDays: 3, capabilities: {}, upstreamPrice: '30.0000', markupRate: '0.3500', referenceSaleAmountCents: 4100, referenceChargedPoints: 4100, lastSeenAt: '2026-09-07T09:37:22Z', syncedAt: '2026-09-07T09:40:00Z', version: 5 },
]
const submissions: readonly AdminMediaSubmissionSummary[] = [
  { id: 'PREVIEW-SUBMISSION-001', batchId: 'PREVIEW-BATCH-001', taskId: 'PREVIEW-TASK-001', userId: 'PREVIEW-USER-9527', mediaResourceId: 'PREVIEW-MEDIA-001', mediaName: '百家号', providerCode: PREVIEW_PROVIDER, upstreamModelId: 'preview_baijiahao_article', upstreamResourceId: 'preview-res-1001', customerOrderId: 'PREVIEW-ORDER-001', upstreamOrderId: null, upstreamOrderNo: null, upstreamStatus: 'UNKNOWN', status: 'SUBMIT_UNKNOWN', submissionState: 'UNKNOWN', publishedUrl: null, publishedUrls: [], stableErrorCode: 'UPSTREAM_TIMEOUT', upstreamPrice: '18.0000', markupRate: '0.3500', saleAmountCents: 2500, chargedPoints: 2500, remark: '预览：上游受理结果未知，先核对上游状态，不得重复投稿。', submitAttemptedAt: '2026-09-07T09:48:15Z', nextPollAt: '2026-09-07T09:58:15Z', publishedAt: null, rejectedAt: null, createdAt: '2026-09-07T09:48:15Z', updatedAt: '2026-09-07T09:49:02Z' },
  { id: 'PREVIEW-SUBMISSION-002', batchId: 'PREVIEW-BATCH-002', taskId: 'PREVIEW-TASK-002', userId: 'PREVIEW-USER-7315', mediaResourceId: 'PREVIEW-MEDIA-003', mediaName: '搜狐号', providerCode: PREVIEW_PROVIDER, upstreamModelId: 'preview_sohu_article', upstreamResourceId: 'preview-res-1003', customerOrderId: 'PREVIEW-ORDER-002', upstreamOrderId: null, upstreamOrderNo: null, upstreamStatus: 'FAILED', status: 'FAILED', submissionState: 'NOT_SUBMITTED', publishedUrl: null, publishedUrls: [], stableErrorCode: 'UPSTREAM_REJECTED', upstreamPrice: '15.0000', markupRate: '0.3500', saleAmountCents: 2100, chargedPoints: 2100, remark: '预览：上游拒绝受理。', submitAttemptedAt: '2026-09-07T09:44:02Z', nextPollAt: null, publishedAt: null, rejectedAt: null, createdAt: '2026-09-07T09:44:02Z', updatedAt: '2026-09-07T09:45:10Z' },
]
const submissionEvents: NonNullable<AdminMediaSubmission['events']> = [
  { id: 'PREVIEW-EVENT-001', sequence: 1, eventType: 'SUBMIT_STARTED', fromStatus: null, toStatus: 'SUBMITTING', stableErrorCode: null, occurredAt: '2026-09-07T09:48:16Z' },
  { id: 'PREVIEW-EVENT-002', sequence: 2, eventType: 'SUBMIT_TIMEOUT', fromStatus: 'SUBMITTING', toStatus: 'SUBMIT_UNKNOWN', stableErrorCode: 'UPSTREAM_TIMEOUT', occurredAt: '2026-09-07T09:49:02Z' },
]
const callbacks: readonly AdminMediaCallback[] = [
  { id: 'PREVIEW-CALLBACK-001', providerCode: PREVIEW_PROVIDER, customerOrderId: 'PREVIEW-ORDER-001', upstreamModelId: 'preview_baijiahao_article', upstreamResourceId: 'preview-res-1001', eventType: 'order.status_changed', status: 'RECEIVED', attempts: 2, stableErrorCode: 'STATUS_CONFLICT', receivedAt: '2026-09-07T09:45:00Z', processedAt: null, updatedAt: '2026-09-07T09:45:00Z' },
]
const syncStates: readonly AdminMediaSyncState[] = [
  { providerCode: PREVIEW_PROVIDER, upstreamModelId: 'preview_baijiahao_article', status: 'SUCCEEDED', lastAttemptedAt: '2026-09-07T09:40:00Z', lastSucceededAt: '2026-09-07T09:42:18Z', lastErrorCode: null, lastResourceCount: 6, version: 7, updatedAt: '2026-09-07T09:42:18Z' },
  { providerCode: PREVIEW_PROVIDER, upstreamModelId: 'preview_wechat_mp', status: 'FAILED', lastAttemptedAt: '2026-09-07T09:40:00Z', lastSucceededAt: '2026-09-06T09:40:00Z', lastErrorCode: 'UPSTREAM_TIMEOUT', lastResourceCount: 5, version: 5, updatedAt: '2026-09-07T09:41:00Z' },
]

const invoices: readonly InvoiceApplicationView[] = [
  { id: 'PREVIEW-INVOICE-001', account: '132****8921', orderId: 'PREVIEW-RECHARGE-001', amountCents: 29900, title: '设计验收示例企业', taxIdMasked: '9111********1234', emailMasked: 'zhi***@example.com', phoneMasked: '132****8921', status: 'PENDING', createdAt: '2026-09-07 10:15:23', note: '预览：请开具增值税普通发票。', events: [{ title: '用户提交（预览）', time: '2026-09-07 10:15:23' }, { title: '通知记录（预览）', time: '2026-09-07 10:15:25', detail: '仅作流程展示，不代表真实通知已发送。' }] },
  { id: 'PREVIEW-INVOICE-002', account: '159****3320', orderId: 'PREVIEW-RECHARGE-002', amountCents: 9900, title: '内容工作室（预览）', taxIdMasked: '9131********5678', emailMasked: 'stu***@example.com', status: 'PROCESSING', createdAt: '2026-09-07 09:55:10', events: [{ title: '申请已受理（预览）', time: '2026-09-07 10:00:00' }] },
  { id: 'PREVIEW-INVOICE-003', account: '186****7712', orderId: 'PREVIEW-RECHARGE-003', amountCents: 49900, title: '设计示例科技企业', taxIdMasked: '9133********9012', emailMasked: 'yun***@example.com', status: 'COMPLETED', createdAt: '2026-09-06 09:20:45', invoiceNumber: 'PREVIEW-NUMBER-003', deliveryResult: 'SENT', processingNote: '预览已结束状态，不代表真实开票。', events: [{ title: '处理完成（预览）', time: '2026-09-06 11:20:00' }] },
  { id: 'PREVIEW-INVOICE-004', account: '138****5599', orderId: 'PREVIEW-RECHARGE-004', amountCents: 19900, title: '品牌示例工作室', taxIdMasked: '9144********3456', emailMasked: 'wei***@example.com', status: 'REJECTED', createdAt: '2026-09-05 08:40:12', processingNote: '预览：申请信息需要重新核对。', events: [{ title: '申请驳回（预览）', time: '2026-09-05 11:00:00' }] },
]

export function AdminMediaPreviewPage() {
  const [selection, setSelection] = useState<AdminMediaDetailTarget>()
  const detail: AdminMediaDetailProps | null = selection
    ? {
        target: selection,
        resource:
          selection.kind === 'resource'
            ? resources.find((item) => item.id === selection.id)
            : undefined,
        submission:
          selection.kind === 'submission'
            ? submissions.find((item) => item.id === selection.id)
            : undefined,
        submissionDetail:
          selection.kind === 'submission'
            ? {
                ...(submissions.find((item) => item.id === selection.id) as AdminMediaSubmissionSummary),
                events: submissionEvents,
              }
            : undefined,
        callback:
          selection.kind === 'callback'
            ? callbacks.find((item) => item.id === selection.id)
            : undefined,
        loading: false,
      }
    : null
  return (
    <AdminShell>
      <AdminMediaWorkspace
        preview
        overview={{ activeResources: 4, walletBalanceCents: null, pendingSubmissions: 1, exceptionSubmissions: 2 }}
        resources={{ items: resources, filters: {}, page: 1, pageSize: 20, total: resources.length, loading: false }}
        submissions={{ items: submissions, filter: 'ALL', page: 1, pageSize: 20, total: submissions.length, loading: false }}
        callbacks={{ items: callbacks, page: 1, pageSize: 20, total: callbacks.length, loading: false }}
        syncStates={{ items: syncStates, loading: false }}
        detail={detail ?? null}
        onOpenDetail={setSelection}
        onCloseDetail={() => setSelection(undefined)}
      />
    </AdminShell>
  )
}

export function AdminInvoicePreviewPage() {
  return <AdminShell><AdminInvoiceWorkspace preview applications={invoices} summary={{ PENDING: 1, PROCESSING: 1, COMPLETED: 1, REJECTED: 1 }} /></AdminShell>
}

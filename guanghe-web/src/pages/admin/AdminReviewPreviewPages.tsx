import { AdminFeedbackWorkspace } from '../../features/admin-review/AdminFeedbackWorkspace'
import type { FeedbackView } from '../../features/admin-review/types'
import { AdminShell } from '../../layouts/AdminShell'

// This module is lazy-loaded only within the DEV branch of AppRoutes.
// All records below are explicitly labelled UI examples, never API results.
const tickets: FeedbackView[] = [
  { id: 'PREVIEW-FEEDBACK-001', account: '预览用户 A（脱敏）', business: 'AI 生图', description: '开发样例：生成图片的细节与预期不一致，希望了解如何调整描述。', taskId: 'PREVIEW-TASK-IMAGE-01', createdAt: '2026-09-07 11:00:00', status: 'NEW', problemType: '效果反馈', runId: 'PREVIEW-REVIEW-RUN', taskStatus: '待核实（样例）', provider: '预览 Provider', profileHash: 'preview-profile-hash', attachments: [{ id: 'preview-attachment-1', name: '用户截图（预览占位）' }], events: [] },
  { id: 'PREVIEW-FEEDBACK-002', account: '预览用户 B（脱敏）', business: 'AI 生视频', description: '开发样例：任务等待时间较长，需要协助确认当前状态。', taskId: 'PREVIEW-TASK-VIDEO-02', createdAt: '2026-09-06 16:40:00', status: 'PROCESSING', assignee: '预览客服', problemType: '任务异常', attachments: [], events: [{ id: 'preview-message-1', actor: '预览客服', time: '2026-09-06 17:00:00', summary: '沟通记录样例：已记录问题，等待进一步核实。' }] },
  { id: 'PREVIEW-FEEDBACK-003', account: '预览用户 C（脱敏）', business: '文章创作', description: '开发样例：咨询文章编辑入口。', taskId: 'PREVIEW-TASK-ARTICLE-03', createdAt: '2026-09-05 09:30:00', status: 'RESOLVED', assignee: '预览客服', problemType: '使用咨询', attachments: [], events: [] },
]
export function AdminFeedbackPreviewPage() {
  return <AdminShell><AdminFeedbackWorkspace preview tickets={tickets} summary={{ NEW: 1, PROCESSING: 1, RESOLVED: 1 }} aggregates={[{ id: 'preview-aggregate-1', business: 'AI 生图（样例）', problemType: '效果反馈', count: 1, resolved: 0 }]} /></AdminShell>
}

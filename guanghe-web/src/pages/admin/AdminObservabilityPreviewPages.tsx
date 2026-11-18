import { useState } from 'react'
import type {
  AdminAgentRuntimeSnapshot,
  AdminAgentVersionDetail,
  AdminAgentVersionRun,
  AdminAgentVersionSummary,
} from '../../api/adminAgentVersionsApi'
import { AdminAuditWorkspace } from '../../features/admin-observability/AdminAuditWorkspace'
import { AdminAgentVersionsWorkspace } from '../../features/admin-observability/AdminAgentVersionsWorkspace'
import type { AuditLogView } from '../../features/admin-observability/types'
import { AdminShell } from '../../layouts/AdminShell'

// DEV-only UI examples. Never import this module into production pages.
const logs: AuditLogView[] = [
  { id: 'PREVIEW-AUDIT-001', administrator: '预览管理员 A', action: '发布价格版本', objectType: '定价版本', objectId: 'preview-price-v2', summary: '开发样例：价格版本变更摘要，非真实发布记录', result: 'SUCCESS', ipMasked: '192.***.0.10', occurredAt: '2026-09-07 10:30:00', traceId: 'PREVIEW-AUDIT-TRACE', reason: '开发样例：用于审计详情视觉验收', changes: [{ field: '版本号', before: 'preview-v1', after: 'preview-v2' }, { field: '状态', before: '草稿（样例）', after: '已发布（样例）' }], confirmation: { administrator: '预览管理员 A', time: '2026-09-07 10:29:00', result: '确认通过（样例）' }, events: [{ id: 'preview-event-1', time: '2026-09-07 10:29:00', summary: '二次确认（样例）' }, { id: 'preview-event-2', time: '2026-09-07 10:30:00', summary: '操作完成（样例）' }] },
  { id: 'PREVIEW-AUDIT-002', administrator: '预览管理员 B', action: '任务诊断', objectType: '任务', objectId: 'preview-task-2', summary: '开发样例：部分成功状态展示', result: 'PARTIAL', ipMasked: '192.***.0.20', occurredAt: '2026-09-06 15:20:00', traceId: 'preview-task-trace', reason: '开发设计预览', changes: [], taskId: 'preview-task-2', events: [] },
  { id: 'PREVIEW-AUDIT-003', administrator: '预览管理员 A', action: '账号状态变更', objectType: '用户', objectId: 'preview-user-3', summary: '开发样例：失败状态展示，不代表账号变更', result: 'FAILED', ipMasked: '192.***.0.10', occurredAt: '2026-09-05 09:00:00', traceId: 'preview-user-trace', reason: '开发设计预览', userIdMasked: '用**甲', changes: [], events: [] },
]
const previewRuntime: AdminAgentRuntimeSnapshot = {
  availability: 'AVAILABLE', reasonCode: null, serviceVersion: 'preview-service-1', bundleVersion: 'preview-bundle-2', bundleHash: 'preview-bundle-hash', promptHash: 'preview-prompt-hash', gitCommit: 'abcdef0',
  modelGateway: { mode: 'MOCK', provider: 'PREVIEW', model: 'preview-model', status: 'AVAILABLE', reasonCode: null },
  profiles: [{ profileCode: 'PREVIEW_PROFILE', profileVersion: '1', taskTypes: ['ARTICLE'] }],
  providers: [{ code: 'PREVIEW', status: 'AVAILABLE', model: 'preview-model' }],
}
const previewVersions: AdminAgentVersionSummary[] = [
  { bundleVersion: 'preview-bundle-2', status: 'CURRENT', firstObservedAt: '2026-09-07T09:00:00Z', lastObservedAt: '2026-09-07T10:00:00Z', runCount: 2 },
  { bundleVersion: 'preview-bundle-1', status: 'OBSERVED', firstObservedAt: '2026-09-01T09:00:00Z', lastObservedAt: '2026-09-06T10:00:00Z', runCount: 1 },
]
const previewRuns: AdminAgentVersionRun[] = [
  { executionId: '101', source: 'TASK', parentId: '201', taskType: 'ARTICLE', status: 'SUCCEEDED', stage: 'COMPLETED', provider: 'PREVIEW', model: 'preview-model', stableErrorCode: null, startedAt: '2026-09-07T09:30:00Z', finishedAt: '2026-09-07T09:30:02.410Z', durationMs: 2410 },
  { executionId: '102', source: 'WORKFLOW', parentId: '202', taskType: 'WORKFLOW', status: 'FAILED', stage: null, provider: null, model: null, stableErrorCode: 'PREVIEW_FAILURE', startedAt: '2026-09-07T09:31:00Z', finishedAt: '2026-09-07T09:31:01Z', durationMs: 1000 },
]

export function AdminAuditPreviewPage() { return <AdminShell><AdminAuditWorkspace preview logs={logs} /></AdminShell> }
export function AdminAgentVersionsPreviewPage() {
  const [selected, setSelected] = useState<AdminAgentVersionSummary>()
  const detail: AdminAgentVersionDetail | undefined = selected ? {
    version: selected,
    currentRuntime: previewRuntime,
    evaluationAvailability: 'NOT_REGISTERED',
    dataGaps: selected.status === 'CURRENT' ? ['EVALUATIONS_NOT_REGISTERED'] : ['EVALUATIONS_NOT_REGISTERED', 'HISTORICAL_RUNTIME_METADATA_NOT_PERSISTED'],
  } : undefined
  return <AdminShell><AdminAgentVersionsWorkspace
    preview
    versions={{ runtime: previewRuntime, items: previewVersions, page: 1, pageSize: 20, total: 2, loading: false, error: null }}
    detail={selected ? {
      summary: selected, data: detail, loading: false, error: null,
      runs: { items: selected.status === 'CURRENT' ? previewRuns : [], page: 1, pageSize: 20, total: selected.status === 'CURRENT' ? 2 : 0, loading: false, error: null },
      evaluations: { data: { availability: 'NOT_REGISTERED', results: { items: [], page: 1, size: 20, total: 0, hasNext: false } }, loading: false, error: null },
    } : null}
    onRefresh={() => undefined}
    onOpenVersion={(bundleVersion) => setSelected(previewVersions.find((item) => item.bundleVersion === bundleVersion))}
    onCloseDetail={() => setSelected(undefined)}
  /></AdminShell>
}

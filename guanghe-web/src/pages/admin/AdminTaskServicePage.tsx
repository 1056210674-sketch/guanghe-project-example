import { SafetyCertificateOutlined } from '@ant-design/icons'
import { Tag, Typography } from 'antd'
import { useState } from 'react'
import type {
  AdminRunDiagnosticListQuery,
  AdminTaskDiagnosticListQuery,
} from '../../api/adminDiagnosticsApi'
import { isApiError } from '../../api/apiError'
import {
  AdminRunDiagnosticsWorkspace,
  AdminTaskDiagnosticsWorkspace,
  useAdminRunDiagnostic,
  useAdminRunDiagnostics,
  useAdminTaskDiagnostic,
  useAdminTaskDiagnostics,
} from '../../features/admin-tasks'
import type {
  AdminTaskDiagnosticErrorView,
  AdminRunDiagnosticFiltersView,
  AdminTaskDiagnosticFiltersView,
} from '../../features/admin-tasks'
import { AdminShell } from '../../layouts/AdminShell'

const PAGE_SIZE = 20

function diagnosticError(error: unknown): AdminTaskDiagnosticErrorView | null {
  if (!error) return null
  if (isApiError(error)) return { message: error.message, traceId: error.traceId }
  return { message: '任务诊断数据加载失败，请稍后重试。' }
}

function apiDate(value?: string): string | undefined {
  if (!value) return undefined
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) ? new Date(timestamp).toISOString() : undefined
}

function toApiQuery(
  filters: AdminTaskDiagnosticFiltersView,
  page: number,
): AdminTaskDiagnosticListQuery {
  return {
    taskId: filters.taskId?.trim() || undefined,
    taskType: filters.taskType,
    taskStatus: filters.taskStatus,
    stableErrorCode: filters.stableErrorCode?.trim().toUpperCase() || undefined,
    traceId: filters.traceId?.trim().toLowerCase() || undefined,
    from: apiDate(filters.from),
    to: apiDate(filters.to),
    page,
    size: PAGE_SIZE,
  }
}

function toRunApiQuery(
  filters: AdminRunDiagnosticFiltersView,
  page: number,
): AdminRunDiagnosticListQuery {
  return {
    runId: filters.runId?.trim() || undefined,
    conversationId: filters.conversationId?.trim() || undefined,
    status: filters.status,
    stableErrorCode: filters.stableErrorCode?.trim().toUpperCase() || undefined,
    traceId: filters.traceId?.trim().toLowerCase() || undefined,
    from: apiDate(filters.from),
    to: apiDate(filters.to),
    page,
    size: PAGE_SIZE,
  }
}

export function AdminTaskServicePage() {
  const [activeTab, setActiveTab] = useState<'TASKS' | 'RUNS'>('TASKS')
  const [filters, setFilters] = useState<AdminTaskDiagnosticFiltersView>({})
  const [page, setPage] = useState(1)
  const [selectedTaskId, setSelectedTaskId] = useState<string>()
  const [runFilters, setRunFilters] = useState<AdminRunDiagnosticFiltersView>({})
  const [runPage, setRunPage] = useState(1)
  const [selectedRunId, setSelectedRunId] = useState<string>()
  const listQuery = useAdminTaskDiagnostics(
    toApiQuery(filters, page),
    activeTab === 'TASKS',
  )
  const detailQuery = useAdminTaskDiagnostic(
    selectedTaskId,
    activeTab === 'TASKS',
  )
  const runListQuery = useAdminRunDiagnostics(
    toRunApiQuery(runFilters, runPage),
    activeTab === 'RUNS',
  )
  const runDetailQuery = useAdminRunDiagnostic(
    selectedRunId,
    activeTab === 'RUNS',
  )

  const selectTab = (tab: 'TASKS' | 'RUNS') => {
    setActiveTab(tab)
    if (tab === 'TASKS') setSelectedRunId(undefined)
    else setSelectedTaskId(undefined)
  }

  const openLinkedTask = (taskId: string) => {
    setSelectedRunId(undefined)
    setSelectedTaskId(taskId)
    setActiveTab('TASKS')
  }

  return (
    <AdminShell>
      <section className="admin-workspace" aria-labelledby="admin-diagnostics-title">
        <header className="admin-workspace__heading">
          <div>
            <Typography.Title id="admin-diagnostics-title" level={1}>任务诊断</Typography.Title>
            <Typography.Text type="secondary">查询付费任务和 AI 对话 Run 的安全诊断数据</Typography.Text>
          </div>
          <Tag icon={<SafetyCertificateOutlined />} color="red">管理员只读</Tag>
        </header>
        <nav className="admin-workspace-tabs" aria-label="诊断类型">
          <button type="button" className={activeTab === 'TASKS' ? 'is-active' : ''} aria-current={activeTab === 'TASKS' ? 'page' : undefined} onClick={() => selectTab('TASKS')}>付费任务诊断</button>
          <button type="button" className={activeTab === 'RUNS' ? 'is-active' : ''} aria-current={activeTab === 'RUNS' ? 'page' : undefined} onClick={() => selectTab('RUNS')}>AI 对话 Run</button>
        </nav>
        {activeTab === 'TASKS' ? (
          <AdminTaskDiagnosticsWorkspace
            embedded
            tasks={listQuery.data?.items ?? []}
            total={listQuery.data?.total ?? 0}
            page={listQuery.data?.page ?? page}
            pageSize={listQuery.data?.size ?? PAGE_SIZE}
            loading={listQuery.isLoading}
            error={diagnosticError(listQuery.error)}
            filters={filters}
            selectedTaskId={selectedTaskId}
            detail={detailQuery.data}
            detailLoading={detailQuery.isLoading}
            detailError={diagnosticError(detailQuery.error)}
            onFiltersChange={(next) => { setFilters(next); setPage(1) }}
            onPageChange={setPage}
            onOpenTask={setSelectedTaskId}
            onCloseTask={() => setSelectedTaskId(undefined)}
            onRetryList={() => void listQuery.refetch()}
            onRetryDetail={() => void detailQuery.refetch()}
          />
        ) : (
          <AdminRunDiagnosticsWorkspace
            embedded
            runs={runListQuery.data?.items ?? []}
            total={runListQuery.data?.total ?? 0}
            page={runListQuery.data?.page ?? runPage}
            pageSize={runListQuery.data?.size ?? PAGE_SIZE}
            loading={runListQuery.isLoading}
            error={diagnosticError(runListQuery.error)}
            filters={runFilters}
            selectedRunId={selectedRunId}
            detail={runDetailQuery.data}
            detailLoading={runDetailQuery.isLoading}
            detailError={diagnosticError(runDetailQuery.error)}
            onFiltersChange={(next) => { setRunFilters(next); setRunPage(1) }}
            onPageChange={setRunPage}
            onOpenRun={setSelectedRunId}
            onCloseRun={() => setSelectedRunId(undefined)}
            onOpenLinkedTask={openLinkedTask}
            onRetryList={() => void runListQuery.refetch()}
            onRetryDetail={() => void runDetailQuery.refetch()}
          />
        )}
      </section>
    </AdminShell>
  )
}

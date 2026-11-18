import type { components, operations } from './generated/browser-api'
import { ApiError } from './apiError'
import { apiRequest } from './client'

export type AdminTaskDiagnosticListQuery = NonNullable<
  operations['listAdminTaskDiagnostics']['parameters']['query']
>
export type TaskDiagnosticSummaryDto = components['schemas']['TaskDiagnosticSummary']
export type TaskDiagnosticDto = components['schemas']['TaskDiagnostic']
export type TaskDiagnosticPageDto = components['schemas']['TaskDiagnosticPage']
export type AdminRunDiagnosticListQuery = NonNullable<
  operations['listAdminRunDiagnostics']['parameters']['query']
>
export type RunDiagnosticSummaryDto = components['schemas']['RunDiagnosticSummary']
export type RunDiagnosticDto = components['schemas']['RunDiagnostic']
export type RunDiagnosticPageDto = components['schemas']['RunDiagnosticPage']

type TaskDiagnosticPageApiResponse =
  components['schemas']['TaskDiagnosticPageApiResponse']
type TaskDiagnosticApiResponse = components['schemas']['TaskDiagnosticApiResponse']
type RunDiagnosticPageApiResponse =
  components['schemas']['RunDiagnosticPageApiResponse']
type RunDiagnosticApiResponse = components['schemas']['RunDiagnosticApiResponse']

function requireData<T>(data: T | undefined, resource: string): T {
  if (data === undefined) {
    throw new ApiError({
      status: 200,
      code: 'INVALID_RESPONSE',
      message: `${resource}返回了无法识别的数据。`,
    })
  }
  return data
}

function listPath(query: AdminTaskDiagnosticListQuery): string {
  const search = new URLSearchParams()
  if (query.taskId !== undefined) search.set('taskId', query.taskId)
  if (query.taskType !== undefined) search.set('taskType', query.taskType)
  if (query.taskStatus !== undefined) search.set('taskStatus', query.taskStatus)
  if (query.stableErrorCode !== undefined) {
    search.set('stableErrorCode', query.stableErrorCode)
  }
  if (query.traceId !== undefined) search.set('traceId', query.traceId)
  if (query.from !== undefined) search.set('from', query.from)
  if (query.to !== undefined) search.set('to', query.to)
  if (query.page !== undefined) search.set('page', String(query.page))
  if (query.size !== undefined) search.set('size', String(query.size))
  const value = search.toString()
  return value
    ? `/api/v1/admin/diagnostics/tasks?${value}`
    : '/api/v1/admin/diagnostics/tasks'
}

export async function listAdminTaskDiagnostics(
  query: AdminTaskDiagnosticListQuery = {},
): Promise<TaskDiagnosticPageDto> {
  const response = await apiRequest<TaskDiagnosticPageApiResponse>(listPath(query), {
    method: 'GET',
    auth: 'required',
  })
  return requireData(response.data, '任务诊断列表')
}

export async function getAdminTaskDiagnostic(
  taskId: string,
): Promise<TaskDiagnosticDto> {
  const response = await apiRequest<TaskDiagnosticApiResponse>(
    `/api/v1/admin/diagnostics/tasks/${encodeURIComponent(taskId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, '任务诊断详情')
}

function runListPath(query: AdminRunDiagnosticListQuery): string {
  const search = new URLSearchParams()
  if (query.runId !== undefined) search.set('runId', query.runId)
  if (query.conversationId !== undefined) {
    search.set('conversationId', query.conversationId)
  }
  if (query.status !== undefined) search.set('status', query.status)
  if (query.stableErrorCode !== undefined) {
    search.set('stableErrorCode', query.stableErrorCode)
  }
  if (query.traceId !== undefined) search.set('traceId', query.traceId)
  if (query.from !== undefined) search.set('from', query.from)
  if (query.to !== undefined) search.set('to', query.to)
  if (query.page !== undefined) search.set('page', String(query.page))
  if (query.size !== undefined) search.set('size', String(query.size))
  const value = search.toString()
  return value
    ? `/api/v1/admin/diagnostics/runs?${value}`
    : '/api/v1/admin/diagnostics/runs'
}

export async function listAdminRunDiagnostics(
  query: AdminRunDiagnosticListQuery = {},
): Promise<RunDiagnosticPageDto> {
  const response = await apiRequest<RunDiagnosticPageApiResponse>(
    runListPath(query),
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, 'Run 诊断列表')
}

export async function getAdminRunDiagnostic(
  runId: string,
): Promise<RunDiagnosticDto> {
  const response = await apiRequest<RunDiagnosticApiResponse>(
    `/api/v1/admin/diagnostics/runs/${encodeURIComponent(runId)}`,
    { method: 'GET', auth: 'required' },
  )
  return requireData(response.data, 'Run 诊断详情')
}

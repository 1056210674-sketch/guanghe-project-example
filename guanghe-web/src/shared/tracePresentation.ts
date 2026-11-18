import { canViewDiagnosticTrace, useAuthStore } from '../stores/authStore'

/** Keep the API error intact; only an authenticated administrator sees its trace in UI text. */
export function visibleDiagnosticTraceId(traceId?: string): string | undefined {
  return canViewDiagnosticTrace(useAuthStore.getState()) ? traceId : undefined
}

export function diagnosticTraceDescription(traceId?: string): string | undefined {
  const visibleTraceId = visibleDiagnosticTraceId(traceId)
  return visibleTraceId ? `traceId：${visibleTraceId}` : undefined
}

export function errorTextWithDiagnosticTrace(message: string, traceId?: string): string {
  const description = diagnosticTraceDescription(traceId)
  return description ? `${message}（${description}）` : message
}

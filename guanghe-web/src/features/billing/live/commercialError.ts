import { isApiError } from '../../../api/apiError'
import { errorTextWithDiagnosticTrace } from '../../../shared/tracePresentation'

/**
 * The one place a failed commercial request becomes text.
 *
 * The server's own message is shown. Diagnostic trace ids are only appended for administrators.
 */
export function errorText(error: unknown, fallback = '请求失败，请稍后重试。'): string {
  if (isApiError(error)) {
    return errorTextWithDiagnosticTrace(error.message, error.traceId)
  }
  return fallback
}

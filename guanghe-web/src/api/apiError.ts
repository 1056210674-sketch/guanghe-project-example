export interface ApiErrorInit {
  status: number
  code: string
  message: string
  details?: Record<string, unknown>
  traceId?: string
  clientTraceId?: string
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: Record<string, unknown>
  readonly traceId?: string
  readonly clientTraceId?: string

  constructor({
    status,
    code,
    message,
    details = {},
    traceId,
    clientTraceId,
  }: ApiErrorInit) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
    this.traceId = traceId
    this.clientTraceId = clientTraceId
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}

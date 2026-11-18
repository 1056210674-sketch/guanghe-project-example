interface FailureRule {
  matches: (code: string) => boolean
  message: string
  retryMessage?: string
}

function equalsOrStartsWith(code: string, values: readonly string[]): boolean {
  return values.some((value) => code === value || code.startsWith(`${value}_`))
}

const FAILURE_RULES: readonly FailureRule[] = [
  {
    matches: (code) =>
      ['INSUFFICIENT_POINTS', 'POINTS_INSUFFICIENT'].includes(code),
    message: '可用积分不足，请充值或调整任务后重试。',
  },
  {
    matches: (code) =>
      /^MEDIA(?:_[A-Z0-9]+)*_REJECTED$/.test(code) ||
      ['MEDIA_REJECTED', 'SUBMISSION_REJECTED'].includes(code),
    message:
      '媒体未受理本次投稿，请检查媒体要求或调整内容后重新选择媒体。',
  },
  {
    matches: (code) =>
      equalsOrStartsWith(code, [
        'CONTENT_REJECTED',
        'CONTENT_SAFETY',
        'MODERATION',
        'SAFETY_CHECK',
        'POLICY_VIOLATION',
      ]),
    message: '内容未通过安全检查，请调整描述或素材后重试。',
  },
  {
    matches: (code) =>
      [
        'CONFIRMATION_EXPIRED',
        'DRAFT_EXPIRED',
        'QUOTE_EXPIRED',
        'TASK_DRAFT_EXPIRED',
      ].includes(code),
    message: '确认信息已过期，请刷新后重新确认。',
  },
  {
    matches: (code) =>
      ['GEO_PROVIDER_RATE_LIMITED', 'GEO_YUANBAO_RATE_LIMITED'].includes(code),
    message: '上游接口触发限流，本条未取得回答。',
  },
  {
    matches: (code) =>
      equalsOrStartsWith(code, ['RATE_LIMIT', 'TOO_MANY_REQUESTS']),
    message: '当前请求较多，请稍后重试。',
  },
  {
    matches: (code) =>
      equalsOrStartsWith(code, [
        'ASSET_INGESTION',
        'ASSET_STORAGE',
        'ASSET_UPLOAD',
      ]),
    message: '结果暂未完成保存，请稍后刷新任务状态。',
  },
  {
    matches: (code) =>
      equalsOrStartsWith(code, ['POINT', 'POINTS', 'BILLING', 'SETTLEMENT']),
    message: '积分结算暂未完成，请稍后刷新任务状态；不要重复提交。',
  },
  {
    matches: (code) =>
      equalsOrStartsWith(code, [
        'VALIDATION',
        'PARAMETER_INVALID',
        'INVALID_PARAMETER',
      ]),
    message: '当前任务参数无法执行，请调整要求后重试。',
  },
  {
    matches: (code) =>
      ['TASK_CANCELLED', 'TASK_CANCELED', 'TASK_SKIPPED'].includes(code),
    message: '任务未继续执行；如仍需要，请重新发起。',
  },
  {
    matches: (code) => code === 'SYSTEM_TERMINATED',
    message: '任务因系统保护机制中止，请重新发起；如重复发生，请联系支持。',
  },
  {
    matches: (code) =>
      equalsOrStartsWith(code, [
        'PROVIDER',
        'WORKER',
        'SERVICE_UNAVAILABLE',
        'NETWORK',
        'CONNECTION',
        'UPSTREAM_TIMEOUT',
      ]),
    message: '执行服务暂时异常，本次任务未完成，请稍后重新发起。',
    retryMessage:
      '执行暂时中断，任务尚未完成。请稍后刷新状态；若长时间没有更新，请联系支持，暂勿重复发起。',
  },
]

const UNKNOWN_FAILURE_MESSAGE =
  '任务执行未完成，请稍后重试；如重复失败，请联系支持。'

export interface TaskFailurePresentationContext {
  status?: string | null
  retryable?: boolean
}

const EXPLICIT_RETRY_STATUSES = new Set([
  'RETRY_WAIT',
  'RETRYING',
  'RECONCILING',
  'SUBMIT_UNKNOWN',
])

function retryWillContinue(context: TaskFailurePresentationContext): boolean {
  if (context.retryable !== undefined) return context.retryable
  return context.status
    ? EXPLICIT_RETRY_STATUSES.has(context.status.trim().toUpperCase())
    : false
}

export function taskFailureMessage(
  errorCode?: string | null,
  context: TaskFailurePresentationContext = {},
): string | undefined {
  if (!errorCode) return undefined
  const normalized = errorCode.trim().toUpperCase()
  const match = FAILURE_RULES.find((entry) => entry.matches(normalized))
  if (match?.retryMessage && retryWillContinue(context)) {
    return match.retryMessage
  }
  return match?.message ?? UNKNOWN_FAILURE_MESSAGE
}

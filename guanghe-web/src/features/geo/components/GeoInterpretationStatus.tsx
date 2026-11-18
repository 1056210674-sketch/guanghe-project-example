import { ReloadOutlined } from '@ant-design/icons'
import { Alert, Button, Space, Typography } from 'antd'
import type { GeoReportDetailView } from '../types'
import { formatGeoDateTime } from '../presentation'

interface GeoInterpretationStatusProps {
  report: GeoReportDetailView
  requesting?: boolean
  requestError?: string
  refreshFailed?: boolean
  onGenerate?: () => void
  onRefresh?: () => void
}

const unavailableMessages: Record<string, string> = {
  ARCHIVED: '这份报告已归档，请先恢复报告，再生成解读。',
  NOT_TERMINAL: '本次监测尚未结束，请等回答收集完成后再生成解读。',
  NO_VALID_ANSWERS: '本次没有取得有效回答，无法生成解读。重新监测属于新任务，需要另行确认报价。',
  ATTEMPT_LIMIT_REACHED: '本报告的解读重试次数已用完。已保存的回答不受影响，请联系支持处理，无需为恢复解读重新采集。',
  SERVICE_UNAVAILABLE: '解读服务暂时无法处理请求。已保存的回答不受影响，请稍后刷新状态再试。',
  UNSUPPORTED_REPORT: '这份历史报告的数据格式暂不支持恢复解读，已保存的回答仍可查看。请联系支持处理。',
}

const failureMessages: Record<string, string> = {
  GEO_INTERPRETATION_TIMEOUT: '本次解读等待时间过长，未能完成。',
  GEO_INTERPRETATION_PROVIDER_UNAVAILABLE: '本次解读服务未能返回结果。',
  GEO_INTERPRETATION_INVALID_RESULT: '解读结果未通过完整性校验，没有写入报告。',
  GEO_INTERPRETATION_INPUT_CHANGED: '报告数据已变化，本次解读结果没有写入。',
  GEO_INTERPRETATION_REPORT_UNAVAILABLE: '这份报告暂时无法更新解读。',
  GEO_INTERPRETATION_LEASE_EXPIRED: '本次解读处理意外中断，未能完成。',
  GEO_INTERPRETATION_CAPACITY_FULL: '解读服务繁忙，稍后可重试。本次未调用模型，不消耗解读尝试次数。',
}

export function GeoInterpretationStatus({
  report,
  requesting = false,
  requestError,
  refreshFailed = false,
  onGenerate,
  onRefresh,
}: GeoInterpretationStatusProps) {
  const interpretation = report.interpretation
  const hasConclusion = report.methodVersion !== 'geo-deterministic-v1'
  const active = interpretation?.status === 'QUEUED' || interpretation?.status === 'RUNNING'
  const busy = requesting || active
  const failed = interpretation?.status === 'FAILED'
  const capacityRefused = failed && interpretation.failureCode === 'GEO_INTERPRETATION_CAPACITY_FULL'
  const blockedReason = interpretation?.unavailableReason
  const hasAnswers = (report.sampleScope?.succeeded ?? 0) > 0 && blockedReason !== 'NO_VALID_ANSWERS'
  const blockedMessage = blockedReason ? unavailableMessages[blockedReason] : undefined
  const failureMessage = interpretation?.failureCode ? failureMessages[interpretation.failureCode] : undefined
  const canGenerate = !hasConclusion && hasAnswers && interpretation?.canRetry
  const remaining = interpretation ? Math.max(0, interpretation.maxAttempts - interpretation.attempts) : 0

  if (hasConclusion && !active && !requesting && !failed) {
    return interpretation?.finishedAt ? (
      <Typography.Paragraph type="secondary" className="geo-interpretation-status--complete">
        智能解读已完成 · {formatGeoDateTime(interpretation.finishedAt)}（北京时间）
      </Typography.Paragraph>
    ) : null
  }

  const title = busy
    ? interpretation?.status === 'QUEUED' ? '智能解读已排队' : '正在根据已保存的回答生成解读'
    : !hasAnswers ? '本次尚未取得有效回答'
      : failed ? '本次智能解读未完成，回答与可见度结果已保留'
        : interpretation?.status === 'SUCCEEDED' ? '解读已完成，请刷新查看结果'
          : '已有回答可用于生成智能解读'

  return (
    <Alert
      type={failed && !busy ? 'warning' : 'info'}
      showIcon
      title={title}
      description={
        <Space orientation="vertical" size={8}>
          <Typography.Text>仅使用本报告已保存的回答，不重新采集，不扣新积分。</Typography.Text>
          {busy ? <Typography.Text>你可以继续查看下方回答，也可以离开此页，稍后回来查看结果。{hasConclusion ? '更新期间继续保留原有结论。' : ''}</Typography.Text> : null}
          {!busy && blockedMessage && !(capacityRefused && blockedReason === 'SERVICE_UNAVAILABLE') ? <Typography.Text>{blockedMessage}</Typography.Text> : null}
          {!busy && !hasAnswers && !blockedMessage ? <Typography.Text>没有有效回答不能形成智能结论。可检查平台采集情况，再决定是否另行发起监测。</Typography.Text> : null}
          {!busy && failed ? <Typography.Text>{failureMessage ?? '本次未能完成解读。'}{capacityRefused ? '' : '这是解读步骤失败，不代表平台没有提及商品。'}</Typography.Text> : null}
          {!busy && capacityRefused && interpretation.retryAfter ? <Typography.Text>稍后将自动刷新可重试状态，不会自动提交解读。</Typography.Text> : null}
          {!busy && failed && hasConclusion ? <Typography.Text>本次更新没有替换已保存的结论。</Typography.Text> : null}
          {!busy && requestError ? <Typography.Text type="danger">{requestError}</Typography.Text> : null}
          {!busy && canGenerate && !capacityRefused ? <Typography.Text type="secondary">本报告还可尝试 {remaining} 次。</Typography.Text> : null}
          {!interpretation ? <Typography.Text>请刷新报告以获取解读服务状态。</Typography.Text> : null}
          <Space wrap>
            {(canGenerate || busy) && !hasConclusion ? (
              <Button
                type="primary"
                aria-label={busy ? '正在生成解读' : failed ? '重新解读' : '生成智能解读'}
                icon={<ReloadOutlined />}
                loading={busy}
                disabled={busy || refreshFailed || !onGenerate}
                onClick={onGenerate}
              >
                {busy ? '正在生成解读' : failed ? '重新解读' : '生成智能解读'}
              </Button>
            ) : null}
            {(!interpretation || (!busy && blockedReason === 'SERVICE_UNAVAILABLE') || (!hasConclusion && interpretation?.status === 'SUCCEEDED')) && onRefresh ? (
              <Button onClick={onRefresh}>刷新解读状态</Button>
            ) : null}
          </Space>
        </Space>
      }
    />
  )
}

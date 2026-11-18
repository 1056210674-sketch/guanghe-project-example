import { Alert, Button, Progress, Space, Typography } from 'antd'

const labels: Record<string, string> = {
  SAMPLING: '正在跨平台采样', ANALYSIS_QUEUED: '等待调研分析', ANALYSIS_RUNNING: '正在分析诊断证据',
  ANALYSIS_FAILED: '调研分析暂未完成', PDF_QUEUED: '等待生成 PDF', PDF_RUNNING: '正在排版 PDF',
  PDF_FAILED: 'PDF 暂未生成完成', READY: '诊断报告已交付',
}

/** Delivery progress is not a chat reply and never controls the composer. */
export function DiagnosticDeliveryProgress({ status, progress, noData, partial, canRetry, retrying, error, onRetry, onReport, reportHref, onRefresh }: {
  status: string; progress?: number; noData: boolean; partial: boolean; canRetry: boolean; retrying?: boolean; error?: string
  onRetry?: () => void; onReport?: () => void; reportHref?: string; onRefresh?: () => void
}) {
  const failed = status === 'ANALYSIS_FAILED' || status === 'PDF_FAILED'
  return <section className="geo-diagnostic-delivery" aria-label="诊断后台任务进度">
    <Typography.Text strong>{noData ? '监测未获取有效样本' : labels[status] ?? '正在读取诊断任务状态'}</Typography.Text>
    {progress !== undefined ? <Progress percent={Math.max(0, Math.min(100, progress))} status={failed || noData ? 'exception' : status === 'READY' ? 'success' : 'normal'} /> : null}
    <Typography.Paragraph type="secondary">诊断已提交到后台。你可以继续聊天或离开页面，重新打开会恢复进度。已提交的资料与采样配置不会再修改。</Typography.Paragraph>
    {noData ? <Alert type="warning" showIcon title={status === 'READY' ? '已生成失败说明 PDF，不代表诊断成功。' : '本次没有有效样本，不展示可见度或口碑结论。'} description="失败与未采集回答不会算成品牌未被提及，也不会伪造优化建议。结算以服务端记录为准。" />
      : partial ? <Alert type="warning" showIcon title="部分平台样本失败或缺失，报告会保留失败范围与限制。" /> : null}
    {failed ? <Alert type="warning" showIcon title={labels[status]} description="已保留监测数据。恢复报告仅继续分析或 PDF 排版，不重新监测、不再次收取监测积分。" /> : null}
    {error ? <Alert type="error" showIcon title={error} /> : null}
    <Space wrap>
      {onReport || reportHref ? <Button type="primary" href={reportHref} onClick={onReport}>查看详细诊断报告</Button> : null}
      {canRetry && onRetry ? <Button loading={retrying} onClick={onRetry}>恢复生成报告</Button> : null}
      {onRefresh ? <Button disabled={retrying} onClick={onRefresh}>刷新后台进度</Button> : null}
    </Space>
  </section>
}

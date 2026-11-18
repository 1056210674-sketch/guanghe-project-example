import { Tag } from 'antd'
import type {
  GeoPlanStatus,
  GeoReportStatus,
  GeoRunStatus,
  GeoSampleAnalysisStatus,
  GeoSampleExecutionStatus,
} from '../types'

type GeoStatus =
  | GeoRunStatus
  | GeoPlanStatus
  | GeoReportStatus
  | GeoSampleExecutionStatus
  | GeoSampleAnalysisStatus

const STATUS_LABELS: Record<GeoStatus, string> = {
  ACTIVE: '运行中',
  PAUSED: '已暂停',
  QUEUED: '排队中',
  RUNNING: '执行中',
  RETRY_WAIT: '等待重试',
  SUCCEEDED: '已完成',
  PARTIAL: '部分完成',
  FAILED: '失败',
  SKIPPED: '本次已跳过',
  SYSTEM_TERMINATED: '系统终止',
  PENDING: '待处理',
  MENTIONED: '已提及',
  NOT_MENTIONED: '未提及',
}
function statusColor(status: GeoStatus) {
  switch (status) {
    case 'ACTIVE':
    case 'SUCCEEDED':
    case 'MENTIONED':
      return 'success'
    case 'PARTIAL':
    case 'RETRY_WAIT':
    case 'SKIPPED':
    case 'PAUSED':
      return 'warning'
    case 'FAILED':
    case 'SYSTEM_TERMINATED':
      return 'error'
    case 'QUEUED':
    case 'RUNNING':
      return 'processing'
    case 'PENDING':
    case 'NOT_MENTIONED':
      return 'default'
  }
}

export function GeoStatusTag({ status }: { status: GeoStatus }) {
  return <Tag color={statusColor(status)}>{STATUS_LABELS[status]}</Tag>
}

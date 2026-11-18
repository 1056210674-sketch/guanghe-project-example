import {
  CloseCircleOutlined,
  FileSearchOutlined,
  LockOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import { Button, Card, Empty, Skeleton, Spin, Typography } from 'antd'
import type { ReactNode } from 'react'
import { TraceId } from './TraceId'

interface StateCardProps {
  icon: ReactNode
  title: string
  description: string
  action?: ReactNode
  traceId?: string
}

export function StateCard({
  icon,
  title,
  description,
  action,
  traceId,
}: StateCardProps) {
  return (
    <Card className="state-card" bordered>
      <div className="state-card__icon" aria-hidden="true">
        {icon}
      </div>
      <Typography.Title level={3}>{title}</Typography.Title>
      <Typography.Paragraph type="secondary">
        {description}
      </Typography.Paragraph>
      <TraceId traceId={traceId} />
      {action ? <div className="state-card__action">{action}</div> : null}
    </Card>
  )
}

export function PageLoading({ label = '正在加载，请稍候…' }: { label?: string }) {
  return (
    <div className="page-loading" role="status" aria-live="polite">
      <Card className="page-loading__card">
        <Spin size="large" />
        <Typography.Text>{label}</Typography.Text>
        <Skeleton active paragraph={{ rows: 3 }} title={false} />
      </Card>
    </div>
  )
}

interface ErrorStateProps {
  message?: string
  traceId?: string
  onRetry?: () => void
}

export function ErrorState({
  message = '请求失败，请稍后重试。',
  traceId,
  onRetry,
}: ErrorStateProps) {
  return (
    <div className="state-surface">
      <StateCard
        icon={<CloseCircleOutlined />}
        title="服务暂不可用"
        description={message}
        traceId={traceId}
        action={
          onRetry ? (
            <Button type="primary" icon={<ReloadOutlined />} onClick={onRetry}>
              重新加载
            </Button>
          ) : undefined
        }
      />
    </div>
  )
}

interface EmptyStateProps {
  title: string
  description: string
  action?: ReactNode
}

export function EmptyState({ title, description, action }: EmptyStateProps) {
  return (
    <div className="empty-state">
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={
          <div className="empty-state__copy">
            <Typography.Text strong>{title}</Typography.Text>
            <Typography.Text type="secondary">{description}</Typography.Text>
          </div>
        }
      >
        {action}
      </Empty>
    </div>
  )
}

export function ForbiddenState({ onBack }: { onBack: () => void }) {
  return (
    <div className="state-surface">
      <StateCard
        icon={<LockOutlined />}
        title="403 拒绝访问"
        description="您没有权限访问此页面。"
        action={
          <Button type="primary" onClick={onBack}>
            返回可访问页面
          </Button>
        }
      />
    </div>
  )
}

export function NotFoundState({ onBack }: { onBack: () => void }) {
  return (
    <div className="state-surface">
      <StateCard
        icon={<FileSearchOutlined />}
        title="404 页面未找到"
        description="抱歉，您访问的页面不存在。"
        action={
          <Button type="primary" onClick={onBack}>
            返回首页
          </Button>
        }
      />
    </div>
  )
}

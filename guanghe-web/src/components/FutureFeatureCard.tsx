import { LockOutlined } from '@ant-design/icons'
import { Card, Tag, Typography } from 'antd'
import type { ReactNode } from 'react'

interface FutureFeatureCardProps {
  icon: ReactNode
  title: string
  description: string
}

export function FutureFeatureCard({
  icon,
  title,
  description,
}: FutureFeatureCardProps) {
  return (
    <Card className="future-card" aria-disabled="true">
      <div className="future-card__icon" aria-hidden="true">
        {icon}
      </div>
      <div className="future-card__body">
        <Typography.Title level={5}>{title}</Typography.Title>
        <Typography.Text type="secondary">{description}</Typography.Text>
      </div>
      <Tag icon={<LockOutlined />} bordered={false}>
        尚未接入
      </Tag>
    </Card>
  )
}

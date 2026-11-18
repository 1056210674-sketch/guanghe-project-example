import { ClockCircleOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Space, Tag, Typography } from 'antd'
import { diagnosticTraceDescription } from '../../../shared/tracePresentation'
import { formatGeoPoints, formatGeoSchedule } from '../presentation'
import type { GeoPlanAuthorizationView, GeoViewError } from '../types'

export interface GeoPlanAuthorizationCardProps {
  authorization: GeoPlanAuthorizationView
  confirming: boolean
  error?: GeoViewError | null
  onBack?: () => void
  onConfirm?: (draftId: string) => void
}

export function GeoPlanAuthorizationCard({
  authorization,
  confirming,
  error,
  onBack,
  onConfirm,
}: GeoPlanAuthorizationCardProps) {
  return (
    <Card className="geo-confirm-card" aria-label="自动监测计划授权确认">
      <div className="geo-confirm-card__headline">
        <SafetyCertificateOutlined />
        <div>
          <Typography.Title level={3}>确认自动监测计划</Typography.Title>
          <Typography.Text type="secondary">
            本次确认只创建计划，不立即执行监测，也不立即冻结或扣除积分。
          </Typography.Text>
        </div>
      </div>

      {error ? (
        <Alert
          type="error"
          showIcon
          title={error.message}
          description={diagnosticTraceDescription(error.traceId)}
        />
      ) : null}

      <dl className="geo-summary-list">
        <div><dt>计划名称</dt><dd>{authorization.title}</dd></div>
        <div><dt>监测专题</dt><dd>{authorization.topicName}</dd></div>
        <div><dt>监测模式</dt><dd>{authorization.mode === 'DEEP' ? '深度监测' : '标准监测'}</dd></div>
        <div><dt>执行周期</dt><dd>{formatGeoSchedule(authorization.schedule)}</dd></div>
        <div><dt>问题范围</dt><dd>{authorization.questionLabels.join('、')}</dd></div>
        <div>
          <dt>平台范围</dt>
          <dd><Space size={[4, 4]} wrap>{authorization.platformNames.map((name) => <Tag key={name}>{name}</Tag>)}</Space></dd>
        </div>
        <div><dt>预计单次积分</dt><dd>{formatGeoPoints(authorization.estimatedPointsPerRun)}</dd></div>
        <div><dt>单次积分上限</dt><dd>{formatGeoPoints(authorization.maxPointsPerRun)}</dd></div>
        <div><dt>定价版本</dt><dd>{authorization.pricingVersionId}</dd></div>
        <div><dt>授权有效期</dt><dd><ClockCircleOutlined /> {authorization.expiresAt}</dd></div>
      </dl>

      <Alert
        type="warning"
        showIcon
        title="每个计划窗口执行前会按届时有效范围与价格校验"
        description="额度不足、报价超过单次上限或资料失效时，由服务端按正式规则跳过；恢复计划从下一个时间窗口开始，不补跑历史窗口。"
      />
      <div className="geo-confirm-card__actions">
        <Button disabled={confirming || !onBack} onClick={onBack}>返回修改</Button>
        <Button
          type="primary"
          loading={confirming}
          disabled={confirming || !authorization.confirmEnabled || !onConfirm}
          onClick={() => onConfirm?.(authorization.draftId)}
        >
          确认并创建计划
        </Button>
      </div>
    </Card>
  )
}

import { Button, Progress, Typography } from 'antd'
import type { GeoPlatformPerformanceView } from '../types'
import { GeoPlatformBadge } from './GeoPlatformBadge'

/** Values and denominators come from the server; this component only formats them. */
export function GeoPlatformVisibility({ platforms, onSelectPlatform }: {
  platforms: readonly GeoPlatformPerformanceView[]
  onSelectPlatform?: (platformCode: string) => void
}) {
  if (!platforms.length) return <Typography.Paragraph type="secondary">还没有平台监测结果。</Typography.Paragraph>
  return <div className="geo-visibility-list">
    {platforms.map((item) => <article className="geo-visibility-row" key={item.platform.code}>
      <div className="geo-visibility-row__heading">
        <GeoPlatformBadge platform={item.platform} compact />
        <Typography.Text strong className="geo-visibility-row__value">{item.primaryValue}</Typography.Text>
      </div>
      {item.percentage !== undefined ? <Progress percent={item.percentage} showInfo={false} strokeColor="#df493f" aria-label={`${item.platform.name} AI 可见度 ${item.primaryValue}`} /> : null}
      <Typography.Text>{item.evidenceLabel ?? item.primaryLabel ?? '未评估'}</Typography.Text>
      {item.unavailableLabel ? <Typography.Text type="secondary">{item.unavailableLabel}</Typography.Text> : null}
      {onSelectPlatform ? <Button type="link" disabled={!item.canViewEvidence} aria-label={`查看${item.platform.name}的回答`} onClick={() => onSelectPlatform(item.platform.code)}>查看平台回答</Button> : null}
    </article>)}
  </div>
}

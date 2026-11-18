import { CheckCircleOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Checkbox, Input, InputNumber, Select, Typography } from 'antd'
import { useState } from 'react'
import type {
  ArticleBodyConfirmationInput,
  ArticleSubmissionGoal,
} from '../types'

const GOALS: readonly { value: ArticleSubmissionGoal; label: string }[] = [
  { value: 'BALANCED', label: '综合匹配' },
  { value: 'INDUSTRY_MATCH', label: '行业匹配优先' },
  { value: 'COST_EFFECTIVE', label: '性价比优先' },
  { value: 'BROAD_REACH', label: '广泛触达优先' },
]

export interface ArticleBodyConfirmationCardProps {
  busy?: boolean
  initialValue?: Partial<ArticleBodyConfirmationInput>
  title?: string
  onConfirm: (input: ArticleBodyConfirmationInput) => void
}

export function ArticleBodyConfirmationCard({
  busy,
  initialValue,
  title = '确认正文并设置投稿目标',
  onConfirm,
}: ArticleBodyConfirmationCardProps) {
  const [goal, setGoal] = useState<ArticleSubmissionGoal>(
    initialValue?.goal ?? 'BALANCED',
  )
  const [preferredRegion, setPreferredRegion] = useState(
    initialValue?.preferredRegion ?? '全国',
  )
  const [maxTotalPoints, setMaxTotalPoints] = useState<number | undefined>(
    initialValue?.maxTotalPoints,
  )
  const [requiresLink, setRequiresLink] = useState(
    initialValue?.requiresLink ?? false,
  )

  return (
    <Card title={title} className="article-body-confirmation">
      <Alert
        type="info"
        showIcon
        title="这一步不会投稿，也不会扣积分"
        description="确认后，光禾AI智能体框架才会基于当前正文版本、商品品类和下面的约束筛选真实媒体。正文再次修改后需要重新确认。"
      />
      <div className="article-body-confirmation__fields">
        <label>
          <Typography.Text strong>推荐策略</Typography.Text>
          <Select
            aria-label="媒体推荐策略"
            value={goal}
            options={[...GOALS]}
            onChange={setGoal}
          />
        </label>
        <label>
          <Typography.Text strong>优先地域</Typography.Text>
          <Input
            aria-label="媒体优先地域"
            value={preferredRegion}
            maxLength={120}
            placeholder="例如：全国、北京；留空表示不限"
            onChange={(event) => setPreferredRegion(event.target.value)}
          />
        </label>
        <label>
          <Typography.Text strong>推荐组合总预算（积分）</Typography.Text>
          <InputNumber
            aria-label="推荐组合总预算"
            value={maxTotalPoints}
            min={1}
            precision={0}
            placeholder="留空表示不限"
            onChange={(value) => setMaxTotalPoints(value ?? undefined)}
          />
        </label>
        <Checkbox
          checked={requiresLink}
          onChange={(event) => setRequiresLink(event.target.checked)}
        >
          只推荐明确支持带链接的媒体
        </Checkbox>
      </div>
      <Button
        type="primary"
        icon={<CheckCircleOutlined aria-hidden="true" />}
        loading={busy}
        disabled={busy}
        onClick={() =>
          onConfirm({
            goal,
            preferredRegion: preferredRegion.trim() || undefined,
            maxTotalPoints,
            requiresLink,
          })
        }
      >
        确认正文并获取媒体推荐
      </Button>
    </Card>
  )
}

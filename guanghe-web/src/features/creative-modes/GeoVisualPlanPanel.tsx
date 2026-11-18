import { Alert, Button, Input, Typography } from 'antd'
import type { GeoCreativePlanResponse } from '../../api/geoApi'

interface GeoVisualPlanPanelProps {
  taskType: 'IMAGE' | 'VIDEO'
  userDirection: string
  onDirectionChange: (value: string) => void
  onGenerate: () => void
  canGenerate: boolean
  loading: boolean
  plan?: GeoCreativePlanResponse
  error?: string
}

/** Displays the evidence-to-creative decision before any paid draft is quoted. */
export function GeoVisualPlanPanel({
  taskType, userDirection, onDirectionChange, onGenerate, canGenerate, loading, plan, error,
}: GeoVisualPlanPanelProps) {
  return <div className="geo-visual-plan" aria-label="GEO 创意方案">
    <label htmlFor={`geo-${taskType.toLowerCase()}-direction`}>创作补充要求（选填）</label>
    <Input.TextArea
      id={`geo-${taskType.toLowerCase()}-direction`}
      aria-label="创作补充要求"
      value={userDirection}
      maxLength={500}
      showCount
      autoSize={{ minRows: 2, maxRows: 5 }}
      placeholder="例如：突出通勤携带，采用简洁明亮的画面风格。"
      onChange={(event) => onDirectionChange(event.target.value)}
    />
    <div className="geo-visual-plan__actions">
      <Button type="primary" loading={loading} disabled={!canGenerate || loading} onClick={onGenerate}>
        {plan ? '重新生成创意方案' : '生成创意方案'}
      </Button>
      <Typography.Text type="secondary">可在上方补充想要的风格或镜头方式。</Typography.Text>
    </div>
    {error ? <Alert type="error" showIcon title={error} /> : null}
    {plan ? <section className="geo-visual-plan__result" aria-label="已生成的 GEO 创意方案">
      <Typography.Title level={5}>创意方案与依据</Typography.Title>
      <dl>
        <div><dt>发现的机会</dt><dd>{plan.plan.opportunity}</dd></div>
        <div><dt>目标人群</dt><dd>{plan.plan.audience}</dd></div>
        <div><dt>核心信息</dt><dd>{plan.plan.message}</dd></div>
        <div><dt>画面场景</dt><dd>{plan.plan.scene}</dd></div>
        {taskType === 'IMAGE' ? <div><dt>画面重点</dt><dd>{plan.plan.visualFocus}</dd></div> : null}
      </dl>
      {taskType === 'VIDEO' ? <div><Typography.Text strong>镜头节奏</Typography.Text><ol>{plan.plan.shotBeats.map((beat, index) => <li key={`${index}-${beat}`}>{beat}</li>)}</ol></div> : null}
      <Typography.Text strong>来自监测回答的依据</Typography.Text>
      {plan.evidence.length === 0 ? <Typography.Text type="secondary">暂无监测回答摘要。</Typography.Text> : null}
      <ul className="geo-visual-plan__evidence">{plan.evidence.filter((item) => plan.plan.evidenceKeys.includes(item.key)).map((item) => <li key={item.key}>
        <strong>{item.platformCode} · {item.questionText}</strong>
        <span>{item.answerExcerpt}</span>
      </li>)}</ul>
    </section> : null}
  </div>
}

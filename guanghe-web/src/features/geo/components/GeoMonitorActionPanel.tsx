import { Alert, Button, Checkbox, Drawer, Empty, Input, Pagination, Radio, Select, Space, Typography } from 'antd'
import type { GeoPlatform, GeoTopic, GeoTopicSummary } from '../../../api/geoApi'
import { monitorExpiryIso, positiveMonitorPoints } from '../monitorActionEditor'
import type { GeoMonitorEditor } from '../monitorActionEditor'
import '../../../styles/geo-monitor-action.css'

interface GeoMonitorActionPanelProps {
  open: boolean
  loading: boolean
  busy: boolean
  editor: GeoMonitorEditor
  topics: readonly GeoTopicSummary[]
  topic?: GeoTopic
  topicPage: number
  topicTotal: number
  topicSearch: string
  platforms: readonly GeoPlatform[]
  quote?: { points: number; sampleCount: number }
  authorizationLabel?: string
  canRevoke: boolean
  canStart: boolean
  startLabel: string
  error?: string
  notice?: string
  admission?: { title: string; description: string; tone: 'info' | 'warning' | 'success'; taskId?: string }
  onClose: () => void
  onChange: (editor: GeoMonitorEditor) => void
  onSearch: (value: string) => void
  onPageChange: (page: number) => void
  onPreview: () => void
  onStart: () => void
  onRevoke: () => void
  onRetry: () => void
  onRetryPending?: () => void
  onViewTask: (taskId: string) => void
}

export function GeoMonitorActionPanel(props: GeoMonitorActionPanelProps) {
  const { editor, topic, busy, loading } = props
  const locked = busy || loading || Boolean(props.onRetryPending)
  const enabledQuestions = topic?.questions.filter((question) => question.enabled) ?? []
  const validRange = Boolean(topic && topic.id === editor.topicId && topic.region?.trim())
    && editor.questionIds.length >= 3 && editor.questionIds.length <= 10
    && new Set(editor.questionIds).size === editor.questionIds.length
    && editor.questionIds.every((id) => enabledQuestions.some((question) => question.id === id))
    && editor.platformCodes.length > 0 && new Set(editor.platformCodes).size === editor.platformCodes.length
    && editor.platformCodes.every((code) => props.platforms.some((platform) => platform.code === code && platform.selectable))
  const actionPoints = positiveMonitorPoints(editor.actionPoints)
  const dayPoints = positiveMonitorPoints(editor.dayPoints)
  const validBudget = actionPoints !== undefined && dayPoints !== undefined && actionPoints <= dayPoints
  const validExpiry = monitorExpiryIso(editor.expiresLocal) !== undefined
  const quoteValid = props.quote && Number.isSafeInteger(props.quote.points) && props.quote.points > 0
    && Number.isSafeInteger(props.quote.sampleCount) && props.quote.sampleCount > 0
  const patch = (value: Partial<GeoMonitorEditor>) => props.onChange({ ...editor, ...value })

  return <Drawer title="监测一次" open={props.open} onClose={props.onClose} size={560} className="geo-monitor-action-drawer">
    <Space orientation="vertical" size={20} className="geo-monitor-action-panel">
      <Typography.Paragraph type="secondary">选定已有专题，执行一次监测。不创建定时计划，也不自动生成或发布文章。</Typography.Paragraph>
      {props.error ? <Alert type="error" showIcon title={props.error} action={<Button disabled={busy} onClick={props.onRetry}>重新加载</Button>} /> : null}
      {props.notice ? <Alert type="info" showIcon title={props.notice} /> : null}
      {props.onRetryPending ? <Alert type="warning" showIcon title="上一条监测请求的发送结果尚未确认"
        description="先核对同一次请求，暂不修改范围或再次授权。"
        action={<Button disabled={busy || loading} onClick={props.onRetryPending}>重试同一次请求</Button>} /> : null}
      {props.admission ? <Alert type={props.admission.tone} showIcon title={props.admission.title} description={props.admission.description}
        action={props.admission.taskId ? <Button onClick={() => props.onViewTask(props.admission!.taskId!)}>查看任务</Button> : undefined} /> : null}

      <section className="geo-monitor-action-section" aria-label="本次监测范围">
        <Typography.Title level={5}>本次监测范围</Typography.Title>
        <Input.Search aria-label="搜索已有监测专题" placeholder="搜索专题名称" maxLength={100} defaultValue={props.topicSearch}
          disabled={locked} onSearch={props.onSearch} allowClear />
        <Select aria-label="已有监测专题" placeholder="选择已有专题" value={editor.topicId} loading={loading} disabled={locked}
          onChange={(topicId: string) => patch({ topicId, questionIds: [] })}
          options={props.topics.map((item) => ({ value: item.id, label: item.name }))} />
        {!loading && !props.error && props.topics.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有可选的已有专题，请先在 GEO 工作台建立专题与问题。" /> : null}
        {props.topicTotal > 20 ? <Pagination size="small" current={props.topicPage} pageSize={20} total={props.topicTotal}
          showSizeChanger={false} disabled={locked} onChange={props.onPageChange} /> : null}
        {topic ? <>
          <Typography.Text type={topic.region?.trim() ? 'secondary' : 'warning'}>地区：{topic.region?.trim() || '尚未设置，请先完善专题地区'}</Typography.Text>
          <Typography.Text>选择 3～10 个已启用的问题</Typography.Text>
          <Checkbox.Group className="geo-monitor-question-options" value={editor.questionIds} disabled={locked}
            onChange={(questionIds) => patch({ questionIds: questionIds.map(String) })}
            options={enabledQuestions.map((question) => ({ value: question.id, label: question.text }))} />
          {enabledQuestions.length < 3 ? <Typography.Text type="warning">本专题启用的问题不足 3 个，请先补充问题。</Typography.Text> : null}
        </> : null}
        <Typography.Text>监测平台</Typography.Text>
        <Checkbox.Group value={editor.platformCodes} disabled={locked}
          onChange={(codes) => patch({ platformCodes: props.platforms.filter((platform) => platform.selectable && codes.includes(platform.code)).map((platform) => platform.code) })}
          options={props.platforms.map((platform) => ({ value: platform.code, label: platform.name, disabled: !platform.selectable }))} />
        <Radio.Group aria-label="采样模式" value={editor.sampleMode} disabled={locked} onChange={(event) => patch({ sampleMode: event.target.value })}
          options={[{ value: 'STANDARD', label: '标准采样' }, { value: 'DEEP', label: '深度采样' }]} />
        <Button disabled={locked || !validRange} loading={busy} onClick={props.onPreview}>核对本次费用</Button>
      </section>

      <section className="geo-monitor-action-section" aria-label="执行额度与授权">
        <Typography.Title level={5}>执行额度与授权</Typography.Title>
        <div className="geo-monitor-budget-fields">
          <label>每次上限（积分）<Input aria-label="每次积分上限" inputMode="numeric" maxLength={10} value={editor.actionPoints} disabled={locked} onChange={(event) => patch({ actionPoints: event.target.value })} placeholder="自行填写" /></label>
          <label>当日准入上限（积分）<Input aria-label="当日准入积分上限" inputMode="numeric" maxLength={10} value={editor.dayPoints} disabled={locked} onChange={(event) => patch({ dayPoints: event.target.value })} placeholder="自行填写" /></label>
        </div>
        <Typography.Text type="secondary">这是自动执行的准入预算，按北京时间自然日计算，并包含在途冻结积分；不代表手动确认和旧计划的全账户消费硬上限。</Typography.Text>
        {(editor.actionPoints || editor.dayPoints) && !validBudget ? <Typography.Text type="warning">请填写正整数，且每次上限不能高于当日上限。</Typography.Text> : null}
        <label>本专题授权截止（北京时间）<Input aria-label="授权截止时间（北京时间）" type="datetime-local" value={editor.expiresLocal} disabled={locked} onChange={(event) => patch({ expiresLocal: event.target.value })} /></label>
        {editor.expiresLocal && !validExpiry ? <Typography.Text type="warning">请选择有效且晚于当前时间的授权截止时间。</Typography.Text> : null}
        {props.authorizationLabel ? <Typography.Text>{props.authorizationLabel}</Typography.Text> : null}
        {props.canRevoke ? <Button danger disabled={locked} onClick={props.onRevoke}>撤销此专题授权</Button> : null}
        <Typography.Text type="secondary">授权只适用于这个会话中选定的专题与范围。修改范围、资料或价格后会重新核对；撤销授权不会取消已受理的任务。</Typography.Text>
      </section>

      <section className="geo-monitor-action-section" aria-label="本次费用与开始">
        {quoteValid ? <Typography.Text strong>本次预计 {props.quote!.points} 积分 · {props.quote!.sampleCount} 个采样</Typography.Text>
          : <Typography.Text type="secondary">尚未取得有效报价，不会开始监测。</Typography.Text>}
        {quoteValid && actionPoints !== undefined && props.quote!.points > actionPoints ? <Typography.Text type="warning">本次报价超过每次上限，请调整范围或额度。</Typography.Text> : null}
        <Button type="primary" loading={busy} disabled={locked || !validRange || !validBudget || !validExpiry || !quoteValid
          || props.quote!.points > (actionPoints ?? 0) || !props.canStart} onClick={props.onStart}>{props.startLabel}</Button>
        <Typography.Text type="secondary">点击后仅请求本次监测，是否受理以服务端检查结果为准。</Typography.Text>
      </section>
    </Space>
  </Drawer>
}

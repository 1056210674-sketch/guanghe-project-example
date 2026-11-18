import { ArrowRightOutlined, PlusOutlined } from '@ant-design/icons'
import { Button, Card, Select, Space, Table, Typography } from 'antd'
import type { ReactNode } from 'react'
import type { GeoAsyncViewProps, GeoMetricView, GeoPlatformPerformanceView, GeoRecentRunView, GeoSelectOption, GeoSuggestionView, GeoTrendPointView } from '../types'
import { formatGeoDateTime, formatGeoPoints } from '../presentation'
import { GeoPlatformVisibility } from './GeoPlatformVisibility'
import { GeoStateFrame } from './GeoStateFrame'
import { GeoStatusTag } from './GeoStatusTag'

export interface GeoOverviewProps extends GeoAsyncViewProps {
  objectLabel?: string
  metrics: readonly GeoMetricView[]
  trend: readonly GeoTrendPointView[]
  platformPerformance: readonly GeoPlatformPerformanceView[]
  recentRuns: readonly GeoRecentRunView[]
  suggestions: readonly GeoSuggestionView[]
  topics?: readonly GeoSelectOption[]
  selectedTopicId?: string
  onTopicChange?: (id: string) => void
  reportId?: string
  reportTime?: string
  visibilityNotice?: string
  onCreateMonitoring?: () => void
  onViewReport?: (reportId: string, platformCode?: string) => void
  onPrepareContentDraft?: (suggestionId?: string) => void
  preparingContentDraft?: boolean
  longitudinal?: ReactNode
}

export function GeoOverview({ loading, error, onRetry, objectLabel, metrics,
  platformPerformance, recentRuns, suggestions, topics = [], selectedTopicId, onTopicChange,
  reportId, reportTime, visibilityNotice, onCreateMonitoring, onViewReport,
  onPrepareContentDraft, preparingContentDraft = false, longitudinal }: GeoOverviewProps) {
  const empty = topics.length === 0 && metrics.length === 0 && platformPerformance.length === 0 && recentRuns.length === 0
  return <GeoStateFrame label="GEO 监测概览" loading={loading} error={error} onRetry={onRetry}
    empty={empty} emptyTitle="还没有监测结果" emptyDescription="选择商品、问题和平台，了解 AI 回答是否提到了你的商品。"
    emptyAction={<Button type="primary" icon={<PlusOutlined />} disabled={!onCreateMonitoring} onClick={onCreateMonitoring}>新建监测</Button>}>
    <section className="geo-overview" aria-labelledby="geo-overview-title">
      <header className="geo-page-heading">
        <div><Typography.Title id="geo-overview-title" level={1}>商品 AI 可见度</Typography.Title>
          <Typography.Paragraph type="secondary">了解顾客向不同 AI 平台提问时，你的商品有没有被提到。</Typography.Paragraph></div>
        <Button type="primary" icon={<PlusOutlined />} disabled={!onCreateMonitoring} onClick={onCreateMonitoring}>新建监测</Button>
      </header>
      {topics.length > 0 ? <Card className="geo-panel-card"><div className="geo-overview-scope">
        <label htmlFor="geo-overview-topic">监测专题</label>
        <Select id="geo-overview-topic" aria-label="监测专题" value={selectedTopicId} options={topics.map((topic) => ({ value: topic.id, label: topic.label }))} onChange={onTopicChange} />
        <Typography.Text type="secondary">按专题查看同一商品的持续记录；不同识别范围和问题版本分别标注。</Typography.Text>
      </div></Card> : null}
      {longitudinal}
      <Card title={objectLabel ? `${objectLabel} · 各平台 AI 可见度` : '各平台 AI 可见度'} className="geo-panel-card"
        extra={reportId ? <Button type="link" onClick={() => onViewReport?.(reportId)} disabled={!onViewReport}>查看完整报告</Button> : null}>
        {reportTime ? <Typography.Paragraph type="secondary">最近监测：{formatGeoDateTime(reportTime)}（北京时间）</Typography.Paragraph> : null}
        <Typography.Paragraph className="geo-visibility-notice">{visibilityNotice ?? '尚无可评估结果。完成一次监测后，在这里查看商品在各平台回答中的提及情况。'}</Typography.Paragraph>
        <GeoPlatformVisibility platforms={platformPerformance} onSelectPlatform={reportId && onViewReport ? (platformCode) => onViewReport(reportId, platformCode) : undefined} />
        <Typography.Paragraph type="secondary" className="geo-field-help">可见度 = 提及监测对象的有效回答数 ÷ 有效回答数；失败与缺失单独统计。</Typography.Paragraph>
      </Card>
      {suggestions.length > 0 ? <Card title="下一步可以做什么" className="geo-panel-card">
        <div className="geo-suggestion-list">{suggestions.map((suggestion) => <article key={suggestion.id} className="geo-suggestion-item">
          <Typography.Text strong>{suggestion.title}</Typography.Text>
          {suggestion.description ? <Typography.Paragraph type="secondary">{suggestion.description}</Typography.Paragraph> : null}
          {onPrepareContentDraft ? <Button type="link" icon={<ArrowRightOutlined />} loading={preparingContentDraft} disabled={preparingContentDraft} onClick={() => onPrepareContentDraft(suggestion.id)}>准备内容草稿</Button> : null}
        </article>)}</div>
      </Card> : null}
      <Card title="当前专题的监测记录" className="geo-panel-card geo-table-card">
        <Table<GeoRecentRunView> rowKey="id" dataSource={recentRuns} pagination={false} scroll={{ x: 600 }}
          locale={{ emptyText: '这个专题还没有监测记录。' }} columns={[
            { title: '监测时间', dataIndex: 'createdAt', render: (value: string | undefined) => formatGeoDateTime(value) },
            { title: '状态', dataIndex: 'status', render: (status: GeoRecentRunView['status']) => <GeoStatusTag status={status} /> },
            { title: '实际积分', dataIndex: 'settledPoints', render: (value: number | undefined) => formatGeoPoints(value) },
            { title: '操作', key: 'action', render: (_, record) => <Button type="link" disabled={!record.reportId || !onViewReport} onClick={() => { if (record.reportId) onViewReport?.(record.reportId) }}>查看报告</Button> },
          ]} />
      </Card>
      <Space className="geo-methodology-note"><Typography.Text type="secondary">不同平台的接口回答与消费者端产品可能不同；本页仅代表本次问题、时间和平台接口的观察结果。</Typography.Text></Space>
    </section>
  </GeoStateFrame>
}

import { Alert, Button, Card, Checkbox, Collapse, Form, Modal, Pagination, Popconfirm, Radio, Select, Space, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import type { ReactNode } from 'react'
import type { ConfirmGeoCompetitorCandidateRequest, GeoCompetitiveInsights, GeoCompetitorCandidate, GeoCompetitorCandidatePage } from '../../../api/geoApi'
import { formatGeoDateTime } from '../presentation'
import type { GeoCompetitorView, GeoPlatformView, GeoSelectOption } from '../types'
import { GeoPlatformBadge } from './GeoPlatformBadge'

type Scope = GeoCompetitiveInsights['questionScope']
type CandidateStatus = GeoCompetitorCandidate['status']
type Evidence = GeoCompetitorCandidate['evidence'][number]
type ConfirmationValues = Pick<ConfirmGeoCompetitorCandidateRequest, 'existingCompetitorId' | 'type' | 'addToTopic'>

const entityLabels = { BRAND: '品牌', PRODUCT: '商品', UNKNOWN: '层级待核实' } as const
const statusLabels = { PENDING: '待确认', CONFIRMED: '已确认', IGNORED: '已忽略' } as const
const analysisReasons: Record<string, string> = {
  NO_VALID_ANSWERS: '未取得有效回答，暂时无法发现或比较竞品。请查看原报告核对采集结果。',
  ARCHIVED: '该报告已归档，仍可查看已有证据；如需补充分析，请先恢复报告。',
  NOT_TERMINAL: '本次监测尚未结束，请完成采集后再分析。',
  SERVICE_UNAVAILABLE: '竞品分析服务暂不可用，请稍后刷新状态。',
  ATTEMPT_LIMIT_REACHED: '本次报告已达到分析尝试上限，原始回答仍可查看。',
  USER_ANALYSIS_ACTIVE: '已有其他报告正在分析，请等待完成后再刷新状态。',
  CAPACITY_COOLDOWN: '分析服务繁忙，正在短暂冷却，请稍后刷新状态。',
  CAPACITY_FULL: '分析服务繁忙，请稍后刷新状态。',
  PROVIDER_UNAVAILABLE: '分析服务暂不可用，原始报告不受影响。',
  TIMEOUT: '分析未能在时限内完成，原始报告不受影响。',
  INVALID_RESULT: '本次分析结果未通过核验，未将其作为竞品证据。',
  INPUT_CHANGED: '报告资料已变化，请刷新后核对分析资格。',
  LEASE_EXPIRED: '本次分析意外中断，请刷新状态查看是否可以重试。',
  REPORT_UNAVAILABLE: '报告不存在或已不可用，请重新选择报告。',
}

function rate(value: number | null) {
  return value !== null && Number.isFinite(value) && value >= 0 && value <= 100
    ? `${Number(value.toFixed(1))}%` : '暂不可评估'
}

function EvidenceList({ evidence, onViewEvidence }: {
  evidence: readonly Evidence[]
  onViewEvidence: (reportId: string, unitKey: string) => void
}) {
  return evidence.length ? <ul className="geo-competitive-evidence">
    {evidence.map((item, index) => <li key={`${item.reportId}:${item.unitKey}:${index}`}>
      <Tag>{item.signal === 'POSITIVE_ATTRIBUTE' ? '回答中的正面描述' : item.signal === 'NEGATIVE_ATTRIBUTE' ? '回答中的负面描述' : '回答中提及'}</Tag>
      <blockquote>{item.quote}</blockquote>
      {item.reportId && item.unitKey.trim() ? <Button type="link" onClick={() => onViewEvidence(item.reportId, item.unitKey)}>查看原始回答 {index + 1}</Button>
        : <Typography.Text type="secondary">原始回答暂不可定位</Typography.Text>}
    </li>)}
  </ul> : <Typography.Text type="secondary">暂无可核对的回答摘录，请先查看原报告。</Typography.Text>
}

export interface GeoCompetitiveWorkbenchProps {
  topics: readonly GeoSelectOption[]
  selectedTopicId?: string
  reports: readonly GeoSelectOption[]
  selectedReportId?: string
  reportLoading?: boolean
  reportError?: string
  reportPage: number
  reportTotal: number
  platforms: readonly GeoPlatformView[]
  questionScope: Scope
  insights?: GeoCompetitiveInsights
  insightsLoading?: boolean
  insightsError?: string
  analyzing?: boolean
  analyzeError?: string
  candidates?: GeoCompetitorCandidatePage
  candidatesLoading?: boolean
  candidatesError?: string
  candidateStatus: CandidateStatus
  candidatePage: number
  ignoringId?: string
  candidateActionError?: string
  confirmationReady?: boolean
  formalManager: ReactNode
  onTopicChange: (topicId: string) => void
  onReportChange: (reportId: string) => void
  onReportPageChange: (page: number) => void
  onScopeChange: (scope: Scope) => void
  onAnalyze: () => void
  onRefresh: () => void
  onCandidateStatusChange: (status: CandidateStatus) => void
  onCandidatePageChange: (page: number) => void
  onConfirmCandidate: (candidate: GeoCompetitorCandidate) => void
  onIgnoreCandidate: (candidate: GeoCompetitorCandidate) => void
  onViewEvidence: (reportId: string, unitKey: string) => void
  onViewReport: () => void
  onManageCatalog: () => void
}

export function GeoCompetitiveWorkbench(props: GeoCompetitiveWorkbenchProps) {
  const [expandedCandidateIds, setExpandedCandidateIds] = useState<readonly string[]>([])
  const data = props.insights
  const inProgress = data?.status === 'QUEUED' || data?.status === 'RUNNING'
  const canAnalyze = Boolean(data?.retryAllowed && (data.status === 'NOT_ANALYZED' || data.status === 'FAILED'))
  return <section className="geo-competitive-workbench" aria-labelledby="geo-competitive-title">
    <header className="geo-page-heading">
      <div><Typography.Title id="geo-competitive-title" level={1}>竞品洞察工作台</Typography.Title>
        <Typography.Paragraph type="secondary">从监测回答发现候选、核对同批可见度，确认后再纳入正式竞品。</Typography.Paragraph></div>
      <Button onClick={props.onManageCatalog}>维护正式竞品资料</Button>
    </header>
    <Card className="geo-panel-card" title="选择本次分析范围">
      <div className="geo-competitive-selectors">
        <label>监测专题<Select aria-label="竞品洞察专题" showSearch optionFilterProp="label" placeholder="选择专题" value={props.topics.some((item) => item.id === props.selectedTopicId) ? props.selectedTopicId : undefined}
          options={props.topics.map((item) => ({ value: item.id, label: item.label }))} onChange={props.onTopicChange} /></label>
        <label>监测报告<Select aria-label="竞品洞察报告" showSearch optionFilterProp="label" value={props.selectedReportId}
          loading={props.reportLoading} disabled={!props.selectedTopicId || !props.reports.length} placeholder="该专题暂无报告"
          options={props.reports.map((item) => ({ value: item.id, label: item.label }))} onChange={props.onReportChange} /></label>
      </div>
      {props.reportTotal > 20 ? <Pagination size="small" current={props.reportPage} pageSize={20} total={props.reportTotal}
        showSizeChanger={false} onChange={props.onReportPageChange} /> : null}
      {props.reportError ? <Alert type="error" showIcon title={props.reportError} action={<Button onClick={props.onRefresh}>重新加载</Button>} /> : null}
      {props.selectedReportId ? <div className="geo-competitive-toolbar">
        <Radio.Group aria-label="竞品比较问题范围" value={props.questionScope} onChange={(event) => props.onScopeChange(event.target.value as Scope)}
          options={[{ label: '自然发现问题', value: 'DISCOVERY' }, { label: '品牌验证问题', value: 'BRAND_VALIDATION' }]} optionType="button" />
        <Button onClick={props.onViewReport}>查看本次完整报告</Button>
      </div> : <Typography.Paragraph type="secondary">选择有报告的专题后查看洞察；没有报告时，请先完成一次监测。</Typography.Paragraph>}
    </Card>

    {props.selectedReportId ? <Card title="同批平台可见度" className="geo-panel-card">
      <Typography.Paragraph>仅统计所选报告、所选问题范围内的有效回答。问题点名本批自家或竞品对象时归品牌验证；名称提及不等于推荐、排名或市场份额。失败和缺失不按未提及计入。</Typography.Paragraph>
      {props.questionScope === 'BRAND_VALIDATION' ? <Alert type="info" showIcon title="这些问题已点名本批自家或竞品对象，不能与自然发现问题混合比较。" /> : null}
      {props.insightsError ? <Alert type="error" showIcon title={props.insightsError}
        description="保留已加载结果。刷新只读取状态，不会触发分析。" action={<Button onClick={props.onRefresh}>刷新状态</Button>} /> : null}
      {props.insightsLoading ? <Typography.Paragraph role="status">正在读取本次竞品洞察…</Typography.Paragraph> : null}
      {data?.reasonCode && analysisReasons[data.reasonCode] ? <Typography.Paragraph role="status">{analysisReasons[data.reasonCode]}</Typography.Paragraph> : null}
      {data?.status === 'NOT_ANALYZED' ? <Alert type="info" showIcon title="这份历史报告尚未分析竞品"
        description={canAnalyze ? '点击后仅分析已保存的回答，不重新采集、不创建付费任务。成功结果会缓存，之后查看不会重复调用分析。' : '当前不满足分析条件，原始报告仍可查看。稍后可刷新状态，刷新不会自动提交分析。'} /> : null}
      {inProgress ? <Alert type="info" showIcon title={data?.status === 'QUEUED' ? '竞品分析已排队' : '正在分析回答中的竞品'}
        description="页面会自动查询处理状态；关闭后再回来也能继续查看，不必再次提交。" /> : null}
      {data?.status === 'FAILED' ? <Alert type="warning" showIcon title="本次竞品分析未完成"
        description={data.retryAllowed ? '原始报告不受影响。可明确点击重试；分析最多尝试两次（首次及一次失败重试）。' : '当前暂不可重试，请刷新状态查看是否恢复。已达到尝试上限时不会再次调用分析，原始报告仍可查看。'} /> : null}
      {props.analyzeError ? <Alert type="error" showIcon title={props.analyzeError} /> : null}
      {canAnalyze ? <Button type="primary" loading={props.analyzing} disabled={Boolean(props.insightsError)} onClick={props.onAnalyze}>
        {data?.status === 'FAILED' ? '重试竞品分析' : '分析这份报告的竞品'}
      </Button> : null}
      {(data?.status === 'FAILED' || data?.status === 'NOT_ANALYZED') && !data.retryAllowed ? <Button onClick={props.onRefresh}>刷新分析状态</Button> : null}
      {data?.evidenceTruncated ? <Alert type="warning" showIcon title={data.reasonCode === 'CANDIDATES_REJECTED'
        ? '部分发现证据未通过验证，已仅保留可核验内容。'
        : '本次仅展示可处理范围内的证据，不能据此推断所有回答都未提及其他竞品。'} /> : null}
      {data?.status === 'SUCCEEDED' && !data.platforms.length ? <Typography.Paragraph type="secondary">该问题范围没有可比较的样本，可切换问题范围或查看原报告。</Typography.Paragraph> : null}
      {data?.platforms.map((platform) => {
        const own = platform.entities.find((entity) => entity.role === 'OWN')
        const platformView = props.platforms.find((item) => item.code === platform.platformCode)
          ?? { code: platform.platformCode, name: '其他 AI 平台', availability: 'DISABLED' as const }
        return <Card key={platform.platformCode} className="geo-table-card geo-competitive-platform" title={<GeoPlatformBadge platform={platformView} />}>
          <div className="geo-table-card__content">
            <Typography.Text>有效 {platform.validUnits} / 计划 {platform.expectedUnits} 份回答 · 失败 {platform.failedUnits} · 缺失 {platform.missingUnits}</Typography.Text>
            <Typography.Text type="secondary">不同对象层级不直接比较；名称及层级仍需人工核实。本表不提供竞争排名。</Typography.Text>
          </div>
          <Table dataSource={platform.entities} rowKey={(entity) => `${entity.role}:${entity.competitorId ?? entity.candidateId ?? entity.name}`} pagination={false}
            scroll={{ x: 660 }} columns={[
              { title: '对象', key: 'name', render: (_, entity) => <Space wrap><Typography.Text strong>{entity.name || '监测对象名称待核实'}</Typography.Text>
                <Tag>{entity.role === 'OWN' ? '自家' : entity.role === 'CONFIGURED' ? '正式竞品' : '发现对象'}</Tag>
                {entity.recognitionStatus ? <Tag>{statusLabels[entity.recognitionStatus]}</Tag> : null}</Space> },
              { title: '层级与可比性', key: 'kind', render: (_, entity) => <div><Tag>{entityLabels[entity.entityKind]}</Tag>
                {entity.role !== 'OWN' && (!own || own.entityKind === 'UNKNOWN' || entity.entityKind === 'UNKNOWN' || own.entityKind !== entity.entityKind)
                  ? <Typography.Text type="secondary">不可直接比较</Typography.Text> : null}</div> },
              { title: '名称提及率', key: 'rate', render: (_, entity) => <div><Typography.Text strong>{rate(platform.validUnits ? entity.mentionRate : null)}</Typography.Text>
                <div>{!platform.validUnits ? '没有有效回答' : entity.mentionedUnits === null || entity.mentionRate === null ? '名称尚不可核验' : `${entity.mentionedUnits} / ${platform.validUnits} 份有效回答`}</div></div> },
            ]} expandable={{ expandedRowRender: (entity) => <EvidenceList evidence={entity.evidence} onViewEvidence={props.onViewEvidence} />,
              rowExpandable: (entity) => entity.evidence.length > 0 }} />
        </Card>
      })}
    </Card> : null}

    {props.selectedTopicId ? <Card title="专题累计候选池" className="geo-panel-card geo-table-card">
      <div className="geo-table-card__content">
        <Typography.Paragraph>新报告分析发现的对象会进入待确认池，尚不是正式竞品。此处汇总该专题历次报告，与上方本次报告的统计范围不同。忽略状态会保留，不因再次发现自动恢复。</Typography.Paragraph>
        <Radio.Group aria-label="候选处理状态" value={props.candidateStatus} onChange={(event) => props.onCandidateStatusChange(event.target.value as CandidateStatus)}
          options={Object.entries(statusLabels).map(([value, label]) => ({ value, label }))} optionType="button" />
        {props.candidatesError || props.candidateActionError ? <Alert type="error" showIcon title={props.candidateActionError ?? props.candidatesError}
          action={<Button onClick={props.onRefresh}>刷新候选</Button>} /> : null}
      </div>
      <Table<GeoCompetitorCandidate> rowKey="id" loading={props.candidatesLoading} dataSource={props.candidates?.items ?? []}
        scroll={{ x: 760 }} locale={{ emptyText: props.candidatesError ? '候选暂时无法加载' : `目前没有${statusLabels[props.candidateStatus]}的候选。` }}
        pagination={{ current: props.candidatePage, pageSize: 20, total: props.candidates?.total ?? 0, showSizeChanger: false, onChange: props.onCandidatePageChange }}
        columns={[
          { title: '发现对象', dataIndex: 'name', render: (name: string, candidate) => <div><Typography.Text strong>{name}</Typography.Text><div><Tag>{entityLabels[candidate.entityKind]}</Tag><Tag>{statusLabels[candidate.status]}</Tag></div></div> },
          { title: '最近发现（北京时间）', dataIndex: 'lastSeenAt', render: (value: string) => formatGeoDateTime(value) },
          { title: '证据与处理', key: 'actions', render: (_, candidate) => <Space wrap>
            <Button onClick={() => setExpandedCandidateIds(expandedCandidateIds.includes(candidate.id) ? [] : [candidate.id])}>查看证据与借鉴</Button>
            {candidate.status === 'PENDING' ? <><Button type="primary" disabled={!props.confirmationReady} onClick={() => props.onConfirmCandidate(candidate)}>确认或关联</Button>
              <Popconfirm title={`忽略「${candidate.name}」？`} description="后续再次发现也会保留忽略状态，可在已忽略列表查看。" okText="确认忽略" cancelText="取消" onConfirm={() => props.onIgnoreCandidate(candidate)}>
                <Button aria-label="忽略" loading={props.ignoringId === candidate.id}>忽略</Button>
              </Popconfirm></> : null}
          </Space> },
        ]} expandable={{ expandedRowKeys: expandedCandidateIds, onExpandedRowsChange: (keys) => setExpandedCandidateIds(keys.map(String)), expandedRowRender: (candidate) => <div className="geo-competitive-candidate-detail">
          <Typography.Title level={5}>AI 如何描述它 · 回答原文摘录</Typography.Title>
          <EvidenceList evidence={candidate.evidence} onViewEvidence={props.onViewEvidence} />
          <Typography.Title level={5}>可以借鉴什么 · 待验证建议</Typography.Title>
          {candidate.learningSuggestions.length ? <ul>{candidate.learningSuggestions.map((suggestion, index) => <li key={index}>{suggestion}</li>)}</ul>
            : <Typography.Text type="secondary">现有证据不足，暂不生成借鉴建议。</Typography.Text>}
        </div> }} />
    </Card> : null}
    <Collapse items={[{ key: 'formal', label: '正式竞品集合 · 影响后续监测', children: props.formalManager }]} />
  </section>
}

export function GeoCandidateConfirmation({ candidate, competitors, submitting, error, onCancel, onSubmit }: {
  candidate: GeoCompetitorCandidate
  competitors: readonly GeoCompetitorView[]
  submitting: boolean
  error?: string
  onCancel: () => void
  onSubmit: (values: ConfirmationValues) => void
}) {
  const [form] = Form.useForm<ConfirmationValues>()
  const [mode, setMode] = useState(candidate.existingCompetitorId ? 'existing' : 'new')
  const matchedCompetitor = competitors.find((item) => item.id === candidate.existingCompetitorId)
  return <Modal open title={`确认「${candidate.name}」`} okText="确认保存" cancelText="取消" confirmLoading={submitting}
    onCancel={submitting ? undefined : onCancel} maskClosable={!submitting} closable={!submitting}
    onOk={() => void form.validateFields().then((values) => onSubmit({ ...values,
      type: mode === 'existing' ? competitors.find((item) => item.id === values.existingCompetitorId)?.competitorType ?? 'DIRECT' : values.type,
      existingCompetitorId: mode === 'existing' ? values.existingCompetitorId : null })).catch(() => { /* Field validation stays visible. */ })}>
    <Alert type="info" showIcon title="确认后才会成为正式资料" description="纳入专题仅对后续任务生效，不改写历史报告，不修改已有自动计划的授权范围，也不会自动创建付费监测。" />
    {error ? <Alert type="error" showIcon title={error} /> : null}
    <Form form={form} layout="vertical" initialValues={{ existingCompetitorId: matchedCompetitor?.id, type: 'DIRECT', addToTopic: true }}>
      <Form.Item label="保存方式"><Radio.Group value={mode} onChange={(event) => setMode(event.target.value)}>
        <Radio value="new" disabled={Boolean(candidate.existingCompetitorId)}>新建正式竞品</Radio><Radio value="existing">关联已有竞品</Radio>
      </Radio.Group></Form.Item>
      {candidate.existingCompetitorId ? <Typography.Paragraph type="secondary">发现可能匹配的正式资料，请核实并关联，避免重复创建。</Typography.Paragraph> : null}
      {mode === 'existing' ? <Form.Item name="existingCompetitorId" label="关联竞品" rules={[{ required: true, message: '请选择要关联的正式竞品' }]}>
        <Select aria-label="关联已有竞品" showSearch optionFilterProp="label" options={competitors.map((item) => ({ value: item.id, label: item.name }))} />
      </Form.Item> : <Form.Item name="type" label="竞品关系" rules={[{ required: true }]}><Select options={[{ value: 'DIRECT', label: '直接竞品' }, { value: 'INDIRECT', label: '间接竞品' }, { value: 'SUBSTITUTE', label: '替代品' }]} /></Form.Item>}
      <Form.Item name="addToTopic" valuePropName="checked"><Checkbox>同时纳入当前专题的正式竞品集合</Checkbox></Form.Item>
      <Typography.Paragraph type="secondary">仅确认资料时可取消勾选；现有自动计划如需使用新集合，请另行检查并确认计划配置。</Typography.Paragraph>
    </Form>
  </Modal>
}

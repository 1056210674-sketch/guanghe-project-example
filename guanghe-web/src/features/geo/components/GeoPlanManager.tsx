import {
  EditOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  SaveOutlined,
} from '@ant-design/icons'
import { diagnosticTraceDescription } from '../../../shared/tracePresentation'
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Drawer,
  Input,
  InputNumber,
  Radio,
  Select,
  Space,
  Table,
  Typography,
} from 'antd'
import { formatGeoPoints, formatGeoSchedule } from '../presentation'
import type {
  GeoAsyncViewProps,
  GeoMonitoringMode,
  GeoPlanAuthorizationView,
  GeoPlanDraftChange,
  GeoPlanDraftView,
  GeoPlanFrequency,
  GeoPlanPaginationView,
  GeoPlanScheduleView,
  GeoPlanStatus,
  GeoPlanView,
  GeoPlatformView,
  GeoQuestionView,
  GeoTopicView,
  GeoViewError,
  GeoWeekday,
} from '../types'
import { GeoPlanAuthorizationCard } from './GeoPlanAuthorizationCard'
import { GeoPlatformBadge } from './GeoPlatformBadge'
import { GeoStateFrame } from './GeoStateFrame'
import { GeoStatusTag } from './GeoStatusTag'

export interface GeoPlanManagerProps extends GeoAsyncViewProps {
  plans: readonly GeoPlanView[]
  platforms: readonly GeoPlatformView[]
  topics: readonly GeoTopicView[]
  questions: readonly GeoQuestionView[]
  pagination: GeoPlanPaginationView
  statusFilter?: GeoPlanStatus
  topicFilter?: string
  editorOpen: boolean
  draft?: GeoPlanDraftView
  authorization?: GeoPlanAuthorizationView
  editingPlanId?: string
  actionError?: GeoViewError | null
  saving?: boolean
  confirming?: boolean
  operatingPlanId?: string
  onPageChange?: (page: number, pageSize: number) => void
  onStatusFilterChange?: (status?: GeoPlanStatus) => void
  onTopicFilterChange?: (topicId?: string) => void
  onOpenCreatePlan?: () => void
  onCloseEditor?: () => void
  onBackToEditor?: () => void
  onDraftChange?: (change: GeoPlanDraftChange) => void
  onSavePlanIntent?: (draft: GeoPlanDraftView) => void
  onConfirmPlan?: (draftId: string) => void
  onEditPlanIntent?: (planId: string) => void
  onPausePlanIntent?: (planId: string) => void
  onResumePlanIntent?: (planId: string) => void
  onOpenTasks?: () => void
  onOpenReports?: () => void
}

const FREQUENCY_LABELS: Record<GeoPlanFrequency, string> = {
  HOURLY: '每小时',
  DAILY: '每天',
  WEEKLY: '每周',
  INTERVAL_DAYS: '每 N 天',
}

const WEEKDAY_LABELS: Record<GeoWeekday, string> = {
  1: '周一', 2: '周二', 3: '周三', 4: '周四',
  5: '周五', 6: '周六', 7: '周日',
}

function validDraft(draft: GeoPlanDraftView | undefined) {
  if (
    !draft || !draft.name.trim() || !draft.topicId ||
    draft.questionIds.length < 3 || draft.questionIds.length > 10 ||
    new Set(draft.questionIds).size !== draft.questionIds.length ||
    draft.platformCodes.length === 0 || !draft.schedule.timeZone.trim() ||
    !/^\d{2}:\d{2}$/.test(draft.schedule.localTime) ||
    !Number.isSafeInteger(draft.maxPointsPerRun) ||
    (draft.maxPointsPerRun ?? 0) < 1
  ) return false
  if (draft.schedule.frequency === 'WEEKLY' && draft.schedule.weekdays.length === 0) return false
  return draft.schedule.frequency !== 'INTERVAL_DAYS' ||
    (Number.isSafeInteger(draft.schedule.intervalDays) && draft.schedule.intervalDays >= 1)
}

function dateTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

export function GeoPlanManager({
  loading, error, onRetry, plans, platforms, topics, questions, pagination,
  statusFilter, topicFilter, editorOpen, draft, authorization, editingPlanId, actionError,
  saving = false, confirming = false, operatingPlanId, onPageChange,
  onStatusFilterChange, onTopicFilterChange, onOpenCreatePlan, onCloseEditor, onBackToEditor,
  onDraftChange, onSavePlanIntent, onConfirmPlan, onEditPlanIntent,
  onPausePlanIntent, onResumePlanIntent, onOpenTasks, onOpenReports,
}: GeoPlanManagerProps) {
  const editorReady = validDraft(draft) && Boolean(onSavePlanIntent)
  const empty = plans.length === 0 && !editorOpen && !statusFilter && !topicFilter

  return (
    <GeoStateFrame
      label="自动监测计划" loading={loading} error={error} onRetry={onRetry}
      empty={empty} emptyTitle="还没有自动监测计划"
      emptyDescription="先选择正式专题、问题和平台生成授权草稿，确认后只创建计划，不立即执行。"
      emptyAction={<Button type="primary" icon={<PlusOutlined />} disabled={!onOpenCreatePlan} onClick={onOpenCreatePlan}>新建计划</Button>}
    >
      <section className="geo-plans" aria-labelledby="geo-plans-title">
        <header className="geo-page-heading">
          <div>
            <Typography.Title id="geo-plans-title" level={1}>自动监测计划</Typography.Title>
            <Typography.Paragraph type="secondary">暂停期间不会创建运行；恢复后从下一个计划时间开始，不补跑历史窗口。</Typography.Paragraph>
          </div>
          <Space wrap>
            <Button disabled={!onOpenTasks} onClick={onOpenTasks}>查看任务</Button>
            <Button disabled={!onOpenReports} onClick={onOpenReports}>查看报告</Button>
            <Button type="primary" icon={<PlusOutlined />} disabled={!onOpenCreatePlan} onClick={onOpenCreatePlan}>新建计划</Button>
          </Space>
        </header>

        {actionError && !editorOpen ? <Alert type="error" showIcon title={actionError.message}
          description={diagnosticTraceDescription(actionError.traceId)} /> : null}

        <Card className="geo-panel-card geo-table-card">
          <div className="geo-filter-bar">
            {onTopicFilterChange ? <Select aria-label="计划专题" allowClear placeholder="全部监测专题" value={topicFilter}
              options={topics.map((topic) => ({ value: topic.id, label: topic.name }))} onChange={onTopicFilterChange} /> : null}
            <Select aria-label="计划状态" allowClear placeholder="全部计划状态" value={statusFilter}
              options={[{ value: 'ACTIVE', label: '运行中' }, { value: 'PAUSED', label: '已暂停' }]}
              onChange={(value) => onStatusFilterChange?.(value)} />
          </div>
          <Table<GeoPlanView>
            rowKey="id" dataSource={plans} scroll={{ x: 1400 }}
            pagination={{ current: pagination.current, pageSize: pagination.pageSize,
              total: pagination.total, showSizeChanger: true, onChange: onPageChange }}
            columns={[
              { title: '计划名称', dataIndex: 'name', width: 180 },
              { title: '专题', dataIndex: 'topicName', width: 150 },
              { title: '问题', dataIndex: 'questionIds', width: 90, render: (ids: readonly string[]) => `${ids.length} 个` },
              { title: '平台', dataIndex: 'platformCodes', width: 220,
                render: (codes: readonly string[]) => <Space size={[4, 4]} wrap>{codes.map((code) => {
                  const platform = platforms.find((item) => item.code === code)
                  return platform ? <GeoPlatformBadge key={code} platform={platform} compact /> : <span key={code}>{code}</span>
                })}</Space> },
              { title: '模式', dataIndex: 'mode', width: 90, render: (mode: GeoMonitoringMode) => mode === 'STANDARD' ? '标准' : '深度' },
              { title: '周期', dataIndex: 'schedule', width: 180, render: (schedule: GeoPlanScheduleView) => formatGeoSchedule(schedule) },
              { title: '状态', dataIndex: 'status', width: 100, render: (status: GeoPlanStatus) => <GeoStatusTag status={status} /> },
              { title: '下次执行', dataIndex: 'nextRunAt', width: 170, render: dateTime },
              { title: '最近计划窗口', dataIndex: 'lastScheduledSlot', width: 170, render: dateTime },
              { title: '状态说明', dataIndex: 'pauseReason', width: 150, render: (value?: string) => value ?? '—' },
              { title: '预计单次', dataIndex: 'estimatedPointsPerRun', width: 110, render: formatGeoPoints },
              { title: '单次上限', dataIndex: 'maxPointsPerRun', width: 110, render: formatGeoPoints },
              { title: '操作', key: 'actions', fixed: 'right', width: 190, render: (_, plan) => {
                const locked = operatingPlanId === plan.id
                return <Space size={2}>
                  {plan.status === 'ACTIVE' ?
                    <Button type="link" size="small" icon={<PauseCircleOutlined />} loading={locked} disabled={Boolean(operatingPlanId) || !onPausePlanIntent} onClick={() => onPausePlanIntent?.(plan.id)}>暂停</Button> :
                    <Button type="link" size="small" icon={<PlayCircleOutlined />} loading={locked} disabled={Boolean(operatingPlanId) || !onResumePlanIntent} onClick={() => onResumePlanIntent?.(plan.id)}>恢复</Button>}
                  <Button type="link" size="small" icon={<EditOutlined />} disabled={Boolean(operatingPlanId) || !onEditPlanIntent} onClick={() => onEditPlanIntent?.(plan.id)}>编辑</Button>
                </Space>
              } },
            ]}
          />
        </Card>

        <Drawer title={editingPlanId ? '编辑自动监测计划' : '新建自动监测计划'} size={520}
          open={editorOpen} onClose={onCloseEditor} destroyOnHidden
          footer={authorization ? null : <Space className="geo-drawer-actions">
            <Button onClick={onCloseEditor} disabled={saving}>取消</Button>
            <Button type="primary" icon={<SaveOutlined />} loading={saving} disabled={!editorReady || saving}
              onClick={() => draft && onSavePlanIntent?.(draft)}>{editingPlanId ? '保存修改' : '生成授权草稿'}</Button>
          </Space>}
        >
          {authorization ? <GeoPlanAuthorizationCard authorization={authorization} confirming={confirming}
              error={actionError} onBack={onBackToEditor} onConfirm={onConfirmPlan} /> :
            draft ? <>{actionError ? <Alert className="geo-form-alert" type="error" showIcon title={actionError.message}
                description={diagnosticTraceDescription(actionError.traceId)} /> : null}
              <GeoPlanEditor draft={draft} topics={topics} questions={questions} platforms={platforms} onChange={onDraftChange} /></> :
              <Alert type="info" showIcon title="暂无可编辑的计划草稿" description="请重新打开计划编辑器。" />}
        </Drawer>
      </section>
    </GeoStateFrame>
  )
}

interface GeoPlanEditorProps {
  draft: GeoPlanDraftView
  topics: readonly GeoTopicView[]
  questions: readonly GeoQuestionView[]
  platforms: readonly GeoPlatformView[]
  onChange?: (change: GeoPlanDraftChange) => void
}

function browserTimeZones(current: string) {
  try {
    const supportedValuesOf = (Intl as typeof Intl & { supportedValuesOf?: (key: 'timeZone') => string[] }).supportedValuesOf
    const values = supportedValuesOf?.('timeZone') ?? []
    return values.includes(current) ? values : [current, ...values]
  } catch { return [current] }
}

function GeoPlanEditor({ draft, topics, questions, platforms, onChange }: GeoPlanEditorProps) {
  const enabledQuestions = questions.filter((question) => question.enabled !== false)
  return <div className="geo-form-stack">
    <label className="geo-form-field"><Typography.Text strong>计划名称</Typography.Text>
      <Input maxLength={80} showCount value={draft.name} disabled={!onChange} onChange={(event) => onChange?.({ field: 'NAME', value: event.target.value })} /></label>
    <label className="geo-form-field"><Typography.Text strong>专题</Typography.Text>
      <Select value={draft.topicId} placeholder="选择专题" options={topics.map((topic) => ({ value: topic.id, label: topic.name }))}
        disabled={!onChange} onChange={(value) => onChange?.({ field: 'TOPIC', value })} /></label>
    <label className="geo-form-field"><Typography.Text strong>问题（3～10 个）</Typography.Text>
      <Select mode="multiple" value={[...draft.questionIds]} placeholder={draft.topicId ? '选择已启用的正式问题' : '请先选择专题'}
        options={enabledQuestions.map((question) => ({ value: question.id, label: question.text }))} disabled={!onChange || !draft.topicId}
        maxCount={10} onChange={(value) => onChange?.({ field: 'QUESTIONS', value })} /></label>
    <div className="geo-form-field"><Typography.Text strong>监测平台</Typography.Text>
      <Checkbox.Group className="geo-platform-check-grid geo-platform-check-grid--drawer" value={[...draft.platformCodes]} disabled={!onChange}
        onChange={(values) => onChange?.({ field: 'PLATFORMS', value: values.map(String) })}>
        {platforms.map((platform) => <Checkbox key={platform.code} value={platform.code} disabled={platform.availability !== 'AVAILABLE'}><GeoPlatformBadge platform={platform} compact /></Checkbox>)}
      </Checkbox.Group></div>
    <div className="geo-form-field"><Typography.Text strong>监测模式</Typography.Text>
      <Radio.Group value={draft.mode} disabled={!onChange} onChange={(event) => onChange?.({ field: 'MODE', value: event.target.value as GeoMonitoringMode })}
        options={[{ value: 'STANDARD', label: '标准模式' }, { value: 'DEEP', label: '深度模式' }]} /></div>
    <div className="geo-form-field"><Typography.Text strong>频率</Typography.Text>
      <Radio.Group value={draft.schedule.frequency} disabled={!onChange} onChange={(event) => onChange?.({ field: 'FREQUENCY', value: event.target.value as GeoPlanFrequency })}
        options={Object.entries(FREQUENCY_LABELS).map(([value, label]) => ({ value, label }))} /></div>
    {draft.schedule.frequency === 'WEEKLY' ? <div className="geo-form-field"><Typography.Text strong>执行星期</Typography.Text>
      <Checkbox.Group value={[...draft.schedule.weekdays]} disabled={!onChange} options={Object.entries(WEEKDAY_LABELS).map(([value, label]) => ({ value: Number(value), label }))}
        onChange={(values) => onChange?.({ field: 'WEEKDAYS', value: values.map(Number) as GeoWeekday[] })} /></div> : null}
    {draft.schedule.frequency === 'INTERVAL_DAYS' ? <label className="geo-form-field"><Typography.Text strong>间隔天数</Typography.Text>
      <InputNumber className="geo-control-full" min={1} precision={0} value={draft.schedule.intervalDays} disabled={!onChange}
        onChange={(value) => onChange?.({ field: 'INTERVAL_DAYS', value: typeof value === 'number' ? value : undefined })} /></label> : null}
    <label className="geo-form-field"><Typography.Text strong>{draft.schedule.frequency === 'HOURLY' ? '每小时的执行分钟' : '执行时间'}</Typography.Text>
      <Input type="time" value={draft.schedule.localTime} disabled={!onChange} onChange={(event) => onChange?.({ field: 'LOCAL_TIME', value: event.target.value })} /></label>
    {draft.schedule.frequency === 'HOURLY' ? <Typography.Text type="secondary">按所选分钟每小时监测一次，小时部分不决定频率。只监测，不自动写稿或发布。</Typography.Text> : null}
    <label className="geo-form-field"><Typography.Text strong>IANA 时区</Typography.Text>
      <Select showSearch value={draft.schedule.timeZone} options={browserTimeZones(draft.schedule.timeZone).map((value) => ({ value, label: value }))}
        disabled={!onChange} onChange={(value) => onChange?.({ field: 'TIME_ZONE', value })} /></label>
    <label className="geo-form-field"><Typography.Text strong>单次积分上限</Typography.Text>
      <InputNumber className="geo-control-full" min={1} precision={0} value={draft.maxPointsPerRun} disabled={!onChange}
        onChange={(value) => onChange?.({ field: 'MAX_POINTS_PER_RUN', value: typeof value === 'number' ? value : undefined })} /></label>
    <Alert type="info" showIcon title="创建计划不立即扣积分" description="每个执行窗口由服务端按当时有效价格和问题范围重新校验；计划创建与正式监测任务严格分离。" />
  </div>
}

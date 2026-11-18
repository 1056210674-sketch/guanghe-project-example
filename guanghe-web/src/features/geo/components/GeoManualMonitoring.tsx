import {
  CheckCircleOutlined,
  ExclamationCircleOutlined,
  RadarChartOutlined,
} from '@ant-design/icons'
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Empty,
  Radio,
  Select,
  Space,
  Typography,
} from 'antd'
import { PaidTaskConfirmationCard } from '../../chat/components/PaidTaskConfirmationCard'
import { ParallelTaskGroup } from '../../chat/components/ParallelTaskGroup'
import { TaskCreatedReceiptCard } from '../../chat/components/TaskCreatedReceiptCard'
import { TaskExecutionOutcomeGroup } from '../../chat/components/TaskExecutionOutcomeGroup'
import type {
  PaidTaskConfirmationView,
  ParallelTaskGroupView,
  TaskCreatedReceiptView,
  TaskExecutionOutcomeGroupView,
  TaskOutcomeActionType,
} from '../../chat'
import { formatGeoCount, formatGeoPoints } from '../presentation'
import type {
  GeoAsyncViewProps,
  GeoManualMonitoringIntent,
  GeoMonitoringMode,
  GeoPlatformView,
  GeoQuestionGroupOption,
  GeoQuestionView,
  GeoQuoteView,
  GeoTopicView,
} from '../types'
import { GeoPlatformBadge } from './GeoPlatformBadge'
import { GeoStateFrame } from './GeoStateFrame'

export interface GeoManualMonitoringProps extends GeoAsyncViewProps {
  experience?: 'MANUAL' | 'QUICK'
  topics: readonly GeoTopicView[]
  questionGroups: readonly GeoQuestionGroupOption[]
  questions: readonly GeoQuestionView[]
  platforms: readonly GeoPlatformView[]
  selectedTopicId?: string
  selectedQuestionGroupId?: string
  selectedQuestionIds: readonly string[]
  selectedPlatformCodes: readonly string[]
  mode: GeoMonitoringMode
  quote?: GeoQuoteView
  businessSpaceName?: string
  productName?: string
  region?: string
  configurationNotice?: string
  preparing?: boolean
  confirmation?: PaidTaskConfirmationView
  taskReceipt?: TaskCreatedReceiptView
  taskProgress?: ParallelTaskGroupView
  taskOutcome?: TaskExecutionOutcomeGroupView
  reportId?: string
  onTopicChange?: (topicId: string) => void
  onQuestionGroupChange?: (groupId: string | undefined) => void
  onQuestionSelectionChange?: (questionIds: readonly string[]) => void
  onPlatformSelectionChange?: (platformCodes: readonly string[]) => void
  onModeChange?: (mode: GeoMonitoringMode) => void
  onBack?: () => void
  onPrepareConfirmation?: (intent: GeoManualMonitoringIntent) => void
  onEditConfirmation?: (cardId: string) => void
  onConfirm?: (cardId: string) => void
  onOpenRecharge?: (cardId: string) => void
  onPrepareReestimate?: (cardId: string) => void
  onViewTask?: (taskId: string) => void
  onTaskOutcomeAction?: (
    taskId: string,
    actionType: TaskOutcomeActionType,
  ) => void
  onViewReport?: (reportId: string) => void
}

export function GeoManualMonitoring({
  experience = 'MANUAL',
  loading,
  error,
  onRetry,
  topics,
  questionGroups,
  questions,
  platforms,
  selectedTopicId,
  selectedQuestionGroupId,
  selectedQuestionIds,
  selectedPlatformCodes,
  mode,
  quote,
  businessSpaceName,
  productName,
  region,
  configurationNotice,
  preparing = false,
  confirmation,
  taskReceipt,
  taskProgress,
  taskOutcome,
  reportId,
  onTopicChange,
  onQuestionGroupChange,
  onQuestionSelectionChange,
  onPlatformSelectionChange,
  onModeChange,
  onBack,
  onPrepareConfirmation,
  onEditConfirmation,
  onConfirm,
  onOpenRecharge,
  onPrepareReestimate,
  onViewTask,
  onTaskOutcomeAction,
  onViewReport,
}: GeoManualMonitoringProps) {
  const quickExperience = experience === 'QUICK'
  const availablePlatformCodes = platforms
    .filter((platform) => platform.availability === 'AVAILABLE')
    .map((platform) => platform.code)
  const validSelectedPlatformCodes = selectedPlatformCodes.filter((code) =>
    availablePlatformCodes.includes(code),
  )
  const canConfirm =
    Boolean(selectedTopicId) &&
    selectedQuestionIds.length >= 3 &&
    selectedQuestionIds.length <= 10 &&
    validSelectedPlatformCodes.length > 0 &&
    !configurationNotice &&
    !preparing &&
    Boolean(onPrepareConfirmation)

  const confirm = () => {
    onPrepareConfirmation?.({
      topicId: selectedTopicId,
      questionGroupId: selectedQuestionGroupId,
      questionIds: selectedQuestionIds,
      platformCodes: validSelectedPlatformCodes,
      mode,
    })
  }

  return (
    <GeoStateFrame
      label={quickExperience ? 'GEO 快速监测' : '手动 GEO 监测配置'}
      loading={loading}
      error={error}
      onRetry={onRetry}
      empty={topics.length === 0}
      emptyTitle="当前暂无监测专题"
      emptyDescription="请先创建包含正式问题的监测专题。"
    >
      <section className="geo-manual" aria-labelledby="geo-monitoring-title">
        <header className="geo-page-heading">
          <div>
            <Typography.Title id="geo-monitoring-title" level={1}>
              {quickExperience ? '快速 GEO 监测' : '手动监测配置'}
            </Typography.Title>
            <Typography.Paragraph type="secondary">
              {quickExperience
                ? '选择专题后自动带入最小有效样本范围；核对服务端报价后再创建正式任务。'
                : '选择问题和当前可用平台，确认范围与服务端报价后创建正式任务。'}
            </Typography.Paragraph>
          </div>
        </header>

        <div
          className="geo-step-strip"
          aria-label={quickExperience ? '快速监测步骤' : '手动监测步骤'}
        >
          <span className="geo-step-strip__item geo-step-strip__item--active">
            <b>1</b> {quickExperience ? '确认默认范围' : '选择问题'}
          </span>
          <span className="geo-step-strip__item">
            <b>2</b> 选择平台与模式
          </span>
          <span className="geo-step-strip__item">
            <b>3</b> 准备付费确认
          </span>
        </div>

        <div className="geo-manual__layout">
          <div className="geo-manual__configuration">
            <Card title="问题范围" className="geo-panel-card">
              <div className="geo-form-grid">
                <label className="geo-form-field">
                  <Typography.Text strong>监测专题</Typography.Text>
                  <Select
                    placeholder="选择专题"
                    value={selectedTopicId}
                    options={topics.map((topic) => ({
                      value: topic.id,
                      label: topic.name,
                    }))}
                    disabled={!onTopicChange}
                    onChange={onTopicChange}
                  />
                </label>
                {questionGroups.length > 0 ? (
                  <label className="geo-form-field">
                    <Typography.Text strong>问题组（可选）</Typography.Text>
                    <Select
                      allowClear
                      placeholder="选择问题组"
                      value={selectedQuestionGroupId}
                      options={questionGroups.map((group) => ({
                        value: group.id,
                        label: `${group.name}（${group.questionCount}）`,
                      }))}
                      disabled={!onQuestionGroupChange}
                      onChange={onQuestionGroupChange}
                    />
                  </label>
                ) : null}
              </div>

              {selectedTopicId ? (
                <dl className="geo-manual__context" aria-label="监测业务上下文">
                  <div><dt>经营空间</dt><dd>{businessSpaceName ?? '—'}</dd></div>
                  <div><dt>商品</dt><dd>{productName ?? '未关联商品'}</dd></div>
                  <div><dt>监测区域</dt><dd>{region ?? '—'}</dd></div>
                </dl>
              ) : null}

              {questions.length > 0 ? (
                <>
                  <Typography.Text type="secondary">
                    请选择 3～10 个正式启用的问题
                  </Typography.Text>
                  <Checkbox.Group
                    className="geo-question-check-list"
                    value={[...selectedQuestionIds]}
                    disabled={!onQuestionSelectionChange}
                    onChange={(values) =>
                      onQuestionSelectionChange?.(values.map(String))
                    }
                  >
                    {questions.map((question) => (
                      <Checkbox
                        key={question.id}
                        value={question.id}
                        disabled={
                          question.status !== 'ENABLED' ||
                          (selectedQuestionIds.length >= 10 &&
                            !selectedQuestionIds.includes(question.id))
                        }
                      >
                        {question.text}
                      </Checkbox>
                    ))}
                  </Checkbox.Group>
                </>
              ) : selectedTopicId ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该专题暂无正式启用的问题" />
              ) : null}
            </Card>

            <Card title="平台与监测模式" className="geo-panel-card">
              <Checkbox.Group
                className="geo-platform-check-grid"
                value={[...validSelectedPlatformCodes]}
                disabled={!onPlatformSelectionChange}
                onChange={(values) =>
                  onPlatformSelectionChange?.(values.map(String))
                }
              >
                {platforms.map((platform) => (
                  <Checkbox
                    key={platform.code}
                    value={platform.code}
                    disabled={platform.availability !== 'AVAILABLE'}
                  >
                    <GeoPlatformBadge
                      platform={platform}
                      compact
                      showAvailability={platform.availability !== 'AVAILABLE'}
                    />
                  </Checkbox>
                ))}
              </Checkbox.Group>
              <Typography.Paragraph type="secondary" className="geo-field-help">
                维护中的平台禁止加入新任务，但历史报告仍会保留该平台结果。
              </Typography.Paragraph>

              <Radio.Group
                className="geo-mode-options"
                value={mode}
                disabled={!onModeChange}
                onChange={(event) =>
                  onModeChange?.(event.target.value as GeoMonitoringMode)
                }
              >
                <Radio value="STANDARD">
                  <span>
                    <strong>标准模式</strong>
                    <small>每个问题、每个平台采样 1 轮</small>
                  </span>
                </Radio>
                <Radio value="DEEP">
                  <span>
                    <strong>深度模式</strong>
                    <small>每个问题、每个平台采样 3 轮并分析稳定性</small>
                  </span>
                </Radio>
              </Radio.Group>
            </Card>
          </div>

          <div className="geo-manual__journey">
            {confirmation ? (
              <PaidTaskConfirmationCard
                confirmation={confirmation}
                onEdit={(cardId) => onEditConfirmation?.(cardId)}
                onConfirm={(cardId) => onConfirm?.(cardId)}
                onOpenRecharge={(cardId) => onOpenRecharge?.(cardId)}
                onPrepareReestimate={(cardId) => onPrepareReestimate?.(cardId)}
                onOpenPaidTaskReferenceAsset={() => undefined}
                onAddPaidTaskReferenceAsset={() => undefined}
              />
            ) : taskReceipt || taskProgress || taskOutcome ? (
              <>
                {taskReceipt ? (
                  <TaskCreatedReceiptCard
                    receipt={taskReceipt}
                    onViewTask={(taskId) => onViewTask?.(taskId)}
                  />
                ) : null}
                {taskProgress ? (
                  <ParallelTaskGroup
                    group={taskProgress}
                    onViewTask={(taskId) => onViewTask?.(taskId)}
                  />
                ) : null}
                {taskOutcome ? (
                  <TaskExecutionOutcomeGroup
                    group={taskOutcome}
                    onAction={(taskId, actionType) =>
                      onTaskOutcomeAction?.(taskId, actionType)
                    }
                  />
                ) : null}
                {reportId ? (
                  <Button type="primary" onClick={() => onViewReport?.(reportId)}>
                    查看 GEO 报告
                  </Button>
                ) : null}
              </>
            ) : (
              <Card title="监测与报价摘要" className="geo-panel-card geo-confirm-card">
                <div className="geo-confirm-card__headline">
                  <CheckCircleOutlined aria-hidden="true" />
                  <div>
                    <Typography.Text strong>请核对执行范围</Typography.Text>
                    <Typography.Text type="secondary">
                      所有数值以服务端当前报价为准
                    </Typography.Text>
                  </div>
                </div>
                <dl className="geo-summary-list">
                  <div>
                    <dt>问题数</dt>
                    <dd>{formatGeoCount(quote?.questionCount)}</dd>
                  </div>
                  <div>
                    <dt>可用平台数</dt>
                    <dd>{formatGeoCount(quote?.platformCount)}</dd>
                  </div>
                  <div>
                    <dt>采样轮数</dt>
                    <dd>{formatGeoCount(quote?.sampleRounds)}</dd>
                  </div>
                  <div>
                    <dt>预计调用单元</dt>
                    <dd>{formatGeoCount(quote?.estimatedUnits)}</dd>
                  </div>
                  <div>
                    <dt>预计积分</dt>
                    <dd>{formatGeoPoints(quote?.estimatedPoints)}</dd>
                  </div>
                  <div>
                    <dt>当前可用积分</dt>
                    <dd>{formatGeoPoints(quote?.availablePoints)}</dd>
                  </div>
                </dl>
                {quote?.pricingVersion ? (
                  <Typography.Text type="secondary">
                    报价版本：{quote.pricingVersion}
                  </Typography.Text>
                ) : null}
                <Alert
                  type="warning"
                  showIcon
                  icon={<ExclamationCircleOutlined />}
                  title="下一步将进入付费确认"
                  description={
                    quote?.settlementNotice ??
                    '完成付费确认后才会创建正式任务；任务创建后不可取消，最终积分按真实完成范围和服务端结算规则处理。'
                  }
                />
                {configurationNotice ? (
                  <Alert type="error" showIcon title={configurationNotice} />
                ) : null}
                <Space className="geo-confirm-card__actions">
                  <Button onClick={onBack} disabled={!onBack}>
                    返回修改
                  </Button>
                  <Button
                    type="primary"
                    icon={<RadarChartOutlined />}
                    loading={preparing}
                    disabled={!canConfirm}
                    onClick={confirm}
                  >
                    准备付费确认
                  </Button>
                </Space>
              </Card>
            )}
          </div>
        </div>
      </section>
    </GeoStateFrame>
  )
}

import { Button, Tag, Typography } from 'antd'
import type {
  TaskExecutionOutcomeGroupView,
  TaskExecutionOutcomeView,
  TaskOutcomeActionType,
  TaskOutcomeItemView,
} from '../types'

export interface TaskExecutionOutcomeGroupProps {
  group: TaskExecutionOutcomeGroupView
  onAction: (taskId: string, actionType: TaskOutcomeActionType) => void
}

const ACTION_LABELS = {
  VIEW_COMPLETED_REPORT: '查看已完成报告',
  VIEW_GEO_TREND: '查看该专题趋势',
  VIEW_GEO_COMPETITORS: '查看本次竞品洞察',
  CREATE_ARTICLE_FROM_GEO: '根据监测结果写文章',
  PREPARE_RETRY_FAILED_ITEMS: '仅重试失败项',
  PREPARE_ADJUST_AND_RETRY: '调整内容重新生成',
  PREPARE_RETRY: '稍后重试',
  CONTACT_SUPPORT: '联系支持',
} satisfies Record<TaskOutcomeActionType, string>

function formatPoints(value?: number) {
  return value !== undefined && Number.isSafeInteger(value) && value >= 0
    ? `${value.toLocaleString('zh-CN')} 积分`
    : value === undefined ? '暂不可查' : '—'
}

function TaskOutcomeItem({ item }: { item: TaskOutcomeItemView }) {
  return (
    <li className="task-outcome-item">
      <Typography.Text>{item.label}</Typography.Text>
      <Tag
        className={`task-outcome-item__status task-outcome-item__status--${item.statusTone}`}
      >
        {item.statusLabel}
      </Tag>
    </li>
  )
}

function TaskExecutionOutcomeCard({
  outcome,
  onAction,
}: {
  outcome: TaskExecutionOutcomeView
  onAction: (taskId: string, actionType: TaskOutcomeActionType) => void
}) {
  const hasResultItems = (outcome.resultItems?.length ?? 0) > 0
  const hasActions = (outcome.actions?.length ?? 0) > 0

  return (
    <li
      className="task-execution-outcome"
      aria-label={`任务执行结果：${outcome.taskType}`}
    >
      <header className="task-execution-outcome__header">
        <Typography.Title level={3}>{outcome.taskType}</Typography.Title>
        <Tag
          className={`task-execution-outcome__status task-execution-outcome__status--${outcome.statusTone}`}
        >
          {outcome.statusLabel}
        </Tag>
      </header>

      {outcome.completedAt ? (
        <Typography.Text
          className="task-execution-outcome__completed-at"
          type="secondary"
        >
          完成时间：{outcome.completedAt}
        </Typography.Text>
      ) : null}

      {outcome.summary ? (
        <Typography.Paragraph className="task-execution-outcome__summary">
          {outcome.summary}
        </Typography.Paragraph>
      ) : null}

      {outcome.geoDiagnosis ? (
        <div className="task-execution-outcome__geo-diagnosis">
          {outcome.geoDiagnosis.findings.length > 0 ? (
            <div>
              <Typography.Text strong>为什么需要优化</Typography.Text>
              <ul>
                {outcome.geoDiagnosis.findings.map((item, index) => (
                  <li key={`${outcome.taskId}:finding:${index}`}>{item}</li>
                ))}
              </ul>
            </div>
          ) : null}
          {outcome.geoDiagnosis.recommendations.length > 0 ? (
            <div>
              <Typography.Text strong>建议的内容方向</Typography.Text>
              <ul>
                {outcome.geoDiagnosis.recommendations.map((item, index) => (
                  <li key={`${outcome.taskId}:recommendation:${index}`}>
                    <strong>{item.title}</strong>
                    {item.rationale ? `：${item.rationale}` : ''}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
          {outcome.geoDiagnosis.caveat ? (
            <Typography.Paragraph type="secondary">
              {outcome.geoDiagnosis.caveat}
            </Typography.Paragraph>
          ) : null}
        </div>
      ) : null}

      {outcome.failureReason ? (
        <div
          className="task-execution-outcome__failure"
          role="note"
          aria-label={`${outcome.taskType}失败原因`}
        >
          <strong>失败原因</strong>
          <span>{outcome.failureReason}</span>
        </div>
      ) : null}

      {hasResultItems ? (
        <div className="task-execution-outcome__results">
          <Typography.Text strong>结果明细</Typography.Text>
          <ul aria-label={`${outcome.taskType}结果明细`}>
            {outcome.resultItems?.map((item) => (
              <TaskOutcomeItem key={item.id} item={item} />
            ))}
          </ul>
        </div>
      ) : null}

      <dl
        className="task-execution-outcome__settlement"
        aria-label={`${outcome.taskType}积分结算`}
      >
        <div>
          <dt>已结算积分</dt>
          <dd>{formatPoints(outcome.settlement.settledPoints)}</dd>
        </div>
        <div>
          <dt>已退回积分</dt>
          <dd>{formatPoints(outcome.settlement.returnedPoints)}</dd>
        </div>
      </dl>

      {hasActions ? (
        <div
          className="task-execution-outcome__actions"
          role="group"
          aria-label={`${outcome.taskType}恢复操作`}
        >
          {outcome.actions?.map((action, index) => (
            <Button
              key={`${index}-${action.type}`}
              disabled={!action.enabled}
              onClick={(event) => {
                if (action.type === 'CREATE_ARTICLE_FROM_GEO' && event.detail > 1) return
                if (action.enabled) {
                  onAction(outcome.taskId, action.type)
                }
              }}
            >
              {ACTION_LABELS[action.type]}
            </Button>
          ))}
        </div>
      ) : null}
      {outcome.actions?.filter((action) => action.disabledReason).map((action) => (
        <Typography.Paragraph key={action.type} type="secondary">{action.disabledReason}</Typography.Paragraph>
      ))}
    </li>
  )
}

export function TaskExecutionOutcomeGroup({
  group,
  onAction,
}: TaskExecutionOutcomeGroupProps) {
  if (group.outcomes.length === 0) {
    return null
  }

  return (
    <section
      className="task-execution-outcome-group"
      aria-label="任务执行结果与积分结算"
    >
      <ul className="task-execution-outcome-group__list">
        {group.outcomes.map((outcome) => (
          <TaskExecutionOutcomeCard
            key={outcome.taskId}
            outcome={outcome}
            onAction={onAction}
          />
        ))}
      </ul>
    </section>
  )
}

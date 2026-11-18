import {
  CheckCircleFilled,
  ClockCircleOutlined,
  CloseCircleFilled,
  DownOutlined,
  InfoCircleOutlined,
  LoadingOutlined,
  RightOutlined,
} from '@ant-design/icons'
import { useEffect, useId, useState } from 'react'
import type { ExecutionTimeline } from '../live/executionTimeline'
import { executionTimelineStepLabel } from '../live/executionTimeline'
import { businessOutcomeLabel } from '../live/businessOutcome'
import { taskTypeLabel } from '../live/chatAdapters'

export interface ChatExecutionTimelineProps {
  timeline: ExecutionTimeline
  outputStreaming?: boolean
}

function secondsLabel(elapsedMs: number) {
  return `${Math.max(0, Math.ceil(elapsedMs / 1_000))}s`
}

function statusSummary(timeline: ExecutionTimeline) {
  const taskName = timeline.taskType ? taskTypeLabel(timeline.taskType) : '任务'
  if (timeline.businessOutcome && !timeline.taskId && timeline.status !== 'STOPPED') {
    return businessOutcomeLabel(timeline.businessOutcome.status)
  }
  if (timeline.status === 'COMPLETED') return timeline.taskId ? `${taskName}已完成` : '本次回复已结束'
  if (timeline.status === 'PARTIAL') return timeline.taskId ? `${taskName}部分完成` : '本次回复部分完成'
  if (timeline.status === 'STOPPED') return '本次回复已停止'
  if (timeline.status === 'FAILED') return timeline.taskId ? `${taskName}未完成` : '本次回复未完成'
  if (timeline.status === 'WAITING_CONFIRMATION') return '任务草稿已准备，等待确认'
  const latestStep = timeline.steps.at(-1)
  return latestStep ? executionTimelineStepLabel(latestStep)
    : timeline.status === 'QUEUED' ? '任务已排队' : '正在处理你的消息'
}

export function ChatExecutionTimeline({ timeline, outputStreaming = false }: ChatExecutionTimelineProps) {
  const effectiveStatus = timeline.status !== 'STOPPED' && !timeline.taskId && timeline.businessOutcome
    ? timeline.businessOutcome.status === 'FAILED' ? 'FAILED'
      : timeline.businessOutcome.status === 'ANSWERED' ? 'COMPLETED'
        : timeline.businessOutcome.status === 'DRAFT_READY' ? 'WAITING_CONFIRMATION'
          : timeline.businessOutcome.status
    : timeline.status
  const [expanded, setExpanded] = useState(false)
  const stepsId = useId()
  const [now, setNow] = useState(() => Date.now())
  const active = ['RUNNING', 'QUEUED'].includes(effectiveStatus)

  useEffect(() => {
    if (!active) return
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [active])

  const elapsedMs = active
    ? Math.max(timeline.elapsedMs, now - Date.parse(timeline.startedAt), 0)
    : timeline.elapsedMs
  const currentStep = timeline.steps.at(-1)
  const summary = `${timeline.recordLabel ? `${timeline.recordLabel} · ` : ''}${active && outputStreaming ? '正在输出回复' : statusSummary(timeline)}`
  const count = active && currentStep?.current !== undefined && currentStep.total !== undefined
    ? `${currentStep.current}/${currentStep.total}` : undefined
  const announcement = count ? `${summary}，${count}` : summary

  return (
    <section
      className={`chat-execution-timeline chat-execution-timeline--${effectiveStatus.toLowerCase()}`}
      aria-label="执行动态"
    >
      <span className="chat-execution-timeline__announcement" role="status" aria-live="polite">
        {announcement}
      </span>
      <button
        type="button"
        className="chat-execution-timeline__summary"
        aria-expanded={expanded}
        aria-controls={stepsId}
        aria-label={`${expanded ? '收起执行动态' : '展开执行动态'}：${announcement}`}
        onClick={() => setExpanded((value) => !value)}
      >
        <span className="chat-execution-timeline__summary-icon" aria-hidden="true">
          {effectiveStatus === 'COMPLETED' && (timeline.taskId || timeline.businessOutcome?.status === 'ANSWERED') ? (
            <CheckCircleFilled />
          ) : effectiveStatus === 'FAILED' ? (
            <CloseCircleFilled />
          ) : active ? (
            <LoadingOutlined spin />
          ) : ['NEEDS_INPUT', 'PARTIAL'].includes(effectiveStatus) ? (
            <InfoCircleOutlined />
          ) : (
            <ClockCircleOutlined />
          )}
        </span>
        <span className="chat-execution-timeline__summary-label" aria-hidden="true">{summary}</span>
        {count ? <span className="chat-execution-timeline__count" aria-hidden="true">{count}</span> : null}
        <span className="chat-execution-timeline__elapsed" aria-hidden="true">{secondsLabel(elapsedMs)}</span>
        <span className="chat-execution-timeline__toggle" aria-hidden="true">
          {expanded ? <DownOutlined /> : <RightOutlined />}
        </span>
      </button>

      {expanded ? (
        <div id={stepsId}>
        {!timeline.taskId ? <p className="chat-execution-timeline__caption">思考与处理过程<span>展示实际处理阶段与工具操作</span></p> : null}
        <ol className="chat-execution-timeline__steps">
          {timeline.steps.length === 0 ? (
            <li className="chat-execution-timeline__empty">{active ? '请求已开始处理' : '暂无详细过程记录'}</li>
          ) : (
            timeline.steps.map((step) => {
              const stillRunning = active && step.id === currentStep?.id && step.status === 'RUNNING'
              const historical = step.status === 'RUNNING' && !stillRunning
              const label = executionTimelineStepLabel(step, historical)
              return (
                <li
                  key={step.id}
                  className={`chat-execution-timeline__step chat-execution-timeline__step--${historical ? 'waiting' : step.status.toLowerCase()}`}
                >
                  <span className="chat-execution-timeline__step-icon" aria-hidden="true">
                    {step.status === 'SUCCEEDED' ? (
                      <CheckCircleFilled />
                    ) : step.status === 'FAILED' ? (
                      <CloseCircleFilled />
                    ) : stillRunning ? (
                      <LoadingOutlined spin />
                    ) : (
                      <ClockCircleOutlined />
                    )}
                  </span>
                  <span>{label}</span>
                  {step.current !== undefined && step.total !== undefined ? (
                    <span className="chat-execution-timeline__count">
                      {step.current}/{step.total}
                    </span>
                  ) : null}
                </li>
              )
            })
          )}
        </ol>
        </div>
      ) : null}
    </section>
  )
}

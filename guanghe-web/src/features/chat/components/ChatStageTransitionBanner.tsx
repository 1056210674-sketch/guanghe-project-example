import { ArrowRightOutlined, CheckCircleFilled } from '@ant-design/icons'
import { Tag } from 'antd'
import type { ChatStageTransitionView, ChatStageView } from '../types'

export interface ChatStageTransitionBannerProps {
  transition: ChatStageTransitionView
}

function StageSummary({
  stage,
  current,
}: {
  stage: ChatStageView
  current: boolean
}) {
  return (
    <div
      className={`chat-stage-transition__stage${
        current ? ' chat-stage-transition__stage--current' : ''
      }`}
      aria-label={`${stage.label}：${stage.statusLabel}`}
    >
      {!current && stage.statusTone === 'success' ? (
        <CheckCircleFilled aria-hidden="true" />
      ) : null}
      <strong>{stage.label}</strong>
      <Tag
        className={`chat-stage-transition__status chat-stage-transition__status--${stage.statusTone}`}
      >
        {stage.statusLabel}
      </Tag>
    </div>
  )
}

export function ChatStageTransitionBanner({
  transition,
}: ChatStageTransitionBannerProps) {
  return (
    <section className="chat-stage-transition" aria-label="对话阶段切换">
      <span className="chat-stage-transition__segment-label">上一阶段</span>
      <StageSummary stage={transition.previousStage} current={false} />
      <ArrowRightOutlined
        className="chat-stage-transition__arrow"
        aria-label="切换至"
      />
      <span className="chat-stage-transition__segment-label">当前阶段</span>
      <StageSummary stage={transition.currentStage} current />
    </section>
  )
}

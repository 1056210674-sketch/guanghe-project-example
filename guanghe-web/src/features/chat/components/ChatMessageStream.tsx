import {
  ArrowRightOutlined,
  FileTextOutlined,
  NotificationOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { Link } from 'react-router-dom'
import { appLinks } from '../../../routes/paths'
import type {
  ChatMessageView,
  GeneratedVideoPlaybackSourceView,
  TaskOutcomeActionType,
} from '../types'
import { ArticleDraftPreviewCard } from './ArticleDraftPreviewCard'
import { ChatArticlePreparationCard } from './ChatArticlePreparationCard'
import type { ArticleDraftPreviewCardProps } from './ArticleDraftPreviewCard'
import { ChatExecutionTimeline } from './ChatExecutionTimeline'
import { ChatGeoContextCard } from './ChatGeoContextCard'
import { ChatResourceSelectionCard } from './ChatResourceSelectionCard'
import { businessOutcomeLabel } from '../live/businessOutcome'
import { geoDiagnosticMessageKey, placeGeoDiagnosticMessages } from '../live/diagnosticMessagePlacement'
import { GeneratedImageResultGallery } from './GeneratedImageResultGallery'
import { GeneratedVideoResultPlayer } from './GeneratedVideoResultPlayer'
import { MarkdownMessageContent } from './MarkdownMessageContent'
import { ParallelTaskGroup } from './ParallelTaskGroup'
import { PaidTaskConfirmationCard } from './PaidTaskConfirmationCard'
import { TaskCreatedReceiptCard } from './TaskCreatedReceiptCard'
import { TaskExecutionOutcomeGroup } from './TaskExecutionOutcomeGroup'
import { GeoMessageAvatar, GeoPendingReply } from '../mascot/GeoMascot'
import { useGeoMascotConversation } from '../mascot/geoMascotContext'
import { AssistantAvatar, type AssistantAvatarVariant } from './AssistantAvatar'
import type { ReactNode } from 'react'

export interface ChatMessageStreamProps {
  assistantAvatarVariant?: AssistantAvatarVariant
  renderGeoDiagnosticCard?: (reference: NonNullable<ChatMessageView['geoDiagnostic']>, messageId: string) => ReactNode
  onOpenArticlePreparation?: (id: string) => void
  messages: readonly ChatMessageView[]
  preparingGeoArticleMessageId?: string
  geoArticleDirections?: Readonly<Record<string, string>>
  onGeoArticleDirectionChange?: (messageId: string, value: string) => void
  onGeoReportAction?: (messageId: string, action: 'VIEW_REPORT' | 'PREPARE_ARTICLE') => void
  onOpenGeoTopic?: (topicId?: string, configureMonitoring?: boolean) => void
  onSelectClarificationOption: (messageId: string, optionId: string) => void
  onSelectResourceCandidate?: (messageId: string, candidateSetId: string, choiceId: string) => void
  resourceSelectionBusy?: boolean
  onEditPaidTaskConfirmation: (cardId: string) => void
  onConfirmPaidTaskConfirmation: (cardId: string) => void
  onOpenRecharge: (cardId: string) => void
  onPrepareReestimate: (cardId: string) => void
  onOpenPaidTaskReferenceAsset: (cardId: string, assetId: string) => void
  onAddPaidTaskReferenceAsset: (cardId: string) => void
  onViewTask: (taskId: string) => void
  onPreviewArticle: (articleId: string) => void
  onContinueEditingArticle: (articleId: string) => void
  onSaveArticleToAssets: (articleId: string) => void
  onConfirmArticleTitle?: ArticleDraftPreviewCardProps['onConfirmArticleTitle']
  onRegenerateArticleTitles?: (articleId: string) => void
  onReviseArticleCreativePlan?: ArticleDraftPreviewCardProps['onReviseArticleCreativePlan']
  onConfirmArticleCreativePlan?: ArticleDraftPreviewCardProps['onConfirmArticleCreativePlan']
  onRegenerateArticleCreativePlan?: ArticleDraftPreviewCardProps['onRegenerateArticleCreativePlan']
  onConfirmArticleBody?: ArticleDraftPreviewCardProps['onConfirmArticleBody']
  recommendedSubmissionBusyArticleId?: string
  recommendedSubmissionBlockedArticleIds?: readonly string[]
  onConfirmRecommendedSubmission?: ArticleDraftPreviewCardProps['onConfirmRecommendedSubmission']
  onRefreshMediaRecommendations?: ArticleDraftPreviewCardProps['onRefreshMediaRecommendations']
  onPreviewGeneratedImage: (assetId: string) => void
  onDownloadGeneratedImage: (assetId: string) => void
  onSaveGeneratedImageToAssets: (assetId: string) => void
  onSelectGeneratedImage: (assetId: string) => void
  onOpenGeneratedVideo?: (assetId: string) => void
  onRefreshGeneratedVideo?: (
    assetId: string,
  ) => Promise<GeneratedVideoPlaybackSourceView>
  onTaskOutcomeAction: (
    taskId: string,
    actionType: TaskOutcomeActionType,
  ) => void
}

function MessageAvatar({ role, messageId, secondary, variant }: {
  role: ChatMessageView['role']
  messageId?: string
  secondary?: boolean
  variant?: AssistantAvatarVariant
}) {
  const geo = useGeoMascotConversation()
  if (role === 'ASSISTANT' && geo) return <GeoMessageAvatar messageId={messageId} secondary={secondary} />
  if (role === 'ASSISTANT') return <AssistantAvatar variant={variant} />
  return (
    <span className="chat-message__avatar" aria-hidden="true">
      <UserOutlined />
    </span>
  )
}

export function ChatMessageStream({
  assistantAvatarVariant,
  renderGeoDiagnosticCard,
  onOpenArticlePreparation,
  messages,
  preparingGeoArticleMessageId,
  geoArticleDirections,
  onGeoArticleDirectionChange,
  onGeoReportAction,
  onOpenGeoTopic,
  onSelectClarificationOption,
  onSelectResourceCandidate,
  resourceSelectionBusy,
  onEditPaidTaskConfirmation,
  onConfirmPaidTaskConfirmation,
  onOpenRecharge,
  onPrepareReestimate,
  onOpenPaidTaskReferenceAsset,
  onAddPaidTaskReferenceAsset,
  onViewTask,
  onPreviewArticle,
  onContinueEditingArticle,
  onSaveArticleToAssets,
  onConfirmArticleTitle,
  onRegenerateArticleTitles,
  onReviseArticleCreativePlan,
  onConfirmArticleCreativePlan,
  onRegenerateArticleCreativePlan,
  onConfirmArticleBody,
  recommendedSubmissionBusyArticleId,
  recommendedSubmissionBlockedArticleIds,
  onConfirmRecommendedSubmission,
  onRefreshMediaRecommendations,
  onPreviewGeneratedImage,
  onDownloadGeneratedImage,
  onSaveGeneratedImageToAssets,
  onSelectGeneratedImage,
  onOpenGeneratedVideo = () => undefined,
  onRefreshGeneratedVideo,
  onTaskOutcomeAction,
}: ChatMessageStreamProps) {
  const geo = useGeoMascotConversation()
  return (
    <div className="chat-message-stream" aria-label="消息列表">
      {placeGeoDiagnosticMessages(messages).map((message) => {
        const hasClarificationOptions = message.role === 'ASSISTANT' &&
          (message.clarificationOptions?.length ?? 0) > 0
        const hasClarification = hasClarificationOptions || Boolean(message.guidance)
        const taskCreatedReceipt =
          message.role === 'ASSISTANT'
            ? message.taskCreatedReceipt
            : undefined
        const paidTaskConfirmation =
          message.role === 'ASSISTANT' && !taskCreatedReceipt
            ? message.paidTaskConfirmation
            : undefined
        const articleDraftPreview =
          message.role === 'ASSISTANT'
            ? message.articleDraftPreview
            : undefined
        const parallelTaskGroup =
          message.role === 'ASSISTANT' &&
          (message.parallelTaskGroup?.tasks.length ?? 0) > 0
            ? message.parallelTaskGroup
            : undefined
        const generatedImageResultGroup =
          message.role === 'ASSISTANT' &&
          (message.generatedImageResultGroup?.images.length ?? 0) > 0
            ? message.generatedImageResultGroup
            : undefined
        const generatedVideoResult =
          message.role === 'ASSISTANT' ? message.generatedVideoResult : undefined
        const taskExecutionOutcomeGroup =
          message.role === 'ASSISTANT' &&
          (message.taskExecutionOutcomeGroup?.outcomes.length ?? 0) > 0
            ? message.taskExecutionOutcomeGroup
            : undefined
        const executionTimeline =
          message.role === 'ASSISTANT' ? message.executionTimeline : undefined
        const backgroundTaskNotification =
          message.role === 'ASSISTANT'
            ? message.backgroundTaskNotification
            : undefined
        const primaryTaskId =
          backgroundTaskNotification?.taskId ??
          taskCreatedReceipt?.taskId ??
          parallelTaskGroup?.tasks[0]?.taskId ??
          taskExecutionOutcomeGroup?.outcomes[0]?.taskId ??
          executionTimeline?.taskId

        const messageContent = (
          <div className="chat-message__content">
            {message.role === 'USER' && message.referencedGeoReportId ? (
              <Link className="chat-message__report-reference" to={appLinks.geoReportDetail(message.referencedGeoReportId)}>
                <FileTextOutlined aria-hidden="true" /> 引用的监测报告
              </Link>
            ) : null}
            {executionTimeline ? (
              <ChatExecutionTimeline
                key={executionTimeline.runId ?? executionTimeline.id}
                outputStreaming={message.outputStreaming}
                timeline={message.businessOutcome
                  ? { ...executionTimeline, businessOutcome: message.businessOutcome }
                  : executionTimeline}
              />
            ) : message.role === 'ASSISTANT' && message.businessOutcome && message.businessOutcome.status !== 'ANSWERED' ? (
              <p className="chat-message__business-outcome" role="status">{businessOutcomeLabel(message.businessOutcome.status)}</p>
            ) : null}
            {message.content ? (
              <div className={`chat-message__body${message.outputStreaming ? ' chat-message__body--streaming' : ''}`} aria-busy={message.outputStreaming || undefined}>
                <MarkdownMessageContent content={message.content} />
                {message.outputStreaming ? <span className="chat-message__output-status" role="status">正文输出中</span> : null}
              </div>
            ) : null}
            {message.role === 'ASSISTANT' && message.geoContext ? <ChatGeoContextCard
              context={message.geoContext}
              preparingArticle={preparingGeoArticleMessageId === message.id}
              direction={geoArticleDirections?.[message.id]}
              onDirectionChange={(value) => onGeoArticleDirectionChange?.(message.id, value)}
              onViewReport={() => onGeoReportAction?.(message.id, 'VIEW_REPORT')}
              onPrepareArticle={() => onGeoReportAction?.(message.id, 'PREPARE_ARTICLE')}
              onSelectTopic={(topicId) => onOpenGeoTopic?.(topicId, message.geoContext?.status === 'NO_REPORT')}
              onOpenTopics={() => onOpenGeoTopic?.()}
            /> : null}
            {message.articlePreparation ? <ChatArticlePreparationCard preparation={message.articlePreparation} onOpen={onOpenArticlePreparation} /> : null}
            {message.geoDiagnostic ? renderGeoDiagnosticCard?.(message.geoDiagnostic, message.id) : null}
            {articleDraftPreview && !message.articlePreparation ? (
              <ArticleDraftPreviewCard
                article={articleDraftPreview}
                onPreviewArticle={onPreviewArticle}
                onContinueEditingArticle={onContinueEditingArticle}
                onSaveArticleToAssets={onSaveArticleToAssets}
                onConfirmArticleTitle={onConfirmArticleTitle}
                onRegenerateArticleTitles={onRegenerateArticleTitles}
                onReviseArticleCreativePlan={onReviseArticleCreativePlan}
                onConfirmArticleCreativePlan={onConfirmArticleCreativePlan}
                onRegenerateArticleCreativePlan={
                  onRegenerateArticleCreativePlan
                }
                onConfirmArticleBody={onConfirmArticleBody}
                recommendedSubmissionBusy={
                  recommendedSubmissionBusyArticleId ===
                  articleDraftPreview.id
                }
                recommendedSubmissionBlocked={
                  recommendedSubmissionBlockedArticleIds?.includes(
                    articleDraftPreview.id,
                  )
                }
                onConfirmRecommendedSubmission={
                  onConfirmRecommendedSubmission
                }
                onRefreshMediaRecommendations={
                  onRefreshMediaRecommendations
                }
              />
            ) : null}
            {taskCreatedReceipt ? (
              <TaskCreatedReceiptCard
                receipt={taskCreatedReceipt}
                onViewTask={onViewTask}
              />
            ) : null}
            {paidTaskConfirmation ? (
              <PaidTaskConfirmationCard
                confirmation={paidTaskConfirmation}
                onEdit={onEditPaidTaskConfirmation}
                onConfirm={onConfirmPaidTaskConfirmation}
                onOpenRecharge={onOpenRecharge}
                onPrepareReestimate={onPrepareReestimate}
                onOpenPaidTaskReferenceAsset={
                  onOpenPaidTaskReferenceAsset
                }
                onAddPaidTaskReferenceAsset={onAddPaidTaskReferenceAsset}
              />
            ) : null}
            {parallelTaskGroup ? (
              <ParallelTaskGroup
                group={parallelTaskGroup}
                onViewTask={onViewTask}
              />
            ) : null}
            {taskExecutionOutcomeGroup ? (
              <TaskExecutionOutcomeGroup
                group={taskExecutionOutcomeGroup}
                onAction={onTaskOutcomeAction}
              />
            ) : null}
            {generatedImageResultGroup ? (
              <GeneratedImageResultGallery
                group={generatedImageResultGroup}
                onPreviewGeneratedImage={onPreviewGeneratedImage}
                onDownloadGeneratedImage={onDownloadGeneratedImage}
                onSaveGeneratedImageToAssets={
                  onSaveGeneratedImageToAssets
                }
                onSelectGeneratedImage={onSelectGeneratedImage}
              />
            ) : null}
            {generatedVideoResult ? (
              <GeneratedVideoResultPlayer
                video={generatedVideoResult}
                onOpenOriginal={onOpenGeneratedVideo}
                onRefreshPlaybackUrl={onRefreshGeneratedVideo}
              />
            ) : null}
            {message.createdAt ? <time>{message.createdAt}</time> : null}
          </div>
        )

        return (
          <article
            key={geoDiagnosticMessageKey(message) ?? message.id}
            data-chat-task-id={primaryTaskId}
            data-article-workflow-id={
              articleDraftPreview?.id ?? message.articleWorkflowAnchor?.articleId
            }
            className={`chat-message chat-message--${message.role.toLowerCase()}${
              hasClarification ? ' chat-message--clarification' : ''
            }${paidTaskConfirmation ? ' chat-message--paid-task' : ''}${
              taskCreatedReceipt ? ' chat-message--task-created' : ''
            }${articleDraftPreview ? ' chat-message--article-draft' : ''}${
              parallelTaskGroup ? ' chat-message--parallel-tasks' : ''
            }${
              generatedImageResultGroup
                ? ' chat-message--generated-images'
                : ''
            }${
              generatedVideoResult
                ? ' chat-message--generated-video'
                : ''
            }${
              taskExecutionOutcomeGroup
                ? ' chat-message--task-outcomes'
                : ''
            }${message.geoDiagnostic ? ' chat-message--geo-diagnostic' : ''
            }${executionTimeline ? ' chat-message--execution-timeline' : ''
            }${
              backgroundTaskNotification
                ? ' chat-message--background-task-notification'
                : ''
            }`}
            role={
              backgroundTaskNotification?.announce ? 'status' : undefined
            }
            aria-live={
              backgroundTaskNotification?.announce ? 'polite' : undefined
            }
            aria-label={
              backgroundTaskNotification
                ? `${backgroundTaskNotification.label}：${backgroundTaskNotification.title}`
                : message.role === 'ASSISTANT'
                  ? '光禾AI智能体框架消息'
                  : '用户消息'
            }
          >
            {backgroundTaskNotification ? (
              <div className="chat-background-task-notification">
                <header className="chat-background-task-notification__header">
                  {geo || assistantAvatarVariant === 'creative' ? <MessageAvatar role="ASSISTANT" messageId={message.id} variant={assistantAvatarVariant} /> : <span
                    className="chat-background-task-notification__icon"
                    aria-hidden="true"
                  >
                    <NotificationOutlined />
                  </span>}
                  <span className="chat-background-task-notification__copy">
                    <span className="chat-background-task-notification__label">
                      {backgroundTaskNotification.label}
                    </span>
                    <strong>{backgroundTaskNotification.title}</strong>
                    <span>{backgroundTaskNotification.description}</span>
                  </span>
                </header>
                {messageContent}
              </div>
            ) : (
              <div className="chat-message__row">
                <MessageAvatar role={message.role} messageId={message.id} variant={assistantAvatarVariant} />
                {messageContent}
              </div>
            )}

            {message.role === 'ASSISTANT' && message.resourceSelection ? <ChatResourceSelectionCard
              selection={message.resourceSelection}
              busy={resourceSelectionBusy}
              onSelect={onSelectResourceCandidate
                ? (candidateSetId, choiceId) => onSelectResourceCandidate(message.id, candidateSetId, choiceId)
                : undefined}
            /> : null}
            {hasClarificationOptions ? (
              <div
                className="chat-clarification-options"
                role="group"
                aria-label="补问选项"
              >
                {message.clarificationOptions?.map((option) => (
                  <button
                    key={option.id}
                    type="button"
                    className="chat-clarification-option"
                    disabled={option.disabled}
                    onClick={() =>
                      onSelectClarificationOption(message.id, option.id)
                    }
                  >
                    <span
                      className="chat-clarification-option__icon"
                      aria-hidden="true"
                    >
                      {option.icon}
                    </span>
                    <strong>{option.title}</strong>
                    <span className="chat-clarification-option__description">
                      {option.description}
                    </span>
                    <ArrowRightOutlined
                      className="chat-clarification-option__arrow"
                      aria-hidden="true"
                    />
                  </button>
                ))}
              </div>
            ) : null}

            {message.guidance ? (
              <div className="chat-message__guidance-row">
                <MessageAvatar role="ASSISTANT" secondary variant={assistantAvatarVariant} />
                <div className="chat-message__body chat-message__guidance">
                  <MarkdownMessageContent content={message.guidance} />
                </div>
              </div>
            ) : null}
          </article>
        )
      })}
      <GeoPendingReply />
    </div>
  )
}

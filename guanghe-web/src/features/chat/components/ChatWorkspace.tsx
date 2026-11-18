import { ArrowDownOutlined, CloseOutlined, FileTextOutlined, RightOutlined } from '@ant-design/icons'
import { Alert, Button, Drawer, Space, Spin, Tag, Typography } from 'antd'
import { useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import type {
  ActiveArticleWorkflowView,
  ChatHistoricalContextView,
  ChatMessageView,
  GeneratedVideoPlaybackSourceView,
  ChatRecommendationView,
  ChatSessionView,
  ChatStageTransitionView,
  ChatTaskFocusOptionView,
  ChatTaskStatusSummaryView,
  ChatTaskContextView,
  TaskOutcomeActionType,
} from '../types'
import type { ChatComposerAttachmentView } from '../creativeGrounding'
import { ChatComposer } from './ChatComposer'
import { ChatContextPanel } from './ChatContextPanel'
import { ChatMessageStream } from './ChatMessageStream'
import type { ChatMessageStreamProps } from './ChatMessageStream'
import { ChatSessionPanel } from './ChatSessionPanel'
import { ChatStageTransitionBanner } from './ChatStageTransitionBanner'
import { GeoMessageAvatar, GeoPendingReply } from '../mascot/GeoMascot'
import { GEO_PENDING_REPLY, useGeoMascotConversation } from '../mascot/geoMascotContext'
import { AssistantAvatar, type AssistantAvatarVariant } from './AssistantAvatar'

export interface ChatWorkspaceProps {
  renderGeoDiagnosticCard?: ChatMessageStreamProps['renderGeoDiagnosticCard']
  onOpenArticlePreparation?: ChatMessageStreamProps['onOpenArticlePreparation']
  title: string
  navigationInShell?: boolean
  platformHome?: boolean
  platformWelcomeTitle?: string
  platformWelcomeDescription?: string
  assistantAvatarVariant?: AssistantAvatarVariant
  identityControls?: ReactNode
  emptyWelcome?: ReactNode
  taskActions?: ReactNode
  activeArticleWorkflow?: ActiveArticleWorkflowView
  sessions: readonly ChatSessionView[]
  messages: readonly ChatMessageView[]
  recommendations: readonly ChatRecommendationView[]
  preparingGeoArticleMessageId?: ChatMessageStreamProps['preparingGeoArticleMessageId']
  geoArticleDirections?: ChatMessageStreamProps['geoArticleDirections']
  onGeoArticleDirectionChange?: ChatMessageStreamProps['onGeoArticleDirectionChange']
  onGeoReportAction?: ChatMessageStreamProps['onGeoReportAction']
  onOpenGeoTopic?: ChatMessageStreamProps['onOpenGeoTopic']
  onOpenGeoTrends?: () => void
  onOpenGeoCompetitors?: () => void
  geoReportSourceLabel?: string
  geoReportSourceLoading?: boolean
  geoReportSourceNotice?: string
  onRemoveGeoReportSource?: () => void
  onViewGeoReportSource?: () => void
  onChooseGeoReport?: () => void
  onCreatePeriodReport?: () => void
  onCreateDiagnosticReport?: () => void
  selectedSessionId?: string
  selectedProductId?: string
  taskFocusOptions: readonly ChatTaskFocusOptionView[]
  taskStatusSummaries: readonly ChatTaskStatusSummaryView[]
  selectedTaskId?: string
  stageTransition?: ChatStageTransitionView
  currentTaskContext?: ChatTaskContextView
  historicalTaskContext?: ChatHistoricalContextView
  historicalContextExpanded?: boolean
  sessionSearchValue: string
  draft: string
  isGenerating: boolean
  sendEnabled: boolean
  sendFeedback?: string
  currentAttachments?: readonly ChatComposerAttachmentView[]
  attachmentUploadEnabled?: boolean
  attachmentUploadDisabledReason?: string
  sessionLoading?: boolean
  sessionError?: string
  messagesLoading?: boolean
  workspaceError?: string
  onNewConversation: () => void
  onSessionSearchChange: (value: string) => void
  onSelectSession: (id: string) => void
  onRenameSession?: (id: string) => void
  onDeleteSession?: (id: string) => void
  onSelectTaskFocus: (taskId: string) => void
  onRecommendationSelect: (prompt: string, submitOnSelect?: boolean) => void
  onDraftChange: (value: string) => void
  onSend: (message: string) => void
  onStop: () => void
  onStopAndSend?: (message: string) => void
  stopPending?: boolean
  composerNotice?: string
  onRestorePendingMessage?: () => void
  hasEarlierMessages?: boolean
  earlierMessagesLoading?: boolean
  onLoadEarlierMessages?: () => void
  onSelectAttachmentFiles?: (files: readonly File[]) => void
  onChooseAttachmentAssets?: () => void
  onRemoveAttachment?: (assetId: string) => void
  onAddProjectDocument?: () => void
  onAddProjectExcerpt?: () => void
  onOpenProjectKnowledge?: () => void
  onSelectClarificationOption: (messageId: string, optionId: string) => void
  onSelectResourceCandidate?: ChatMessageStreamProps['onSelectResourceCandidate']
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
  onConfirmArticleTitle?: ChatMessageStreamProps['onConfirmArticleTitle']
  onRegenerateArticleTitles?: (articleId: string) => void
  onReviseArticleCreativePlan?: ChatMessageStreamProps['onReviseArticleCreativePlan']
  onConfirmArticleCreativePlan?: ChatMessageStreamProps['onConfirmArticleCreativePlan']
  onRegenerateArticleCreativePlan?: ChatMessageStreamProps['onRegenerateArticleCreativePlan']
  onConfirmArticleBody?: ChatMessageStreamProps['onConfirmArticleBody']
  recommendedSubmissionBusyArticleId?: string
  recommendedSubmissionBlockedArticleIds?: readonly string[]
  onConfirmRecommendedSubmission?: ChatMessageStreamProps['onConfirmRecommendedSubmission']
  onRefreshMediaRecommendations?: ChatMessageStreamProps['onRefreshMediaRecommendations']
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
  onToggleHistoricalContext: (contextId: string, expanded: boolean) => void
  onOpenReferenceAsset: (assetId: string) => void
}

function ChatEmptyWelcome({
  recommendations,
  onRecommendationSelect,
  sendEnabled,
  genericAgent = false,
  title = '你好，我是光禾AI智能体框架。',
  description = '可以直接提问，或告诉我你想做什么。不必先填完资料；需要的信息我会再问你。',
  assistantAvatarVariant,
}: {
  recommendations: readonly ChatRecommendationView[]
  onRecommendationSelect: ChatWorkspaceProps['onRecommendationSelect']
  sendEnabled: boolean
  genericAgent?: boolean
  title?: string
  description?: string
  assistantAvatarVariant?: AssistantAvatarVariant
}) {
  const geo = useGeoMascotConversation()
  return (
    <div className="chat-empty-welcome">
      {geo ? <GeoMessageAvatar /> : <AssistantAvatar placement="welcome" variant={assistantAvatarVariant} />}
      <Typography.Title level={2}>{title}</Typography.Title>
      <Typography.Paragraph type="secondary">
        {description}
      </Typography.Paragraph>
      {!genericAgent ? <Typography.Paragraph type="secondary">可以先聊优化思路，或明确告诉我需要监测、写文章。品牌资料会在项目中持续积累，投稿前由你确认。</Typography.Paragraph> : null}

      <div className="chat-recommendations" aria-label="推荐输入入口">
        {recommendations.map((recommendation) => (
          <button
            key={recommendation.id}
            type="button"
            className="chat-recommendation"
            aria-label={recommendation.title}
            disabled={recommendation.submitOnSelect && !sendEnabled}
            onClick={(event) => {
              if (recommendation.submitOnSelect && event.detail > 1) return
              onRecommendationSelect(recommendation.prompt, recommendation.submitOnSelect)
            }}
          >
            <span className="chat-recommendation__icon" aria-hidden="true">
              {recommendation.icon}
            </span>
            <strong>{recommendation.title}</strong>
            <span>{recommendation.description}</span>
          </button>
        ))}
      </div>
    </div>
  )
}

export function ChatWorkspace({
  renderGeoDiagnosticCard,
  onOpenArticlePreparation,
  title,
  navigationInShell = false,
  platformHome = false,
  platformWelcomeTitle,
  platformWelcomeDescription,
  assistantAvatarVariant,
  identityControls,
  emptyWelcome,
  taskActions,
  activeArticleWorkflow,
  sessions,
  messages,
  recommendations,
  preparingGeoArticleMessageId,
  geoArticleDirections,
  onGeoArticleDirectionChange,
  onGeoReportAction,
  onOpenGeoTopic,
  onOpenGeoTrends,
  onOpenGeoCompetitors,
  geoReportSourceLabel,
  geoReportSourceLoading,
  geoReportSourceNotice,
  onChooseGeoReport,
  onCreatePeriodReport,
  onCreateDiagnosticReport,
  onRemoveGeoReportSource,
  onViewGeoReportSource,
  selectedSessionId,
  selectedProductId,
  taskFocusOptions,
  taskStatusSummaries,
  selectedTaskId,
  stageTransition,
  currentTaskContext,
  historicalTaskContext,
  historicalContextExpanded,
  sessionSearchValue,
  draft,
  isGenerating,
  sendEnabled,
  sendFeedback,
  currentAttachments,
  attachmentUploadEnabled,
  attachmentUploadDisabledReason,
  sessionLoading,
  sessionError,
  messagesLoading,
  workspaceError,
  onNewConversation,
  onSessionSearchChange,
  onSelectSession,
  onRenameSession,
  onDeleteSession,
  onSelectTaskFocus,
  onRecommendationSelect,
  onDraftChange,
  onSend,
  onStop,
  onStopAndSend,
  stopPending,
  composerNotice,
  onRestorePendingMessage,
  hasEarlierMessages,
  earlierMessagesLoading,
  onLoadEarlierMessages,
  onSelectAttachmentFiles,
  onChooseAttachmentAssets,
  onRemoveAttachment,
  onAddProjectDocument,
  onAddProjectExcerpt,
  onOpenProjectKnowledge,
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
  onToggleHistoricalContext,
  onOpenReferenceAsset,
}: ChatWorkspaceProps) {
  const geo = useGeoMascotConversation()
  const pendingReply = geo?.pose.messageId === GEO_PENDING_REPLY
  const conversationContentRef = useRef<HTMLDivElement>(null)
  const followLatestRef = useRef(true)
  const previousSessionRef = useRef(selectedSessionId)
  const [openPanel, setOpenPanel] = useState<'sessions' | 'context'>()
  const [scrollState, setScrollState] = useState({ sessionId: selectedSessionId, following: true })
  const followingLatest = scrollState.sessionId !== selectedSessionId || scrollState.following

  const handleSelectTaskFocus = (taskId: string) => {
    onSelectTaskFocus(taskId)
    const targets = conversationContentRef.current?.querySelectorAll<HTMLElement>(
      '[data-chat-task-id]',
    )
    const target = targets
      ? Array.from(targets)
          .filter((item) => item.dataset.chatTaskId === taskId)
          .at(-1)
      : undefined
    target?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }

  const hasTaskContext = Boolean(taskFocusOptions.length || taskStatusSummaries.length
    || currentTaskContext || historicalTaskContext)
  const showConversationHeader = platformHome || !navigationInShell

  useLayoutEffect(() => {
    const content = conversationContentRef.current
    if (!content) return

    if (previousSessionRef.current !== selectedSessionId) {
      previousSessionRef.current = selectedSessionId
      followLatestRef.current = true
    }

    if (followLatestRef.current) {
      content.scrollTop = content.scrollHeight
    }
  }, [messages, selectedSessionId, pendingReply])

  useLayoutEffect(() => {
    const content = conversationContentRef.current
    if (!content || typeof ResizeObserver === 'undefined') return
    // Keep the bottom anchored as the composer grows, without moving a user
    // who has deliberately scrolled back to read earlier messages.
    const observer = new ResizeObserver(() => {
      if (followLatestRef.current) content.scrollTop = content.scrollHeight
    })
    observer.observe(content)
    return () => observer.disconnect()
  }, [])

  return (
    <section className={`chat-workspace${navigationInShell ? ' chat-workspace--platform' : ''}${platformHome ? ' chat-workspace--home' : ''}${emptyWelcome ? ' chat-workspace--onboarding' : ''}${openPanel ? ` chat-workspace--${openPanel}-open` : ''}`} aria-label="AI 对话工作区">
      {!navigationInShell ? <div className="chat-workspace__sessions" id="chat-sessions-panel">
      <ChatSessionPanel
        sessions={sessions}
        selectedSessionId={selectedSessionId}
        searchValue={sessionSearchValue}
        onNewConversation={onNewConversation}
        onSearchChange={onSessionSearchChange}
        onSelectSession={(id) => { onSelectSession(id); setOpenPanel(undefined) }}
        loading={sessionLoading}
        error={sessionError}
        onRenameSession={onRenameSession}
        onDeleteSession={onDeleteSession}
      />
      </div> : null}

      <section className="chat-conversation" aria-labelledby={showConversationHeader ? 'chat-title' : undefined} aria-label={showConversationHeader ? undefined : title}>
        {showConversationHeader ? <header className="chat-conversation__header">
          <div className="chat-conversation__heading">
          <div className="chat-conversation__panel-actions">
            {!navigationInShell ? <Button htmlType="button" aria-controls="chat-sessions-panel" aria-expanded={openPanel === 'sessions'} onClick={() => setOpenPanel(openPanel === 'sessions' ? undefined : 'sessions')}>历史对话</Button> : null}
            {hasTaskContext ? <Button htmlType="button" aria-controls="chat-context-panel" aria-expanded={openPanel === 'context'} onClick={() => setOpenPanel(openPanel === 'context' ? undefined : 'context')}>任务进度</Button> : null}
          </div>
          <Typography.Title id="chat-title" level={1}>
            {title}
          </Typography.Title>
          {identityControls}
          {!platformHome && (onOpenGeoTrends || onOpenGeoCompetitors) ? <Space wrap>
            {onOpenGeoTrends ? <Button htmlType="button" onClick={onOpenGeoTrends}>查看已有 GEO 趋势</Button> : null}
            {onOpenGeoCompetitors ? <Button htmlType="button" onClick={onOpenGeoCompetitors}>查看竞品洞察</Button> : null}
          </Space> : null}
          {activeArticleWorkflow ? (
            <button
              className="chat-active-article"
              type="button"
              aria-label={`返回进行中的文章：${activeArticleWorkflow.title}`}
              onClick={() => {
                const content = conversationContentRef.current
                const targets = content?.querySelectorAll<HTMLElement>(
                  `[data-article-workflow-id="${activeArticleWorkflow.articleId}"]`,
                )
                const target = targets ? Array.from(targets).at(-1) : undefined
                target?.scrollIntoView({ behavior: 'smooth', block: 'center' })
              }}
            >
              <FileTextOutlined className="chat-active-article__icon" />
              <span className="chat-active-article__label">文章进行中</span>
              <span className="chat-active-article__title">
                {activeArticleWorkflow.title}
              </span>
              <Tag color="processing">{activeArticleWorkflow.stageLabel}</Tag>
              <span className="chat-active-article__action">
                {activeArticleWorkflow.nextActionLabel}
                <RightOutlined />
              </span>
            </button>
          ) : null}
          </div>
        </header> : null}

        <div className="chat-conversation__viewport">
        <div
          ref={conversationContentRef}
          className="chat-conversation__content"
          onScroll={(event) => {
            const content = event.currentTarget
            followLatestRef.current =
              content.scrollHeight - content.scrollTop - content.clientHeight <=
              96
            setScrollState({ sessionId: selectedSessionId, following: followLatestRef.current })
          }}
        >
          {hasEarlierMessages ? <Button htmlType="button" loading={earlierMessagesLoading} onClick={onLoadEarlierMessages}>加载更早消息</Button> : null}
          {workspaceError ? (
            <Alert
              className="chat-workspace__error"
              type="error"
              showIcon
              title={workspaceError}
            />
          ) : null}
          {stageTransition ? (
            <ChatStageTransitionBanner transition={stageTransition} />
          ) : null}
          {messagesLoading ? (
            <Spin
              className="chat-workspace__loading"
              description="正在加载消息…"
            />
          ) : null}
          {messages.length === 0 && pendingReply ? <GeoPendingReply /> : messages.length === 0 && !messagesLoading && !workspaceError && emptyWelcome ? emptyWelcome : messages.length === 0 && platformHome && platformWelcomeTitle ? (
            <ChatEmptyWelcome
              recommendations={recommendations}
              onRecommendationSelect={onRecommendationSelect}
              sendEnabled={sendEnabled}
              genericAgent
              title={platformWelcomeTitle}
              description={platformWelcomeDescription}
              assistantAvatarVariant={assistantAvatarVariant}
            />
          ) : messages.length === 0 && platformHome ? <div className="platform-home-welcome"><Typography.Paragraph type="secondary">直接告诉我你想完成的事，例如“提高品牌可见度”。项目不是开始任务的前提。</Typography.Paragraph></div> : messages.length === 0 ? (
            <ChatEmptyWelcome
              recommendations={recommendations}
              onRecommendationSelect={onRecommendationSelect}
              sendEnabled={sendEnabled}
              genericAgent={recommendations.length === 0}
              assistantAvatarVariant={assistantAvatarVariant}
            />
          ) : (
            <ChatMessageStream
              assistantAvatarVariant={assistantAvatarVariant}
              renderGeoDiagnosticCard={renderGeoDiagnosticCard}
              messages={messages}
              preparingGeoArticleMessageId={preparingGeoArticleMessageId}
              geoArticleDirections={geoArticleDirections}
              onGeoArticleDirectionChange={onGeoArticleDirectionChange}
              onGeoReportAction={onGeoReportAction}
              onOpenArticlePreparation={onOpenArticlePreparation}
              onOpenGeoTopic={onOpenGeoTopic}
              onSelectClarificationOption={onSelectClarificationOption}
              onSelectResourceCandidate={onSelectResourceCandidate}
              resourceSelectionBusy={resourceSelectionBusy}
              onEditPaidTaskConfirmation={onEditPaidTaskConfirmation}
              onConfirmPaidTaskConfirmation={onConfirmPaidTaskConfirmation}
              onOpenRecharge={onOpenRecharge}
              onPrepareReestimate={onPrepareReestimate}
              onOpenPaidTaskReferenceAsset={onOpenPaidTaskReferenceAsset}
              onAddPaidTaskReferenceAsset={onAddPaidTaskReferenceAsset}
              onViewTask={onViewTask}
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
              recommendedSubmissionBusyArticleId={
                recommendedSubmissionBusyArticleId
              }
              recommendedSubmissionBlockedArticleIds={
                recommendedSubmissionBlockedArticleIds
              }
              onConfirmRecommendedSubmission={
                onConfirmRecommendedSubmission
              }
              onRefreshMediaRecommendations={onRefreshMediaRecommendations}
              onPreviewGeneratedImage={onPreviewGeneratedImage}
              onDownloadGeneratedImage={onDownloadGeneratedImage}
              onSaveGeneratedImageToAssets={onSaveGeneratedImageToAssets}
              onSelectGeneratedImage={onSelectGeneratedImage}
              onOpenGeneratedVideo={onOpenGeneratedVideo}
              onRefreshGeneratedVideo={onRefreshGeneratedVideo}
              onTaskOutcomeAction={onTaskOutcomeAction}
            />
          )}
        </div>
        {!followingLatest ? <Button className="chat-conversation__latest" shape="circle" icon={<ArrowDownOutlined aria-hidden />} aria-label="回到最新消息" title="回到最新消息" htmlType="button" onClick={() => {
          const content = conversationContentRef.current
          if (content) content.scrollTop = content.scrollHeight
          followLatestRef.current = true
          setScrollState({ sessionId: selectedSessionId, following: true })
        }} /> : null}
        </div>
        {composerNotice ? <Alert className="chat-composer__notice" type="info" showIcon title={composerNotice} /> : null}
        {geoReportSourceLoading || geoReportSourceNotice ? <Alert className="chat-composer__notice chat-geo-source"
          type={geoReportSourceNotice ? 'warning' : 'info'} showIcon
          title={geoReportSourceLoading ? '正在核对报告…' : geoReportSourceNotice}
          action={<Button onClick={onRemoveGeoReportSource}>移除引用</Button>} /> : null}
        {onRestorePendingMessage ? <Button className="chat-conversation__restore" htmlType="button" onClick={onRestorePendingMessage}>恢复上次未确认消息</Button> : null}
        <ChatComposer
          onViewTaskProgress={hasTaskContext || taskActions ? () => setOpenPanel('context') : undefined}
          onChooseGeoReport={onChooseGeoReport}
          onCreatePeriodReport={onCreatePeriodReport}
          onCreateDiagnosticReport={onCreateDiagnosticReport}
          reportReference={geoReportSourceLabel ? <div className="chat-report-reference" aria-label="当前对话引用的监测报告">
            <FileTextOutlined aria-hidden="true" />
            <button className="chat-report-reference__title" type="button" onClick={onViewGeoReportSource} title={`当前引用：${geoReportSourceLabel}。后续消息继续引用，可更换或移除。`}>
              当前引用：{geoReportSourceLabel}
            </button>
            {onChooseGeoReport ? <Button size="small" type="text" htmlType="button" onClick={onChooseGeoReport}>更换</Button> : null}
            <Button size="small" type="text" htmlType="button" icon={<CloseOutlined />} aria-label="移除报告引用" onClick={onRemoveGeoReportSource} />
          </div> : null}
          value={draft}
          isGenerating={isGenerating}
          sendEnabled={sendEnabled}
          sendFeedback={sendFeedback}
          attachments={currentAttachments}
          hasSelectedProduct={Boolean(selectedProductId)}
          attachmentUploadEnabled={attachmentUploadEnabled}
          attachmentUploadDisabledReason={attachmentUploadDisabledReason}
          onChange={onDraftChange}
          onSend={onSend}
          onStop={onStop}
          onStopAndSend={onStopAndSend}
          stopPending={stopPending}
          onSelectAttachmentFiles={onSelectAttachmentFiles}
          onChooseAttachmentAssets={onChooseAttachmentAssets}
          onRemoveAttachment={onRemoveAttachment}
          onAddProjectDocument={onAddProjectDocument}
          onAddProjectExcerpt={onAddProjectExcerpt}
          onOpenProjectKnowledge={onOpenProjectKnowledge}
        />
      </section>

      <Drawer
        rootClassName="chat-home-context-drawer"
        title="任务进度"
        open={openPanel === 'context'}
        onClose={() => setOpenPanel(undefined)}
        size="min(380px, 100vw)"
        forceRender
        styles={{ body: { padding: 0 } }}
      >
        <div id="chat-context-panel">
          {taskActions ? <div className="chat-task-actions">{taskActions}</div> : null}
          {renderContextPanel()}
        </div>
      </Drawer>
    </section>
  )

  function renderContextPanel() {
    return (
      <ChatContextPanel
        taskFocusOptions={taskFocusOptions}
        taskStatusSummaries={taskStatusSummaries}
        selectedTaskId={selectedTaskId}
        currentTaskContext={currentTaskContext}
        historicalTaskContext={historicalTaskContext}
        historicalContextExpanded={historicalContextExpanded}
        onSelectTaskFocus={handleSelectTaskFocus}
        onToggleHistoricalContext={onToggleHistoricalContext}
        onOpenReferenceAsset={onOpenReferenceAsset}
      />
    )
  }
}

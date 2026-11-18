import {
  Alert,
  Card,
  Descriptions,
  Divider,
  Drawer,
  Progress,
  Tag,
  Typography,
} from 'antd'
import { EmptyState, ErrorState, PageLoading } from '../../../components/GlobalState'
import { diagnosticTraceDescription } from '../../../shared/tracePresentation'
import { GeneratedImageResultGallery } from '../../chat/components/GeneratedImageResultGallery'
import { GeneratedVideoResultPlayer } from '../../chat/components/GeneratedVideoResultPlayer'
import type { GeneratedVideoPlaybackSourceView } from '../../chat'
import type { LiveMediaSubmissionListItemView } from '../../articles/types'
import {
  TASK_CENTER_TYPE_LABELS,
  formatTaskCenterPoints,
  formatTaskCenterProgress,
  normalizeTaskCenterPercent,
  taskCenterSettlementPoints,
} from '../presentation'
import type {
  TaskCenterActionType,
  TaskCenterId,
  TaskCenterResultItemView,
  TaskCenterTaskDetailView,
  TaskCenterViewError,
} from '../types'
import { TaskActionButtons } from './TaskActionButtons'
import { TaskCenterMediaSubmissions } from './TaskCenterMediaSubmissions'
import { TaskStatusTag } from './TaskStatusTag'

export interface TaskCenterDetailDrawerProps {
  open: boolean
  task: TaskCenterTaskDetailView | null
  loading: boolean
  error: TaskCenterViewError | null
  onClose: () => void
  onAction: (taskId: TaskCenterId, actionType: TaskCenterActionType) => void
  onRetry?: () => void
  onPreviewGeneratedImage?: (assetId: string) => void
  onDownloadGeneratedImage?: (assetId: string) => void
  onOpenGeneratedImageAsset?: (assetId: string) => void
  onOpenGeneratedVideo?: (assetId: string) => void
  onRefreshGeneratedVideo?: (
    assetId: string,
  ) => Promise<GeneratedVideoPlaybackSourceView>
  resultActionError?: TaskCenterViewError | null
  mediaSubmissions?: readonly LiveMediaSubmissionListItemView[]
  mediaSubmissionsLoading?: boolean
  mediaSubmissionsRefreshing?: boolean
  mediaSubmissionsError?: TaskCenterViewError | null
  onRetryMediaSubmissions?: () => void
  onRefreshMediaSubmissions?: () => void
  onOpenArticle?: (articleId: string) => void
}

function TaskCenterDetailProgress({ task }: { task: TaskCenterTaskDetailView }) {
  if (!task.progress) {
    return <Typography.Text type="secondary">—</Typography.Text>
  }

  const percent = normalizeTaskCenterPercent(task.progress.percent)
  const unitProgress = formatTaskCenterProgress(
    task.progress.completedUnits,
    task.progress.totalUnits,
  )

  return (
    <div className="task-center-detail-progress">
      <Typography.Text>{unitProgress}</Typography.Text>
      {percent !== undefined ? <Progress percent={percent} size="small" /> : null}
    </div>
  )
}

function TaskCenterPoints({ task }: { task: TaskCenterTaskDetailView }) {
  if (!task.points) {
    return null
  }

  return (
    <section className="task-center-detail-section" aria-labelledby="task-points-title">
      <Typography.Title id="task-points-title" level={4}>
        积分结算
      </Typography.Title>
      <div className="task-center-points-grid">
        <Card size="small">
          <Typography.Text type="secondary">报价积分</Typography.Text>
          <Typography.Text strong>
            {formatTaskCenterPoints(task.points.quotedPoints)}
          </Typography.Text>
        </Card>
        <Card size="small">
          <Typography.Text type="secondary">冻结积分</Typography.Text>
          <Typography.Text strong>
            {formatTaskCenterPoints(task.points.frozenPoints)}
          </Typography.Text>
        </Card>
        <Card size="small">
          <Typography.Text type="secondary">已结算积分</Typography.Text>
          <Typography.Text strong>
            {taskCenterSettlementPoints(task.points.settledPoints, task.status)}
          </Typography.Text>
        </Card>
        <Card size="small">
          <Typography.Text type="secondary">退回积分</Typography.Text>
          <Typography.Text strong>
            {task.points.returnedPoints === undefined
              ? taskCenterSettlementPoints(undefined, task.status)
              : formatTaskCenterPoints(task.points.returnedPoints)}
          </Typography.Text>
        </Card>
      </div>
    </section>
  )
}

function TaskCenterSubtasks({ task }: { task: TaskCenterTaskDetailView }) {
  if (!task.subtasks?.length) {
    return null
  }

  return (
    <section className="task-center-detail-section" aria-labelledby="task-subtasks-title">
      <Typography.Title id="task-subtasks-title" level={4}>
        子任务
      </Typography.Title>
      <ul className="task-center-subtask-list">
        {task.subtasks.map((subtask) => (
          <li key={subtask.id} className="task-center-subtask">
            <div className="task-center-subtask__heading">
              <Typography.Text strong>{subtask.label}</Typography.Text>
              <TaskStatusTag status={subtask.status} />
            </div>
            {subtask.stage ? (
              <Typography.Text type="secondary">
                当前阶段：{subtask.stage.label}
              </Typography.Text>
            ) : null}
            {subtask.resultSummary ? (
              <Typography.Text>{subtask.resultSummary}</Typography.Text>
            ) : null}
            {subtask.errorMessage ? (
              <Alert
                className="task-center-subtask__error"
                type="error"
                showIcon
                title={subtask.errorMessage}
              />
            ) : null}
          </li>
        ))}
      </ul>
    </section>
  )
}

function TaskCenterResultItem({ item }: { item: TaskCenterResultItemView }) {
  return (
    <li className="task-center-result-item">
      <div>
        <Typography.Text>{item.label}</Typography.Text>
        {item.value ? (
          <Typography.Text type="secondary">{item.value}</Typography.Text>
        ) : null}
      </div>
      {item.status ? <TaskStatusTag status={item.status} /> : null}
    </li>
  )
}

function TaskCenterResult({
  task,
  onPreviewGeneratedImage,
  onDownloadGeneratedImage,
  onOpenGeneratedImageAsset,
  onOpenGeneratedVideo,
  onRefreshGeneratedVideo,
  actionError,
}: {
  task: TaskCenterTaskDetailView
  onPreviewGeneratedImage: (assetId: string) => void
  onDownloadGeneratedImage: (assetId: string) => void
  onOpenGeneratedImageAsset: (assetId: string) => void
  onOpenGeneratedVideo: (assetId: string) => void
  onRefreshGeneratedVideo?: (
    assetId: string,
  ) => Promise<GeneratedVideoPlaybackSourceView>
  actionError?: TaskCenterViewError | null
}) {
  if (
    !task.resultSummary &&
    !task.generatedImageResultGroup &&
    !task.generatedVideoResult
  ) {
    return null
  }

  return (
    <section className="task-center-detail-section" aria-labelledby="task-result-title">
      <Typography.Title id="task-result-title" level={4}>
        {task.resultSummary?.title ?? '任务结果'}
      </Typography.Title>
      {task.resultSummary?.summary ? (
        <Typography.Paragraph>{task.resultSummary.summary}</Typography.Paragraph>
      ) : null}
      {task.resultSummary?.items?.length ? (
        <ul className="task-center-result-list">
          {task.resultSummary.items.map((item) => (
            <TaskCenterResultItem key={item.id} item={item} />
          ))}
        </ul>
      ) : null}
      {task.generatedImageResultGroup ? (
        <GeneratedImageResultGallery
          group={task.generatedImageResultGroup}
          onPreviewGeneratedImage={onPreviewGeneratedImage}
          onDownloadGeneratedImage={onDownloadGeneratedImage}
          onSaveGeneratedImageToAssets={onOpenGeneratedImageAsset}
          onSelectGeneratedImage={() => undefined}
        />
      ) : null}
      {task.generatedVideoResult ? (
        <GeneratedVideoResultPlayer
          video={task.generatedVideoResult}
          onOpenOriginal={onOpenGeneratedVideo}
          onRefreshPlaybackUrl={onRefreshGeneratedVideo}
        />
      ) : null}
      {actionError ? (
        <Alert
          type="error"
          showIcon
          title={actionError.message}
          description={diagnosticTraceDescription(actionError.traceId)}
        />
      ) : null}
    </section>
  )
}

function TaskCenterTimeline({ task }: { task: TaskCenterTaskDetailView }) {
  if (!task.timeline?.length) {
    return null
  }

  return (
    <section className="task-center-detail-section" aria-labelledby="task-timeline-title">
      <Typography.Title id="task-timeline-title" level={4}>
        任务进程
      </Typography.Title>
      <ol className="task-center-timeline">
        {task.timeline.map((event) => (
          <li key={event.id} className="task-center-timeline__item">
            <span className="task-center-timeline__marker" aria-hidden="true" />
            <div>
              <div className="task-center-timeline__heading">
                <Typography.Text strong>{event.label}</Typography.Text>
                {event.status ? <TaskStatusTag status={event.status} /> : null}
              </div>
              {event.occurredAt ? (
                <Typography.Text type="secondary">{event.occurredAt}</Typography.Text>
              ) : null}
              {event.description ? (
                <Typography.Paragraph type="secondary">
                  {event.description}
                </Typography.Paragraph>
              ) : null}
            </div>
          </li>
        ))}
      </ol>
    </section>
  )
}

function TaskCenterDetailContent({
  task,
  onAction,
  onPreviewGeneratedImage,
  onDownloadGeneratedImage,
  onOpenGeneratedImageAsset,
  onOpenGeneratedVideo,
  onRefreshGeneratedVideo,
  resultActionError,
  mediaSubmissions = [],
  mediaSubmissionsLoading = false,
  mediaSubmissionsRefreshing = false,
  mediaSubmissionsError = null,
  onRetryMediaSubmissions = () => undefined,
  onRefreshMediaSubmissions,
  onOpenArticle = () => undefined,
}: {
  task: TaskCenterTaskDetailView
  onAction: (taskId: TaskCenterId, actionType: TaskCenterActionType) => void
  onPreviewGeneratedImage: (assetId: string) => void
  onDownloadGeneratedImage: (assetId: string) => void
  onOpenGeneratedImageAsset: (assetId: string) => void
  onOpenGeneratedVideo: (assetId: string) => void
  onRefreshGeneratedVideo?: (
    assetId: string,
  ) => Promise<GeneratedVideoPlaybackSourceView>
  resultActionError?: TaskCenterViewError | null
  mediaSubmissions?: readonly LiveMediaSubmissionListItemView[]
  mediaSubmissionsLoading?: boolean
  mediaSubmissionsRefreshing?: boolean
  mediaSubmissionsError?: TaskCenterViewError | null
  onRetryMediaSubmissions?: () => void
  onRefreshMediaSubmissions?: () => void
  onOpenArticle?: (articleId: string) => void
}) {
  return (
    <div className="task-center-detail">
      <header className="task-center-detail__heading">
        <div>
          <Typography.Title level={3}>{task.name || '—'}</Typography.Title>
          <Tag>{TASK_CENTER_TYPE_LABELS[task.taskType]}</Tag>
        </div>
        <TaskStatusTag status={task.status} />
      </header>

      {task.failureReason ? (
        <Alert
          type={task.status === 'RETRY_WAIT' ? 'warning' : 'error'}
          showIcon
          title={task.status === 'RETRY_WAIT' ? '任务等待重试' : '失败原因'}
          description={task.failureReason}
        />
      ) : null}

      <Descriptions className="task-center-detail__facts" column={1} size="small">
        <Descriptions.Item label="任务编号">
          {task.taskNumber || '—'}
        </Descriptions.Item>
        <Descriptions.Item label="关联对象">
          {task.relatedObject?.label || '—'}
        </Descriptions.Item>
        {task.businessSpaceName ? (
          <Descriptions.Item label="经营空间">
            {task.businessSpaceName}
          </Descriptions.Item>
        ) : null}
        {task.productName ? (
          <Descriptions.Item label="商品">{task.productName}</Descriptions.Item>
        ) : null}
        <Descriptions.Item label="当前阶段">
          {task.stage?.label || '—'}
        </Descriptions.Item>
        <Descriptions.Item label="任务进度">
          <TaskCenterDetailProgress task={task} />
        </Descriptions.Item>
        <Descriptions.Item label="创建时间">{task.createdAt}</Descriptions.Item>
        {task.startedAt ? (
          <Descriptions.Item label="开始时间">{task.startedAt}</Descriptions.Item>
        ) : null}
        {task.finishedAt ? (
          <Descriptions.Item label="完成时间">{task.finishedAt}</Descriptions.Item>
        ) : null}
        {task.durationLabel ? (
          <Descriptions.Item label="耗时">{task.durationLabel}</Descriptions.Item>
        ) : null}
      </Descriptions>

      <Divider />
      <TaskCenterTimeline task={task} />
      <TaskCenterSubtasks task={task} />
      <TaskCenterPoints task={task} />
      <TaskCenterResult
        task={task}
        onPreviewGeneratedImage={onPreviewGeneratedImage}
        onDownloadGeneratedImage={onDownloadGeneratedImage}
        onOpenGeneratedImageAsset={onOpenGeneratedImageAsset}
        onOpenGeneratedVideo={onOpenGeneratedVideo}
        onRefreshGeneratedVideo={onRefreshGeneratedVideo}
        actionError={resultActionError}
      />
      {task.taskType === 'MEDIA' ? (
        <TaskCenterMediaSubmissions
          submissions={mediaSubmissions}
          loading={mediaSubmissionsLoading}
          refreshing={mediaSubmissionsRefreshing}
          error={mediaSubmissionsError}
          onRetry={onRetryMediaSubmissions}
          onRefresh={onRefreshMediaSubmissions}
          onOpenArticle={onOpenArticle}
        />
      ) : null}

      {task.actions?.length ? (
        <div className="task-center-detail__actions" aria-label="任务操作">
          <TaskActionButtons
            taskId={task.id}
            actions={task.actions}
            onAction={onAction}
          />
        </div>
      ) : null}
    </div>
  )
}

export function TaskCenterDetailDrawer({
  open,
  task,
  loading,
  error,
  onClose,
  onAction,
  onRetry,
  onPreviewGeneratedImage = () => undefined,
  onDownloadGeneratedImage = () => undefined,
  onOpenGeneratedImageAsset = () => undefined,
  onOpenGeneratedVideo = () => undefined,
  onRefreshGeneratedVideo,
  resultActionError,
  mediaSubmissions,
  mediaSubmissionsLoading,
  mediaSubmissionsRefreshing,
  mediaSubmissionsError,
  onRetryMediaSubmissions,
  onRefreshMediaSubmissions,
  onOpenArticle,
}: TaskCenterDetailDrawerProps) {
  return (
    <Drawer
      className="task-center-detail-drawer"
      title="任务详情"
      size={680}
      open={open}
      onClose={onClose}
    >
      {loading ? (
        <PageLoading label="正在加载任务详情…" />
      ) : error ? (
        <ErrorState
          message={error.message}
          traceId={error.traceId}
          onRetry={onRetry}
        />
      ) : task ? (
        <TaskCenterDetailContent
          task={task}
          onAction={onAction}
          onPreviewGeneratedImage={onPreviewGeneratedImage}
          onDownloadGeneratedImage={onDownloadGeneratedImage}
          onOpenGeneratedImageAsset={onOpenGeneratedImageAsset}
          onOpenGeneratedVideo={onOpenGeneratedVideo}
          onRefreshGeneratedVideo={onRefreshGeneratedVideo}
          resultActionError={resultActionError}
          mediaSubmissions={mediaSubmissions}
          mediaSubmissionsLoading={mediaSubmissionsLoading}
          mediaSubmissionsRefreshing={mediaSubmissionsRefreshing}
          mediaSubmissionsError={mediaSubmissionsError}
          onRetryMediaSubmissions={onRetryMediaSubmissions}
          onRefreshMediaSubmissions={onRefreshMediaSubmissions}
          onOpenArticle={onOpenArticle}
        />
      ) : (
        <EmptyState
          title="暂无可查看的任务详情"
          description="请选择任务后查看执行阶段、子任务和积分结算。"
        />
      )}
    </Drawer>
  )
}

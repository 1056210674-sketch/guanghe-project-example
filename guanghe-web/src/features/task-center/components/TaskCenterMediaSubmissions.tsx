import { ReloadOutlined } from '@ant-design/icons'
import { Button, Empty, Tag, Typography } from 'antd'
import { ErrorState, PageLoading } from '../../../components/GlobalState'
import { PublishedMediaLinks } from '../../articles/components/PublishedMediaLinks'
import type { LiveMediaSubmissionListItemView } from '../../articles/types'
import type { TaskCenterViewError } from '../types'

export interface TaskCenterMediaSubmissionsProps {
  submissions: readonly LiveMediaSubmissionListItemView[]
  loading: boolean
  refreshing?: boolean
  error: TaskCenterViewError | null
  onRetry: () => void
  onRefresh?: () => void
  onOpenArticle: (articleId: string) => void
}

export function TaskCenterMediaSubmissions({
  submissions,
  loading,
  refreshing = false,
  error,
  onRetry,
  onRefresh,
  onOpenArticle,
}: TaskCenterMediaSubmissionsProps) {
  return (
    <section className="task-center-detail-section" aria-label="媒体投稿记录">
      <div className="task-center-media-submissions__heading">
        <Typography.Title level={4}>媒体投稿记录</Typography.Title>
        {onRefresh ? (
          <Button
            icon={<ReloadOutlined aria-hidden="true" />}
            loading={refreshing}
            disabled={refreshing}
            onClick={onRefresh}
          >
            刷新投稿状态
          </Button>
        ) : null}
      </div>
      {error ? (
        <ErrorState message={error.message} traceId={error.traceId} onRetry={onRetry} />
      ) : loading ? (
        <PageLoading label="正在加载投稿记录…" />
      ) : submissions.length === 0 ? (
        <Empty description="暂无该任务的投稿记录" />
      ) : (
        <ul className="task-center-media-submissions">
          {submissions.map((submission) => (
            <li key={submission.id}>
              <div className="task-center-media-submissions__heading">
                <Typography.Text strong>{submission.mediaName}</Typography.Text>
                <Tag>{submission.statusLabel}</Tag>
                <Button type="link" onClick={() => onOpenArticle(submission.articleId)}>
                  查看文章
                </Button>
              </div>
              <PublishedMediaLinks
                urls={submission.publishedUrls}
                published={submission.status === 'PUBLISHED'}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

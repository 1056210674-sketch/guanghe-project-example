import {
  ClockCircleOutlined,
  FileTextOutlined,
  GlobalOutlined,
} from '@ant-design/icons'
import { Typography } from 'antd'
import type { ArticleMediaSubmissionDetailsView } from '../types'

export interface ArticleMediaSubmissionDetailsProps {
  details: ArticleMediaSubmissionDetailsView
}

export function ArticleMediaSubmissionDetails({
  details,
}: ArticleMediaSubmissionDetailsProps) {
  const { articleSnapshot, targets } = details

  return (
    <section
      className="article-media-submission-details"
      aria-label={`文章媒体投稿明细：${articleSnapshot.title}`}
    >
      <Typography.Text strong>投稿明细</Typography.Text>

      <div
        className="article-media-submission-details__snapshot"
        aria-label="文章版本快照"
      >
        <span
          className="article-media-submission-details__snapshot-icon"
          aria-hidden="true"
        >
          <FileTextOutlined />
        </span>
        <div className="article-media-submission-details__snapshot-copy">
          <Typography.Text strong>{articleSnapshot.title}</Typography.Text>
          <div>
            <Typography.Text type="secondary">
              {articleSnapshot.versionLabel}
            </Typography.Text>
            {articleSnapshot.generatedAt ? (
              <Typography.Text type="secondary">
                {articleSnapshot.generatedAt}
              </Typography.Text>
            ) : null}
          </div>
        </div>
      </div>

      <div className="article-media-submission-details__targets">
        <Typography.Text strong>已选媒体</Typography.Text>

        {targets.length === 0 ? (
          <div
            className="article-media-submission-details__empty"
            role="status"
          >
            <GlobalOutlined aria-hidden="true" />
            <span>暂无已选媒体</span>
          </div>
        ) : (
          <ul
            className="article-media-submission-details__target-list"
            aria-label="已选媒体"
          >
            {targets.map((target) => (
              <li
                key={target.id}
                className="article-media-submission-details__target"
              >
                <span
                  className="article-media-submission-details__target-icon"
                  aria-hidden="true"
                >
                  <GlobalOutlined />
                </span>
                <Typography.Text strong>{target.name}</Typography.Text>
                {target.estimatedTimeLabel ? (
                  <Typography.Text
                    className="article-media-submission-details__estimated-time"
                    type="secondary"
                  >
                    <ClockCircleOutlined aria-hidden="true" />
                    预计时效：{target.estimatedTimeLabel}
                  </Typography.Text>
                ) : null}
                {target.saleAmountLabel ? (
                  <Typography.Text type="secondary">
                    本稿总价：{target.saleAmountLabel}
                  </Typography.Text>
                ) : null}
                {target.imageFeeLabel ? <Typography.Text type="secondary">{target.imageFeeLabel}</Typography.Text> : null}
                {target.chargedPoints !== undefined ? (
                  <Typography.Text type="secondary">
                    {target.chargedPoints.toLocaleString('zh-CN')} 积分
                  </Typography.Text>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}

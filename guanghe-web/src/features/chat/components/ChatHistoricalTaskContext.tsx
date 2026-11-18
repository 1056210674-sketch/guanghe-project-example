import { DownOutlined, RightOutlined } from '@ant-design/icons'
import { Button, Tag, Typography } from 'antd'
import type { ChatHistoricalContextView } from '../types'
import { ChatReferenceAssetButton } from './ChatReferenceAssetButton'

const HISTORICAL_CONTEXT_NOTICE = '历史任务参数不参与本次任务'

export interface ChatHistoricalTaskContextProps {
  context: ChatHistoricalContextView
  expanded?: boolean
  onToggle: (contextId: string, expanded: boolean) => void
  onOpenReferenceAsset: (assetId: string) => void
}

export function ChatHistoricalTaskContext({
  context,
  expanded = false,
  onToggle,
  onOpenReferenceAsset,
}: ChatHistoricalTaskContextProps) {
  const contentId = `chat-historical-context-${context.id}`

  return (
    <section
      className="chat-historical-task-context"
      aria-label={`历史任务参数：${context.title}`}
    >
      <Typography.Text className="chat-historical-task-context__eyebrow">
        历史任务参数（不参与本次任务）
      </Typography.Text>
      <Button
        block
        className="chat-historical-task-context__toggle"
        aria-expanded={expanded}
        aria-controls={contentId}
        icon={
          expanded ? (
            <DownOutlined aria-hidden="true" />
          ) : (
            <RightOutlined aria-hidden="true" />
          )
        }
        iconPlacement="end"
        onClick={() => onToggle(context.id, !expanded)}
      >
        <span>
          <strong>{context.title}</strong>
          <Tag>{context.statusLabel}</Tag>
        </span>
      </Button>

      {expanded ? (
        <div id={contentId} className="chat-historical-task-context__content">
          <Typography.Paragraph className="chat-historical-task-context__notice">
            {HISTORICAL_CONTEXT_NOTICE}
          </Typography.Paragraph>
          {context.summaryRows.length > 0 ? (
            <dl className="chat-historical-task-context__details">
              {context.summaryRows.map((row, index) => (
                <div key={`${index}-${row.label}-${row.value}`}>
                  <dt>{row.label}</dt>
                  <dd>{row.value}</dd>
                </div>
              ))}
            </dl>
          ) : null}
          {context.referenceAssets?.length ? (
            <div className="chat-historical-task-context__assets">
              <Typography.Text type="secondary">历史引用素材</Typography.Text>
              <div className="chat-reference-assets">
                {context.referenceAssets.map((asset) => (
                  <ChatReferenceAssetButton
                    key={`${asset.id}-${asset.thumbnailUrl ?? 'no-image'}`}
                    asset={asset}
                    onOpen={onOpenReferenceAsset}
                  />
                ))}
              </div>
            </div>
          ) : null}
        </div>
      ) : null}
    </section>
  )
}

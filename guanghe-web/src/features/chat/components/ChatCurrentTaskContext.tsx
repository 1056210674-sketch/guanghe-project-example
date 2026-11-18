import { Tag, Typography } from 'antd'
import type { ChatTaskContextView } from '../types'
import { ChatReferenceAssetButton } from './ChatReferenceAssetButton'

export interface ChatCurrentTaskContextProps {
  context: ChatTaskContextView
  onOpenReferenceAsset: (assetId: string) => void
}

export function ChatCurrentTaskContext({
  context,
  onOpenReferenceAsset,
}: ChatCurrentTaskContextProps) {
  return (
    <section
      className="chat-current-task-context"
      aria-label={`当前任务参数：${context.title}`}
    >
      <header className="chat-current-task-context__header">
        <Typography.Text type="secondary">当前意图</Typography.Text>
        <Typography.Title level={3}>{context.title}</Typography.Title>
      </header>

      {context.sourceLabel ? (
        <div className="chat-current-task-context__source">
          <Typography.Text type="secondary">参数来源</Typography.Text>
          <Tag>{context.sourceLabel}</Tag>
        </div>
      ) : null}

      {context.detailRows.length > 0 ? (
        <div className="chat-current-task-context__parameters">
          <Typography.Text type="secondary">本次任务参数</Typography.Text>
          <dl className="chat-current-task-context__details">
            {context.detailRows.map((row, index) => (
              <div key={`${index}-${row.label}-${row.value}`}>
                <dt>{row.label}</dt>
                <dd>{row.value}</dd>
              </div>
            ))}
          </dl>
        </div>
      ) : null}

      {context.referenceAssets?.length ? (
        <div className="chat-current-task-context__assets">
          <Typography.Text type="secondary">当前引用素材</Typography.Text>
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
    </section>
  )
}

import { PictureOutlined } from '@ant-design/icons'
import { useState } from 'react'
import type { ChatReferenceAssetView } from '../types'

export interface ChatReferenceAssetButtonProps {
  asset: ChatReferenceAssetView
  onOpen: (assetId: string) => void
}

export function ChatReferenceAssetButton({
  asset,
  onOpen,
}: ChatReferenceAssetButtonProps) {
  const [loadFailed, setLoadFailed] = useState(false)

  return (
    <button
      type="button"
      className="chat-reference-asset"
      aria-label={`查看素材：${asset.name}`}
      onClick={() => onOpen(asset.id)}
    >
      {asset.thumbnailUrl && !loadFailed ? (
        <img
          src={asset.thumbnailUrl}
          alt=""
          width={48}
          height={48}
          loading="lazy"
          onError={() => setLoadFailed(true)}
        />
      ) : (
        <span className="chat-reference-asset__placeholder" aria-hidden="true">
          <PictureOutlined />
        </span>
      )}
      <span>{asset.name}</span>
    </button>
  )
}

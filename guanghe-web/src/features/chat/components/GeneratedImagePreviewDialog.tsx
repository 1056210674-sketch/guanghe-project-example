import { Alert, Button, Modal, Space, Spin, Typography } from 'antd'
import { useState } from 'react'

export interface GeneratedImagePreviewDialogProps {
  name: string
  url?: string
  loading: boolean
  error: boolean
  onRetry: () => void
  onClose: () => void
}

export function GeneratedImagePreviewDialog({
  name, url, loading, error, onRetry, onClose,
}: GeneratedImagePreviewDialogProps) {
  const [originalSize, setOriginalSize] = useState(false)
  const [dimensions, setDimensions] = useState<{ width: number; height: number }>()
  const [failedUrl, setFailedUrl] = useState<string>()
  const imageFailed = Boolean(url && failedUrl === url)

  return (
    <Modal
      title="图片预览"
      open
      centered
      width="min(1120px, calc(100vw - 32px))"
      footer={null}
      onCancel={onClose}
      destroyOnHidden
    >
      <div className="generated-image-preview">
        <div className="generated-image-preview__toolbar">
          <Typography.Text ellipsis title={name}>{name}</Typography.Text>
          <Space wrap>
            {dimensions ? <Typography.Text type="secondary">{dimensions.width} × {dimensions.height} 像素</Typography.Text> : null}
            <Button size="small" aria-pressed={!originalSize} onClick={() => setOriginalSize(false)}>适应窗口</Button>
            <Button size="small" aria-pressed={originalSize} onClick={() => setOriginalSize(true)}>原始尺寸（1:1）</Button>
          </Space>
        </div>
        {error || imageFailed ? (
          <Alert
            type="warning"
            showIcon
            title="图片加载失败，请重试。"
            action={<Button onClick={() => { setFailedUrl(undefined); onRetry() }}>重新加载</Button>}
          />
        ) : loading || !url ? (
          <div className="generated-image-preview__loading" role="status"><Spin /> 正在加载原图…</div>
        ) : (
          <div
            className={`generated-image-preview__viewport${originalSize ? ' generated-image-preview__viewport--original' : ''}`}
            role="region"
            aria-label={originalSize ? '原始尺寸图片，可滚动查看' : '适应窗口图片'}
            tabIndex={0}
          >
            <img
              src={url}
              alt={`${name}原图`}
              onLoad={(event) => setDimensions({ width: event.currentTarget.naturalWidth, height: event.currentTarget.naturalHeight })}
              onError={() => setFailedUrl(url)}
            />
          </div>
        )}
      </div>
    </Modal>
  )
}

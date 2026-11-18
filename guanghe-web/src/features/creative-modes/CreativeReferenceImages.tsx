import { DeleteOutlined, UploadOutlined } from '@ant-design/icons'
import { Button, Typography } from 'antd'
import { useRef } from 'react'
import './creative-modes.css'

export interface CreativeReferenceImage {
  id: string
  name: string
  thumbnailUrl?: string
}

export function CreativeReferenceImages({
  images, disabled = false, uploading = false, maxSelection,
  onChoose, onUpload, onRemove,
}: {
  images: readonly CreativeReferenceImage[]
  disabled?: boolean
  uploading?: boolean
  maxSelection: number
  onChoose: () => void
  onUpload: (files: readonly File[]) => void
  onRemove: (assetId: string) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  return <section className="creative-source-panel" aria-label="本次参考图片">
    <Typography.Text>参考图片（{images.length}/{maxSelection}）</Typography.Text>
    <div className="creative-source-actions">
      <Button disabled={disabled || uploading} onClick={onChoose}>从资产中心选择</Button>
      <Button icon={<UploadOutlined />} disabled={disabled || images.length >= maxSelection} loading={uploading} onClick={() => input.current?.click()}>上传图片</Button>
      <input ref={input} aria-label="上传参考图片" hidden type="file" accept="image/png,image/jpeg" multiple={maxSelection > 1}
        onChange={(event) => {
          const files = Array.from(event.target.files ?? [])
          event.target.value = ''
          if (files.length) onUpload(files)
        }} />
    </div>
    <ul className="creative-source-images">
      {images.map((item) => <li key={item.id}>
        {item.thumbnailUrl ? <img src={item.thumbnailUrl} alt={item.name} /> : <span>{item.name}</span>}
        <Typography.Text ellipsis>{item.name}</Typography.Text>
        <Button size="small" icon={<DeleteOutlined />} aria-label={`移除参考图 ${item.name}`} disabled={disabled || uploading} onClick={() => onRemove(item.id)}>移除</Button>
      </li>)}
    </ul>
    <Typography.Text type="secondary">支持 PNG/JPEG，单张不超过 20 MiB；上传完成后才可获取报价。</Typography.Text>
  </section>
}

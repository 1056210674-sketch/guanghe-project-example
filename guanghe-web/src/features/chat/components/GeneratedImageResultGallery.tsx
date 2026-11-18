import {
  CheckCircleFilled,
  EyeOutlined,
  ExportOutlined,
  FolderAddOutlined,
  FolderOpenOutlined,
  LinkOutlined,
  PictureOutlined,
} from '@ant-design/icons'
import { Button, Tooltip, Typography } from 'antd'
import { useState } from 'react'
import type {
  GeneratedImageResultGroupView,
  GeneratedImageResultView,
} from '../types'

export interface GeneratedImageResultGalleryProps {
  group: GeneratedImageResultGroupView
  onPreviewGeneratedImage: (assetId: string) => void
  onDownloadGeneratedImage: (assetId: string) => void
  onSaveGeneratedImageToAssets: (assetId: string) => void
  onSelectGeneratedImage: (assetId: string) => void
  onAssociateGeneratedImageToProduct?: (assetId: string) => void
}

function GeneratedImagePreview({
  image,
}: {
  image: GeneratedImageResultView
}) {
  const [loadFailed, setLoadFailed] = useState(false)

  if (!image.imageUrl || loadFailed) {
    return (
      <span
        className="generated-image-result__placeholder"
        role="img"
        aria-label={`${image.name}预览暂不可用`}
      >
        <PictureOutlined aria-hidden="true" />
        <span>成果已保存，缩略图暂不可用。可点击“预览”重试。</span>
      </span>
    )
  }

  return (
    <img
      className="generated-image-result__image"
      src={image.imageUrl}
      alt={`${image.name}生成图片`}
      width={320}
      height={240}
      loading="lazy"
      onError={() => setLoadFailed(true)}
    />
  )
}

function GeneratedImageResultCard({
  image,
  selected,
  onPreview,
  onDownload,
  onSaveToAssets,
  onSelect,
  showProductAssociation,
  productAssociationEnabled,
  productAssociationDisabledReason,
  onAssociateToProduct,
}: {
  image: GeneratedImageResultView
  selected: boolean
  onPreview: (assetId: string) => void
  onDownload: (assetId: string) => void
  onSaveToAssets: (assetId: string) => void
  onSelect: (assetId: string) => void
  showProductAssociation: boolean
  productAssociationEnabled: boolean
  productAssociationDisabledReason?: string
  onAssociateToProduct: (assetId: string) => void
}) {
  const hasMetadata = Boolean(
    image.formatLabel || image.dimensionsLabel || image.expiresAt,
  )
  const saveLabel = image.persistedAsset ? '已保存到资产中心' : '保存到资产'
  const selectLabel = selected ? '已加入下一条素材' : '用这张继续创作'

  return (
    <li
      className={`generated-image-result${
        selected ? ' generated-image-result--selected' : ''
      }`}
    >
      <div className="generated-image-result__preview">
        <button
          type="button"
          className="generated-image-result__preview-button"
          aria-label={`预览${image.name}`}
          aria-haspopup="dialog"
          disabled={!image.previewEnabled}
          onClick={() => onPreview(image.assetId)}
        >
          <GeneratedImagePreview key={image.imageUrl ?? 'no-image'} image={image} />
        </button>
        {selected ? (
          <span
            className="generated-image-result__selected-mark"
            aria-label="已选择"
          >
            <CheckCircleFilled aria-hidden="true" />
          </span>
        ) : null}
      </div>

      <div className="generated-image-result__details">
        {image.candidateLabel ? <Typography.Text type="secondary">{image.candidateLabel}</Typography.Text> : null}
        <Typography.Text strong>{image.name}</Typography.Text>
        {hasMetadata ? (
          <div className="generated-image-result__metadata">
            {image.formatLabel ? <span>{image.formatLabel}</span> : null}
            {image.dimensionsLabel ? (
              <span>{image.dimensionsLabel}</span>
            ) : null}
            {image.expiresAt ? (
              <span>临时链接有效期至 {image.expiresAt}</span>
            ) : null}
            {image.safetyStatusLabel ? <span>{image.safetyStatusLabel}</span> : null}
          </div>
        ) : null}
      </div>

      <div className="generated-image-result__actions" role="group" aria-label="图片操作">
        <Tooltip title="预览">
          <span>
            <Button
              size="small"
              type="text"
              aria-label="预览"
              aria-haspopup="dialog"
              icon={<EyeOutlined aria-hidden="true" />}
              disabled={!image.previewEnabled}
              onClick={() => {
                if (image.previewEnabled) {
                  onPreview(image.assetId)
                }
              }}
            />
          </span>
        </Tooltip>
        <Tooltip title="打开原图">
          <span>
            <Button
              size="small"
              type="text"
              aria-label="打开原图"
              icon={<ExportOutlined aria-hidden="true" />}
              disabled={!image.downloadEnabled}
              onClick={() => {
                if (image.downloadEnabled) {
                  onDownload(image.assetId)
                }
              }}
            />
          </span>
        </Tooltip>
        <Tooltip title={saveLabel}>
          <span>
            <Button
              size="small"
              type="text"
              aria-label={saveLabel}
              icon={image.persistedAsset
                ? <FolderOpenOutlined aria-hidden="true" />
                : <FolderAddOutlined aria-hidden="true" />}
              disabled={!image.saveToAssetsEnabled}
              onClick={() => {
                if (image.saveToAssetsEnabled) {
                  onSaveToAssets(image.assetId)
                }
              }}
            />
          </span>
        </Tooltip>
        <Tooltip title={selectLabel}>
          <span>
            <Button
              size="small"
              className="generated-image-result__select"
              type="text"
              aria-label={selectLabel}
              icon={(
                <svg aria-hidden="true" focusable="false" width="1em" height="1em" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M5 7h6v6l-3 5H5l3-5H5V7Zm9 0h6v6l-3 5h-3l3-5h-3V7Z" />
                </svg>
              )}
              aria-pressed={selected}
              disabled={!image.selectEnabled}
              onClick={() => {
                if (image.selectEnabled) {
                  onSelect(image.assetId)
                }
              }}
            />
          </span>
        </Tooltip>
        {showProductAssociation ? (
          <Tooltip title={productAssociationDisabledReason || '关联到商品'}>
            <span>
              <Button
                size="small"
                type="text"
                aria-label="关联到商品"
                icon={<LinkOutlined aria-hidden="true" />}
                disabled={!productAssociationEnabled}
                onClick={() => {
                  if (productAssociationEnabled) {
                    onAssociateToProduct(image.assetId)
                  }
                }}
              />
            </span>
          </Tooltip>
        ) : null}
      </div>
    </li>
  )
}

export function GeneratedImageResultGallery({
  group,
  onPreviewGeneratedImage,
  onDownloadGeneratedImage,
  onSaveGeneratedImageToAssets,
  onSelectGeneratedImage,
  onAssociateGeneratedImageToProduct = () => undefined,
}: GeneratedImageResultGalleryProps) {
  if (group.images.length === 0) {
    return null
  }

  return (
    <section
      className="generated-image-results"
      aria-label={`图片生成结果：${group.title}`}
    >
      <Typography.Title level={3}>{group.title}</Typography.Title>
      <ul className="generated-image-results__gallery">
        {group.images.map((image) => (
          <GeneratedImageResultCard
            key={image.assetId}
            image={image}
            selected={image.assetId === group.selectedAssetId || Boolean(group.selectedAssetIds?.includes(image.assetId))}
            onPreview={onPreviewGeneratedImage}
            onDownload={onDownloadGeneratedImage}
            onSaveToAssets={onSaveGeneratedImageToAssets}
            onSelect={onSelectGeneratedImage}
            showProductAssociation={
              group.creativeSourceMode === 'CURRENT_ATTACHMENTS'
            }
            productAssociationEnabled={Boolean(
              group.productAssociationEnabled,
            )}
            productAssociationDisabledReason={
              group.productAssociationDisabledReason
            }
            onAssociateToProduct={onAssociateGeneratedImageToProduct}
          />
        ))}
      </ul>
    </section>
  )
}
